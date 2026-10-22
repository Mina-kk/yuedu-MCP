package com.mina.legadostudio.reader

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 旧版调试通道：目标派生、请求形状、令牌子协议、完成/忙碌/超时/恶意重定向/日志上限/脱敏/取消。
 *
 * 全部走 MockWebServer 的 WebSocket 升级，不触碰真实局域网地址，也不访问模型给出的任何 URL。
 * 客户端固定按「用户 origin 端口 + 1」连接，所以测试把配置 origin 写成 `server.port - 1`，
 * 派生后正好命中 MockWebServer。
 */
class ReaderWebLegacyDebugTest {

    // ---- 目标派生（纯逻辑） ----

    @Test fun debugTargetIsAlwaysWebPortPlusOneWithFixedPath() {
        val target = ReaderWebEndpoint.derive("http://192.168.1.8:1122")
        assertEquals("192.168.1.8", target.host)
        assertEquals(1123, target.port)
        assertEquals("/bookSourceDebug", target.path)
        assertEquals("ws://192.168.1.8:1123/bookSourceDebug", target.display)

        // 协议随 origin：https → wss
        val secure = ReaderWebEndpoint.derive("https://reader.lan:8443")
        assertEquals("wss://reader.lan:8444/bookSourceDebug", secure.display)

        // 回环别名仍由 ReaderWebOrigin 归一，派生只看主机与端口
        val loop = ReaderWebEndpoint.derive(ReaderWebOrigin.normalize("http://127.0.01:1162"))
        assertEquals("127.0.0.1", loop.host)
        assertEquals(1163, loop.port)
    }

    @Test fun debugTargetRejectsPortOverflowAndNonOriginInput() {
        val overflow = runCatching { ReaderWebEndpoint.derive("http://127.0.0.1:65535") }.exceptionOrNull()
        assertTrue(overflow is ReaderWebEndpoint.InvalidEndpointException)
        assertEquals("PORT_OVERFLOW", (overflow as ReaderWebEndpoint.InvalidEndpointException).code)

        listOf(
            "http://127.0.0.1:1122/web",
            "http://127.0.0.1:1122/?a=1",
            "ftp://127.0.0.1:1122",
            "not a url",
            "",
        ).forEach { raw ->
            val error = runCatching { ReaderWebEndpoint.derive(raw) }.exceptionOrNull()
            assertTrue("应拒绝: $raw", error is ReaderWebEndpoint.InvalidEndpointException)
        }
    }

    @Test fun tokenSubprotocolIsBase64UrlWithoutPadding() {
        assertEquals(listOf("legado"), ReaderWebEndpoint.subprotocols(""))
        assertEquals(listOf("legado"), ReaderWebEndpoint.subprotocols("   "))
        assertEquals(
            "legado, legado.token.c2VjcmV0LXRva2Vu",
            ReaderWebEndpoint.headerValue("secret-token"),
        )
        // 无填充：字节数不被 3 整除时不出现 '='
        assertEquals("YQ", ReaderWebEndpoint.encodeToken("a"))
        assertEquals("YWI", ReaderWebEndpoint.encodeToken("ab"))
        // url-safe 字母表：标准 base64 的 "w7/Dvw==" 在这里必须是 "w7_Dvw"
        assertEquals("w7_Dvw", ReaderWebEndpoint.encodeToken("ÿÿ"))
    }

    // ---- 请求形状 ----

    /** 供子协议用例观察「是否真的把请求发出去了」。 */
    @Volatile
    private var socketGotPayload = false

    @Test fun sendsLegadoSubprotocolAndJsonObjectWithoutToken() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val received = AtomicReference<String>()
            server.dispatcher = webSocketDispatcher(onMessage = { socket, text ->
                received.set(text)
                socket.close(1000, "")
                true
            })
            server.start()

            val outcome = client(originFor(server)).debug(
                sourceUrl = "https://book.test/source",
                keyword = "关键词",
            )
            val payload = outcome.toPayload()
            assertEquals("legacy_debug_only", payload["status"])
            assertEquals("unverifiable", payload["machineVerdict"])
            assertEquals("reader_web_legacy_debug", payload["evidenceScope"])
            assertEquals(1000, outcome.closeCode)

            // 请求 JSON 恰好两个字符串字段
            val body = com.google.gson.JsonParser.parseString(received.get()).asJsonObject
            assertEquals(2, body.size())
            assertEquals("https://book.test/source", body.get("tag").asString)
            assertEquals("关键词", body.get("key").asString)

