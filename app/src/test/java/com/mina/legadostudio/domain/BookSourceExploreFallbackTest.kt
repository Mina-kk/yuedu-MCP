package com.mina.legadostudio.domain

import org.junit.Assert.assertTrue
import com.google.gson.JsonParser
import org.junit.Test

class BookSourceExploreFallbackTest {
    private fun source(explore: String = "{}", search: String = """{"bookList":".book","name":"a@text","bookUrl":"a@href"}""") = """
        {"bookSourceName":"fallback","bookSourceUrl":"https://example.com",
         "exploreUrl":"分类::/list","ruleExplore":$explore,"ruleSearch":$search,
         "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#content@text"}}
    """.trimIndent()

    @Test fun emptyExploreUsesSearchListWithoutSearchUrl() {
        val report = BookSourceValidator().validate(source())
        assertTrue(report.issues.toString(), report.isValid)
    }

    @Test fun absentNullAndBlankExploreUseSearch() {
        val absent = JsonParser.parseString(source()).asJsonObject.apply { remove("ruleExplore") }
        for (json in listOf(absent.toString(), source("null"), source("""{"bookList":"  ","name":"wrong@text"}"""))) {
            val report = BookSourceValidator().validate(json)
            assertTrue(report.issues.toString(), report.isValid)
        }
    }

    @Test fun missingBothListsStillFails() {
        for (search in listOf("{}", "null", """{"bookList":" "}""")) {
            assertTrue(BookSourceValidator().validate(source(search = search)).issues.any { it.path == "ruleExplore.bookList" })
        }
    }

    @Test fun explicitExploreDoesNotRequireSearch() {
        assertTrue(BookSourceValidator().validate(source("""{"bookList":".explore"}""", "null")).isValid)
    }

    @Test fun malformedExploreIsNotSilentlyReplaced() {
        for (explore in listOf("[]", "\"broken\"", """{"bookList":{}}""", """{"bookList":[]}""")) {
            assertTrue(explore, BookSourceValidator().validate(source(explore)).issues.any { it.path == "ruleExplore.bookList" })
        }
    }

    @Test fun discoveryFallbackDoesNotRelaxOtherRequiredRules() {
        val root = JsonParser.parseString(source()).asJsonObject.apply {
            addProperty("searchUrl", "/search?q={{key}}")
            add("ruleSearch", JsonParser.parseString("{}"))
            add("ruleExplore", JsonParser.parseString("""{"bookList":".explore"}"""))
            remove("ruleToc")
            remove("ruleContent")
        }
        val issues = BookSourceValidator().validate(root.toString()).issues.map { it.path }
        assertTrue(issues.containsAll(listOf("ruleSearch.bookList", "ruleToc.chapterList", "ruleContent.content")))
    }
}
