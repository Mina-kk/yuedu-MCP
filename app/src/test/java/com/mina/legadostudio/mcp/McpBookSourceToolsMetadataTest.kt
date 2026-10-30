package com.mina.legadostudio.mcp

import com.google.gson.JsonParser
import com.mina.legadostudio.domain.RecentCheckOutcomes
import com.mina.legadostudio.reader.ReaderExecutionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpBookSourceToolsMetadataTest {
    @Test fun `injects selected source type only when absent`() {
        val source = JsonParser.parseString("""{"bookSourceUrl":"https://example.test/source"}""").asJsonObject
        assertEquals(listOf("bookSourceType"), injectSelectedBookSourceType(source, 0))
        assertEquals(0, source.get("bookSourceType").asInt)
        assertEquals(emptyList<String>(), injectSelectedBookSourceType(source, 1))

        val explicit = JsonParser.parseString("""{"bookSourceType":null}""").asJsonObject
        assertEquals(listOf("bookSourceType"), injectSelectedBookSourceType(explicit, 2))
        assertEquals(emptyList<String>(), injectSelectedBookSourceType(JsonParser.parseString("{}").asJsonObject, -1))
    }

    @Test fun `reports json string normalization fields`() {
        val obj = JsonParser.parseString("""{"bookSourceUrl":"https://example.test/source"}""").asJsonObject
        val fields = JsonParser.parseString("""{"header":{"User-Agent":"agent"},"ruleSearch":{"bookList":".book"}}""").asJsonObject
        val normalized = mutableListOf<String>()
        McpSourceTools.mergeFieldsInto(obj, fields, normalized)
        assertEquals(listOf("header"), normalized)
        assertTrue(obj.get("header").isJsonPrimitive)
        assertTrue(obj.get("ruleSearch").isJsonObject)
    }

    @Test fun `flags edits after sandbox failure only until a later reader execution`() {
        val url = "https://example.test/source"
        RecentCheckOutcomes.clear()
        ReaderExecutionRegistry.clear()
        val checkAt = System.currentTimeMillis() - 10L
        RecentCheckOutcomes.record(url, hadFailure = true, atMs = checkAt)
        assertTrue(shouldFlagEditWithoutReaderEvidence(" $url "))

        ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record(kind = "debug", at = checkAt - 1L, executed = true), now = checkAt)
        assertTrue(shouldFlagEditWithoutReaderEvidence(url))
        ReaderExecutionRegistry.record(url, ReaderExecutionRegistry.Record(kind = "debug", at = checkAt + 1L, executed = true), now = checkAt + 1L)
        assertFalse(shouldFlagEditWithoutReaderEvidence(url))

        RecentCheckOutcomes.record(url, hadFailure = false, atMs = checkAt + 2L)
        assertFalse(shouldFlagEditWithoutReaderEvidence(url))
        RecentCheckOutcomes.clear()
        ReaderExecutionRegistry.clear()
    }
}
