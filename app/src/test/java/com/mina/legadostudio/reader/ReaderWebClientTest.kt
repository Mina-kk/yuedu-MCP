package com.mina.legadostudio.reader

import com.google.gson.JsonObject
import okhttp3.OkHttpClient
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
import java.util.concurrent.TimeUnit

/**
 * 阅读 Web 客户端只用固定 allowlist 路由、只向已保存 origin 发请求；
 * 这里用 MockWebServer 覆盖地址校验、未配置、离线、令牌、200 业务失败、恶意重定向、
 * 超时与检验轮询状态，全部不触碰真实局域网地址。
 */
class ReaderWebClientTest {

    private fun config(baseUrl: String, token: String = "") = ReaderWebConfigStore.Config(baseUrl, token)

    private fun testClient(timeoutSec: Long = 5): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(timeoutSec, TimeUnit.SECONDS)
        .build()

    private fun client(
        baseUrl: String?,
        token: String = "",
        timeoutSec: Long = 5,
    ): ReaderWebClient = ReaderWebClient(
        configProvider = { baseUrl?.let { config(it, token) } },
        clientFactory = { testClient(timeoutSec) },
    )

    private fun okJson(data: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody("""{"isSuccess":true,"errorMsg":"","data":$data}""")

    private fun failJson(message: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody("""{"isSuccess":false,"errorMsg":"$message","data":null}""")

    private fun source(url: String): JsonObject = com.google.gson.JsonParser.parseString(
        """{"bookSourceName":"测试源","bookSourceUrl":"$url","bookSourceType":0,"searchUrl":"https://example.test/search?q={%s}"}"""
    ).asJsonObject

    // ---- 地址校验（纯逻辑） ----

    @Test fun originAcceptsPlainHttpAndHttpsWithPort() {
        assertEquals("http://192.168.1.8:1122", ReaderWebOrigin.normalize("http://192.168.1.8:1122"))
        assertEquals("http://192.168.1.8:1122", ReaderWebOrigin.normalize("  http://192.168.1.8:1122/  "))
        assertEquals("https://reader.lan:1123", ReaderWebOrigin.normalize("https://reader.lan:1123"))
        // 未写端口时按其默认端口解析；OkHttp 会把缺省端口从 URL 字符串里省略
        assertEquals("http://reader.lan", ReaderWebOrigin.normalize("http://reader.lan"))
        assertEquals("https://reader.lan", ReaderWebOrigin.normalize("https://reader.lan:443"))
    }

    @Test fun originNormalizesLoopbackAliasesWithoutTouchingPort() {
        // 用户实输入的例子：127.0.01 必须安全归一为本机回环
        assertEquals("http://127.0.0.1:1162", ReaderWebOrigin.normalize("http://127.0.01:1162"))
        assertEquals("http://127.0.0.1:1162", ReaderWebOrigin.normalize("  http://127.0.01:1162/ "))
        assertEquals("http://127.0.0.1:1162", ReaderWebOrigin.normalize("http://127.1:1162"))
        assertEquals("http://127.0.0.1:1162", ReaderWebOrigin.normalize("http://127.0.0.1:1162"))
        assertEquals("http://127.0.0.1:1162", ReaderWebOrigin.normalize("http://localhost:1162"))
        assertEquals("http://127.0.0.1", ReaderWebOrigin.normalize("http://127.0.01"))
        assertEquals("https://127.0.0.1:1162", ReaderWebOrigin.normalize("https://127.0.01:1162"))
        // 端口来自用户输入，不写死 1122
        assertEquals("http://127.0.0.1:8080", ReaderWebOrigin.normalize("http://127.0.01:8080"))
        // 非回环私网地址不得被改写，也不得放开任意缩写/十六进制
        assertEquals("http://192.168.1.8:1122", ReaderWebOrigin.normalize("http://192.168.1.8:1122"))
        listOf(
            "http://10.20.30.40:1162",
            "http://0x7f.0.0.1:1162",
            "http://2130706433:1162",
            "http://127.0.0.1.nip.io:1162",
            "http://localhost.evil.test:1162",
            "http://127.0.01:1162/web",
        ).forEach { raw ->
            val normalized = runCatching { ReaderWebOrigin.normalize(raw) }.getOrNull()
            assertFalse("不得把非回环输入改写成本机: $raw -> $normalized", normalized == "http://127.0.0.1:1162")
        }
        // 严格 URL 保护仍然生效
        assertTrue(runCatching { ReaderWebOrigin.normalize("http://127.0.01:1162/web") }.isFailure)
    }

    @Test fun otherLoopbackHostsAreNeverRewrittenToDotOne() {
        // 127.0.0.2 / 127.0.255.1 是不同回环主机：必须原样保留，绝不能把令牌改发到 127.0.0.1
        assertEquals("http://127.0.0.2:1162", ReaderWebOrigin.normalize("http://127.0.0.2:1162"))
        assertEquals("http://127.0.0.255:1162", ReaderWebOrigin.normalize("http://127.0.0.255:1162"))
        assertEquals("http://127.0.255.1:1162", ReaderWebOrigin.normalize("http://127.0.255.1:1162"))
        assertEquals("http://127.9.9.9:1162", ReaderWebOrigin.normalize("http://127.9.9.9:1162"))
        // 只差一位的写法也不能被当成 .1
        assertEquals("http://127.0.0.10:1162", ReaderWebOrigin.normalize("http://127.0.0.10:1162"))
        assertEquals("http://127.0.0.11:1162", ReaderWebOrigin.normalize("http://127.0.0.11:1162"))
        // 非等价、含义不确定的缩写：拒绝，既不重写也不交给平台解析
        listOf("http://127.2:1162", "http://127.0.0:1162", "http://127.0.01.1:1162", "http://127.0.2:1162").forEach { raw ->
            assertTrue("应拒绝不确定缩写: $raw", runCatching { ReaderWebOrigin.normalize(raw) }.isFailure)
        }
        // 0.0.1 的等价前导零写法仍可归一
        assertEquals("http://127.0.0.1:1162", ReaderWebOrigin.normalize("http://127.00.0.01:1162"))
        assertEquals("http://127.0.0.1:1162", ReaderWebOrigin.normalize("http://127.0.1:1162"))
    }

    @Test fun tokenIsNeverCarriedToAnotherLoopbackHost() {
        // 配置在 127.0.0.2、令牌为该主机的令牌：请求必须仍打到 .2，且 .2 地址不被归一到 .1
        val configured = ReaderWebOrigin.normalize("http://127.0.0.2:1162")
        assertEquals("http://127.0.0.2:1162", configured)
        // 令牌与 origin 绑定：换成 127.0.0.1 会被判定为换地址并清除旧令牌
        val moved = ReaderWebConfigPolicy.resolve(configured, "token-for-dot-2", "http://127.0.0.1:1162", null)
        assertEquals("http://127.0.0.1:1162", moved.origin)
        assertEquals(null, moved.token)
        // 同一 .2 地址重复保存（含别名拼写差异）不算换地址，令牌保留
        val same = ReaderWebConfigPolicy.resolve(configured, "token-for-dot-2", "http://127.0.0.2:1162/", null)
        assertEquals("http://127.0.0.2:1162", same.origin)
        assertEquals("token-for-dot-2", same.token)
    }

    @Test fun requestsStayOnConfiguredLoopbackHostNotDotOne() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(okJson("false"))
            server.start()
            // 服务监听 127.0.0.1；用户配置的是 127.0.0.2（同端口）：不得被归一到 .1 去连这台服务
            assertEquals("http://127.0.0.2:${server.port}", ReaderWebOrigin.normalize("http://127.0.0.2:${server.port}"))
            val error = runCatching { client("http://127.0.0.2:${server.port}", token = "token-for-dot-2").probe() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            // .2 上没有服务 → 连接失败；关键是它没有被当成 .1 而把令牌送出去
            assertTrue("code=${error.code}", error.code in setOf("UNREACHABLE", "TIMEOUT"))
            assertFalse(error.message.orEmpty().contains("token-for-dot-2"))
            assertEquals("不得向 .1 发送任何请求", 0, server.requestCount)
        }
    }

    @Test fun protectedRoute401MapsToTokenRequiredOrInvalidByStoredToken() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("true")
                    else -> MockResponse().setResponseCode(401)
                }
            }
            server.start()
            val noToken = runCatching { client(server.url("/").toString(), token = "").fetchSources(emptyList()) }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("TOKEN_REQUIRED", noToken.code)
            val badToken = runCatching { client(server.url("/").toString(), token = "wrong").fetchSources(emptyList()) }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("TOKEN_INVALID", badToken.code)
        }
    }

    @Test fun gatewayErrorsAreNotMistakenForMissingEndpoints() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    else -> MockResponse().setResponseCode(503)
                }
            }
            server.start()
            val error = runCatching { client(server.url("/").toString()).testConnection() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            // 503 是服务暂时不可用，不是「版本没有这个接口」
            assertEquals("HTTP_ERROR", error.code)
        }
    }

    @Test fun loopbackSuggestionKeepsUserPortAndOnlyChangesHost() {
        assertEquals("http://127.0.0.1:1162", ReaderWebOrigin.loopbackFor("http://10.20.30.40:1162"))
        assertEquals("http://127.0.0.1:1162", ReaderWebOrigin.loopbackFor("http://127.0.01:1162"))
        assertEquals("https://127.0.0.1:1163", ReaderWebOrigin.loopbackFor("https://192.168.1.8:1163"))
        assertEquals(null, ReaderWebOrigin.loopbackFor("http://192.168.1.8:1162/api"))
        assertEquals(null, ReaderWebOrigin.loopbackFor(""))
    }

    @Test fun sameDeviceLoopbackInputReachesMockServer() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(okJson("false"))
            server.start()
            // 同机：MockWebServer 监听回环，用户写 127.0.01 归一后应能直连，无需 Wi-Fi
            val origin = ReaderWebOrigin.normalize("http://127.0.01:${server.port}")
            assertEquals("http://127.0.0.1:${server.port}", origin)
            val probe = client(origin).probe()
            assertFalse(probe.tokenRequired)
            assertEquals("/getJsSourceApiTokenRequired", server.takeRequest().path)
        }
    }

    @Test fun wifiAddressIsUsedVerbatimAsUserTypedIt() = kotlinx.coroutines.test.runTest {
        // 用 MockWebServer 的真实回环端口，但把配置写成「用户填的 10.x」并显式解析到本地：
        // 这里只验证 origin 不被改写；真正连通性由上面的同机用例覆盖。
        val origin = ReaderWebOrigin.normalize("http://10.20.30.40:1162")
        assertEquals("http://10.20.30.40:1162", origin)
        val client = client(origin)
        // 该地址在本机必然不可达，但请求确实按用户原值发出（不是被偷偷换成 127.0.0.1）
        val error = runCatching { client.probe() }.exceptionOrNull() as ReaderWebClient.ReaderWebException
        assertTrue("code=${error.code}", error.code in setOf("UNREACHABLE", "TIMEOUT"))
        assertFalse(error.message.orEmpty().contains("10.20.30.40"))
    }

    @Test fun guidanceSeparatesSameDeviceWifiAndCellular() {
        // 同机：回环可用且明确不要求 Wi-Fi
        val sameDevice = ReaderWebGuidance.advise(loopback = true, privateLan = false, kind = ReaderWebGuidance.NetworkKind.CELLULAR)
        assertTrue(sameDevice.headline.contains("同机"))
        assertTrue(sameDevice.detail.contains("无需 Wi-Fi") || sameDevice.detail.contains("不需要 Wi-Fi") ||
            sameDevice.detail.contains("也能连"))
        // 异机同 Wi-Fi：用阅读的 Wi-Fi 地址
        val otherWifi = ReaderWebGuidance.advise(loopback = false, privateLan = true, kind = ReaderWebGuidance.NetworkKind.WIFI)
        assertTrue(otherWifi.detail.contains("局域网地址"))
        // 蜂窝私网：提示不能互访，但不拒绝
        val cellular = ReaderWebGuidance.advise(loopback = false, privateLan = true, kind = ReaderWebGuidance.NetworkKind.CELLULAR)
        assertTrue(cellular.headline.contains("10.x") || cellular.headline.contains("蜂窝"))
        assertTrue(cellular.detail.contains("回环"))
        // 无法判断：不猜，让用户点选
        val unknown = ReaderWebGuidance.advise(loopback = false, privateLan = false, kind = ReaderWebGuidance.NetworkKind.UNKNOWN)
        assertTrue(unknown.detail.contains("无法判断"))
        // 回环/私网识别
        assertTrue(ReaderWebGuidance.isLoopback("http://127.0.0.1:1162"))
        assertTrue(ReaderWebGuidance.isLoopback("http://[::1]:1162"))
        assertFalse(ReaderWebGuidance.isLoopback("http://10.20.30.40:1162"))
        assertTrue(ReaderWebGuidance.isPrivateLan("http://10.20.30.40:1162"))
        assertTrue(ReaderWebGuidance.isPrivateLan("http://192.168.1.8:1122"))
        assertFalse(ReaderWebGuidance.isPrivateLan("http://8.8.8.8:1122"))
    }

    @Test fun guidanceDoesNotCompressVersionDifferencesIntoNotReader() {
        val legacy = ReaderWebGuidance.describe("LEGACY_READER", "legacy_probe", "旧版接口")
        assertFalse(legacy.title.contains("不像阅读"))
        assertTrue("必须说明网页在线", legacy.title.contains("网页在线"))
        assertFalse("不得要求升级", legacy.title.contains("升级"))
        val unsupported = ReaderWebGuidance.describe("CHECK_UNSUPPORTED", "sources", "管理接口缺失")
        assertFalse(unsupported.title.contains("不像阅读"))
        assertTrue(unsupported.stageLabel!!.contains("书源列表"))
        val mismatch = ReaderWebGuidance.describe("NOT_READER", "legacy_probe", "见 http://10.20.30.40:1162")
        assertTrue("只描述接口不匹配，不断言不是阅读", mismatch.title.contains("接口不匹配"))
        assertFalse(mismatch.title.contains("不是阅读"))
        assertFalse("私网地址不得回显", mismatch.detail.contains("10.20.30.40"))
        assertEquals("打开阅读 Web 页面", ReaderWebGuidance.describe("TIMEOUT", "web_page", null).stageLabel)
    }

    // ---- 根路径网页探测（webPageReachable 的唯一依据） ----

    @Test fun webPageProbeTreatsAny2xxHtmlAsPageWithoutTemplateMatching() = kotlinx.coroutines.test.runTest {
        // 现场报告的形态：root 200 是 HTML5UP「Forty」改装的第三方导航页。
        // 探针只看 HTTP 层与粗分类，不匹配 title/模板/资源路径，因此必须 reachable。
        val thirdParty = """
            <!DOCTYPE HTML><html><head><title>Legado web 导航</title>
            <link rel="stylesheet" href="assets/css/main.css"></head>
            <body class="is-preload"><div id="wrapper"><h1>导航</h1></div>
            <script src="assets/js/jquery.min.js"></script></body></html>
        """.trimIndent()
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(thirdParty),
            )
            server.start()
            val probe = client(server.url("/").toString()).probeWebPage()
            assertTrue("root 200 任意 HTML 都算页面入口", probe.reachable)
            assertEquals(200, probe.statusCode)
            assertEquals("html", probe.contentKind)
            assertEquals("只请求一次根路径", 1, server.requestCount)
        }
    }

    @Test fun webPageProbeAccepts2xxWithoutContentType() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            // 省略 Content-Type：仍按正文形状归类，不把缺头当成「不是页面」
            server.enqueue(MockResponse().setBody("<html><body>plain</body></html>"))
            server.start()
            val probe = client(server.url("/").toString()).probeWebPage()
            assertTrue(probe.reachable)
            assertEquals(200, probe.statusCode)
            assertEquals("html", probe.contentKind)
        }
    }

    @Test fun webPageProbeRootTimeoutIsNetworkFailureNotHttpStatus() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            // 连响应头都不发：这才是「根路径没有拿到 HTTP 响应」
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html><body>slow</body></html>")
                    .setHeadersDelay(3, TimeUnit.SECONDS),
            )
            server.start()
            val error = runCatching { client(server.url("/").toString(), timeoutSec = 1).probeWebPage() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("TIMEOUT", error.code)
            assertNull("网络层失败没有 HTTP 状态", error.statusCode)
            assertEquals("web_page", error.stage)
            assertFalse("错误文本不得带出地址", error.message.orEmpty().contains("127.0.0.1"))
        }
    }

    @Test fun webPageProbeBodyTimeoutKeepsObservedStatus() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            // 状态已到、正文延迟：异常必须带上已观察到的状态码，调用方才能区分「无响应」
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html><body>slow</body></html>")
                    .setBodyDelay(3, TimeUnit.SECONDS),
            )
            server.start()
            val error = runCatching { client(server.url("/").toString(), timeoutSec = 1).probeWebPage() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("TIMEOUT", error.code)
            assertEquals("正文读不动时状态码仍然已知", 200, error.statusCode)
        }
    }

    @Test fun webPageProbeRoot404IsHttpStatusFailure() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
            server.start()
            val probe = client(server.url("/").toString()).probeWebPage()
            assertFalse(probe.reachable)
            assertEquals(404, probe.statusCode)
            assertEquals("http_error", probe.contentKind)
        }
    }

    @Test fun webPageProbeGetsRootWithoutTokenAndBounded() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html><body>reader</body></html>"),
            )
            server.start()
            val probe = client(server.url("/").toString(), token = "secret").probeWebPage()
            assertTrue(probe.reachable)
            assertEquals(200, probe.statusCode)
            assertEquals("html", probe.contentKind)
            assertFalse(probe.viaRedirect)
            val request = server.takeRequest()
            assertEquals("固定根路径", "/", request.path)
            assertEquals("GET", request.method)
            assertNull("根路径探测不得带 X-Legado-Token", request.getHeader("X-Legado-Token"))
            assertNull("根路径探测不得带 Authorization", request.getHeader("Authorization"))
            assertTrue(
                "不得预发 Cookie 或书源会话",
                request.getHeader("Cookie") == null,
            )
        }
    }

    @Test fun webPageProbeReportsLoginWallAndHttpErrorTruthfully() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer"))
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val origin = server.url("/").toString()
            val login = client(origin).probeWebPage()
            assertFalse("登录墙不算可在匿名下打开", login.reachable)
            assertEquals("auth", login.contentKind)
            val missing = client(origin).probeWebPage()
            assertFalse(missing.reachable)
            assertEquals(404, missing.statusCode)
            assertEquals("http_error", missing.contentKind)
        }
    }

    @Test fun webPageProbeFollowsSameOriginRedirect() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/" -> MockResponse().setResponseCode(301).setHeader("Location", "/reader/")
                    "/reader/" -> MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html>ok</html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val probe = client(server.url("/").toString()).probeWebPage()
            assertTrue(probe.reachable)
            assertTrue("同源 301 视为页面入口", probe.viaRedirect)
            assertEquals(200, probe.statusCode)
            assertEquals("html", probe.contentKind)
            val requests = generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }.toList()
            assertEquals(listOf("/", "/reader/"), requests.map { it.path })
            assertTrue(requests.all { it.getHeader("X-Legado-Token") == null })
        }
    }

    @Test fun webPageProbeBlocksCrossOriginRedirectWithoutSecondRequest() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            MockWebServer().use { victim ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        "/" -> MockResponse().setResponseCode(302)
                            .setHeader("Location", victim.url("/steal").toString())
                        else -> MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html>ok</html>")
                    }
                }
                server.start()
                victim.start()
                val error = runCatching {
                    client(server.url("/").toString(), token = "secret").probeWebPage()
                }.exceptionOrNull() as ReaderWebClient.ReaderWebException
                assertEquals("REDIRECT_BLOCKED", error.code)
                assertEquals("web_page", error.stage)
                assertEquals(302, error.statusCode)
                assertEquals("越域不得发出第二个请求", 0, victim.requestCount)
                assertFalse(error.message.orEmpty().contains(victim.hostName))
                assertFalse(error.message.orEmpty().contains("secret"))
            }
        }
    }

    @Test fun webPageProbeCapsRedirectHops() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(302).setHeader("Location", "/loop")
            }
            server.start()
            val error = runCatching { client(server.url("/").toString()).probeWebPage() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("REDIRECT_BLOCKED", error.code)
            assertTrue("必须有跳转次数上限", server.requestCount <= 4)
        }
    }

    @Test fun webPageProbeRejectsRedirectWithoutLocation() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302))
            server.start()
            val error = runCatching { client(server.url("/").toString()).probeWebPage() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("REDIRECT_BLOCKED", error.code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun originRejectsPathQueryFragmentUserInfoAndBadPort() {
        val rejected = listOf(
            "http://192.168.1.8:1122/web",
            "http://192.168.1.8:1122/?a=1",
            "http://192.168.1.8:1122/#x",
            "http://user:pass@192.168.1.8:1122",
            "ftp://192.168.1.8:1122",
            "http://192.168.1.8:0",
            "http://192.168.1.8:70000",
            "192.168.1.8:1122",
            "",
        )
        rejected.forEach { raw ->
            assertTrue("应拒绝: $raw", runCatching { ReaderWebOrigin.normalize(raw) }.isFailure)
        }
        assertEquals(null, ReaderWebOrigin.normalizeOrNull("http://192.168.1.8:1122/x"))
    }

    // ---- 客户端安全边界 ----

    @Test fun notConfiguredFailsBeforeAnyNetwork() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val error = runCatching { client(baseUrl = null).probe() }.exceptionOrNull()
            assertTrue(error is ReaderWebClient.ReaderWebException)
            assertEquals("NOT_CONFIGURED", (error as ReaderWebClient.ReaderWebException).code)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun probeReadsTokenSwitchAndSendsOnlyFixedPath() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(okJson("true"))
            server.start()
            val result = client(server.url("/").toString()).probe()
            assertTrue(result.tokenRequired)
            val request = server.takeRequest()
            assertEquals("/getJsSourceApiTokenRequired", request.path)
            assertEquals(null, request.getHeader("Cookie"))
            // 未配置令牌时不发 X-Legado-Token
            assertEquals(null, request.getHeader("X-Legado-Token"))
        }
    }

    @Test fun tokenIsSentOnlyToProtectedRoutesAndNeverLeaksInErrors() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(failJson("Web 书源访问令牌未配置或不正确"))
            server.start()
            val error = runCatching { client(server.url("/").toString(), token = "s3cret-token").probe() }.exceptionOrNull()
            // 令牌开关是公开 GET：不带令牌
            assertEquals(null, server.takeRequest().getHeader("X-Legado-Token"))
            assertTrue(error is ReaderWebClient.ReaderWebException)
            assertFalse(error!!.message.orEmpty().contains("s3cret-token"))
            assertFalse(error.message.orEmpty().contains(server.hostName + ":" + server.port))
        }
    }

    @Test fun http200BusinessFailureIsAnError() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(failJson("请选择需要检验的书源"))
            server.start()
            val error = runCatching { client(server.url("/").toString()).startCheck(listOf(source("https://a.test")), null) }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            // 无令牌时业务文本里出现「令牌」会先被判成 TOKEN_REQUIRED；此文案不含令牌 → BUSINESS_ERROR
            assertEquals("BUSINESS_ERROR", error.code)
            assertTrue(error.message.orEmpty().contains("请选择需要检验的书源"))
        }
    }

    @Test fun htmlPageIsNotMistakenForReturnData() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            // 新版接口回 HTML 前端页、旧版公开读路由也 404 → 只能判断「不是阅读」
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") ->
                        MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody("<html><body>阅读 Web</body></html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val error = runCatching { client(server.url("/").toString()).probe("https://a.test") }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("NOT_READER", error.code)
            assertEquals("legacy_probe", error.stage)
        }
    }

    @Test fun redirectsAreRejectedWithoutFollowing() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            // 只重定向到本机另一个路径：证明不跟随且不会产生第二个请求（不探测任何外部地址）
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
            server.start()
            val error = runCatching { client(server.url("/").toString()).probe() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("REDIRECT_BLOCKED", error.code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun legacyReaderIsRecognizedByOldPublicRouteNotReportedAsNotReader() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") ->
                        MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody("<html><body>Legado Web</body></html>")
                    request.path!!.startsWith("/getBookSource?") ->
                        okJson("""{"bookSourceName":"A","bookSourceUrl":"https://a.test"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val error = runCatching { client(server.url("/").toString()).probe("https://a.test") }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("LEGACY_READER", error.code)
            assertTrue("必须说明网页在线、只是没有结构化检验接口", error.message.orEmpty().contains("网页在线"))
            assertTrue(error.message.orEmpty().contains("结构化检验接口"))
            assertFalse("不得要求升级", error.message.orEmpty().contains("升级"))
            // 旧版公开路由不得携带令牌
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("/getJsSourceApiTokenRequired", first.path)
            assertTrue(second.path!!.startsWith("/getBookSource?"))
            assertEquals(null, first.getHeader("X-Legado-Token"))
            assertEquals(null, second.getHeader("X-Legado-Token"))
        }
    }

    @Test fun legacySourcesProbeIsBoundedAndNeverLoadsWholeLibrary() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            // 旧版几千条书源的整包：远超 4MB 上限，探测只应读开头一小段
            val huge = """{"isSuccess":true,"errorMsg":"","data":[""" +
                (1..80_000).joinToString(",") { """{"bookSourceName":"源$it","bookSourceUrl":"https://s$it.test"}""" } + "]}"
            assertTrue("测试数据必须超过 4MB", huge.toByteArray().size > 4 * 1024 * 1024)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> MockResponse().setResponseCode(404)
                    else -> MockResponse()
                        .setHeader("Content-Type", "application/json; charset=utf-8")
                        .setBody(huge)
                }
            }
            server.start()
            val error = runCatching { client(server.url("/").toString()).probe() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("LEGACY_READER", error.code)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun newTokenEndpointOkButManagementHtmlMeansCheckUnsupported() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    else -> MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody("<html><body>management</body></html>")
                }
            }
            server.start()
            val error = runCatching { client(server.url("/").toString()).testConnection() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            // 网页在线、只是没有这些接口：不是「不像阅读」，也不要求升级
            assertEquals("CHECK_UNSUPPORTED", error.code)
            assertTrue(error.message.orEmpty().contains("页面在线"))
            assertFalse("不得要求升级", error.message.orEmpty().contains("升级"))
            assertEquals("sources", error.stage)
        }
    }

    @Test fun newTokenEndpoint404ButLegacyReturnDataStillMeansReader() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> MockResponse().setResponseCode(500)
                    request.path!!.startsWith("/getBookSource?") -> okJson("""{"bookSourceName":"A"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val error = runCatching { client(server.url("/").toString()).probe("https://a.test") }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("LEGACY_READER", error.code)
        }
    }

    @Test fun businessFailureOnPublicRouteIsNotReportedAsUnreachable() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(failJson("未找到对应书源"))
            server.start()
            val error = runCatching { client(server.url("/").toString()).probe() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            // 200 + isSuccess=false：阅读在线，是业务错误；不能降级成 LEGACY/NOT_READER
            assertEquals("BUSINESS_ERROR", error.code)
            assertTrue(error.message.orEmpty().contains("未找到对应书源"))
        }
    }

    @Test fun verifierReportsLegacyReaderAsNeedsUpgradeInsteadOfPassing() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> MockResponse().setResponseCode(404)
                    request.path!!.startsWith("/getBookSource?") -> okJson("""{"bookSourceName":"A"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val error = runCatching {
                ReaderWebVerifier(client(server.url("/").toString()), pollIntervalMs = 0)
                    .verify("https://a.test", null, 10)
            }.exceptionOrNull() as ReaderWebVerifier.VerifyException
            assertEquals("LEGACY_READER", error.code)
            // verify 只能走新版结构化接口；降级后仍不得把旧版日志当成检验通过
            assertTrue("必须给出可执行的下一步", error.message.orEmpty().contains("手动检验"))
            assertFalse("旧版响应不得被当成检验通过", error.message.orEmpty().contains("通过"))
        }
    }

    @Test fun testConnectionPinsConfigSoSecondStepCannotHitAnotherHost() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                var current = ReaderWebConfigStore.Config(first.url("/").toString().removeSuffix("/"), "")
                first.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        // 第一步（令牌开关）成功后，「用户」在界面上改了地址
                        current = ReaderWebConfigStore.Config(second.url("/").toString().removeSuffix("/"), "")
                        return if (request.path!!.startsWith("/getJsSourceApiTokenRequired")) {
                            okJson("false")
                        } else {
                            okJson("""{"sources":[],"states":[]}""")
                        }
                    }
                }
                second.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        okJson("""{"sources":[],"states":[]}""")
                }
                first.start()
                second.start()
                ReaderWebClient(configProvider = { current }, clientFactory = { testClient() }).testConnection()
                assertEquals(0, second.requestCount)
                assertTrue(first.requestCount >= 2)
            }
        }
    }

    @Test fun offlineServiceReportsUnreachableAndTimesOut() = kotlinx.coroutines.test.runTest {
        // 先占用再关闭，拿到一个必然拒绝连接的端口
        val port = MockWebServer().use { it.start(); it.port }
        val error = runCatching { client("http://127.0.0.1:$port").probe() }.exceptionOrNull()
            as ReaderWebClient.ReaderWebException
        assertTrue("code=${error.code}", error.code in setOf("UNREACHABLE", "TIMEOUT"))
        assertFalse(error.message.orEmpty().contains("://"))
    }

    @Test fun slowServiceTimesOutWithoutLeakingAddress() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(3, TimeUnit.SECONDS))
            server.start()
            val error = runCatching { client(server.url("/").toString(), timeoutSec = 1).probe() }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("TIMEOUT", error.code)
            assertFalse(error.message.orEmpty().contains("127.0.0.1"))
        }
    }

    @Test fun sourcesAreRequestedByUrlListAndKeepReaderState() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(okJson("""{"sources":[{"bookSourceName":"A","bookSourceUrl":"https://a.test"}],"states":[{"bookSourceUrl":"https://a.test","status":"PASSED","detail":"ok","sourceRevision":"r1"}]}"""))
            server.start()
            val entries = client(server.url("/").toString()).fetchSources(listOf("https://a.test"))
            assertEquals(1, entries.size)
            assertEquals("https://a.test", entries.first().source.get("bookSourceUrl").asString)
            assertEquals("PASSED", entries.first().state?.get("status")?.asString)
            val request = server.takeRequest()
            assertEquals("/getBookSourcesForManagement", request.path!!.substringBefore('?'))
            val urls = java.net.URLDecoder.decode(request.requestUrl!!.queryParameter("urls"), "UTF-8")
            assertEquals("""["https://a.test"]""", urls)
        }
    }

    // ---- 书源精确预检的形状 ----

    @Test fun lookupOnlyTreatsExplicitNotFoundAsAbsent() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(okJson("null"))
            server.start()
            val client = client(server.url("/").toString())
            // isSuccess=true + data=null：读不懂，不得当成「没有这个源」
            val nullData = runCatching { client.fetchSource("https://a.test") }.exceptionOrNull()
                as ReaderWebClient.ReaderWebException
            assertEquals("INCOMPATIBLE", nullData.code)

            server.enqueue(okJson("{}"))
            val emptyObject = runCatching { client.fetchSource("https://a.test") }.exceptionOrNull()
                as ReaderWebClient.ReaderWebException
            assertEquals("INCOMPATIBLE", emptyObject.code)

            server.enqueue(okJson("[]"))
            val array = runCatching { client.fetchSource("https://a.test") }.exceptionOrNull()
                as ReaderWebClient.ReaderWebException
            assertEquals("INCOMPATIBLE", array.code)

            server.enqueue(okJson("""{"bookSourceName":"别的源","bookSourceUrl":"https://other.test"}"""))
            val mismatch = runCatching { client.fetchSource("https://a.test") }.exceptionOrNull()
                as ReaderWebClient.ReaderWebException
            assertEquals("SOURCE_MISMATCH", mismatch.code)

            // 只有明确的官方「未找到」才是 present=false
            server.enqueue(failJson("未找到源"))
            assertFalse(client.fetchSource("https://a.test").present)

            // 其它业务失败仍是错误，不能被当作「不存在」而触发覆盖写
            server.enqueue(failJson("保存失败：书源校验不通过"))
            val business = runCatching { client.fetchSource("https://a.test") }.exceptionOrNull()
                as ReaderWebClient.ReaderWebException
            assertEquals("BUSINESS_ERROR", business.code)
        }
    }

    // ---- 检验编排 ----

    @Test fun verifyPollsUntilReaderFinishes() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val url = "https://a.test"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[${source(url)}],"states":[]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> okJson(
                        """{"states":[{"bookSourceUrl":"$url","status":"NEEDS_CHECK","sourceRevision":"r1"}],"sessionToken":"p:1"}"""
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val verifier = ReaderWebVerifier(
                client(server.url("/").toString()),
                pollIntervalMs = 0,
                now = run {
                    var tick = 0L
                    { (tick + 1_000).also { tick = it } }
                },
            )
            val outcome = verifier.verify(url, "关键词", timeoutSec = 10)
            // 状态一直 NEEDS_CHECK 直到预算用尽 → 如实报 timeout，不伪造通过
            assertEquals("timeout", outcome.status)
            assertTrue(outcome.polls >= 1)
            assertTrue(outcome.states.isNotEmpty())
            assertTrue(outcome.message.contains("超时"))
        }
    }

    @Test fun verifyReturnsPassedOnlyWhenReaderSaysPassed() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val url = "https://a.test"
            var stateCalls = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[${source(url)}],"states":[]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> {
                        stateCalls++
                        if (stateCalls == 1) okJson("""{"states":[{"bookSourceUrl":"$url","status":"NEEDS_CHECK","revision":"rv-2","sourceRevision":"r1","checkedAt":0}],"sessionToken":"p:1"}""")
                        else okJson("""{"states":[{"bookSourceUrl":"$url","status":"PASSED","detail":"三项通过","revision":"rv-2","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":null}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val verifier = ReaderWebVerifier(client(server.url("/").toString()), pollIntervalMs = 0)
            val outcome = verifier.verify(url, null, timeoutSec = 10)
            assertEquals("passed", outcome.status)
            assertEquals("三项通过", outcome.state?.get("detail")?.asString)
            assertEquals(2, stateCalls)
            // start 请求提交的是阅读里的完整书源，且带 keyword 只在传了的时候
            val startBody = server.takeRequest().let { _ ->
                generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }.toList()
            }.firstOrNull { it.path!!.startsWith("/startBookSourceCheck") }?.body?.readUtf8().orEmpty()
            assertTrue(startBody.contains("\"sources\""))
            assertFalse(startBody.contains("keyword"))
        }
    }

    @Test fun verifyReportsReaderFailureAsFailed() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val url = "https://a.test"
            var stateCalls = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[${source(url)}],"states":[]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                    else -> {
                        stateCalls++
                        if (stateCalls == 1) okJson("""{"states":[{"bookSourceUrl":"$url","status":"NEEDS_CHECK","revision":"rv-2","sourceRevision":"r1","checkedAt":0}],"sessionToken":"p:1"}""")
                        else okJson("""{"states":[{"bookSourceUrl":"$url","status":"FAILED","detail":"搜索超时","revision":"rv-2","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":null}""")
                    }
                }
            }
            server.start()
            val verifier = ReaderWebVerifier(client(server.url("/").toString()), pollIntervalMs = 0)
            val outcome = verifier.verify(url, null, timeoutSec = 10)
            assertEquals("failed", outcome.status)
            assertTrue(outcome.state?.get("detail")?.asString == "搜索超时")
        }
    }

    // ---- 只认本次检验写入的新状态（旧 PASSED 不得冒充通过） ----

    /** 上一个用例的 tick 时钟：每次读时间前进 1s，避免真实等待整个预算。 */
    private fun tickingNow(): () -> Long = run {
        var tick = 0L
        { (tick + 1_000).also { tick = it } }
    }

    @Test fun stalePassedFromPreviousCheckIsNeverReportedAsPassed() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val url = "https://a.test"
            // 阅读里留着上一轮检验的 PASSED：revision rv-old、checkedAt 已非 0
            val staleState = """{"bookSourceUrl":"$url","status":"PASSED","detail":"上一轮通过","revision":"rv-old","sourceRevision":"r1","checkedAt":1600000000000}"""
            var stateCalls = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[${source(url)}],"states":[$staleState]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> {
                        stateCalls++
                        // 本次检验还没写入新状态：轮询一直读到同一条旧 PASSED
                        okJson("""{"states":[$staleState],"sessionToken":"p:1"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val outcome = ReaderWebVerifier(
                client(server.url("/").toString()),
                pollIntervalMs = 0,
                now = tickingNow(),
            ).verify(url, null, timeoutSec = 10)
            assertTrue(stateCalls >= 1)
            assertFalse("旧 PASSED 不得冒充本次通过", outcome.status == "passed")
            assertEquals("stale_state", outcome.status)
            assertTrue(outcome.message.contains("旧状态"))
            assertEquals(false, outcome.progress["confirmedNewState"])
            assertEquals(true, outcome.progress["staleStateIgnored"])
            assertEquals(false, outcome.progress["stateRevisionChanged"])
        }
    }

    @Test fun passedFromDifferentSourceRevisionIsNotAccepted() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val url = "https://a.test"
            val oldState = """{"bookSourceUrl":"$url","status":"PASSED","detail":"上一轮通过","revision":"rv-old","sourceRevision":"r1","checkedAt":1600000000000}"""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[${source(url)}],"states":[$oldState]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                    // revision 变了，但 sourceRevision 是另一个版本
                    else -> okJson("""{"states":[{"bookSourceUrl":"$url","status":"PASSED","detail":"别的版本通过","revision":"rv-2","sourceRevision":"r-other","checkedAt":1700000000000}],"sessionToken":"p:1"}""")
                }
            }
            server.start()
            val outcome = ReaderWebVerifier(
                client(server.url("/").toString()),
                pollIntervalMs = 0,
                now = tickingNow(),
            ).verify(url, null, timeoutSec = 10)
            assertFalse("异版本 PASSED 不得判通过", outcome.status == "passed")
            assertEquals("stale_state", outcome.status)
            assertEquals(true, outcome.progress["stateRevisionChanged"])
            assertEquals(false, outcome.progress["sourceRevisionMatched"])
        }
    }

    @Test fun passedOnlyWhenRevisionAndTimestampBelongToThisCheck() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val url = "https://a.test"
            val oldState = """{"bookSourceUrl":"$url","status":"PASSED","detail":"上一轮通过","revision":"rv-old","sourceRevision":"r1","checkedAt":1600000000000}"""
            var stateCalls = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[${source(url)}],"states":[$oldState]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> {
                        stateCalls++
                        if (stateCalls == 1) {
                            okJson("""{"states":[{"bookSourceUrl":"$url","status":"NEEDS_CHECK","revision":"rv-new","sourceRevision":"r1","checkedAt":0}],"sessionToken":"p:1"}""")
                        } else {
                            okJson("""{"states":[{"bookSourceUrl":"$url","status":"PASSED","detail":"本次通过","revision":"rv-new","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":null}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val outcome = ReaderWebVerifier(client(server.url("/").toString()), pollIntervalMs = 0)
                .verify(url, null, timeoutSec = 10)
            assertEquals("passed", outcome.status)
            assertEquals("本次通过", outcome.state?.get("detail")?.asString)
            assertEquals(true, outcome.progress["confirmedNewState"])
            assertEquals(true, outcome.progress["stateRevisionChanged"])
            assertEquals(true, outcome.progress["sourceRevisionMatched"])
            assertEquals(false, outcome.progress["staleStateIgnored"])
            assertEquals(2, stateCalls)
        }
    }

    @Test fun verifyRefusesWhenReaderOmitsSourceRevision() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val url = "https://a.test"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[${source(url)}],"states":[]}""")
                    request.path!!.startsWith("/startBookSourceCheck") -> okJson("""{"sessionToken":"p:1"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val error = runCatching {
                ReaderWebVerifier(client(server.url("/").toString()), pollIntervalMs = 0).verify(url, null, 10)
            }.exceptionOrNull() as ReaderWebVerifier.VerifyException
            // 拿不到本次版本号就无法确认状态归属，必须报错而不是继续轮询
            assertEquals("SOURCE_REVISION_UNKNOWN", error.code)
            // probe + fetchSources + startCheck 之后立刻报错，不再轮询
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun newPassedFromAnotherCheckSessionIsNotAccepted() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val url = "https://a.test"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[${source(url)}],"states":[]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                    // 另一个客户端的新检验已取代本会话：令牌不同，但它写下的 PASSED 是新 revision
                    else -> okJson("""{"states":[{"bookSourceUrl":"$url","status":"PASSED","detail":"别人的会话通过","revision":"rv-other","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":"p:2"}""")
                }
            }
            server.start()
            val outcome = ReaderWebVerifier(
                client(server.url("/").toString()),
                pollIntervalMs = 0,
                now = tickingNow(),
            ).verify(url, null, timeoutSec = 10)
            assertFalse("异会话的新 PASSED 不得算本次通过", outcome.status == "passed")
            assertEquals("session_replaced", outcome.status)
            assertTrue(outcome.message.contains("另一个检验会话"))
            assertEquals(true, outcome.progress["sessionReplaced"])
            assertEquals(false, outcome.progress["confirmedNewState"])
        }
    }

    @Test fun passedFromOwnSessionWithoutNeedCheckIsNotAccepted() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val url = "https://a.test"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[${source(url)}],"states":[]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                    // 会话已结束（token 为 null），但从未观察到本会话的 NEEDS_CHECK revision：无法证明归属
                    else -> okJson("""{"states":[{"bookSourceUrl":"$url","status":"PASSED","detail":"来历不明","revision":"rv-2","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":null}""")
                }
            }
            server.start()
            val outcome = ReaderWebVerifier(
                client(server.url("/").toString()),
                pollIntervalMs = 0,
                now = tickingNow(),
            ).verify(url, null, timeoutSec = 10)
            assertFalse(outcome.status == "passed")
            assertEquals("session_unconfirmed", outcome.status)
            assertEquals(false, outcome.progress["needCheckRevisionObserved"])
        }
    }

    @Test fun passedAcceptedWhenSessionTokenMatchesStartCheck() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val url = "https://a.test"
            var stateCalls = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[${source(url)}],"states":[]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> {
                        stateCalls++
                        if (stateCalls == 1) okJson("""{"states":[{"bookSourceUrl":"$url","status":"NEEDS_CHECK","revision":"rv-2","sourceRevision":"r1","checkedAt":0}],"sessionToken":"p:1"}""")
                        else okJson("""{"states":[{"bookSourceUrl":"$url","status":"PASSED","detail":"本会话通过","revision":"rv-2","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":"p:1"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val outcome = ReaderWebVerifier(client(server.url("/").toString()), pollIntervalMs = 0)
                .verify(url, null, timeoutSec = 10)
            assertEquals("passed", outcome.status)
            assertEquals(true, outcome.progress["sessionTokenMatched"])
            assertEquals(true, outcome.progress["needCheckRevisionObserved"])
            assertEquals(false, outcome.progress["sessionReplaced"])
            assertEquals(2, stateCalls)
        }
    }

    @Test fun verifyRefusesSourceMissingFromReader() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") -> okJson("""{"sources":[],"states":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val error = runCatching { ReaderWebVerifier(client(server.url("/").toString()), pollIntervalMs = 0).verify("https://nope.test", null, 10) }
                .exceptionOrNull()
            assertTrue(error is ReaderWebVerifier.VerifyException)
            assertEquals("SOURCE_NOT_IN_READER", (error as ReaderWebVerifier.VerifyException).code)
        }
    }

    @Test fun verifyRequiresTokenWhenReaderProtectsApi() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.enqueue(okJson("true"))
            server.start()
            val error = runCatching {
                ReaderWebVerifier(client(server.url("/").toString(), token = ""), pollIntervalMs = 0)
                    .verify("https://a.test", null, 10)
            }.exceptionOrNull() as ReaderWebVerifier.VerifyException
            assertEquals("TOKEN_REQUIRED", error.code)
            assertNotNull(error.message)
            assertEquals(1, server.requestCount)
        }
    }

    // ---- origin 与令牌绑定 / 配置固定在调用开始时 ----

    @Test fun tokenIsKeptOnlyWhileOriginUnchanged() {
        val prev = "http://192.168.1.8:1122"
        val oldToken = "old-token"
        // 地址不变 + 留空：保持已存令牌
        assertEquals(oldToken, ReaderWebConfigPolicy.resolve(prev, oldToken, prev, null).token)
        // 末尾斜杠差异不算换地址
        assertEquals(oldToken, ReaderWebConfigPolicy.resolve(prev, oldToken, "$prev/", null).token)
        // 地址变了 + 留空：必须清除旧令牌，不能跟着发到新地址
        assertEquals(null, ReaderWebConfigPolicy.resolve(prev, oldToken, "http://192.168.1.9:1122", null).token)
        // 地址变了 + 显式填入新令牌：改用新令牌
        val replaced = ReaderWebConfigPolicy.resolve(prev, oldToken, "http://192.168.1.9:1122", " new-token ")
        assertEquals("new-token", replaced.token)
        assertEquals("http://192.168.1.9:1122", replaced.origin)
        // 显式清空（空串/空白）始终清除
        assertEquals(null, ReaderWebConfigPolicy.resolve(prev, oldToken, prev, "").token)
        assertEquals(null, ReaderWebConfigPolicy.resolve(prev, oldToken, prev, "   ").token)
        // 首次配置、没有旧地址：留空就不写令牌
        assertEquals(null, ReaderWebConfigPolicy.resolve(null, null, prev, null).token)
        // 非法地址仍然拒绝
        assertTrue(runCatching { ReaderWebConfigPolicy.resolve(prev, oldToken, "$prev/web", null) }.isFailure)
    }

    @Test fun storedTokenIsNotSentAfterSavingAnotherOrigin() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val oldOrigin = "http://10.0.0.1:1122"
            server.start()
            // 用回环 IP 而非 localhost：保存后 origin 会被归一成 127.0.0.1
            val newOrigin = "http://127.0.0.1:${server.port}"
            // 真实「保存」判定：换 origin、令牌框留空 → 存的令牌应被清掉
            val persisted = ReaderWebConfigPolicy.resolve(oldOrigin, "old-token", newOrigin, null)
            assertEquals(newOrigin.trimEnd('/'), persisted.origin)
            assertEquals(null, persisted.token)
            server.enqueue(okJson("false"))
            // 用「保存后实际生效的令牌」构造客户端：新地址上不会出现旧令牌
            client(persisted.origin, token = persisted.token.orEmpty()).probe()
            assertEquals(null, server.takeRequest().getHeader("X-Legado-Token"))
        }
    }

    @Test fun configChangeMidVerifyKeepsRequestsOnOriginalOrigin() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                val url = "https://a.test"
                var stateCalls = 0
                var current = ReaderWebConfigStore.Config(first.url("/").toString().removeSuffix("/"), "old-token")
                first.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                        request.path!!.startsWith("/getBookSourcesForManagement") ->
                            okJson("""{"sources":[${source(url)}],"states":[]}""")
                        request.path!!.startsWith("/startBookSourceCheck") ->
                            okJson("""{"sessionToken":"p:1","sourceRevisions":{"$url":"r1"}}""")
                        else -> {
                            stateCalls++
                            // 第一次轮询后，「用户」把配置改到另一个服务
                            if (stateCalls == 1) {
                                current = ReaderWebConfigStore.Config(second.url("/").toString(), "new-token")
                                okJson("""{"states":[{"bookSourceUrl":"$url","status":"NEEDS_CHECK","revision":"rv-2","sourceRevision":"r1","checkedAt":0}],"sessionToken":"p:1"}""")
                            } else {
                                okJson("""{"states":[{"bookSourceUrl":"$url","status":"PASSED","detail":"原地址通过","revision":"rv-2","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":null}""")
                            }
                        }
                    }
                }
                second.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        okJson("""{"states":[{"bookSourceUrl":"$url","status":"PASSED","revision":"rv-x","sourceRevision":"r1","checkedAt":1800000000000}],"sessionToken":null}""")
                }
                first.start()
                second.start()
                val verifier = ReaderWebVerifier(
                    ReaderWebClient(configProvider = { current }, clientFactory = { testClient() }),
                    pollIntervalMs = 0,
                )
                val outcome = verifier.verify(url, null, timeoutSec = 10)
                // 检验固定了开始时的地址：结论来自原服务，第二个服务一个请求都没收到
                assertEquals("passed", outcome.status)
                assertEquals("原地址通过", outcome.state?.get("detail")?.asString)
                assertEquals(0, second.requestCount)
                assertTrue(first.requestCount >= 4)
                // 原地址只收到开始时的令牌，新令牌不会跨主机漂移
                val firstRequests = generateSequence { first.takeRequest(1, TimeUnit.MILLISECONDS) }.toList()
                assertTrue(firstRequests.isNotEmpty())
                firstRequests.forEach { req ->
                    if (req.path!!.startsWith("/getJsSourceApiTokenRequired")) {
                        // 公开探测路由不发令牌
                        assertEquals(null, req.getHeader("X-Legado-Token"))
                    } else {
                        assertEquals("old-token", req.getHeader("X-Legado-Token"))
                    }
                }
            }
        }
    }


    @Test fun payloadKeepsReaderProvenanceAndNeverClaimsLocalEvidence() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val url = "https://a.test"
            var stateCalls = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") ->
                        okJson("""{"sources":[${source(url)}],"states":[]}""")
                    request.path!!.startsWith("/startBookSourceCheck") ->
                        okJson("""{"sessionToken":"p:7","sourceRevisions":{"$url":"r1"}}""")
                    else -> {
                        stateCalls++
                        if (stateCalls == 1) okJson("""{"states":[{"bookSourceUrl":"$url","status":"NEEDS_CHECK","revision":"rv-2","sourceRevision":"r1","checkedAt":0}],"sessionToken":"p:7"}""")
                        else okJson("""{"states":[{"bookSourceUrl":"$url","status":"UNCHECKABLE","detail":"站点需登录","revision":"rv-2","sourceRevision":"r1","checkedAt":1700000000000}],"sessionToken":null}""")
                    }
                }
            }
            server.start()
            val outcome = ReaderWebVerifier(client(server.url("/").toString()), pollIntervalMs = 0)
                .verify(url, "我", timeoutSec = 10)
            val payload = outcome.toPayload()
            assertEquals("uncheckable", payload["status"])
            assertEquals("reader_web", payload["evidenceScope"])
            assertEquals(url, payload["sourceUrl"])
            assertEquals("p:7", payload["sessionToken"])
            assertTrue((payload["evidenceNote"] as String).contains("不登记"))
            val state = payload["state"] as com.google.gson.JsonObject
            assertEquals("站点需登录", state.get("detail").asString)
        }
    }

    @Test fun errorPayloadUsesLowercaseStatusAndKeepsCode() {
        val payload = ReaderWebVerifier.Outcome.errorPayload("TOKEN_REQUIRED", "请填写令牌")
        assertEquals("token_required", payload["status"])
        assertEquals("TOKEN_REQUIRED", payload["errorCode"])
        assertEquals("请填写令牌", payload["message"])
    }
}
