package com.mina.legadostudio.reader

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `reader_web_sync_source`：固定同源 lookups/save + 回读确认。
 *
 * 覆盖：不存在才写、相同不写、冲突拒写、显式覆写、写入后回读不符、令牌只发受保护/挑战路由。
 * 全部走 MockWebServer，不触碰任何真实局域网地址。
 */
class ReaderWebSyncTest {

    private val url = "https://book.test/source"

    private fun source(url: String = this.url, name: String = "测试源", search: String = "https://book.test/search?q={%s}") =
        JsonParser.parseString(
            """{"bookSourceName":"$name","bookSourceUrl":"$url","bookSourceType":0,"searchUrl":"$search"}"""
        ).asJsonObject

    private fun okJson(data: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody("""{"isSuccess":true,"errorMsg":"","data":$data}""")

    private fun missingJson(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody("""{"isSuccess":false,"errorMsg":"未找到源","data":null}""")

    private fun client(baseUrl: String, token: String = "", timeoutSec: Long = 5): ReaderWebClient =
        ReaderWebClient(
            configProvider = { ReaderWebConfigStore.Config(baseUrl, token) },
            clientFactory = {
                OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .callTimeout(timeoutSec, TimeUnit.SECONDS)
                    .build()
            },
        )

    private fun sync(
        baseUrl: String,
        local: JsonObject,
        token: String = "",
    ): Pair<ReaderWebSourceSync, AtomicReference<String?>> {
        val captured = AtomicReference<String?>(null)
        val service = ReaderWebSourceSync(
            client(baseUrl, token),
            sourceProvider = { id -> captured.set(id); local.toString() },
        )
        return service to captured
    }

    /** 记录请求的调度器：`present` 为当前「阅读里」的书源，save 会替换它。 */
    private fun readerDispatcher(
        server: MockWebServer,
        initial: JsonObject?,
        saveWrites: Boolean = true,
    ): Dispatcher {
        val stored = AtomicReference<JsonObject?>(initial)
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/getBookSource?") -> {
                        val current = stored.get()
                        if (current == null) missingJson() else okJson(current.toString())
                    }
                    path.startsWith("/saveBookSource") -> {
                        if (saveWrites) {
                            stored.set(JsonParser.parseString(request.body.readUtf8()).asJsonObject)
                        }
                        // 官方成功回包 data 可能是空串
                        MockResponse()
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody("""{"isSuccess":true,"errorMsg":"","data":""}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @Test fun absentSourceIsWrittenAndConfirmed() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = readerDispatcher(server, initial = null)
            server.start()
            val (service, captured) = sync(server.url("/").toString(), source())
            val result = service.sync("p1", overwrite = false)
            assertEquals("written", result.status)
            assertEquals("p1", captured.get())
            assertTrue(result.written)
            assertTrue(result.confirmed)
            assertEquals(url, result.bookSourceUrl)
            assertEquals("reader_web_verify", result.toPayload()["nextAction"])
            assertTrue(result.mismatchedFields.isEmpty())

            val paths = generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }.map { it.path!! }.toList()
            // 先精确预检，再写入，最后回读确认
            assertTrue(paths[0].startsWith("/getBookSource?url="))
            assertTrue(paths.any { it == "/saveBookSource" })
            assertEquals(3, paths.size)
        }
    }

    @Test fun identicalSourceIsNotWrittenAgain() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = readerDispatcher(server, initial = source())
            server.start()
            val (service, _) = sync(server.url("/").toString(), source())
            val result = service.sync("p1", overwrite = false)
            assertEquals("already_present", result.status)
            assertFalse(result.written)
            assertTrue(result.confirmed)
            assertEquals(result.localHash, result.remoteHash)
            // 只有一次预检请求，没有 POST
            assertEquals(1, server.requestCount)
            // 已确认内容一致：可以直接进入阅读端检验
            assertEquals("reader_web_verify", result.toPayload()["nextAction"])
        }
    }

    @Test fun differingSourceIsRejectedWithoutOverwriteAndLeaksNoValues() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = readerDispatcher(
                server,
                initial = source(name = "阅读里的名字", search = "https://reader.test/s?q={%s}"),
            )
            server.start()
            val (service, _) = sync(server.url("/").toString(), source(name = "本地名字"))
            val result = service.sync("p1", overwrite = false)
            assertEquals("conflict", result.status)
            assertFalse(result.written)
            assertFalse(result.confirmed)
            assertTrue(result.differentTopLevelFields.contains("bookSourceName"))
            assertTrue(result.differentTopLevelFields.contains("searchUrl"))
            assertFalse("冲突只给字段名，不给规则值", result.differentTopLevelFields.contains("ruleSearch"))
            assertTrue(result.localHash != result.remoteHash)
            // 冲突时绝不发 POST
            assertEquals(1, server.requestCount)
            val payload = result.toPayload()
            val text = payload.toString()
            assertFalse("不得回显书源规则内容", text.contains("reader.test/s"))
            assertFalse("不得回显书源规则内容", text.contains("本地名字"))
        }
    }

    @Test fun explicitOverwriteWritesAndReadsBack() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = readerDispatcher(server, initial = source(name = "旧名字"))
            server.start()
            val (service, _) = sync(server.url("/").toString(), source(name = "新名字"))
            val result = service.sync("p1", overwrite = true)
            assertEquals("written", result.status)
            assertTrue(result.written)
            assertTrue(result.confirmed)
            assertTrue(result.differentTopLevelFields.contains("bookSourceName"))
            val save = generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }
                .firstOrNull { it.path == "/saveBookSource" }
            assertEquals("新名字", JsonParser.parseString(save!!.body.readUtf8()).asJsonObject.get("bookSourceName").asString)
        }
    }

    /**
     * 本地新成品省略了 `jsLib`，阅读旧源仍带非空 `jsLib`：这是实质冲突，
     * 不能因为本地缺键就判 `already_present` 而跳过同步。
     */
    @Test fun remoteSubstantiveFieldOmittedLocallyIsAConflict() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val remote = JsonParser.parseString(
                """{"bookSourceName":"测试源","bookSourceUrl":"$url","bookSourceType":0,
                   "searchUrl":"https://book.test/search?q={%s}",
                   "jsLib":"$.require('https://cdn.test/lib.js')",
                   "ruleContent":{"content":"@css:.content"}}"""
            ).asJsonObject
            server.dispatcher = readerDispatcher(server, initial = remote)
            server.start()
            // 本地成品只有最基础字段，没有 jsLib / ruleContent
            val (service, _) = sync(server.url("/").toString(), source())
            val result = service.sync("p1", overwrite = false)
            assertEquals("conflict", result.status)
            assertFalse(result.confirmed)
            assertTrue(
                "远端残留的非空 jsLib 必须算冲突字段",
                result.differentTopLevelFields.contains("jsLib"),
            )
            assertTrue(
                "远端残留的非空规则必须算冲突字段",
                result.differentTopLevelFields.contains("ruleContent"),
            )
            assertTrue(result.remoteOnlyTopLevelFields.contains("jsLib"))
            // 必须零写入，且不回显规则/脚本内容
            assertTrue(generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }
                .none { it.path == "/saveBookSource" })
            assertFalse(result.toPayload().toString().contains("cdn.test/lib.js"))
            assertEquals(null, result.toPayload()["nextAction"])
        }
    }

    /** 阅读补的默认空值（空串/false/0/空对象）不构成冲突。 */
    @Test fun readerDefaultPaddingIsNotAConflict() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val remote = JsonParser.parseString(
                """{"bookSourceName":"测试源","bookSourceUrl":"$url","bookSourceType":0,
                   "searchUrl":"https://book.test/search?q={%s}",
                   "jsLib":"","header":"","loginUrl":"","enabled":true,
                   "ruleContent":{"content":""},"ruleSearch":{"bookList":"","checkKeyWord":""},
                   "exploreUrl":"","respondTime":0,"lastUpdateTime":1}"""
            ).asJsonObject
            server.dispatcher = readerDispatcher(server, initial = remote)
            server.start()
            val (service, _) = sync(server.url("/").toString(), source())
            val result = service.sync("p1", overwrite = false)
            assertEquals("already_present", result.status)
            assertTrue(result.confirmed)
            assertTrue(result.differentTopLevelFields.isEmpty())
            assertEquals(1, server.requestCount)
            assertEquals("reader_web_verify", result.toPayload()["nextAction"])
        }
    }

    @Test fun onlyOmittedLocalCookieJarAndRemoteTrueAreNormalized() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val remote = source().apply { addProperty("enabledCookieJar", true) }
            server.dispatcher = readerDispatcher(server, initial = remote)
            server.start()
            val (service, _) = sync(server.url("/").toString(), source())
            val result = service.sync("p1", false)
            assertEquals("already_present", result.status)
            assertTrue(result.presentInReader)
            assertEquals(listOf("enabledCookieJar"), result.toPayload()["normalizedFields"])
            assertEquals(listOf("enabledCookieJar"), result.remoteOnlyTopLevelFields)
            assertEquals(1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.dispatcher = readerDispatcher(server, initial = source().apply { addProperty("enabledCookieJar", true) })
            server.start()
            val local = source().apply { addProperty("enabledCookieJar", false) }
            val (service, _) = sync(server.url("/").toString(), local)
            val result = service.sync("p1", false)
            assertEquals("conflict", result.status)
            assertTrue(result.presentInReader)
            assertFalse(result.confirmed)
            assertTrue(result.normalizedFields.isEmpty())
            assertEquals(listOf("enabledCookieJar"), result.differentTopLevelFields)
        }
        MockWebServer().use { server ->
            server.dispatcher = readerDispatcher(server, initial = source().apply { addProperty("enabledCookieJar", false) })
            server.start()
            val (service, _) = sync(server.url("/").toString(), source())
            val result = service.sync("p1", false)
            assertEquals("conflict", result.status)
            assertEquals(listOf("enabledCookieJar"), result.differentTopLevelFields)
            assertTrue(result.normalizedFields.isEmpty())
        }
        MockWebServer().use { server ->
            server.dispatcher = readerDispatcher(server, initial = source().apply { addProperty("otherFlag", true) })
            server.start()
            val (service, _) = sync(server.url("/").toString(), source())
            assertEquals(listOf("otherFlag"), service.sync("p1", false).differentTopLevelFields)
        }
    }

    @Test fun readbackCookieJarTrueIsConfirmed() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getBookSource?") && server.requestCount == 1 -> missingJson()
                    request.path!!.startsWith("/getBookSource?") -> okJson(source().apply { addProperty("enabledCookieJar", true) }.toString())
                    request.path == "/saveBookSource" -> okJson("\"\"")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val (service, _) = sync(server.url("/").toString(), source())
            val result = service.sync("p1", false)
            assertEquals("written", result.status)
            assertEquals(listOf("enabledCookieJar"), result.normalizedFields)
        }
    }

    /** 写后回读：阅读保留了旧源的 jsLib，本地没提交 → 必须报未确认，且不给下一步。 */
    @Test fun readbackKeepsRemoteSubstantiveFieldAsUnconfirmed() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            // save 只接受不改：回读仍是带 jsLib 的旧源
            val stale = JsonParser.parseString(
                """{"bookSourceName":"测试源","bookSourceUrl":"$url","bookSourceType":0,
                   "searchUrl":"https://book.test/search?q={%s}",
                   "jsLib":"$.require('https://cdn.test/lib.js')"}"""
            ).asJsonObject
            server.dispatcher = readerDispatcher(server, initial = stale, saveWrites = false)
            server.start()
            val (service, _) = sync(server.url("/").toString(), source())
            val result = service.sync("p1", overwrite = true)
            assertEquals("written_unconfirmed", result.status)
            assertTrue(result.written)
            assertFalse(result.confirmed)
            assertTrue(
                "回读必须发现远端残留的 jsLib",
                result.mismatchedFields.contains("jsLib"),
            )
            assertEquals(null, result.toPayload()["nextAction"])
        }
    }

    @Test fun readbackMismatchIsReportedAsUnconfirmed() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            // save 接受请求但不真正落库：回读仍是旧值
            server.dispatcher = readerDispatcher(server, initial = source(name = "旧名字"), saveWrites = false)
            server.start()
            val (service, _) = sync(server.url("/").toString(), source(name = "新名字"))
            val result = service.sync("p1", overwrite = true)
            assertEquals("written_unconfirmed", result.status)
            assertTrue("确实提交过写入", result.written)
            assertFalse("HTTP 200 不等于已生效", result.confirmed)
            assertTrue(result.mismatchedFields.contains("bookSourceName"))
            // 未确认生效时绝不引导下一步检验
            assertEquals(null, result.toPayload()["nextAction"])
            assertFalse("回读不符不能报成功", result.status == "written")
        }
    }

    /** 嵌套对象里的 `enabled` 是规则内容，不能被当成阅读元数据而忽略。 */
    @Test fun nestedRuleEnabledIsComparedNotTreatedAsMetadata() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val remote = JsonParser.parseString(
                """{"bookSourceName":"测试源","bookSourceUrl":"$url","bookSourceType":0,
                   "searchUrl":"https://book.test/search?q={%s}",
                   "ruleSearch":{"bookList":"@css:.list","enabled":true}}"""
            ).asJsonObject
            server.dispatcher = readerDispatcher(server, initial = remote)
            server.start()
            val local = JsonParser.parseString(
                """{"bookSourceName":"测试源","bookSourceUrl":"$url","bookSourceType":0,
                   "searchUrl":"https://book.test/search?q={%s}",
                   "ruleSearch":{"bookList":"@css:.list","enabled":false}}"""
            ).asJsonObject
            val (service, _) = sync(server.url("/").toString(), local)
            val result = service.sync("p1", overwrite = false)
            // 只有嵌套的 enabled 不同：不能判成 already_present 而跳过写入
            assertEquals("conflict", result.status)
            assertTrue(result.differentTopLevelFields.contains("ruleSearch"))
            // 冲突不得回显规则内容
            assertFalse(result.toPayload().toString().contains("@css:.list"))
        }
    }

    @Test fun canonicalStripsMetadataOnlyAtTopLevel() {
        val service = ReaderWebSourceSync(client("http://127.0.0.1:1"), sourceProvider = { null })
        val withEnabled = JsonParser.parseString(
            """{"bookSourceUrl":"$url","ruleSearch":{"enabled":true,"bookList":"a"}}"""
        )
        val withoutEnabled = JsonParser.parseString(
            """{"bookSourceUrl":"$url","ruleSearch":{"bookList":"a"}}"""
        )
        assertFalse(
            "嵌套 enabled 属于规则内容，必须参与比较",
            service.canonical(withEnabled) == service.canonical(withoutEnabled),
        )
        // 顶层元数据仍然忽略
        val topA = JsonParser.parseString("""{"bookSourceUrl":"$url","lastUpdateTime":1,"enabled":true}""")
        val topB = JsonParser.parseString("""{"bookSourceUrl":"$url","lastUpdateTime":9,"enabled":false}""")
        assertEquals(service.canonical(topA), service.canonical(topB))
    }

    /** 不在旧 KEY_FIELDS 白名单里的字段被阅读端丢弃，也必须报未确认。 */
    @Test fun nonKeyFieldDriftIsDetectedOnReadback() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/getBookSource?") -> okJson(source().toString())
                        path == "/saveBookSource" -> {
                            // 阅读悄悄丢掉了 bookSourceComment 之外的一个普通字段
                            okJson("\"\"")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val local = JsonParser.parseString(
                """{"bookSourceName":"测试源","bookSourceUrl":"$url","bookSourceType":0,
                   "searchUrl":"https://book.test/search?q={%s}",
                   "customField":"保留我","ruleBookInfo":{"name":"@css:.title"}}"""
            ).asJsonObject
            val (service, _) = sync(server.url("/").toString(), local)
            val result = service.sync("p1", overwrite = true)
            assertEquals("written_unconfirmed", result.status)
            assertFalse(result.confirmed)
            assertTrue("必须报出被丢弃的普通字段", result.mismatchedFields.contains("customField"))
            assertTrue("必须报出缺失的嵌套规则", result.mismatchedFields.contains("ruleBookInfo"))
            assertEquals(null, result.toPayload()["nextAction"])
        }
    }

    /** 预检返回空对象/缺 bookSourceUrl：不能算「不存在」，有零写入就停。 */
    @Test fun unusableLookupShapeStopsBeforeWriting() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/getBookSource?") -> okJson("{}")
                        path == "/saveBookSource" -> okJson("\"\"")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val (service, _) = sync(server.url("/").toString(), source())
            val error = runCatching { service.sync("p1", overwrite = false) }
                .exceptionOrNull() as ReaderWebSourceSync.SyncException
            assertEquals("INCOMPATIBLE", error.code)
            // 读不懂的形状必须零写入：只有预检请求，没有任何 POST
            assertTrue(generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }
                .none { it.path == "/saveBookSource" })
        }
    }

    @Test fun saveBusinessFailureIsAnErrorNotASuccess() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getBookSource?") -> missingJson()
                    request.path == "/saveBookSource" -> MockResponse()
                        .setHeader("Content-Type", "application/json; charset=utf-8")
                        .setBody("""{"isSuccess":false,"errorMsg":"保存失败：书源校验不通过","data":null}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val (service, _) = sync(server.url("/").toString(), source())
            val error = runCatching { service.sync("p1", overwrite = false) }
                .exceptionOrNull() as ReaderWebSourceSync.SyncException
            assertEquals("BUSINESS_ERROR", error.code)
            assertTrue(error.message.orEmpty().contains("校验不通过"))
        }
    }

    @Test fun missingProjectAndMissingUrlFailBeforeNetwork() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.start()
            val noProject = ReaderWebSourceSync(client(server.url("/").toString()), sourceProvider = { null })
            val e1 = runCatching { noProject.sync("nope", false) }
                .exceptionOrNull() as ReaderWebSourceSync.SyncException
            assertEquals("PROJECT_NOT_FOUND", e1.code)

            val noUrl = ReaderWebSourceSync(
                client(server.url("/").toString()),
                sourceProvider = { """{"bookSourceName":"没有URL"}""" },
            )
            val e2 = runCatching { noUrl.sync("p1", false) }
                .exceptionOrNull() as ReaderWebSourceSync.SyncException
            assertEquals("MISSING_BOOK_SOURCE_URL", e2.code)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun tokenPolicyStaysOnConfiguredOrigin() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                second.enqueue(okJson("""{"bookSourceName":"别的服务"}"""))
                first.start()
                second.start()
                var current = ReaderWebConfigStore.Config(first.url("/").toString().removeSuffix("/"), "tok-a")
                val stored = AtomicReference<JsonObject?>(null)
                first.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.path.orEmpty()
                        // 第一次预检之后就「用户改了地址」：后续写入/回读仍必须留在原 origin
                        current = ReaderWebConfigStore.Config(second.url("/").toString().removeSuffix("/"), "tok-b")
                        return when {
                            path.startsWith("/getBookSource?") -> stored.get()?.let { okJson(it.toString()) } ?: missingJson()
                            path == "/saveBookSource" -> {
                                stored.set(JsonParser.parseString(request.body.readUtf8()).asJsonObject)
                                okJson("\"\"")
                            }
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                val service = ReaderWebSourceSync(
                    ReaderWebClient(configProvider = { current }, clientFactory = { OkHttpClient() }),
                    sourceProvider = { source().toString() },
                )
                val result = service.sync("p1", overwrite = false)
                assertEquals("written", result.status)
                assertEquals("第二个服务不得收到任何请求", 0, second.requestCount)
                val requests = generateSequence { first.takeRequest(1, TimeUnit.MILLISECONDS) }.toList()
                assertTrue(requests.isNotEmpty())
                // 公开读预检不带令牌；写路由是受保护路由，只把开始时快照的令牌发到同一 origin
                requests.forEach { req ->
                    if (req.path!!.startsWith("/getBookSource?")) {
                        assertNull(req.getHeader("X-Legado-Token"))
                        assertNull(req.getHeader("Authorization"))
                    } else {
                        assertEquals("tok-a", req.getHeader("X-Legado-Token"))
                        assertNull("不得升级为 Bearer（未收到挑战）", req.getHeader("Authorization"))
                    }
                }
            }
        }
    }

