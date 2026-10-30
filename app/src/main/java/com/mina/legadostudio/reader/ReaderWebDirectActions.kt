package com.mina.legadostudio.reader

import com.mina.legadostudio.domain.CheckSourceRunner

/**
 * 固定一次调用的配置快照；调试预检与 WS 不随用户中途改配置而改目标。
 * 调试尝试与实际返回的搜索结果写进 [ReaderExecutionRegistry]，供交付门槛核对。
 */
class ReaderWebDirectActions(
    private val client: ReaderWebClient,
    private val debug: ReaderWebLegacyDebugClient,
    private val search: ReaderWebSearchClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** 官方阅读的调试通道在 key 为空时只回一句 cannot_empty 就关闭，所以缺省必须给关键词。 */
        const val DEFAULT_DEBUG_KEYWORD = "我的"
        const val DEBUG_TIMEOUT_MIN_SEC = 5
        const val DEBUG_TIMEOUT_MAX_SEC = 180

        fun clampDebugTimeoutSec(timeoutSec: Int): Int =
            timeoutSec.coerceIn(DEBUG_TIMEOUT_MIN_SEC, DEBUG_TIMEOUT_MAX_SEC)
    }

    suspend fun debug(
        bookSourceUrl: String, keyword: String, timeoutSec: Int,
        expectedConfigFingerprint: String? = null, expectedRawSourceHash: String? = null,
    ): Map<String, Any?> {
        val started = clock()
        val pinned = client.pinConfig()
        val config = pinned.configSnapshot() ?: return debugError("NOT_CONFIGURED", "尚未配置阅读 Web 服务地址", false)
        val configFp = ReaderExecutionRegistry.configFingerprintOf(config)
        if (expectedConfigFingerprint != null && configFp != expectedConfigFingerprint) {
            return debugError("CONFIG_CHANGED", "同步后阅读配置已变更，请重新确认当前地址；未执行调试", false)
        }
        val url = bookSourceUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://") || url.length > 4096 || url.any { Character.isISOControl(it) }) {
            return debugError("INVALID_ARGUMENT", "bookSourceUrl 必须是有效的 http/https 书源地址", false)
        }
        val lookup = try { pinned.fetchSource(url) } catch (error: ReaderWebClient.ReaderWebException) {
            ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("debug", started, false,
                status = error.code, configFingerprint = configFp), clock())
            return debugError(error.code, error.message.orEmpty(), false) + mapOf("diagnostic" to error.diagnostic?.toPayload())
        }
        if (!lookup.present) {
            ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("debug", started, false,
                status = "SOURCE_NOT_IN_READER", configFingerprint = configFp), clock())
            return debugError("SOURCE_NOT_IN_READER", "阅读中没有精确匹配的已保存书源；未运行调试", false)
        }
        if (expectedRawSourceHash != null && lookup.source?.let(ReaderWebSourceSync::hashOf) != expectedRawSourceHash) {
            ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("debug", started, false,
                status = "SOURCE_CHANGED", sourceHash = lookup.source?.let(ReaderExecutionRegistry::sourceHashOf),
                configFingerprint = configFp), clock())
            return debugError("SOURCE_CHANGED", "同步后阅读书源内容已变化，请重新核对版本；未执行调试", false)
        }
        val defaulted = keyword.isBlank()
        val effectiveKey = if (defaulted) lookup.source?.let(CheckSourceRunner::probeKeyword) ?: DEFAULT_DEBUG_KEYWORD else keyword.trim()
        val keywordInfo = mapOf("keywordDefaulted" to defaulted, "keyword" to effectiveKey, "executionStartedAtEpochMs" to started)
        val sourceHash = lookup.source?.let(ReaderExecutionRegistry::sourceHashOf)
        return try {
            val outcome = debug.debugAt(config, url, effectiveKey, clampDebugTimeoutSec(timeoutSec).toLong() * 1_000)
            val signals = ReaderDebugLogSignals.parse(outcome.debugLogs)
            val complete = ReaderExecutionRegistry.debugComplete(outcome.toPayload())
            ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record(
                kind = "debug", at = started, executed = true,
                status = if (complete) ReaderWebLegacyDebugClient.STATUS else "truncated",
                closeCode = outcome.closeCode, frameCount = outcome.frames,
                stagesReached = signals.stagesReached, failureSignals = signals.failureSignals,
                sourceHash = sourceHash, configFingerprint = configFp, complete = complete, keyword = effectiveKey,
            ), clock())
            outcome.toPayload() + mapOf("executed" to true, "lookupConfirmed" to true) + keywordInfo +
                ReaderDebugLogSignals.annotate(outcome.debugLogs) + evidenceFields(sourceHash, configFp, complete)
        } catch (error: ReaderWebLegacyDebugClient.LegacyDebugException) {
            val signals = ReaderDebugLogSignals.parse(error.debugLogs)
            ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record(
                kind = "debug", at = started, executed = error.debugLogs.isNotEmpty(), status = error.code,
                stagesReached = signals.stagesReached, failureSignals = signals.failureSignals,
                sourceHash = sourceHash, configFingerprint = configFp, complete = false, keyword = effectiveKey,
            ), clock())
            error.toErrorPayload() +
                mapOf("executed" to "unknown", "lookupConfirmed" to true) + keywordInfo +
                ReaderDebugLogSignals.annotate(error.debugLogs) + evidenceFields(sourceHash, configFp, false)
        }
    }

    suspend fun search(keyword: String, timeoutSec: Int, maxResults: Int): Map<String, Any?> {
        val config = client.pinConfig().configSnapshot()
            ?: return ReaderWebSearchClient.SearchException("NOT_CONFIGURED", false, "尚未配置阅读 Web 服务地址").toPayload()
        return try {
            val outcome = search.searchAt(config, keyword, timeoutSec.coerceIn(5, 300).toLong() * 1_000,
                maxResults.coerceIn(1, 100))
            if (outcome.executed) {
                val now = clock()
                val configFp = ReaderExecutionRegistry.configFingerprintOf(config)
                // 全局搜索按 origin 精确归属；拿不到书源内容 → sourceHash=null（只能观测），
                // 搜索未命中目标源不登记任何记录，自然不构成该源的规则失败
                val complete = outcome.status == "completed" && !outcome.truncated && outcome.closeCode != null
                outcome.results.groupBy { it["origin"].orEmpty().trim() }
                    .filterKeys { it.isNotEmpty() }
                    .forEach { (origin, hits) ->
                        ReaderExecutionRegistry.record(origin, ReaderExecutionRegistry.Record(
                            kind = "search", at = now, executed = true, status = outcome.status,
                            closeCode = outcome.closeCode, frameCount = outcome.frameCount, searchHits = hits.size,
                            configFingerprint = configFp, complete = complete,
                        ), now)
                    }
            }
            outcome.toPayload()
        } catch (error: ReaderWebSearchClient.SearchException) {
            error.toPayload()
        }
    }

    private fun debugError(code: String, message: String, executed: Boolean): Map<String, Any?> =
        ReaderWebLegacyDebugClient.Outcome.errorPayload(code, message) + mapOf("executed" to executed)

    /** 回包里透出的证据身份/完整性字段（供 [ReaderExecutionRegistry.recordVerifyPayload] 与交付判定消费）。 */
    private fun evidenceFields(sourceHash: String?, configFp: String?, complete: Boolean): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "evidenceSourceHash" to sourceHash,
            "evidenceConfigFingerprint" to configFp,
            "executionComplete" to complete,
        ).filterValues { it != null }
}
