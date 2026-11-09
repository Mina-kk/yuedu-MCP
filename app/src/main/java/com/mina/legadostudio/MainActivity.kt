package com.mina.legadostudio

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import com.mina.legadostudio.service.McpService
import com.mina.legadostudio.ui.theme.ThemeMode
import com.mina.legadostudio.ui.theme.ThemeModeStore
import com.mina.legadostudio.web.SafHost
import com.mina.legadostudio.web.SafImportResult
import com.mina.legadostudio.web.SafLauncher
import com.mina.legadostudio.web.SafRequest
import com.mina.legadostudio.web.StudioWebBridge
import com.mina.legadostudio.web.VerificationBrowserView
import com.mina.legadostudio.web.WebBridgeProtocol

/**
 * 全屏 WebView 宿主（不使用 Compose 业务 UI）。
 *
 * 结构：`FrameLayout` → 主 WebView（Vue 应用，privileged bridge）+ 可选隔离验证 WebView（无 bridge）。
 * 所有业务控件（含验证工具栏、确认框、Toast 替代）都由 Web 渲染；本类只做：
 * - 加载 `https://appassets.androidplatform.net/assets/web/index.html`（WebViewAssetLoader，仅本地 assets）；
 * - 安装 [StudioWebBridge]（origin + main frame 双重校验）；
 * - Back 派发 `studio:back` 给前端；Insets 通过 `studio:insets` 事件与 CSS 变量下发；
 * - 隔离验证页的创建/关闭（toolbar 由前端覆盖，宿主只发 `studio:verification-browser` 事件）。
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var bridge: StudioWebBridge? = null
    private var verificationView: VerificationBrowserView? = null
    private var verificationSessionId: String? = null
    private lateinit var saf: SafLauncher
    private var unsupportedShown = false
    /** 验证页观察地址回写用的后台作用域（Activity 销毁即取消）。 */
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** 系统 Back 交给前端处理（先关弹层/草稿，再根页退出确认）。 */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // 验证页打开时，Back 先退回主 Web 控制台（关闭由前端 toolbar 或本回调触发）。
            if (verificationView != null) {
                closeVerificationBrowser(reason = "back")
                return
            }
            bridge?.emitBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
        }
        setContentView(root)

        saf = SafLauncher(this).also { it.register() }

        bridge = StudioWebBridge(
            activity = this,
            onExit = { finish() },
            onRequestCloseVerificationBrowser = { closeVerificationBrowser(reason = "host") },
            onThemeApplied = { dark -> applySystemBars(dark) },
        ).also { created ->
            created.attachSaf(object : SafHost {
                override suspend fun export(request: SafRequest.Export) {
                    saf.export(request.name, request.text, request.mime)
                }

                override suspend fun import(): SafImportResult {
                    val (name, text) = saf.import()
                    return SafImportResult(name, text)
                }
            })
            created.setVerificationBrowserOpener { session -> showVerificationBrowser(session.id, session.url) }
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
        installInsetsListener()
        applyThemeFromStore()
        buildWebView()
        consumeRoute(intent)
    }

    // --- WebView ---------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            // 只服务 app 内 assets/web/：不挂 AssetsPathHandler 根目录，避免把 skills/corpus 暴露给页面。
            .addPathHandler("/assets/web/", WebViewAssetLoader.AssetsPathHandler(this, "web"))
            .build()
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            }
            // 不允许任何全域 JS interface：业务能力只经 WebMessageListener。
            removeJavascriptInterface("Android")
            removeJavascriptInterface("studio")
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // 主页面只允许 appassets origin 内导航；外链一律不接管（不打开外部浏览器，
                // 避免把用户带离宿主后无路可回）。
                val url = request.url
                return url.scheme != "https" || url.host != "appassets.androidplatform.net"
            }

            @Deprecated("兼容旧 WebView")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !url.startsWith(WebBridgeProtocol.TRUSTED_ORIGIN)
        }
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            // 主页面同样不弹原生对话框：JS alert/confirm/prompt 一律拒绝并记录。
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                result?.cancel(); return true
            }
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                result?.cancel(); return true
            }
            override fun onJsPrompt(
                view: WebView, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?,
            ): Boolean {
                result?.cancel(); return true
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
        }
        root.addView(webView)
        val installed = bridge?.install(webView) ?: false
        if (!installed) {
            // 契约：不支持 WebMessageListener 时必须明确提示升级，绝不退回全域桥接。
            unsupportedShown = true
            webView.loadDataWithBaseURL(
                WebBridgeProtocol.TRUSTED_ORIGIN,
                UNSUPPORTED_HTML,
                "text/html",
                "utf-8",
                null,
            )
            return
        }
        webView.loadUrl(WebBridgeProtocol.INDEX_URL)
    }

    // --- Insets ----------------------------------------------------------

    private fun installInsetsListener() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bottom = maxOf(bars.bottom, ime)
            // 原生只负责给 Web 留出安全区（内容不被系统栏/键盘压住）；具体布局由 Vue 决定。
            view.setPadding(bars.left, bars.top, bars.right, bottom)
            bridge?.emitInsets(bars.top, bars.bottom, ime)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applySystemBars(dark: Boolean) {
        val controller = WindowInsetsControllerCompat(window, root)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        // 背景由 Web 绘制（透明窗口），这里只同步图标对比度，避免白底白字。
        window.decorView.setBackgroundColor(if (dark) Color.BLACK else Color.WHITE)
    }

    private fun applyThemeFromStore() {
        val mode = ThemeModeStore(this).load()
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        applySystemBars(ThemeMode.resolveDark(mode, systemDark))
    }

    // --- 隔离验证页 -------------------------------------------------------

    /**
     * 在宿主内嵌隔离 WebView。toolbar（关闭/完成）由主 Web UI 覆盖渲染：
     * 这里只创建无 bridge 的页面，并把状态变化以 `studio:verification-browser` 事件通知前端。
     */
    private fun showVerificationBrowser(sessionId: String, url: String) {
        if (verificationView != null && verificationSessionId == sessionId) return
        closeVerificationBrowser(reason = "replace")
        val view = VerificationBrowserView(
            context = this,
            sessionId = sessionId,
            stateStore = (application as StudioApplication).verificationWebState,
            onUrlObserved = { observed ->
                bridge?.emitVerificationBrowser("url", sessionId)
                // 观察地址交给宿主记录：完成会话时使用它，而不是前端传来的地址。
                val app = application as StudioApplication
                ioScope.launch {
                    runCatching {
                        val session = app.database.dao().verificationSession(sessionId)
                        if (session != null && session.status == "WAITING" && observed != session.finalUrl) {
                            app.verification.updateCurrentUrl(sessionId, observed)
                        }
                    }.onFailure {
                        com.mina.legadostudio.mcp.StudioLog.add(
                            "verification url observe err", "W", "verification", it.javaClass.simpleName,
                        )
                    }
                }
            },
            onTitleObserved = { bridge?.emitVerificationBrowser("title", sessionId) },
        )
        verificationView = view
        verificationSessionId = sessionId
        root.addView(view.webView)
        view.load(url)
        bridge?.emitVerificationBrowser("open", sessionId)
        // 隔离页盖住主页面时，主页面仍可被前端覆盖层操作（toolbar）；这里不拦截触摸。
    }

    private fun closeVerificationBrowser(reason: String) {
        val view = verificationView ?: return
        val sessionId = verificationSessionId
        verificationView = null
        verificationSessionId = null
        runCatching {
            root.removeView(view.webView)
            view.destroy()
        }
        bridge?.emitVerificationBrowser("close", sessionId)
        if (reason == "host") bridge?.emitVerificationBrowser("closed", sessionId)
    }

    // --- 生命周期 --------------------------------------------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRoute(intent)
    }

    /** 通知/深链带来的路由：交给前端处理（前端自己决定打开哪一页）。 */
    private fun consumeRoute(intent: Intent?) {
        val route = intent?.getStringExtra("route") ?: return
        intent.removeExtra("route")
        webView.post {
            if (unsupportedShown) return@post
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('studio:route',{detail:${quoteJs(route)}}))",
                null,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        applyThemeFromStore()
        // 服务状态变化（前台服务启停/网络切换）由前端轮询 bootstrap，这里不重复推送。
        if (McpService.running) bridge?.emit("studio:service", null)
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ioScope.cancel()
        runCatching { webView.stopLoading() }
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.destroy() }
        verificationView?.destroy()
        verificationView = null
        bridge?.release()
        bridge = null
        super.onDestroy()
    }

    companion object {
        private const val UNSUPPORTED_HTML = """
<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:system-ui,sans-serif;margin:0;padding:32px;line-height:1.6;color:#111;background:#fff}
h1{font-size:20px;margin:0 0 12px}p{margin:0 0 8px;color:#444}code{background:#f2f2f2;padding:2px 6px;border-radius:4px}</style>
</head><body><h1>需要更新 Android System WebView</h1>
<p>当前系统 WebView 不支持 <code>WebMessageListener</code>，无法安全地与本地页面通信。</p>
<p>本应用不会退回不安全的全域 JS 桥接。请在应用商店更新「Android System WebView」或升级系统后重新打开。</p>
</body></html>
"""

        private fun quoteJs(text: String): String {
            val builder = StringBuilder("\"")
            text.forEach { ch ->
                when (ch) {
                    '\\' -> builder.append("\\\\")
                    '"' -> builder.append("\\\"")
                    '\n' -> builder.append("\\n")
                    '\r' -> builder.append("\\r")
                    '<' -> builder.append("\\u003c")
                    '>' -> builder.append("\\u003e")
                    else -> if (ch.code < 0x20) builder.append("\\u%04x".format(ch.code)) else builder.append(ch)
                }
            }
            return builder.append('"').toString()
        }
    }
}
