package com.mina.legadostudio.runtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mina.legadostudio.domain.BookSourceValidator
import com.mina.legadostudio.network.HttpFetcher
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedExploreFallbackTest {
    private val html = "<div class='book'><a href='/book/1'>搜索书名</a><i>搜索作者</i></div><div class='explore'><a href='/book/2'>发现书名</a></div>"
    private fun source(explore: String = "{}"): JsonObject = JsonParser.parseString("""
        {"bookSourceName":"fallback","bookSourceUrl":"https://example.com",
         "ruleExplore":$explore,
         "ruleSearch":{"bookList":".book","name":"a@text","author":"i@text","bookUrl":"a@href"}}
    """).asJsonObject

    private fun debug(root: JsonObject, body: String = html): LegadoRuntime.DebugReport = runBlocking {
        val fetcher = HttpFetcher()
        EmbeddedLegadoRuntime(fetcher, BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(fetcher, Gson()))
            .debug(root.toString(), "分类::https://example.com/list", { request ->
                assertEquals("https://example.com/list", request.url)
                HttpFetcher.FetchResult(200, requireNotNull(request.url), emptyMap(), body, 0)
            })
    }

    private fun books(report: LegadoRuntime.DebugReport) = report.data as List<*>

    @Test fun emptyExploreUsesWholeSearchBlock() {
        val report = debug(source())
        assertEquals(1, books(report).size)
        val book = books(report).single() as Map<*, *>
        assertEquals("搜索书名", book["name"])
        assertEquals("搜索作者", book["author"])
        assertEquals("https://example.com/book/1", book["bookUrl"])
    }

    @Test fun absentNullBlankAndPartialExploreUseWholeSearchBlock() {
        val absent = source().apply { remove("ruleExplore") }
        for (root in listOf(absent, source("null"), source("""{"bookList":"  ","name":"missing@text"}"""), source("""{"name":"missing@text"}"""))) {
            val book = books(debug(root)).single() as Map<*, *>
            assertEquals("搜索书名", book["name"])
            assertEquals("搜索作者", book["author"])
            assertEquals("https://example.com/book/1", book["bookUrl"])
        }
    }

    @Test fun explicitExploreWinsWithoutMergingSearchFields() {
        val book = books(debug(source("""{"bookList":".explore","name":"a@text"}"""))).single() as Map<*, *>
        assertEquals("发现书名", book["name"])
        assertEquals("", book["bookUrl"])
        assertEquals("", book["author"])
    }

    @Test fun explicitEmptyResultDoesNotRetrySearch() {
        assertTrue(books(debug(source("""{"bookList":".not-found","name":"a@text"}"""))).isEmpty())
    }

    @Test fun missingBothRuleBlocksStillFails() {
        val root = source().apply { remove("ruleExplore"); remove("ruleSearch") }
        assertTrue(runCatching { debug(root) }.isFailure)
    }

    @Test fun malformedExploreStillFailsRatherThanFallingBack() {
        for (value in listOf("[]", "\"bad\"", """{"bookList":{}}""")) {
            assertTrue(value, runCatching { debug(source(value)) }.isFailure)
        }
    }

    private fun assertMalformedExploreBookList(value: String) {
        val error = runCatching { debug(source("""{"bookList":$value,"name":"a@text"}""")) }.exceptionOrNull()
        assertTrue("$value: $error", error is IllegalArgumentException)
        assertEquals("ruleExplore.bookList 必须是基本类型或 null", error?.message)
    }

    @Test fun emptyStringArrayBookListIsRejected() = assertMalformedExploreBookList("""[""]""")

    @Test fun blankStringArrayBookListIsRejected() = assertMalformedExploreBookList("""[" "]""")

    @Test fun selectorArrayBookListIsRejected() = assertMalformedExploreBookList("""[".explore"]""")

    @Test fun objectBookListErrorIdentifiesExploreRule() = assertMalformedExploreBookList("{}")

    @Test fun fallbackFieldErrorsIdentifySearchRule() {
        val root = source().apply { getAsJsonObject("ruleSearch").addProperty("name", "@css:[@text") }
        val result = runCatching { debug(root) }
        val errors = result.getOrNull()?.fieldErrors.orEmpty().joinToString("\n") + result.exceptionOrNull()?.message.orEmpty()
        assertTrue(errors, errors.contains("ruleSearch.name"))
    }

    @Test fun fallbackDoesNotBypassChallengeGuard() {
        val error = runCatching {
            debug(source(), "<html>内容正在载入<script>var c2='fixture';</script><script src='/nnxswnn/challenge.js'></script></html>")
        }.exceptionOrNull()
        assertTrue(error.toString(), error is com.mina.legadostudio.network.JsChallengeException)
    }
}
