package com.mina.legadostudio.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * 面向 MCP 的**持续**阅读 Web 页面会话（版本无关：不依赖任何阅读 HTTP 接口契约）。
 *
 * 用法（每次 MCP 调用都可能命中同一个实例，SPA 的 JS 状态因此跨调用保留）：
 * ```
 * open() / snapshot() / click(selector, index) / pressEnter(selector, index) /
 * fill(selector, text, index) / scroll(deltaY) / back() / close()
 * ```
 *
 * 安全边界（与 [ReaderWebUiPolicy] 的纯判定一一对应）：
 * - 目标只来自 `configProvider()` 里用户保存的 `baseUrl`；本类**没有** host/URL/headers/JS 参数，
 *   模型只能给 selector、文本与滚动量，全部作为字面量嵌进固定脚本，不存在 eval 模型脚本的路径；
 * - 主导航与重定向只允许与已保存 origin 严格同 scheme + host + port；跨 origin、非 http(s) 一律不导航；
 * - 子资源允许公网（阅读页面可能引 CDN），跨 origin 的回环/私网地址返回空响应阻断；
 * - JavaScript + DOM storage 开启；file/content access 关闭；不注册任何 JS bridge，
 *   也不注入旧阅读 Web 令牌或自定义请求头；
 * - 不写日志、不落盘截图；快照只回同源 path（敏感查询参数已剔除），password/hidden 等输入值不回显。
 *
 * 线程与生命周期：WebView 的创建、操作、销毁全部在主线程；每次调用有超时上限；会话空闲
 * [idleTimeoutMs] 后自动销毁；`close()` 幂等；调用协程被取消时销毁会话，避免 WebView 泄漏。
 * 用户把已保存的 origin 改掉后，下一次调用即销毁旧会话，不会带着旧页面的 JS/存储状态去新地址。
 *
 * 无窗口说明：MCP 服务没有 Activity，WebView 以应用 Context 创建、手动 measure/layout 并
 * 使用软件层，以便 DOM 布局与 `getBoundingClientRect` 有真实尺寸；这不等于「真机 UI 已验证」。
 */
