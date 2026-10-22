package com.mina.legadostudio.reader

/**
 * 进程内记录“阅读端是否真的跑过这个书源”。与沙箱的 CheckEvidenceRegistry 完全分开：
 * 沙箱结果永远不会写进这里，只有阅读 Web 的 debug / search / verify 才会。
 */
object ReaderExecutionRegistry {
    data class Record(
        val kind: String,
        val at: Long,
        val executed: Boolean,
        val status: String? = null,
        val closeCode: Int? = null,
        val frameCount: Int? = null,
        val stagesReached: List<String> = emptyList(),
        val failureSignals: List<String> = emptyList(),
        val searchHits: Int? = null,
        val verifyStatus: String? = null,
    )

    const val TTL_MS = 2 * 60 * 60 * 1000L
    const val CAPACITY = 200

    private val records = LinkedHashMap<String, MutableList<Record>>()

    private fun key(url: String) = url.trim()

    fun record(url: String, rec: Record, now: Long = System.currentTimeMillis()) {
        val k = key(url)
        if (k.isEmpty()) return
        synchronized(records) {
            prune(now)
            val list = records.remove(k) ?: mutableListOf()
            list += rec
            while (list.size > 10) list.removeAt(0)
            records[k] = list
            while (records.size > CAPACITY) records.remove(records.keys.first())
        }
    }

    fun all(url: String, now: Long = System.currentTimeMillis()): List<Record> = synchronized(records) {
        prune(now)
        records[key(url)]?.toList().orEmpty()
    }

    fun latest(url: String, now: Long = System.currentTimeMillis()): Record? = all(url, now).lastOrNull()

    fun hasExecuted(url: String, now: Long = System.currentTimeMillis()): Boolean = all(url, now).any { it.executed }

    fun hasFailure(url: String, now: Long = System.currentTimeMillis()): Boolean =
        summary(url, now)?.get("failureSignals")?.let { (it as List<*>).isNotEmpty() } == true

    fun summary(url: String, now: Long = System.currentTimeMillis()): Map<String, Any?>? {
        val list = all(url, now)
        if (list.isEmpty()) return null
        val latestDebug = list.lastOrNull { it.kind == "debug" && it.executed }
        val latestVerify = list.lastOrNull { it.kind == "verify" && it.executed }
        val current = latestDebug ?: latestVerify ?: list.lastOrNull { it.executed }
        val historicalFailures = list.flatMap { it.failureSignals }.distinct().take(6)
        val currentFailures = current?.failureSignals.orEmpty() +
            if (current?.kind == "search" && current.searchHits == 0) listOf("搜索无命中")
            else if (current?.kind == "verify" && current.verifyStatus != null &&
                current.verifyStatus !in setOf("passed", "legacy_debug_only")) listOf(current.verifyStatus)
            else emptyList()
        return mapOf(
            "executed" to list.any { it.executed },
            "kinds" to list.filter { it.executed }.map { it.kind }.distinct(),
            "failureSignals" to currentFailures.distinct().take(6),
            "historicalFailureSignals" to historicalFailures,
            "latestDebugStatus" to latestDebug?.status,
            "latestDebugAtEpochMs" to latestDebug?.at,
            "searchHits" to list.lastOrNull { it.kind == "search" }?.searchHits,
            "verifyStatus" to latestVerify?.verifyStatus,
            "stagesReached" to current?.stagesReached.orEmpty(),
            "lastAtEpochMs" to list.last().at,
        ).filterValues { it != null }
    }

    /**
     * 登记一次 reader_web_verify 的回包。结构化检验看 status；旧版降级看 debugLogs。
     * 未真正连到阅读（NOT_CONFIGURED / 不可达等、且无日志）的不登记为已执行。
     */
    fun recordVerifyPayload(url: String, payload: Map<String, Any?>, now: Long = System.currentTimeMillis()) {
        val status = payload["status"]?.toString()
        val logs = (payload["debugLogs"] as? List<*>)?.map { it.toString() }.orEmpty()
        val hasError = payload["errorCode"] != null
        val executed = logs.isNotEmpty() || (!hasError && status != null)
        if (!executed) return
        val signals = ReaderDebugLogSignals.parse(logs)
        record(url, Record(
            kind = "verify", at = now, executed = true, status = status,
            stagesReached = signals.stagesReached,
            failureSignals = signals.failureSignals,
            verifyStatus = status,
        ), now)
    }

    fun clear() = synchronized(records) { records.clear() }

    private fun prune(now: Long) {
        val iter = records.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            e.value.removeAll { r -> now - r.at > TTL_MS }
            if (e.value.isEmpty()) iter.remove()
        }
    }
}
