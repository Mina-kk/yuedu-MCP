package com.mina.legadostudio

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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
import com.mina.legadostudio.web.SafHost
import com.mina.legadostudio.web.SafImportResult
import com.mina.legadostudio.web.SafLauncher
import com.mina.legadostudio.web.SafRequest
import com.mina.legadostudio.web.StudioWebBridge
import com.mina.legadostudio.web.VerificationBrowserView
import com.mina.legadostudio.web.WebAppearance
import com.mina.legadostudio.web.WebBridgePolicy
import com.mina.legadostudio.web.WebBridgeProtocol
import com.mina.legadostudio.web.WebProxyBoundary
import com.mina.legadostudio.web.VerificationLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 全屏 WebView 宿主（不使用 Compose 业务 UI）。
 *
 * 结构：`FrameLayout` → 主 WebView（Vue 应用，privileged bridge）+ 可选隔离验证 WebView（无 bridge）。
 * 所有业务控件（含验证工具栏、确认框、Toast 替代）都由 Web 渲染；本类只做：
 * - 加载 `https://appassets.androidplatform.net/assets/web/index.html`（WebViewAssetLoader，仅本地 assets）；
 * - 安装 [StudioWebBridge]（origin + main frame 双重校验）；
 * - Back 派发 `studio:back` 给前端；Insets 通过 `studio:insets` 事件下发；
 * - 隔离验证页的创建/关闭（toolbar 由前端覆盖，宿主只发 `studio:verification-browser` 事件）。
 *
 * 布局与 insets（`.tasks/host-remote-build.md` 第 6/8 条）：
 * - 系统栏/键盘由**原生 padding** 让出（只算一次），因此下发给前端的 insets 是 0；
 *   原始 px 与 density 只作信息字段，前端不得再加一次；
 * - 验证页打开时，主 WebView 保持原尺寸（不 resize，避免底部弹层错位），
 *   隔离页从顶部 [VERIFICATION_TOOLBAR_DP] 下方开始，Vue 渲染的 toolbar 始终可点。
 */
class MainActivity : ComponentActivity() {

    private var notificationRequest: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val notificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {
        val granted = notificationPermissionGranted()
        notificationRequest?.complete(granted)
        notificationRequest = null
        McpService.refreshNotificationIfActive()
        bridge?.emit(WebBridgeProtocol.EVENT_SERVICE, null)
    }