    @Test fun canonicalAndHashIgnoreReaderMetadataAndKeyOrder() {
        val a = JsonParser.parseString("""{"bookSourceUrl":"$url","bookSourceName":"A","enabled":true}""").asJsonObject
        val b = JsonParser.parseString("""{"bookSourceName":"A","enabled":true,"bookSourceUrl":"$url","lastUpdateTime":123}""").asJsonObject
        val service = ReaderWebSourceSync(
            client("http://127.0.0.1:1"),
            sourceProvider = { null },
        )
        assertEquals(service.canonical(a), service.canonical(b))
        assertEquals(service.hashOf(a), service.hashOf(b))
        assertFalse(service.hashOf(a) == service.hashOf(source(name = "B")))
    }

    // ---- 真实 HTTP 方法 + 随机端口：只有 POST 才能写入 ----

    /**
     * 严格调度器：`GET /saveBookSource` 一律 405（+纯文本），只有 `POST` 才真正写入并回 200。
     *
     * 现场误判是「同步用 GET /saveBookSource」：如果实现真的用 GET 写，这里必然失败，
     * 而不是像宽松桩那样把任意方法都当成功。
     */
    private fun strictMethodDispatcher(stored: AtomicReference<JsonObject?>): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/getBookSource?") ->
                        stored.get()?.let { okJson(it.toString()) } ?: missingJson()
                    path == "/saveBookSource" && request.method == "POST" -> {
                        stored.set(JsonParser.parseString(request.body.readUtf8()).asJsonObject)
                        MockResponse()
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody("""{"isSuccess":true,"errorMsg":"","data":""}""")
                    }
                    // 用 GET/其它方法打写入路由：阅读不会这样保存，服务端只回方法不允许
                    path == "/saveBookSource" -> MockResponse()
                        .setResponseCode(405)
                        .setHeader("Allow", "POST")
                        .setHeader("Content-Type", "text/plain; charset=utf-8")
                        .setBody("Method Not Allowed")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

