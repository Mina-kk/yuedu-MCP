package com.mina.legadostudio.reader

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 阅读 Web 的**生产传输**回归：这里一律走 `ReaderWebClient(configProvider = …)` 的默认
 * `clientFactory`（= `ReaderWebClient.defaultClient()`），不注入另一套 testClient——
 * 否则「生产 client 的旗标/池/Brotli/UA」根本不会被测到。
 *
 * 覆盖现场报告（同秒 `java.ajax` / `HttpFetcher` / `reader_web_page` 成功，而本通道偶发
 * `UNREACHABLE`）可能涉及的传输差异：
 * - 浏览器 UA / `Accept-Language` / Brotli 协商（前置反代对非浏览器 UA 直接断连）；
 * - 零 idle 连接池 + `Connection: close`（绝不复用对端已关闭的陈旧连接）；
 * - GET 在 RST/EOF 上最多一次**新连接**重试，POST 绝不重放；
 * - HTTP/1.0 或 close-delimited（无 Content-Length）响应；
 * - 随机端口贯穿，不写死端口、不扫描/不回落；
 * - 脱敏诊断：connect/headers/body/parse 分相位，Socket RESET、EOF、协议、DNS、超时分类。
 *
 * 全部指向 127.0.0.1 上临时监听的 MockWebServer / 裸 ServerSocket，不触碰用户真实地址。
 */
class ReaderWebTransportTest {

    // ---- 生产 client（唯一注入点就是配置；不替换工厂） ----

    private fun productionClient(baseUrl: String?, token: String = ""): ReaderWebClient = ReaderWebClient(
        configProvider = { baseUrl?.let { ReaderWebConfigStore.Config(it, token) } },
    )

    private fun okJson(data: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody("""{"isSuccess":true,"errorMsg":"","data":$data}""")

    private fun html(body: String = "<html><body>reader web</body></html>"): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    private fun source(url: String): JsonObject = JsonParser.parseString(
        """{"bookSourceName":"测试源","bookSourceUrl":"$url","bookSourceType":0,"searchUrl":"https://example.test/search?q={%s}"}"""
    ).asJsonObject

    // ---- 裸 socket 服务器：模拟 MockWebServer 表达不了的握手/半开/关闭语义 ----

    /**
     * 极简 HTTP 服务器：按连接序号交给 [handler]，由用例决定「先读请求再重置」
     * 「不发响应头就关」「声明长度却少写」等现场形态。端口由系统随机分配。
     */
    private inner class RawHttpServer(
        private val handler: (index: Int, socket: Socket, request: String) -> Unit,
    ) : Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        val accepted = AtomicInteger()

        val port: Int get() = server.localPort
        val origin: String get() = "http://127.0.0.1:$port"

        private val acceptThread = Thread {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                val index = accepted.incrementAndGet()
                sockets.add(socket)
                Thread {
                    runCatching {
                        socket.soTimeout = 5_000
                        handler(index, socket, readRequest(socket.getInputStream()))
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }

        init {
            acceptThread.start()
        }

        override fun close() {
            runCatching { server.close() }
            synchronized(sockets) {
                sockets.forEach { runCatching { it.close() } }
                sockets.clear()
            }
        }
    }

    private fun readRequest(input: InputStream): String {
        val head = StringBuilder()
        var state = 0
        while (state < 4) {
            val value = input.read()
            if (value < 0) return head.toString()
            head.append(value.toChar())
            state = when {
                (state == 0 || state == 2) && value == '\r'.code -> state + 1
                (state == 1 || state == 3) && value == '\n'.code -> state + 1
                value == '\r'.code -> 1
                else -> 0
            }
        }
        val headers = head.toString()
        val length = Regex("(?i)content-length:\\s*(\\d+)")
            .find(headers)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return headers + String(body, 0, read, Charsets.UTF_8)
    }

    private fun respond(socket: Socket, body: String, closeDelimited: Boolean = false) {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            if (closeDelimited) {
                "Connection: close\r\n\r\n$body"
            } else {
                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
            }
        socket.getOutputStream().write(raw.toByteArray())
        socket.getOutputStream().flush()
        if (!closeDelimited) socket.close() else runCatching { socket.close() }
    }

    private fun reset(socket: Socket) {
        runCatching { socket.setSoLinger(true, 0) }
        runCatching { socket.close() }
    }

    // ---- 1) 生产 client 的请求头：浏览器 UA / Accept-Language / Brotli / 一次性连接 ----

    @Test fun productionClientSpeaksBrowserProfileAndClosesEachConnection() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(okJson("false"))
            server.enqueue(okJson("false"))
            server.start()
            val client = productionClient(server.url("/").toString())
            client.probe()
            client.probe()
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!

            first.getHeader("User-Agent")!!.let { ua ->
                assertTrue("必须是浏览器 UA（前置反代对非浏览器 UA 常直接断连）：$ua", ua.contains("Mozilla/5.0"))
                assertTrue("UA 必须是浏览器内核形态：$ua", ua.contains("Chrome") || ua.contains("AppleWebKit"))
                assertEquals(
                    "阅读 Web 与服务书源抓取必须用同一个浏览器 UA（避免两份字符串漂移）",
                    com.mina.legadostudio.network.HttpFetcher.DEFAULT_UA,
                    ua,
                )
            }
            assertEquals("zh-CN,zh;q=0.9", first.getHeader("Accept-Language"))
            val acceptEncoding = first.getHeader("Accept-Encoding").orEmpty()
            assertTrue("必须协商 br（装了 BrotliInterceptor）：$acceptEncoding", acceptEncoding.contains("br"))
            assertTrue(
                "必须显式要求关闭连接（零 idle 池）：${first.getHeader("Connection")}",
                first.getHeader("Connection").orEmpty().equals("close", ignoreCase = true),
            )
            assertNull("不得发送 Cookie", first.getHeader("Cookie"))
            assertNull("公开路由不得带令牌", first.getHeader("X-Legado-Token"))
            // 第二条请求仍是同一条已保存 origin（随机端口原样贯穿），且是新连接
            assertEquals(server.port, second.requestUrl!!.port)
            assertTrue(second.headers.toString().isNotEmpty())
        }
    }