    private fun notificationPermissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Trusted bridge only; returns real permission, never starts the service implicitly. */
    suspend fun requestNotificationPermission(): Boolean = kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
        check(!isFinishing && !isDestroyed) { "宿主已关闭" }
        check(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) { "请回到应用后申请通知权限" }
        if (notificationPermissionGranted()) return@withContext true
        notificationRequest?.let { return@withContext it.await() }
        val prefs = getSharedPreferences("studio_notification_permission", MODE_PRIVATE)
        if (prefs.getBoolean("requested", false) &&
            !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
            openNotificationSettings()
            return@withContext false
        }
        val request = kotlinx.coroutines.CompletableDeferred<Boolean>()
        notificationRequest = request
        try {
            prefs.edit().putBoolean("requested", true).apply()
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } catch (error: Exception) {
            notificationRequest = null
            request.completeExceptionally(error)
        }
        request.await()
    }

    fun openNotificationSettings(): Boolean = com.mina.legadostudio.device.DeviceReadiness(this).openNotificationSettings()
    fun openMcpNotificationChannel(): Boolean {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.createNotificationChannel(android.app.NotificationChannel("studio_mcp", "阅读书源MCP", android.app.NotificationManager.IMPORTANCE_LOW))
        return com.mina.legadostudio.device.DeviceReadiness(this).openNotificationChannel("studio_mcp")
    }
    fun openOverlaySettings(): Boolean = com.mina.legadostudio.device.DeviceReadiness(this).openOverlaySettings()
    fun openBatterySettings(): Boolean {
        // A settings launch is not a grant; bootstrap reads PowerManager again.
        return runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            true
        }.getOrElse { com.mina.legadostudio.device.DeviceReadiness(this).openAppDetails() }
    }
    fun setOverlayEnabled(enabled: Boolean): Boolean {
        com.mina.legadostudio.verification.OverlayPrefs.setEnabled(this, enabled)
        com.mina.legadostudio.verification.VerificationOverlayManager.refresh(this)
        return com.mina.legadostudio.verification.OverlayPrefs.isEnabled(this)
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var bridge: StudioWebBridge? = null
    private val verificationLifecycle = VerificationLifecycle<VerificationBrowserView>()
    private val verificationView: VerificationBrowserView? get() = verificationLifecycle.resource
    private var verificationSessionId: String? = null
    private lateinit var saf: SafLauncher
    private lateinit var skillSaf: com.mina.legadostudio.web.SkillManagementSaf
    private var unsupportedShown = false
    private var captureHost: com.mina.legadostudio.web.InteractiveCaptureHost? = null
    private var captureView: WebView? = null
    private var captureBounds: com.google.gson.JsonObject? = null
    @Volatile private var verificationOpenToken: Any? = null

    internal fun closeVerificationIfMatching(id: String) {
        if (verificationSessionId == id) closeVerificationBrowser("host")
    }

    internal suspend fun verificationManagement(method: String, params: com.google.gson.JsonObject): com.google.gson.JsonObject {
        val app = application as StudioApplication
        return com.mina.legadostudio.web.VerificationManagementActions(this,
            { id -> kotlinx.coroutines.withContext(Dispatchers.IO) { app.database.dao().verificationSession(id) } },
            { id -> verificationView?.takeIf { verificationSessionId == id } },
        ).handle(method, params)
    }

    internal suspend fun captureCommand(method: String, params: com.google.gson.JsonObject): com.google.gson.JsonObject =
        kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            check(!isFinishing && !isDestroyed) { "宿主已关闭" }
            if (method == "capture.browser.viewport") {
                setCaptureViewport(params)
                return@withContext com.google.gson.JsonObject().apply { addProperty("applied", true) }
            }
            check(!verificationLifecycle.busy) { "请先关闭验证页" }
            val host = captureHost ?: run {
                val app = application as StudioApplication
                com.mina.legadostudio.web.InteractiveCaptureHost(this@MainActivity, app.webViewCapture, app.database.dao(),
                    beforeEnable = { app.readerWebUiSession.close() },
                    attach = { view ->
                        captureView?.let { (it.parent as? ViewGroup)?.removeView(it) }
                        captureView = view
                        view?.let { it.visibility = android.view.View.GONE; root.addView(it) }
                        applyCaptureLayout()
                    },
                    captureCookies = { url -> app.cookieStore.captureFromWebView(url) },
                    exportFile = { file ->
                        try {
                            saf.exportFile(file, "capture.json", "application/json")
                            com.google.gson.JsonObject().apply { addProperty("saved", true); addProperty("cancelled", false) }
                        } catch (_: SafLauncher.Cancelled) {
                            com.google.gson.JsonObject().apply { addProperty("saved", false); addProperty("cancelled", true) }
                        }
                    },
                ).also { captureHost = it }
            }
            host.command(method, params)
        }

    private fun setCaptureViewport(params: com.google.gson.JsonObject) {
        captureBounds = params.get("rect")?.takeIf { it.isJsonObject }?.asJsonObject
        applyCaptureLayout()
    }

    private fun applyCaptureLayout() {
        val view = captureView ?: return
        view.visibility = android.view.View.GONE
        val r = captureBounds ?: return
        fun number(key: String) = r.get(key)?.asDouble?.takeIf { it.isFinite() } ?: error("无效网页区域")
        val cssWidth = number("viewportWidth")
        if (cssWidth <= 0 || webView.width <= 0) return
        val scale = webView.width / cssWidth
        val left = (number("x") * scale).toInt().coerceIn(0, webView.width)
        val top = (number("y") * scale).toInt().coerceIn(0, webView.height)
        val right = ((number("x") + number("width")) * scale).toInt().coerceIn(left, webView.width)
        val bottom = ((number("y") + number("height")) * scale).toInt().coerceIn(top, webView.height)
        if (right <= left || bottom <= top) return
        view.layoutParams = FrameLayout.LayoutParams(right - left, bottom - top).apply {
            leftMargin = left; topMargin = top
        }
        view.visibility = android.view.View.VISIBLE
    }

    /** 主页面是否已 load 完成；未完成时路由先排队（事件不能打空）。 */
    private var webReady = false
    private var pendingRoute: String? = null

    /** 验证页观察地址回写用的后台作用域（Activity 销毁即取消）。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 系统 Back 交给前端处理（先关弹层/草稿，再根页退出确认）。 */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // 验证页打开时，Back 先退回主 Web 控制台（关闭由前端 toolbar 或本回调触发）。
            if (verificationLifecycle.busy) {
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

        // SAF 必须在 onCreate 内注册（registerForActivityResult 生命周期约束）。
        saf = SafLauncher(this).also { it.register() }
        skillSaf = com.mina.legadostudio.web.SkillManagementSaf(this).also { it.register() }

        bridge = StudioWebBridge(
            activity = this,
            onExit = { finish() },
            onRequestCloseVerificationBrowser = { closeVerificationBrowser(reason = "host") },
            onThemeApplied = { dark, _ -> applySystemBars(dark) },
        ).also { created ->
            created.attachSkillDocuments(skillSaf)
            created.attachSaf(object : SafHost {
                override suspend fun exportFile(file: java.io.File, name: String, mime: String) {
                    saf.exportFile(file, name, mime)
                }

                override suspend fun export(request: SafRequest.Export) {
                    saf.export(request.name, request.text, request.mime)
                }

                override suspend fun import(): SafImportResult {
                    val (name, text) = saf.import()
                    return SafImportResult(name, text)
                }
            })
            created.setVerificationBrowserOpener { session -> showVerificationBrowser(session.id, session.url) }
            // 完成会话时取隔离页当前真实观察地址（没有打开中的页面则返回 null，由 bridge 回落）。
            created.setObservedUrlProvider { sessionId ->
                verificationView?.takeIf { verificationSessionId == sessionId }?.lastObservedUrl
            }
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
        // 标准 AssetsPathHandler 只能挂 Context 根（/assets/），因此挂到 /assets/ 再由
        // shouldInterceptRequest 只放行 /assets/web/ 子树，其余一律 404，绝不回 null 去联网。
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
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
                when (WebBridgePolicy.localResourceDecision(request.url?.toString())) {
                    WebBridgePolicy.LocalResource.Serve -> assetLoader.shouldInterceptRequest(request.url)
                        ?: DENY_RESPONSE
                    WebBridgePolicy.LocalResource.Deny -> DENY_RESPONSE
                }

            @Deprecated("兼容旧 WebView")
            override fun shouldInterceptRequest(view: WebView, url: String?): WebResourceResponse? =
                when (WebBridgePolicy.localResourceDecision(url)) {
                    WebBridgePolicy.LocalResource.Serve -> url?.let { assetLoader.shouldInterceptRequest(android.net.Uri.parse(it)) }
                        ?: DENY_RESPONSE
                    WebBridgePolicy.LocalResource.Deny -> DENY_RESPONSE
                }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                WebBridgePolicy.hostNavigationDecision(request.url?.toString()) != WebBridgePolicy.NavigationDecision.Allow

            @Deprecated("兼容旧 WebView")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                WebBridgePolicy.hostNavigationDecision(url) != WebBridgePolicy.NavigationDecision.Allow

            override fun onPageFinished(view: WebView, url: String?) {
                notifyWebReady()
            }
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
        webView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            captureBounds = null
            captureView?.visibility = android.view.View.GONE
        }
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

    /**
     * 原生 padding 让出系统栏 + 键盘（只算一次），随后把「留给 Web 的 insets」按 0 下发，
     * 防止前端再算一次导致双倍留白；raw px / density 仅作信息字段。
     */
    private fun installInsetsListener() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bottom = maxOf(bars.bottom, ime)
            view.setPadding(bars.left, bars.top, bars.right, bottom)
            val density = resources.displayMetrics.density
            bridge?.emitInsets(
                top = 0,
                bottom = 0,
                ime = 0,
                rawTop = bars.top,
                rawBottom = bars.bottom,
                rawIme = ime,
                density = density,
            )
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
        val state = WebAppearance.load(this)
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        applySystemBars(WebAppearance.isDark(state.mode, systemDark))
    }

    // --- 隔离验证页 -------------------------------------------------------

    /**
     * 在宿主内嵌隔离 WebView。toolbar（关闭/完成）由主 Web UI 覆盖渲染：
     * 这里只创建无 bridge 的页面，并把状态变化以 `studio:verification-browser` 事件通知前端。
     *
     * 布局：主 WebView **保持原尺寸**（不 resize，否则底部弹层会错位）；隔离页从顶部
     * [VERIFICATION_TOOLBAR_DP] 之下开始，Vue 的顶部控制栏（96 CSS px）始终可见可点。
     */
    private suspend fun showVerificationBrowser(sessionId: String, url: String) {
        if (verificationView != null && verificationSessionId == sessionId) return
        check(!isFinishing && !isDestroyed) { "宿主已关闭" }
        check(captureView == null) { "请先结束浏览器抓包" }
        val openToken = Any()
        verificationOpenToken = openToken
        val app = application as StudioApplication
        val networkOwner = verificationLifecycle.begin()
        try {
        val generation = app.verification.beginBrowserGeneration(sessionId, url)
        check(verificationOpenToken === openToken && verificationLifecycle.canAttach(networkOwner)) { "验证页打开已取消" }
        WebProxyBoundary.acquire(networkOwner) { (application as StudioApplication).readerWebUiSession.close() }
        check(!isFinishing && !isDestroyed) { "宿主已关闭" }
        check(verificationLifecycle.canAttach(networkOwner)) { "验证页打开已取消" }
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
                            app.verification.updateCurrentUrl(sessionId, observed) { verificationOpenToken === openToken }
                        }
                    }.onFailure {
                        com.mina.legadostudio.mcp.StudioLog.add(
                            "verification url observe err", "W", "verification", it.javaClass.simpleName,
                        )
                    }
                }
            },
            onTitleObserved = { bridge?.emitVerificationBrowser("title", sessionId) },
            autoCompleteEligible = { generation != null && verificationOpenToken === openToken && verificationSessionId == sessionId },
            onAutoComplete = { observed, stillCurrent ->
                if (generation != null) ioScope.launch {
                    runCatching {
                        val completed = app.verification.completeAutomatically(sessionId, url, generation, observed) {
                            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                                verificationOpenToken === openToken && verificationSessionId == sessionId && stillCurrent()
                            }
                        }
                        if (completed != null) kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                            if (verificationOpenToken === openToken) {
                                closeVerificationBrowser("automatic")
                                bridge?.emitVerificationBrowser("completed", sessionId)
                            }
                        }
                    }
                }
            },
        )
        // Track the unconfigured, blank view before any operation that can throw.
        verificationLifecycle.attach(networkOwner, view)
        verificationSessionId = sessionId
        val toolbarPx = verificationToolbarPx()
        view.webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { topMargin = toolbarPx }
        root.addView(view.webView)
        view.load(url)
        applyVerificationLayout()
        bridge?.emitVerificationBrowser("open", sessionId)
        } catch (error: Exception) {
            val closed = verificationLifecycle.finish(networkOwner, { it.destroy() }, WebProxyBoundary::release)
            if (closed) verificationSessionId = null
            throw error
        }
    }

    /** 96dp 顶部控制栏高度（px）。 */
    private fun verificationToolbarPx(): Int =
        verificationToolbarPx(resources.displayMetrics.density)

    /**
     * 验证页打开时主 WebView 不 resize（保持底部弹层坐标正确），
     * 只在关闭时恢复默认布局参数。旋转/insets 变化时重新应用 topMargin。
     */
    private fun applyVerificationLayout() {
        val view = verificationView ?: return
        val params = view.webView.layoutParams as? FrameLayout.LayoutParams ?: return
        params.topMargin = verificationToolbarPx()
        view.webView.layoutParams = params
    }

    private fun closeVerificationBrowser(reason: String) {
        verificationOpenToken = null
        val view = verificationView
        val sessionId = verificationSessionId
        val closed = verificationLifecycle.cancel({ it.destroy() }, WebProxyBoundary::release)
        if (!closed) {
            if (view != null) com.mina.legadostudio.mcp.StudioLog.add("验证页销毁未确认，保留网络隔离，请重启应用", "W", "verification")
            return
        }
        verificationSessionId = null
        if (view == null) return
        bridge?.emitVerificationBrowser("close", sessionId)
        if (reason == "host") bridge?.emitVerificationBrowser("closed", sessionId)
    }

    // --- 生命周期 --------------------------------------------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRoute(intent)
    }

    /* 主 WebView 加载完成：宿主此前排队的深链/通知路由现在可以安全派发。 */
    private fun notifyWebReady() {
        webReady = true
        flushPendingRoute()
    }

    /** 路由只在页面 load 完成后派发；页面未就绪时先存起来，避免事件打空。 */
    private fun flushPendingRoute() {
        val route = pendingRoute ?: return
        if (!webReady || unsupportedShown) return
        pendingRoute = null
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('studio:route',{detail:${quoteJs(route)}}))",
            null,
        )
    }

    /** 通知/深链带来的路由：交给前端处理（前端自己决定打开哪一页）。 */
    private fun consumeRoute(intent: Intent?) {
        val route = intent?.getStringExtra("route") ?: return
        intent.removeExtra("route")
        pendingRoute = route
        webView.post { flushPendingRoute() }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        com.mina.legadostudio.verification.VerificationOverlayManager.refresh(this)
        McpService.refreshNotificationIfActive()
        applyThemeFromStore()
        // 服务状态变化（前台服务启停/网络切换）由前端轮询 bootstrap，这里不重复推送。
        bridge?.emit(WebBridgeProtocol.EVENT_SERVICE, null)
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统深浅色切换：同步系统栏图标；验证页顶部区域按新 density 重算。
        applyThemeFromStore()
        applyVerificationLayout()
    }

    override fun onDestroy() {
        notificationRequest?.cancel()
        notificationRequest = null
        captureHost?.dispose()
        ioScope.cancel()
        runCatching { webView.stopLoading() }
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        runCatching { webView.destroy() }
        closeVerificationBrowser(reason = "destroy")
        // 先释放 SAF（结算在途文件操作），再释放桥（结算在途 RPC）。
        runCatching { saf.release() }
        runCatching { skillSaf.release() }
        bridge?.release()
        bridge = null
        super.onDestroy()
    }

    companion object {
        /** Vue 顶部控制栏高度（CSS px = dp）：验证页必须让出这块区域。 */
        const val VERIFICATION_TOOLBAR_DP = 96

        /** 验证页顶部区域高度（px）。density 变化（旋转/显示大小）时重算。 */
        fun verificationToolbarPx(density: Float): Int =
            (VERIFICATION_TOOLBAR_DP * density + 0.5f).toInt()

        /** 非本地资源统一 404：空体、不联网。 */
        internal val DENY_RESPONSE: WebResourceResponse by lazy {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
        }

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