class ReaderWebUiSession(
    context: Context,
    private val configProvider: () -> ReaderWebConfigStore.Config?,
    private val limits: ReaderWebUiPolicy.Limits = ReaderWebUiPolicy.Limits(),
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
) {
    /** @property code `NOT_CONFIGURED` / `NOT_OPENED` / `CONFIG_CHANGED` / `CLOSED` / `TIMEOUT` /
     *   `LOAD_FAILED` / `OFF_ORIGIN` / `SNAPSHOT_FAILED` / `TARGET_NOT_FOUND` /
     *   `INVALID_SELECTOR` / `INVALID_ARGUMENT` / `UNSUPPORTED_INPUT` /
     *   `NO_EFFECT` / `ACTION_UNVERIFIED` */
    class UiSessionException(val code: String, message: String) : Exception(message)

    private class Live(
        val identity: ReaderWebUiPolicy.SessionIdentity,
        val view: WebView,
    ) {
        val origin: ReaderWebUiPolicy.Origin get() = identity.origin

        var startedGeneration: Int = 0
        var finishedGeneration: Int = 0
        var mainFrameError: String? = null

        /** `shouldInterceptRequest` 在拦截线程读它，必须 volatile。 */
        @Volatile var destroyed: Boolean = false
    }

    /** 当前已保存配置：身份 + 明文令牌（令牌只用于返回前脱敏，不进身份对象、不落盘）。 */
    private class CurrentConfig(val identity: ReaderWebUiPolicy.SessionIdentity, val token: String)

    /**
     * 当前页面所属配置的不可变身份（规范化 origin + revision + 令牌指纹）；没有会话时为 null。
     *
     * 回包里的 origin/revision 若在调用前另读一次配置，可能与页面实际所属配置不是同一次
     * （读完配置到会话返回之间用户又改了）。工具层必须在动作成功后读它来标注页面归属，
     * 再与此刻的配置比对；不一致就丢弃这次结果，不能拿预读值冒充。
     */
    val pinnedIdentity: ReaderWebUiPolicy.SessionIdentity?
        get() = live?.let { state -> if (state.destroyed) null else state.identity }

    /** 当前页面所属配置的 revision；没有会话时为 null。 */
    val pinnedRevision: Long?
        get() = pinnedIdentity?.revision

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleCloser = Runnable { destroyNow() }
    /** 同一会话的 MCP 调用串行化：WebView 只有一个页面状态，并发操作只会互相破坏。 */
    private val mutex = Mutex()

    /** 主线程独占状态；工具层会从其它线程读 [pinnedRevision]，故对会话引用做 volatile。 */
    @Volatile private var live: Live? = null

    // ---------------------------------------------------------------- public API

    /**
     * 打开（或复用）会话首页并返回快照。
     * 固定 (origin, revision, 令牌指纹) 身份；地址或令牌任一变化都先销毁旧会话再重建。
     */
    suspend fun open(): ReaderWebUiPolicy.Snapshot = runCall {
        onMain {
            val current = currentConfig() ?: run {
                destroyNow()
                throw notConfigured()
            }
            val existing = live
            if (existing != null && !existing.destroyed &&
                ReaderWebUiPolicy.identityChange(existing.identity, current.identity) == null
            ) {
                touchIdle()
                return@onMain readSnapshotLocked(existing)
            }
            destroyNow()
            attach(current.identity)
            awaitReady(requireLoaded = true)
            requireIdentity()
            readSnapshotLocked(live ?: throw closed())
        }
    }

    /** 读取当前页面快照；未打开时抛 `NOT_OPENED`（不隐式打开，避免模型不知不觉发起导航）。 */
    suspend fun snapshot(): ReaderWebUiPolicy.Snapshot = runCall {
        onMain {
            requireIdentity()
            readSnapshotLocked(live ?: throw closed())
        }
    }

    /** 点击仅在观察到导航或 DOM 变化时返回快照；派发成功但未生效时抛 NO_EFFECT。 */
    suspend fun click(selector: String, index: Int = 0): ReaderWebUiPolicy.Snapshot = runCall {
        val problem = ReaderWebUiPolicy.selectorProblem(selector, limits)
        if (problem != null) throw invalidSelector(problem)
        val safeIndex = index.coerceIn(0, MAX_INDEX)
        onMain {
            requireIdentity()
            val state = live ?: throw closed()
            val before = ReaderWebUiPolicy.decodeJsResult(evalJs(ReaderWebUiPolicy.buildSignatureScript()))
            val generation = state.startedGeneration
            applyAction(ReaderWebUiPolicy.buildClickScript(selector, safeIndex), "NOT_FOUND" to "没有匹配该 selector 的可点击元素")
            val outcome = awaitActionObservation(state, before, generation)
            val snapshot = readSnapshotLocked(requireIdentity().let { live ?: throw closed() })
            requireObserved(outcome)
            snapshot
        }
    }

    /** 仅可编辑文本表单控件能接收 Enter；同源表单可 requestSubmit。 */
    suspend fun pressEnter(selector: String, index: Int = 0): ReaderWebUiPolicy.Snapshot = runCall {
        val problem = ReaderWebUiPolicy.selectorProblem(selector, limits)
        if (problem != null) throw invalidSelector(problem)
        val safeIndex = index.coerceIn(0, MAX_INDEX)
        onMain {
            requireIdentity()
            val state = live ?: throw closed()
            val before = ReaderWebUiPolicy.decodeJsResult(evalJs(ReaderWebUiPolicy.buildSignatureScript()))
            val generation = state.startedGeneration
            applyAction(ReaderWebUiPolicy.buildPressEnterScript(selector, safeIndex), "NOT_FOUND" to "没有匹配该 selector 的可编辑表单控件")
            val outcome = awaitActionObservation(state, before, generation)
            val snapshot = readSnapshotLocked(requireIdentity().let { live ?: throw closed() })
            requireObserved(outcome)
            snapshot
        }
    }

    /** 向 selector 匹配的第 index 个表单元素写入文本，触发 `input` + `change`，等稳定后返回快照。 */
    suspend fun fill(selector: String, text: String, index: Int = 0): ReaderWebUiPolicy.Snapshot = runCall {
        val problem = ReaderWebUiPolicy.selectorProblem(selector, limits)
        if (problem != null) throw invalidSelector(problem)
        if (text.length > ReaderWebUiPolicy.MAX_FILL_TEXT_CHARS) {
            throw UiSessionException(
                "INVALID_ARGUMENT",
                "填充文本超过 ${ReaderWebUiPolicy.MAX_FILL_TEXT_CHARS} 字符上限",
            )
        }
        val safeIndex = index.coerceIn(0, MAX_INDEX)
        onMain {
            requireIdentity()
            applyAction(
                ReaderWebUiPolicy.buildFillScript(selector, text, safeIndex),
                "NOT_FOUND" to "没有匹配该 selector 的可填充元素",
            )
            awaitReady(requireLoaded = false)
            readSnapshotLocked(requireIdentity().let { live ?: throw closed() })
        }
    }

    /** 纵向滚动（负值向上，绝对值上限 [ReaderWebUiPolicy.MAX_SCROLL_DELTA]），等稳定后返回快照。 */
    suspend fun scroll(deltaY: Int): ReaderWebUiPolicy.Snapshot = runCall {
        if (deltaY == 0) throw UiSessionException("INVALID_ARGUMENT", "滚动量不能为 0")
        onMain {
            requireIdentity()
            applyAction(ReaderWebUiPolicy.buildScrollScript(deltaY), null)
            awaitReady(requireLoaded = false)
            readSnapshotLocked(requireIdentity().let { live ?: throw closed() })
        }
    }

    /** 后退一页（WebView 历史），等稳定后返回快照；落点不在已保存 origin 时销毁会话并报 `OFF_ORIGIN`。 */
    suspend fun back(): ReaderWebUiPolicy.Snapshot = runCall {
        onMain {
            requireIdentity()
            val current = live ?: throw closed()
            if (!current.view.canGoBack()) throw UiSessionException("INVALID_ARGUMENT", "当前没有可后退的历史页面")
            // goBack 可能触发整页导航（onPageStarted 异步），也可能只是 SPA 的 history.back()
            // （只有 popstate，没有 onPageStarted）。两种都要识别，否则 SPA 场景会等到超时。
            val startedBefore = current.startedGeneration
            val urlBefore = current.view.url
            val signatureBefore = ReaderWebUiPolicy.decodeJsResult(evalJs(ReaderWebUiPolicy.buildSignatureScript()))
            current.view.goBack()
            awaitHistoryChange(current, startedBefore, urlBefore, signatureBefore)
            awaitReady(requireLoaded = false)
            verifyCurrentOrigin(current)
            readSnapshotLocked(requireIdentity().let { live ?: throw closed() })
        }
    }

    /** 销毁会话；幂等，可重复调用。 */
    suspend fun close() {
        // 与操作抢同一把锁：正在跑的操作结束后才销毁，不会留下半截 WebView
        mutex.withLock {
            withContext(NonCancellable + Dispatchers.Main.immediate) { destroyNow() }
        }
    }

    // ---------------------------------------------------------------- orchestration

    /**
     * 串行化并限时执行一次工具调用。
     *
     * 并发调用（两个 MCP 请求同时打进来）在 [mutex] 上排队，而不是互相把对方正在用的 WebView 销毁；
     * 超时只作用于**拿到锁之后**的操作本身。只有真正持有会话的调用被取消/超时才销毁 WebView，
     * 排队中被取消的调用不会误伤别人正在用的会话。
     */
    private suspend fun <T> runCall(block: suspend () -> T): T {
        // lock() 在 try 之外：被取消时不会进入 finally，也就不会误放别人的锁
        mutex.lock()
        try {
            return withTimeout(callTimeoutMs) {
                try {
                    block()
                } catch (cancel: CancellationException) {
                    // 取消/超时都会走到这里：WebView 状态已不可信，销毁后由下一次 open() 重建
                    withContext(NonCancellable + Dispatchers.Main.immediate) { destroyNow() }
                    throw cancel
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw UiSessionException(
                "TIMEOUT",
                "页面操作超时（${callTimeoutMs / 1000} 秒），会话已销毁，请重新 open",
            )
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    /**
     * 读一次已保存配置并构造成身份；未配置或地址无效返回 null。
     * 配置读取要解 AndroidKeyStore（令牌），放 IO 线程，不在主线程做磁盘/密钥库操作。
     */
    private suspend fun currentConfig(): CurrentConfig? {
        val config = withContext(Dispatchers.IO) { configProvider() } ?: return null
        val identity = ReaderWebUiPolicy.SessionIdentity.of(config) ?: return null
        return CurrentConfig(identity, config.token)
    }

    /** 入口复核：配置必须存在，且会话固定身份与当前配置完全一致，否则销毁旧 WebView 并报 CONFIG_CHANGED。 */
    private suspend fun requireIdentity(): CurrentConfig {
        val current = currentConfig() ?: run {
            destroyNow()
            throw notConfigured()
        }
        val state = live ?: throw UiSessionException("NOT_OPENED", "会话未打开：请先调用 open")
        if (state.destroyed) throw UiSessionException("NOT_OPENED", "会话未打开：请先调用 open")
        ReaderWebUiPolicy.identityChange(state.identity, current.identity)?.let { change ->
            destroyNow()
            throw configChanged(change)
        }
        return current
    }

    /**
     * 出口复核：快照返回前再读一次配置。入口到读完 DOM 之间用户可能改了地址/令牌，
     * 这时旧页面的内容绝不能挂上新配置的 revision——销毁会话并拒绝返回。
     */
    private suspend fun verifyIdentityBeforeReturn(state: Live): String {
        val current = currentConfig() ?: run {
            destroyNow()
            throw notConfigured()
        }
        ReaderWebUiPolicy.identityChange(state.identity, current.identity)?.let { change ->
            destroyNow()
            throw configChanged(change)
        }
        return current.token
    }

    private fun notConfigured() = UiSessionException(
        "NOT_CONFIGURED",
        "尚未配置阅读 Web 服务地址：请在 MCP 页「设置」→「阅读 Web 服务」填写并保存",
    )

    private fun configChanged(change: ReaderWebUiPolicy.IdentityChange) = UiSessionException(
        "CONFIG_CHANGED",
        "${change.reason}（${change.code}），旧页面会话已销毁：请重新 open，不要复用旧页面的观测结果",
    )

    private fun closed() = UiSessionException("CLOSED", "会话已关闭：请重新 open")

    private fun invalidSelector(problem: String): UiSessionException = when (problem) {
        "EMPTY_SELECTOR" -> UiSessionException("INVALID_SELECTOR", "selector 不能为空")
        "SELECTOR_TOO_LONG" -> UiSessionException(
            "INVALID_SELECTOR",
            "selector 超过 ${limits.maxSelectorChars} 字符上限",
        )
        else -> UiSessionException("INVALID_SELECTOR", "selector 含控制字符，不能用于定位元素")
    }

    // ---------------------------------------------------------------- WebView lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun attach(identity: ReaderWebUiPolicy.SessionIdentity) {
        val view = WebView(appContext)
        val state = Live(identity, view)
        live = state

        view.layoutParams = android.view.ViewGroup.LayoutParams(
            HEADLESS_VIEWPORT_WIDTH,
            HEADLESS_VIEWPORT_HEIGHT,
        )
        // 无窗口渲染：给出真实尺寸并走软件层，DOM 布局与 getBoundingClientRect 才有意义
        view.measure(
            View.MeasureSpec.makeMeasureSpec(HEADLESS_VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEADLESS_VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, HEADLESS_VIEWPORT_WIDTH, HEADLESS_VIEWPORT_HEIGHT)
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
        }
        // 不调用 addJavascriptInterface：页面脚本拿不到任何原生对象
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)

        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!isActive(state)) return
                state.startedGeneration++
                state.mainFrameError = null
            }

            override fun onPageFinished(v: WebView, url: String?) {
                if (!isActive(state)) return
                state.finishedGeneration = state.startedGeneration
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (!isActive(state)) return
                if (request?.isForMainFrame != true) return
                state.mainFrameError = "页面加载失败（${error?.errorCode ?: -1}）"
            }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (!isActive(state)) return true
                val decision = ReaderWebUiPolicy.navigationDecision(request.url?.toString(), state.origin)
                return !decision.allowed
            }

            @Deprecated("兼容旧 WebView 回调")
            override fun shouldOverrideUrlLoading(v: WebView, url: String?): Boolean {
                if (!isActive(state)) return true
                return !ReaderWebUiPolicy.navigationDecision(url, state.origin).allowed
            }

            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!isActive(state)) return blockedResponse()
                if (request.isForMainFrame) {
                    // 服务端 3xx 等不一定走 shouldOverrideUrlLoading：主文档同样只放行严格同 origin
                    val decision = ReaderWebUiPolicy.navigationDecision(request.url?.toString(), state.origin)
                    return if (decision.allowed) null else blockedResponse()
                }
                val decision = ReaderWebUiPolicy.subresourceDecision(request.url?.toString(), state.origin)
                return if (decision.allowed) null else blockedResponse()
            }
        }

        touchIdle()
        view.loadUrl(state.origin.rootUrl())
    }

    private fun isActive(state: Live): Boolean = live === state && !state.destroyed

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun touchIdle() {
        mainHandler.removeCallbacks(idleCloser)
        mainHandler.postDelayed(idleCloser, idleTimeoutMs)
    }

    private fun destroyNow() {
        mainHandler.removeCallbacks(idleCloser)
        val state = live
        live = null
        if (state == null) return
        state.destroyed = true
        val view = state.view
        runCatching { view.stopLoading() }
        runCatching { view.webChromeClient = null }
        runCatching { view.webViewClient = WebViewClient() }
        runCatching { view.loadUrl("about:blank") }
        runCatching { view.clearHistory() }
        runCatching { view.removeAllViews() }
        runCatching {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
        }
        runCatching { view.destroy() }
    }

    // ---------------------------------------------------------------- page operations

    /**
     * 等到「当前这次导航已结束」且连续两次稳定签名相同。
     *
     * @param requireLoaded 是否要求至少有一轮导航完成（open 用）
     */
    private suspend fun awaitReady(requireLoaded: Boolean) {
        val state = live ?: throw closed()
        var previous: String? = null
        while (true) {
            if (!isActive(state)) throw closed()
            val error = state.mainFrameError
            if (error != null) {
                destroyNow()
                throw UiSessionException("LOAD_FAILED", error)
            }
            val navigating = state.startedGeneration > state.finishedGeneration
            val waitingForNavigation = requireLoaded && state.finishedGeneration == 0
            if (navigating || waitingForNavigation) {
                previous = null
                delay(STABILITY_POLL_MS)
                continue
            }
            val signature = ReaderWebUiPolicy.decodeJsResult(evalJs(ReaderWebUiPolicy.buildSignatureScript()))
            if (ReaderWebUiPolicy.isStable(previous, signature)) {
                touchIdle()
                return
            }
            previous = signature
            delay(STABILITY_POLL_MS)
        }
    }

    private enum class ActionObservation { OBSERVED, DISPATCHED_UNVERIFIED, NO_EFFECT }

    /** 异步 SPA 留出固定短窗；首个变化后还需安静 360ms，绝不因派发本身判成功。 */
    private suspend fun awaitActionObservation(state: Live, before: String?, generation: Int): ActionObservation {
        val start = android.os.SystemClock.uptimeMillis()
        var previous: String? = null
        var lastChange = start
        var observed = false
        val baselineValid = before != null && before != "ERR"
        while (true) {
            if (!isActive(state)) throw closed()
            state.mainFrameError?.let { error ->
                destroyNow()
                throw UiSessionException("LOAD_FAILED", error)
            }
            val now = android.os.SystemClock.uptimeMillis()
            val navigating = state.startedGeneration > state.finishedGeneration
            val signature = if (navigating) null else ReaderWebUiPolicy.decodeJsResult(evalJs(ReaderWebUiPolicy.buildSignatureScript()))
            if (signature != previous) lastChange = now
            previous = signature
            val changed = state.startedGeneration > generation ||
                baselineValid && signature != null && signature != "ERR" && signature != before
            if (changed) observed = true
            val settled = signature != null && signature != "ERR" && signature.startsWith("complete|") &&
                !navigating && now - lastChange >= ACTION_SETTLE_MS
            if (observed && settled && now - start >= ACTION_MIN_OBSERVE_MS) {
                return if (changed) ActionObservation.OBSERVED else ActionObservation.NO_EFFECT
            }
            if (now - start >= ACTION_MAX_OBSERVE_MS || !observed && now - start >= ACTION_NO_EFFECT_MS) {
                return when {
                    navigating || !baselineValid || signature == null || signature == "ERR" -> ActionObservation.DISPATCHED_UNVERIFIED
                    changed -> ActionObservation.OBSERVED
                    else -> ActionObservation.NO_EFFECT
                }
            }
            delay(STABILITY_POLL_MS)
        }
    }

    private fun requireObserved(outcome: ActionObservation) {
        when (outcome) {
            ActionObservation.OBSERVED -> Unit
            ActionObservation.DISPATCHED_UNVERIFIED -> throw UiSessionException(
                "ACTION_UNVERIFIED", "事件已派发，但页面变化无法确认；不要当作导航或搜索成功",
            )
            ActionObservation.NO_EFFECT -> throw UiSessionException(
                "NO_EFFECT", "事件已派发，但观察窗口内未发现导航或 DOM 变化；不要当作点击或搜索成功",
            )
        }
    }

    /**
     * 等 goBack 真正生效：整页导航看 startedGeneration 推进，SPA 的 history.back() 只看
     * URL 或页面签名变化。两者都不发生说明 WebView 没有可回退项（例如同 URL 历史被合并），
     * 直接返回交给后面的稳定性等待与停靠点校验处理。
     */
    private suspend fun awaitHistoryChange(
        state: Live,
        startedBefore: Int,
        urlBefore: String?,
        signatureBefore: String?,
    ) {
        while (true) {
            if (!isActive(state)) throw closed()
            if (state.startedGeneration > startedBefore) return
            val urlNow = state.view.url
            if (urlNow != urlBefore) return
            val signatureNow = ReaderWebUiPolicy.decodeJsResult(evalJs(ReaderWebUiPolicy.buildSignatureScript()))
            if (signatureNow != null && signatureNow != signatureBefore) return
            delay(STABILITY_POLL_MS)
        }
    }

    private suspend fun applyAction(script: String, notFound: Pair<String, String>?) {
        val raw = evalJs(script)
        val result = ReaderWebUiPolicy.parseActionResult(raw)
        if (result.ok) return
        when (result.code) {
            "NO_MATCH", "INDEX_OUT_OF_RANGE" -> throw UiSessionException(
                notFound?.first ?: "TARGET_NOT_FOUND",
                notFound?.second ?: "没有匹配该 selector 的元素",
            )
            "INVALID_SELECTOR" -> throw UiSessionException("INVALID_SELECTOR", "selector 语法无效")
            "UNSUPPORTED_INPUT" -> throw UiSessionException("UNSUPPORTED_INPUT", "不支持填充文件类输入框")
            "UNSUPPORTED_TARGET" -> throw UiSessionException(
                "INVALID_ARGUMENT",
                "该元素不是可编辑的表单控件或可填充区域",
            )
            "UNSAFE_FORM" -> throw UiSessionException("INVALID_ARGUMENT", "表单目标不是当前同源页面，已拒绝提交")
            else -> throw UiSessionException("SCRIPT_ERROR", "页面脚本执行失败（${result.code ?: "UNKNOWN"}）")
        }
    }

    /**
     * 读 DOM、出口复核配置身份、再用**最新保存的令牌**脱敏后才返回。
     *
     * 顺序刻意如此：先在读完 DOM 后复核身份（中途改配置就把旧页面判为不可用），
     * 再取复核时读到的令牌做替换——令牌从入口到出口若被改过，[verifyIdentityBeforeReturn]
     * 会先抛 `CONFIG_CHANGED`，不会走到脱敏。
     */
    private suspend fun readSnapshotLocked(state: Live): ReaderWebUiPolicy.Snapshot {
        if (!isActive(state)) throw closed()
        val raw = evalJs(ReaderWebUiPolicy.buildSnapshotScript(limits))
        return when (val parsed = ReaderWebUiPolicy.parseSnapshot(raw, state.origin, limits)) {
            is ReaderWebUiPolicy.SnapshotParse.Ok -> {
                // 出口复核同时给出「当前保存的令牌」；入口读到的那份已过时，不再使用
                val token = verifyIdentityBeforeReturn(state)
                touchIdle()
                ReaderWebUiPolicy.redactToken(parsed.snapshot, token)
            }
            ReaderWebUiPolicy.SnapshotParse.OffOrigin -> {
                destroyNow()
                throw UiSessionException(
                    "OFF_ORIGIN",
                    "页面已离开已保存的阅读 Web 服务地址，会话已销毁：请重新 open",
                )
            }
            ReaderWebUiPolicy.SnapshotParse.Malformed -> throw UiSessionException(
                "SNAPSHOT_FAILED",
                "读取页面快照失败：页面脚本可能异常，可重新 open 后再试",
            )
        }
    }

    private fun verifyCurrentOrigin(state: Live) {
        val decision = ReaderWebUiPolicy.navigationDecision(state.view.url, state.origin)
        if (!decision.allowed) {
            destroyNow()
            throw UiSessionException(
                "OFF_ORIGIN",
                "页面已离开已保存的阅读 Web 服务地址，会话已销毁：请重新 open",
            )
        }
    }

    private suspend fun evalJs(script: String): String? {
        val state = live ?: throw closed()
        if (!isActive(state)) throw closed()
        return suspendCancellableCoroutine { continuation ->
            state.view.evaluateJavascript(script) { raw ->
                if (continuation.isActive) continuation.resume(raw)
            }
        }
    }

    companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 20_000L
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
        private const val STABILITY_POLL_MS = 120L
        private const val ACTION_SETTLE_MS = 360L
        private const val ACTION_MIN_OBSERVE_MS = 720L
        private const val ACTION_NO_EFFECT_MS = 1_800L
        private const val ACTION_MAX_OBSERVE_MS = 2_800L
        private const val MAX_INDEX = 1_000_000
        private const val HEADLESS_VIEWPORT_WIDTH = 1080
        private const val HEADLESS_VIEWPORT_HEIGHT = 1920
    }
}
