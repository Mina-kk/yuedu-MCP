package com.mina.legadostudio.runtime

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import io.legado.app.model.analyzeRule.RuleAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Offline synthetic regressions, NOT a live book-source/reader acceptance result.
 * Oracle: Team 330e606c4d57e08adc056ff8ab7a4132c784da9a AnalyzeUrl.kt:188-243.
 * RuleAnalyzer.kt is byte-identical to that snapshot (SHA-256:
 * 74ab0152bce9d5e014cc69d9c6974cbb093d8353200f1452066f7d05102265a7).
 * Keep each input identical on both paths; do not rewrite rules to fit the sandbox.
 */
class ReaderUrlUpstreamParityTest {
    @Test fun urlTemplatesUseTheActualUpstreamDelimiterOverload() {
        val templates = listOf(
            "/search?q={{key}}&page={{page}}",
            "/search?q={{'prefix}}suffix'}}",
            "/search?q={{({a:{b:1}}).a.b}}",
            "/search?q={{'{{'}}",
            "/search?q={{unterminated",
        )
        for (template in templates) {
            val expectedScripts = mutableListOf<String>()
            val expected = RuleAnalyzer(template).innerRule("{{", "}}") {
                expectedScripts += it
                "VALUE"
            }
            val actualScripts = mutableListOf<String>()
            val actual = ReaderRequestTemplate.replaceTemplates(template, null) { script, _ ->
                actualScripts += script
                "VALUE"
            }
            assertEquals("script slices must match fixed Team: $template", expectedScripts, actualScripts)
            assertEquals("replacement must match fixed Team: $template", expected, actual)
        }
    }

    @Test fun completelyEmptyTemplateReplacementRetainsOriginalRuleLikeTeam() {
        // AnalyzeUrl.kt:229 only assigns the replaced text when it is nonempty.
        val rule = "{{''}}"
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        assertEquals(rule, ReaderRequestTemplate.replaceTemplates(rule, null) { script, previous ->
            evaluator.evaluateRaw(script, previous = previous).value
        })
    }

    @Test fun nullUrlJavascriptResultUsesUpstreamNullableToString() {
        // AnalyzeUrl.kt:200 calls evalJS(...).toString(), not ?.toString().orEmpty().
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        assertEquals("null", ReaderRequestTemplate.analyzeJs("<js>null</js>") { script, previous ->
            evaluator.evaluateRaw(script, previous = previous).value
        })
    }
}
