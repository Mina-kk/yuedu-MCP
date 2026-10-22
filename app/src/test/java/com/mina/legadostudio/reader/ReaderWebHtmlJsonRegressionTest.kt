package com.mina.legadostudio.reader

import com.google.gson.JsonParser
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
import java.util.concurrent.atomic.AtomicReference

class ReaderWebHtmlJsonRegressionTest {
    private val url = "https://example.test/source"
    private val source = JsonParser.parseString(
        """{"bookSourceUrl":"$url","bookSourceName":"Example","searchUrl":"https://example.test/search"}"""
    ).asJsonObject

    private fun response(data: String, success: Boolean = true, bom: Boolean = false): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody((if (bom) "\uFEFF" else "") +
            """{"isSuccess":$success,"errorMsg":"${if (success) "" else "未找到源"}","data":$data}""")

    private fun client(server: MockWebServer) = ReaderWebClient(
        configProvider = { ReaderWebConfigStore.Config(server.url("/").toString(), "") },
        clientFactory = {
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .callTimeout(5, TimeUnit.SECONDS).build()
        },
    )

    @Test fun statusRecognizesLegacyReturnDataDespiteHtmlHeaderAndBom() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Reader</html>")
                    "/getJsSourceApiTokenRequired" -> MockResponse().setResponseCode(404)
                    "/getBookSources" -> response("[]", bom = true)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = ReaderWebBridge(client(server)).status()
            assertEquals("ok", status.toPayload()["status"])
            assertEquals(ReaderWebBridge.ApiProfile.LEGACY_READ, status.apiProfile)
            assertEquals(ReaderWebBridge.Capability.SUPPORTED, status.capabilities.getValue("read").state)
            assertEquals(ReaderWebBridge.Capability.UNSUPPORTED, status.capabilities.getValue("check").state)
            assertTrue(status.webPageReachable)
        }
    }

    @Test fun syncReadsHtmlLabeledJsonBeforeAndAfterPostButNeverWritesOnAmbiguousLookup() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val stored = AtomicReference<String?>(null)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getBookSource?") ->
                        stored.get()?.let { response(it, bom = true) } ?: response("null", success = false, bom = true)
                    request.path == "/saveBookSource" && request.method == "POST" -> {
                        stored.set(request.body.readUtf8())
                        response("\"\"", bom = true)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
            server.start()
            val sync = ReaderWebSourceSync(client(server), sourceProvider = { source.toString() })
            val result = sync.sync("project", overwrite = false)
            assertEquals("written", result.status)
            assertTrue(result.confirmed)
            assertEquals(listOf("GET", "POST", "GET"),
                (result.toPayload()["requests"] as List<*>).map { (it as Map<*, *>)["method"] })
            val requests = (1..3).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
            assertTrue(requests[0].path!!.startsWith("/getBookSource?url="))
            assertEquals("/saveBookSource", requests[1].path)
            assertTrue(requests[2].path!!.startsWith("/getBookSource?url="))
            assertEquals(3, server.requestCount)
        }
        MockWebServer().use { server ->
            server.enqueue(response("null", bom = true))
            server.start()
            val sync = ReaderWebSourceSync(client(server), sourceProvider = { source.toString() })
            val error = runCatching { sync.sync("project", overwrite = true) }.exceptionOrNull()
                as ReaderWebSourceSync.SyncException
            assertEquals("INCOMPATIBLE", error.code)
            assertFalse(error.writeAttempted)
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun verifyFallsBackOnlyAfterExactLookupAndNeverCallsWsForHtmlOrMissingSource() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/getJsSourceApiTokenRequired" -> MockResponse()
                        .setHeader("Content-Type", "text/html").setBody("<html>no new API</html>")
                    request.path!!.startsWith("/getBookSource?") -> response(source.toString(), bom = true)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            var debugCalls = 0
            val outcome = ReaderWebVerifier(client(server), legacyDebug = { _, _, _, _ ->
                debugCalls++
                ReaderWebLegacyDebugClient.Outcome(
                    sourceUrl = url, keyword = "query", debugLogs = listOf("diagnostic only"),
                    frames = 1, bytes = 15, truncated = false, closeCode = 1000,
                    closeReason = "", elapsedMs = 1, completionSignal = false,
                )
            }).verify(url, "query", 10)
            assertEquals(1, debugCalls)
            assertEquals("legacy_debug_only", outcome.status)
            assertEquals("unverifiable", outcome.toPayload()["machineVerdict"])
            assertNull(outcome.state)
            assertEquals(listOf("GET", "GET", "GET"),
                (1..3).map { server.takeRequest(1, TimeUnit.SECONDS)!!.method })
            assertEquals(3, server.requestCount)
        }
        listOf(
            MockResponse().setHeader("Content-Type", "text/html").setBody("<html>not a source</html>") to "INCOMPATIBLE",
            response("null", success = false, bom = true) to "SOURCE_NOT_IN_READER",
        ).forEach { (lookup, expectedCode) ->
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path == "/getJsSourceApiTokenRequired" -> MockResponse()
                            .setHeader("Content-Type", "text/html").setBody("<html>no new API</html>")
                        request.path!!.startsWith("/getBookSource?") -> lookup
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                server.start()
                var debugCalls = 0
                val error = runCatching {
                    ReaderWebVerifier(client(server), legacyDebug = { _, _, _, _ ->
                        debugCalls++
                        error("WS must not run")
                    }).verify(url, "query", 10)
                }.exceptionOrNull() as ReaderWebVerifier.VerifyException
                assertEquals(expectedCode, error.code)
                assertEquals(0, debugCalls)
                assertEquals(3, server.requestCount)
                assertTrue((1..3).map { server.takeRequest(1, TimeUnit.SECONDS)!!.method }.all { it == "GET" })
            }
        }
    }

    @Test fun notReaderProbeRequiresASecondExactSuccessfulLookupBeforeWs() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            var lookups = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/getJsSourceApiTokenRequired" -> MockResponse().setResponseCode(404)
                    request.path!!.startsWith("/getBookSource?") -> {
                        lookups++
                        if (lookups == 1) MockResponse().setHeader("Content-Type", "text/html")
                            .setBody("<html>unsupported</html>")
                        else response(source.toString(), bom = true)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            var debugCalls = 0
            val result = ReaderWebVerifier(client(server), legacyDebug = { _, _, _, _ ->
                debugCalls++
                ReaderWebLegacyDebugClient.Outcome(
                    sourceUrl = url, keyword = "query", debugLogs = listOf("only logs"),
                    frames = 1, bytes = 9, truncated = false, closeCode = 1000,
                    closeReason = "", elapsedMs = 1, completionSignal = false,
                )
            }).verify(url, "query", 10)
            assertEquals(2, lookups)
            assertEquals(1, debugCalls)
            assertEquals("NOT_READER", result.toPayload()["structuredCheckCode"])
            assertEquals("unverifiable", result.toPayload()["machineVerdict"])
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun realHtmlIsNotReturnDataEvenWhenHeaderSaysJson() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("\uFEFF<html>not JSON</html>"))
            server.start()
            val error = runCatching { client(server).fetchSource(url) }.exceptionOrNull()
                as ReaderWebClient.ReaderWebException
            assertEquals("INCOMPATIBLE", error.code)
            assertEquals("lookup", error.stage)
            assertEquals(1, server.requestCount)
        }
    }
}
