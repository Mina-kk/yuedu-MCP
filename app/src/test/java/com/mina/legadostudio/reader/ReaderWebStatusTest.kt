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
 * `reader_web_status`：能力驱动、只读、无副作用。
 *
 * 判定主轴是「用户选的阅读 Web 页面能否操作」（根路径 GET），官方接口画像只是可选增强：
 * 网页 HTML 200 **不等于**结构化检验通过，接口不匹配也**不**断言不是阅读、不要求升级。
 */
class ReaderWebStatusTest {

    private fun okJson(data: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody("""{"isSuccess":true,"errorMsg":"","data":$data}""")

    private fun html(body: String = "<html><body>reader web</body></html>") = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    private fun client(
        baseUrl: String?,
        token: String = "",
        revision: Long = 1L,
    ): ReaderWebClient = ReaderWebClient(
        configProvider = { baseUrl?.let { ReaderWebConfigStore.Config(it, token, revision) } },
        clientFactory = {
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .callTimeout(5, TimeUnit.SECONDS).build()
        },
    )

    private fun bridge(baseUrl: String?, token: String = "") = ReaderWebBridge(client(baseUrl, token))

    // ---- 新版：结构化检验可用 ----

    @Test fun modernReaderReportsStructuredCheckAndNextActions() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/" -> html()
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") -> okJson("""{"sources":[],"states":[]}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> okJson("""{"states":[],"sessionToken":null}""")
                    request.path!!.startsWith("/getBookSources") -> okJson("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString()).status()
            assertEquals("ok", status.toPayload()["status"])
            assertTrue(status.configured)
            assertTrue(status.reachable)
            assertTrue(status.webPageReachable)
            assertTrue(status.readerDetected)
            assertEquals(ReaderWebBridge.ApiProfile.STRUCTURED, status.apiProfile)
            assertEquals(
                ReaderWebBridge.Capability.SUPPORTED,
                status.capabilities.getValue("check").state,
            )
            assertEquals(
                ReaderWebBridge.Capability.SUPPORTED,
                status.capabilities.getValue("read").state,
            )
            assertEquals(
                ReaderWebBridge.Capability.SUPPORTED,
                status.capabilities.getValue("webPage").state,
            )
            // 写入/旧版调试不可能在无副作用的前提下确认 → unknown，不能判成 unsupported
            assertEquals(ReaderWebBridge.Capability.UNKNOWN, status.capabilities.getValue("write").state)
            assertEquals(ReaderWebBridge.Capability.UNKNOWN, status.capabilities.getValue("legacyDebug").state)
            assertEquals(ReaderWebBridge.Capability.UNKNOWN, status.capabilities.getValue("search").state)
            assertTrue(status.allowedNextActions.contains("reader_web_debug"))
            assertTrue(status.allowedNextActions.contains("reader_web_search"))
            assertTrue(status.allowedNextActions.contains("reader_web_sync_source"))
            assertTrue(status.allowedNextActions.contains("reader_web_verify"))
            assertTrue("网页可操作时应给出页面工具", status.allowedNextActions.contains("reader_web_page"))
            // 探测本身不能写入或启动检验
            val paths = generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }.map { it.path!! }.toList()
            assertTrue(paths.none { it == "/saveBookSource" })
            assertTrue(paths.none { it.startsWith("/startBookSourceCheck") })
            // 根路径探测不带任何令牌
            assertEquals("/", paths.first())
        }
    }

    // ---- 旧版：没有结构检验，但网页与公开读都可用 ----

    @Test fun legacyReaderReportsCheckUnsupportedButPageAndReadAvailable() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/" -> html()
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> MockResponse().setResponseCode(404)
                    request.path!!.startsWith("/getBookSources") -> okJson("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString()).status()
            assertTrue("旧公开读可用即应识别到官方接口", status.readerDetected)
            assertTrue("网页即使用户能操作就算可用", status.webPageReachable)
            assertEquals("ok", status.toPayload()["status"])
            assertEquals(ReaderWebBridge.ApiProfile.LEGACY_READ, status.apiProfile)
            assertEquals(
                ReaderWebBridge.Capability.UNSUPPORTED,
                status.capabilities.getValue("check").state,
            )
            assertTrue(
                "缺少结构检验时应提示可走旧版调试",
                status.allowedNextActions.contains("reader_web_debug"),
            )
            assertTrue(status.allowedNextActions.contains("reader_web_page"))
        }
    }

    // ---- 未配置 / 不可达 ----

    @Test fun notConfiguredIsExplicitAndDoesNoNetwork() = kotlinx.coroutines.test.runTest {
        val status = bridge(baseUrl = null).status()
        assertFalse(status.configured)
        assertEquals("not_configured", status.toPayload()["status"])
        assertTrue(status.allowedNextActions.isEmpty())
        assertEquals(ReaderWebBridge.ApiProfile.NOT_PROBED, status.apiProfile)
        assertNull(status.originEcho)
        assertEquals(0L, status.configRevision)
        // 未配置时所有能力都必须是 unknown，而不是「不支持」
        status.capabilities.values.forEach { assertEquals(ReaderWebBridge.Capability.UNKNOWN, it.state) }
    }

    @Test fun unreachableRootIsReportedTruthfullyWithoutLeakingPrivateHost() = kotlinx.coroutines.test.runTest {
        val port = MockWebServer().use { it.start(); it.port }
        val status = bridge("http://127.0.0.1:$port").status()
        assertFalse(status.reachable)
        assertFalse("根路径不可达时必须如实报", status.webPageReachable)
        assertEquals("unreachable", status.toPayload()["status"])
        assertEquals(ReaderWebBridge.ApiProfile.NOT_PROBED, status.apiProfile)
        assertEquals(listOf("reader_web_status"), status.allowedNextActions)
        // 回环可以回显实际端口（让模型知道保存的不是默认端口），但不得回显令牌形态
        assertEquals("http://127.0.0.1:$port", status.originEcho)
        // 根路径完全连不上：是网络层失败，且没有 HTTP 状态可报
        assertNull(status.toPayload()["webPageStatusCode"])
        assertTrue(status.webPageProbeErrorCode in setOf("UNREACHABLE", "TIMEOUT"))
        assertEquals("network", status.toPayload()["webPageProbeFailureKind"])
        assertEquals(
            "功能标志与可达性无关，离线时也要回传",
            true,
            status.toPayload()["webUiSessionSupported"],
        )
    }

    /** 根路径就是页面入口：HTML 200 只说明网页可用，不是结构化检验通过。 */
    @Test fun htmlRootMakesPageAvailableButApiProfileMismatch() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/" -> html("<html><body>router admin</body></html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString()).status()
            val payload = status.toPayload()
            assertTrue(status.reachable)
            assertTrue("根路径 200 HTML = 网页在线", status.webPageReachable)
            assertEquals("html", status.webPageContentKind)
            assertEquals(200, status.webPageStatusCode)
            assertFalse("HTML 200 不能当成识别到官方接口", status.readerDetected)
            assertEquals("web_page_available", payload["status"])
            assertEquals(ReaderWebBridge.ApiProfile.MISMATCH, status.apiProfile)
            assertEquals("reader_web_page", status.allowedNextActions.first())
            assertEquals(
                ReaderWebBridge.Capability.SUPPORTED,
                status.capabilities.getValue("webPage").state,
            )
            assertTrue(status.message.contains("网页在线"))
            assertTrue(status.message.contains("官方接口不匹配"))
            assertTrue("明确不断言不是阅读", status.message.contains("不断言不是阅读"))
            assertTrue("明确不强制升级", status.message.contains("不强制升级"))
            assertFalse("不得要求升级", status.message.contains("请升级"))
            assertFalse("不得要求先升级再试", status.message.contains("需升级"))
            // 网页 200 不等于结构化检验：check 不能被报成 supported
            assertFalse(status.capabilities.getValue("check").state == ReaderWebBridge.Capability.SUPPORTED)
        }
    }

    /** 根路径 302 到同源页面：算页面入口，只跟随同源。 */
    @Test fun sameOriginRootRedirectIsTreatedAsPageEntry() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/" -> MockResponse().setResponseCode(302).setHeader("Location", "/reader/index.html")
                    "/reader/index.html" -> html("<html><body>reader</body></html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString()).status()
            assertTrue(status.webPageReachable)
            assertTrue("同源跳转仍算网页入口", status.webPageStatusCode == 200)
            assertEquals("web_page_available", status.toPayload()["status"])
            val requests = generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }.toList()
            assertEquals("/", requests.first().path)
            assertTrue("必须跟随到同源入口", requests.any { it.path == "/reader/index.html" })
            // 越域请求一条都不能有：所有请求都留在同一 host:port
            assertTrue(requests.all { it.requestUrl!!.host == server.hostName && it.requestUrl!!.port == server.port })
        }
    }

    /** 根路径 302 跨域：拒绝跟随，不把令牌/请求带去别的地址。 */
    @Test fun crossOriginRootRedirectIsRefusedAndDoesNotLeakToken() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            MockWebServer().use { victim ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        "/" -> MockResponse().setResponseCode(302)
                            .setHeader("Location", victim.url("/steal").toString())
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                server.start()
                victim.start()
                val status = bridge(server.url("/").toString(), token = "secret-token").status()
                val payload = status.toPayload()
                assertTrue("服务确实响应了，不能判离线", status.reachable)
                assertFalse("越域跳转不构成页面入口", status.webPageReachable)
                assertEquals("web_page_unavailable", payload["status"])
                assertEquals("REDIRECT_BLOCKED", payload["webPageProbeErrorCode"])
                assertEquals("redirect_refused", payload["webPageProbeFailureKind"])
                assertEquals("越域 302 的状态码如实回传，但不含目标地址", 302, payload["webPageStatusCode"])
                assertFalse("官方接口不匹配也不能断言是阅读", status.readerDetected)
                assertEquals("不得向越域目标发任何请求", 0, victim.requestCount)
                val requests = generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }.toList()
                assertTrue(requests.none { request -> request.getHeader("Authorization") != null })
                assertTrue(requests.none { request -> request.getHeader("X-Legado-Token") != null })
                assertFalse(status.toPayload().toString().contains("secret-token"))
                assertFalse(
                    "不得回显越域目标端口",
                    status.toPayload().toString().contains(":${victim.port}"),
                )
            }
        }
    }

    @Test fun webPageRootUnavailableButApiOnlineIsDistinctStatus() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/" -> MockResponse().setResponseCode(404)
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") -> okJson("""{"sources":[],"states":[]}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> okJson("""{"states":[],"sessionToken":null}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString()).status()
            val payload = status.toPayload()
            assertTrue(status.reachable)
            assertFalse(status.webPageReachable)
            assertTrue(status.readerDetected)
            // 接口可达 ≠ 网页入口可用：既不能报 ok，也不能笼统报 web_page_unavailable
            assertEquals("api_only_available", payload["status"])
            assertEquals(404, payload["webPageStatusCode"])
            assertNull("拿到 HTTP 状态就不是网络层失败", payload["webPageProbeErrorCode"])
            assertEquals("http_status", payload["webPageProbeFailureKind"])
            assertEquals(
                ReaderWebBridge.Capability.UNSUPPORTED,
                status.capabilities.getValue("webPage").state,
            )
            assertTrue("必须说明只是接口可达", status.message.contains("接口可达"))
            assertTrue("必须区分根路径 HTTP 错误", status.message.contains("HTTP 404"))
            assertFalse("不得因此断言不是阅读", status.message.contains("不是阅读的"))
        }
    }

    /** 根路径网络层失败但接口在线：必须报「根路径网络层失败」，不能与 HTTP 状态错误混为一谈。 */
    @Test fun rootTimeoutWithApiOnlineReportsNetworkFailureNotHttpStatus() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    // 连响应头都没有：真正的「没有拿到 HTTP 响应」
                    request.path == "/" -> MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody("<html><body>root</body></html>")
                        .setHeadersDelay(3, TimeUnit.SECONDS)
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") -> okJson("""{"sources":[],"states":[]}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> okJson("""{"states":[],"sessionToken":null}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = timeoutBridge(server.url("/").toString()).status()
            val payload = status.toPayload()
            assertTrue("接口在线", status.readerDetected)
            assertTrue("接口有响应才算在线", status.reachable)
            assertFalse(status.webPageReachable)
            assertNull("没有读到 HTTP 状态", payload["webPageStatusCode"])
            assertEquals("TIMEOUT", payload["webPageProbeErrorCode"])
            assertEquals("network", payload["webPageProbeFailureKind"])
            assertEquals("api_only_available", payload["status"])
            assertEquals(
                ReaderWebBridge.Capability.UNKNOWN,
                status.capabilities.getValue("webPage").state,
            )
            assertTrue(status.message.contains("网络层失败"))
            assertFalse("不得声称根路径返回了 HTTP 错误", status.message.contains("返回 HTTP"))
        }
    }

    /** 状态码已到但正文没读完：既不是「没有响应」，也不是普通 HTTP 错误，必须单独归类。 */
    @Test fun rootBodyTimeoutAfterStatusIsReportedAsBodyUnreadable() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/" -> MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody("<html><body>root</body></html>")
                        .setBodyDelay(3, TimeUnit.SECONDS)
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") -> okJson("""{"sources":[],"states":[]}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> okJson("""{"states":[],"sessionToken":null}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = timeoutBridge(server.url("/").toString()).status()
            val payload = status.toPayload()
            assertTrue(status.readerDetected)
            assertFalse(status.webPageReachable)
            assertEquals("状态码如实回传", 200, payload["webPageStatusCode"])
            assertEquals("TIMEOUT", payload["webPageProbeErrorCode"])
            assertEquals("body_unreadable", payload["webPageProbeFailureKind"])
            assertEquals("api_only_available", payload["status"])
            assertEquals(
                ReaderWebBridge.Capability.UNKNOWN,
                status.capabilities.getValue("webPage").state,
            )
            assertTrue("必须说明正文没读完、页面是否可用未确认", status.message.contains("未确认"))
            assertTrue("未确认时不能报笼统的网页不可用", payload["status"] != "web_page_unavailable")
        }
    }

    /** 调用超时压到 1.5s：根路径慢响应会失败，接口响应不受影响。 */
    private fun timeoutBridge(origin: String): ReaderWebBridge = ReaderWebBridge(
        ReaderWebClient(
            configProvider = { ReaderWebConfigStore.Config(origin, "", 1L) },
            clientFactory = {
                OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                    .callTimeout(1500, TimeUnit.MILLISECONDS).build()
            },
        ),
    )

    /** 状态码已到但正文没读完、又没有接口正面证据：报 web_page_probe_incomplete，不报网页不可用。 */
    @Test fun rootBodyTimeoutWithoutApiEvidenceIsProbeIncomplete() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/" -> MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody("<html><body>root</body></html>")
                        .setBodyDelay(3, TimeUnit.SECONDS)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = timeoutBridge(server.url("/").toString()).status()
            val payload = status.toPayload()
            assertFalse(status.readerDetected)
            assertFalse(status.webPageReachable)
            assertEquals(200, payload["webPageStatusCode"])
            assertEquals("body_unreadable", payload["webPageProbeFailureKind"])
            assertEquals("web_page_probe_incomplete", payload["status"])
            assertTrue(payload["status"] != "web_page_unavailable")
        }
    }

    /** 根路径网络层失败、接口侧另有响应（不是阅读接口）：报 web_page_unreachable，不笼统报网页不可用。 */
    @Test fun rootNetworkFailureWithUnrecognizedApiResponseIsNotBluntPageUnavailable() =
        kotlinx.coroutines.test.runTest {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path == "/" -> MockResponse().setHeader("Content-Type", "text/html")
                            .setBody("<html><body>slow</body></html>")
                            .setHeadersDelay(3, TimeUnit.SECONDS)
                        else -> MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody("<html><body>not the reader api</body></html>")
                    }
                }
                server.start()
                val status = timeoutBridge(server.url("/").toString()).status()
                val payload = status.toPayload()
                assertTrue("接口侧有响应", status.reachable)
                assertFalse(status.webPageReachable)
                assertFalse("不是阅读接口，不得算识别到", status.readerDetected)
                assertEquals(ReaderWebBridge.ApiProfile.MISMATCH, payload["apiProfile"])
                assertEquals("network", payload["webPageProbeFailureKind"])
                assertEquals("web_page_unreachable", payload["status"])
                assertTrue(payload["status"] != "web_page_unavailable")
                assertTrue(status.message.contains("根路径网络层失败"))
            }
        }

    /**
     * 报告的现场形态：root 200 是任意第三方 HTML 模板（HTML5UP「Forty」改装导航页），
     * 接口侧是 JSON 或 404。按源码，root 2xx 只能是 web_page_available，
     * 不允许因为 HTML 标题/模板不匹配就报 web_page_unavailable。
     */
    @Test fun arbitraryRootHtmlWithApiHtmlOr404StaysWebPageAvailable() = kotlinx.coroutines.test.runTest {
        val thirdPartyHtml = """
            <!DOCTYPE HTML>
            <html>
              <head>
                <title>Legado web 导航</title>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <link rel="stylesheet" href="assets/css/main.css" />
              </head>
              <body class="is-preload">
                <div id="wrapper"><header id="header"><h1>导航</h1></header></div>
                <script src="assets/js/jquery.min.js"></script>
                <script src="assets/js/main.js"></script>
              </body>
            </html>
        """.trimIndent()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/" -> MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody(thirdPartyHtml)
                    // 接口侧：HTML 首页（不是 JSON）
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") ->
                        MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(thirdPartyHtml)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString()).status()
            val payload = status.toPayload()
            assertTrue("任意 2xx HTML root 都是网页在线", status.webPageReachable)
            assertTrue("root 200 是网络层可达", status.reachable)
            assertEquals(200, payload["webPageStatusCode"])
            assertEquals("html", payload["webPageContentKind"])
            assertNull(payload["webPageProbeErrorCode"])
            assertEquals("none", payload["webPageProbeFailureKind"])
            assertEquals("web_page_available", payload["status"])
            assertEquals(ReaderWebBridge.ApiProfile.MISMATCH, payload["apiProfile"])
            assertFalse("root 200 不等于识别到官方接口", status.readerDetected)
            assertEquals("reader_web_page", status.allowedNextActions.first())
            assertTrue("不得报网页不可用", payload["status"] != "web_page_unavailable")
        }
    }

    /** 省略 Content-Type 的任意 HTML：仍按 2xx = 页面在线（不靠模板或响应头猜身份）。 */
    @Test fun root200WithoutContentTypeIsStillWebPageAvailable() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/" -> MockResponse().setBody("<html><body>no content type</body></html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString()).status()
            val payload = status.toPayload()
            assertTrue(status.webPageReachable)
            assertEquals(200, payload["webPageStatusCode"])
            assertEquals("html", payload["webPageContentKind"])
            assertEquals("web_page_available", payload["status"])
            assertEquals(ReaderWebBridge.ApiProfile.MISMATCH, status.apiProfile)
        }
    }

    // ---- 令牌 ----

    @Test fun tokenProtectedReaderWithoutSavedTokenIsActionable() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/" -> html()
                    else -> if (request.path!!.startsWith("/getJsSourceApiTokenRequired")) okJson("true")
                    else MockResponse().setResponseCode(401)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString(), token = "").status()
            assertEquals(true, status.tokenRequired)
            assertFalse(status.tokenSaved)
            assertTrue(status.reachable)
            assertTrue("网页根路径可打开", status.webPageReachable)
            assertFalse("没有令牌就是鉴权未就绪", status.authReady)
            assertEquals("auth_required", status.toPayload()["status"])
            // 先补令牌；网页能打开时也允许用户直接在页面里操作/登录
            assertEquals(
                listOf("reader_web_page", "reader_web_status"),
                status.allowedNextActions,
            )
            // 令牌开关与根路径都是公开 GET：不得携带任何令牌头
            val requests = generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }.take(2).toList()
            assertEquals("/", requests.first().path)
            requests.forEach {
                assertNull(it.getHeader("X-Legado-Token"))
                assertNull(it.getHeader("Authorization"))
            }
        }
    }

    /** 已保存的令牌被阅读拒绝：接口存在但不可用；网页仍可打开时保留页面工具。 */
    @Test fun wrongSavedTokenIsAuthRequiredNotReady() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/" -> html()
                    else -> if (request.path!!.startsWith("/getJsSourceApiTokenRequired")) okJson("true")
                    else MockResponse().setResponseCode(401)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString(), token = "wrong-token").status()
            assertTrue("401 是响应，不能判离线", status.reachable)
            assertTrue("网页可打开", status.webPageReachable)
            assertTrue("公开接口可读即已识别到官方接口", status.readerDetected)
            assertTrue(status.authRequired)
            assertFalse(status.authReady)
            assertEquals("auth_required", status.toPayload()["status"])
            assertEquals(
                ReaderWebBridge.Capability.UNAUTHORIZED,
                status.capabilities.getValue("check").state,
            )
            assertEquals(
                listOf("reader_web_page", "reader_web_status"),
                status.allowedNextActions,
            )
            assertFalse(status.allowedNextActions.contains("reader_web_sync_source"))
            assertFalse(status.allowedNextActions.contains("reader_web_verify"))
            assertTrue(status.message.contains("鉴权"))
            assertTrue("必须说明可在网页里自行登录", status.message.contains("页面里登录"))
            assertFalse("不得回显令牌", status.toPayload().toString().contains("wrong-token"))
        }
    }

    /** 所有路由都要求 Bearer 且未填令牌：网页登录可行，但不得断言不是阅读。 */
    @Test fun unauthorizedBearerIsNotReportedAsNotReaderOrOffline() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer")
            }
            server.start()
            val status = bridge(server.url("/").toString(), token = "").status()
            val payload = status.toPayload()
            assertTrue("HTTP 401 也是响应，不得判离线", status.reachable)
            assertTrue(status.bearerAuth)
            assertEquals(true, status.tokenRequired)
            assertTrue(status.authRequired)
            assertFalse(status.authReady)
            assertEquals("auth_required", payload["status"])
            assertFalse("不得断言不是阅读", payload["status"] == "not_reader")
            assertFalse("不得报离线", payload["status"] == "unreachable")
            assertEquals("根路径要求登录页", "auth", status.webPageContentKind)
            assertEquals(
                ReaderWebBridge.Capability.UNAUTHORIZED,
                status.capabilities.getValue("webPage").state,
            )
            // 401 保留 auth_required，但允许用户直接在 Web 页面里自行登录
            assertEquals(
                listOf("reader_web_page", "reader_web_status"),
                status.allowedNextActions,
            )
            assertTrue("必须说明已响应但需要鉴权", status.message.contains("已响应") && status.message.contains("鉴权"))
            assertTrue("必须明确不能据此断言不是阅读", status.message.contains("不代表地址不是阅读"))
        }
    }

    /** 令牌正确时的对照：鉴权就绪、能力可用、下一步是写入/检验。 */
    @Test fun correctTokenMakesAuthReadyAndRecommendsSyncThenVerify() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/" -> html()
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("true")
                    request.getHeader("X-Legado-Token") != "good-token" ->
                        MockResponse().setResponseCode(401)
                    request.path!!.startsWith("/getBookSourcesForManagement") -> okJson("""{"sources":[],"states":[]}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> okJson("""{"states":[],"sessionToken":null}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString(), token = "good-token").status()
            assertTrue(status.reachable)
            assertTrue(status.webPageReachable)
            assertTrue(status.readerDetected)
            assertTrue(status.authRequired)
            assertTrue("令牌正确时应视为鉴权就绪", status.authReady)
            assertEquals("ok", status.toPayload()["status"])
            assertEquals(ReaderWebBridge.ApiProfile.STRUCTURED, status.apiProfile)
            assertEquals(
                ReaderWebBridge.Capability.SUPPORTED,
                status.capabilities.getValue("check").state,
            )
            assertTrue(status.allowedNextActions.contains("reader_web_sync_source"))
            assertTrue(status.allowedNextActions.contains("reader_web_verify"))
            assertTrue(status.allowedNextActions.contains("reader_web_search"))
        }
    }

    @Test fun managementAuthFailureIsNotMisreportedAsUnsupported() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/" -> html()
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("true")
                    else -> MockResponse().setResponseCode(401)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString(), token = "wrong").status()
            assertEquals(
                ReaderWebBridge.Capability.UNAUTHORIZED,
                status.capabilities.getValue("check").state,
            )
            assertTrue(status.capabilities.getValue("check").detail.contains("令牌"))
            assertFalse(status.capabilities.getValue("check").state == ReaderWebBridge.Capability.UNSUPPORTED)
        }
    }

    @Test fun gatewayFailureIsUnknownNotUnsupported() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/" -> html()
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    else -> MockResponse().setResponseCode(503)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString()).status()
            // 503 是临时故障：能力未知，绝不能据此让模型降级
            assertEquals(
                ReaderWebBridge.Capability.UNKNOWN,
                status.capabilities.getValue("check").state,
            )
            assertTrue("网页仍可操作", status.webPageReachable)
        }
    }

    // ---- 回显与 revision ----

    @Test fun loopbackEchoKeepsActualPortAndNeverLeaksToken() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/") html() else MockResponse().setResponseCode(404)
            }
            server.start()
            val token = "super-secret-token"
            val status = bridge(server.url("/").toString(), token = token).status()
            val payload = status.toPayload()
            val text = payload.toString()
            // 允许回显回环与实际端口：模型必须知道当前保存的是哪个端口
            assertEquals("http://127.0.0.1:${server.port}", payload["originEcho"])
            assertFalse("不得回显令牌", text.contains(token))
            assertFalse("不得回显明文 Authorization", text.contains("Bearer"))
            assertEquals(
                setOf("webPage", "read", "check", "write", "legacyDebug", "search"),
                (payload["capabilities"] as Map<*, *>).keys,
            )
            assertEquals("reader_web_probe", payload["evidenceScope"])
            // 纯功能标志：本构建装了网页会话工具；不含端口/日期/个人信息
            assertEquals(true, payload["webUiSessionSupported"])
            assertEquals(ReaderWebBridge.WEB_UI_SESSION_SUPPORTED, status.webUiSessionSupported)
        }
    }

    @Test fun privateHostIsMaskedButPortIsKept() {
        // 纯回显逻辑：不得真的去连任何私网地址
        val origin = ReaderWebOrigin.normalize("http://192.168.1.8:1162")
        val echo = ReaderWebBridge.originEcho(origin)
        assertEquals("http://<已隐藏>:1162", echo)
        assertFalse(echo!!.contains("192.168.1.8"))
        assertEquals("https://<已隐藏>:8443", ReaderWebBridge.originEcho("https://10.20.30.40:8443"))
        assertEquals("http://<已隐藏>:80", ReaderWebBridge.originEcho("http://172.16.5.9"))
        assertNull(ReaderWebBridge.originEcho("not a url"))
        assertEquals(
            "http://127.0.0.1:1162",
            ReaderWebBridge.originEcho(ReaderWebOrigin.normalize("http://127.0.01:1162")),
        )
        // 其它回环主机是不同主机：回显保留实际主机，不改写成 .1
        assertEquals(
            "http://127.0.0.2:1162",
            ReaderWebBridge.originEcho(ReaderWebOrigin.normalize("http://127.0.0.2:1162")),
        )
    }

    @Test fun configRevisionIsEchoedAndTracksSavedValue() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/") html() else MockResponse().setResponseCode(404)
            }
            server.start()
            val first = ReaderWebBridge(client(server.url("/").toString(), revision = 1L)).status()
            assertEquals(1L, first.toPayload()["configRevision"])
            val second = ReaderWebBridge(client(server.url("/").toString(), revision = 2L)).status()
            assertEquals(2L, second.toPayload()["configRevision"])
            // revision 变化才是「用户改了配置」；重复保存同一值不推进
            assertEquals(1L, ReaderWebConfigRevision.next(1L, changed = false))
            assertEquals(2L, ReaderWebConfigRevision.next(1L, changed = true))
            // null 与空串都表示「没有令牌」，不算变化
            assertFalse(
                ReaderWebConfigRevision.changed("http://127.0.0.1:1162", null, "http://127.0.0.1:1162", ""),
            )
            assertTrue(
                ReaderWebConfigRevision.changed("http://127.0.0.1:1162", null, "http://127.0.0.1:1122", null),
            )
            assertTrue(
                ReaderWebConfigRevision.changed("http://127.0.0.1:1162", "old", "http://127.0.0.1:1162", "new"),
            )
        }
    }

    @Test fun bearerChallengedProbeMarksBearerAndNeverPrefetches() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/") return html()
                    if (request.getHeader("Authorization") == "Bearer good-token") {
                        return when {
                            request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                            else -> okJson("""{"sources":[],"states":[]}""")
                        }
                    }
                    return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer")
                }
            }
            server.start()
            val status = bridge(server.url("/").toString(), token = "good-token").status()
            assertTrue(status.bearerAuth)
            val requests = generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }.toList()
            assertEquals("根路径最先探测", "/", requests[0].path)
            assertNull("根路径不得预发令牌", requests[0].getHeader("Authorization"))
            assertNull(requests[0].getHeader("X-Legado-Token"))
            val apiRequests = requests.drop(1)
            assertNull("首次公开探测不得预发令牌", apiRequests[0].getHeader("Authorization"))
            assertTrue(
                "挑战后必须在同一 origin 带 Bearer 重试",
                apiRequests.any { it.getHeader("Authorization") == "Bearer good-token" },
            )
        }
    }

    // ---- 根路径诊断（webPageProbeDiagnostic） ----

    /**
     * 现场报告：根路径偶发 UNREACHABLE，同秒其它通道却成功。诊断必须能说清
     * 「哪条固定路径、卡在哪个相位、什么异常类别、试了几次、有没有复用连接」，
     * 但**不能**带出 host/端口/令牌；也不能把成功 WebView/其它接口的证据算成根路径成功。
     */
    @Test fun webPageProbeDiagnosticExposesPhaseAndCategoryWithoutAddress() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                // 收到请求后立刻断开、不发响应头：根路径这是网络层失败，不是 HTTP 状态错误
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
            server.start()
            val token = "secret-token-value"
            val status = bridge(server.url("/").toString(), token = token).status()
            val payload = status.toPayload()
            assertFalse(status.webPageReachable)
            assertEquals("network", payload["webPageProbeFailureKind"])
            val diagnostic = payload["webPageProbeDiagnostic"] as Map<*, *>
            assertEquals("GET", diagnostic["method"])
            assertEquals("只暴露固定根路径，不带查询串", "/", diagnostic["path"])
            assertEquals("web_page", diagnostic["stage"])
            assertEquals(ReaderWebRequestDiagnostic.Phase.HEADERS, diagnostic["phase"])
            assertTrue("必须给出可判定的异常类别", (diagnostic["exceptionCategory"] as String).isNotEmpty())
            assertTrue("必须记录实际尝试次数", (diagnostic["attempts"] as Int) >= 1)
            // 只约束诊断本身（originEcho 按设计会回显回环端口，那是另一条受控字段）
            val diagnosticText = diagnostic.toString()
            assertFalse("诊断不得回显 host", diagnosticText.contains(server.hostName))
            assertFalse("诊断不得回显端口", diagnosticText.contains(server.port.toString()))
            assertFalse("诊断不得回显令牌", diagnosticText.contains(token))
            assertFalse("整体回包不得回显令牌", payload.toString().contains(token))
        }
    }

    @Test fun successfulRootProbeStillCarriesDiagnosticAndDoesNotProveApi() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/" -> html("<html><body>nav</body></html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString()).status()
            val diagnostic = status.toPayload()["webPageProbeDiagnostic"] as Map<*, *>
            assertEquals("GET", diagnostic["method"])
            assertEquals("/", diagnostic["path"])
            assertEquals("成功交换的相位是 none", ReaderWebRequestDiagnostic.Phase.NONE, diagnostic["phase"])
            assertEquals(200, diagnostic["httpStatus"])
            assertNull("成功没有异常类别", diagnostic["exceptionCategory"])
            // 根路径成功绝不能反过来证明官方接口可用
            assertFalse(status.readerDetected)
            assertEquals(ReaderWebBridge.ApiProfile.MISMATCH, status.apiProfile)
            assertEquals("web_page_available", status.toPayload()["status"])
        }
    }

    @Test fun rootDiagnosticIsSeparateFromApiMismatch() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/" -> MockResponse().setResponseCode(404)
                    request.path!!.startsWith("/getJsSourceApiTokenRequired") -> okJson("false")
                    request.path!!.startsWith("/getBookSourcesForManagement") -> okJson("""{"sources":[],"states":[]}""")
                    request.path!!.startsWith("/getBookSourceCheckStates") -> okJson("""{"states":[],"sessionToken":null}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val status = bridge(server.url("/").toString()).status()
            val payload = status.toPayload()
            assertEquals("api_only_available", payload["status"])
            // 接口有正面证据，但根路径诊断只描述根路径这一条 GET 的 HTTP 结果
            val diagnostic = payload["webPageProbeDiagnostic"] as Map<*, *>
            assertEquals("/", diagnostic["path"])
            assertEquals(404, diagnostic["httpStatus"])
            assertEquals(ReaderWebRequestDiagnostic.Phase.NONE, diagnostic["phase"])
            assertNull("HTTP 404 不是网络异常", diagnostic["exceptionCategory"])
            assertTrue("接口侧仍可有独立结论", status.readerDetected)
        }
    }
}
