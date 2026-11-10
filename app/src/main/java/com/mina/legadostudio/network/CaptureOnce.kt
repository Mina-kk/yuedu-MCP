package com.mina.legadostudio.network

import com.mina.legadostudio.domain.LogFilterUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** 逐次抓包被安全边界拒绝时抛出（message 面向用户，不含内部细节）。 */
class CaptureBlockedException(message: String) : Exception(message)

/**
 * 「逐次抓包」共用编排：UI 与 MCP capture_once 同一条链路。
 *
 * - 每次发起生成唯一 `cap:<UUID>` contextId，本次请求产生的事务日志带同一 contextId，事后按 contextId 精确取回。
 *   重定向中间每一跳（3xx + Location）另记一条独立脱敏日志：`url` 为本跳请求 URL、`finalUrl` 为下一跳目标，
 *   `statusCode` 为 3xx、`responseHeaders` 含 Location/Set-Cookie(脱敏)，`originKind="capture_hop"`；
 *   被 SSRF redirect guard 拦截的一跳也会先落库再中断（保留 3xx 证据），且不触发自身 follow 下一跳。
 *   最终响应仍维持原有的事务日志（`originKind="capture_once"`），`redirectChain` 字符串结构不变。
 *   注意：中间跳日志的 `redirectChain` 恒为 []——归属找链上目标请看该行的 url/finalUrl 或汇总行。
 * - sourceAnchor 用书源初始域名的注册域（eTLD+1）；中间跳日志带同一个锚点。
 * - 录制开关关闭时直接拒绝（抓包的意义是留证据），不代为开启。
 * - 安全边界：仅公网 HTTP/HTTPS；初始目标、每一跳重定向目标（HttpFetcher resolve 成绝对 URL 后校验，
 *   覆盖 //host、相对路径、公网→私网）、DNS 解析结果（域名解析到私网/回环即拒）三层拦截。
 * - WebView 模式域直接拒绝：WebView 内部跳转/子资源不受逐跳校验约束，无法保证不触达内网。
 * - 串行执行（同一时刻只允许一次手动抓包），避免 UI/MCP 并发把日志归属搅乱。
 */
