package com.mina.legadostudio.reader

import com.google.gson.JsonObject
import kotlinx.coroutines.delay

/**
 * 在阅读 App 侧执行一次书源检验并轮询结果。
 *
 * 关键语义：
 * - 只检验「阅读里已经保存」的书源：先按 bookSourceUrl 取阅读中的完整 BookSource，再原样提交检验，
 *   不覆盖或导入书源内容；阅读仍会更新检验状态与相关元数据。提交版本不一致时由阅读端拒绝。
 * - 检验结果**只属于阅读端**：这里的「通过」不能当作本 App `check_source` 的四阶段证据，
 *   也不登记进 CheckEvidenceRegistry。
 * - **只认本次检验写入的新状态**：阅读里可能残留上一次检验的 PASSED。判定依据是
 *   `revision`（每次 beginCheck 新建）、`checkedAt`（本次开始前为 0）、`sourceRevision`（= 本次提交版本）
 *   三者同时成立；读到旧状态或异版本状态时绝不判通过，最多如实报 `stale_state`。
 * - **状态必须归属本次会话**：快照里的 `sessionToken` 非空时必须等于本次 `startBookSourceCheck` 返回的令牌；
 *   为 null（本次检验刚结束）时，必须是先前在本会话下观察到 `NEEDS_CHECK` 的那个 `revision`。
 * - 被别的客户端的新检验取代（令牌不同）或无法归属时一律返回未完成，不判通过。
 * - **配置在调用开始时固定**：整次检验用同一份地址+令牌快照（见 `ReaderWebClient.pinConfig`），
 *   中途在界面上改地址不会把同一检验会话带到另一个主机，也不会把旧令牌发往新地址；
 *   改动只在下一次调用生效。
 * - 服务不可达、会话被替换、超时都会如实返回未完成，并把已拿到的状态快照带回去。
 */
