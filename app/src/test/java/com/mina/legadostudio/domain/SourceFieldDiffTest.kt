package com.mina.legadostudio.domain

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceFieldDiffTest {
    @Test fun `compares top-level values and rule children`() {
        val old = JsonParser.parseString(
            """{"bookSourceName":"old","enabled":true,"ruleSearch":{"bookList":".old","name":"a@text"},"ruleToc":{"chapterList":".chapter"}}""",
        ).asJsonObject
        val new = JsonParser.parseString(
            """{"bookSourceName":"new","enabled":true,"ruleSearch":{"bookList":".new","name":"a@text"},"ruleToc":{"chapterList":".chapter","chapterName":"a@text"},"searchUrl":"/search"}""",
        ).asJsonObject
        assertEquals(listOf("bookSourceName", "ruleSearch.bookList", "ruleToc.chapterName", "searchUrl"),
            SourceFieldDiff.changedFields(old, new))
    }

    @Test fun `object key order does not create a change`() {
        val old = JsonParser.parseString("""{"extra":{"a":1,"b":2}}""").asJsonObject
        val new = JsonParser.parseString("""{"extra":{"b":2,"a":1}}""").asJsonObject
        assertEquals(emptyList<String>(), SourceFieldDiff.changedFields(old, new))
    }
}
