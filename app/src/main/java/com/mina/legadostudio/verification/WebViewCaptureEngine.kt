package com.mina.legadostudio.verification

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mina.legadostudio.domain.LogFilterUtils
import com.mina.legadostudio.network.CaptureBlockedException
import com.mina.legadostudio.network.CaptureOnce
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpLogCaps
import com.mina.legadostudio.network.HttpLogRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.brotli.BrotliInterceptor
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 「WebView 浏览器抓包」的供给/记录引擎：把一个抓包 [WebViewCapture.Session] 装配到
 * WebView 的 WebViewClient 上——shouldInterceptRequest 里对每个 GET/HEAD 请求用一次
 * **独立的 OkHttpClient 对同一 URL 发供给请求**，如实记录这次供给请求与响应（这是
 * OkHttp 的独立请求，不是 WebView 内部网络栈的字节流），然后把已记录的这批字节
 * 喂回 WebView。
 *
 * 两种使用形态（同一套供给/判定逻辑）：
 * - [newSession]+[configure]+[clientFor]：装配到一个**可见、可交互**的 WebView
 *   （浏览器抓包页 Compose AndroidView），页面一直存活，用户可继续点击章节/翻页，
 *   期间所有请求都落入同一 `cap:` contextId；
 * - [run]：无头模式——一次性临时 WebView 跑完即销毁，供 MCP webview_capture 工具
 *   取证调用。它不可交互：没有用户点章节这一步。
 *
 * 安全与诚实边界：
 * - decide(allow=false) 的请求（私网/回环/非 http(s)/DNS 解析到私网）返回空占位
 *   WebResourceResponse **真正阻断**——不是 return null 放行；同时记 BLOCKED 证据行；
 *   WebView 自加载路径的跳转另由 shouldOverrideUrlLoading 补闸（私网/DNS 私网吞掉）；
 *   供给侧 OkHttp 的 DNS 用钉住地址（连接级不重解析），杜绝 decide 时公网/建连时重绑定到私网；
 * - 3xx/204/304 不用 WebResourceResponse 供给：如实记 OBSERVED_ONLY 行（供给侧拿到了 3xx），
 *   return null 让 WebView 对**同一 URL** 重新发起、由它自己的栈处理跳转——
 *   不宣称"逐跳抓全链"，后续请求是否出现取决于 WebView 自身行为；
 * - POST/PUT 拿不到请求体、容量耗尽、声明长度或实际读出超过上限、供给异常：
 *   都只记观察行后 return null，绝不把截断/不完整流喂给页面；
 * - 供给用 OkHttp 带 BrotliInterceptor、followRedirects(false)，且不转发
 *   Accept-Encoding——让 OkHttp 自管编码协商并透明解压，喂回 WebView 的是已解压字节；
 * - 每条事务按 txnSeq 去重（同一 URL 重复请求不互相吞掉），WebView 的
 *   onReceivedHttpError/onReceivedError 只对「供给路径没记过」的事务补记错误行；
 * - 字体/明确二进制：原始字节落盘 cacheDir/captures/<ctx>/，日志行记文件路径+大小。
 *
 * 未在真机上验证的部分（如实声明）：拦截供给在 WebView 渲染管线的端到端行为、
 * WebView 是否总是采用供给响应、3xx 自跳观察的逐跳顺序、DNS 缓存命中/过期行为。
 */
