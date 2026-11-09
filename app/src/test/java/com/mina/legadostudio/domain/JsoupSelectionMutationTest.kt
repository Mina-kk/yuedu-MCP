package com.mina.legadostudio.domain

import io.legado.app.model.analyzeRule.AnalyzeByJSoup
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class JsoupSelectionMutationTest {
    private val html = """<article><div class="item"><a href="/1"><img alt="书名"></a><div class="itemtxt"><p><span>连载中</span><span>玄幻小说</span></p><p><a>作者：田隶</a></p><ul><li><a>第336章 登龙</a></li></ul></div></div></article>"""
    private val fields = linkedMapOf(
        "class.itemtxt.0@tag.p.1@tag.a.0@text" to "作者：田隶",
        "class.itemtxt.0@tag.p.0@tag.span@text" to "连载中\n玄幻小说",
        "class.itemtxt.0@tag.li.0@tag.a.0@text" to "第336章 登龙",
    )

    @Test fun fieldOrderAndSessionReuseDoNotDeleteSourceNodes() {
        for (reuse in listOf(false, true)) for (reversed in listOf(false, true)) {
            val document = Jsoup.parse(html)
            val before = document.outerHtml()
            val item = document.selectFirst(".item")!!
            val shared = AnalyzeByJSoup(item)
            val entries = fields.entries.toList().let { if (reversed) it.reversed() else it }
            repeat(2) {
                for ((rule, expected) in entries) {
                    assertEquals(rule, expected, (if (reuse) shared else AnalyzeByJSoup(item)).getString(rule))
                    assertEquals("selection must not mutate DOM", before, document.outerHtml())
                }
            }
        }
    }

    @Test fun chainedElementSelectionDoesNotRemoveParent() {
        val document = Jsoup.parse(html)
        val before = document.outerHtml()
        assertEquals(2, AnalyzeByJSoup(document).getElements("class.itemtxt@tag.p.0@tag.span").size)
        assertEquals(before, document.outerHtml())
    }

    @Test fun exclusionIndexesOnlyDeselectResults() {
        val document = Jsoup.parse("<ul><li>A</li><li>B</li><li>C</li></ul>")
        val before = document.outerHtml()
        assertEquals(listOf("A", "C"), AnalyzeByJSoup(document).getElements("tag.li!1").map { it.text() })
        assertEquals(before, document.outerHtml())
    }
}
