package com.mina.legadostudio.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** read_knowledge / get_skill_reference / 语料分片共用的分页边界：offset、limit、query 的合法范围与翻页拼接。 */
class PagedTextTest {
    private val text = "0123456789abcdefghij"

    private fun failure(block: () -> Unit): String {
        val e = runCatching(block).exceptionOrNull()
        assertTrue("应抛 IllegalArgumentException，实际 $e", e is IllegalArgumentException)
        return e!!.message.orEmpty()
    }

    @Test fun offsetMustStayInsideText() {
        assertEquals("offset 超出正文范围", failure { PagedText.page(text, -1, 10) })
        assertEquals("offset 超出正文范围", failure { PagedText.page(text, text.length + 1, 10) })
        val atEnd = PagedText.page(text, text.length, 10)
        assertEquals("", atEnd["body"])
        assertEquals(false, atEnd["hasMore"])
        assertEquals(text.length, atEnd["nextOffset"])
    }

    @Test fun limitMustBeOneToTwelveThousand() {
        assertEquals("limit 必须是 1..12000", failure { PagedText.page(text, 0, 0) })
        assertEquals("limit 必须是 1..12000", failure { PagedText.page(text, 0, 12001) })
        assertEquals("0", PagedText.page(text, 0, 1)["body"])
        assertEquals(text, PagedText.page(text, 0, 12000)["body"])
    }

    @Test fun emptyTextReadsAsSingleEmptyPage() {
        val page = PagedText.page("", 0, 6000)
        assertEquals(true, page["found"])
        assertEquals("", page["body"])
        assertEquals(false, page["hasMore"])
        assertEquals(0, page["totalChars"])
    }

    @Test fun pagesJoinBackToOriginalWithoutGapOrOverlap() {
        val out = StringBuilder()
        var offset = 0
        var pages = 0
        do {
            val p = PagedText.page(text, offset, 7)
            out.append(p["body"])
            offset = p["nextOffset"] as Int
            pages++
        } while (p["hasMore"] == true)
        assertEquals(text, out.toString())
        assertEquals(3, pages)
    }

    @Test fun queryJumpsCaseInsensitivelyFromOffset() {
        val hit = PagedText.page(text, 0, 3, "ABC")
        assertEquals(true, hit["found"])
        assertEquals(10, hit["offset"])
        assertEquals("abc", hit["body"])
        val miss = PagedText.page(text, 11, 3, "abc")
        assertEquals(false, miss["found"])
        assertEquals(11, miss["offset"])
        assertEquals("", miss["body"])
    }

    @Test fun queryLengthMustBeOneTo256() {
        assertEquals("query 长度必须为 1..256", failure { PagedText.page(text, 0, 10, "") })
        assertEquals("query 长度必须为 1..256", failure { PagedText.page(text, 0, 10, "x".repeat(257)) })
        assertEquals(false, PagedText.page(text, 0, 10, "x".repeat(256))["found"])
    }
}