class WebViewCaptureEngine(
    private val context: Context,
    private val logStore: HttpLogRecorder,
    private val logsByContextId: suspend (String) -> List<CaptureOnce.LogEntry>,
    private val recordingEnabled: () -> Boolean,
    private val userAgentProvider: () -> String = { HttpFetcher.DEFAULT_UA },
) {
    /** 无头会话串行门：可见页的交互会话不经过它（页面由 Compose 生命周期持有）。 */
    private val gate = Mutex()

    /**
     * 正在写入的 cap: 会话注册表：newSession 建会话即登记，finishSession（「结束并保存」/
     * 无头收尾/页面销毁结算）注销。抓包历史页删会话前查它——活跃会话仍在追加日志，
     * 删除会与写入竞态（删后新行复活成幽灵会话），必须拒绝而不是先删后写丢数据。
     * 线程安全：shouldInterceptRequest 在拦截线程、注册/查询在主线程，用 synchronizedSet。
     * 覆盖两类 webview 抓包（可见浏览器页 + MCP 无头）；逐次抓包 capture_once 走
     * HttpLogRecorder 同步链路，一次跑完即结束，不在此表（会话产生期间历史页即便删到
     * 同名 cap: 也只是小概率空操作窗口——cap:<uuid> 撞名实际不可能）。
     */
    private val activeCaptureContextIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 该 cap:contextId 是否有正在写入的抓包会话（可见页未结算/无头 run 进行中）。 */
    fun isCaptureSessionActive(contextId: String): Boolean = contextId in activeCaptureContextIds

    /**
     * DNS 私网守卫 + 连接级钉住：同 host 3 分钟内不重复解析，防拦截线程被 DNS 拖死。
     * 值 = (expiresAt, reasonOrNull, pinnedAddrs)：
     * - reasonOrNull 非空 = 解析到私网/回环/保留地址（decide 用它直接拒）；
     * - pinnedAddrs 非空 = 已验证的全部公网地址，[supplyDns] 只返回这一批——
     *   OkHttp 建连不再二次解析，DNS 重绑定在连接级被钉死。
     */
    private class DnsVerdict(val expiresAt: Long, val reason: String?, val addrs: List<InetAddress>)
    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, DnsVerdict>()

    // ------------------------------------------------------------------
    // 可见浏览器页：把抓包逻辑装配到交互式 WebView
    // ------------------------------------------------------------------

    /**
     * 打开一个抓包会话（纯判定层，不创建 WebView）。
     * 录制关闭即拒绝；调用方（浏览器抓包页/无头引擎）负责建 WebView → [configure] →
     * 设 [clientFor] → loadUrl。
     * [interactive]=true 是会话形态标记（应用内可见浏览器页）：结算时写进会话汇总行的
     * `mode=` 字段，让 list_captures/历史页能如实区分「可见浏览器」与「无头 webview_capture」；
     * 无头 [run] 走默认值 MODE_HEADLESS，不传本参数。
     */
    fun newSession(url: String, interactive: Boolean = false): WebViewCapture.Session {
        if (!recordingEnabled()) {
            throw CaptureBlockedException("HTTP 事务记录已关闭，浏览器抓包不会留下任何日志；请先到日志页开启记录后再抓包")
        }
        val base = WebViewCapture.open(url)
        // 建会话即登记活跃表：该 contextId 在结算前不接受历史页删除（删行会与在途追加竞态）。
        activeCaptureContextIds.add(base.contextId)
        return WebViewCapture.Session(
            contextId = base.contextId,
            entryUrl = base.entryUrl,
            sourceAnchor = base.sourceAnchor,
            sessionMode = if (interactive) WebViewCapture.Session.MODE_INTERACTIVE else WebViewCapture.Session.MODE_HEADLESS,
            dnsGuard = ::dnsGuardCached,
        )
    }

    /**
     * 装配 WebViewClient：对 [session] 里判定放行的请求做供给/记录，其余观察或阻断。
     * [onTransaction] 每写一条证据行回调一次。**回调在拦截线程触发，内部已切到主线程再调**
     * 传入的 lambda（调用方可直接刷新 Compose 状态，不用自己切线程）。
     * [onMainFrameNavigated] 在主文档 top-level 导航的供给响应返回后回调最新 URL（同样已切主线程）。
     */
    fun clientFor(
        session: WebViewCapture.Session,
        onTransaction: () -> Unit = {},
        onMainFrameNavigated: (String) -> Unit = {},
    ): WebViewClient {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        // onTransaction/onMainFrameNavigated 在拦截线程被调用；Compose 可变状态必须在主线程改，
        // 统一在这里 post 到主线程，UI 侧拿到的就是已切线程的安全回调。
        val postTxn: () -> Unit = { main.post(onTransaction) }
        val postNav: (String) -> Unit = { u -> main.post { onMainFrameNavigated(u) } }
        return object : WebViewClient() {

        override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
            val reqUrl = request.url?.toString().orEmpty()
            val method = request.method?.uppercase() ?: "GET"
            val decision = session.decide(request)

            // 阻断（私网/非法 URL/DNS 私网/超上限）：返回空占位响应真正断掉加载，不是放行。
            // 证据行进写入闸：闸已关（结算已发起）时仍返回空响应阻断，只是不再写库——
            // 闸外新写的行会落在结算行之后，复活成已被历史页删除的幽灵会话。
            if (!decision.allow) {
                session.withWriteGate {
                    session.blockedRows.incrementAndGet()
                    logStore.recordAsync(
                        HttpLogRecorder.Draft(
                            method = method,
                            url = reqUrl,
                            statusCode = 0,
                            requestHeaders = requestHeadersWithCookie(reqUrl, request.requestHeaders),
                            error = decision.requestNote ?: "BLOCKED：不允许的 URL，已阻断加载",
                            sourceAnchor = session.sourceAnchor,
                            contextId = session.contextId,
                            originKind = ORIGIN_WEBVIEW_BLOCKED,
                        )
                    )
                    postTxn()
                }
                return emptyResponse()
            }

            if (decision.observeOnly) {
                // 带体请求/容量耗尽：只记请求行，不干预加载。闸已关则连请求行也不写（同上理由）。
                session.withWriteGate {
                    session.observedRows.incrementAndGet()
                    recordObservation(session, request, reqUrl, method, decision)
                    postTxn()
                }
                return null
            }

            // 拦截供给：整个供给块（含内部全部 recordAsync）进闸——闸内已开始的供给允许写完，
            // 其行恒先于结算行 FIFO 入队；闸已关的新请求 supplied=null，交回 WebView 自加载。
            val supplied = session.withWriteGate {
                runCatching {
                    supplyAndRecord(session, request, reqUrl, method, decision)
                }.onFailure { supplyError ->
                    // 供给失败也留证据：记错误行后交回 WebView 自己加载（不破坏页面）。
                    recordFetchError(session, request, reqUrl, method, decision, supplyError)
                }.getOrNull()
            }
            if (supplied != null && request.isForMainFrame) postNav(reqUrl)
            if (supplied != null || decision.txnSeq > 0) postTxn()
            return supplied
        }

        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
            // WebView 自加载路径的跳转（3xx 自跳/链接点击/JS location）不走 shouldInterceptRequest 的
            // 供给前置判定；在这里补一道闸：私网/回环/非 http(s)/DNS 解析到私网的导航直接吞掉并记 BLOCKED。
            val target = request.url?.toString().orEmpty()
            if (!WebViewCapture.isCapturableUrl(target)) {
                // 关闸后仍 return true 吞掉私网导航（功能行为不变），只是不再写证据行。
                session.withWriteGate {
                    session.blockedRows.incrementAndGet()
                    logStore.recordAsync(
                        HttpLogRecorder.Draft(
                            method = "GET",
                            url = target,
                            statusCode = 0,
                            error = "BLOCKED：导航目标非 http(s)/内嵌凭据/回环私网保留地址，已拦截跳转",
                            sourceAnchor = session.sourceAnchor,
                            contextId = session.contextId,
                            originKind = ORIGIN_WEBVIEW_BLOCKED,
                        )
                    )
                    postTxn()
                }
                return true
            }
            val host = LogFilterUtils.extractRawHost(target)
            val dnsReason = if (host.isNotEmpty()) session.dnsGuard?.invoke(host) else null
            if (dnsReason != null) {
                session.withWriteGate {
                    session.blockedRows.incrementAndGet()
                    logStore.recordAsync(
                        HttpLogRecorder.Draft(
                            method = "GET",
                            url = target,
                            statusCode = 0,
                            error = "BLOCKED：导航目标 DNS 解析到私网/回环/保留地址（$dnsReason），已拦截跳转",
                            sourceAnchor = session.sourceAnchor,
                            contextId = session.contextId,
                            originKind = ORIGIN_WEBVIEW_BLOCKED,
                        )
                    )
                    postTxn()
                }
                return true
            }
            return false
        }

        override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            // 只对供给路径没记过的事务补记（supplied 路径的 4xx/5xx 已在 supplyAndRecord 落库）。
            val reqUrl = request.url?.toString().orEmpty()
            val method = request.method?.uppercase() ?: "GET"
            if (!WebViewCapture.isCapturableUrl(reqUrl)) return
            // 写入闸：关闸后的迟到错误回调不再写库（否则落在结算行后成幽灵会话行）。
            if (!session.beginWrite()) return
            try {
                val seq = session.lastTxnSeq(reqUrl, method)
                // seq<=0：这个请求根本没进 decide（WebView 内部/不经拦截的路径），仍留证据行。
                if (seq > 0 && session.isRecorded(seq)) return
                if (seq > 0) session.markRecorded(seq)
                val respHeaders = runCatching { errorResponse.responseHeaders }.getOrNull() ?: emptyMap()
                logStore.recordAsync(
                    HttpLogRecorder.Draft(
                        method = method,
                        url = reqUrl,
                        statusCode = errorResponse.statusCode,
                        requestHeaders = requestHeadersWithCookie(reqUrl, request.requestHeaders),
                        responseHeaders = respHeaders,
                        error = "HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase.orEmpty()}（WebView 自加载路径）",
                        sourceAnchor = session.sourceAnchor,
                        contextId = session.contextId,
                        originKind = ORIGIN_WEBVIEW_ERROR,
                    )
                )
                session.observedRows.incrementAndGet()
                postTxn()
            } finally {
                session.endWrite()
            }
        }

        override fun onReceivedError(v: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            val reqUrl = request.url?.toString().orEmpty()
            val method = request.method?.uppercase() ?: "GET"
            if (!WebViewCapture.isCapturableUrl(reqUrl)) return
            // 写入闸：关闸后的迟到错误回调不再写库（否则落在结算行后成幽灵会话行）。
            if (!session.beginWrite()) return
            try {
                val seq = session.lastTxnSeq(reqUrl, method)
                if (seq > 0 && session.isRecorded(seq)) return
                if (seq > 0) session.markRecorded(seq)
                logStore.recordAsync(
                    HttpLogRecorder.Draft(
                        method = method,
                        url = reqUrl,
                        statusCode = 0,
                        requestHeaders = requestHeadersWithCookie(reqUrl, request.requestHeaders),
                        error = "WebView 加载失败：${error.errorCode} ${error.description}",
                        sourceAnchor = session.sourceAnchor,
                        contextId = session.contextId,
                        originKind = ORIGIN_WEBVIEW_ERROR,
                    )
                )
                session.observedRows.incrementAndGet()
                postTxn()
            } finally {
                session.endWrite()
            }
        }
    }
    }

    /** 装配 WebView 公共设置（可见页与无头引擎共用同一套安全配置）。 */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = userAgentProvider()
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.safeBrowsingEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = WebChromeClient()
    }

    /** 给阻断请求的无害空响应：真正断掉加载（不是放行）。 */
    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /**
     * 结算会话：写一条 originKind=[ORIGIN_WEBVIEW_SESSION] 的汇总行（浏览器页「结束并保存」
     * 与无头 [run] 收尾共用）。正文 `mode=` 标记来自 [WebViewCapture.Session.sessionMode]——
     * 可见页传 interactive、无头传 headless，是 list_captures/历史页识别会话来源的依据。
     */
    fun finishSession(session: WebViewCapture.Session, finalUrl: String, startedAtMs: Long) {
        // 两段式收尾消竞态：
        // 1) closeWrites 关写入闸——此后所有回调的新写请求被拒（闸外行不再入队）；闸内已开始
        //    的供给/记录块允许写完，其 recordAsync 在写库单线程队列里恒先于结算行入队，
        //    由最后一个 endWrite（或无在途时由本调用）触发 settle。
        // 2) 结算行真正落库后才注销活跃表（onSettled 在写库线程回调）：历史页在写入完成前
        //    查 isCaptureSessionActive 仍见活跃、拒绝删除；注销点即「会话不再有在途写入」点，
        //    之后删除拿到的就是含结算行的完整会话，不会再出现删后写回孤儿会话。
        session.closeWrites {
            logStore.recordAsync(
                HttpLogRecorder.Draft(
                    method = "WEBVIEW_CAPTURE",
                    url = session.entryUrl,
                    finalUrl = finalUrl,
                    statusCode = 0,
                    durationMs = System.currentTimeMillis() - startedAtMs,
                    responseBody = "WebView 抓包会话：entry=${session.entryUrl} final=$finalUrl captured=${session.capturedRows.get()} observed=${session.observedRows.get()} blocked=${session.blockedRows.get()} budgetExhausted=${session.sessionExhausted} mode=${session.sessionMode}",
                    redirectChain = listOf(finalUrl),
                    sourceAnchor = session.sourceAnchor,
                    contextId = session.contextId,
                    originKind = ORIGIN_WEBVIEW_SESSION,
                ),
                // 失败也注销：否则该 ctx 永远活跃、历史页永远删不掉；如实记日志不冒称已写。
                onSettled = { written ->
                    activeCaptureContextIds.remove(session.contextId)
                    if (!written) {
                        android.util.Log.w("WebViewCapture", "结算行未落库 contextId=${session.contextId}")
                    }
                },
            )
        }
    }

    // ------------------------------------------------------------------
    // 无头模式：MCP webview_capture 用——一次性 WebView，不可交互
    // ------------------------------------------------------------------

    /**
     * 无头抓包：临时 WebView 加载入口页，跑完即销毁。**不可交互**——没有用户点章节这一步；
     * 要交互式浏览/点击翻页请用应用内「日志→抓包→浏览器抓包」打开的可见抓包页。
     * 录制关闭即拒绝（抓包的意义是留证据），串行执行避免日志归属混淆。
     */
    suspend fun run(
        url: String,
        delayMs: Long = 800,
        timeoutSec: Int = 120,
        webJs: String? = null,
    ): CaptureOutcome {
        if (!recordingEnabled()) {
            throw CaptureBlockedException("HTTP 事务记录已关闭，浏览器抓包不会留下任何日志；请先到日志页开启记录后再抓包")
        }
        return gate.withLock {
            val result = load(url, delayMs, timeoutSec.coerceIn(10, 300) * 1000L, webJs)
            // 写库走异步队列：短轮询等齐（参照 CaptureOnce.collectLogs 的等 hop 语义）。
            val logs = collectLogs(result.contextId)
            val count = logs.size
            CaptureOutcome(
                result = result,
                logIds = logs.take(MAX_LOG_IDS).map { it.id },
                logCount = count,
                hint = "本次无头浏览器抓包共记录 $count 条事务（供给成功 ${result.capturedCount} 条、仅观察 ${result.observedCount} 条、阻断 ${result.blockedCount} 条" +
                    (if (result.exhausted) "，会话容量已耗尽" else "") +
                    (if (count > MAX_LOG_IDS) "，超出上限仅返回前 $MAX_LOG_IDS 条" else "") + "）",
                workflow = mapOf(
                    "workflow" to "webview_capture 发起→logIds/logCount 取回本会话→get_http_log(id) 看单条请求/响应头与正文→get_capture(contextId) 回看整链→list_captures 翻历史",
                    "getCapture" to "get_capture(contextId, offset, limit)：同一 contextId 的轻量分页+total",
                    "getHttpLog" to "get_http_log(id)：单条完整详情（含请求/响应头与脱敏正文片段）",
                    "listCaptures" to "list_captures(offset/limit)：按 cap:contextId 分组的会话摘要",
                    "next" to "看正文用 get_http_log(id)；要纯 HTML 结果可用 htmlPreview；要交互浏览/点击章节请到应用内「日志→抓包→浏览器抓包」开可见抓包页",
                ),
            )
        }
    }

    data class CaptureOutcome(
        val result: Result,
        val logIds: List<Long>,
        val logCount: Int,
        val hint: String,
        val workflow: Map<String, String>,
    )

    /** webview_eval 的取值结果：value 已对 Promise unwrap（fulfilled 原值转 String）。 */
    data class EvalOutcome(val finalUrl: String, val value: String, val elapsedMs: Long)

    /**
     * webview_eval：一次性 WebView 打开 [url] 执行 [script]。
     * Promise/thenable 只跑一次，状态挂唯一 token，之后 300ms 轮询同一 token 不重跑；
     * 非 Promise 仍按 null 重跑。不建抓包会话、不拦截供给（与 [run] 不同）。
     * webview_render / [run] 的 webJs 继续走 [WebViewLoadPlan.buildPollScript]，对齐阅读端 null 轮询。
     */
    suspend fun eval(
        url: String,
        script: String,
        delayMs: Long = 800,
        timeoutMs: Long = 60_000,
        resourceRecorder: ((method: String, requestUrl: String, headers: Map<String, String>) -> Unit)? = null,
    ): EvalOutcome = withTimeout(timeoutMs) {
        require(script.isNotBlank()) { "script 不能为空" }
        val plan = WebViewLoadPlan.plan(url, null)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val started = System.currentTimeMillis()
        val generation = AtomicInteger(0)
        var view: WebView? = null

        fun teardown() {
            main.removeCallbacksAndMessages(null)
            view?.stopLoading()
            view?.destroy()
            view = null
        }

        suspendCancellableCoroutine<EvalOutcome> { continuation ->
            fun finish(result: kotlin.Result<EvalOutcome>) {
                if (!closed.compareAndSet(false, true)) return
                main.post { teardown() }
                if (continuation.isActive) {
                    result.onSuccess(continuation::resume).onFailure(continuation::resumeWithException)
                }
            }
            continuation.invokeOnCancellation {
                closed.set(true)
                main.post { teardown() }
            }
            main.post {
                if (closed.get()) return@post
                runCatching {
                    WebView(context).also { webView ->
                        view = webView
                        configure(webView)
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(v: WebView, finishedUrl: String) {
                                if (closed.get()) return
                                val gen = generation.incrementAndGet()
                                val token = WebViewLoadPlan.newEvalToken()
                                val js = WebViewLoadPlan.buildEvalPollScript(script, token)
                                main.postDelayed({
                                    if (closed.get() || generation.get() != gen) return@postDelayed
                                    pollEval(v, plan.url, js, gen, generation, started, main, closed, ::finish)
                                }, delayMs.coerceIn(0, 10_000))
                            }

                            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                                resourceRecorder?.let { rec ->
                                    runCatching {
                                        rec(
                                            request.method ?: "GET",
                                            request.url?.toString().orEmpty(),
                                            request.requestHeaders ?: emptyMap(),
                                        )
                                    }
                                }
                                return null
                            }
                        }
                        webView.loadUrl(plan.url)
                    }
                }.onFailure { finish(kotlin.Result.failure(it)) }
            }
        }
    }

    /** 无头模式的 WebView 加载流程：onPageFinished + 稳定轮询后取 outerHTML / webJs 值。 */
    private suspend fun load(
        url: String,
        delayMs: Long,
        timeoutMs: Long,
        webJs: String?,
    ): Result = withTimeout(timeoutMs) {
        val session = newSession(url)
        val plan = WebViewLoadPlan.plan(session.entryUrl, null)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val started = System.currentTimeMillis()
        var view: WebView? = null
        var finalUrl = session.entryUrl

        fun teardown() {
            main.removeCallbacksAndMessages(null)
            view?.stopLoading()
            view?.destroy()
            view = null
        }

        suspendCancellableCoroutine<Result> { continuation ->
            fun finish(result: kotlin.Result<Result>) {
                if (!closed.compareAndSet(false, true)) return
                main.post { teardown() }
                if (continuation.isActive) {
                    result.onSuccess(continuation::resume).onFailure(continuation::resumeWithException)
                }
            }
            continuation.invokeOnCancellation {
                closed.set(true)
                main.post { teardown() }
            }
            main.post {
                if (closed.get()) return@post
                runCatching {
                    WebView(context).also { webView ->
                        view = webView
                        configure(webView)
                        // 无头模式：供给 client 之上叠一层 onPageFinished→稳定轮询收尾。
                        val inner = clientFor(session)
                        webView.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                                inner.shouldInterceptRequest(v, request)

                            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                                inner.shouldOverrideUrlLoading(v, request)

                            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) =
                                inner.onReceivedHttpError(v, request, errorResponse)

                            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) =
                                inner.onReceivedError(v, request, error)

                            override fun onPageFinished(v: WebView, finishedUrl: String) {
                                if (closed.get()) return
                                main.postDelayed({
                                    poll(v, webJs, session, started, main, closed, ::finish) { resolved ->
                                        finalUrl = resolved
                                    }
                                }, delayMs.coerceIn(0, 10_000))
                            }
                        }
                        webView.loadUrl(plan.url)
                    }
                }.onFailure { finish(kotlin.Result.failure(it)) }
            }
        }.also { result ->
            finishSession(session, finalUrl, started)
        }
    }

    // ------------------------------------------------------------------
    // 供给/记录内核（可见页与无头共用）
    // ------------------------------------------------------------------

    /** 仅观察行：请求头+方法+URL+观察限制说明，无响应行。 */
    private fun recordObservation(
        session: WebViewCapture.Session,
        request: WebResourceRequest,
        reqUrl: String,
        method: String,
        decision: WebViewCapture.Decision,
    ) {
        session.markRecorded(decision.txnSeq)
        logStore.recordAsync(
            HttpLogRecorder.Draft(
                method = method,
                url = reqUrl,
                statusCode = 0,
                requestHeaders = requestHeadersWithCookie(reqUrl, request.requestHeaders),
                requestBody = decision.requestNote.orEmpty(),
                sourceAnchor = session.sourceAnchor,
                contextId = session.contextId,
                originKind = ORIGIN_WEBVIEW_RESOURCE,
            )
        )
    }

    /** 供给阶段自身失败（网络异常/超时）：记错误行，返回 null 让 WebView 走自己的栈。 */
    private fun recordFetchError(
        session: WebViewCapture.Session,
        request: WebResourceRequest,
        reqUrl: String,
        method: String,
        decision: WebViewCapture.Decision,
        error: Throwable,
    ) {
        session.markRecorded(decision.txnSeq)
        logStore.recordAsync(
            HttpLogRecorder.Draft(
                method = method,
                url = reqUrl,
                statusCode = 0,
                requestHeaders = requestHeadersWithCookie(reqUrl, request.requestHeaders),
                error = "供给失败（已交回 WebView 自加载）：${error.message?.take(200) ?: error.javaClass.simpleName}",
                sourceAnchor = session.sourceAnchor,
                contextId = session.contextId,
                originKind = ORIGIN_WEBVIEW_ERROR,
            )
        )
    }

    /**
     * 拦截供给核心：对 GET/HEAD 用一次独立 OkHttp 请求取响应，如实记录后把字节喂回 WebView。
     *
     * - 3xx/204/304：如实记 OBSERVED_ONLY 行（含 Location）后 return null——WebView 对同一 URL
     *   重新发起、由它自己的栈处理跳转；不用 WebResourceResponse 供给 3xx（可能 IllegalArgumentException）；
     * - contentLength 已知且超过单资源上限 → 只观察不供给（截断流喂给页面会破坏渲染）；
     * - contentLength 未知：手动限长读，读满上限仍没到 EOF → 也只观察不供给；
     * - Accept-Encoding 不转发（OkHttp 自管 + BrotliInterceptor 透明解压），WebView 拿到
     *   的是已解压字节，响应头里剥掉 content-encoding/content-length；
     * - 字体/明确二进制：原始字节落盘 cacheDir/captures/<ctx>/，日志行记文件路径+大小。
     */
    private fun supplyAndRecord(
        session: WebViewCapture.Session,
        request: WebResourceRequest,
        reqUrl: String,
        method: String,
        decision: WebViewCapture.Decision,
    ): WebResourceResponse? {
        val reqHeaders = requestHeadersWithCookie(reqUrl, request.requestHeaders)
        val okRequest = Request.Builder()
            .url(reqUrl)
            .method(method, null)
            .apply {
                reqHeaders.forEach { (k, v) ->
                    val kl = k.lowercase()
                    if (!FORBIDDEN_SUPPLY_HEADERS.contains(kl)) header(k, v)
                }
            }
            .build()
        val startedAt = System.currentTimeMillis()
        newClient().newCall(okRequest).execute().use { response ->
            val statusCode = response.code
            val reason = response.message.ifBlank { defaultReason(statusCode).ifBlank { "OK" } }
            // 响应头扁平化：多值头用 "; " 拼（不换行——换行会损坏 Set-Cookie/Cookie 类头被下游
            // 当成多行拼接单值的语义；Set-Cookie 本身含逗号日期，按 "; " 连接是日志可读口径）。
            val respHeaders = response.headers.toMultimap()
                .mapValues { (_, values) -> values.joinToString("; ") }
            val contentLength = response.body?.contentLength() ?: -1L
            val contentType = response.body?.contentType()?.toString()
            val kind = if (decision.kind == WebViewCapture.ResourceKind.OTHER) {
                WebViewCapture.kindFromContentType(contentType)
            } else decision.kind

            // 3xx/204/304：如实记行后 return null——WebView 会对**同一个请求**重新发起
            // （不是"跳下一跳"，是同一 URL 重发，由它自己的网络栈拿到 3xx 后再自行决定跳转）。
            // 本行只是供给侧证据，Location 供排查用；不能说"下一跳已被记录"。
            if (!WebViewCapture.isSuppliableStatus(statusCode)) {
                val location = response.headers["Location"].orEmpty()
                session.markRecorded(decision.txnSeq)
                session.observedRows.incrementAndGet()
                logStore.recordAsync(
                    HttpLogRecorder.Draft(
                        method = method,
                        url = reqUrl,
                        finalUrl = location.takeIf { it.isNotBlank() } ?: response.request.url.toString(),
                        statusCode = statusCode,
                        durationMs = System.currentTimeMillis() - startedAt,
                        requestHeaders = reqHeaders,
                        responseHeaders = respHeaders,
                        error = "OBSERVED_ONLY：供给侧拿到 ${statusCode} 不供给——本行是 OkHttp 供给请求的证据，" +
                            "WebView 会对同一 URL 重新发起自行跳转（其后续请求是否出现取决于 WebView 自己的行为）" +
                            if (location.isNotBlank()) "（Location=$location）" else "",
                        redirectChain = listOfNotNull(location.takeIf { it.isNotBlank() }),
                        sourceAnchor = session.sourceAnchor,
                        contextId = session.contextId,
                        originKind = ORIGIN_WEBVIEW_RESOURCE,
                    )
                )
                return null
            }

            // contentLength 已知且超过单资源上限 / 会话容量耗尽：响应头阶段就观察，
            // **不读体**（不为记一条"太大"去把 MB 级字节拉一遍）。WebView 自己拿全量，页面不受损。
            val (budget, maxBytes) = session.budgetFor(contentLength)
            if (budget == WebViewCapture.Budget.DROP ||
                (contentLength > 0 && contentLength > session.resourceBudgetBytes)
            ) {
                session.markRecorded(decision.txnSeq)
                session.observedRows.incrementAndGet()
                logStore.recordAsync(
                    HttpLogRecorder.Draft(
                        method = method,
                        url = reqUrl,
                        statusCode = statusCode,
                        durationMs = System.currentTimeMillis() - startedAt,
                        requestHeaders = reqHeaders,
                        responseHeaders = respHeaders,
                        error = if (contentLength > session.resourceBudgetBytes) {
                            "OBSERVED_ONLY：响应声明 ${contentLength}B 超过单资源上限 ${session.resourceBudgetBytes}B，仅记录请求/响应头"
                        } else {
                            "OBSERVED_ONLY：会话容量耗尽，仅记录请求/响应头"
                        },
                        sourceAnchor = session.sourceAnchor,
                        contextId = session.contextId,
                        originKind = ORIGIN_WEBVIEW_RESOURCE,
                    )
                )
                return null
            }

            // 限长读：contentLength 未知/或虽已知但未超上限时，最多读 maxBytes+1 探测超长；
            // 超长只观察不供给（不截断喂页面）。已知超上限已在上面直接 return，根本到不了这里。
            val body = response.body
            val raw: ByteArray
            val overCap: Boolean
            if (body != null) {
                val source = body.source()
                source.request(maxBytes + 1)
                val buf = source.buffer
                overCap = buf.size > maxBytes
                raw = if (overCap) ByteArray(0) else buf.readByteArray(buf.size)
            } else {
                raw = ByteArray(0)
                overCap = false
            }
            if (overCap) {
                session.markRecorded(decision.txnSeq)
                session.observedRows.incrementAndGet()
                logStore.recordAsync(
                    HttpLogRecorder.Draft(
                        method = method,
                        url = reqUrl,
                        statusCode = statusCode,
                        durationMs = System.currentTimeMillis() - startedAt,
                        requestHeaders = reqHeaders,
                        responseHeaders = respHeaders,
                        error = "OBSERVED_ONLY：响应实际超过单资源上限 ${session.resourceBudgetBytes}B（读出即超限），仅记录请求/响应头",
                        sourceAnchor = session.sourceAnchor,
                        contextId = session.contextId,
                        originKind = ORIGIN_WEBVIEW_RESOURCE,
                    )
                )
                return null
            }

            session.consume(raw.size.toLong())
            session.markRecorded(decision.txnSeq)

            // 二进制/字体：与喂给 WebView 的同一份 raw 原始字节落盘 cacheDir/captures/<ctx>/，
            // 日志行记解析稳定的 [bodyFile=相对路径] 标记（导出器按它定位并内嵌 base64 字节）。
            // content-type 说谎时（验证码接口 text/html 返 PNG）按 magic bytes 兜底，保证图片走落盘不被当文本内联。
            val isBinary = kind == WebViewCapture.ResourceKind.FONT || kind == WebViewCapture.ResourceKind.IMAGE ||
                kind == WebViewCapture.ResourceKind.MEDIA || isBinaryContentType(contentType) ||
                looksLikeBinaryMagic(raw)
            var bodyFileRef: String? = null
            if (isBinary && raw.isNotEmpty() && raw.size.toLong() <= WebViewCapture.MAX_RESOURCE_FILE_BYTES) {
                bodyFileRef = runCatching {
                    val dir = File(context.cacheDir, "captures/${session.contextId.removePrefix("cap:")}").apply { mkdirs() }
                    val file = File(dir, "${decision.txnSeq}.bin")
                    file.writeBytes(raw)
                    "captures/${file.parentFile?.name}/${file.name}"
                }.getOrNull()
            }
            val bodyText = if (bodyFileRef != null) {
                "[binary ${raw.size}B ${contentType.orEmpty().ifBlank { "unknown" }}] ${WebViewCapture.bodyFileMark(bodyFileRef)}"
            } else {
                decodeBody(raw, raw.size, contentType)
            }
            logStore.recordAsync(
                HttpLogRecorder.Draft(
                    method = method,
                    url = reqUrl,
                    finalUrl = response.request.url.toString(),
                    statusCode = statusCode,
                    durationMs = System.currentTimeMillis() - startedAt,
                    requestHeaders = reqHeaders,
                    responseHeaders = respHeaders,
                    // WebView 抓包供给行显式申请更大的独立正文上限（HttpLogCaps 钳制到 512K chars），
                    // 让整页 HTML/章节正文可回看；其余来源不传该参数、维持 8_192 旧口径。
                    bodyMaxChars = HttpLogCaps.MAX_WEBVIEW_BODY_CHARS,
                    responseBody = buildString {
                        if (kind != WebViewCapture.ResourceKind.OTHER) append("[kind=${kind.name.lowercase()}] ")
                        append(bodyText)
                    },
                    sourceAnchor = session.sourceAnchor,
                    contextId = session.contextId,
                    originKind = ORIGIN_WEBVIEW_RESOURCE,
                )
            )
            session.capturedRows.incrementAndGet()

            // 喂回 WebView：剥掉 OkHttp 已透明解压的 content-encoding / content-length。
            val outHeaders = respHeaders.toMutableMap()
            outHeaders.keys.toList().forEach { k ->
                if (k.equals("Content-Encoding", true) || k.equals("Content-Length", true)) outHeaders.remove(k)
            }
            return WebResourceResponse(
                contentType?.substringBefore(';')?.trim().orEmpty().ifBlank { "application/octet-stream" },
                contentType?.let { ct -> Regex("(?i)charset=([^;]+)").find(ct)?.groupValues?.get(1)?.trim() } ?: "UTF-8",
                statusCode,
                reason,
                outHeaders,
                ByteArrayInputStream(raw),
            )
        }
    }

    /**
     * 每请求一个短生命周期 client：不跟随重定向（3xx 还给 WebView 自跳）、Brotli 透明解压。
     * DNS 走 [supplyDns]：只返回已判定为公网的钉住地址，连接级杜绝重绑定/二次解析漂移。
     */
    private fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(::supplyDns)
        .connectTimeout(SUPPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SUPPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .addInterceptor(BrotliInterceptor)
        .build()

    /**
     * 请求头补齐：WebView 没带 Cookie 时用 CookieManager 的会话 Cookie 兜（供体与 WebView 身份一致）。
     * **仅 https 目标**才回带 CookieManager 的 Cookie——getCookie 对 http URL 也会返回该域的
     * Secure Cookie，若兜底进明文 http 供给请求会把安全 Cookie 泄给不加密链路。
     */
    private fun requestHeadersWithCookie(reqUrl: String, headers: Map<String, String>?): Map<String, String> {
        val base = headers ?: emptyMap()
        val hasCookie = base.keys.any { it.equals("Cookie", ignoreCase = true) }
        if (hasCookie) return base
        if (!reqUrl.startsWith("https://", ignoreCase = true)) return base
        val cookie = runCatching { CookieManager.getInstance().getCookie(reqUrl) }.getOrNull()
        return if (cookie.isNullOrBlank()) base else base + ("Cookie" to cookie)
    }

    /** 入账文本：文本/JSON/脚本文类按 charset 解码；二进制类只写摘要不落乱码。 */
    private fun decodeBody(raw: ByteArray, bytesToLog: Int, contentType: String?): String {
        val ct = contentType.orEmpty().lowercase()
        val binary = ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") ||
            ct.startsWith("font/") || ct.contains("octet-stream") || ct.contains("font")
        if (binary) return "(binary ${raw.size}B ${ct.ifBlank { "unknown" }}，不入正文)"
        val charset = contentType?.let { Regex("(?i)charset=([^;]+)").find(it)?.groupValues?.get(1)?.trim() }
        return runCatching {
            if (charset.isNullOrBlank()) String(raw, 0, bytesToLog) else String(raw, 0, bytesToLog, charset(charset))
        }.getOrElse { String(raw, 0, bytesToLog) }
    }

    private fun isBinaryContentType(contentType: String?): Boolean {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") ||
            ct.startsWith("font/") || ct == "application/octet-stream" || ct == "binary/octet-stream" ||
            ct.startsWith("application/font") || ct.contains("font")
    }

    /**
     * Content-Type 说谎时的兜底：看响应头几个字节是不是常见二进制魔数。
     * 覆盖：PNG / JPEG / GIF / WebP / WOFF / WOFF2 / ZIP（EPUB/APK）。
     * 验证码接口常见 text/html 返 PNG，这一步能把它们正确判为二进制走落盘，不当文本内联。
     */
    private fun looksLikeBinaryMagic(raw: ByteArray): Boolean {
        if (raw.size < 4) return false
        fun b(i: Int) = raw.getOrElse(i) { 0 }.toInt() and 0xFF
        return when {
            // PNG: 89 50 4E 47
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> true
            // JPEG: FF D8 FF
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> true
            // GIF: "GIF8"
            b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38 -> true
            // WebP "RIFF....WEBP"
            b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 &&
                raw.size >= 12 && b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> true
            // WOFF "wOF2" / "wOFF"
            b(0) == 0x77 && b(1) == 0x4F && (b(2) == 0x46 && (b(3) == 0x32 || b(3) == 0x46)) -> true
            // ZIP (EPUB/APK/字体压缩包): "PK\x03\x04"
            b(0) == 0x50 && b(1) == 0x4B && b(2) == 0x03 && b(3) == 0x04 -> true
            else -> false
        }
    }

    private fun defaultReason(code: Int): String = when (code) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
        301 -> "Moved Permanently"; 302 -> "Found"; 303 -> "See Other"
        304 -> "Not Modified"; 307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
        else -> ""
    }

    private suspend fun collectLogs(contextId: String): List<CaptureOnce.LogEntry> {
        if (!recordingEnabled()) return emptyList()
        var entries: List<CaptureOnce.LogEntry> = emptyList()
        repeat(10) {
            entries = logsByContextId(contextId)
            // 等到至少出现会话汇总行（最后落库）或行数稳定，再放行结果。
            val hasSession = entries.any { it.originKind == ORIGIN_WEBVIEW_SESSION }
            if (hasSession || entries.isNotEmpty() && it >= 4) return entries
            delay(120)
        }
        return entries
    }

    /**
     * 会话内落盘资源目录（只读视角）：列出 `cacheDir/captures/<ctxDir>/` 下真实存在的
     * 资源文件，供 MCP get_capture_resource 校验「logId 声明的 bodyFile 确实在该会话
     * 目录里且未过期/删除」。目录不存在（该会话没写过二进制，或已被 7 天过期清理）返回空。
     * 不递归、不列其他目录；文件名只保留安全段（与写盘端 `<txnSeq>.bin` 同口径）。
     */
    fun listCaptureResources(contextId: String): List<CaptureResource> {
        val dirName = WebViewCapture.captureDirName(contextId) ?: return emptyList()
        val dir = File(context.cacheDir, "captures/$dirName")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' } }
            .map { CaptureResource(name = "captures/$dirName/${it.name}", bytes = it.length(), lastModified = it.lastModified()) }
            .sortedBy { it.name }
    }

    /** 按 logId 校验后读该会话落盘资源的一段字节；越界/非本会话/已清理一律返回 null 由调用方报错。 */
    fun readCaptureResource(contextId: String, resourceName: String, offset: Int, length: Int): CaptureResourceChunk? {
        val file = WebViewCapture.captureResourcePath(context.cacheDir, contextId, resourceName) ?: return null
        if (!file.isFile) return null
        val size = file.length()
        val off = offset.coerceAtLeast(0)
        if (off.toLong() >= size && size > 0) return CaptureResourceChunk(resourceName, size, off, ByteArray(0), eof = true)
        val take = minOf(length.toLong(), size - off).toInt().coerceAtLeast(0)
        // 不用 InputStream.readNBytes（Java 9+，minSdk=26 且无 desugaring，在低版本会 NoSuchMethodError）：
        // 手动循环 skip（skip 允许短跳）+ 定长缓冲循环 read 到 take 或 EOF。
        val bytes = runCatching {
            file.inputStream().use { ins ->
                var remaining = off.toLong()
                while (remaining > 0) {
                    val skipped = ins.skip(remaining)
                    if (skipped <= 0) break          // skip 不再前进（已到 EOF 或流异常），按现有可读数据返回
                    remaining -= skipped
                }
                val buf = ByteArray(take)
                var read = 0
                while (read < take) {
                    val n = ins.read(buf, read, take - read)
                    if (n <= 0) break                // EOF：返回实际读到的前缀
                    read += n
                }
                if (read == take) buf else buf.copyOf(read)
            }
        }.getOrNull() ?: return null
        return CaptureResourceChunk(resourceName, size, off, bytes, eof = off + bytes.size >= size)
    }

    /** 会话落盘资源目录里的一个文件（name 是 `captures/<ctxDir>/<file>` 相对路径）。 */
    data class CaptureResource(val name: String, val bytes: Long, val lastModified: Long)

    /** 资源分段读取结果：totalBytes 是文件真实大小，eof=已读到文件尾（含空段）。 */
    data class CaptureResourceChunk(
        val name: String,
        val totalBytes: Long,
        val offset: Int,
        val bytes: ByteArray,
        val eof: Boolean,
    )

    /**
     * webview_eval 轮询：脚本由 [WebViewLoadPlan.buildEvalPollScript] 一次构造（token 已烘焙），
     * 每 300ms 重评同一段 JS，不换 token、不重跑用户脚本（Promise pending 时脚本自己 return null）。
     * 文档级跳转由 [eval] 的 generation 作废旧链；SPA pushState 由脚本内 href 比对丢弃旧 token。
     */
    private fun pollEval(
        view: WebView,
        entryUrl: String,
        js: String,
        gen: Int,
        generation: AtomicInteger,
        started: Long,
        main: android.os.Handler,
        closed: AtomicBoolean,
        finish: (kotlin.Result<EvalOutcome>) -> Unit,
    ) {
        if (closed.get() || generation.get() != gen) return
        view.evaluateJavascript(js) { raw ->
            if (closed.get() || generation.get() != gen) return@evaluateJavascript
            val value = decode(raw)
            when {
                value == null -> main.postDelayed(
                    { pollEval(view, entryUrl, js, gen, generation, started, main, closed, finish) },
                    WebViewLoadPlan.POLL_INTERVAL_MS,
                )
                value.startsWith("__STUDIO_ERROR__") -> finish(
                    kotlin.Result.failure(IllegalStateException(value.removePrefix("__STUDIO_ERROR__"))),
                )
                else -> {
                    val resolved = runCatching {
                        WebViewLoadPlan.requirePublicHttpUrl(view.url ?: entryUrl, "webview_eval finalUrl")
                    }.getOrElse { err ->
                        finish(kotlin.Result.failure(IllegalStateException("webview_eval 重定向落点不合法：${err.message}")))
                        return@evaluateJavascript
                    }
                    CookieManager.getInstance().flush()
                    finish(
                        kotlin.Result.success(
                            EvalOutcome(
                                finalUrl = resolved,
                                value = value,
                                elapsedMs = System.currentTimeMillis() - started,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * onPageFinished 后的轮询（仅无头 webview_capture）：与 [WebViewPageLoader.poll] 同协议——
     * 必须走 [WebViewLoadPlan.buildPollScript]，对齐阅读端 webJs「返回 null 重跑」语义，
     * **不要**换成 eval 的 Promise unwrap。webJs 为 null 时直接取 documentElement.outerHTML，
     * 返回 null 重试到外层超时；__STUDIO_ERROR__ 抛错。finalUrl 校验仍走 [WebViewLoadPlan.requirePublicHttpUrl]。
     */
    private fun poll(
        view: WebView,
        webJs: String?,
        session: WebViewCapture.Session,
        started: Long,
        main: android.os.Handler,
        closed: AtomicBoolean,
        finish: (kotlin.Result<Result>) -> Unit,
        onFinalUrl: (String) -> Unit,
    ) {
        if (closed.get()) return
        val script = WebViewLoadPlan.buildPollScript(webJs)
        view.evaluateJavascript(script) { raw ->
            if (closed.get()) return@evaluateJavascript
            val value = decode(raw)
            when {
                value == null -> main.postDelayed(
                    { poll(view, webJs, session, started, main, closed, finish, onFinalUrl) },
                    WebViewLoadPlan.POLL_INTERVAL_MS,
                )
                value.startsWith("__STUDIO_ERROR__") -> finish(
                    kotlin.Result.failure(IllegalStateException(value.removePrefix("__STUDIO_ERROR__")))
                )
                else -> {
                    val resolved = runCatching {
                        WebViewLoadPlan.requirePublicHttpUrl(view.url ?: session.entryUrl, "webview_capture finalUrl")
                    }.getOrElse { err ->
                        finish(kotlin.Result.failure(IllegalStateException("webview_capture 重定向落点不合法：${err.message}")))
                        return@evaluateJavascript
                    }
                    CookieManager.getInstance().flush()
                    onFinalUrl(resolved)
                    finish(
                        kotlin.Result.success(
                            Result(
                                contextId = session.contextId,
                                entryUrl = session.entryUrl,
                                finalUrl = resolved,
                                elapsedMs = System.currentTimeMillis() - started,
                                sourceAnchor = session.sourceAnchor,
                                capturedCount = session.capturedRows.get(),
                                observedCount = session.observedRows.get(),
                                blockedCount = session.blockedRows.get(),
                                html = value,
                                exhausted = session.sessionExhausted,
                            )
                        )
                    )
                }
            }
        }
    }

    private fun decode(value: String?): String? {
        if (value == null || value == "null") return null
        return runCatching { com.google.gson.JsonParser.parseString(value).takeUnless { it.isJsonNull }?.asString }
            .getOrElse { value.trim('"') }
    }

    /**
     * DNS 私网守卫：解析 host 一次并缓存 3 分钟。
     * - 解析到私网/回环/保留地址 → 返回非空原因（decide 拒绝）；
     * - 解析失败/超时 → 返回 null 放行（WebView/OkHttp 自己会报 DNS 错，不双判）；
     * - 解析到全公网 → 返回 null 并把地址钉进 [DnsVerdict.addrs] 供 [supplyDns] 复用。
     */
    private fun dnsGuardCached(host: String): String? = resolveAndPin(host).reason

    /**
     * 连接级 DNS（OkHttp Dns 接口）：只返回 [dnsGuardCached] 已钉住的公网地址，
     * 绝不二次系统解析——拦住「decide 时公网、建连时被重绑定到私网」的 TOCTOU。
     * 未钉住（miss/过期）时现解析一次并复用同一判定。
     */
    private fun supplyDns(hostname: String): List<InetAddress> {
        val verdict = resolveAndPin(hostname)
        if (verdict.reason != null) {
            throw java.net.UnknownHostException("DNS 解析被拒绝：${verdict.reason}")
        }
        if (verdict.addrs.isEmpty()) {
            throw java.net.UnknownHostException("DNS 无可用地址：$hostname")
        }
        return verdict.addrs
    }

    /** 解析 + 缓存（带 TTL）：全公网才钉地址；任一私网/回环/保留地址即整体拒绝。 */
    private fun resolveAndPin(host: String): DnsVerdict {
        val now = System.currentTimeMillis()
        dnsCache[host]?.let { if (now < it.expiresAt) return it }
        val verdict = runCatching {
            val addrs = InetAddress.getAllByName(host).toList()
            val bad = addrs.firstOrNull { LogFilterUtils.isLoopbackOrPrivate(it.hostAddress.orEmpty()) }
            if (bad != null) {
                DnsVerdict(now + DNS_CACHE_MS, "解析到私网/回环/保留地址 ${bad.hostAddress}", emptyList())
            } else {
                DnsVerdict(now + DNS_CACHE_MS, null, addrs)
            }
        }.getOrElse { DnsVerdict(now + DNS_FAIL_CACHE_MS, null, emptyList()) }
        dnsCache[host] = verdict
        return verdict
    }

    data class Result(
        val contextId: String,
        val entryUrl: String,
        val finalUrl: String,
        val elapsedMs: Long,
        val sourceAnchor: String?,
        /** 拦截供给成功（OkHttp 供给请求已记完整行并喂回 WebView）的资源行数。 */
        val capturedCount: Int,
        /** 仅记证据行的资源数（POST 观察、超大、容量耗尽、供给失败、3xx 自跳观察、HTTP/加载错误）。 */
        val observedCount: Int,
        /** 被阻断（私网/非法 URL/DNS 私网/超上限）的请求数。 */
        val blockedCount: Int,
        /** 主文档 HTML（webJs 为空时是 outerHTML；非空时是 webJs 求值结果）。 */
        val html: String,
        /** 会话容量是否已耗尽（后续行只剩请求行）。 */
        val exhausted: Boolean,
    )

    companion object {
        /** WebView 抓包资源行 originKind。 */
        const val ORIGIN_WEBVIEW_RESOURCE = "webview_capture_resource"
        /** 会话汇总行 originKind：一条 cap: 会话一条。 */
        const val ORIGIN_WEBVIEW_SESSION = "webview_capture"
        /** 供给失败/HTTP 错误/加载失败行 originKind。 */
        const val ORIGIN_WEBVIEW_ERROR = "webview_capture_error"
        /** 被阻断行（私网/非法 URL/DNS 私网/超上限）originKind。 */
        const val ORIGIN_WEBVIEW_BLOCKED = "webview_capture_blocked"
        /** 供给用 OkHttp 单次请求超时：挡住慢站拖死拦截线程（WebView 侧有自己的超时兜底）。 */
        private const val SUPPLY_TIMEOUT_MS = 15_000L
        /** DNS 私网守卫结果缓存时长。 */
        private const val DNS_CACHE_MS = 3 * 60 * 1000L
        /** DNS 解析失败的短负缓存：失败不钉地址，但 30s 内不反复打 DNS。 */
        private const val DNS_FAIL_CACHE_MS = 30 * 1000L
        /** 结果里带回的 logIds 上限：与逐次抓包同口径。 */
        private const val MAX_LOG_IDS = 200
        /** 供给时不转发的请求头（Host/Content-Length/Connection 由 OkHttp 自管；Accept-Encoding 让它自管解码）。 */
        private val FORBIDDEN_SUPPLY_HEADERS = setOf(
            "host", "content-length", "connection", "transfer-encoding", "upgrade",
            "accept-encoding",
        )
    }
}
