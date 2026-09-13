package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
            runtime.debug(source, "万相之王")
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertTrue(recorded.body.readUtf8().contains("keyboard="))
            assertTrue(EmbeddedLegadoRuntime.JS_TEMPLATE.containsMatchIn("prefix{{java.time()}}suffix"))
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
}
