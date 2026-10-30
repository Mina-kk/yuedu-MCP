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

    @Test fun logOffsetParserAndHeadIncompleteAreIndependentOfInlineTail() {
        assertEquals(1L, ReaderWebLegacyDebugClient.parseLogOffsetMs("[00:00.001] ⇒开始搜索关键字:玄鉴仙族"))
        assertEquals(1653L, ReaderWebLegacyDebugClient.parseLogOffsetMs("[00:01.653] └http://example.test/cover.jpg"))
        assertEquals(2424L, ReaderWebLegacyDebugClient.parseLogOffsetMs("[00:02.424] ┌获取封面链接"))
        assertNull(ReaderWebLegacyDebugClient.parseLogOffsetMs("︾开始搜索关键字 我的"))
        assertFalse(
            "完整原记录从 0ms 开始不算缺开头",
            ReaderWebLegacyDebugClient.logsHeadIncomplete(
                listOf("[00:00.001] ⇒开始搜索关键字:玄鉴仙族", "[00:01.653] └cover"),
            ),
        )
        assertTrue(
            "首行已过 1s 且没有搜索起始行，才标原记录缺开头",
            ReaderWebLegacyDebugClient.logsHeadIncomplete(
                listOf("[00:01.653] └http://example.test/cover.jpg", "[00:02.424] ┌获取封面链接"),
            ),
        )
        val payload = ReaderWebLegacyDebugClient.Outcome(
            sourceUrl = "https://book.test/source",
            keyword = "我的",
            debugLogs = listOf("[00:00.001] ⇒开始搜索关键字:玄鉴仙族"),
            frames = 1,
            bytes = 10,
            truncated = false,
            closeCode = 1000,
            closeReason = "",
            elapsedMs = 12,
            completionSignal = true,
        ).toPayload()
        assertEquals(false, payload["truncated"])
        assertEquals(false, payload["debugLogsHeadIncomplete"])
        assertEquals(1L, payload["debugLogsFirstOffsetMs"])
    }

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
            assertEquals(ReaderWebLegacyDebugClient.TIMEOUT_KIND_IDLE, timeout.observations["timeoutKind"])
            assertTrue("应远早于总超时结束（实际 ${elapsedMs}ms）", elapsedMs < 4_000)
            assertTrue("应带回已收到的日志", timeout.debugLogs.contains("▷ 开始调试"))
            assertTrue((timeout.observations["framesReceived"] as Int) >= 1)
            assertTrue(
                "lastFrameAgoMs=${timeout.observations["lastFrameAgoMs"]}",
                (timeout.observations["lastFrameAgoMs"] as Long) >= 250,
            )
            val payload = timeout.toErrorPayload()
            assertEquals("legacy_debug_only", payload["status"])
            assertEquals("unverifiable", payload["machineVerdict"])
            assertEquals("idle", payload["timeoutKind"])
        }
    }

    @Test fun silentOpenChannelHitsTotalTimeoutNotIdle() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = webSocketDispatcher { _, _ ->
                // 握手后不发帧也不关闭：模拟旧版通道稳定无输出
                true
            }
            server.start()
            val started = System.nanoTime()
            val error = runCatching {
                client(originFor(server)).debug(
                    sourceUrl = "https://a.test",
                    keyword = "",
                    totalTimeoutMs = 900,
                    handshakeTimeoutMs = 400,
                    idleTimeoutMs = 200,
                )
            }.exceptionOrNull() as ReaderWebLegacyDebugClient.LegacyDebugException
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals("TIMEOUT", error.code)
            assertEquals(ReaderWebLegacyDebugClient.TIMEOUT_KIND_TOTAL, error.observations["timeoutKind"])
            assertEquals(0, error.observations["framesReceived"])
            assertNull(error.observations["lastFrameAgoMs"])
            assertTrue("无帧通道不得被空闲超时提前误断（实际 ${elapsedMs}ms）", elapsedMs >= 600)
            assertTrue("总超时应收口（实际 ${elapsedMs}ms）", elapsedMs < 4_000)
            val payload = error.toErrorPayload()
            assertEquals("legacy_debug_only", payload["status"])
            assertEquals("total", payload["timeoutKind"])
            assertEquals(0, payload["framesReceived"])
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

    @Test fun idleTimeoutDoesNotFireOnSilentChannel() {
        assertFalse(ReaderWebLegacyDebugClient.idleTimedOut(0, null, 15_000))
        assertFalse(ReaderWebLegacyDebugClient.idleTimedOut(0, 60_000, 15_000))
        assertFalse(ReaderWebLegacyDebugClient.idleTimedOut(1, null, 15_000))
        assertFalse(ReaderWebLegacyDebugClient.idleTimedOut(1, 14_999, 15_000))
        assertTrue(ReaderWebLegacyDebugClient.idleTimedOut(1, 15_000, 15_000))
        assertTrue(ReaderWebLegacyDebugClient.idleTimedOut(3, 20_000, 15_000))
    }

    @Test fun totalTimeoutClampKeepsTwoHundredFloorAndThirtySecondDefaultAndRaisesMaxToOneEighty() {
        assertEquals(200L, ReaderWebLegacyDebugClient.TOTAL_TIMEOUT_MIN_MS)
        assertEquals(30_000L, ReaderWebLegacyDebugClient.DEFAULT_TOTAL_TIMEOUT_MS)
        assertEquals(180_000L, ReaderWebLegacyDebugClient.TOTAL_TIMEOUT_MAX_MS)
        assertEquals(200L, ReaderWebLegacyDebugClient.clampTotalTimeoutMs(0))
        assertEquals(200L, ReaderWebLegacyDebugClient.clampTotalTimeoutMs(199))
        assertEquals(200L, ReaderWebLegacyDebugClient.clampTotalTimeoutMs(200))
        assertEquals(30_000L, ReaderWebLegacyDebugClient.clampTotalTimeoutMs(30_000))
        assertEquals(120_000L, ReaderWebLegacyDebugClient.clampTotalTimeoutMs(120_000))
        assertEquals(180_000L, ReaderWebLegacyDebugClient.clampTotalTimeoutMs(180_000))
        assertEquals(180_000L, ReaderWebLegacyDebugClient.clampTotalTimeoutMs(180_001))
        assertEquals(180_000L, ReaderWebLegacyDebugClient.clampTotalTimeoutMs(999_999))
    }

    @Test fun oneEightySecondTotalTimeoutReachesWebSocketInsteadOfBeingCappedAtOneTwenty() =
        kotlinx.coroutines.test.runTest {
            MockWebServer().use { server ->
                server.dispatcher = webSocketDispatcher(onMessage = { socket, _ ->
                    socket.send("调试完成")
                    socket.close(1000, "")
                    true
                })
                server.start()
                val outcome = client(originFor(server)).debug(
                    sourceUrl = "https://a.test",
                    keyword = "",
                    totalTimeoutMs = 180_000,
                )
                assertEquals(1000, outcome.closeCode)
                assertTrue(outcome.completionSignal)
                assertEquals("/bookSourceDebug", server.takeRequest().path)
            }
        }

    @Test fun timeoutErrorPayloadCarriesKindAndTimings() {
        val idle = ReaderWebLegacyDebugClient.timeoutObservations("idle", 1_800, 1_500, 2)
        val total = ReaderWebLegacyDebugClient.timeoutObservations("total", 30_000, null, 0)
        assertEquals("idle", idle["timeoutKind"])
        assertEquals(1_800L, idle["elapsedMs"])
        assertEquals(1_500L, idle["lastFrameAgoMs"])
        assertEquals(2, idle["framesReceived"])
        assertEquals("total", total["timeoutKind"])
        assertEquals(0, total["framesReceived"])
        assertNull(total["lastFrameAgoMs"])

        val payload = ReaderWebLegacyDebugClient.Outcome.errorPayload(
            "TIMEOUT",
            "空闲超时",
            listOf("▷ 开始调试"),
            idle,
        )
        assertEquals("legacy_debug_only", payload["status"])
        assertEquals("unverifiable", payload["machineVerdict"])
        assertEquals("reader_web_legacy_debug", payload["evidenceScope"])
        assertEquals("TIMEOUT", payload["errorCode"])
        assertEquals("idle", payload["timeoutKind"])
        assertEquals(1_800L, payload["elapsedMs"])
        assertEquals(1_500L, payload["lastFrameAgoMs"])
        assertEquals(2, payload["framesReceived"])
        assertEquals(listOf("▷ 开始调试"), payload["debugLogs"])
        assertFalse(payload.toString().contains("PASSED"))
    }

    @Test fun connectionLostPayloadCarriesCloseAndLostKind() {
        val noClose = ReaderWebLegacyDebugClient.lostObservations(
            ReaderWebLegacyDebugClient.LOST_KIND_NO_CLOSE_FRAME, 2_100, 800, 1, null, "",
        )
        assertEquals("no_close_frame", noClose["lostKind"])
        assertNull(noClose["closeCode"])
        assertEquals("", noClose["closeReason"])
        assertEquals(2_100L, noClose["elapsedMs"])
        assertEquals(800L, noClose["lastFrameAgoMs"])
        assertEquals(1, noClose["framesReceived"])

        val io = ReaderWebLegacyDebugClient.lostObservations(
            ReaderWebLegacyDebugClient.LOST_KIND_IO_FAILURE, 500, 100, 2, 1006, "abnormal",
        )
        assertEquals("io_failure", io["lostKind"])
        assertEquals(1006, io["closeCode"])
        assertEquals("abnormal", io["closeReason"])

        val payload = ReaderWebLegacyDebugClient.LegacyDebugException(
            "CONNECTION_LOST",
            "阅读调试连接已断开，但没有收到关闭帧",
            listOf("▷ 开始"),
            noClose,
        ).toErrorPayload()
        assertEquals("legacy_debug_only", payload["status"])
        assertEquals("CONNECTION_LOST", payload["errorCode"])
        assertEquals("no_close_frame", payload["lostKind"])
        assertNull(payload["closeCode"])
        assertEquals(1, payload["framesReceived"])
        assertFalse(payload.toString().contains("PASSED"))
    }

    @Test fun observationsCannotOverwriteLegacyStatus() {
        val payload = ReaderWebLegacyDebugClient.Outcome.errorPayload(
            "TIMEOUT",
            "超时",
            emptyList(),
            mapOf("status" to "PASSED", "machineVerdict" to "PASSED", "timeoutKind" to "idle"),
        )
        assertEquals("legacy_debug_only", payload["status"])
        assertEquals("unverifiable", payload["machineVerdict"])
        assertEquals("idle", payload["timeoutKind"])
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
