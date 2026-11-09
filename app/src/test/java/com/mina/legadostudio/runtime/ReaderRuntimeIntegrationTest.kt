package com.mina.legadostudio.runtime

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * debug 链路集成回归：inspect / 搜索列表 / 详情字段都走同一 ReaderRuleSession 核，
 * 变量按 chapter→book→ruleData→source 层级共享，bodyJs 先于 loginCheckJs。
 */
class ReaderRuntimeIntegrationTest {

    private fun runtime(fetcher: HttpFetcher = HttpFetcher()) = EmbeddedLegadoRuntime(
        fetcher,
        BookSourceValidator(),
        LegadoRuleEngine(),
        RhinoEvaluator(fetcher, Gson()),
    )

    @Test fun inspectUsesRuleCoreForChainAndRegexGroups() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    "<div class='book'><a href='/b/1'>书名一</a><span class='author'>作者甲</span></div>",
                ),
            )
            server.start()
            val report = runtime().inspect(
                LegadoRuntime.InspectRequest(
                    url = server.url("/list").toString(),
                    rule = ".book<js>java.setContent(result); java.put('k', java.getString('a@text')); java.get('k') + '|' + src.length</js>",
                ),
            )
            val output = report.output
            assertNotNull("inspect 应产出结果", output)
            val first = output!!.first.orEmpty()
            assertTrue(first, first.startsWith("书名一|"))
            // src 是原始页面，不是上一段结果（旧实现会把 src 设成 previous）
            assertTrue("src 必须是原始页面长度：$first", first.substringAfter('|').toInt() > 20)
        }
    }

    @Test fun javaPutFromOneFieldIsVisibleInNextFieldOfSameDebug() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    "<div class='book'><a class='n' href='/b/1'>书名一</a><span class='a'>作者甲</span></div>",
                ),
            )
            server.start()
            val source = """
                {
                  "bookSourceName":"变量共享","bookSourceUrl":"${server.url("/")}",
                  "searchUrl":"${server.url("/search")}",
                  "ruleSearch":{
                    "bookList":".book",
                    "name":"a.n@text<js>java.put('mark', result + '!'); result</js>",
                    "author":".a@text<js>java.get('mark') + java.get('bookName')</js>",
                    "bookUrl":"a.n@href"
                  },
                  "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
                }
            """.trimIndent()
            val report = runtime().debug(source, "关键词")
            val books = report.data as List<*>
            val first = books.first() as Map<*, *>
            assertEquals("书名一", first["name"])
            // java.get('mark') 来自同一条目上一字段的 java.put；bookName 特殊键取当前 book.name
            assertEquals("书名一!书名一", first["author"])
        }
    }

    @Test fun separateDebugRunsDoNotShareJavaVariables() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setBody("<div class='book'><a class='n' href='/b/1'>书</a></div>")
            }
            server.start()
            val source = """
                {
                  "bookSourceName":"隔离","bookSourceUrl":"${server.url("/")}",
                  "searchUrl":"${server.url("/search")}",
                  "ruleSearch":{
                    "bookList":".book",
                    "name":"a.n@text<js>String(java.get('leak'))</js>",
                    "author":"<js>java.put('leak','X'); 'a'</js>",
                    "bookUrl":"a.n@href"
                  },
                  "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
                }
            """.trimIndent()
            val rt = runtime()
            val firstRun = (rt.debug(source, "k").data as List<*>).first() as Map<*, *>
            val secondRun = (rt.debug(source, "k").data as List<*>).first() as Map<*, *>
            // name 在 author 之前求值，两次运行都不应看到上一轮 put 的值
            assertEquals("", firstRun["name"])
            assertEquals("", secondRun["name"])
        }
    }

    @Test fun bodyJsRunsBeforeLoginCheckJs() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a class='n' href='/b/1'>原名</a></div>"))
            server.start()
            val source = """
                {
                  "bookSourceName":"顺序","bookSourceUrl":"${server.url("/")}",
                  "searchUrl":"${server.url("/search")},{\"bodyJs\":\"result.replace('原名','bodyJs')\"}",
                  "loginCheckJs":"result.body().indexOf('bodyJs') >= 0 ? result : java.log('loginCheck saw: ' + result.body())",
                  "ruleSearch":{
                    "bookList":".book",
                    "name":"a.n@text",
                    "author":"a.n@text",
                    "bookUrl":"a.n@href"
                  },
                  "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
                }
            """.trimIndent()
            val report = runtime().debug(source, "k")
            val first = (report.data as List<*>).first() as Map<*, *>
            assertEquals("bodyJs", first["name"])
            assertFalse(
                "loginCheckJs 不应看到 bodyJs 之前的正文：${report.jsLogs}",
                report.jsLogs.orEmpty().any { it.contains("loginCheck saw") },
            )
        }
    }

    @Test fun headerJsIsUsedForDebugRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a class='n' href='/b/1'>书</a></div>"))
            server.start()
            val source = """
                {
                  "bookSourceName":"请求头","bookSourceUrl":"${server.url("/")}",
                  "header":"@js: JSON.stringify({Referer: baseUrl + '/ref', 'X-Probe': 'js'})",
                  "searchUrl":"${server.url("/search")}",
                  "ruleSearch":{"bookList":".book","name":"a.n@text","author":"a.n@text","bookUrl":"a.n@href"},
                  "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
                }
            """.trimIndent()
            runtime().debug(source, "k")
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertEquals("js", recorded!!.getHeader("X-Probe"))
            assertTrue(recorded.getHeader("Referer").orEmpty().endsWith("/ref"))
        }
    }

    @Test fun urlOptionJsRewritesRequestUrl() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a class='n' href='/b/1'>书</a></div>"))
            server.start()
            val source = """
                {
                  "bookSourceName":"选项js","bookSourceUrl":"${server.url("/")}",
                  "searchUrl":"${server.url("/search")},{\"js\":\"result + '&from=opt'\"}",
                  "ruleSearch":{"bookList":".book","name":"a.n@text","author":"a.n@text","bookUrl":"a.n@href"},
                  "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
                }
            """.trimIndent()
            runtime().debug(source, "k")
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertTrue(recorded!!.path.orEmpty(), recorded.path.orEmpty().contains("from=opt"))
        }
    }

    @Test fun remoteJsLibWithoutFetcherFailsLoudlyNotSilently() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a class='n' href='/b/1'>书</a></div>"))
            server.start()
            val source = """
                {
                  "bookSourceName":"远程库","bookSourceUrl":"${server.url("/")}",
                  "jsLib":"{\"lib\":\"${server.url("/lib.js")}\"}",
                  "searchUrl":"${server.url("/search")}",
                  "ruleSearch":{"bookList":".book","name":"a.n@text","author":"a.n@text","bookUrl":"a.n@href"},
                  "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
                }
            """.trimIndent()
            // 关闭远程库加载 = 环境不支持，必须结构化失败而不是跳过 jsLib 后把报错归给规则
            val report = runCatching { runtime().debug(source, "k") }
            val error = report.exceptionOrNull()
            assertNotNull("远程 jsLib 未加载时必须失败：$report", error)
            assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("jsLib"))
        }
    }
}