class ReaderWebVerifier(
    private val client: ReaderWebClient,
    private val pollIntervalMs: Long = 1_000L,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * 旧版/叉版阅读的 WebSocket 调试通道。结构化接口缺失，或官方接口画像不匹配
     * （`NOT_READER`）时，只有 `getBookSource` 精确预检确认目标书源已保存才会运行；
     * 结果恒为 `legacy_debug_only`/`unverifiable`，不会产出 PASSED。
     */
    private val legacyDebug: LegacyDebugRunner? = null,
) {

    /**
     * 跑一次旧版调试通道。配置由调用方按**本次调用固定的快照**传入，
     * 避免 UI 中途改地址后把调试请求带到另一台主机。
     */
    fun interface LegacyDebugRunner {
        suspend fun debug(
            config: ReaderWebConfigStore.Config?,
            sourceUrl: String,
            keyword: String,
            totalTimeoutMs: Long,
        ): ReaderWebLegacyDebugClient.Outcome
    }

    data class Outcome(
        val status: String,
        val sourceUrl: String,
        val keyword: String?,
        val tokenRequired: Boolean,
        val sessionToken: String?,
        /** 只放**本次检验**写入的新状态；历史/无法归属的状态为 null。 */
        val state: JsonObject?,
        val states: List<JsonObject>,
        val polls: Int,
        val elapsedMs: Long,
        val message: String,
        val progress: Map<String, Any?>,
        /** 本次未拿到新状态时最后观察到的状态（仅作诊断，`belongsToThisRun=false`）。 */
        val lastObservedState: JsonObject? = null,
        /** 旧版 WS 调试回包（`legacy_debug_only` / `unverifiable`）；现代结构化检验时为 null。 */
        val legacyDebugPayload: Map<String, Any?>? = null,
        /** 失败那次请求的脱敏诊断（如轮询中断）：成功时通常为最后一次状态读取，null = 未拿到。 */
        val diagnostic: ReaderWebRequestDiagnostic? = null,
    ) {
        /** MCP 工具回包形状（纯数据，JVM 可测）：如实标注证据来源与证据不跨实例。 */
        fun toPayload(): Map<String, Any?> {
            // 旧版通道：原样使用其回包，绝不改写 status / machineVerdict
            legacyDebugPayload?.let { legacy ->
                return linkedMapOf<String, Any?>().apply {
                    putAll(legacy)
                    put("evidenceScope", "reader_web_legacy_debug")
                    put(
                        "evidenceNote",
                        "日志来自阅读 App 的旧版 WebSocket 调试通道，仅代表阅读端调试输出；该通道没有结构化结论，" +
                            "不登记为本实例 check_source 的证据，也不代表书源检验通过",
                    )
                    val logs = (legacy["debugLogs"] as? List<*>)?.map { it.toString() }.orEmpty()
                    putAll(ReaderDebugLogSignals.annotate(logs))
                }
            }
            return linkedMapOf<String, Any?>(
                "status" to status,
                "sourceUrl" to sourceUrl,
                "keyword" to keyword,
                "tokenRequired" to tokenRequired,
                "sessionToken" to sessionToken,
                // 只有本次检验写入的新状态才能占用主 state；历史状态改名并显式标注不归属本次
                "state" to state,
                "belongsToThisRun" to (state != null),
                "lastObservedState" to if (state == null) lastObservedState else null,
                "elapsedMs" to elapsedMs,
                "polls" to polls,
                "message" to message,
                "progress" to progress,
                // 失败阶段（预检/列表/启动/轮询）随回包透出：模型不必再从文案猜卡在哪；
                // 成功互不产生诊断时不塞空键，保持既有回包形状
                "evidenceScope" to "reader_web",
                "evidenceNote" to
                    "结果由阅读 App 检验产生，仅代表阅读端状态；不登记为本实例 check_source 的证据，不代表四阶段样本检查通过",
            ).apply { diagnostic?.let { put("diagnostic", it.toPayload()) } }
        }

        companion object {
            /**
             * 未开始/失败时的统一回包形状（status 用小写错误码，errorCode 保留原值）。
             * @param diagnostic 失败请求的脱敏诊断（方法/固定路径/阶段/相位/状态/异常类别/尝试次数）；
             *   绝不含 host、端口、查询串、令牌与书源正文。
             */
            fun errorPayload(
                code: String,
                message: String,
                diagnostic: ReaderWebRequestDiagnostic? = null,
            ): Map<String, Any?> = linkedMapOf<String, Any?>(
                "status" to code.lowercase(),
                "errorCode" to code,
                "message" to message,
            ).apply { diagnostic?.let { put("diagnostic", it.toPayload()) } }
        }
    }

    /**
     * @property payload 旧版通道等已构造好的回包（工具直接输出，不再套错误模板）。
     * @property diagnostic 失败请求的脱敏诊断：保留根请求阶段（lookup/sources/start/states），
     *   让 AI 能区分「预检失败」与「检验启动失败」，而不是只看到一句文案。
     */
    class VerifyException(
        val code: String,
        message: String,
        val payload: Map<String, Any?>? = null,
        val diagnostic: ReaderWebRequestDiagnostic? = null,
    ) : Exception(message)

    /**
     * @param sourceUrl 阅读书源 URL（bookSourceUrl 全等匹配）
     * @param keyword 阅读端检验关键词；null 表示沿用阅读上次的关键词
     * @param timeoutSec 总等待预算，5..300 秒
     */
    suspend fun verify(sourceUrl: String, keyword: String?, timeoutSec: Int): Outcome {
        val url = sourceUrl.trim()
        require(url.isNotEmpty()) { "bookSourceUrl 不能为空" }
        require(url.length <= 4096) { "bookSourceUrl 过长" }
        val started = now()
        val budgetMs = timeoutSec.coerceIn(5, 300) * 1_000L
        // 整次检验固定一份地址+令牌快照：中途改配置只影响下一次调用，不会把同一会话带到别的主机
        val client = this.client.pinConfig()

        val probe = try {
            client.probe(url)
        } catch (error: ReaderWebClient.ReaderWebException) {
            // 只有「确实是阅读、只是没有新版检验接口」才允许降级到旧版调试通道；
            // 未配置/不可达/超时/令牌/不是阅读 一律如实失败，不误降级。
            if (isLegacyCapabilityGap(error)) {
                return legacyDebugFallback(client, url, keyword, budgetMs, started, error)
            }
            throw VerifyException(error.code, verifierMessage(error), diagnostic = error.diagnostic)
        }
        if (probe.tokenRequired && !client.hasStoredToken()) {
            throw VerifyException(
                "TOKEN_REQUIRED",
                "阅读已开启「访问令牌保护」，但本 App 未保存令牌：请在 MCP 页「设置」→「阅读 Web 服务」填入与阅读设置完全相同的令牌后重试",
            )
        }

        val entries = try {
            client.fetchSources(listOf(url))
        } catch (error: ReaderWebClient.ReaderWebException) {
            // 新版探测已证明是阅读，只是这条路由不存在：同样属于「版本不支持检验」
            if (error.routeMissing) {
                return legacyDebugFallback(client, url, keyword, budgetMs, started, error)
            }
            throw VerifyException(error.code, verifierMessage(error), diagnostic = error.diagnostic)
        }
        val entry = entries.firstOrNull { it.source.stringOrNull("bookSourceUrl") == url }
            ?: throw VerifyException(
                "SOURCE_NOT_IN_READER",
                "阅读中没有该地址的书源：请先在阅读的 Web 书源管理页导入/保存该书源；本工具不会自动把本地书源上传到阅读",
            )
        // 检验开始前的旧状态：用于把「本次写入的新状态」和上一次检验遗留区分开
        val oldState = entry.state
        val oldRevision = oldState?.stringOrNull("revision")?.takeIf { it.isNotBlank() }
        val oldCheckedAt = oldState?.longOrNull("checkedAt") ?: 0L

        val start = try {
            client.startCheck(listOf(entry.source), keyword)
        } catch (error: ReaderWebClient.ReaderWebException) {
            if (error.routeMissing) {
                return legacyDebugFallback(client, url, keyword, budgetMs, started, error)
            }
            throw VerifyException(error.code, verifierMessage(error), diagnostic = error.diagnostic)
        }
        // 没有本次提交的版本号就无法确认状态归属：宁可报错，也不把旧状态当成本次结果
        val expectedRevision = start.sourceRevisions[url]?.takeIf { it.isNotBlank() }
            ?: throw VerifyException(
                "SOURCE_REVISION_UNKNOWN",
                "阅读端未返回该书源的版本号，无法确认检验结果属于本次提交的书源；请升级阅读到支持该接口的版本后重试",
            )

        var polls = 0
        var lastStates: List<JsonObject> = emptyList()
        var lastState: JsonObject? = null
        var freshState: JsonObject? = null
        var lastError: ReaderWebClient.ReaderWebException? = null
        var staleStateIgnored = false
        var stateRevisionChanged = false
        var sessionReplaced = false
        var unattributedStateSeen = false
        // 本次会话里观察到 NEEDS_CHECK 的 revision：会话结束后（sessionToken 变 null）用它证明结果归属
        val needCheckRevisions = mutableSetOf<String>()

        while (true) {
            if (now() - started >= budgetMs) break
            if (polls > 0) delay(pollIntervalMs)
            polls++
            val snapshot = try {
                client.checkStates()
            } catch (error: ReaderWebClient.ReaderWebException) {
                lastError = error
                break
            }
            lastStates = snapshot.states
            val stateOfSource = snapshot.states.firstOrNull { it.stringOrNull("bookSourceUrl") == url }
            lastState = stateOfSource
            if (stateOfSource == null) {
                // 状态行还没落库：继续等，除非阅读已经把会话结束
                if (snapshot.sessionToken == null) break
                continue
            }
            val revision = stateOfSource.stringOrNull("revision")
            val status = stateOfSource.stringOrNull("status")
            val checkedAt = stateOfSource.longOrNull("checkedAt") ?: 0L
            if (oldRevision != null && revision != null && revision != oldRevision) {
                stateRevisionChanged = true
            }
            val session = snapshot.sessionToken
            // 会话归属：非空时必须就是本次 startBookSourceCheck 返回的令牌
            if (session != null && session != start.sessionToken) {
                sessionReplaced = true
                break
            }
            if (status == "NEEDS_CHECK" && revision != null && session == start.sessionToken) {
                needCheckRevisions += revision
            }
            val isNewState = revision != null &&
                revision != oldRevision &&
                stateOfSource.stringOrNull("sourceRevision") == expectedRevision &&
                status != null && status != "NEEDS_CHECK" &&
                checkedAt > 0L && checkedAt > oldCheckedAt
            // sessionToken 为 null 表示本次检验刚结束：只有先前在本会话下见过该 revision 才能认领
            val attributed = if (session != null) true else revision in needCheckRevisions
            if (isNewState && attributed) {
                freshState = stateOfSource
                break
            }
            if (isNewState) {
                unattributedStateSeen = true
            } else if (status != null && status != "NEEDS_CHECK") {
                // 已结束但不是本次写入的状态：可能是上一次检验遗留或异版本结果，继续等本次结果
                staleStateIgnored = true
            }
            if (session == null) break
        }

        val elapsed = now() - started
        val stateOfRun = freshState
        val summary = when {
            stateOfRun != null -> when (stateOfRun.stringOrNull("status")) {
                "PASSED" -> "passed"
                "FAILED" -> "failed"
                "UNCHECKABLE" -> "uncheckable"
                else -> "finished"
            }
            sessionReplaced -> "session_replaced"
            lastError != null -> "reader_unavailable"
            unattributedStateSeen -> "session_unconfirmed"
            staleStateIgnored -> "stale_state"
            elapsed >= budgetMs -> "timeout"
            else -> "session_ended_without_result"
        }
        val message = when (summary) {
            "passed" -> "阅读端检验通过（仅代表阅读端本次检验结果，不代表本 App check_source 的四阶段证据）"
            "failed" -> "阅读端检验未通过，详见 state.detail"
            "uncheckable" -> "阅读端判定该书源无法检验，详见 state.detail"
            "session_replaced" ->
                "阅读端检验已被另一个检验会话取代，本次没有得到自己的结果；请稍后重新运行本工具，避免同时用其它客户端检验同一书源"
            "session_unconfirmed" ->
                "拿到了新的 PASSED/FAILED 状态，但无法确认它来自本次检验（会话已结束且未观察到本会话的 NEEDS_CHECK 状态），不能据此判定通过；请稍后重新运行本工具"
            "stale_state" ->
                "没有拿到本次检验的新状态：轮询读到的是检验开始前的旧状态或其它版本的状态，不能据此判定通过；" +
                    "阅读可能仍在检验，请稍后重新运行本工具查看"
            "timeout" -> "等待阅读端检验超时（${elapsed / 1000}s）：阅读可能仍在检验，可稍后重新运行本工具查看状态"
            "reader_unavailable" -> "轮询期间连接阅读失败：${lastError?.message.orEmpty()}"
            "session_ended_without_result" -> "阅读端检验会话已结束，但没有拿到该书源的最终状态；可能被新的检验会话替换或书源已变更"
            else -> "阅读端已返回该状态：${stateOfRun?.stringOrNull("status").orEmpty().ifBlank { "未知" }}"
        }

        return Outcome(
            status = summary,
            sourceUrl = url,
            keyword = keyword?.trim()?.takeIf { it.isNotEmpty() },
            tokenRequired = probe.tokenRequired,
            sessionToken = start.sessionToken,
            // 只有本次检验写入的新状态才进主 state；历史/异会话状态只作为 lastObservedState 诊断
            state = stateOfRun,
            states = lastStates.filter { it.stringOrNull("bookSourceUrl") == url },
            polls = polls,
            elapsedMs = elapsed,
            message = message,
            progress = linkedMapOf(
                "readerStatesSample" to lastStates.size,
                "sessionTokenSeen" to (lastState != null),
                "sessionTokenMatched" to (stateOfRun != null && !sessionReplaced),
                "needCheckRevisionObserved" to needCheckRevisions.isNotEmpty(),
                "sessionReplaced" to sessionReplaced,
                "oldStateSeen" to (oldState != null),
                "stateRevisionChanged" to stateRevisionChanged,
                "staleStateIgnored" to staleStateIgnored,
                "confirmedNewState" to (stateOfRun != null),
                "sourceRevisionMatched" to (stateOfRun?.stringOrNull("sourceRevision") == expectedRevision),
            ),
            lastObservedState = stateOfRun ?: lastState,
            // 轮询失败时把「哪条请求失败、什么阶段」一起带回去，不只留一句文案
            diagnostic = lastError?.diagnostic,
        )
    }

    // ---- 旧版通道降级 ----

    /**
     * `LEGACY_READER`、`CHECK_UNSUPPORTED` 或 `NOT_READER`（官方接口画像不匹配）
     * 仅允许进入精确 `getBookSource` 只读预检；目标书源确已保存才尝试旧版 WS 调试。
     * 未配置、不可达、超时、令牌或重定向错误不进入降级流程。
     */
    private fun isLegacyCapabilityGap(error: ReaderWebClient.ReaderWebException): Boolean =
        error.code == "LEGACY_READER" || error.code == "CHECK_UNSUPPORTED" || error.code == "NOT_READER" ||
            (error.routeMissing && error.stage in setOf("sources", "start", "states"))

    /**
     * 先用固定公开读路由确认「该书源确在阅读」，再跑真实阅读 Web 调试通道。
     * 回包恒为 `legacy_debug_only` + `machineVerdict=unverifiable`，绝不产出 PASSED。
     */
    private suspend fun legacyDebugFallback(
        client: ReaderWebClient,
        url: String,
        keyword: String?,
        budgetMs: Long,
        started: Long,
        cause: ReaderWebClient.ReaderWebException,
    ): Outcome {
        val runner = legacyDebug ?: throw VerifyException(
            cause.code,
            "阅读 Web 已连通，但该版本没有提供书源检查接口；该部署未启用旧版调试通道，无法自动降级。" +
                "请升级阅读到支持书源检验接口的版本，或改在阅读的 Web 书源页面手动检验。本次调用不会产生任何阅读端检验结果。",
            // 根请求阶段（哪条路由不被支持）随异常透出，不被「无调试通道」这句文案吞掉
            diagnostic = cause.diagnostic,
        )
        val lookup = try {
            client.fetchSource(url, ReaderWebClient.Stage.LOOKUP)
        } catch (error: ReaderWebClient.ReaderWebException) {
            // 连「是否已保存在阅读」都确认不了：保留原诊断，不做降级
            throw VerifyException(error.code, verifierMessage(error), diagnostic = error.diagnostic)
        }
        if (!lookup.present) {
            throw VerifyException(
                "SOURCE_NOT_IN_READER",
                "阅读中没有该地址的书源：请先用 reader_web_sync_source 写入，或在阅读的 Web 书源管理页导入；" +
                    "旧版调试通道也要求书源已保存在阅读中",
            )
        }
        val key = keyword?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LEGACY_KEYWORD
        // 给旧版调试留出剩余预算（至少 10 秒，最多 120 秒），不把整次调用拖到预算外
        val remainingMs = (budgetMs - (now() - started)).coerceIn(10_000L, 120_000L)
        val debugOutcome = try {
            runner.debug(client.configSnapshot(), url, key, remainingMs)
        } catch (error: ReaderWebLegacyDebugClient.LegacyDebugException) {
            // 保留旧版通道自己的错误分类与诊断日志，但仍是 legacy_debug_only / unverifiable
            throw VerifyException(
                error.code,
                error.message.orEmpty(),
                ReaderWebLegacyDebugClient.Outcome.errorPayload(
                    error.code,
                    error.message.orEmpty(),
                    error.debugLogs,
                ) + mapOf(
                    "reason" to "structured_check_unavailable",
                    "structuredCheckCode" to cause.code,
                ),
            )
        }
        return Outcome(
            status = ReaderWebLegacyDebugClient.STATUS,
            sourceUrl = url,
            keyword = key,
            tokenRequired = false,
            sessionToken = null,
            state = null,
            states = emptyList(),
            polls = 0,
            elapsedMs = now() - started,
            message = "该阅读版本没有结构化检验接口，已运行旧版 WebSocket 调试：只有日志，没有通过/失败结论",
            progress = linkedMapOf("legacyDebug" to true, "structuredCheckCode" to cause.code),
            legacyDebugPayload = debugOutcome.toPayload() + mapOf(
                "reason" to "structured_check_unavailable",
                "structuredCheckCode" to cause.code,
                "nextAction" to "查看日志定位问题；该通道不产出 PASSED，不能作为检验通过依据",
            ),
        )
    }

    private fun verifierMessage(error: ReaderWebClient.ReaderWebException): String = when (error.code) {
        // 旧版/缺少接口：说清「确实连上了阅读，只是不能做结构化检验」，不要让调用方以为地址写错
        "LEGACY_READER", "CHECK_UNSUPPORTED" -> error.message.orEmpty() +
            "；reader_web_verify 只能走新版检验接口，请升级阅读，或改在阅读的 Web 书源页面手动检验。" +
            "本次调用不会产生任何阅读端检验结果。"
        else -> error.message.orEmpty()
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    private fun JsonObject.longOrNull(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

    private companion object {
        /** 用户没给关键词时阅读端调试的缺省关键词（与阅读 Web 调试页的默认一致）。 */
        const val DEFAULT_LEGACY_KEYWORD = "我的"
    }
}
