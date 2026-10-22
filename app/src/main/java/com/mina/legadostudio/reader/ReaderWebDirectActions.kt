package com.mina.legadostudio.reader

/**
 * 固定一次调用的配置快照；调试预检与 WS 不随用户中途改配置而改目标。
 * 每次真正跑到阅读端的 debug / search 都写进 [ReaderExecutionRegistry]，供交付门槛核对。
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
    }

    suspend fun debug(bookSourceUrl: String, keyword: String, timeoutSec: Int): Map<String, Any?> {
        val trimmedKey = keyword.trim()
        val defaulted = trimmedKey.isEmpty()
        val effectiveKey = if (defaulted) DEFAULT_DEBUG_KEYWORD else trimmedKey
        val keywordInfo = mapOf("keywordDefaulted" to defaulted)
        val pinned = client.pinConfig()
        val config = pinned.configSnapshot() ?: return debugError("NOT_CONFIGURED", "尚未配置阅读 Web 服务地址", false)
        val url = bookSourceUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://") || url.length > 4096 || url.any { Character.isISOControl(it) }) {
            return debugError("INVALID_ARGUMENT", "bookSourceUrl 必须是有效的 http/https 书源地址", false)
        }
        val lookup = try { pinned.fetchSource(url) } catch (error: ReaderWebClient.ReaderWebException) {
            return debugError(error.code, error.message.orEmpty(), false) +
                mapOf("diagnostic" to error.diagnostic?.toPayload())
        }
        if (!lookup.present) {
            return debugError("SOURCE_NOT_IN_READER", "阅读中没有精确匹配的已保存书源；未运行调试", false) + keywordInfo
        }
        return try {
            val outcome = debug.debugAt(config, url, effectiveKey, timeoutSec.coerceIn(5, 120).toLong() * 1_000)
            val signals = ReaderDebugLogSignals.parse(outcome.debugLogs)
            ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record(
                kind = "debug", at = clock(), executed = true,
                status = if (outcome.truncated || outcome.closeCode == null && !outcome.completionSignal) "truncated" else ReaderWebLegacyDebugClient.STATUS,
                closeCode = outcome.closeCode, frameCount = outcome.frames,
                stagesReached = signals.stagesReached, failureSignals = signals.failureSignals,
            ), clock())
            outcome.toPayload() + mapOf("executed" to true, "lookupConfirmed" to true) + keywordInfo +
                ReaderDebugLogSignals.annotate(outcome.debugLogs)
        } catch (error: ReaderWebLegacyDebugClient.LegacyDebugException) {
            // 旧客户端的异常不携带“首帧是否已送达”，不能把失败伪报为已执行或未执行。
            val signals = ReaderDebugLogSignals.parse(error.debugLogs)
            if (error.debugLogs.isNotEmpty()) {
                ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record(
                    kind = "debug", at = clock(), executed = true, status = error.code,
                    stagesReached = signals.stagesReached,
                    failureSignals = signals.failureSignals,
                ), clock())
            }
            ReaderWebLegacyDebugClient.Outcome.errorPayload(error.code, error.message.orEmpty(), error.debugLogs) +
                mapOf("executed" to "unknown", "lookupConfirmed" to true) + keywordInfo +
                ReaderDebugLogSignals.annotate(error.debugLogs)
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
                outcome.results.groupBy { it["origin"].orEmpty().trim() }
                    .filterKeys { it.isNotEmpty() }
                    .forEach { (origin, hits) ->
                        ReaderExecutionRegistry.record(origin, ReaderExecutionRegistry.Record(
                            kind = "search", at = now, executed = true, status = outcome.status,
                            closeCode = outcome.closeCode, frameCount = outcome.frameCount, searchHits = hits.size,
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
}