    @Test fun syncUsesGetLookupThenPostSaveThenGetReadbackOnRandomPort() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val stored = AtomicReference<JsonObject?>(null)
            server.dispatcher = strictMethodDispatcher(stored)
            server.start()
            val local = source()
            val (service, _) = sync(server.url("/").toString(), local)
            val result = service.sync("p1", overwrite = false)

            assertEquals("written", result.status)
            assertTrue(result.confirmed)
            assertTrue("确实发出过写入", result.writeAttempted)
            assertEquals(ReaderWebSourceSync.WriteOutcome.ACCEPTED, result.writeOutcome)

            val requests = generateSequence { server.takeRequest(2, TimeUnit.SECONDS) }.toList()
            assertEquals("lookup → save → readback 三条，不得多条/漏条", 3, requests.size)
            assertEquals("GET", requests[0].method)
            assertTrue(requests[0].path!!.startsWith("/getBookSource?url="))
            assertEquals("写入必须是 POST（不是 GET）", "POST", requests[1].method)
            assertEquals("/saveBookSource", requests[1].path)
            assertEquals("GET", requests[2].method)
            assertTrue(requests[2].path!!.startsWith("/getBookSource?url="))

            // 请求体是本地成品原文（逐字符同 JSON）
            val body = JsonParser.parseString(requests[1].body.readUtf8()).asJsonObject
            assertEquals(local, body)

