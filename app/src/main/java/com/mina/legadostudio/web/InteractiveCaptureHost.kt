package com.mina.legadostudio.web

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.export.CaptureSessionExporter
import com.mina.legadostudio.network.CaptureActivity
import com.mina.legadostudio.verification.WebViewCapture
import com.mina.legadostudio.verification.WebViewCaptureEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** One Activity-owned interactive browser. All commands are serialized on Main.
 * attach must place the view in the Vue-reported viewport; null removes it.
 * beforeEnable must suspend the Reader LAN WebView, as for VerificationBrowserView.
 * exportFile MUST stream the file through SAF and return {saved:true} or {cancelled:true}.
 * No external page ever receives the trusted StudioBridge.
 */
internal class InteractiveCaptureHost(
    private val context: Context,
    private val engine: WebViewCaptureEngine,
    private val dao: StudioDao,
    private val beforeEnable: suspend () -> Unit,
    private val attach: (WebView?) -> Unit,
    private val captureCookies: suspend (String) -> Unit,
    private val exportFile: suspend (File) -> JsonObject,
) {
    private val mutex = Mutex()
    private var session: WebViewCapture.Session? = null
    private var view: WebView? = null
    private var startedAt = 0L
    private var currentUrl = ""
    private var phase = "idle"
    private var leased = false
    private var disposed = false
    private var diagnostic = ""

    suspend fun command(action: String, args: JsonObject): JsonObject = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            check(!disposed) { "抓包宿主已销毁" }
            when (action.removePrefix("capture.browser.")) {
                "open" -> { open(args.get("url")?.asString.orEmpty().trim()); snapshot() }
                "navigate" -> { val url = args.get("url")?.asString.orEmpty().trim(); requireUrl(url); activeView().loadUrl(url); snapshot() }
                "back" -> { activeView().let { if (it.canGoBack()) it.goBack() }; snapshot() }
                "forward" -> { activeView().let { if (it.canGoForward()) it.goForward() }; snapshot() }
                "reload" -> { activeView().reload(); snapshot() }
                "state" -> snapshot()
                "transactions" -> transactions(args.get("offset")?.asInt ?: 0)
                "detail" -> detail(args.get("id")?.asLong ?: error("缺少事务 ID"))
                "finish" -> { finish(); snapshot() }
                "close" -> close()
                "export" -> export()
                else -> error("未知抓包动作")
            }
        }
    }

    private fun requireUrl(url: String) { require(WebBridgePolicy.isVerificationUrlAllowed(url)) { "仅允许完整公网 HTTP(S) 地址" } }
    private fun activeView(): WebView { check(phase == "active"); return checkNotNull(view) }

    private suspend fun open(url: String) {
        requireUrl(url)
        check(phase != "active" && phase != "finishing") { "请先结束当前会话" }
        WebProxyBoundary.acquire(this, beforeEnable)
        leased = true
        try {
            check(!disposed)
            val s = engine.newSession(url, interactive = true)
            session = s; startedAt = System.currentTimeMillis(); currentUrl = url; phase = "active"; diagnostic = ""
            val w = WebView(context)
            view = w
            engine.configure(w)
            w.settings.apply {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false; allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false); setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
            }
            val delegate = engine.clientFor(s, onMainFrameNavigated = { observed ->
                if (phase == "active" && session === s) currentUrl = observed
            })
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (WebBridgePolicy.verificationResourceDecision(request.url.toString()) != WebBridgePolicy.LocalResource.Serve) return blocked()
                    return delegate.shouldInterceptRequest(v, request)
                }
                @Deprecated("Legacy resource callback fails closed")
                override fun shouldInterceptRequest(v: WebView, url: String?): WebResourceResponse = blocked()
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                    !WebBridgePolicy.isVerificationUrlAllowed(request.url.toString()) || delegate.shouldOverrideUrlLoading(v, request)
                @Deprecated("Legacy navigation callback")
                override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean = !WebBridgePolicy.isVerificationUrlAllowed(url)
                override fun onPageStarted(v: WebView, url: String?, icon: Bitmap?) { observe(url); delegate.onPageStarted(v, url, icon) }
                override fun onPageFinished(v: WebView, url: String?) { observe(url); delegate.onPageFinished(v, url) }
                private fun observe(url: String?) { if (phase == "active" && session === s && url != null) currentUrl = url }
                override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) { delegate.onReceivedError(v, request, error) }
                override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) { delegate.onReceivedHttpError(v, request, response) }
                override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel() }
            }
            w.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(v: WebView, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
                override fun onJsConfirm(v: WebView, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
                override fun onJsPrompt(v: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
            }
            attach(w)
            w.loadUrl(url)
        } catch (e: Throwable) {
            beginFinish()
            throw e
        }
    }

    /** Close write gate first; engine retains CaptureActivity until its FIFO barrier, including filtered/failed rows. */
    private fun beginFinish() {
        val s = session
        if (s != null && phase == "active") {
            phase = "finishing"
            engine.finishSession(s, currentUrl, startedAt)
        }
        val w = view
        if (w != null) {
            runCatching { w.settings.blockNetworkLoads = true }
            runCatching { w.stopLoading() }
            runCatching { (w.parent as? ViewGroup)?.removeView(w) }
            // Keep the proxy rejecting if destruction fails. Never release a lease under a live external view.
            w.destroy()
            view = null
        }
        attach(null)
        if (leased) { WebProxyBoundary.release(this); leased = false }
    }

    private suspend fun finish() {
        val s = session ?: return
        beginFinish()
        withTimeout(30_000) { while (engine.isCaptureSessionActive(s.contextId)) delay(40) }
        // Inactivity alone is NOT proof of persistence: recorder may be disabled or failed.
        try {
            val total = dao.countHttpLogsByContextId(s.contextId)
            val last = dao.httpLogsByContextIdPage(s.contextId, 1, (total - 1).coerceAtLeast(0)).lastOrNull()
            check(last?.originKind == WebViewCaptureEngine.ORIGIN_WEBVIEW_SESSION) { "结算行未落盘，不能声明已保存" }
            captureCookies(currentUrl)
            phase = "saved"
            diagnostic = ""
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            phase = "failed"
            diagnostic = "结算或 Cookie 保存未确认；已捕获事务保留，可返回日志查看或重新开始"
            throw e
        }
    }

    /** Exit is not a persistence assertion. Never unregister here: only the engine's
     * FIFO callback owns that. Retain context/diagnostics/rows for re-entry and logs. */
    private suspend fun close(): JsonObject {
        try { finish() } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            diagnostic = "保存未确认；已捕获事务保留，可返回日志查看或重新开始"
        }
        check(view == null && !leased) { "隔离网页尚未安全关闭，请重试" }
        // If the FIFO is still draining, keep finishing: reopening must not replace it.
        session?.let { if (!engine.isCaptureSessionActive(it.contextId) && phase != "saved") phase = "failed" }
        return JsonObject().apply {
            addProperty("closed", true)
            addProperty("saved", phase == "saved")
            addProperty("contextId", session?.contextId.orEmpty())
            addProperty("diagnostic", diagnostic)
        }
    }

    /** Call synchronously on Main from Activity.onDestroy, even when lifecycleScope is cancelled. */
    fun dispose() { disposed = true; beginFinish() }

    private suspend fun snapshot() = JsonObject().apply {
        // A timed-out close may finish draining after the UI has left.
        if (phase == "finishing" && view == null && session?.let { !engine.isCaptureSessionActive(it.contextId) } == true) {
            phase = "failed"
            diagnostic = "保存未确认；已捕获事务保留，可重试结算检查或重新开始"
        }
        addProperty("diagnostic", diagnostic)
        addProperty("phase", phase); addProperty("contextId", session?.contextId.orEmpty())
        addProperty("url", currentUrl); addProperty("canGoBack", view?.canGoBack() == true)
        addProperty("canGoForward", view?.canGoForward() == true)
        addProperty("total", session?.let { dao.countHttpLogsByContextId(it.contextId) } ?: 0)
    }

    private suspend fun transactions(offset: Int): JsonObject {
        require(offset >= 0)
        val s = checkNotNull(session)
        val rows = dao.httpLogSummariesByContextIdPage(s.contextId, 50, offset)
        return JsonObject().apply {
            addProperty("contextId", s.contextId); addProperty("total", dao.countHttpLogsByContextId(s.contextId))
            add("items", JsonArray().apply { rows.forEach { row -> add(JsonObject().apply {
                addProperty("id", row.id); addProperty("method", row.method); addProperty("url", row.url)
                addProperty("statusCode", row.statusCode); addProperty("originKind", row.originKind)
            }) } })
        }
    }

    private suspend fun detail(id: Long): JsonObject {
        val row = checkNotNull(dao.httpLog(id))
        check(row.contextId == checkNotNull(session).contextId)
        return JsonObject().apply {
            addProperty("id", row.id); addProperty("method", row.method); addProperty("url", row.url)
            addProperty("finalUrl", row.finalUrl); addProperty("statusCode", row.statusCode)
            addProperty("requestHeaders", row.requestHeaders); addProperty("responseHeaders", row.responseHeaders)
            addProperty("requestBody", row.requestBody); addProperty("responseBody", row.responseBody)
            addProperty("redirectChain", row.redirectChain); addProperty("error", row.error)
        }
    }

    private suspend fun export(): JsonObject {
        check(phase == "saved") { "请先结束并保存，等待写入结算" }
        val id = checkNotNull(session).contextId
        var result: JsonObject? = null
        // Existing exclusive reservation also prevents concurrent history deletion during paged export.
        check(CaptureActivity.deleteIfInactive(id) {
            val file = withContext(Dispatchers.IO) {
                File(context.cacheDir, "exports").apply { mkdirs() }.let { File.createTempFile("capture-", ".json", it) }
            }
            try {
                val metadata = withContext(Dispatchers.IO) {
                    val report = CaptureSessionExporter.ResourceReport()
                    val total = dao.countHttpLogsByContextId(id)
                    val count = file.bufferedWriter().use { writer ->
                        CaptureSessionExporter.writeSessionJson(writer, id, System.currentTimeMillis(), total,
                            page = { dao.httpLogsByContextIdPage(id, CaptureSessionExporter.EXPORT_PAGE_SIZE, it) },
                            report = report,
                            fileReader = { relative ->
                                val root = File(context.cacheDir, "captures").canonicalFile
                                val body = File(context.cacheDir, relative).canonicalFile
                                if (body.path.startsWith(root.path + File.separator) && body.isFile) body.readBytes() else null
                            })
                    }
                    check(count == total) { "导出条数不完整" }
                    JsonObject().apply {
                        addProperty("count", count)
                        addProperty("total", total)
                        addProperty("partial", count != total || report.missingResources > 0)
                        addProperty("missingResources", report.missingResources)
                        addProperty("omittedResources", report.omittedResources)
                    }
                }
                // SAF confirms only persistence, never completeness. Attach the actual
                // writer report after provider success; do not infer counts from UI state.
                result = exportFile(file).apply {
                    if (get("saved")?.asBoolean == true && get("cancelled")?.asBoolean != true) {
                        metadata.entrySet().forEach { (key, value) -> add(key, value) }
                    }
                }
            } finally { withContext(NonCancellable + Dispatchers.IO) { file.delete() } }
        }) { "会话正在写入或删除，请稍后重试" }
        return checkNotNull(result)
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
}
