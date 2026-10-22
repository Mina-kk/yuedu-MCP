package com.mina.legadostudio.reader

import kotlinx.coroutines.test.runTest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ReaderWebDirectActionsTest {
    @Test fun missingSourceStopsBeforeWebSocket() = runTest {
        pair { http, ws ->
            http.enqueue(MockResponse().setBody("""{"isSuccess":false,"errorMsg":"未找到书源"}"""))
            val actions = actions(http)
            val result = actions.debug("https://books.example/source", "词", 5)
            assertEquals("SOURCE_NOT_IN_READER", result["errorCode"])
            assertEquals(false, result["executed"])
            assertEquals("/getBookSource", http.takeRequest().requestUrl!!.encodedPath)
            assertEquals(0, ws.requestCount)
        }
    }

    @Test fun activeDebugUsesSameSnapshotForLookupAndWsEvenWithoutNewRest() = runTest {
        pair { http, ws ->
            var current = ReaderWebConfigStore.Config("http://${http.hostName}:${http.port}", "old-token")
            http.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    current = ReaderWebConfigStore.Config("http://127.0.0.1:65535", "new-token")
                    return MockResponse().setBody("""{"isSuccess":true,"data":{"bookSourceUrl":"https://books.example/source"}}""")
                }
            }
            ws.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("调试完成")
                        webSocket.close(1000, "")
                    }
                })
            }
            val client = ReaderWebClient({ current })
            val actions = ReaderWebDirectActions(client, ReaderWebLegacyDebugClient({ current }), ReaderWebSearchClient({ current }))
            val result = actions.debug("https://books.example/source", "词", 5)
            assertEquals("legacy_debug_only", result["status"])
            assertEquals("unverifiable", result["machineVerdict"])
            assertEquals(true, result["executed"])
            val lookup = http.takeRequest()
            assertEquals("https://books.example/source", lookup.requestUrl!!.queryParameter("url"))
            val handshake = ws.takeRequest()
            assertEquals("/bookSourceDebug", handshake.path)
            assertEquals("legado, legado.token.b2xkLXRva2Vu", handshake.getHeader("Sec-WebSocket-Protocol"))
        }
    }

    @Test fun blankKeywordFallsBackSoOfficialReaderActuallyRunsAndIsRegistered() = runTest {
        ReaderExecutionRegistry.clear()
        pair { http, ws ->
            http.enqueue(MockResponse().setBody("""{"isSuccess":true,"data":{"bookSourceUrl":"https://books.example/source"}}"""))
            var sentKey: String? = null
            ws.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        sentKey = com.google.gson.JsonParser.parseString(text).asJsonObject.get("key").asString
                        webSocket.send("≡未获取到书籍")
                        webSocket.close(1000, "")
                    }
                })
            }
            val result = actions(http).debug("https://books.example/source", "  ", 5)
            assertEquals(ReaderWebDirectActions.DEFAULT_DEBUG_KEYWORD, sentKey)
            assertEquals(true, result["keywordDefaulted"])
            assertEquals("legacy_debug_only", result["status"])
            val signals = result["logSignals"] as Map<*, *>
            assertEquals(listOf("≡未获取到书籍"), signals["failureSignals"])
            assertTrue(ReaderExecutionRegistry.hasExecuted("https://books.example/source"))
            assertTrue(ReaderExecutionRegistry.hasFailure("https://books.example/source"))
        }
        ReaderExecutionRegistry.clear()
    }

    @Test fun searchUsesRealArrayFramesAndDeduplicatesUpdates() = runTest {
        pair { http, ws ->
            ws.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        assertEquals("词", com.google.gson.JsonParser.parseString(text).asJsonObject.get("key").asString)
                        webSocket.send("""[{"name":"甲","author":"作者","bookUrl":"https://books.example/a","origin":"https://books.example","untrusted":"ignore"}]""")
                        webSocket.send("""[{"name":"甲新","bookUrl":"https://books.example/a","origin":"https://books.example","latestChapterTitle":"第二章"}]""")
                        webSocket.close(1000, "Search finish")
                    }
                })
            }
            val result = actions(http).search("词", 5, 10)
            assertEquals("completed", result["status"])
            assertEquals(true, result["executed"])
            assertEquals(1, result["resultCount"])
            val book = (result["results"] as List<*>).single() as Map<*, *>
            assertEquals("甲新", book["name"])
            assertEquals("第二章", book["latestChapterTitle"])
            assertFalse(book.containsKey("untrusted"))
            assertEquals("/searchBook", ws.takeRequest().path)
            assertEquals(0, http.requestCount)
        }
    }

    @Test fun searchDoesNotTreatLogsAsBooksAndMissingRouteIsExplicit() = runTest {
        pair { http, ws ->
            ws.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("搜索成功：1本")
                        webSocket.close(1000, "Search finish")
                    }
                })
            }
            val bad = actions(http).search("词", 5, 10)
            assertEquals("INCOMPATIBLE_FRAME", bad["errorCode"])
            assertEquals(emptyList<Any>(), bad["results"])
            ws.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
            }
            val missing = actions(http).search("词", 5, 10)
            assertEquals("ENDPOINT_MISSING", missing["errorCode"])
            assertEquals(false, missing["executed"])
        }
    }

    @Test fun searchRedactsOriginAndTokenInActualFieldsAndNeverFollowsRedirect() = runTest {
        pair { http, ws ->
            val secret = "secret-token"
            ws.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("""[{"name":"$secret","origin":"http://${http.hostName}:${http.port}","bookUrl":"https://books.example/a"}]""")
                        webSocket.close(1000, "Search finish")
                    }
                })
            }
            val config = ReaderWebConfigStore.Config("http://${http.hostName}:${http.port}", secret)
            val result = ReaderWebSearchClient({ config }).searchAt(config, "词", 5_000).toPayload()
            val serialized = result.toString()
            assertFalse(serialized.contains(secret))
            assertFalse(serialized.contains("http://${http.hostName}:${http.port}"))
            assertTrue(serialized.contains("[已隐藏]"))
            ws.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(302)
                    .setHeader("Location", "http://example.invalid/steal")
            }
            val failed = actions(http).search("词", 5, 10)
            assertEquals("REDIRECT_BLOCKED", failed["errorCode"])
            assertFalse(failed.toString().contains("example.invalid"))
        }
    }

    private fun actions(http: MockWebServer): ReaderWebDirectActions {
        val config = ReaderWebConfigStore.Config("http://${http.hostName}:${http.port}", "")
        return ReaderWebDirectActions(ReaderWebClient({ config }), ReaderWebLegacyDebugClient({ config }), ReaderWebSearchClient({ config }))
    }

    private fun pair(block: suspend (MockWebServer, MockWebServer) -> Unit) {
        repeat(20) {
            MockWebServer().use { ws ->
                ws.start()
                if (ws.port <= 1025) return@use
                try {
                    MockWebServer().use { http ->
                        http.start(ws.port - 1)
                        kotlinx.coroutines.runBlocking { block(http, ws) }
                    }
                    return
                } catch (error: java.net.BindException) {
                    // 下一次随机端口；测试仍验证严格 +1，而非替换生产端点规则。
                }
            }
        }
        error("无法取得相邻本机测试端口")
    }
}
