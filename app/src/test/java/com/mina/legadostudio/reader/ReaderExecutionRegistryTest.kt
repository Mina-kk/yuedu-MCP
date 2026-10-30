package com.mina.legadostudio.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReaderExecutionRegistryTest {
    private val url = "https://books.example/source"

    @Before fun reset() = ReaderExecutionRegistry.clear()

    @Test fun recordAndSummaryUseTrimmedKey() {
        ReaderExecutionRegistry.record(" $url ", ReaderExecutionRegistry.Record("debug", 1_000, true, stagesReached = listOf("search")), 1_000)
        assertTrue(ReaderExecutionRegistry.hasExecuted(url, 2_000))
        val s = ReaderExecutionRegistry.summary(url, 2_000)!!
        assertEquals(true, s["executed"])
        assertEquals(listOf("debug"), s["kinds"])
        assertEquals(listOf("search"), s["stagesReached"])
        assertFalse(s.containsKey("searchHits"))
        assertFalse(ReaderExecutionRegistry.hasFailure(url, 2_000))
    }

    @Test fun blankUrlIsIgnored() {
        ReaderExecutionRegistry.record("  ", ReaderExecutionRegistry.Record("debug", 1, true), 1)
        assertNull(ReaderExecutionRegistry.summary("", 1))
    }

    @Test fun recordsExpireAfterTtl() {
        ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("debug", 0, true), 0)
        assertTrue(ReaderExecutionRegistry.hasExecuted(url, ReaderExecutionRegistry.TTL_MS))
        assertFalse(ReaderExecutionRegistry.hasExecuted(url, ReaderExecutionRegistry.TTL_MS + 1))
        assertNull(ReaderExecutionRegistry.summary(url, ReaderExecutionRegistry.TTL_MS + 1))
    }

    @Test fun perUrlAndTotalCapacityAreBounded() {
        repeat(15) { ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("debug", it.toLong(), true), it.toLong()) }
        assertEquals(10, ReaderExecutionRegistry.all(url, 20).size)
        assertEquals(5L, ReaderExecutionRegistry.all(url, 20).first().at)
        repeat(ReaderExecutionRegistry.CAPACITY + 5) {
            ReaderExecutionRegistry.record("https://s$it.example", ReaderExecutionRegistry.Record("debug", 20, true), 20)
        }
        assertNull("最早登记的 url 被淘汰", ReaderExecutionRegistry.summary(url, 21))
        assertTrue(ReaderExecutionRegistry.hasExecuted("https://s${ReaderExecutionRegistry.CAPACITY + 4}.example", 21))
    }

    @Test fun failureSignalsMarkFailure() {
        ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("debug", 1, true, failureSignals = listOf("≡未获取到书籍")), 1)
        assertTrue(ReaderExecutionRegistry.hasFailure(url, 2))
        assertEquals(listOf("≡未获取到书籍"), ReaderExecutionRegistry.summary(url, 2)!!["failureSignals"])
    }

    @Test fun verifyPayloadWithoutExecutionIsNotRecorded() {
        ReaderExecutionRegistry.recordVerifyPayload(url, mapOf("status" to "error", "errorCode" to "UNREACHABLE"), 1)
        assertFalse(ReaderExecutionRegistry.hasExecuted(url, 2))
    }

    @Test fun verifyPayloadWithLogsOrStatusIsRecorded() {
        ReaderExecutionRegistry.recordVerifyPayload(url, mapOf("status" to "legacy_debug_only", "debugLogs" to listOf("︽搜索页解析完成")), 1)
        val s = ReaderExecutionRegistry.summary(url, 2)!!
        assertEquals("legacy_debug_only", s["verifyStatus"])
        assertEquals(listOf("search"), s["stagesReached"])
        assertFalse(ReaderExecutionRegistry.hasFailure(url, 2))

        ReaderExecutionRegistry.clear()
        ReaderExecutionRegistry.recordVerifyPayload(url, mapOf("status" to "failed"), 1)
        assertTrue(ReaderExecutionRegistry.hasFailure(url, 2))

        ReaderExecutionRegistry.clear()
        ReaderExecutionRegistry.recordVerifyPayload(url, mapOf("errorCode" to "TIMEOUT", "debugLogs" to listOf("开始搜索")), 1)
        assertEquals(emptyList<String>(), ReaderExecutionRegistry.summary(url, 2)!!["failureSignals"])
        assertFalse(ReaderExecutionRegistry.hasFailure(url, 2))
    }

    @Test fun latestDebugWinsWhileHistoricalFailureRemainsVisibleAndProbeIsSeparate() {
        ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("debug", 1, true,
            failureSignals = listOf("旧失败")), 1)
        ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("debug", 2, true,
            status = "legacy_debug_only", stagesReached = listOf("search")), 2)
        val current = ReaderExecutionRegistry.summary(url, 3)!!
        assertEquals(emptyList<String>(), current["failureSignals"])
        assertEquals(listOf("旧失败"), current["historicalFailureSignals"])
        assertFalse(ReaderExecutionRegistry.hasFailure(url, 3))
        ReaderExecutionRegistry.record("$url#probeUA", ReaderExecutionRegistry.Record("search", 3, true,
            searchHits = 1), 3)
        assertEquals(listOf("debug"), ReaderExecutionRegistry.summary(url, 4)!!["kinds"])
        assertEquals(1, ReaderExecutionRegistry.summary("$url#probeUA", 4)!!["searchHits"])
    }

    @Test fun evidenceCannotCrossContentOrConfigAndNewFailureWins() {
        val success = ReaderExecutionRegistry.Record("verify", 10, true, verifyStatus = "passed",
            sourceHash = "source-a", configFingerprint = "config-a", complete = true)
        ReaderExecutionRegistry.record(url, success, 10)
        assertTrue(ReaderExecutionRegistry.passed(ReaderExecutionRegistry.summary(url, 11, "source-a", "config-a")))
        assertFalse(ReaderExecutionRegistry.passed(ReaderExecutionRegistry.summary(url, 11, "source-b", "config-a")))
        assertFalse(ReaderExecutionRegistry.passed(ReaderExecutionRegistry.summary(url, 11, "source-a", "config-b")))
        ReaderExecutionRegistry.record(url, success.copy(at = 20, verifyStatus = "failed"), 20)
        ReaderExecutionRegistry.record(url, success.copy(at = 15), 21)
        val latest = ReaderExecutionRegistry.summary(url, 22, "source-a", "config-a")!!
        assertEquals("failed", latest["verifyStatus"])
        assertFalse(ReaderExecutionRegistry.passed(latest))
    }

    @Test fun laterIncompleteAttemptCannotReuseOldSuccessfulEvidence() {
        ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("verify", 10, true,
            verifyStatus = "passed", sourceHash = "a", configFingerprint = "b", complete = true), 10)
        ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("debug", 20, false,
            status = "TIMEOUT", sourceHash = "a", configFingerprint = "b"), 20)
        val summary = ReaderExecutionRegistry.summary(url, 21, "a", "b")!!
        assertFalse(ReaderExecutionRegistry.passed(summary))
        assertEquals(false, summary["complete"])
        assertEquals(false, summary["executed"])
    }

    @Test fun reachedStagesDoNotProveSuccessAndGlobalSearchDoesNotCertifyContent() {
        ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("debug", 1, true,
            stagesReached = ReaderExecutionRegistry.REQUIRED_STAGES, sourceHash = "a", configFingerprint = "b", complete = true), 1)
        assertFalse(ReaderExecutionRegistry.passed(ReaderExecutionRegistry.summary(url, 2, "a", "b")))
        ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record("search", 3, true,
            searchHits = 1, configFingerprint = "b", complete = true), 3)
        assertEquals(false, ReaderExecutionRegistry.summary(url, 4, "a", "b")!!["identityMatches"])
    }

    @Test fun debugRequiresNormalCloseTerminalAndUntruncatedLogs() {
        val payload = mapOf<String, Any?>("closeCode" to 1000, "completionSignal" to true)
        assertTrue(ReaderExecutionRegistry.debugComplete(payload))
        assertFalse(ReaderExecutionRegistry.debugComplete(payload - "closeCode"))
        assertFalse(ReaderExecutionRegistry.debugComplete(payload + ("closeCode" to 1006)))
        assertFalse(ReaderExecutionRegistry.debugComplete(payload + ("truncated" to true)))
        assertFalse(ReaderExecutionRegistry.debugComplete(payload + ("errorCode" to "TIMEOUT")))
    }

    @Test fun configurationRevisionAndCookieDefaultsArePartOfIdentityRules() {
        val first = ReaderWebConfigStore.Config("http://127.0.0.1:1162", "token", 1)
        assertFalse(ReaderExecutionRegistry.configFingerprintOf(first) == ReaderExecutionRegistry.configFingerprintOf(first.copy(revision = 2)))
        assertFalse(ReaderExecutionRegistry.configFingerprintOf(first) == ReaderExecutionRegistry.configFingerprintOf(first.copy(token = "different")))
        val source = com.google.gson.JsonObject().apply { addProperty("bookSourceUrl", url) }
        val explicit = source.deepCopy().apply { addProperty("enabledCookieJar", true) }
        assertEquals(ReaderExecutionRegistry.sourceHashOf(source), ReaderExecutionRegistry.sourceHashOf(explicit))
        explicit.addProperty("enabledCookieJar", false)
        assertFalse(ReaderExecutionRegistry.sourceHashOf(source) == ReaderExecutionRegistry.sourceHashOf(explicit))
    }

    @Test fun searchByOriginKeepsProbeSeparateFromOfficialKey() {
        val payload = ReaderWebSearchClient.Outcome("completed", true, listOf(
            mapOf("origin" to "$url#probeUA", "name" to "探测"),
            mapOf("origin" to url, "name" to "正式"),
        ), 2, false, 1000).toPayload()
        assertEquals(mapOf("$url#probeUA" to 1, url to 1), payload["byOrigin"])
    }
}