class CaptureOnce(
    private val fetcherFactory: (
        unsafeRedirectGuard: (String) -> String?,
        unsafeDnsGuard: (java.net.InetAddress) -> String?,
        perHop: (HttpFetcher.RedirectHop) -> Unit,
    ) -> HttpFetcher,
    private val logStore: LogStore,
    private val requiresWebView: (String) -> Boolean,
    /**
     * 测试缝：非空时替代入口字面量校验（validateTarget 的 http(s)/凭据/私网检查），仅放行 URL 字面量；
     * redirectGuard/dnsGuard 仍由 execute 内部强制，三层 SSRF 拦截不受影响。生产代码不传。
     */
    private val entryTargetValidator: ((String) -> Unit)? = null,
    /**
     * 测试缝：逐跳/DNS 守卫分量替换；分量为 null 时回落内建 redirectGuard/dnsGuard。
     * 供 JVM 测试放行 MockWebServer 的 localhost 解析/跳转（生产不传，三守卫全量在线）。
     */
    private val guardOverrides: GuardOverrides? = null,
) {
    /** 测试缝守卫组：分量 null = 用内建实现；非 null = 替换该层判定。 */
    class GuardOverrides(
        val redirect: ((String) -> String?)? = null,
        val dns: ((java.net.InetAddress) -> String?)? = null,
    )
    /** 录制开关 + 按 contextId 读日志的最小依赖面，Android 侧由 HttpLogRecorder+StudioDao 薄壳实现，JVM 测试注入 fake。 */
    interface LogStore {
        val recordingEnabled: Boolean
        /** Stores with asynchronous writes must enqueue this callback behind every accepted write. */
        fun afterPendingWrites(onSettled: () -> Unit) { onSettled() }
        suspend fun logsByContextId(contextId: String): List<LogEntry>
        /**
         * 写入一条重定向中间跳的脱敏日志（App 侧桥接 HttpLogRecorder：调用线程构建实体、异步落库）。
         * 默认 no-op：JVM 测试/自定义 store 不实现时逐跳证据直接丢弃，不影响请求链。
         */
        fun recordHop(hop: HttpFetcher.RedirectHop, sourceAnchor: String?, contextId: String, originKind: String) {}
    }
    data class Params(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val charset: String? = null,
        val timeoutSec: Int = 30,
    )

    data class LogEntry(
        val id: Long,
        val method: String,
        val url: String,
        val finalUrl: String,
        val statusCode: Int,
        val durationMs: Long,
        val error: String,
        val originKind: String?,
        /**
         * 二进制落盘资源相对路径（`captures/<ctxDir>/<file>`，来自正文里的 [bodyFile=…] 标记）。
         * 为 null = 该行没有关联的落盘二进制资源。由调用方从 responseBody 提取后填入——
         * 旧字段构造方不传它（保持既有调用点兼容），需要资源目录的调用方（get_capture_resource）
         * 用完整实体填充。
         */
        val bodyFile: String? = null,
    )

    data class Result(
        val contextId: String,
        val sourceAnchor: String?,
        val viaWebView: Boolean,
        val code: Int,
        val finalUrl: String,
        val elapsedMs: Long,
        val redirectChain: List<String>,
        val bodyNote: String,
        /** 按 cap:contextId 取回的本批日志摘要（含中间跳，最多 MAX_LOG_IDS 条）。 */
        val logs: List<LogEntry>,
        /** 给 MCP/UI 用来 get_http_log(id) 取详情的稳定 ID 列表（已截断到 MAX_LOG_IDS）。 */
        val logIds: List<Long>,
        /** 本批 contextId 实际落库总数（可能 > logs.size）。 */
        val logCount: Int,
        /** 子集：最终落点事务（originKind 非 capture_hop）的 logId，便于直接取详情。 */
        val finalLogIds: List<Long>,
        /** 子集：重定向中间跳（originKind=capture_hop）的 logId，按发生顺序。 */
        val hopLogIds: List<Long>,
        /** 记录被关闭等原因导致日志不落库时的用户提示（入口已拦截关闭，此处保留兜底）。 */
        val recordingDisabled: Boolean,
        /** 轻量提示（不会含响应正文）。 */
        val hint: String,
        /** 可直接复用的下一步工作流说明（固定字段，供 AI 按名取物）。 */
        val workflow: Map<String, String> = emptyMap(),
    )

    private val gate = Mutex()

    /** 入口先校验录制开关与目标，再串行发起，最后按 contextId 精确收日志。 */
    suspend fun run(params: Params): Result {
        // 录制关闭时直接拒绝：抓包的意义是留证据，关闭后请求无据可查；由用户手动开启，不代为开启。
        if (!logStore.recordingEnabled) {
            throw CaptureBlockedException("HTTP 事务记录已关闭，逐次抓包不会留下任何日志；请先到日志页开启记录后再抓包")
        }
        val url = params.url.trim()
        require(url.startsWith("http://") || url.startsWith("https://")) { "仅支持 HTTP/HTTPS" }
        // 测试可注入入口校验（仅字面量）；生产走 validateTarget 的 http(s)/凭据/私网三层。
        if (entryTargetValidator != null) entryTargetValidator.invoke(url) else validateTarget(url)
        val method = params.method.uppercase().also {
            require(it in setOf("GET", "POST", "HEAD")) { "method 必须是 GET/POST/HEAD" }
        }
        require(url.length <= 8192) { "url 过长" }
        require((params.body?.length ?: 0) <= 1_000_000) { "请求体过大" }
        params.headers.forEach { (k, v) ->
            require(k.isNotBlank() && k.length <= 128 && !k.contains(':')) { "请求头名非法" }
            require(v.length <= 4096) { "请求头值过长" }
            require(!k.contains('\n') && !k.contains('\r') && !v.contains('\r') && !v.contains('\n')) { "请求头含非法字符" }
        }

        val contextId = "cap:" + UUID.randomUUID().toString()
        // 书源初始域名的注册域；私网/解析失败不置锚点
        val anchor = runCatching {
            LogFilterUtils.extractPrimaryDomain(url)
        }.getOrNull()

        return gate.withLock {
            CaptureActivity.start(contextId)
            try {
                execute(url, method, params, contextId, anchor)
            } finally {
                // Polling limits/recording switches/cancellation do not imply the writer is drained.
                logStore.afterPendingWrites { CaptureActivity.finish(contextId) }
            }
        }
    }

    private suspend fun execute(
        url: String,
        method: String,
        params: Params,
        contextId: String,
        anchor: String?,
    ): Result {
        // WebView 路径不受 redirectGuard/DNS guard 约束（WebView 内部自行加载，无法逐跳校验），
        // 为避免发起不受控的内网请求，逐次抓包对 WebView 模式域直接拒绝；用户可改回 HTTP 模式或用 fetch_page。
        if (method == "GET" && requiresWebView(url)) {
            throw CaptureBlockedException(
                "该域当前为 WebView 模式：逐次抓包无法对 WebView 内部跳转做逐跳安全校验，已拒绝发起。" +
                    "请到验证/域名模式把该域切回 HTTP 模式，或改用 fetch_page 抓取"
            )
        }
        val viaWebView = false
        // 中间跳计数与逐跳落库共用同一个 Atomic：collectLogs 需要知道“应有几跳”来等齐异步写库。
        val hopsEmitted = java.util.concurrent.atomic.AtomicInteger(0)
        val origin = HttpOrigin(sourceAnchor = anchor, contextId = contextId, originKind = ORIGIN_FINAL)
        val request = HttpFetcher.FetchRequest(
            url = url, method = method, headers = params.headers, body = params.body,
            charset = params.charset, timeoutSec = params.timeoutSec, maxBodyBytes = 4_000_000,
            origin = origin,
        )
        val fetcher = fetcherFactory(
            guardOverrides?.redirect ?: ::redirectGuard,
            guardOverrides?.dns ?: ::dnsGuard,
        ) { hop ->
            // 中间跳证据在守卫判定之前产生：被 SSRF 拦截的 3xx 也会先落库（保留状态码+Location），
            // 但 guard 拦截会中断该跳自身的 follow，中间跳 URL 不再进入后续请求。
            hopsEmitted.incrementAndGet()
            logStore.recordHop(hop, anchor, contextId, ORIGIN_HOP)
        }
        val result = try {
            withContext(Dispatchers.IO) { fetcher.fetch(request) }
        } catch (blocked: CaptureBlockedException) {
            throw blocked
        } catch (error: Throwable) {
            // 联网失败也属一次完整抓包：错误已进日志（recorder 开着时），调用方仍需拿到 logIds
            val logs = collectLogs(contextId, expectedHops = hopsEmitted.get())
            val partial = Result(
                contextId = contextId, sourceAnchor = anchor, viaWebView = viaWebView,
                code = 0, finalUrl = url, elapsedMs = 0, redirectChain = emptyList(),
                bodyNote = "", logs = logs.entries, logIds = logs.entries.map { it.id },
                logCount = logs.totalCount,
                finalLogIds = logs.entries.filter { it.originKind != ORIGIN_HOP }.map { it.id },
                hopLogIds = logs.entries.filter { it.originKind == ORIGIN_HOP }.map { it.id },
                recordingDisabled = logs.recordingDisabled,
                hint = "请求未成功发出：${error.message?.take(200) ?: error.javaClass.simpleName}",
                workflow = workflowFor(failed = true, hasHops = logs.entries.any { it.originKind == ORIGIN_HOP }),
            )
            throw CaptureFailedException(error.message?.take(200) ?: error.javaClass.simpleName, partial)
        }

        val collected = collectLogs(contextId, expectedHops = hopsEmitted.get())
        val hopIds = collected.entries.filter { it.originKind == ORIGIN_HOP }.map { it.id }
        return Result(
            contextId = contextId, sourceAnchor = anchor, viaWebView = viaWebView,
            code = result.code, finalUrl = result.finalUrl, elapsedMs = result.elapsedMs,
            redirectChain = result.redirectChain, bodyNote = result.bodyNote,
            logs = collected.entries, logIds = collected.entries.map { it.id },
            logCount = collected.totalCount,
            finalLogIds = collected.entries.filter { it.originKind != ORIGIN_HOP }.map { it.id },
            hopLogIds = hopIds,
            recordingDisabled = collected.recordingDisabled,
            hint = "本次抓包共记录 ${collected.totalCount} 条事务" +
                (if (hopIds.isNotEmpty()) "（其中 ${hopIds.size} 条为重定向中间跳 capture_hop）" else "") +
                (if (collected.totalCount > collected.entries.size) "（超出上限，仅返回前 ${collected.entries.size} 条）" else ""),
            workflow = workflowFor(failed = false, hasHops = hopIds.isNotEmpty()),
        )
    }

    /** 生成固定结构的工作流提示：让 MCP 调用方不用猜下一步工具名与参数。 */
    private fun workflowFor(failed: Boolean, hasHops: Boolean): Map<String, String> {
        val next = buildString {
            if (hasHops) append("中间跳已逐条落库（originKind=capture_hop），可用 get_capture(contextId) 按跳回看，或 get_http_log(id) 取单条请求/响应头详情；")
            else append("本批日志可 get_capture(contextId) 回看，详情用 get_http_log(id)；")
            append("历史抓包用 list_captures(offset/limit) 按 cap: 前缀列出；")
            if (failed) append("本次请求未成功发出，确认日志后可用同参数重试")
            else append("看正文请对最终响应 URL 用 fetch_page；对不同入口（搜索/目录/正文）可再次 capture_once 逐一取证")
        }
        return mapOf(
            "workflow" to "capture_once 发起→logs/hopLogIds 区分中间跳与最终落点→get_http_log(id) 看头/正文→get_capture(contextId) 回看整链→list_captures 翻历史",
            "listCaptures" to "list_captures(offset=0,limit=20)：按 cap:contextId 分组的会话摘要（含 hopCount/totalCount/firstLogId）",
            "getCapture" to "get_capture(contextId, offset, limit)：同一 contextId 的逐跳轻量分页+total（不受最近500条窗口限制）",
            "getHttpLog" to "get_http_log(id)：单条完整详情（含请求/响应头与脱敏正文片段）",
            "getHttpLogs" to "get_http_logs(contextId=cap:...)：把全局事务列表限定到本次抓包",
            "next" to next,
        )
    }

    private class CollectResult(val entries: List<LogEntry>, val totalCount: Int, val recordingDisabled: Boolean)

    /**
     * 录制已开启时短轮询等异步落库后按 contextId 取回；入口已保证 recordingEnabled=true。
     * [expectedHops] 为本次 fetch 已确认产生的中间跳数：写库走异步队列，最终日志先可见时
     * 不能提前返回，要等中间跳也落库（或轮询耗尽）才能让 hopLogIds 不漏跳。
     */
    private suspend fun collectLogs(contextId: String, expectedHops: Int = 0): CollectResult {
        if (!logStore.recordingEnabled) return CollectResult(emptyList(), 0, recordingDisabled = true)
        var entries: List<LogEntry> = emptyList()
        var total = 0
        repeat(10) { attempt ->
            val all = logStore.logsByContextId(contextId)
            total = all.size
            // hop 计数用未截断的 all：超过 MAX_LOG_IDS 时 entries 截断会漏数尾部跳。
            val hops = all.count { it.originKind == ORIGIN_HOP }
            // 至少等到「有非中间跳行（最终落点/错误日志）」；有 hop 无 final 的极端场景不提前放行。
            val hasFinal = all.any { it.originKind != ORIGIN_HOP }
            entries = all.take(MAX_LOG_IDS).map { it.copy(error = it.error.take(MAX_ERROR_CHARS)) }
            // 中间跳比最终日志晚落库时继续等到 hops 凑齐（attempt 兜底防死等）。
            if (hasFinal && (expectedHops == 0 || hops >= expectedHops || attempt >= 8)) {
                return CollectResult(entries, total, recordingDisabled = false)
            }
            delay(120)
        }
        return CollectResult(entries, total, recordingDisabled = false)
    }

    class CaptureFailedException(message: String, val partial: Result) : Exception(message)

    /** 初始目标校验：仅 http(s)，禁止私网/回环，禁止内嵌凭据与不可信 scheme 跳转。 */
    private fun validateTarget(url: String) {
        require(url.startsWith("http://") || url.startsWith("https://")) { "仅支持 HTTP/HTTPS" }
        val parsed = url.toHttpUrlOrNull() ?: throw CaptureBlockedException("URL 无法解析")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw CaptureBlockedException("URL 不允许内嵌用户名/密码")
        }
        if (LogFilterUtils.isLoopbackOrPrivate(url)) {
            throw CaptureBlockedException("目标为回环/私网/保留地址，逐次抓包仅允许公网 HTTP/HTTPS")
        }
    }

    /**
     * 重定向逐跳校验：收到的是 HttpFetcher 已 resolve 的绝对目标 URL（含 //host、相对路径的展开），
     * http(s) 之外的 scheme、内嵌凭据、私网/回环一律拒绝。
     */
    private fun redirectGuard(absoluteTarget: String): String? {
        if (absoluteTarget.isBlank()) return null
        val parsed = absoluteTarget.toHttpUrlOrNull() ?: return "重定向目标不是有效的 HTTP(S) 地址"
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return "重定向目标内嵌凭据"
        if (LogFilterUtils.isLoopbackOrPrivate(absoluteTarget)) return "重定向目标为回环/私网/保留地址"
        return null
    }

    /** DNS 解析后逐地址校验：公网域名若落到回环/私网/保留段（重绑定/内网劫持）即拒绝建连。 */
    private fun dnsGuard(address: java.net.InetAddress): String? {
        return if (LogFilterUtils.isLoopbackOrPrivate(address.hostAddress.orEmpty())) {
            "解析到私网/回环地址 ${address.hostAddress}"
        } else null
    }

    private companion object {
        const val MAX_LOG_IDS = 200
        const val MAX_ERROR_CHARS = 300
        /** 最终落点事务的 originKind（与既有日志一致）。 */
        const val ORIGIN_FINAL = "capture_once"
        /** 重定向中间跳的 originKind：与最终落点同一 contextId/anchor，仅按 originKind 区分行角色。 */
        const val ORIGIN_HOP = "capture_hop"
    }
}
