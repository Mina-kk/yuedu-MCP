package com.mina.legadostudio.domain

import io.legado.app.model.analyzeRule.LegadoRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class LegadoRuleEngineTest {
    private val engine = LegadoRuleEngine()

    @Test fun runsOfficialCssRule() {
        val html = "<div class='book'><a href='/b/1'>标题一</a></div>"
        assertEquals("标题一", engine.extract(html, ".book a@text").first)
        assertEquals("/b/1", engine.extract(html, ".book a@href").first)
    }

    @Test fun runsOfficialXpathRule() {
        val html = "<div class='book'><a>标题二</a></div>"
        assertEquals("标题二", engine.extract(html, "@XPath://div[@class='book']/a/text()").first)
    }

    @Test fun runsOfficialJsonPathRule() {
        assertEquals(listOf("甲", "乙"), engine.extract("{\"books\":[{\"name\":\"甲\"},{\"name\":\"乙\"}]}", "@Json:$.books[*].name").values)
    }

    @Test fun runsOfficialRegexRule() {
        assertEquals("标题三", engine.extract("xx标题三yy", ":标题三").first)
    }

    @Test fun combinesCssRulesWithOrAndAnd() {
        val html = "<div class='book'><span class='name'>书名</span><span class='author'>作者</span></div>"
        assertEquals("书名", engine.extract(html, ".missing@text||.name@text").first)
        assertEquals(listOf("书名", "作者"), engine.extract(html, ".name@text&&.author@text").values)
    }

    @Test fun cssOrUsesOnePrefixForTheWholeRule() {
        val html = """<div id="play_0"><ul><li><a href="/w/152957.html">第一章 我有三个相宫</a></li></ul></div>"""
        val rule = "@css:.m-box ul a[href\$='.html']||#play_0 ul a||ul a[href\$='.html']"
        val elements = engine.elements(html, rule)
        org.junit.Assert.assertTrue(elements.joinToString().contains("第一章"))
    }

    @Test fun mixedDefaultAndCssSegmentsInOrRule() {
        val html = """<html><head><meta property="k" content="书名甲"></head><body><h1>书名乙</h1></body></html>"""
        // 第一段为空时落到 @css: 段
        assertEquals("书名甲", engine.extract(html, "@css:.missing@text||meta[property=k]@content").first)
        // 官方 || 语义：第一个非空即停止，不合并
        assertEquals(listOf("书名乙"), engine.extract(html, "@css:h1@text||meta[property=k]@content").values)
    }

    @Test(expected = Error::class)
    fun invalidCssIsNotSilentlyTreatedAsNoMatch() {
        // Fixed Team RuleAnalyzer.kt:228 throws Error for unbalanced groups, before JSoup runs.
        engine.elements("<p>x</p>", "@css:[||p")
    }

    @Test fun leadingMinusReversesList() {
        val html = "<ul><li>a</li><li>b</li><li>c</li></ul>"
        assertEquals(listOf("a", "b", "c"), engine.extract(html, "li@text").values)
        assertEquals(listOf("c", "b", "a"), engine.extract(html, "-li@text").values)
    }
}
