package com.mina.legadostudio.mcp

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SourceRawSaveTest {
    @Test fun originalSourceTextSurvivesNewAndIdempotentSave() {
        val raw = "{\n  \"bookSourceName\" : \"原文\", \"bookSourceUrl\":\"https://example.org/#a\",\n  \"weight\":123456789012345678901234567890, \"rule\":\"\\u4e2d\\n@js:result\"\n}\n"
        val obj = JsonParser.parseString(raw).asJsonObject
        val first = McpSourceTools.decideSave(obj, false, emptyList(), 1, rawSource = raw)
        assertEquals(raw, first.project.sourceJson)
        val again = McpSourceTools.decideSave(obj, false, listOf(first.project), 2, rawSource = raw)
        assertEquals(first.project.id, again.project.id)
        assertEquals(raw, again.project.sourceJson)
    }
    @Test fun rawCandidateCannotHideDifferentRules() {
        val obj = JsonParser.parseString("""{"bookSourceUrl":"https://example.org","weight":123456789012345678901234567890}""").asJsonObject
        val different = obj.toString().replace("7890}", "7891}")
        assertTrue(runCatching { McpSourceTools.decideSave(obj, false, emptyList(), 1, rawSource = different) }.isFailure)
    }
}
