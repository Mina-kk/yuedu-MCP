package com.mina.legadostudio.reader

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 主 `state` 必须是**本次检验新写入**的状态：
 * timeout / stale_state / session_unconfirmed / session_replaced 都不得把历史 PASSED 放进主 state，
 * 历史状态只能经 `lastObservedState` + `belongsToThisRun=false` 暴露。
 */
class ReaderWebVerifierStateTest {

    private val url = "https://book.test/source"
    private val stalePassed =
        """{"bookSourceUrl":"$url","status":"PASSED","detail":"上一轮通过","revision":"rv-old","sourceRevision":"r1","checkedAt":1600000000000}"""

    private fun okJson(data: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody("""{"isSuccess":true,"errorMsg":"","data":$data}""")

    private fun verifier(server: MockWebServer): ReaderWebVerifier = ReaderWebVerifier(
        ReaderWebClient(
            configProvider = { ReaderWebConfigStore.Config(server.url("/").toString().removeSuffix("/"), "") },
            clientFactory = {
                OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                    .callTimeout(5, TimeUnit.SECONDS).build()
            },
        ),
        pollIntervalMs = 0,
        now = run {
            var tick = 0L
            { (tick + 1_000).also { tick = it } }
        },
    )

    private fun statesSource(
        before: String,
        poll: (Int) -> String,
        sessionTokenAfterStart: String? = "p:1",
    ): Dispatcher = object : Dispatcher() {
        private var polls = 0
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
            request.path!!.startsWith("/getBookSourcesForManagement") ->
                okJson("""{"sources":[{"bookSourceName":"A","bookSourceUrl":"$url"}],"states":[$before]}""")
            request.path!!.startsWith("/startBookSourceCheck") ->
                okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
            else -> okJson(poll(++polls))
        }
    }

