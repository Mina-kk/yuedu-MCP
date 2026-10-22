package com.mina.legadostudio.reader

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
 * `reader_web_verify` 的旧版降级：只有**结构化检验接口实际缺失**、且书源确在阅读时才跑
 * 真实 WebSocket 调试；回包恒为 legacy_debug_only/unverifiable，绝不产出 PASSED。
 *
 * 不可达、未配置、令牌错误、书源不在阅读等情形必须保留原错误分类，不能误降级。
 */
class ReaderWebVerifierFallbackTest {

    private val url = "https://book.test/source"

    private fun okJson(data: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody("""{"isSuccess":true,"errorMsg":"","data":$data}""")

    private fun missingJson(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody("""{"isSuccess":false,"errorMsg":"未找到源","data":null}""")

    private fun httpClient(): ReaderWebClient = ReaderWebClient(
        configProvider = { null },
        clientFactory = {
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .callTimeout(5, TimeUnit.SECONDS).build()
        },
    )

    private fun readerClient(baseUrl: String, token: String = ""): ReaderWebClient =
        ReaderWebClient(
            configProvider = { ReaderWebConfigStore.Config(baseUrl, token) },
            clientFactory = {
                OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                    .callTimeout(5, TimeUnit.SECONDS).build()
            },
        )

    /** 旧版阅读：新接口 404/HTML，旧公开读可用。 */
    private fun legacyDispatcher(hasSource: Boolean = true): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path!!.startsWith("/getJsSourceApiTokenRequired") -> MockResponse().setResponseCode(404)
            request.path!!.startsWith("/getBookSourcesForManagement") -> MockResponse().setResponseCode(404)
            request.path!!.startsWith("/getBookSource?") ->
                if (hasSource) {
                    okJson("""{"bookSourceName":"测试源","bookSourceUrl":"$url"}""")
                } else {
                    missingJson()
                }
            request.path!!.startsWith("/getBookSources") -> okJson("[]")
            else -> MockResponse().setResponseCode(404)
        }
    }

