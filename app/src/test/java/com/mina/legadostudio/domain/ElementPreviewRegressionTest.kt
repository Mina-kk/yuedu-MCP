package com.mina.legadostudio.domain

import com.mina.legadostudio.runtime.LegadoStringRule
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class ElementPreviewRegressionTest {
    private val html = "<html><body><div><h1>Example Domain</h1><p>first</p><p>second</p></div></body></html>"

    @Test fun stripsLastRuleSuffixForPreview() {
        assertEquals("p", LegadoStringRule.elementRule("p@text"))
        assertEquals("p", LegadoStringRule.elementRule("p@href"))
        assertEquals("h1", LegadoStringRule.elementRule("h1"))
        assertEquals(".item", LegadoStringRule.elementRule(".item@text"))
        assertEquals("div.cover", LegadoStringRule.elementRule("div.cover@html##<[^>]+>"))
        assertEquals("", LegadoStringRule.elementRule("<js>result</js>"))
    }

    @Test fun strippedRulePreviewDoesNotThrowIndexOutOfBounds() {
        val engine = LegadoRuleEngine()
        val rule = LegadoStringRule.elementRule("p@text")
        assertEquals("p", rule)
        val elements = engine.elements(html, rule, LegadoRuleEngine.detect(rule))
        assertEquals(2, elements.size)
    }
}
