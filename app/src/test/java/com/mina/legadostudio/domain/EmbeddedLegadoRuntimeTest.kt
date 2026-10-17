package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.LegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class EmbeddedLegadoRuntimeTest {
    @Test fun debugsDetailWithOfficialRuleAndInlineRhino() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html><h1>demo</h1><span class='author'>作者甲</span></html>"))
            server.start()
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "ruleBookInfo":{"name":"h1@text<js>String(result).toUpperCase()</js>","author":".author@text"},
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#content@html"}
            }"""
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, server.url("book/1").toString())
            val data = report.data as Map<*, *>
            assertEquals("DEMO", data["name"])
            assertEquals("作者甲", data["author"])
            assertTrue(report.lines.first().contains("HTTP 200"))
        }
    }

    @Test fun appliesBookSourceHeadersToRuntimeRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<h1>书名</h1>")); server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(), "header" to "{\"X-Source\":\"yes\"}",
                "ruleBookInfo" to mapOf("name" to "h1@text"), "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            runtime.debug(source, server.url("book").toString())
            assertEquals("yes", server.takeRequest().getHeader("X-Source"))
        }
    }

    @Test fun appliesFieldRegexReplacement() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<h1>广告-真实书名</h1>")); server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "ruleBookInfo" to mapOf("name" to "h1@text##广告-##"),
                "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, server.url("book").toString())
            assertEquals("真实书名", (report.data as Map<*, *>)["name"])
        }
    }

    @Test fun debugsJavascriptGeneratedBookListThroughOfficialRhino() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html></html>"))
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(), "searchUrl" to "search?q={{key}}",
                "ruleSearch" to mapOf("bookList" to "<js>['<div class=\"book\"><a href=\"/book/1\">书名</a><span>作者甲</span></div>']</js>", "name" to "a@text", "author" to "span@text", "bookUrl" to "a@href"),
                "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "关键字")
            val books = report.data as List<*>
            val first = books.first() as Map<*, *>
            assertEquals("书名", first["name"])
            assertEquals("作者甲", first["author"])
            assertEquals(server.url("book/1").toString(), first["bookUrl"])
        }
    }

    @Test fun contentReplaceRegexRunsAfterPagesAreMerged() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/c1" -> MockResponse().setBody("<div id='content'>前半章<div class='tail'>广告尾巴</div></div><a class='next' href='/c1-2'>下一页</a>")
                    "/c1-2" -> MockResponse().setBody("<div id='content'>后半章<div class='tail'>广告尾巴</div></div>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "ruleToc" to mapOf("chapterList" to "a"),
                "ruleContent" to mapOf("content" to "#content@html", "nextContentUrl" to ".next@href", "replaceRegex" to "##广告尾巴##"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "--${server.url("c1")}")
            val data = report.data as Map<*, *>
            val content = data["content"].toString()
            assertTrue(content.contains("前半章"))
            assertTrue(content.contains("后半章"))
            assertTrue(!content.contains("广告尾巴"))
            assertTrue(data["mergedBeforeReplace"].toString().contains("广告尾巴"))
            assertEquals(2.0, (data["pages"] as Number).toDouble(), 0.0)
        }
    }

    @Test fun greedyEndAnchorReplaceRegexMustNotBeAppliedPerPage() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/c1" -> MockResponse().setBody("<div id='content'>前半章<div class='tail'>广告尾巴</div></div><a class='next' href='/c1-2'>下一页</a>")
                    "/c1-2" -> MockResponse().setBody("<div id='content'>后半章<div class='tail'>广告尾巴</div></div>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val source = """
              {"bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
               "ruleToc":{"chapterList":"a"},
               "ruleContent":{"content":"#content@html","nextContentUrl":".next@href","replaceRegex":"##广告尾巴[\\s\\S]*${'$'}##"}}
            """.trimIndent()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "--${server.url("c1")}")
            val content = (report.data as Map<*, *>)["content"].toString()
            assertTrue(content.contains("前半章"))
            assertTrue("逐页净化会留下后半章，官方是合并后再替换，贪婪 \$ 会吃掉后续页", !content.contains("后半章"))
            assertTrue(!content.contains("广告尾巴"))
        }
    }

    @Test fun doesNotFollowNextChapterAsContentPagination() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/book/1.html" -> MockResponse().setBody("<div id='content'>第一章正文正文正文正文</div><a href='/book/2.html'>下一章</a>")
                    "/book/2.html" -> MockResponse().setBody("<div id='content'>第二章不该被合并</div>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "ruleToc" to mapOf("chapterList" to "a"),
                "ruleContent" to mapOf("content" to "#content@html", "nextContentUrl" to "text.下一章@href"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "--${server.url("book/1.html")}")
            val data = report.data as Map<*, *>
            assertTrue(data["content"].toString().contains("第一章"))
            assertTrue(!data["content"].toString().contains("第二章"))
            assertEquals(1.0, (data["pages"] as Number).toDouble(), 0.0)
        }
    }

    @Test fun interpolatesSearchKeyWithoutAndroidIcuQuantifierCrash() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/1'>书名</a></div>"))
            server.start()
            val source = """
              {"bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
               "searchUrl":"/e/search/index.php,{\"method\":\"POST\",\"body\":\"keyboard={{key}}&tempid=1\"}",
               "ruleSearch":{"bookList":".book","name":"a@text","bookUrl":"a@href"},
               "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#content@html"}}
            """.trimIndent()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "万相 之王+100%&")
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("POST 搜索请求应在 5 秒内到达", recorded)
            checkNotNull(recorded)
            assertEquals("POST", recorded.method)
            assertEquals("/e/search/index.php", recorded.path)
            assertEquals("keyboard=%E4%B8%87%E7%9B%B8+%E4%B9%8B%E7%8E%8B%2B100%25%26&tempid=1", recorded.body.readUtf8())
            assertEquals("application/x-www-form-urlencoded; charset=utf-8", recorded.getHeader("Content-Type"))
            val books = report.data as List<*>
            assertEquals("书名", (books.first() as Map<*, *>)["name"])
            assertEquals(server.url("b/1").toString(), (books.first() as Map<*, *>)["bookUrl"])
            assertTrue(EmbeddedLegadoRuntime.JS_TEMPLATE.containsMatchIn("prefix{{java.time()}}suffix"))
        }
    }

    @Test fun interpolatesSearchKeyInJsReturnedPostOptionBody() = runBlocking {
        // @js 返回 "url,{method:POST,body}"；脚本内 key 经 java.encodeURI（空格 %20），与模板 {{key}} 的 + 不同
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/2'>JS书名</a></div>"))
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "searchUrl" to "@js:'${server.url("/")}e/search/index.php,'+JSON.stringify({method:'POST',body:'keyboard='+java.encodeURI(key)+'&tempid=1'})",
                "ruleSearch" to mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
                "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "万相 之王+100%&")
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("POST 搜索请求应在 5 秒内到达", recorded)
            checkNotNull(recorded)
            assertEquals("POST", recorded.method)
            assertEquals("keyboard=%E4%B8%87%E7%9B%B8%20%E4%B9%8B%E7%8E%8B%2B100%25%26&tempid=1", recorded.body.readUtf8())
            assertEquals("application/x-www-form-urlencoded; charset=utf-8", recorded.getHeader("Content-Type"))
            val books = report.data as List<*>
            assertEquals("JS书名", (books.first() as Map<*, *>)["name"])
            assertEquals(server.url("b/2").toString(), (books.first() as Map<*, *>)["bookUrl"])
        }
    }

    @Test fun jsReturnedPostOptionBodySendsRawKeyUnencoded() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/3'>原名</a></div>"))
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "searchUrl" to "@js:'${server.url("/")}e/search/index.php,'+JSON.stringify({method:'POST',body:'keyboard='+key+'&tempid=1'})",
                "ruleSearch" to mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
                "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "万相 之王+100%&")
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("POST 搜索请求应在 5 秒内到达", recorded)
            checkNotNull(recorded)
            assertEquals("POST", recorded.method)
            assertEquals("keyboard=万相 之王+100%&&tempid=1", recorded.body.readUtf8())
            assertEquals("application/x-www-form-urlencoded; charset=utf-8", recorded.getHeader("Content-Type"))
            val books = report.data as List<*>
            assertEquals("原名", (books.first() as Map<*, *>)["name"])
            assertEquals(server.url("b/3").toString(), (books.first() as Map<*, *>)["bookUrl"])
        }
    }

    @Test fun bareAttrRuleReadsAttributeFromCurrentElement() = runBlocking {
        // 回归：子规则裸 @href 曾把元素序列化成 HTML 重解析，上下文变成文档根节点导致恒为空
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<a class='ch' href='/book/1/2'><span class='t'>第一章</span></a><a class='ch' href='/book/1/3'><span class='t'>第二章</span></a>"))
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "ruleToc" to mapOf("chapterList" to "a.ch", "chapterName" to ".t@text", "chapterUrl" to "@href"),
                "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "++${server.url("book/1")}")
            val chapters = (report.data as Map<*, *>)["chapters"] as List<*>
            val first = chapters.first() as Map<*, *>
            assertEquals("第一章", first["name"])
            assertEquals(server.url("book/1/2").toString(), first["url"])
        }
    }

    @Test fun multiParagraphTextRuleJoinsAllParagraphs() = runBlocking {
        // 回归：多段正文 @text 曾只取首段，与官方 getString 按 \n 拼接的行为不一致
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div id='c'><p>第一段</p><p>第二段</p><p>第三段</p></div>"))
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "ruleToc" to mapOf("chapterList" to "a"),
                "ruleContent" to mapOf("content" to "#c p@text"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "--${server.url("c1")}")
            val content = (report.data as Map<*, *>)["content"].toString()
            assertEquals("第一段\n第二段\n第三段", content)
        }
    }

    @Test fun debugReportsJsChallengeInsteadOfSilentEmptyList() = runBlocking {
        // 阶段二回归：200 JS 挑战页不能被当成「CSS 选不中」静默吞掉——必须抛出带人机挑战提示的错误
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """<div id="x">内容正在载入……</div><script>var c2="abc";</script><script src="/nnxswnn/a.js"></script>"""
            ))
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(), "searchUrl" to "search?q={{key}}",
                "ruleSearch" to mapOf("bookList" to ".book", "name" to "a@text"),
                "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val error = runCatching { runtime.debug(source, "关键字") }.exceptionOrNull()
            assertTrue(error is com.mina.legadostudio.network.JsChallengeException)
            assertTrue(error!!.message.orEmpty().contains("挑战") || error.message.orEmpty().contains("JS_CHALLENGE"))
        }
    }

    @Test fun enabledCookieJarFalseDisablesJarForMainAndJsSubRequests() = runBlocking {
        // 书源级 enabledCookieJar:false → search 主请求不回带 jar、不吸收 Set-Cookie，
        // searchUrl @js 内 java.connect 的二次请求同口径隔离（bindings 透传给 RhinoEvaluator）
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/step1" -> MockResponse().setBody("seed").setHeader("Set-Cookie", "guard=g1; Path=/")
                    "/step2" -> MockResponse().setBody("next")
                    "/search" -> MockResponse().setBody("<div class='book'><a href='/b/1'>书名</a></div>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "enabledCookieJar" to false,
                // @js 返回字面量 URL 字符串：先连发两次 java.connect 探针，再返回搜索地址
                "searchUrl" to "@js:java.connect('" + server.url("/step1") + "');java.connect('" + server.url("/step2") + "');'" + server.url("/search") + "'",
                "ruleSearch" to mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
                "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#c@html"),
            ))
            val runtime = EmbeddedLegadoRuntime(
                HttpFetcher(cookieJar = com.mina.legadostudio.network.StudioCookieJar()),
                BookSourceValidator(), LegadoRuleEngine(),
                RhinoEvaluator(HttpFetcher(cookieJar = com.mina.legadostudio.network.StudioCookieJar()), Gson()),
            )
            runtime.debug(source, "关键字")
            server.takeRequest()                       // /step1：无 Cookie 可带
            assertNull(server.takeRequest().getHeader("Cookie"))   // /step2：jar 禁用 → 不带 guard
            assertNull(server.takeRequest().getHeader("Cookie"))   // /search 主请求同口径
        }
    }

    @Test fun enabledCookieJarAbsentOrTrueKeepsJarOn() = runBlocking {
        // 缺省（字段不存在）→ jar 照旧：/step1 种下的 Set-Cookie 应回带到 /step2
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/step1" -> MockResponse().setBody("seed").setHeader("Set-Cookie", "guard=g1; Path=/")
                    "/search" -> MockResponse().setBody("<div class='book'><a href='/b/1'>书名</a></div>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val jar = com.mina.legadostudio.network.StudioCookieJar()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "searchUrl" to "@js:java.connect('" + server.url("/step1") + "');'" + server.url("/search") + "'",
                "ruleSearch" to mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
                "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#c@html"),
            ))
            val runtime = EmbeddedLegadoRuntime(
                HttpFetcher(cookieJar = jar), BookSourceValidator(), LegadoRuleEngine(),
                RhinoEvaluator(HttpFetcher(cookieJar = jar), Gson()),
            )
            runtime.debug(source, "关键字")
            server.takeRequest()                                       // /step1：种下 guard
            assertEquals("guard=g1", server.takeRequest().getHeader("Cookie"))  // /search 回带
        }
    }

    private fun contentRuntime(fetcher: HttpFetcher = HttpFetcher()) =
        EmbeddedLegadoRuntime(fetcher, BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))

    private fun contentDiagnostics(report: LegadoRuntime.DebugReport): Map<*, *> =
        (report.data as Map<*, *>)["contentDiagnostics"] as Map<*, *>

    private fun contentSource(server: MockWebServer, extra: Map<String, Any?> = emptyMap()): String = Gson().toJson(mapOf(
        "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
        "ruleToc" to mapOf("chapterList" to "a"),
        "ruleContent" to (mapOf("content" to "#content@html") + extra),
    ))

    @Test fun contentDiagnosticsReportPerPageHttpStatus() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/c1" -> MockResponse().setResponseCode(200).setBody("<div id='content'>第一页</div><a class='next' href='/c2'>下一页</a>")
                    "/c2" -> MockResponse().setResponseCode(500).setBody("<div id='content'>第二页</div>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val report = contentRuntime().debug(contentSource(server, mapOf("nextContentUrl" to ".next@href")), "--${server.url("c1")}")
            val diag = contentDiagnostics(report)
            assertEquals("complete", diag["endReason"])
            val pages = diag["pages"] as List<*>
            assertEquals(2, pages.size)
            val p0 = pages[0] as Map<*, *>
            assertEquals(1, (p0["page"] as Number).toInt())
            assertEquals(200, (p0["httpStatus"] as Number).toInt())
            assertEquals(200, (p0["reportedStatus"] as Number).toInt())
            assertEquals("http", p0["transport"])
            assertEquals(false, p0["cacheUsed"])
            assertEquals(500, ((pages[1] as Map<*, *>)["httpStatus"] as Number).toInt())
            assertEquals(500, ((pages[1] as Map<*, *>)["reportedStatus"] as Number).toInt())
        }
    }

    @Test fun contentDiagnosticsMarksSelfLinkCycleAndStopsRefetch() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    MockResponse().setBody("<div id='content'>本页</div><a class='next' href='/loop'>下一页</a>")
            }
            server.start()
            val report = contentRuntime().debug(contentSource(server, mapOf("nextContentUrl" to ".next@href")), "--${server.url("loop")}")
            val diag = contentDiagnostics(report)
            assertEquals("cycle", diag["endReason"])
            assertEquals(1, (diag["pages"] as List<*>).size)
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNull("cycle 后不得再次抓取同一 URL", server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test fun contentDiagnosticsCapsPaginationAtTenPages() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                var served = 0
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    served += 1
                    return MockResponse().setBody("<div id='content'>第${served}页</div><a class='next' href='/p${served + 1}'>下一页</a>")
                }
            }
            server.start()
            val report = contentRuntime().debug(contentSource(server, mapOf("nextContentUrl" to ".next@href")), "--${server.url("p1")}")
            val diag = contentDiagnostics(report)
            assertEquals("page_limit", diag["endReason"])
            assertEquals(10, (diag["pages"] as List<*>).size)
            repeat(10) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            assertNull("10 页上限后不得再抓取", server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test fun contentDiagnosticsCompleteWhenTenthPageHasNoNext() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                var served = 0
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    served += 1
                    val link = if (served < 10) "<a class='next' href='/p${served + 1}'>下一页</a>" else ""
                    return MockResponse().setBody("<div id='content'>第${served}页</div>$link")
                }
            }
            server.start()
            val report = contentRuntime().debug(contentSource(server, mapOf("nextContentUrl" to ".next@href")), "--${server.url("p1")}")
            val diag = contentDiagnostics(report)
            assertEquals("complete", diag["endReason"])
            assertEquals(10, (diag["pages"] as List<*>).size)
        }
    }

    @Test fun contentDiagnosticsMarksNextChapterTermination() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/book/1.html" -> MockResponse().setBody("<div id='content'>第一章</div><a href='/book/2.html'>下一章</a>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val report = contentRuntime().debug(contentSource(server, mapOf("nextContentUrl" to "text.下一章@href")), "--${server.url("book/1.html")}")
            val diag = contentDiagnostics(report)
            assertEquals("next_chapter", diag["endReason"])
            assertEquals(1, (diag["pages"] as List<*>).size)
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNull(server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test fun contentDiagnosticsCacheFetchReportsOnlyReportedCode() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cacheFetch: suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult = { req ->
                HttpFetcher.FetchResult(500, req.url.orEmpty(), emptyMap(), "<div id='content'>缓存页</div>", 0)
            }
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "loginCheckJs" to "result.body();result",
                "ruleToc" to mapOf("chapterList" to "a"),
                "ruleContent" to mapOf("content" to "#content@html"),
            ))
            // cacheFetch 返回值不透明：loginCheckJs/bodyJs 均不得影响已记录的 reported 500
            val report = contentRuntime().debug(source, "--${server.url("c1")},{\"bodyJs\":\"'改写后正文'\"}", cacheFetch)
            val diag = contentDiagnostics(report)
            val pages = diag["pages"] as List<*>
            assertEquals(1, pages.size)
            val p0 = pages[0] as Map<*, *>
            assertNull("cacheFetch 结果拿不到真实 raw code", p0["httpStatus"])
            assertEquals(500, (p0["reportedStatus"] as Number).toInt())
            assertEquals("cache_fetch", p0["transport"])
            assertNull("cacheUsed 未知，不得推断命中", p0["cacheUsed"])
        }
    }

    @Test fun contentDiagnosticsCacheFetchMissInternallyNetworkingStillOpaque() = runBlocking {
        // 回调内部真实联网（miss 后回源）：runtime 侧只能看到 transport=cache_fetch，不得误标命中或 http
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("<div id='content'>miss回源页</div>"))
            server.start()
            val inner = HttpFetcher()
            val cacheFetch: suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult = { req -> inner.fetch(req) }
            val report = contentRuntime().debug(contentSource(server), "--${server.url("c1")}", cacheFetch)
            server.takeRequest(5, TimeUnit.SECONDS)
            val p0 = (contentDiagnostics(report)["pages"] as List<*>)[0] as Map<*, *>
            assertNull(p0["httpStatus"])
            assertEquals(200, (p0["reportedStatus"] as Number).toInt())
            assertEquals("cache_fetch", p0["transport"])
            assertNull(p0["cacheUsed"])
        }
    }

    @Test fun contentDiagnosticsCarriesNoSecretsOrFreeformText() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div id='content'>正文 topsecret-token</div>"))
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "header" to "{\"Cookie\":\"session=topsecret-token\",\"X-Auth\":\"topsecret-token\"}",
                "ruleToc" to mapOf("chapterList" to "a"),
                "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val report = contentRuntime().debug(source, "--${server.url("c1?token=topsecret-token")}")
            server.takeRequest(5, TimeUnit.SECONDS)
            val diag = contentDiagnostics(report)
            assertEquals(setOf("endReason", "pages"), diag.keys)
            val pages = diag["pages"] as List<*>
            val p0 = pages[0] as Map<*, *>
            assertEquals(setOf("page", "httpStatus", "reportedStatus", "transport", "cacheUsed"), p0.keys)
            val serialized = Gson().toJson(diag)
            listOf("topsecret-token", "session", server.url("/").toString().removeSuffix("/"), "Cookie").forEach { secret ->
                assertTrue("contentDiagnostics 不得包含 $secret", !serialized.contains(secret))
            }
            // 旧显式 debug 字段仍按契约存在
            val data = report.data as Map<*, *>
            assertTrue(data.containsKey("content") && data.containsKey("contentTotalChars"))
        }
    }
}
