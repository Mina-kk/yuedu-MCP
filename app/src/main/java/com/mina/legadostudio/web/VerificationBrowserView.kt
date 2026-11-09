package com.mina.legadostudio.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mina.legadostudio.mcp.StudioLog
import com.mina.legadostudio.verification.VerificationWebViewStateStore

/**
 * 隔离的人工验证 WebView。
 *
 * 安全边界（契约「人工验证页面必须没有 privileged JS bridge」）：
 * - **不安装** WebMessageListener、不 addJavascriptInterface、不暴露任何宿主对象；
 * - 只加载 bridge 已准入的公网 http(s) URL（[WebBridgePolicy.isVerificationUrlAllowed]）；
 * - 页面内跳转到非 http(s)（intent://、market://、tel: 等）一律拒绝，不交给系统；
 * - 关闭/完成控件**不在**这里渲染：toolbar 由主 Web UI（Vue）覆盖，宿主只把
 *   `studio:verification-browser` 事件发给主页面。
 *
 * 与既有验证中心共享：Cookie 走系统 CookieManager（与 [com.mina.legadostudio.verification.RuntimeCookieStore]
 * 同一份 WebView cookie 库），会话状态存 Room，页面状态用 [VerificationWebViewStateStore] 复用。
 */
internal class VerificationBrowserView(
    context: Context,
    private val sessionId: String,
    private val stateStore: VerificationWebViewStateStore,
    private val onUrlObserved: (String) -> Unit,
    private val onTitleObserved: (String) -> Unit,
) {
    /** 宿主实际观察到的最后地址（完成会话时以此为准，绝不采用 JS 传来的地址）。 */
    @Volatile var lastObservedUrl: String = ""
        private set

    val webView: WebView = WebView(context.applicationContext).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 验证页可能需要第三方 cookie（CF 挑战、登录态）；只允许 https 的第三方 cookie。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cookieManager.setAcceptThirdPartyCookies(this@apply, true)
            }
            // 不设置 userAgentString：沿用系统 WebView UA（与既有验证中心一致，避免指纹不一致导致挑战失败）。
            blockNetworkLoads = false
        }
        cookieManager.setAcceptCookie(true)
        // 明确不挂任何 JS bridge：这里没有任何 addJavascriptInterface / addWebMessageListener。
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !allowNavigation(request.url?.toString())

            @Deprecated("兼容旧 WebView")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !allowNavigation(url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null) observe(url, view.title)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null) observe(url, view.title)
                stateStore.save(sessionId, view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    StudioLog.add(
                        "verification page error ${error.errorCode}",
                        "W",
                        "verification",
                        "session=$sessionId host=${runCatching { Uri.parse(request.url.toString()).host }.getOrNull()}",
                    )
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) {
                    StudioLog.add(
                        "verification page http ${errorResponse.statusCode}",
                        "W",
                        "verification",
                        "session=$sessionId",
                    )
                }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                title?.let(onTitleObserved)
            }

            // 验证页的 JS 弹窗不弹原生对话框（契约禁止原生业务控件）：统一按取消处理并记录。
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                StudioLog.add("verification popup blocked", "W", "verification", "session=$sessionId")
                return false
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?,
            ): Boolean {
                result?.cancel()
                return true
            }
        }
    }

    private fun observe(url: String, title: String?) {
        lastObservedUrl = url
        onUrlObserved(url)
        title?.takeIf { it.isNotBlank() }?.let(onTitleObserved)
    }

    /** 只允许公网 http(s)；其余 scheme（intent/market/tel/file/content）一律留在页面内。 */
    private fun allowNavigation(url: String?): Boolean {
        if (WebBridgePolicy.isVerificationUrlAllowed(url)) return true
        StudioLog.add(
            "verification navigation blocked",
            "W",
            "verification",
            "session=$sessionId scheme=${url?.substringBefore(':')?.take(16).orEmpty()}",
        )
        return false
    }

    fun load(url: String) {
        if (!WebBridgePolicy.isVerificationUrlAllowed(url)) {
            StudioLog.add("verification load refused", "W", "verification", "session=$sessionId")
            return
        }
        // 优先恢复上次页面状态（避免刷新后回到挑战入口）；失败则直接加载。
        if (!stateStore.restore(sessionId, webView)) {
            webView.loadUrl(url)
        }
        // 恢复成功时 onPageStarted 会回填观察地址；这里只兜底，避免恢复后仍为空。
        if (lastObservedUrl.isBlank()) lastObservedUrl = url
    }

    fun destroy() {
        runCatching { stateStore.save(sessionId, webView) }
        runCatching {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
}