            // 端口必须原样是 MockWebServer 的随机端口：没有默认端口回落/改写
            requests.forEach { assertEquals(server.port, it.requestUrl!!.port) }

            // 成功回包也列出实际请求：模型不必再猜方法与阶段
            val payload = result.toPayload()
            val trace = payload["trace"] as List<*>
            assertEquals(3, trace.size)
            assertEquals("GET /getBookSource [lookup] HTTP 200", trace[0])
            assertEquals("POST /saveBookSource [save] HTTP 200", trace[1])
            assertEquals("GET /getBookSource [readback] HTTP 200", trace[2])
            val rows = payload["requests"] as List<*>
            assertEquals(listOf("lookup", "save", "readback"), rows.map { (it as Map<*, *>)["stage"] })
            assertEquals(listOf("GET", "POST", "GET"), rows.map { (it as Map<*, *>)["method"] })
            assertNull("成功路径没有失败诊断", payload["diagnostic"])
            // 回包不含地址/令牌
            assertFalse(payload.toString().contains(server.hostName))
            assertFalse(payload.toString().contains(":${server.port}"))
        }
    }

    /**
     * 两次调用之间把配置从 origin A 切到 B：第二次**只**打 B，
     * 不带 A 的令牌、不回落到任何默认端口。
     */
    @Test fun configSwitchBetweenCallsOnlyHitsTheNewOriginWithItsOwnPort() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { a ->
            MockWebServer().use { b ->
                a.dispatcher = strictMethodDispatcher(AtomicReference(null))
                b.dispatcher = strictMethodDispatcher(AtomicReference(null))
                a.start()
                b.start()
                // 随机端口必须真的不同，否则本用例没有区分力
                assertNotEquals(a.port, b.port)

                val current = AtomicReference(ReaderWebConfigStore.Config(a.url("/").toString(), "tok-a"))
                val service = ReaderWebSourceSync(
                    ReaderWebClient(configProvider = { current.get() }, clientFactory = { OkHttpClient() }),
                    sourceProvider = { source().toString() },
                )
                assertEquals("written", service.sync("p1", overwrite = false).status)
                val firstCalls = generateSequence { a.takeRequest(2, TimeUnit.SECONDS) }.toList()
                assertTrue(firstCalls.isNotEmpty())
                firstCalls.forEach { assertEquals(a.port, it.requestUrl!!.port) }

                // 切到 B（新 origin + 新令牌），第二次调用只应打到 B
                current.set(ReaderWebConfigStore.Config(b.url("/").toString(), "tok-b"))
                assertEquals("written", service.sync("p1", overwrite = false).status)

                val secondCalls = generateSequence { b.takeRequest(2, TimeUnit.SECONDS) }.toList()
                assertEquals(3, secondCalls.size)
                secondCalls.forEach { request ->
                    assertEquals("必须用 B 自己的随机端口，不得回落默认端口", b.port, request.requestUrl!!.port)
                    assertNull("不得把 A 的令牌带到 B", request.getHeader("Cookie"))
                    assertNotEquals("tok-a", request.getHeader("X-Legado-Token"))
                    assertNotEquals("tok-a", request.getHeader("Authorization"))
                }
                assertEquals(
                    "写入路由必须带 B 自己的令牌",
                    "tok-b",
                    secondCalls.first { it.method == "POST" }.getHeader("X-Legado-Token"),
                )
                // A 在第一次调用后再也不应收到请求
                assertNull("切到 B 后不得再触碰 A", a.takeRequest(200, TimeUnit.MILLISECONDS))
            }
        }
    }

    /** 同一次调用中途改配置：整次仍钉在开始时的 A（写入与回读都不跟到 B）。 */
    @Test fun configChangeMidCallKeepsThePinnedOrigin() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { a ->
            MockWebServer().use { b ->
                b.start()
                val current = AtomicReference<ReaderWebConfigStore.Config?>(null)
                val stored = AtomicReference<JsonObject?>(null)
                a.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        // 第一条请求就把配置切到 B：后续请求仍必须留在 A
                        if (current.get()?.baseUrl?.contains(":${a.port}") == true) {
                            current.set(ReaderWebConfigStore.Config(b.url("/").toString(), "tok-b"))
                        }
                        val path = request.path.orEmpty()
                        return when {
                            path.startsWith("/getBookSource?") ->
                                stored.get()?.let { okJson(it.toString()) } ?: missingJson()
                            path == "/saveBookSource" && request.method == "POST" -> {
                                stored.set(JsonParser.parseString(request.body.readUtf8()).asJsonObject)
                                okJson("\"\"")
                            }
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                a.start()
                current.set(ReaderWebConfigStore.Config(a.url("/").toString(), "tok-a"))
                val service = ReaderWebSourceSync(
                    ReaderWebClient(configProvider = { current.get() }, clientFactory = { OkHttpClient() }),
                    sourceProvider = { source().toString() },
                )
                val result = service.sync("p1", overwrite = false)
                assertEquals("written", result.status)
                assertEquals("中途改配置不得把请求带到 B", 0, b.requestCount)
                val calls = generateSequence { a.takeRequest(2, TimeUnit.SECONDS) }.toList()
                assertEquals(3, calls.size)
                calls.forEach { assertEquals(a.port, it.requestUrl!!.port) }
                calls.filter { it.method == "POST" }
                    .forEach { assertEquals("仍用开始时快照的 A 令牌", "tok-a", it.getHeader("X-Legado-Token")) }
            }
        }
    }

    /** 每个失败回包都要能区分「哪条请求、什么阶段、什么状态」，且不带 host/端口/令牌/源正文。 */
    @Test fun failurePayloadsExposeMethodPathStageAndStatusWithoutAddressOrBody() = kotlinx.coroutines.test.runTest {
        val token = "secret-sync-token"

        // 1) lookup 返回 HTML（不是 ReturnData）
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html><body>reader web</body></html>")
            }
            server.start()
            val (service, _) = sync(server.url("/").toString(), source(), token = token)
            val error = runCatching { service.sync("p1", overwrite = false) }
                .exceptionOrNull() as ReaderWebSourceSync.SyncException
            assertEquals("INCOMPATIBLE", error.code)
            assertFalse("预检失败时没写过", error.writeAttempted)
            assertEquals(ReaderWebSourceSync.WriteOutcome.NOT_ATTEMPTED, error.writeOutcome)
            val diagnostic = error.diagnostic!!
            assertEquals("GET", diagnostic.method)
            assertEquals("固定路径不含查询串", "/getBookSource", diagnostic.path)
            assertEquals("lookup", diagnostic.stage)
            assertEquals(200, diagnostic.httpStatus)
            assertEquals("parse", diagnostic.phase)

            val payload = ReaderWebSourceSync.failurePayload("p1", error)
            val dumped = payload.toString()
            assertEquals("error", payload["status"])
            assertFalse(dumped.contains(server.hostName))
            assertFalse(dumped.contains(":${server.port}"))
            assertFalse(dumped.contains(token))
            assertFalse("不得回显源正文", dumped.contains("book.test/search"))
            assertEquals("GET /getBookSource [lookup] HTTP 200", (payload["trace"] as List<*>).single())
        }

        // 2) save 返回 5xx：服务端错误，不是路由缺失
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getBookSource?") -> missingJson()
                    request.path == "/saveBookSource" -> MockResponse().setResponseCode(500)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val (service, _) = sync(server.url("/").toString(), source(), token = token)
            val error = runCatching { service.sync("p1", overwrite = false) }
                .exceptionOrNull() as ReaderWebSourceSync.SyncException
            assertEquals("HTTP_ERROR", error.code)
            assertTrue("写入确实发出过", error.writeAttempted)
            assertEquals(ReaderWebSourceSync.WriteOutcome.REJECTED, error.writeOutcome)
            val diagnostic = error.diagnostic!!
            assertEquals("POST", diagnostic.method)
            assertEquals("/saveBookSource", diagnostic.path)
            assertEquals("save", diagnostic.stage)
            assertEquals(500, diagnostic.httpStatus)
            val payload = ReaderWebSourceSync.failurePayload("p1", error)
            assertEquals(listOf("lookup", "save"), (payload["requests"] as List<*>).map { (it as Map<*, *>)["stage"] })
            assertEquals("GET /getBookSource [lookup] HTTP 200", (payload["trace"] as List<*>)[0])
            assertEquals("POST /saveBookSource [save] HTTP 500", (payload["trace"] as List<*>)[1])
            assertFalse(payload.toString().contains(token))
        }

        // 3) 写入被接受、回读返回 HTML：READBACK_FAILED + accepted/unconfirmed（不是「没写」）
        MockWebServer().use { server ->
            val lookups = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getBookSource?") ->
                        if (lookups.getAndIncrement() == 0) {
                            // 预检：阅读明确回答没有这个源 → 允许新增
                            missingJson()
                        } else {
                            // 回读：不返回 ReturnData，而是回网页 HTML
                            MockResponse()
                                .setHeader("Content-Type", "text/html; charset=utf-8")
                                .setBody("<html><body>not json</body></html>")
                        }
                    request.path == "/saveBookSource" && request.method == "POST" -> okJson("\"\"")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val (service, _) = sync(server.url("/").toString(), source(), token = token)
            val error = runCatching { service.sync("p1", overwrite = false) }
                .exceptionOrNull() as ReaderWebSourceSync.SyncException
            assertEquals("READBACK_FAILED", error.code)
            assertTrue("读取 HTML 是回读阶段失败", error.message.orEmpty().contains("回读"))
            assertTrue(error.writeAttempted)
            assertEquals(
                "写入已被阅读接受：只能报 accepted，不能报「没写」",
                ReaderWebSourceSync.WriteOutcome.ACCEPTED,
                error.writeOutcome,
            )
            val diagnostic = error.diagnostic!!
            assertEquals("GET", diagnostic.method)
            assertEquals("readback", diagnostic.stage)
            assertEquals("/getBookSource", diagnostic.path)
            val payload = ReaderWebSourceSync.failurePayload("p1", error)
            assertEquals("只接受而不确认", true, payload["written"])
            assertEquals(false, payload["confirmed"])
            assertEquals("accepted", payload["writeOutcome"])
            assertNull("未确认生效时不得给检验下一步", payload["nextAction"])
            assertEquals(
                listOf("lookup", "save", "readback"),
                (payload["requests"] as List<*>).map { (it as Map<*, *>)["stage"] },
            )
            assertFalse(payload.toString().contains(token))
            assertFalse(payload.toString().contains(server.hostName))
        }
    }

    /**
     * POST 连接被断开（请求可能已到达）：只能报 unknown，且**不得**重放 POST。
     * 写入路由总请求数必须恰好 1 条。
     */
    @Test fun postConnectionDropIsNotReplayedAndWriteOutcomeIsUnknown() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/getBookSource?") -> missingJson()
                    request.path == "/saveBookSource" -> MockResponse()
                        .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val (service, _) = sync(server.url("/").toString(), source())
            val error = runCatching { service.sync("p1", overwrite = false) }
                .exceptionOrNull() as ReaderWebSourceSync.SyncException
            assertEquals("UNREACHABLE", error.code)
            assertTrue(error.writeAttempted)
            assertEquals(
                "连接已建立后断开，无法确认是否已写入",
                ReaderWebSourceSync.WriteOutcome.UNKNOWN,
                error.writeOutcome,
            )
            assertTrue("文案必须承认不确定，不能说肯定没写", error.message.orEmpty().contains("无法确认"))
            val diagnostic = error.diagnostic!!
            assertEquals("POST", diagnostic.method)
            assertEquals("/saveBookSource", diagnostic.path)
            assertEquals("save", diagnostic.stage)
            assertEquals("POST 绝不重放", 1, diagnostic.attempts)
            assertNull("没拿到 HTTP 状态", diagnostic.httpStatus)

            val calls = generateSequence { server.takeRequest(2, TimeUnit.SECONDS) }.toList()
            assertEquals("只能有一条 POST", 1, calls.count { it.method == "POST" })
            assertEquals(2, calls.size)
            val payload = ReaderWebSourceSync.failurePayload("p1", error)
            assertEquals("unknown", payload["writeOutcome"])
            assertFalse("unknown 不能算作已写成功", payload["written"] as Boolean)
            assertEquals(false, payload["confirmed"])
        }
    }

    @Test fun bearerChallengeIsHonouredOnlyAfter401() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (request.getHeader("Authorization") == "Bearer good-token") {
                        return when {
                            path.startsWith("/getBookSource?") -> missingJson()
                            path == "/saveBookSource" -> okJson("\"\"")
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                    return MockResponse().setResponseCode(401)
                        .setHeader("WWW-Authenticate", "Bearer realm=\"reader\"")
                }
            }
            server.start()
            // 只预检：确认挑战后会用同一 origin 的 Bearer 重试，且第一次不带令牌
            val lookup = client(server.url("/").toString(), token = "good-token").fetchSource(url)
            assertFalse(lookup.present)
            assertTrue(lookup.bearerAuth)
            val requests = generateSequence { server.takeRequest(1, TimeUnit.MILLISECONDS) }.toList()
            assertEquals(2, requests.size)
            assertNull("公开探测不得预发 Authorization", requests[0].getHeader("Authorization"))
            assertEquals("Bearer good-token", requests[1].getHeader("Authorization"))
            // 挑战重试不使用 X-Legado-Token
            requests.forEach { assertNull(it.getHeader("X-Legado-Token")) }
        }
    }

    @Test fun bearerChallengeWithoutSavedTokenFailsAsTokenRequired() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer")
            }
            server.start()
            val error = runCatching { client(server.url("/").toString(), token = "").fetchSource(url) }
                .exceptionOrNull() as ReaderWebClient.ReaderWebException
            assertEquals("TOKEN_REQUIRED", error.code)
            assertTrue("必须暴露这是 Bearer 挑战", error.bearerChallenge)
            assertEquals(1, server.requestCount)
        }
    }
}
