package com.mina.legadostudio.mcp

import com.google.gson.Gson
import com.mina.legadostudio.domain.BookSourceValidator
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.LegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fetch_page / inspect_rule 输出合同：requestedUrl/redirectChain（仅 URL，无逐跳 status）、
 * inspect_rule 的 countOnly/limit、以及本地 302 与分页规则。不联网真实站点。
 */
class McpPageFetchEvidenceTest {
    private val gson = Gson()

    private fun page(
        finalUrl: String,
        chain: List<String> = emptyList(),
        body: String = "ok",
        code: Int = 200,
    ) = HttpFetcher.FetchResult(code, finalUrl, emptyMap(), body, 1, redirectChain = chain)

    @Test fun navigationKeepsRequestedUrlAndUrlOnlyRedirectChain() {
        val requested = "http://wap.old.test/"
        val finalUrl = "http://wap.new.test/"
        val chain = listOf(requested, finalUrl)
        val nav = McpPageFetchEvidence.navigation(requested, page(finalUrl, chain))
        assertEquals(requested, nav["requestedUrl"])
        assertEquals(finalUrl, nav["finalUrl"])
        assertEquals(chain, nav["redirectChain"])
        assertEquals(true, nav["crossHostRedirect"])
        assertFalse(nav.containsKey("status"))
        @Suppress("UNCHECKED_CAST")
        val urls = nav["redirectChain"] as List<Any?>
        urls.forEach { assertTrue(it is String) }
        assertFalse(gson.toJson(nav).contains("\"status\""))
    }

    @Test fun sameHostRedirectIsNotCrossHost() {
        val start = "http://example.test/start"
        val finalUrl = "http://example.test/final"
        val nav = McpPageFetchEvidence.navigation(start, page(finalUrl, listOf(start, finalUrl)))
        assertEquals(false, nav["crossHostRedirect"])
        assertEquals(start, nav["requestedUrl"])
    }

    @Test fun emptyChainFallsBackToRequestedArgWithoutFabricatingHops() {
        val requested = "http://wap.old.test/s?q=book"
        val finalUrl = "http://wap.new.test/"
        val nav = McpPageFetchEvidence.navigation(requested, page(finalUrl, emptyList()))
        assertEquals(requested, nav["requestedUrl"])
        assertEquals(finalUrl, nav["finalUrl"])
        assertEquals(emptyList<String>(), nav["redirectChain"])
        assertEquals(true, nav["crossHostRedirect"])
    }

    @Test fun queryStringIsPreservedAndNotTreatedAsHopStatus() {
        val start = "http://example.test/s?q=book"
        val finalUrl = "http://example.test/s?q=book"
        val nav = McpPageFetchEvidence.navigation(start, page(finalUrl, listOf(start)))
        assertEquals(start, nav["requestedUrl"])
        assertEquals(false, nav["crossHostRedirect"])
        assertEquals(listOf(start), nav["redirectChain"])
    }

    @Test fun localHttpRedirectMatchesFetcherChainContract() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final?q=book"))
            server.enqueue(MockResponse().setBody("done"))
            server.start()
            val start = server.url("/start").toString()
            val result = HttpFetcher().fetch(HttpFetcher.FetchRequest(start))
            val nav = McpPageFetchEvidence.navigation(start, result)
            assertEquals(start, nav["requestedUrl"])
            assertEquals(result.finalUrl, nav["finalUrl"])
            assertTrue(result.finalUrl.contains("/final"))
            assertEquals(result.redirectChain, nav["redirectChain"])
            assertEquals(false, nav["crossHostRedirect"])
            assertTrue(result.redirectChain.first().contains("/start"))
            assertTrue(result.redirectChain.last().contains("/final"))
        }
    }

    @Test fun countOnlyOmitsValuesAndElementsWithoutSplittingABlob() = runBlocking {
        val items = (1..50).joinToString("") { "<div class='item'>Book$it|extra</div>" }
        val blob = "<div id='all'>${(1..50).joinToString("|") { "Book$it" }}</div>"
        val html = "<html><body>$items$blob</body></html>"
        val page = page("http://127.0.0.1/search", listOf("http://old.test/search", "http://127.0.0.1/search"), html)
        val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()))
        val many = runtime.inspectSnapshot(LegadoRuntime.InspectRequest(page.finalUrl, rule = "div.item@text"), page)
        assertEquals(50, many.output!!.count)
        assertEquals(50, many.output!!.values.size)

        val countOnly = McpPageFetchEvidence.shapeInspect(many.output, many.elements, countOnly = true, limit = null)
        @Suppress("UNCHECKED_CAST")
        val countMap = countOnly.output as Map<String, Any?>
        assertEquals(50, countMap["count"])
        assertFalse(countMap.containsKey("values"))
        assertNull(countOnly.elements)
        assertTrue(countOnly.valuesOmitted)
        assertTrue(countOnly.elementsOmitted)
        val countJson = gson.toJson(mapOf("output" to countOnly.output, "elements" to countOnly.elements))
        assertTrue("countOnly 不应序列化命中正文: $countJson", countJson.length < 80)
        assertFalse(countJson.contains("Book1"))
        assertFalse(countJson.contains("extra"))

        val limited = McpPageFetchEvidence.shapeInspect(many.output, many.elements, countOnly = false, limit = 3)
        @Suppress("UNCHECKED_CAST")
        val limitedOut = limited.output as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val limitedValues = limitedOut["values"] as List<String>
        assertEquals(listOf("Book1|extra", "Book2|extra", "Book3|extra"), limitedValues)
        assertEquals(50, limitedOut["count"])
        assertEquals(3, limited.valuesReturned)
        assertTrue(limited.valuesTruncated)
        assertEquals(many.elements, limited.elements)

        val blobReport = runtime.inspectSnapshot(LegadoRuntime.InspectRequest(page.finalUrl, rule = "#all@text"), page)
        assertEquals(1, blobReport.output!!.count)
        val blobLimited = McpPageFetchEvidence.shapeInspect(blobReport.output, blobReport.elements, countOnly = false, limit = 3)
        @Suppress("UNCHECKED_CAST")
        val blobOut = blobLimited.output as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val blobValues = blobOut["values"] as List<String>
        assertEquals(1, blobOut["count"])
        assertEquals(1, blobValues.size)
        assertTrue(blobValues.single().contains("Book1|Book2"))
        assertFalse(blobLimited.valuesTruncated)
    }

    @Test fun passthroughKeepsOriginalOutputObject() {
        val output = LegadoRuleEngine.Output(listOf("a", "b"), "a", 2)
        val shaped = McpPageFetchEvidence.shapeInspect(output, listOf("el"), countOnly = false, limit = null)
        assertTrue(shaped.output === output)
        assertEquals(listOf("el"), shaped.elements)
        assertFalse(shaped.valuesOmitted)
        assertFalse(shaped.valuesTruncated)
    }
}
