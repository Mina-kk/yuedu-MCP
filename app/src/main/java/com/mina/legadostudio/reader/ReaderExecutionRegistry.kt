package com.mina.legadostudio.reader

import com.google.gson.JsonObject
import java.security.MessageDigest

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
        val sourceHash: String? = null,
        val configFingerprint: String? = null,
        val complete: Boolean = false,
        val keyword: String? = null,
    )

    const val TTL_MS = 2 * 60 * 60 * 1000L
    const val CAPACITY = 200
    val REQUIRED_STAGES = listOf("search", "detail", "toc", "content")
    private val records = LinkedHashMap<String, MutableList<Record>>()

    fun sourceHashOf(source: JsonObject): String = ReaderWebSourceSync.hashOf(source.deepCopy().apply {
        if (!has("enabledCookieJar")) addProperty("enabledCookieJar", true)
    })

    fun configFingerprintOf(config: ReaderWebConfigStore.Config?): String? {
        config ?: return null
        val origin = ReaderWebOrigin.normalizeOrNull(config.baseUrl) ?: return null
        val token = ReaderWebUiPolicy.SessionIdentity.tokenFingerprint(config.token)
        return MessageDigest.getInstance("SHA-256")
            .digest("$origin|${config.revision}|$token".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun debugComplete(payload: Map<String, Any?>): Boolean {
        val logs = (payload["debugLogs"] as? List<*>).orEmpty().map { it.toString() }
        val terminal = payload["completionSignal"] == true || logs.any { line ->
            listOf("书籍总数:", "未获取到书籍", "没有正文章节", "调试完成", "调试结束").any(line::contains)
        }
        return payload["errorCode"] == null && payload["truncated"] != true &&
            payload["closeCode"] in setOf(1000, 1001) && terminal
    }

    fun record(url: String, rec: Record, now: Long = System.currentTimeMillis()) {
        val key = url.trim()
        if (key.isEmpty()) return
        synchronized(records) {
            prune(now)
            val list = records.remove(key) ?: mutableListOf()
            list += rec
            list.sortBy { it.at }
            while (list.size > 10) list.removeAt(0)
            records[key] = list
            while (records.size > CAPACITY) records.remove(records.keys.first())
        }
    }

    fun all(url: String, now: Long = System.currentTimeMillis()): List<Record> = synchronized(records) {
        prune(now)
        records[url.trim()]?.toList().orEmpty()
    }

    fun latest(url: String, now: Long = System.currentTimeMillis()): Record? = all(url, now).lastOrNull()

    fun hasExecuted(url: String, now: Long = System.currentTimeMillis()): Boolean = all(url, now).any { it.executed }

    fun hasFailure(url: String, now: Long = System.currentTimeMillis()): Boolean =
        (summary(url, now)?.get("failureSignals") as? List<*>)?.isNotEmpty() == true

    fun summary(
        url: String,
        now: Long = System.currentTimeMillis(),
        sourceHash: String? = null,
        configFingerprint: String? = null,
    ): Map<String, Any?>? {
        val list = all(url, now)
        if (list.isEmpty()) return null
        val current = list.last()
        val failures = current.failureSignals + if (current.kind == "verify" && current.verifyStatus == "failed") listOf("failed") else emptyList()
        val matches = sourceHash != null && configFingerprint != null &&
            current.sourceHash == sourceHash && current.configFingerprint == configFingerprint
        return linkedMapOf<String, Any?>(
            "executed" to current.executed,
            "kinds" to list.filter { it.executed }.map { it.kind }.distinct(),
            "currentKind" to current.kind,
            "currentStatus" to current.status,
            "failureSignals" to failures.distinct().take(6),
            "historicalFailureSignals" to list.flatMap { it.failureSignals }.distinct().take(6),
            "latestDebugStatus" to list.lastOrNull { it.kind == "debug" }?.status,
            "latestDebugAtEpochMs" to list.lastOrNull { it.kind == "debug" }?.at,
            "searchHits" to list.lastOrNull { it.kind == "search" }?.searchHits,
            "verifyStatus" to current.verifyStatus,
            "stagesReached" to current.stagesReached,
            "missingStages" to REQUIRED_STAGES.filter { it !in current.stagesReached },
            "complete" to current.complete,
            "sourceHash" to current.sourceHash,
            "identityMatches" to matches,
            "observedContent" to (current.sourceHash != null),
            "keyword" to current.keyword,
            "lastAtEpochMs" to current.at,
        ).filterValues { it != null }
    }

    fun passed(summary: Map<String, Any?>?): Boolean = summary != null &&
        summary["identityMatches"] == true && summary["executed"] == true && summary["complete"] == true &&
        (summary["failureSignals"] as? List<*>).orEmpty().isEmpty() &&
        summary["currentKind"] == "verify" && summary["verifyStatus"] == "passed"

    fun recordVerifyPayload(url: String, payload: Map<String, Any?>, now: Long = System.currentTimeMillis()) {
        val status = payload["status"]?.toString()
        val logs = (payload["debugLogs"] as? List<*>)?.map { it.toString() }.orEmpty()
        val executed = logs.isNotEmpty() || (payload["errorCode"] == null && status != null)
        if (!executed && payload["evidenceSourceHash"] == null) return
        val signals = ReaderDebugLogSignals.parse(logs)
        record(url, Record(
            kind = "verify", at = (payload["executionStartedAtEpochMs"] as? Number)?.toLong() ?: now,
            executed = executed, status = status, stagesReached = signals.stagesReached,
            failureSignals = signals.failureSignals, verifyStatus = status,
            sourceHash = payload["evidenceSourceHash"] as? String,
            configFingerprint = payload["evidenceConfigFingerprint"] as? String,
            complete = payload["executionComplete"] == true,
            keyword = payload["keyword"] as? String,
        ), now)
    }

    fun clear() = synchronized(records) { records.clear() }

    private fun prune(now: Long) {
        val iter = records.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            entry.value.removeAll { now - it.at > TTL_MS }
            if (entry.value.isEmpty()) iter.remove()
        }
    }
}