    @Test fun timeoutKeepsHistoricalPassedOutOfMainState() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            // 本次检验一直 NEEDS_CHECK，预算用尽 → timeout；旧 PASSED 只能出现在 lastObservedState
            server.dispatcher = statesSource(
                before = "{\"bookSourceUrl\":\"$url\",\"status\":\"PASSED\",\"revision\":\"rv-old\",\"checkedAt\":1}",
                poll = {
                    """{"states":[{"bookSourceUrl":"$url","status":"NEEDS_CHECK","revision":"rv-2","sourceRevision":"r1","checkedAt":0}],"sessionToken":"p:1"}"""
                },
            )
            server.start()
            val outcome = verifier(server).verify(url, null, timeoutSec = 10)
            assertEquals("timeout", outcome.status)
            assertNull("超时时不得有主 state", outcome.state)
            val payload = outcome.toPayload()
            assertNull(payload["state"])
            assertEquals(false, payload["belongsToThisRun"])
        }
    }

    @Test fun stalePassedIsOnlyExposedAsLastObservedState() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = statesSource(
                before = stalePassed,
                poll = { """{"states":[$stalePassed],"sessionToken":"p:1"}""" },
            )
            server.start()
            val outcome = verifier(server).verify(url, null, timeoutSec = 10)
            assertEquals("stale_state", outcome.status)
            assertNull("旧 PASSED 不得占主 state", outcome.state)
            val payload = outcome.toPayload()
            assertNull(payload["state"])
            assertEquals(false, payload["belongsToThisRun"])
            val observed = payload["lastObservedState"] as com.google.gson.JsonObject
            assertEquals("PASSED", observed.get("status").asString)
            assertEquals("rv-old", observed.get("revision").asString)
            assertFalse("主 state 不得出现 PASSED", payload["state"] == "PASSED")
        }
    }

    @Test fun unconfirmedSessionPassedIsNotMainState() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = statesSource(
                before = "{}",
                poll = {
                    """{"states":[{"bookSourceUrl":"$url","status":"PASSED","detail":"来历不明","revision":"rv-2","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":null}"""
                },
            )
            server.start()
            val outcome = verifier(server).verify(url, null, timeoutSec = 10)
            assertEquals("session_unconfirmed", outcome.status)
            assertNull(outcome.state)
            assertEquals(false, outcome.toPayload()["belongsToThisRun"])
        }
    }

    @Test fun replacedSessionDoesNotPublishItsPassed() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = statesSource(
                before = "{}",
                poll = {
                    """{"states":[{"bookSourceUrl":"$url","status":"PASSED","revision":"rv-other","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":"p:2"}"""
                },
            )
            server.start()
            val outcome = verifier(server).verify(url, null, timeoutSec = 10)
            assertEquals("session_replaced", outcome.status)
            assertNull(outcome.state)
        }
    }

    @Test fun pollingFailureCarriesRequestDiagnosticInOutcome() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            var stateCalls = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[{"bookSourceName":"A","bookSourceUrl":"$url"}],"states":[]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> {
                        stateCalls++
                        // 轮询第一条就回 503：不是「无法归属」，而是阅读暂时不可用
                        MockResponse().setResponseCode(503)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val outcome = verifier(server).verify(url, null, timeoutSec = 10)
            assertEquals("reader_unavailable", outcome.status)
            val diagnostic = outcome.diagnostic ?: error("轮询失败必须带脱敏诊断")
            assertEquals("GET", diagnostic.method)
            assertEquals("/getBookSourceCheckStates", diagnostic.path)
            assertEquals("states", diagnostic.stage)
            assertEquals(503, diagnostic.httpStatus)
            assertTrue(stateCalls >= 1)
            val payload = outcome.toPayload()
            assertEquals("states", (payload["diagnostic"] as Map<*, *>)["stage"])
            assertFalse("不得回显端口", payload.toString().contains(":${server.port}"))
            // 读不到状态不等于拿到结果：主 state 必须为空
            assertNull(payload["state"])
        }
    }

    @Test fun successfulOutcomeHasNoFailureDiagnostic() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = statesSource(
                before = stalePassed,
                poll = { n ->
                    if (n == 1) {
                        """{"states":[{"bookSourceUrl":"$url","status":"NEEDS_CHECK","revision":"rv-new","sourceRevision":"r1","checkedAt":0}],"sessionToken":"p:1"}"""
                    } else {
                        """{"states":[{"bookSourceUrl":"$url","status":"PASSED","revision":"rv-new","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":null}"""
                    }
                },
            )
            server.start()
            val outcome = verifier(server).verify(url, null, timeoutSec = 10)
            assertEquals("passed", outcome.status)
            assertNull("成功路径没有失败诊断", outcome.diagnostic)
            assertNull(outcome.toPayload()["diagnostic"])
        }
    }

    @Test fun errorPayloadCarriesDiagnosticOnlyWhenPresent() {
        val plain = ReaderWebVerifier.Outcome.errorPayload("TIMEOUT", "超时")
        assertFalse("没有诊断时不硬塞空字段", plain.containsKey("diagnostic"))
        assertEquals("timeout", plain["status"])
    }

    @Test fun confirmedFreshStateIsMainStateAndMarkedAsThisRun() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = statesSource(
                before = stalePassed,
                poll = { n ->
                    if (n == 1) {
                        """{"states":[{"bookSourceUrl":"$url","status":"NEEDS_CHECK","revision":"rv-new","sourceRevision":"r1","checkedAt":0}],"sessionToken":"p:1"}"""
                    } else {
                        """{"states":[{"bookSourceUrl":"$url","status":"PASSED","detail":"本次通过","revision":"rv-new","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":null}"""
                    }
                },
            )
            server.start()
            val outcome = verifier(server).verify(url, null, timeoutSec = 10)
            assertEquals("passed", outcome.status)
            val payload = outcome.toPayload()
            assertEquals(true, payload["belongsToThisRun"])
            assertNull("本次已有主 state 时不再重复历史状态", payload["lastObservedState"])
            val state = payload["state"] as com.google.gson.JsonObject
            assertEquals("rv-new", state.get("revision").asString)
            assertTrue((payload["evidenceNote"] as String).contains("不登记"))
        }
    }
}
