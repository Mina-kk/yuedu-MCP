package com.mina.legadostudio.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
    // Parent must check live identity + persisted WAITING/non-manual webview state.
    private val autoCompleteEligible: () -> Boolean = { false },
    private val onAutoComplete: (String, () -> Boolean) -> Unit = { _, _ -> },
) {
    /** 宿主实际观察到的最后地址（完成会话时以此为准，绝不采用 JS 传来的地址）。 */
    @Volatile var lastObservedUrl: String = ""
        private set

    /** 显式持有系统 CookieManager（与既有验证中心同一份 WebView cookie 库）。 */
    private val cookieManager: CookieManager = CookieManager.getInstance()

    // Remain blank until MainActivity attaches this object to the lease lifecycle.
    val webView: WebView = WebView(context.applicationContext)
    private var configured = false
    private var destroyed = false
    private var closing = false
    private var generation = 0L
    private var pageFailed = false
    private var probeStarted = false
    private val probe = VerificationManagementProbe()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val probeTick = object : Runnable {
        override fun run() {
            if (closing || destroyed) return
            val token = generation
            val url = webView.url.orEmpty()
            if (!pageFailed && autoCompleteEligible() && WebBridgePolicy.isVerificationUrlAllowed(url)) {
                webView.evaluateJavascript("JSON.stringify({ready:document.readyState==='complete',html:document.documentElement.outerHTML})") { raw ->
                    if (closing || destroyed || token != generation || webView.url != url) return@evaluateJavascript
                    runCatching {
                        val json = com.google.gson.JsonParser.parseString(com.google.gson.JsonParser.parseString(raw).asString).asJsonObject
                        val html = json.get("html").asString
                        val challenged = CHALLENGE.containsMatchIn(html)
                        // No task-bound retry verifier is installed for arbitrary sites.
                        // DOM observations alone intentionally remain manual (default evidence=null).
                        if (probe.sample(url, html.length.toLong(), challenged, autoCompleteEligible(), json.get("ready").asBoolean && !pageFailed, android.os.SystemClock.elapsedRealtime())) {
                            cookieManager.flush()
                            handler.postDelayed({
                                val valid = { !closing && !destroyed && token == generation && webView.url == url && !pageFailed && autoCompleteEligible() }
                                if (valid()) { cookieManager.flush(); onAutoComplete(url, valid) }
                            }, 500)
                            probe.close() // At most one automatic request per opened view.
                        }
                    }
                }
            }
            handler.postDelayed(this, 2_000)
        }
    }

    /** Same live view, same admitted URL; never restore stale saved navigation. */
    fun refresh(): Boolean {
        if (closing || destroyed || !configured || !WebProxyBoundary.active) return false
        val url = webView.url.orEmpty()
        if (!WebBridgePolicy.isVerificationUrlAllowed(url)) return false
        generation++
        probe.reset()
        stateStore.clear(sessionId)
        webView.reload()
        return true
    }

    private fun configure() = webView.apply {
        // Keep MainActivity's FrameLayout topMargin; toolbar must remain reachable.
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
            // 外站页面绝不读本机文件/内容提供者：即使导航限制被绕过也拿不到本地数据。
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            // 不设置 userAgentString：沿用系统 WebView UA（与既有验证中心一致，避免指纹不一致导致挑战失败）。
            // MainActivity waits for the public-IP-pinned proxy's applied callback before creating us.
            check(WebProxyBoundary.active) { "验证网络隔离未启用" }
            blockNetworkLoads = false
        }
        cookieManager.setAcceptThirdPartyCookies(this, true)
        cookieManager.setAcceptCookie(true)
        // 明确不挂任何 JS bridge：这里没有任何 addJavascriptInterface / addWebMessageListener。
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !allowNavigation(request.url?.toString())

            @Deprecated("兼容旧 WebView")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !allowNavigation(url)

            /**
             * 子资源：只允许外站自己的 http(s) 与 data:/blob:；
             * 指向 privileged origin 或 file:/content: 的一律拒绝（返回空 403，不回 null）。
             */
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (WebBridgePolicy.verificationResourceDecision(request.url?.toString()) == WebBridgePolicy.LocalResource.Serve) null else BLOCKED_RESPONSE

            @Deprecated("兼容旧 WebView")
            override fun shouldInterceptRequest(view: WebView, url: String?): WebResourceResponse? =
                if (WebBridgePolicy.verificationResourceDecision(url) == WebBridgePolicy.LocalResource.Serve) null else BLOCKED_RESPONSE

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (closing || destroyed) return
                generation++
                probe.reset() // Never carry challenge observations across documents.
                pageFailed = false
                if (url != null) observe(url, view.title)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (closing || destroyed) return
                if (url != null) observe(url, view.title)
                stateStore.save(sessionId, view)
                if (!probeStarted) { probeStarted = true; handler.postDelayed(probeTick, 2_000) }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    pageFailed = true
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
                    pageFailed = true
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
                if (!closing && !destroyed) title?.let(onTitleObserved)
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
        if (closing || destroyed || !WebBridgePolicy.isVerificationUrlAllowed(url)) return
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

    /**
     * 加载任务地址。
     *
     * 恢复上次页面状态只在**同一会话**内允许：状态里存的地址若不是公网 http(s)
     * （例如被篡改成 privileged origin 或 file:），退回直接加载任务 URL。
     */
    fun load(url: String) {
        check(!destroyed) { "验证页已销毁" }
        if (!configured) { configure(); configured = true }
        if (!WebBridgePolicy.isVerificationUrlAllowed(url)) {
            StudioLog.add("verification load refused", "W", "verification", "session=$sessionId")
            return
        }
        // 优先恢复上次页面状态（避免刷新后回到挑战入口）；恢复后地址不合法则退回任务 URL。
        if (stateStore.restore(sessionId, webView) && WebBridgePolicy.isVerificationUrlAllowed(lastObservedUrl)) {
            return
        }
        lastObservedUrl = ""
        webView.loadUrl(url)
        // onPageStarted 会回填观察地址；这里只兜底，避免恢复后仍为空。
        if (lastObservedUrl.isBlank()) lastObservedUrl = url
    }

    fun destroy(): Boolean {
        if (destroyed) return true
        closing = true
        generation++
        probe.close()
        handler.removeCallbacksAndMessages(null)
        runCatching { stateStore.save(sessionId, webView) }
        runCatching { webView.settings.blockNetworkLoads = true }
        runCatching { webView.stopLoading() }
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        destroyed = runCatching { webView.destroy() }.isSuccess
        return destroyed
    }

    companion object {
        // Scan the entire DOM, unlike the old first-20k marker heuristic. Password/login,
        // challenge and access-denied pages stay manual even when their size is stable.
        private val CHALLENGE = Regex("Verify Yourself|WAF/VERIFY/CAPTCHA|cf-chl-|challenges\\.cloudflare\\.com|turnstile|altcha-widget|aegis_altcha|人机验证|安全验证|captcha|type\\s*=\\s*['\"]password|access denied|just a moment", RegexOption.IGNORE_CASE)
        /** 子资源拒绝响应：空体 403，明确告知页面「不给」，绝不回 null 去联网。 */
        private val BLOCKED_RESPONSE: WebResourceResponse by lazy {
            WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
        }
    }
}
