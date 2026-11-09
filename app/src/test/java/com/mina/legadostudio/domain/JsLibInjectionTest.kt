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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官方书源 jsLib 的运行时装载回归：
 * - jsLib 里的函数在 searchUrl / bookList / 目录 / 正文规则的 JS 段里可直接调用；
 * - 每次求值仍是全新作用域，jsLib 声明不被表达式返回值覆盖，也不跨求值泄漏；
 * - jsLib 声明为 http(s) URL 时不做外部加载，只在报告里留诊断；
 * - eval_js（未传 source）不凭空装载任何 jsLib。
 */
class JsLibInjectionTest {

    private fun runtime() = EmbeddedLegadoRuntime(
        HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson())
    )

    @Test fun jsLibFunctionsAvailableInSearchAndListRules() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/1'>书名</a><span>作者甲</span></div>"))
            server.start()
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "jsLib":"function mkKey(k){ return 'q_'+k; } function decorateName(n){ return '['+n+']'; }",
              "searchUrl":"search?kw={{mkKey(key)}}",
              "ruleSearch":{"bookList":".book","name":"a@text<js>decorateName(result)</js>","author":"span@text","bookUrl":"a@href"},
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#content@html"}
            }"""
            val report = runtime().debug(source, "万相")
            val recorded = server.takeRequest()
            assertEquals("/search?kw=q_%E4%B8%87%E7%9B%B8", recorded.path)
            val books = report.data as List<*>
            assertEquals("[书名]", (books.first() as Map<*, *>)["name"])
        }
    }

    @Test fun jsLibAvailableInTocAndContentRules() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/toc" -> MockResponse().setBody("<a class='ch' href='/c1'>第一章</a>")
                    "/c1" -> MockResponse().setBody("<div id='c'>正文基础</div>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "jsLib":"var SUFFIX='!'; function tag(t){ return t+SUFFIX; }",
              "ruleToc":{"chapterList":"a.ch","chapterName":"@text<js>tag(result)</js>","chapterUrl":"@href"},
              "ruleContent":{"content":"#c@text<js>tag(result)</js>"}
            }"""
            val rt = runtime()
            val toc = rt.debug(source, "++${server.url("toc")}")
            val chapters = (toc.data as Map<*, *>)["chapters"] as List<*>
            assertEquals("第一章!", (chapters.first() as Map<*, *>)["name"])
            val content = rt.debug(source, "--${server.url("c1")}")
            assertTrue((content.data as Map<*, *>)["content"].toString().contains("正文基础!"))
        }
    }

    @Test fun jsLibThisBindingSeesJavaAndSource() = runBlocking {
        // 官方 jsLib 惯用法 const {java,source}=this：函数被调用时 this 取当次顶层作用域 globalThis
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/1'>n</a></div>"))
            server.start()
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "jsLib":"function enc(k){ const {java,source}=this; source.put('touched','1'); return java.encodeURI(k); }",
              "searchUrl":"search?kw={{enc(key)}}",
              "ruleSearch":{"bookList":".book","name":"a@text","bookUrl":"a@href"},
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
            }"""
            val report = runtime().debug(source, "关键词")
            assertEquals("/search?kw=%E5%85%B3%E9%94%AE%E8%AF%8D", server.takeRequest().path)
            assertEquals(1, (report.data as List<*>).size)
        }
    }

    @Test fun expressionCannotOverwriteJsLibFunctionAcrossEvals() = runBlocking {
        // 同名 var/赋值只写进当次作用域 globalThis，父层 jsLib 不被覆盖；下一次求值函数仍可用
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/1'>x</a></div>"))
            server.start()
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "jsLib":"function pick(){ return 'fromLib'; }",
              "searchUrl":"search?q={{key}}",
              "ruleSearch":{"bookList":".book",
                "name":"<js>var pick = function(){ return 'shadow'; }; pick()</js>",
                "bookUrl":"<js>pick() + '|' + 'a@href'</js>"},
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
            }"""
            val report = runtime().debug(source, "k")
            val book = (report.data as List<*>).first() as Map<*, *>
            // name 字段的表达式用自己的 shadow；紧接着 bookUrl 是全新作用域，pick() 仍解析到 jsLib
            // bookUrl 会被 resolve() 拼上 host 并把 '|' 转义为 %7C，只校验前缀函数返回值存在
            assertEquals("shadow", book["name"])
            assertTrue("jsLib 函数应不被上一次求值的同名 var 覆盖, got: ${book["bookUrl"]}",
                book["bookUrl"].toString().contains("fromLib"))
        }
    }

    @Test fun jsLibStateDoesNotLeakAcrossEvals() = runBlocking {
        // jsLib 顶层 var 在每次求值重新装载；表达式对它赋值不累积（隔离），但同一表达式内可读
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val libProvider: () -> String? = { "var counter = 0; function bump(){ return ++counter; }" }
        val b = mapOf(RhinoEvaluator.BINDING_JSLIB to libProvider)
        val first = evaluator.evaluate("bump(); bump()", bindings = b)
        val second = evaluator.evaluate("counter", bindings = b)
        assertTrue(first.value == "2.0" || first.value == "2")
        assertTrue(second.value == "0.0" || second.value == "0")
    }

    @Test fun jsLibUrlFormIsNotFetchedAndReportsDiagnostic() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/search") == true -> MockResponse().setBody("<div class='book'><a href='/b/1'>x</a></div>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val evil = server.url("/evil.js")
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "jsLib":"$evil",
              "searchUrl":"search?q={{key}}",
              "ruleSearch":{"bookList":".book","name":"a@text","bookUrl":"a@href"},
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
            }"""
            val error = runCatching { runtime().debug(source, "k") }.exceptionOrNull()
            assertTrue("裸 URL 不是上游远程库 map，不得静默跳过", error != null)
            assertTrue(error!!.message.orEmpty().contains("jsLib"))
            // 加载失败必须终止，不能跳过依赖继续搜索。
            assertNull(server.takeRequest(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
    }

    @Test fun evalJsWithoutSourceDoesNotLoadJsLib() {
        // eval_js 路径（无 source JSON）不凭空装载 jsLib：调用未声明函数报「不是函数/找不到」
        val error = runCatching {
            RhinoEvaluator(HttpFetcher(), Gson()).evaluate("typeof thisIsNotDeclared === 'function' ? thisIsNotDeclared() : 'absent'")
        }.getOrThrow()
        assertEquals("absent", error.value)
    }

    @Test fun brokenJsLibFailsWithDiagnosableError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/1'>n</a></div>"))
            server.start()
            // jsLib 惰性装载：必须有规则触发 JS 求值才会装载；用 <js> 段强制触发，断言错误可诊断
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "jsLib":"throw new Error('jsLib-broken-marker');",
              "searchUrl":"search?q={{key}}",
              "ruleSearch":{"bookList":".book","name":"<js>'x'</js>"},
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
            }"""
            val error = runCatching { runtime().debug(source, "k") }.exceptionOrNull()
            assertTrue(error != null)
            assertTrue(error!!.message.orEmpty().contains("jsLib"))
            assertTrue(error.message.orEmpty().contains("jsLib-broken-marker"))
        }
    }

    @Test fun checkSourceSharesSameJsLibPath() = runBlocking {
        // check_source 与 debug_source 共用 debug 链路：jsLib 函数在各阶段都可用
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/search") == true -> MockResponse().setBody("<div class='book'><a href='/b/1'>n</a></div>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "jsLib":"function wrap(t){ return 'W'+t; }",
              "searchUrl":"search?q={{key}}",
              "ruleSearch":{"bookList":".book","name":"a@text<js>wrap(result)</js>","bookUrl":"a@href"},
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
            }"""
            val result = CheckSourceRunner.run(CheckSourceRunner.Request(source, searchKey = "k"), runtime(), null)
            val search = result["搜索"] as com.mina.legadostudio.runtime.LegadoRuntime.DebugReport
            assertEquals("Wn", ((search.data as List<*>).first() as Map<*, *>)["name"])
        }
    }

    @Test fun noJsLibSourceStillWorks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/1'>n</a></div>"))
            server.start()
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "searchUrl":"search?q={{key}}",
              "ruleSearch":{"bookList":".book","name":"a@text","bookUrl":"a@href"},
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
            }"""
            val report = runtime().debug(source, "k")
            assertEquals(1, (report.data as List<*>).size)
            assertFalse(report.lines.any { it.contains("jsLib") })
        }
    }
}
