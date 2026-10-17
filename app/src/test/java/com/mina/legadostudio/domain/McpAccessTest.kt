package com.mina.legadostudio.domain
import com.mina.legadostudio.mcp.McpAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
class McpAccessTest {
    @Test fun exposesLoopbackFirst() {
        assertEquals(listOf("http://127.0.0.1:1237/mcp"), McpAccess.endpoints(1237))
    }
    @Test fun tokenHeaderLineIsStandardBearer() {
        assertEquals("Authorization: Bearer secret", McpAccess.tokenHeaderLine("secret"))
    }
    @Test fun bearerTokenValueHasBearerPrefix() {
        assertEquals("Bearer secret", McpAccess.bearerTokenValue("secret"))
    }
    @Test fun keepsLoopbackAllowed() {
        val hosts = McpAccess.allowedHosts(listOf(InetAddress.getByName("192.168.1.8")))
        assertTrue(hosts.contains("localhost"))
        assertTrue(hosts.contains("127.0.0.1"))
    }
    @Test fun lanEndpointsAreLanIpv4Only() {
        val urls = McpAccess.lanEndpoints(58823)
        urls.forEach { url ->
            assertTrue(url.startsWith("http://"))
            assertTrue(url.endsWith(":58823/mcp"))
            assertTrue(!url.contains("127.0.0.1"))
            val host = url.removePrefix("http://").removeSuffix(":58823/mcp")
            assertTrue(host.all { it.isDigit() || it == '.' })
            assertTrue(host.count { it == '.' } == 3)
        }
    }
    @Test fun clientConfigJsonEmbedsHeaderWhenTokenRequired() {
        val json = McpAccess.clientConfigJson("http://127.0.0.1:58823/mcp", "tk-123", true)
        assertEquals(
            "{\n  \"mcpServers\": {\n    \"yuedu-MCP\": {\n      \"url\": \"http://127.0.0.1:58823/mcp\",\n      \"headers\": {\n        \"Authorization\": \"Bearer tk-123\"\n      }\n    }\n  }\n}",
            json,
        )
    }
    @Test fun clientConfigJsonOmitsHeaderWhenNotRequiredOrBlank() {
        val expected = "{\n  \"mcpServers\": {\n    \"yuedu-MCP\": {\n      \"url\": \"http://127.0.0.1:58823/mcp\"\n    }\n  }\n}"
        assertEquals(expected, McpAccess.clientConfigJson("http://127.0.0.1:58823/mcp", "tk-123", false))
        assertEquals(expected, McpAccess.clientConfigJson("http://127.0.0.1:58823/mcp", "", true))
    }
    @Test fun clientConfigJsonEscapesQuotesAndBackslashes() {
        val json = McpAccess.clientConfigJson("http://x/mcp", "a\"b\\c", true)
        assertTrue(json.contains("\"Authorization\": \"Bearer a\\\"b\\\\c\""))
    }
    @Test fun connectionUrlStandardKeepsUrlUnchanged() {
        val url = "http://127.0.0.1:58823/mcp"
        assertEquals(url, McpAccess.connectionUrl(url, compact = false))
        assertEquals(url, McpAccess.connectionUrl(url, compact = false))
    }
    @Test fun connectionUrlCompactAppendsParam() {
        assertEquals(
            "http://127.0.0.1:58823/mcp?compact=1",
            McpAccess.connectionUrl("http://127.0.0.1:58823/mcp", compact = true),
        )
    }
    @Test fun connectionUrlCompactKeepsExistingQueryAndFragment() {
        assertEquals(
            "http://192.168.1.8:58823/mcp?foo=bar&x=1&compact=1#frag",
            McpAccess.connectionUrl("http://192.168.1.8:58823/mcp?foo=bar&x=1#frag", compact = true),
        )
    }
    @Test fun connectionUrlCompactNormalizesExistingCompactParam() {
        assertEquals(
            "http://127.0.0.1:58823/mcp?compact=1",
            McpAccess.connectionUrl("http://127.0.0.1:58823/mcp?compact=1", compact = true),
        )
        assertEquals(
            "http://127.0.0.1:58823/mcp?compact=1",
            McpAccess.connectionUrl("http://127.0.0.1:58823/mcp?compact=0&compact=1", compact = true),
        )
    }
    @Test fun connectionUrlCompactIsIdempotent() {
        val once = McpAccess.connectionUrl("http://127.0.0.1:58823/mcp?a=b", compact = true)
        assertEquals(once, McpAccess.connectionUrl(once, compact = true))
    }
    @Test fun connectionUrlStandardStripsCompactParam() {
        assertEquals(
            "http://127.0.0.1:58823/mcp?foo=bar#frag",
            McpAccess.connectionUrl("http://127.0.0.1:58823/mcp?compact=1&foo=bar#frag", compact = false),
        )
        assertEquals(
            "http://127.0.0.1:58823/mcp",
            McpAccess.connectionUrl("http://127.0.0.1:58823/mcp?compact=1", compact = false),
        )
    }
    @Test fun connectionUrlRoundTripSwitchingModes() {
        val base = "http://192.168.1.8:58823/mcp?foo=bar"
        val compact = McpAccess.connectionUrl(base, compact = true)
        assertEquals("http://192.168.1.8:58823/mcp?foo=bar&compact=1", compact)
        assertEquals(base, McpAccess.connectionUrl(compact, compact = false))
    }
    @Test fun connectionUrlTreatsPercentEncodedCompactKeyAsCompact() {
        assertEquals(
            "http://127.0.0.1:58823/mcp?compact=1",
            McpAccess.connectionUrl("http://127.0.0.1:58823/mcp?%63ompact=1", compact = true),
        )
        assertEquals(
            "http://127.0.0.1:58823/mcp",
            McpAccess.connectionUrl("http://127.0.0.1:58823/mcp?%63ompact=1", compact = false),
        )
    }
    @Test fun clientConfigJsonUrlMatchesConnectionUrlModes() {
        val endpoint = "http://127.0.0.1:58823/mcp"
        val compactUrl = McpAccess.connectionUrl(endpoint, compact = true)
        val compactJson = McpAccess.clientConfigJson(compactUrl, "tk", true)
        assertTrue(compactJson.contains("\"url\": \"$compactUrl\""))
        val standardJson = McpAccess.clientConfigJson(McpAccess.connectionUrl(endpoint, compact = false), "tk", true)
        assertTrue(standardJson.contains("\"url\": \"$endpoint\""))
        assertTrue(!standardJson.contains("compact"))
    }
}