    @Test fun productionClientIsRejectedOnlyWhenBrowserUaIsMissing() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val ua = request.getHeader("User-Agent").orEmpty()
                    if (!ua.contains("Mozilla/5.0") || !ua.contains("Chrome")) {
                        // 现场「非浏览器 UA 被前置反代直接掐断」的等价形态
                        return MockResponse().setResponseCode(403)
                    }
                    return if (request.path!!.startsWith("/getJsSourceApiTokenRequired")) okJson("false") else okJson("[]")
                }
            }
            server.start()
            // 用户这里要的是「浏览器 UA 才通」：只要生产 client 带对 UA，就必须通过
            val probe = productionClient(server.url("/").toString()).probe()
            assertFalse(probe.tokenRequired)
        }
    }

    // ---- 2) HTTP/1.0 / close-delimited（无 Content-Length） ----
    @Test fun productionClientReadsCloseDelimitedBodyWithoutContentLength() = kotlinx.coroutines.test.runTest {
        RawHttpServer { _, socket, request ->
            assertTrue(request.startsWith("GET /getJsSourceApiTokenRequired"))
            // HTTP/1.1 但没有 Content-Length：正文以连接关闭为界（现场旧版阅读/反代常见）
            respond(socket, """{"isSuccess":true,"errorMsg":"","data":true}""", closeDelimited = true)
        }.use { raw ->
            val probe = productionClient(raw.origin).probe()
            assertTrue("close-delimited 正文必须能读完，不能误报 EOF", probe.tokenRequired)
            assertEquals(1, raw.accepted.get())
        }
    }

    @Test fun productionClientAcceptsHttp10Response() = kotlinx.coroutines.test.runTest {
        RawHttpServer { _, socket, _ ->
            val raw = "HTTP/1.0 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n\r\n" +
                """{"isSuccess":true,"errorMsg":"","data":true}"""
            socket.getOutputStream().write(raw.toByteArray())
            socket.getOutputStream().flush()
            socket.close()
        }.use { raw ->
            val probe = productionClient(raw.origin).probe()
            assertTrue("HTTP/1.0 响应必须按无长度、close 结束正常读完", probe.tokenRequired)
        }
    }

    // ---- 3) GET 安全重试：RST/EOF 最多一次新连接 ----

    @Test fun getRetriesExactlyOnceOnResetBeforeHeaders() = kotlinx.coroutines.test.runTest {
        RawHttpServer { index, socket, _ ->
            if (index == 1) {
                // 读到请求后立刻 RST：模拟「对端/反代在响应头前重置连接」
                reset(socket)
            } else {
                respond(socket, """{"isSuccess":true,"errorMsg":"","data":false}""")
            }
        }.use { raw ->
            val probe = productionClient(raw.origin).probe()
            assertFalse(probe.tokenRequired)
            assertEquals("必须换新连接重试一次（且只一次）", 2, raw.accepted.get())
            assertEquals("诊断必须记录实际发出 2 次", 2, probe.diagnostic?.attempts)
            assertEquals(
                "重试成功后 reusedConnection 必须是 false（换了新连接）",
                false,
                probe.diagnostic?.reusedConnection,
            )
            assertEquals(ReaderWebRequestDiagnostic.Phase.NONE, probe.diagnostic?.phase)
        }
    }

    @Test fun getRetriesOnceOnEofBeforeHeaders() = kotlinx.coroutines.test.runTest {
        RawHttpServer { index, socket, _ ->
            if (index == 1) {
                // 不发响应头直接关：客户端读到 EOF
                socket.close()
            } else {
                respond(socket, """{"isSuccess":true,"errorMsg":"","data":false}""")
            }
        }.use { raw ->
            val probe = productionClient(raw.origin).probe()
            assertFalse(probe.tokenRequired)
            assertEquals(2, raw.accepted.get())
            assertEquals(2, probe.diagnostic?.attempts)
        }
    }

    @Test fun getGivesUpAfterOneRetryAndReportsCategory() = kotlinx.coroutines.test.runTest {
        RawHttpServer { _, socket, _ -> reset(socket) }.use { raw ->
            val error = runCatching { productionClient(raw.origin).probe() }.exceptionOrNull()
                as ReaderWebClient.ReaderWebException
            assertEquals("UNREACHABLE", error.code)
            assertEquals("第一次 + 最多一次重试", 2, raw.accepted.get())
            val diagnostic = error.diagnostic!!
            assertEquals(2, diagnostic.attempts)
            assertEquals(ReaderWebRequestDiagnostic.Phase.HEADERS, diagnostic.phase)
            assertTrue(
                "必须归类为 RESET/EOF 这类可恢复连接错误：${diagnostic.exceptionCategory}",
                diagnostic.exceptionCategory in setOf(
                    ReaderWebRequestDiagnostic.Category.RESET,
                    ReaderWebRequestDiagnostic.Category.EOF,
                ),
            )
            assertNull("没有拿到 HTTP 状态", diagnostic.httpStatus)
        }
    }

    // ---- 4) POST 绝不因为传输异常重放 ----

    @Test fun postIsNeverReplayedOnNetworkFailure() = kotlinx.coroutines.test.runTest {
        RawHttpServer { _, socket, request ->
            // 已经收到完整请求（含书源正文），然后不回响应直接断：客户端只知道「可能没写成」
            assertTrue(request.startsWith("POST /saveBookSource"))
            socket.close()
        }.use { raw ->
            val error = runCatching {
                productionClient(raw.origin).saveSource(source("https://book.test/source"))
            }.exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("UNREACHABLE", error.code)
            assertEquals("POST 只能到达服务端 1 次（绝不重放）", 1, raw.accepted.get())
            val diagnostic = error.diagnostic!!
            assertEquals(1, diagnostic.attempts)
            assertEquals("POST", diagnostic.method)
            assertEquals("/saveBookSource", diagnostic.path)
            assertEquals("save", diagnostic.stage)
            assertFalse(diagnostic.authRetry)
        }
    }

    @Test fun postIsNotReplayedOnResetEither() = kotlinx.coroutines.test.runTest {
        RawHttpServer { index, socket, _ -> reset(socket) }.use { raw ->
            val error = runCatching {
                productionClient(raw.origin).saveSource(source("https://book.test/source"))
            }.exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals(1, raw.accepted.get())
            assertEquals(1, error.diagnostic?.attempts)
        }
    }

    // ---- 5) 不复用陈旧 idle 连接 ----

    @Test fun productionClientNeverReusesAnIdleConnection() = kotlinx.coroutines.test.runTest {
        // 服务端故意**不**关闭 socket：如果客户端拿池里的旧连接复用，第二条请求会落到同一条
        // 已被对端认为空闲的连接上（现场的偶发 stale socket）。
        RawHttpServer { _, socket, _ ->
            respond(socket, """{"isSuccess":true,"errorMsg":"","data":false}""")
        }.use { raw ->
            val client = productionClient(raw.origin)
            client.probe()
            client.probe()
            assertEquals("每条请求必须新建 TCP 连接（零 idle 池 + Connection: close）", 2, raw.accepted.get())
        }
    }

    // ---- 6) 端口：只来自已保存配置，不写死、不扫描、不回落 ----

    @Test fun configuredRandomPortIsUsedVerbatimAndNoNeighbourIsScanned() = kotlinx.coroutines.test.runTest {
        val neighbour = RawHttpServer { _, socket, _ -> reset(socket) }
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val closedPort = closed.localPort
        closed.close()
        try {
            val error = runCatching { productionClient("http://127.0.0.1:$closedPort").probe() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("UNREACHABLE", error.code)
            assertEquals(
                ReaderWebRequestDiagnostic.Category.CONNECT,
                error.diagnostic?.exceptionCategory,
            )
            assertEquals(ReaderWebRequestDiagnostic.Phase.CONNECT, error.diagnostic?.phase)
            assertEquals("连接被拒不是可重试类别：只发一次", 1, error.diagnostic?.attempts)
            assertEquals("失败后不得去连任何邻近端口/默认端口", 0, neighbour.accepted.get())
        } finally {
            neighbour.close()
        }
    }

    @Test fun workingRandomPortIsUsedWithoutAnyDefaultPortFallback() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(okJson("false"))
            server.start()
            val probe = productionClient(server.url("/").toString()).probe()
            assertFalse(probe.tokenRequired)
            assertEquals(server.port, server.takeRequest().requestUrl!!.port)
            assertNotNull("随机端口也必须能正常拿到诊断", probe.diagnostic)
        }
    }

    // ---- 7) 诊断：分相位、分类别、绝不带 host/port/query/token ----

    @Test fun parseFailureIsIncompatibleWithParsePhaseNotNetworkError() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(html()) // 接口回 HTML 前端页
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val error = runCatching { productionClient(server.url("/").toString()).probe("https://a.test") }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            // 新版接口回 HTML → 形状不符；旧版也 404 → NOT_READER，但诊断必须保留「这不是网络错误」
            assertEquals("NOT_READER", error.code)
            assertNotNull(error.diagnostic)
            assertEquals(
                "形状不符是解析相位，不是网络失败",
                ReaderWebRequestDiagnostic.Phase.PARSE,
                error.diagnostic?.phase,
            )
            assertNull("形状不符没有异常类别", error.diagnostic?.exceptionCategory)
            assertEquals(200, error.diagnostic?.httpStatus)
        }
    }

    @Test fun shapeMismatchIsNotReportedAsEndpointMissing() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(html())
            server.start()
            val error = runCatching { productionClient(server.url("/").toString()).fetchSources(emptyList()) }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("INCOMPATIBLE", error.code)
            assertTrue("形状不符必须显式可区分", error.shapeMismatch)
            assertFalse("形状不符不是路由缺失", error.endpointMissing)
            assertEquals(ReaderWebRequestDiagnostic.Phase.PARSE, error.diagnostic?.phase)
            assertEquals(200, error.diagnostic?.httpStatus)
            assertEquals("GET", error.diagnostic?.method)
            assertEquals("/getBookSourcesForManagement", error.diagnostic?.path)
            assertEquals("sources", error.diagnostic?.stage)
        }
    }

    @Test fun bodyTruncationMidStreamIsReportedAsBodyPhaseWithStatus() = kotlinx.coroutines.test.runTest {
        RawHttpServer { _, socket, _ ->
            // 声明 100 字节，只写 10 字节就关：响应头已到、正文读不动
            val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n"
            socket.getOutputStream().write(head.toByteArray())
            socket.getOutputStream().write("""{"isSuc""".toByteArray())
            socket.getOutputStream().flush()
            socket.close()
        }.use { raw ->
            val error = runCatching { productionClient(raw.origin).fetchSources(emptyList()) }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("UNREACHABLE", error.code)
            assertEquals("状态码已知时必须回传", 200, error.statusCode)
            assertEquals(ReaderWebRequestDiagnostic.Phase.BODY, error.diagnostic?.phase)
            assertEquals(200, error.diagnostic?.httpStatus)
            assertTrue(
                "正文提前结束必须是 EOF/PROTOCOL：${error.diagnostic?.exceptionCategory}",
                error.diagnostic?.exceptionCategory in setOf(
                    ReaderWebRequestDiagnostic.Category.EOF,
                    ReaderWebRequestDiagnostic.Category.PROTOCOL,
                ),
            )
            assertFalse(
                "GET 正文阶段可在可恢复错误上重试一次，但诊断必须如实记录",
                (error.diagnostic?.attempts ?: 0) > 2,
            )
        }
    }

    @Test fun diagnosticPayloadHasNoHostPortQueryTokenOrRawUrl() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(500)
            }
            server.start()
            val token = "s3cret-token-value"
            val error = runCatching {
                productionClient(server.url("/").toString(), token).fetchSources(listOf("https://book.test/source"))
            }.exceptionOrNull() as ReaderWebClient.ReaderWebException
            val payload = error.diagnostic!!.toPayload()
            val text = payload.toString()
            assertFalse("不得带 host", text.contains(server.hostName))
            assertFalse("不得带端口", text.contains(server.port.toString()))
            assertFalse("不得带令牌", text.contains(token))
            assertFalse("不得带协议前缀", text.contains("http://"))
            assertFalse("不得带查询串", text.contains("book.test"))
            assertEquals(
                setOf(
                    "method", "path", "stage", "phase", "httpStatus",
                    "exceptionCategory", "attempts", "reusedConnection", "authRetry",
                ),
                payload.keys,
            )
            assertEquals("/getBookSourcesForManagement", payload["path"])
            assertEquals(500, payload["httpStatus"])
        }
    }

    // ---- 8) 旧契约回归（生产 client）：root HTML / 同源跳转 / 越域拒绝 / 令牌 / 4MB ----

    @Test fun rootHtmlIsPageEntryAndCrossOriginRedirectIsRefusedOnProductionClient() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            MockWebServer().use { victim ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        "/" -> html("<!DOCTYPE HTML><html><head><title>导航</title></head><body>ok</body></html>")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                server.start()
                victim.start()
                val page = productionClient(server.url("/").toString(), token = "secret").probeWebPage()
                assertTrue("任意 2xx HTML 都是页面入口（不看模板）", page.reachable)
                assertEquals(200, page.statusCode)
                assertEquals("html", page.contentKind)
                assertNotNull(page.diagnostic)
                assertEquals("GET", page.diagnostic?.method)
                assertEquals("/", page.diagnostic?.path)
                assertEquals("web_page", page.diagnostic?.stage)
                assertEquals(0, victim.requestCount)

                victim.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = html()
                }
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path == "/") {
                            MockResponse().setResponseCode(302).setHeader("Location", victim.url("/steal"))
                        } else {
                            MockResponse().setResponseCode(404)
                        }
                }
                val error = runCatching { productionClient(server.url("/").toString(), token = "secret").probeWebPage() }
                    .exceptionOrNull() as ReaderWebClient.ReaderWebException
                assertEquals("REDIRECT_BLOCKED", error.code)
                assertEquals("越域不得发出第二个请求", 0, victim.requestCount)
                assertEquals(302, error.diagnostic?.httpStatus)
                assertFalse(error.message.orEmpty().contains(victim.hostName))
            }
        }
    }

    @Test fun bearerChallengeRetryIsCountedAndTrackedOnProductionClient() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.getHeader("Authorization") == "Bearer good-token") {
                        okJson("false")
                    } else {
                        MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer")
                    }
            }
            server.start()
            val probe = productionClient(server.url("/").toString(), token = "good-token").probe()
            assertTrue("挑战后必须完成一次同源认证重发", probe.bearerAuth)
            assertEquals("首发 + 认证重发", 2, probe.diagnostic?.attempts)
            assertEquals(
                "诊断必须标出这是认证后的那次请求",
                true,
                probe.diagnostic?.authRetry,
            )
            assertNull("公开路由的首次请求不得预发 Authorization", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun fourMegabyteLimitStillRejectsOversizedResponseOnProductionClient() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val huge = """{"isSuccess":true,"errorMsg":"","data":[""" +
                (1..80_000).joinToString(",") { """{"bookSourceName":"源$it","bookSourceUrl":"https://s$it.test"}""" } + "]}"
            assertTrue("测试数据必须超过 4MB", huge.toByteArray().size > 4 * 1024 * 1024)
            server.enqueue(okJson(huge))
            server.start()
            val error = runCatching { productionClient(server.url("/").toString()).fetchSources(emptyList()) }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("RESPONSE_TOO_LARGE", error.code)
            assertEquals(ReaderWebRequestDiagnostic.Phase.BODY, error.diagnostic?.phase)
            assertEquals(200, error.diagnostic?.httpStatus)
        }
    }

    // ---- 9) 保存/回读阶段：诊断可消费，5xx 不是「路由缺失」 ----

    @Test fun saveAndReadbackStagesAreObservableAndFiveHundredIsNotEndpointMissing() = kotlinx.coroutines.test.runTest {
        val url = "https://book.test/source"
        MockWebServer().use { server ->
            var saveMode = "fail"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/saveBookSource") -> when (saveMode) {
                        "ok" -> okJson("null")
                        "missing" -> MockResponse().setResponseCode(404)
                        else -> MockResponse().setResponseCode(500)
                    }
                    request.path!!.startsWith("/getBookSource?") ->
                        if (saveMode == "readback500") {
                            MockResponse().setResponseCode(500)
                        } else {
                            okJson(source(url).toString())
                        }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val client = productionClient(server.url("/").toString())

            val saveError = runCatching { client.saveSource(source(url)) }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("写入 5xx 是服务端错误，不是「没有这个路由」", "HTTP_ERROR", saveError.code)
            assertFalse(saveError.endpointMissing)
            assertEquals("POST", saveError.diagnostic?.method)
            assertEquals("/saveBookSource", saveError.diagnostic?.path)
            assertEquals("save", saveError.diagnostic?.stage)
            assertEquals(500, saveError.diagnostic?.httpStatus)
            assertEquals(1, saveError.diagnostic?.attempts)

            saveMode = "missing"
            val missing = runCatching { client.saveSource(source(url)) }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("显式 404 才是路由缺失", "ENDPOINT_MISSING", missing.code)
            assertTrue(missing.endpointMissing)

            saveMode = "ok"
            val saved = client.saveSource(source(url))
            assertEquals("POST", saved.diagnostic?.method)
            assertEquals("/saveBookSource", saved.diagnostic?.path)
            assertEquals("save", saved.diagnostic?.stage)
            assertNull("写入成功没有异常类别", saved.diagnostic?.exceptionCategory)

            // 回读阶段必须显式出现在诊断里，且 5xx 不降级成 ENDPOINT_MISSING
            val lookup = client.fetchSource(url)
            assertTrue(lookup.present)
            assertEquals("lookup", lookup.diagnostic?.stage)
            assertEquals("/getBookSource", lookup.diagnostic?.path)

            saveMode = "readback500"
            val readback = runCatching { client.fetchSource(url, stage = ReaderWebClient.Stage.READBACK) }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("HTTP_ERROR", readback.code)
            assertFalse("回读 5xx 不得一刀切成路由缺失", readback.endpointMissing)
            assertEquals("readback", readback.diagnostic?.stage)
            assertEquals(500, readback.diagnostic?.httpStatus)

            // lookup 阶段的 5xx 仍沿用旧语义（旧版核实要靠它判断「没有该接口」）
            val lookup500 = runCatching { client.fetchSource(url, stage = ReaderWebClient.Stage.LOOKUP) }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("ENDPOINT_MISSING", lookup500.code)
        }
    }

    @Test fun fetchSourceOnlyAcceptsTheFixedStageEnum() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.start()
            listOf("save", "web_page", "", "LOOKUP").forEach { bogus ->
                val error = runCatching { productionClient(server.url("/").toString()).fetchSource("https://a.test", bogus) }
                    .exceptionOrNull()
                assertTrue("stage 只允许 lookup/readback，拒绝：$bogus", error is IllegalArgumentException)
            }
            assertEquals("非法 stage 不得发出任何请求", 0, server.requestCount)
        }
    }
}
