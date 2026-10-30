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
import org.junit.Assert.assertFalse
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
            assertTrue("未配置 tocUrl 且存在目录规则时应回填详情页 URL", data["tocUrl"].toString().contains("/book/1"))
        }
    }

    @Test fun detailExplicitEmptyTocUrlRuleDoesNotFallback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html><h1>demo</h1></html>"))
            server.start()
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "ruleBookInfo":{"name":"h1@text","tocUrl":"#missing@href"},
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#content@html"}
            }"""
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val data = runtime.debug(source, server.url("book/1").toString()).data as Map<*, *>
            assertEquals("demo", data["name"])
            assertTrue("显式 tocUrl 规则取空不得回填详情页", data["tocUrl"] == null || data["tocUrl"].toString().isBlank())
        }
    }

    @Test fun detailBareCssWithBemClassMatchesExplicitCssPrefix() = runBlocking {
        MockWebServer().use { server ->
            val page = "<html><body><div class='qy-book'><h1 class='qy-book__title'>素书</h1><p class='qy-book__author'><a>作者乙</a></p></div></body></html>"
            server.enqueue(MockResponse().setBody(page)); server.enqueue(MockResponse().setBody(page)); server.start()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            fun source(name: String, author: String) = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "ruleBookInfo" to mapOf("name" to name, "author" to author),
                "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val bare = runtime.debug(source("h1.qy-book__title@text", ".qy-book__author a@text"), server.url("book/1.html").toString()).data as Map<*, *>
            val css = runtime.debug(source("@css:h1.qy-book__title@text", "@css:.qy-book__author a@text"), server.url("book/1.html").toString()).data as Map<*, *>
            assertEquals("素书", bare["name"])
            assertEquals("作者乙", bare["author"])
            assertEquals(css["name"], bare["name"])
            assertEquals(css["author"], bare["author"])
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

    @Test fun tocFanoutDoesNotJoinOptionValuesIntoOneUrl() = runBlocking {
        // option@value 返回多条：官方一层展开；extract() 拼接会变成超长 URL → HTTP 400
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val page = when (request.path) {
                        "/book/1" -> 1
                        "/book/1-2" -> 2
                        "/book/1-3" -> 3
                        "/book/1-4" -> 4
                        else -> return MockResponse().setResponseCode(404)
                    }
                    // 首页只有 1/2/3；4 只出现在二级页，用来证明二级页不再取 nextTocUrl
                    val last = if (page == 1) 3 else 4
                    val options = (1..last).joinToString("") { n ->
                        val href = if (n == 1) "/book/1" else "/book/1-$n"
                        "<option value='$href'>$n</option>"
                    }
                    return MockResponse().setBody(
                        "<ul class='chapter'><li><a href='/ch/p$page'>第${page}页章</a></li></ul><select>$options</select>",
                    )
                }
            }
            server.start()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(tocSource(server, "option@value"), "++${server.url("book/1")}")
            val data = report.data as Map<*, *>
            assertEquals(3.0, (data["totalChapters"] as Number).toDouble(), 0.0)
            val names = ((data["chapters"] as List<*>).map { (it as Map<*, *>)["name"] })
            assertEquals(listOf("第1页章", "第2页章", "第3页章"), names)
            assertTrue(report.lines.any { it.contains("一层展开") && it.contains("2 个 URL") })
            assertEquals(3, server.requestCount)
            val paths = List(3) { server.takeRequest().path.orEmpty() }
            assertEquals(listOf("/book/1", "/book/1-2", "/book/1-3"), paths)
            assertTrue("不得把多个 option 拼进同一个请求", paths.none { it.contains("%0A") || it.contains("\n") || it.contains("book/1-2") && it.contains("book/1-3") })
            assertNull("二级页不得再展开 nextTocUrl（/book/1-4）", server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test fun tocSingleNextUrlChainsUntilEnd() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/toc/1" -> MockResponse().setBody("<ul class='chapter'><li><a href='/ch/1'>一</a></li></ul><a class='next' href='/toc/2'>下页</a>")
                    "/toc/2" -> MockResponse().setBody("<ul class='chapter'><li><a href='/ch/2'>二</a></li></ul><a class='next' href='/toc/3'>下页</a>")
                    "/toc/3" -> MockResponse().setBody("<ul class='chapter'><li><a href='/ch/3'>三</a></li></ul>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(tocSource(server, ".next@href"), "++${server.url("toc/1")}")
            val data = report.data as Map<*, *>
            assertEquals(3.0, (data["totalChapters"] as Number).toDouble(), 0.0)
            val names = ((data["chapters"] as List<*>).map { (it as Map<*, *>)["name"] })
            assertEquals(listOf("一", "二", "三"), names)
            assertTrue(report.lines.none { it.contains("一层展开") })
            assertEquals(3, server.requestCount)
            assertEquals(listOf("/toc/1", "/toc/2", "/toc/3"), List(3) { server.takeRequest().path.orEmpty() })
            assertNull(server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test fun tocSelfLinkIsSkippedWithoutRefetch() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    MockResponse().setBody("<ul class='chapter'><li><a href='/ch/1'>一</a></li></ul><a class='next' href='/loop'>下页</a>")
            }
            server.start()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(tocSource(server, ".next@href"), "++${server.url("loop")}")
            val data = report.data as Map<*, *>
            assertEquals(1.0, (data["totalChapters"] as Number).toDouble(), 0.0)
            assertEquals(1, server.requestCount)
            assertEquals("/loop", server.takeRequest().path)
            assertNull("自链等于当前页，不得再抓", server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test fun tocChainStopsWhenNextUrlRepeats() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/toc/a" -> MockResponse().setBody("<ul class='chapter'><li><a href='/ch/a'>A</a></li></ul><a class='next' href='/toc/b'>下页</a>")
                    "/toc/b" -> MockResponse().setBody("<ul class='chapter'><li><a href='/ch/b'>B</a></li></ul><a class='next' href='/toc/a'>下页</a>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(tocSource(server, ".next@href"), "++${server.url("toc/a")}")
            val names = (((report.data as Map<*, *>)["chapters"] as List<*>).map { (it as Map<*, *>)["name"] })
            assertEquals(listOf("A", "B"), names)
            assertEquals(2, server.requestCount)
            assertEquals(listOf("/toc/a", "/toc/b"), List(2) { server.takeRequest().path.orEmpty() })
            assertNull("A→B→A 不得第三次请求", server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test fun tocChainCapsAtMaxPages() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val n = Regex("^/p(\\d+)$").find(request.path.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                        ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setBody(
                        "<ul class='chapter'><li><a href='/ch/$n'>章$n</a></li></ul><a class='next' href='/p${n + 1}'>下页</a>",
                    )
                }
            }
            server.start()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(tocSource(server, ".next@href"), "++${server.url("p1")}")
            val data = report.data as Map<*, *>
            assertEquals(EmbeddedLegadoRuntime.MAX_TOC_PAGES.toDouble(), (data["totalChapters"] as Number).toDouble(), 0.0)
            assertTrue(report.lines.any { it.contains("目录分页达到上限") })
            val diag = tocDiagnostics(data)
            assertEquals("page_limit", diag["endReason"])
            assertEquals(true, diag["truncated"])
            assertEquals(EmbeddedLegadoRuntime.MAX_TOC_PAGES, (diag["pagesFetched"] as Number).toInt())
            assertEquals(EmbeddedLegadoRuntime.MAX_TOC_PAGES, (diag["maxPages"] as Number).toInt())
            assertEquals(EmbeddedLegadoRuntime.MAX_TOC_PAGES, server.requestCount)
            repeat(EmbeddedLegadoRuntime.MAX_TOC_PAGES) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            assertNull("页数上限后不得再抓", server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test fun tocFanoutDedupsHomepageAndPage1Alias() = runBlocking {
        // 飞速中文形态：入口 /book-25073/，option 含别名 /book-25073-1/（同章）与后续不同页。
        // 沙箱按非空 resolve 后的章节 URL 有界去重（近似 BookChapterList.kt:129 LinkedHashSet）。
        // 官方 BookChapter equals/hashCode 未核实；本测试只约束沙箱：不得按章名折叠，也不得把首页章再计一遍。
        MockWebServer().use { server ->
            fun chapters(ids: IntRange) = ids.joinToString("") { n ->
                "<li><a href='/ch/$n'>第${n}章</a></li>"
            }
            fun options() = (1..3).joinToString("") { n ->
                "<option value='/book-25073-$n/'>$n</option>"
            }
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val body = when (request.path) {
                        "/book-25073/", "/book-25073-1/" ->
                            "<ul class='chapter'>${chapters(1..3)}</ul><select>${options()}</select>"
                        "/book-25073-2/" ->
                            "<ul class='chapter'>${chapters(4..5)}</ul><select>${options()}</select>"
                        "/book-25073-3/" ->
                            "<ul class='chapter'>${chapters(6..7)}</ul><select>${options()}</select>"
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(tocSource(server, "option@value"), "++${server.url("book-25073/")}")
            val data = report.data as Map<*, *>
            assertEquals(7.0, (data["totalChapters"] as Number).toDouble(), 0.0)
            val names = (data["chapters"] as List<*>).map { (it as Map<*, *>)["name"] }
            assertEquals(listOf("第1章", "第2章", "第3章", "第4章", "第5章", "第6章", "第7章"), names)
            val urls = (data["chapters"] as List<*>).map { (it as Map<*, *>)["url"].toString() }
            assertEquals(urls.distinct(), urls)
            assertTrue(report.lines.any { it.contains("去重") && it.contains("10 → 7") })
            val diag = tocDiagnostics(data)
            assertEquals("complete", diag["endReason"])
            assertEquals(false, diag["truncated"])
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun tocFanoutKeepsSameNameDifferentUrls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                "<ul class='chapter'>" +
                    "<li><a href='/vol1/ch1'>第一章</a></li>" +
                    "<li><a href='/vol2/ch1'>第一章</a></li>" +
                    "<li><a>卷名</a></li>" +
                    "<li><a>卷名</a></li>" +
                    "</ul>",
            ))
            server.start()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(tocSource(server, "option@value"), "++${server.url("book/1")}")
            val data = report.data as Map<*, *>
            val chapters = data["chapters"] as List<*>
            assertEquals(4.0, (data["totalChapters"] as Number).toDouble(), 0.0)
            val names = chapters.map { (it as Map<*, *>)["name"] }
            assertEquals(listOf("第一章", "第一章", "卷名", "卷名"), names)
            val urls = chapters.map { (it as Map<*, *>)["url"].toString() }
            assertTrue(urls[0].contains("/vol1/ch1"))
            assertTrue(urls[1].contains("/vol2/ch1"))
            assertTrue("空 URL 卷名不得按 URL 折叠", urls[2].isBlank() && urls[3].isBlank())
        }
    }

    @Test fun tocMarksIsVolumeAndDoesNotForgeVolumeUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """<ul>
                    <li class="item"><span class="vol">正文</span></li>
                    <li class="item"><a href="/book/1/1">第一章</a></li>
                </ul>""",
            ))
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                "ruleToc" to mapOf(
                    "chapterList" to "li.item",
                    "chapterName" to "a@text||span.vol@text||text",
                    "chapterUrl" to "a@href",
                    "isVolume" to "span.vol@text",
                ),
                "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "++${server.url("toc")}")
            val chapters = (report.data as Map<*, *>)["chapters"] as List<*>
            val volume = chapters[0] as Map<*, *>
            val chapter = chapters[1] as Map<*, *>
            assertEquals("正文", volume["name"])
            assertTrue("卷名 URL 必须保持空，不得伪造 title+index 或 baseUrl", volume["url"].toString().isBlank())
            assertEquals(true, volume["isVolume"])
            assertEquals("第一章", chapter["name"])
            assertTrue(chapter["url"].toString().contains("/book/1/1"))
            assertEquals(false, chapter["isVolume"])
            val evidence = SourceCheckEvidence.of("目录", report)
            assertTrue(evidence.ok)
            assertEquals(1, evidence.facts["usableChapterIndex"])
            assertTrue(evidence.facts["firstUsableChapterUrl"].toString().contains("/book/1/1"))
            assertFalse(evidence.facts["firstUsableChapterUrl"].toString().contains("正文"))
        }
    }

    @Test fun tocPilishuwuIsVolumeRuleMatchesCurrentVolumeLi() = runBlocking {
        val tocHtml = """
            <ul class="custom-chapter-grid">
              <li class="custom-volume-title">正文</li>
              <li class="custom-chapter-item"><a href="/5/16098/read/4249983.html">第一章</a></li>
            </ul>
        """.trimIndent()
        val engine = LegadoRuleEngine()
        val items = engine.elementList(tocHtml, "@css:li.custom-chapter-item, li.custom-volume-title")
        assertEquals(2, items.size)
        val originalOnVolume = engine.extract(items[0], "li.custom-volume-title@text")
        val originalOnChapter = engine.extract(items[1], "li.custom-volume-title@text")
        assertEquals("正文", originalOnVolume.first)
        assertTrue("章 li 上 isVolume 选择器不得命中", originalOnChapter.first.isNullOrBlank())
        assertEquals("正文", engine.extract(items[0], "a@text||span@text||text").first)
        assertTrue(engine.extract(items[0], "a@href").first.isNullOrBlank())

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(tocHtml))
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "霹雳书屋",
                "bookSourceUrl" to server.url("/").toString(),
                "ruleToc" to mapOf(
                    "chapterList" to "@css:li.custom-chapter-item, li.custom-volume-title",
                    "chapterName" to "a@text||span@text||text",
                    "chapterUrl" to "a@href##read/(.*).html##/wmcms/ajax/index.php?action=novel.getchapter&cid=\$1&format=1###",
                    "isVolume" to "li.custom-volume-title@text",
                ),
                "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "++${server.url("5/16098/menu/1.html")}")
            val data = report.data as Map<*, *>
            val chapters = data["chapters"] as List<*>
            assertEquals(2, chapters.size)
            val volume = chapters[0] as Map<*, *>
            val chapter = chapters[1] as Map<*, *>
            assertEquals("正文", volume["name"])
            assertTrue("卷名 URL 必须保持空，不得伪造 title+index 或 baseUrl", volume["url"].toString().isBlank())
            assertEquals(true, volume["isVolume"])
            assertEquals("第一章", chapter["name"])
            assertTrue(chapter["url"].toString().contains("novel.getchapter") && chapter["url"].toString().contains("cid=4249983"))
            assertEquals(false, chapter["isVolume"])
            val evidence = SourceCheckEvidence.of("目录", report)
            assertTrue("卷名空 URL 不得把目录判失败", evidence.ok)
            assertEquals(1, evidence.facts["usableChapterIndex"])
            assertEquals(1, evidence.facts["volumeCount"])
            assertTrue(evidence.facts["firstUsableChapterUrl"].toString().contains("cid=4249983"))
            assertFalse(evidence.facts["firstUsableChapterUrl"].toString().contains("正文"))
        }
    }

    @Test fun tocFanoutTwentyEightPagesComplete() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val n = when {
                        request.path == "/book-x/" -> 1
                        else -> Regex("^/book-x-(\\d+)/$").find(request.path.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    } ?: return MockResponse().setResponseCode(404)
                    val options = (2..28).joinToString("") { p -> "<option value='/book-x-$p/'>$p</option>" }
                    return MockResponse().setBody(
                        "<ul class='chapter'><li><a href='/ch/$n'>章$n</a></li></ul><select>$options</select>",
                    )
                }
            }
            server.start()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(tocSource(server, "option@value"), "++${server.url("book-x/")}")
            val data = report.data as Map<*, *>
            assertEquals(28.0, (data["totalChapters"] as Number).toDouble(), 0.0)
            val diag = tocDiagnostics(data)
            assertEquals("complete", diag["endReason"])
            assertEquals(false, diag["truncated"])
            assertEquals(28, (diag["pagesFetched"] as Number).toInt())
            assertEquals(28, server.requestCount)
            val evidence = SourceCheckEvidence.of("目录", report)
            assertTrue(evidence.ok)
            assertNull(evidence.sandboxReason)
            assertEquals(false, evidence.facts["tocTruncated"])
            assertEquals("complete", evidence.facts["tocEndReason"])
        }
    }

    @Test fun tocFanoutOneHundredOnePagesIsTruncated() = runBlocking {
        val extra = EmbeddedLegadoRuntime.MAX_TOC_PAGES
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val n = when {
                        request.path == "/book-x/" -> 1
                        else -> Regex("^/book-x-(\\d+)/$").find(request.path.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    } ?: return MockResponse().setResponseCode(404)
                    val options = (2..(extra + 1)).joinToString("") { p -> "<option value='/book-x-$p/'>$p</option>" }
                    return MockResponse().setBody(
                        "<ul class='chapter'><li><a href='/ch/$n'>章$n</a></li></ul><select>$options</select>",
                    )
                }
            }
            server.start()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(tocSource(server, "option@value"), "++${server.url("book-x/")}")
            val data = report.data as Map<*, *>
            val diag = tocDiagnostics(data)
            assertEquals("page_limit", diag["endReason"])
            assertEquals(true, diag["truncated"])
            assertEquals(EmbeddedLegadoRuntime.MAX_TOC_PAGES, (diag["pagesFetched"] as Number).toInt())
            assertEquals(EmbeddedLegadoRuntime.MAX_TOC_PAGES.toDouble(), (data["totalChapters"] as Number).toDouble(), 0.0)
            assertEquals(EmbeddedLegadoRuntime.MAX_TOC_PAGES, server.requestCount)
            val evidence = SourceCheckEvidence.of("目录", report)
            assertTrue("上限采集仍有章节，规则不算失败", evidence.ok)
            assertEquals("sandbox", evidence.category)
            assertEquals("page_limit", evidence.sandboxReason)
            assertEquals(true, evidence.facts["tocTruncated"])
            assertEquals("page_limit", evidence.facts["tocEndReason"])
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

    private fun tocSource(server: MockWebServer, nextTocUrl: String, chapterList: String = "ul.chapter a"): String = Gson().toJson(mapOf(
        "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
        "ruleToc" to mapOf("chapterList" to chapterList, "chapterName" to "@text", "chapterUrl" to "@href", "nextTocUrl" to nextTocUrl),
        "ruleContent" to mapOf("content" to "#content@html"),
    ))

    private fun tocDiagnostics(data: Map<*, *>): Map<*, *> = data["tocDiagnostics"] as Map<*, *>

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

    @Test fun inspectAppliesReplaceRegexPerElement() = runBlocking {
        val html = "<html><body><div class='item'>foo1</div><div class='item'>foo2</div></body></html>"
        val page = HttpFetcher.FetchResult(200, "https://example.com/", emptyMap(), html, 0)
        val report = contentRuntime().inspectSnapshot(
            LegadoRuntime.InspectRequest(url = "https://example.com/", rule = "@css:.item@text##foo##"),
            page,
        )
        val values = report.output!!.values
        assertTrue("inspect 必须逐元素，values.size>1，实际=$values", values.size > 1)
        assertEquals(listOf("1", "2"), values)
    }

    @Test fun contentChannelWarnsMissingNextChapterAndSkipsEmptyNextUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div id='content'>本章正文</div><a class='next' href='/c2'>下一页</a>"))
            server.start()
            val report = contentRuntime().debug(
                contentSource(server, mapOf("nextContentUrl" to "@js:''")),
                "--${server.url("c1")}",
            )
            assertTrue(report.lines.any { it.contains("无下一章上下文") })
            assertTrue(report.lines.any { it.contains("nextContentUrl <empty> (skipped)") })
            val data = report.data as Map<*, *>
            assertEquals(1.0, (data["pages"] as Number).toDouble(), 0.0)
            assertEquals("complete", (data["contentDiagnostics"] as Map<*, *>)["endReason"])
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNull("空串 skip 后不得再请求当前页", server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test fun htmlShapedReplaceRegexDoesNotMatchPlainText() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div id='content'>正文<div class='tail'>广告尾巴</div></div>"))
            server.start()
            val report = contentRuntime().debug(
                contentSource(server, mapOf("replaceRegex" to "##<div class=.tail.>.*?</div>##")),
                "--${server.url("c1")}",
            )
            val data = report.data as Map<*, *>
            val merged = data["mergedBeforeReplace"].toString()
            val content = data["content"].toString()
            assertTrue(merged.contains("广告尾巴"))
            assertTrue("formatKeepImg 后不应再含 HTML 标签", !merged.contains("<div"))
            assertTrue("HTML 形态 replaceRegex 在纯文本上不生效", content.contains("广告尾巴"))
        }
    }

    private fun listSearchSource(server: MockWebServer, explore: Boolean = false): String = Gson().toJson(buildMap {
        put("bookSourceName", "测试")
        put("bookSourceUrl", server.url("/").toString())
        put("searchUrl", "search?q={{key}}")
        put("ruleSearch", mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"))
        put("ruleToc", mapOf("chapterList" to "a"))
        put("ruleContent", mapOf("content" to "#c"))
        if (explore) {
            put("exploreUrl", "全部::list")
            put("ruleExplore", mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"))
        }
    })

    private fun listRuntime() = EmbeddedLegadoRuntime(
        HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()),
    )

    private fun assertChallengeLine(lines: List<String>) {
        val hit = lines.firstOrNull { it.contains("【站点挑战】") }
        assertNotNull("应有站点挑战行: $lines", hit)
        assertTrue(hit!!.contains("Cloudflare"))
        assertTrue(hit.contains("Just a moment"))
        assertTrue(hit.contains("cf-mitigated"))
        val blob = lines.joinToString("\n")
        assertFalse(blob.contains("cf_clearance"))
        assertFalse(blob.contains("SECRET_COOKIE"))
        assertFalse(blob.contains("set-cookie", ignoreCase = true))
    }

    @Test fun looksLikeSiteChallengeOnlyMatchesExplicitSignals() {
        val cfHeader = mapOf("cf-mitigated" to "challenge")
        assertTrue(EmbeddedLegadoRuntime.looksLikeSiteChallenge(cfHeader, "Forbidden"))
        assertTrue(EmbeddedLegadoRuntime.looksLikeSiteChallenge(mapOf("Cf-Mitigated" to "challenge"), ""))
        assertTrue(EmbeddedLegadoRuntime.looksLikeSiteChallenge(emptyMap(), "<title>Just a moment...</title>"))
        assertTrue(EmbeddedLegadoRuntime.looksLikeSiteChallenge(emptyMap(), "window._cf_chl_opt={}"))
        assertTrue(EmbeddedLegadoRuntime.looksLikeSiteChallenge(emptyMap(), "/cdn-cgi/challenge-platform/h/b"))
        assertFalse(EmbeddedLegadoRuntime.looksLikeSiteChallenge(emptyMap(), "Forbidden"))
        assertFalse(EmbeddedLegadoRuntime.looksLikeSiteChallenge(emptyMap(), "HTTP 403"))
        assertFalse(EmbeddedLegadoRuntime.looksLikeSiteChallenge(mapOf("x-forbidden" to "1"), "Access denied"))
        assertFalse(EmbeddedLegadoRuntime.looksLikeSiteChallenge(emptyMap(), "cdn.cloudflare.com static asset"))
        assertNull(EmbeddedLegadoRuntime.siteChallengeLine(emptyMap(), "Forbidden"))
        assertEquals(EmbeddedLegadoRuntime.SITE_CHALLENGE_LINE, EmbeddedLegadoRuntime.siteChallengeLine(cfHeader, "x"))
    }

    @Test fun searchCfMitigatedHeaderMarksChallengeAndIsVerificationNotEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(403)
                    .setHeader("cf-mitigated", "challenge")
                    .setHeader("set-cookie", "cf_clearance=SECRET_COOKIE; Path=/")
                    .setBody("Forbidden"),
            )
            server.start()
            val report = listRuntime().debug(listSearchSource(server), "夜跑别在雷雨天")
            assertChallengeLine(report.lines)
            assertTrue(report.lines.any { it.startsWith("HTTP 403") })
            val ev = SourceCheckEvidence.of("搜索", report)
            assertFalse(ev.ok)
            assertEquals("verification", ev.category)
            assertEquals(403, ev.facts["statusCode"])
            assertEquals(0, ev.facts["resultCount"])
            val facts = ev.facts.entries.joinToString(" ") { "${it.key}=${it.value}" }
            assertFalse(facts.contains("SECRET_COOKIE"))
            assertFalse(facts.contains("cf_clearance"))
        }
    }

    @Test fun searchJustAMomentBodyMarksChallenge() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(403)
                    .setHeader("set-cookie", "cf_clearance=SECRET_COOKIE")
                    .setBody("<html><title>Just a moment...</title></html>"),
            )
            server.start()
            val report = listRuntime().debug(listSearchSource(server), "夜跑别在雷雨天")
            assertChallengeLine(report.lines)
            assertEquals("verification", SourceCheckEvidence.of("搜索", report).category)
        }
    }

    @Test fun searchCfChlAndChallengePlatformBodyMarksChallenge() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("var _cf_chl_opt = {};"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("<script src=\"/cdn-cgi/challenge-platform/h/b\"></script>"))
            server.start()
            val source = listSearchSource(server)
            val cfChl = listRuntime().debug(source, "夜跑别在雷雨天")
            val platform = listRuntime().debug(source, "夜跑别在雷雨天")
            assertChallengeLine(cfChl.lines)
            assertChallengeLine(platform.lines)
            assertEquals("verification", SourceCheckEvidence.of("搜索", cfChl).category)
            assertEquals("verification", SourceCheckEvidence.of("搜索", platform).category)
        }
    }

    @Test fun exploreCfMitigatedHeaderMarksChallenge() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(403)
                    .setHeader("cf-mitigated", "challenge")
                    .setBody("Forbidden"),
            )
            server.start()
            val report = listRuntime().debug(listSearchSource(server, explore = true), "全部::${server.url("list")}")
            assertEquals("发现", report.type)
            assertChallengeLine(report.lines)
            assertEquals("verification", SourceCheckEvidence.of("发现", report).category)
        }
    }

    @Test fun searchPlain403DoesNotMarkCloudflareAndStaysEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
            server.start()
            val report = listRuntime().debug(listSearchSource(server), "夜跑别在雷雨天")
            assertTrue(report.lines.any { it.startsWith("HTTP 403") })
            assertFalse(report.lines.any { it.contains("【站点挑战】") })
            assertFalse(report.lines.any { it.contains("Cloudflare") })
            assertFalse(report.lines.any { it.contains("cf-mitigated") })
            assertFalse(report.lines.any { it.contains("Just a moment") })
            val ev = SourceCheckEvidence.of("搜索", report)
            assertEquals("empty", ev.category)
            assertEquals(403, ev.facts["statusCode"])
        }
    }

    @Test fun searchHttp200EmptyWithoutChallengeStaysEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html><body><p>无结果</p></body></html>"))
            server.start()
            val report = listRuntime().debug(listSearchSource(server), "夜跑别在雷雨天")
            assertFalse(report.lines.any { it.contains("【站点挑战】") })
            val ev = SourceCheckEvidence.of("搜索", report)
            assertEquals("empty", ev.category)
            assertEquals(200, ev.facts["statusCode"])
        }
    }
}