    @Test fun legacyReaderRunsRealWebSocketDebugAndNeverPasses() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = legacyDispatcher()
            server.start()
            val httpOrigin = server.url("/").toString().removeSuffix("/")
            val ws = MockWebServer()
            ws.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            webSocket.send("开始搜索：我的")
                            webSocket.send("搜索失败：站点超时")
                            webSocket.close(1000, "")
                        }
                    })
            }
            ws.start()
            try {
                val verifier = ReaderWebVerifier(
                    readerClient(httpOrigin),
                    pollIntervalMs = 0,
                    legacyDebug = { config, u, keyword, timeoutMs ->
                        // 必须收到本次调用固定的 HTTP 配置快照（同一 origin），而不是重新读配置
                        assertEquals(httpOrigin, config?.baseUrl)
                        // 测试替身：把调试端口换成 MockWebServer（生产固定 Web 端口 +1）
                        ReaderWebLegacyDebugClient(
                            configProvider = { null },
                            clientFactory = { OkHttpClient() },
                            endpointResolver = { ReaderWebEndpoint.derive("http://${ws.hostName}:${ws.port - 1}") },
                        ).debugAt(
                            ReaderWebConfigStore.Config("http://${ws.hostName}:${ws.port - 1}", config?.token.orEmpty()),
                            u, keyword, totalTimeoutMs = timeoutMs,
                        )
                    },
                )
                val outcome = verifier.verify(url, "关键词", timeoutSec = 20)
                assertEquals("legacy_debug_only", outcome.status)
                assertNull("旧版通道不产生结构化 state", outcome.state)
                val payload = outcome.toPayload()
                assertEquals("legacy_debug_only", payload["status"])
                assertEquals("unverifiable", payload["machineVerdict"])
                assertEquals("reader_web_legacy_debug", payload["evidenceScope"])
                assertEquals("structured_check_unavailable", payload["reason"])
                assertEquals("LEGACY_READER", payload["structuredCheckCode"])
                assertNull("旧版通道绝不给出结构化 state", payload["state"])
                assertFalse("status 不得为 PASSED", payload["status"] == "PASSED")
                assertFalse("machineVerdict 不得为 PASSED", payload["machineVerdict"] == "PASSED")
                assertTrue((payload["debugLogs"] as List<*>).any { it.toString().contains("搜索失败") })
            } finally {
                ws.shutdown()
            }
        }
    }

    @Test fun sourceMissingInReaderIsNotDowngradedToDebug() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = legacyDispatcher(hasSource = false)
            server.start()
            var debugCalled = false
            val verifier = ReaderWebVerifier(
                readerClient(server.url("/").toString()),
                pollIntervalMs = 0,
                legacyDebug = { _, _, _, _ -> debugCalled = true; error("不应运行") },
            )
            val error = runCatching { verifier.verify(url, null, 10) }.exceptionOrNull()
            assertTrue(error is ReaderWebVerifier.VerifyException)
            assertEquals("SOURCE_NOT_IN_READER", (error as ReaderWebVerifier.VerifyException).code)
            assertFalse("书源不在阅读时不得跑调试", debugCalled)
        }
    }

    @Test fun unreachableReaderIsNotDowngradedToDebug() = kotlinx.coroutines.test.runTest {
        // 先占用再关闭，拿到必然拒绝连接的端口
        val port = MockWebServer().use { it.start(); it.port }
        var debugCalled = false
        val verifier = ReaderWebVerifier(
            readerClient("http://127.0.0.1:$port"),
            pollIntervalMs = 0,
            legacyDebug = { _, _, _, _ -> debugCalled = true; error("不应运行") },
        )
        val error = runCatching { verifier.verify(url, null, 5) }.exceptionOrNull()
            as ReaderWebVerifier.VerifyException
        assertTrue("code=${error.code}", error.code in setOf("UNREACHABLE", "TIMEOUT"))
        assertFalse("不可达不得降级", debugCalled)
        // 失败请求的根阶段必须透出，而不是只剩一句文案
        val diagnostic = error.diagnostic ?: error("必须带脱敏诊断")
        assertEquals("GET", diagnostic.method)
        assertEquals("/getJsSourceApiTokenRequired", diagnostic.path)
        assertEquals("token_probe", diagnostic.stage)
        assertTrue("必须是传输失败类别", diagnostic.exceptionCategory != null)
        assertNull("没拿到 HTTP 状态", diagnostic.httpStatus)
        val payload = ReaderWebVerifier.Outcome.errorPayload(error.code, error.message.orEmpty(), diagnostic)
        assertEquals(diagnostic.toPayload(), payload["diagnostic"])
        assertFalse("诊断不得回显端口", payload.toString().contains(":$port"))
    }

    /**
     * 结构化检验路由不可用（这里用 500）：错误回包必须说出「卡在 sources 阶段、
     * 打的是哪条固定路径」，让模型能区分接口缺失与网络失败。
     */
    @Test fun missingCheckRouteFailureKeepsStageInErrorPayload() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    else -> MockResponse().setResponseCode(500)
                }
            }
            server.start()
            val verifier = ReaderWebVerifier(
                readerClient(server.url("/").toString()),
                pollIntervalMs = 0,
            )
            val error = runCatching { verifier.verify(url, null, 10) }.exceptionOrNull()
                as ReaderWebVerifier.VerifyException
            val diagnostic = error.diagnostic ?: error("必须带脱敏诊断")
            assertEquals("sources", diagnostic.stage)
            assertEquals("/getBookSourcesForManagement", diagnostic.path)
            assertEquals("GET", diagnostic.method)
            assertEquals(500, diagnostic.httpStatus)
            val payload = ReaderWebVerifier.Outcome.errorPayload(error.code, error.message.orEmpty(), diagnostic)
            assertEquals("sources", (payload["diagnostic"] as Map<*, *>)["stage"])
            assertFalse("不得回显 host", payload.toString().contains(server.hostName))
            assertFalse("不得回显端口", payload.toString().contains(":${server.port}"))
        }
    }

    @Test fun tokenProtectedReaderWithoutTokenIsNotDowngraded() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path!!.startsWith("/getJsSourceApiTokenRequired")) okJson("true")
                    else MockResponse().setResponseCode(401)
            }
            server.start()
            var debugCalled = false
            val verifier = ReaderWebVerifier(
                readerClient(server.url("/").toString(), token = ""),
                pollIntervalMs = 0,
                legacyDebug = { _, _, _, _ -> debugCalled = true; error("不应运行") },
            )
            val error = runCatching { verifier.verify(url, null, 10) }.exceptionOrNull()
                as ReaderWebVerifier.VerifyException
            assertEquals("TOKEN_REQUIRED", error.code)
            assertFalse(debugCalled)
        }
    }

    @Test fun legacyDebugFailureKeepsItsOwnErrorPayload() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = legacyDispatcher()
            server.start()
            val verifier = ReaderWebVerifier(
                readerClient(server.url("/").toString()),
                pollIntervalMs = 0,
                legacyDebug = { _, _, _, _ ->
                    throw ReaderWebLegacyDebugClient.LegacyDebugException(
                        "SERVICE_BUSY",
                        "阅读调试通道正忙",
                        listOf("已有调试任务"),
                    )
                },
            )
            val error = runCatching { verifier.verify(url, null, 10) }.exceptionOrNull()
                as ReaderWebVerifier.VerifyException
            assertEquals("SERVICE_BUSY", error.code)
            val payload = error.payload ?: error("必须保留旧版回包")
            assertEquals("legacy_debug_only", payload["status"])
            assertEquals("unverifiable", payload["machineVerdict"])
            assertEquals("SERVICE_BUSY", payload["errorCode"])
            assertEquals(listOf("已有调试任务"), payload["debugLogs"])
            assertFalse(payload.toString().contains("PASSED"))
        }
    }

    @Test fun defaultKeywordIsUsedForLegacyDebug() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = legacyDispatcher()
            server.start()
            var seenKeyword: String? = null
            val verifier = ReaderWebVerifier(
                readerClient(server.url("/").toString()),
                pollIntervalMs = 0,
                legacyDebug = { _, _, keyword, _ ->
                    seenKeyword = keyword
                    ReaderWebLegacyDebugClient.Outcome(
                        sourceUrl = url, keyword = keyword, debugLogs = listOf("ok"), frames = 1, bytes = 2,
                        truncated = false, closeCode = 1000, closeReason = "", elapsedMs = 1, completionSignal = false,
                    )
                },
            )
            val outcome = verifier.verify(url, null, 10)
            assertEquals("我的", seenKeyword)
            assertEquals("legacy_debug_only", outcome.status)
        }
    }

    @Test fun modernReaderNeverTouchesLegacyChannel() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            var stateCalls = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[{"bookSourceName":"A","bookSourceUrl":"$url"}],"states":[]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                    else -> {
                        stateCalls++
                        if (stateCalls == 1) {
                            okJson("""{"states":[{"bookSourceUrl":"$url","status":"NEEDS_CHECK","revision":"rv-2","sourceRevision":"r1","checkedAt":0}],"sessionToken":"p:1"}""")
                        } else {
                            okJson("""{"states":[{"bookSourceUrl":"$url","status":"PASSED","detail":"本次通过","revision":"rv-2","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":null}""")
                        }
                    }
                }
            }
            server.start()
            var debugCalled = false
            val verifier = ReaderWebVerifier(
                readerClient(server.url("/").toString()),
                pollIntervalMs = 0,
                legacyDebug = { _, _, _, _ -> debugCalled = true; error("不应运行") },
            )
            val outcome = verifier.verify(url, null, 10)
            assertEquals("passed", outcome.status)
            assertFalse("新版结构化检验可用时不得触碰旧版通道", debugCalled)
            assertEquals(true, outcome.toPayload()["belongsToThisRun"])
        }
    }
}