            val request = server.takeRequest()
            assertEquals("/bookSourceDebug", request.path)
            assertEquals("legado", request.getHeader("Sec-WebSocket-Protocol"))
            assertNull("不得发送 Cookie", request.getHeader("Cookie"))
            assertNull("不得发送令牌头", request.getHeader("X-Legado-Token"))
        }
    }

    @Test fun configuredTokenGoesOnlyIntoSubprotocolHeader() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher(onMessage = { socket, _ ->
                socket.close(1000, "")
                true
            })
            server.start()

            client(originFor(server), token = "secret-token")
                .debug(sourceUrl = "https://book.test/source", keyword = "k")

            val request = server.takeRequest()
            assertEquals(
                "legado, legado.token.c2VjcmV0LXRva2Vu",
                request.getHeader("Sec-WebSocket-Protocol"),
            )
            // 令牌不出现在任何其它头里
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Legado-Token"))
            assertFalse(request.headers.toString().contains("secret-token"))
        }
    }

    @Test fun modelSourceUrlNeverChangesTheConnectionTarget() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher(onMessage = { socket, _ ->
                socket.close(1000, "")
                true
            })
            server.start()
            // 模型给的 sourceUrl 指向完全不同的主机：它只能进 JSON，不能改变连接目标
            val outcome = client(originFor(server)).debug(
                sourceUrl = "https://attacker.example:9999/steal",
                keyword = "",
            )
            assertEquals(1000, outcome.closeCode)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals(server.hostName, request.requestUrl!!.host)
            assertEquals(server.port, request.requestUrl!!.port)
            assertEquals("/bookSourceDebug", request.requestUrl!!.encodedPath)
        }
    }

    @Test fun serverSelectedSubprotocolMustBeOneWeOffered() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            // 服务端回一个我们没有提供的子协议：必须立即断开，不发送调试请求
            socketGotPayload = false
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse()
                        .setHeader("Sec-WebSocket-Protocol", "evil-proto")
                        .withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                socketGotPayload = true
                            }

                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                webSocket.close(code, "")
                            }
                        })
            }
            server.start()

            val error = runCatching {
                client(originFor(server)).debug("https://a.test", "")
            }.exceptionOrNull()
            assertTrue(error is ReaderWebLegacyDebugClient.LegacyDebugException)
            assertEquals("PROTOCOL_ERROR", (error as ReaderWebLegacyDebugClient.LegacyDebugException).code)
            assertFalse("不得把调试请求发给未协商一致的对端", socketGotPayload)
        }
    }

    @Test fun serverSelectedLegadoSubprotocolIsAccepted() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse()
                        .setHeader("Sec-WebSocket-Protocol", "legado")
                        .withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                webSocket.send("调试完成")
                                webSocket.close(1000, "")
                            }
                        })
            }
            server.start()
            val outcome = client(originFor(server)).debug("https://a.test", "")
            assertTrue(outcome.completionSignal)
            assertEquals("unverifiable", outcome.toPayload()["machineVerdict"])
        }
    }

    // ---- 完成 / 忙碌 / 拒绝 ----

    @Test fun completedDebugIsLegacyOnlyAndNeverPassed() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher(onMessage = { socket, _ ->
                socket.send("▷ 开始搜索：关键词")
                socket.send("✓ 搜索成功：12 条结果")
                socket.close(1000, "")
                true
            })
            server.start()

            val outcome = client(originFor(server))
                .debug(sourceUrl = "https://book.test/source", keyword = "关键词")
            assertEquals(2, outcome.debugLogs.size)
            assertTrue(outcome.debugLogs.any { it.contains("搜索成功") })
            assertEquals(1000, outcome.closeCode)
            assertFalse(outcome.truncated)

            val payload = outcome.toPayload()
            assertEquals("legacy_debug_only", payload["status"])
            assertEquals("unverifiable", payload["machineVerdict"])
            assertEquals("reader_web_legacy_debug", payload["evidenceScope"])
            // 任何字段都不得声称通过
            val text = payload.toString()
            assertFalse("不得输出 PASSED 类结论", text.contains("PASSED"))
            assertFalse(text.contains("\"passed\""))
            assertTrue((payload["evidenceNote"] as String).contains("不代表"))
        }
    }

    @Test fun close1000WithoutAnyLogsIsStillUnverifiableNotSuccess() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher(onMessage = { socket, _ ->
                socket.close(1000, "")
                true
            })
            server.start()
            val outcome = client(originFor(server)).debug("https://book.test/source", "")
            assertTrue(outcome.debugLogs.isEmpty())
            assertEquals("unverifiable", outcome.toPayload()["machineVerdict"])
        }
    }

    @Test fun busyChannelIsReportedAsBusyWhenOnlyRefusalLogged() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher(onMessage = { socket, _ ->
                socket.send("已有调试任务正在运行，请稍后")
                socket.close(1000, "")
                true
            })
            server.start()
            val error = runCatching { client(originFor(server)).debug("https://book.test/source", "") }
                .exceptionOrNull()
            assertTrue(error is ReaderWebLegacyDebugClient.LegacyDebugException)
            assertEquals("SERVICE_BUSY", (error as ReaderWebLegacyDebugClient.LegacyDebugException).code)
            assertTrue(error.debugLogs.isNotEmpty())
        }
    }

    @Test fun channelClose1013IsBusy() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher(onMessage = { socket, _ ->
                socket.close(1013, "try later")
                true
            })
            server.start()
            val error = runCatching { client(originFor(server)).debug("https://a.test", "") }
                .exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("SERVICE_BUSY", error.code)
        }
    }

    @Test fun missingSourceIsDistinguishedFromBusy() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher(onMessage = { socket, _ ->
                socket.send("书源不存在：请先保存该书源")
                socket.close(1000, "")
                true
            })
            server.start()
            val error = runCatching { client(originFor(server)).debug("https://nope.test", "") }
                .exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("SOURCE_NOT_FOUND", error.code)
        }
    }

    @Test fun normalSearchLogsMentioningWordsAreNotMistakenForRefusal() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher(onMessage = { socket, _ ->
                // 正常调试日志里出现「请稍后」「未找到」等词：不应被判成忙碌/书源不存在
                socket.send("▷ 请求 https://book.test/search")
                socket.send("× 未找到书源内容，稍后重试")
                socket.send("✓ 第 2 次尝试成功")
                socket.close(1000, "")
                true
            })
            server.start()
            val outcome = client(originFor(server)).debug("https://book.test/source", "")
            assertEquals(3, outcome.debugLogs.size)
        }
    }

    // ---- HTTP 层拒绝 ----

    @Test fun http403MapsToTokenRequiredOrInvalid() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(403)
            }
            server.start()
            val noToken = runCatching { client(originFor(server)).debug("https://a.test", "") }
                .exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("TOKEN_REQUIRED", noToken.code)

            val badToken = runCatching {
                client(originFor(server), token = "bad-token").debug("https://a.test", "")
            }.exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("TOKEN_INVALID", badToken.code)
            assertFalse(badToken.message.orEmpty().contains("bad-token"))
        }
    }

    @Test fun missingDebugRouteIsNotReportedAsOffline() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(404)
            }
            server.start()
            val error = runCatching { client(originFor(server)).debug("https://a.test", "") }
                .exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("ENDPOINT_MISSING", error.code)
        }
    }

    @Test fun serviceOfflineIsUnreachable() = kotlinx.coroutines.test.runTest {
        // 占一个端口再关掉，得到必然拒绝连接的「端口 + 1」
        val closedPort = MockWebServer().use { it.start(); it.port }
        val origin = "http://127.0.0.1:${closedPort - 1}"
        val error = runCatching { client(origin).debug("https://a.test", "") }.exceptionOrNull()
        assertTrue(error is ReaderWebLegacyDebugClient.LegacyDebugException)
        val debugError = error as ReaderWebLegacyDebugClient.LegacyDebugException
        assertTrue("code=${debugError.code}", debugError.code in setOf("UNREACHABLE", "TIMEOUT"))
        assertFalse(debugError.message.orEmpty().contains("127.0.0.1"))
        assertFalse(debugError.message.orEmpty().contains("://"))
    }

    // ---- 恶意重定向 ----

    @Test fun redirectToAnotherHostIsRefusedWithoutFollowing() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { victim ->
            MockWebServer().use { server ->
                victim.start()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        // 若被跟随，就会带着令牌去访问另一台主机
                        MockResponse().setResponseCode(302)
                            .setHeader("Location", "http://${victim.hostName}:${victim.port}/collect")
                }
                server.start()

                val error = runCatching {
                    client(originFor(server), token = "secret-token").debug("https://a.test", "")
                }.exceptionOrNull()

                assertTrue(error is ReaderWebLegacyDebugClient.LegacyDebugException)
                val debugError = error as ReaderWebLegacyDebugClient.LegacyDebugException
                assertEquals("REDIRECT_BLOCKED", debugError.code)
                assertEquals("重定向不得产生第二次请求", 1, server.requestCount)
                assertEquals("不得访问 Location 指向的主机", 0, victim.requestCount)
                assertFalse(debugError.message.orEmpty().contains("secret-token"))
                assertFalse(debugError.message.orEmpty().contains(victim.hostName))
                assertFalse(debugError.message.orEmpty().contains("collect"))
            }
        }
    }

    // ---- 预算 ----

    @Test fun idleChannelTimesOutAndKeepsPartialLogs() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher(onMessage = { socket, _ ->
                socket.send("▷ 开始调试")
                // 之后不再有任何输出，也不关闭
                true
            })
            server.start()
            val started = System.nanoTime()
            val error = runCatching {
                client(originFor(server)).debug(
                    sourceUrl = "https://a.test",
                    keyword = "",
                    totalTimeoutMs = 5_000,
                    handshakeTimeoutMs = 2_000,
                    idleTimeoutMs = 300,
                )
            }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(error is ReaderWebLegacyDebugClient.LegacyDebugException)
            val timeout = error as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("TIMEOUT", timeout.code)
            assertTrue("应远早于总超时结束（实际 ${elapsedMs}ms）", elapsedMs < 4_000)
            assertTrue("应带回已收到的日志", timeout.debugLogs.contains("▷ 开始调试"))
        }
    }

    @Test fun stalledHandshakeTimesOutBeforeTotalBudget() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            // 只接受连接、不完成升级
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            server.start()
            val started = System.nanoTime()
            val error = runCatching {
                client(originFor(server)).debug(
                    sourceUrl = "https://a.test",
                    keyword = "",
                    totalTimeoutMs = 10_000,
                    handshakeTimeoutMs = 400,
                )
            }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(error is ReaderWebLegacyDebugClient.LegacyDebugException)
            val timeout = error as ReaderWebLegacyDebugClient.LegacyDebugException
            assertTrue("code=${timeout.code}", timeout.code in setOf("HANDSHAKE_TIMEOUT", "TIMEOUT"))
            assertTrue("握手阶段就应结束（实际 ${elapsedMs}ms）", elapsedMs < 6_000)
        }
    }

    @Test fun totalBudgetCapsAGreedyChannel() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher { socket, _ ->
                var sent = 0
                while (sent < 10_000) {
                    if (!socket.send("第 ${++sent} 行")) break
                }
                true
            }
            server.start()
            val started = System.nanoTime()
            val outcome = runCatching {
                client(originFor(server)).debug(
                    sourceUrl = "https://a.test",
                    keyword = "",
                    totalTimeoutMs = 1_500,
                )
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            // 要么被总预算判超时，要么先被帧/字节上限截断；两种情况都必须在有限时间内收口
            assertTrue("总预算必须生效（实际 ${elapsedMs}ms）", elapsedMs < 6_000)
            val result = outcome.getOrNull()
            if (result == null) {
                assertTrue(outcome.exceptionOrNull() is ReaderWebLegacyDebugClient.LegacyDebugException)
            } else {
                assertTrue("未超时就必须是截断收口", result.truncated)
            }
        }
    }

    @Test fun frameLimitTruncatesInsteadOfUnboundedGrowth() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher { socket, _ ->
                repeat(50) { socket.send("日志行 $it " + "x".repeat(64)) }
                socket.close(1000, "")
                true
            }
            server.start()
            val outcome = client(originFor(server)).debug(
                sourceUrl = "https://a.test",
                keyword = "",
                maxFrames = 5,
            )
            assertTrue("达到帧上限必须标记截断", outcome.truncated)
            assertTrue("日志不超过上限", outcome.debugLogs.size <= 5)
            assertTrue(outcome.toPayload()["message"].toString().contains("截断"))
        }
    }

    @Test fun oversizedSingleLineIsClipped() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher { socket, _ ->
                socket.send("A".repeat(5_000))
                socket.close(1000, "")
                true
            }
            server.start()
            val outcome = client(originFor(server)).debug(
                sourceUrl = "https://a.test",
                keyword = "",
                maxLineChars = 256,
            )
            assertTrue(outcome.truncated)
            assertTrue(outcome.debugLogs.single().length <= 300)
            assertTrue(outcome.debugLogs.single().contains("已截断"))
        }
    }

    // ---- 脱敏 ----

    @Test fun logsAndCloseReasonNeverExposeTokenSubprotocolOrOrigin() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val hostPort = "${server.hostName}:${server.port}"
            server.dispatcher = webSocketDispatcher(onMessage = { socket, _ ->
                socket.send("连接 ws://$hostPort/bookSourceDebug 使用令牌 secret-token")
                socket.send("子协议 legado.token.c2VjcmV0LXRva2Vu")
                socket.close(1000, "ws://$hostPort closed")
                true
            })
            server.start()

            val outcome = client(originFor(server), token = "secret-token")
                .debug("https://a.test", "")
            val all = outcome.debugLogs.joinToString("\n") + outcome.closeReason
            assertFalse(all.contains("secret-token"))
            assertFalse(all.contains("c2VjcmV0LXRva2Vu"))
            assertFalse(all.contains(hostPort))
            assertTrue(outcome.debugLogs.isNotEmpty())
        }
    }

    @Test fun failureMessagesAreRedacted() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(503)
            }
            server.start()
            val error = runCatching {
                client(originFor(server), token = "secret-token").debug("https://a.test", "")
            }.exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("SERVICE_BUSY", error.code)
            val message = error.message.orEmpty()
            assertFalse(message.contains("secret-token"))
            assertFalse(message.contains("127.0.0.1"))
            assertFalse(message.contains(server.hostName))
        }
    }

    // ---- 取消与前置校验 ----

    @Test fun cancellationEndsPromptlyAndClosesSocket() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val upgraded = CountDownLatch(1)
            val tornDown = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            upgraded.countDown()
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            tornDown.countDown()
                            runCatching { webSocket.close(code, "") }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                            tornDown.countDown()
                        }
                    })
            }
            server.start()

            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            val job = scope.launch {
                runCatching { client(originFor(server)).debug("https://a.test", "") }
            }
            assertTrue("应完成握手", upgraded.await(5, TimeUnit.SECONDS))
            val started = System.nanoTime()
            job.cancel()
            job.join()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("取消必须立即结束（实际 ${elapsedMs}ms）", elapsedMs < 3_000)
            // 取消后 finally 立即关闭/取消 WebSocket：服务端必须观察到连接被收尾，不能一直挂着
            assertTrue("连接应被收尾", tornDown.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun notConfiguredAndInvalidArgumentsFailBeforeAnyNetwork() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val notConfigured = runCatching { client(null).debug("https://a.test", "") }
                .exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("NOT_CONFIGURED", notConfigured.code)

            val empty = runCatching { client(originFor(server)).debug("", "") }
                .exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("INVALID_ARGUMENT", empty.code)

            val badScheme = runCatching { client(originFor(server)).debug("file:///etc/passwd", "") }
                .exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("INVALID_ARGUMENT", badScheme.code)

            val newline = runCatching {
                client(originFor(server)).debug("https://a.test\nX-Evil: 1", "")
            }.exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("INVALID_ARGUMENT", newline.code)

            assertEquals("不得发出任何请求", 0, server.requestCount)
        }
    }

    @Test fun portOverflowIsRejectedBeforeAnyNetwork() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val error = runCatching { client("http://127.0.0.1:65535").debug("https://a.test", "") }
                .exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            assertEquals("PORT_OVERFLOW", error.code)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun errorPayloadKeepsLegacySemantics() {
        val payload = ReaderWebLegacyDebugClient.Outcome.errorPayload("TOKEN_REQUIRED", "请填写令牌")
        assertEquals("legacy_debug_only", payload["status"])
        assertEquals("unverifiable", payload["machineVerdict"])
        assertEquals("reader_web_legacy_debug", payload["evidenceScope"])
        assertEquals("TOKEN_REQUIRED", payload["errorCode"])
    }

    // ---- helpers ----

    /**
     * 客户端固定连 origin 端口 + 1；把配置 origin 写成 MockWebServer 的端口 - 1，
     * 派生后正好落到 server.port，从而完整验证「+1」规则而不是绕过它。
     */
    private fun originFor(server: MockWebServer): String =
        "http://${server.hostName}:${server.port - 1}"

    private fun client(
        baseUrl: String?,
        token: String = "",
        timeoutSec: Long = 5,
    ): ReaderWebLegacyDebugClient = ReaderWebLegacyDebugClient(
        configProvider = { baseUrl?.let { ReaderWebConfigStore.Config(it, token) } },
        clientFactory = {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
        },
    )

    /**
     * 升级为 WebSocket 后把客户端首帧交给 [onMessage]；返回 true 表示服务端继续保持连接。
     */
    private fun webSocketDispatcher(
        onMessage: (WebSocket, String) -> Boolean,
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    onMessage(webSocket, text)
                }
            })
    }
}
