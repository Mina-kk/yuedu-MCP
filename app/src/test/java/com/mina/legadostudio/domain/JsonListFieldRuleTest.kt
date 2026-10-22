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

class JsonListFieldRuleTest {
    private val body = """{"code":0,"data":{"list":[{"bookName":"书一","author":"甲","id":"101"},{"bookName":"书二","author":"乙","id":"102"}]}}"""

    private fun run(name: String, author: String, url: String): List<Map<*, *>> = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
            server.start()
            val source = """
              {"bookSourceName":"json","bookSourceUrl":"${server.url("/")}",
               "searchUrl":"/api/search?q={{key}}",
               "ruleSearch":{"bookList":"$.data.list[*]","name":"$name","author":"$author","bookUrl":"$url"}}
            """.trimIndent()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            @Suppress("UNCHECKED_CAST")
            runtime.debug(source, "kw").data as List<Map<*, *>>
        }
    }

    @Test fun absoluteJsonPathFieldsOnListItems() {
        val books = run("\$.bookName", "\$.author", "\$.id")
        assertEquals(2, books.size)
        assertEquals(true, (books[1]["bookUrl"] as String).endsWith("/102"))
        assertEquals("书一", books[0]["name"])
        assertEquals("甲", books[0]["author"])
        assertEquals("书二", books[1]["name"])
    }

    @Test fun brokenFieldRuleReportsFieldNameAndRule() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
            server.start()
            val source = """
              {"bookSourceName":"json","bookSourceUrl":"${server.url("/")}",
               "searchUrl":"/api/search?q={{key}}",
               "ruleSearch":{"bookList":"$.data.list[*]","name":"$.bookName","bookUrl":"$.[[["}}
            """.trimIndent()
            val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
            val report = runtime.debug(source, "kw")
            val errors = report.fieldErrors.orEmpty()
            assertTrue(errors.toString(), errors.any { it.startsWith("ruleSearch.bookUrl 规则解析失败（JSONPath）：\$.[[[") })
            @Suppress("UNCHECKED_CAST")
            assertEquals("书一", (report.data as List<Map<*, *>>)[0]["name"])
        }
    }

    @Test fun jsoupDocumentMethodsCallableFromRhino() {
        val out = RhinoEvaluator(HttpFetcher(), Gson()).evaluate(
            """
            var d = Jsoup.parse('<html><head><title>T1</title></head><body><div id="x"><a href="/a">A</a><a href="/b">B</a></div></body></html>');
            [d.title(), d.select('a').size(), d.getElementById('x').getElementsByTag('a').first().attr('href'), d.body().select('a').last().text()].join('|')
            """.trimIndent()
        )
        assertEquals("T1|2|/a|B", out.value)
    }

    @Test fun relativeKeyFieldsOnListItems() {
        val books = run("bookName", "author", "id")
        assertEquals(2, books.size)
        assertEquals("书一", books[0]["name"])
        assertEquals("乙", books[1]["author"])
        assertEquals(true, (books[0]["bookUrl"] as String).endsWith("/101"))
    }
}
