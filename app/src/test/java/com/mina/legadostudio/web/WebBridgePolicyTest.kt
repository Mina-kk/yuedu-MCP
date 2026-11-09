package com.mina.legadostudio.web

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥接调用编排的纯策略：pending 生命周期、事件名、工具调用结果映射、验证页 URL 保护。
 *
 * 这些是「前端永远 loading」和「验证页逃逸」两类事故的直接防线，必须在 JVM 上先红后绿。
 */
class WebBridgePolicyTest {

    // --- pending 生命周期 -------------------------------------------------

    @Test
    fun `pending request can be settled exactly once`() {
        val pending = WebBridgePending()
        val id = requireNotNull(pending.add("1"))
        assertEquals("1", id)
        assertEquals(1, pending.size)
        assertTrue(pending.remove(id))
        assertFalse("second settle must not win", pending.remove(id))
        assertEquals(0, pending.size)
    }

    @Test
    fun `pending rejects blank or oversize ids`() {
        val pending = WebBridgePending()
        assertNull(pending.add(""))
        assertNull(pending.add("  "))
        assertNull(pending.add("x".repeat(WebBridgeProtocol.MAX_ID_CHARS + 1)))
        assertNull(pending.add("1\n2"))
        assertEquals(0, pending.size)
    }

    @Test
    fun `duplicate id is refused instead of silently replacing`() {
        val pending = WebBridgePending()
        assertEquals("1", pending.add("1"))
        assertNull("duplicate id must be refused", pending.add("1"))
    }

    @Test
    fun `drain returns every outstanding id and empties the set`() {
        val pending = WebBridgePending()
        pending.add("a"); pending.add("b"); pending.add("c")
        val drained = pending.drain()
        assertEquals(setOf("a", "b", "c"), drained)
        assertEquals(0, pending.size)
        assertTrue(pending.drain().isEmpty())
    }

    @Test
    fun `pending is capped so a hostile page cannot exhaust memory`() {
        val pending = WebBridgePending(limit = 3)
        repeat(3) { assertTrue(pending.add("id$it") != null) }
        assertNull(pending.add("overflow"))
        assertEquals(3, pending.size)
    }

    // --- 事件名 -----------------------------------------------------------

    @Test
    fun `host events use the studio namespace and are allowlisted`() {
        assertEquals("studio:back", WebBridgeProtocol.EVENT_BACK)
        assertEquals("studio:verification-browser", WebBridgeProtocol.EVENT_VERIFICATION_BROWSER)
        assertEquals("studio:insets", WebBridgeProtocol.EVENT_INSETS)
        assertEquals("studio:service", WebBridgeProtocol.EVENT_SERVICE)
        assertTrue(WebBridgeProtocol.isAllowedEvent(WebBridgeProtocol.EVENT_BACK))
        assertTrue(WebBridgeProtocol.isAllowedEvent(WebBridgeProtocol.EVENT_VERIFICATION_BROWSER))
        assertFalse(WebBridgeProtocol.isAllowedEvent("studio:anything"))
        assertFalse(WebBridgeProtocol.isAllowedEvent("eval"))
    }

    @Test
    fun `event envelope is a json object with an allowlisted name`() {
        val json = WebBridgeProtocol.event(WebBridgeProtocol.EVENT_VERIFICATION_BROWSER, JsonParser.parseString("""{"action":"close"}"""))
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("studio:verification-browser", obj.get("event").asString)
        assertEquals("close", obj.getAsJsonObject("payload").get("action").asString)
    }

    @Test
    fun `event with unknown name is refused`() {
        assertNull(WebBridgeProtocol.event("studio:evil", null))
    }

    // --- 工具结果映射 -----------------------------------------------------

    @Test
    fun `tool result maps content and isError`() {
        val result = WebBridgeProtocol.toolResultJson(
            isError = false,
            contentJson = """[{"type":"text","text":"{}"}]""",
            structuredJson = null,
        )
        val obj = JsonParser.parseString(result).asJsonObject
        assertFalse(obj.get("isError").asBoolean)
        assertEquals("text", obj.getAsJsonArray("content")[0].asJsonObject.get("type").asString)
        assertFalse(obj.has("structuredContent"))
    }

    @Test
    fun `tool error result keeps isError true`() {
        val obj = JsonParser.parseString(
            WebBridgeProtocol.toolResultJson(true, """[{"type":"text","text":"boom"}]""", null)
        ).asJsonObject
        assertTrue(obj.get("isError").asBoolean)
        assertEquals("boom", obj.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `malformed content json degrades to an error text instead of throwing`() {
        val obj = JsonParser.parseString(
            WebBridgeProtocol.toolResultJson(false, "not-json", null)
        ).asJsonObject
        assertTrue(obj.get("isError").asBoolean)
        assertTrue(obj.toString().contains("content"))
    }

    // --- 验证页 URL 保护 --------------------------------------------------

    @Test
    fun `verification browser only accepts http(s) urls`() {
        assertTrue(WebBridgePolicy.isVerificationUrlAllowed("https://site.example.com/v?x=1"))
        assertTrue(WebBridgePolicy.isVerificationUrlAllowed("http://site.example.com/"))
        listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://com.example/x",
            "data:text/html,<script>",
            "intent://x#Intent;end",
            "https://appassets.androidplatform.net/assets/web/index.html",
            "http://127.0.0.1:58823/mcp",
            "http://localhost/x",
            "http://10.0.0.5/x",
            "http://192.168.1.9/x",
            "http://user:pw@site.example.com/",
            "",
        ).forEach { url -> assertFalse("$url must be refused", WebBridgePolicy.isVerificationUrlAllowed(url)) }
    }

    @Test
    fun `verification browser never gets a js bridge`() {
        // 隔离页只允许「无 bridge」一种形态：这里断言策略常量，防止后续误加开关
        assertEquals(false, WebBridgePolicy.verificationBrowserGetsBridge)
    }

    @Test
    fun `verification session payload never leaks cookies or tokens`() {
        val payload = WebBridgePolicy.verificationListPayload(
            listOf(
                WebBridgePolicy.VerificationRow(
                    id = "s1", domain = "site.example.com", url = "https://site.example.com/v",
                    purpose = "MCP 请求需要网站验证", status = "WAITING", kind = "webview",
                    imageData = "", finalUrl = "", answer = "",
                    createdAt = 1L, updatedAt = 2L, jobId = "mcp",
                )
            )
        )
        val text = payload.toString()
        assertFalse(text.contains("Cookie", ignoreCase = true))
        assertFalse(text.contains("token", ignoreCase = true))
        assertTrue(text.contains("site.example.com"))
    }

    @Test
    fun `verification completion ignores js supplied final url`() {
        // 完成时 finalUrl 只能来自宿主观察；前端传入的地址字段必须被丢弃
        val params = JsonParser.parseString(
            """{"id":"s1","answer":"1234","finalUrl":"https://attacker.example/","url":"https://attacker.example/"}"""
        ).asJsonObject
        val parsed = WebBridgeProtocol.parseVerificationComplete(params)
        assertEquals("s1", (parsed as WebBridgeProtocol.CompleteParse.Ok).id)
        assertEquals("1234", parsed.answer)
        assertFalse(parsed.toString().contains("attacker"))
    }

    @Test
    fun `verification complete requires an id`() {
        val parsed = WebBridgeProtocol.parseVerificationComplete(JsonParser.parseString("""{}""").asJsonObject)
        assertEquals(WebBridgeProtocol.ERR_BAD_PARAMS, (parsed as WebBridgeProtocol.CompleteParse.Bad).code)
    }

    @Test
    fun `verification answer is length bounded`() {
        val params = JsonParser.parseString("""{"id":"s1","answer":"${"9".repeat(500)}"}""").asJsonObject
        assertTrue(WebBridgeProtocol.parseVerificationComplete(params) is WebBridgeProtocol.CompleteParse.Bad)
    }

    // --- 剪贴板内容 -------------------------------------------------------

    @Test
    fun `clipboard json config includes the bearer header but is never returned to js`() {
        val json = WebBridgePolicy.clientConfigJson(
            url = "http://127.0.0.1:58823/mcp", token = "T0KEN", tokenRequired = true,
        )
        assertTrue(json.contains("Bearer T0KEN"))
        // 回包只允许 {copied:true}
        val ack = WebBridgePolicy.copiedAck()
        assertEquals(true, ack.get("copied").asBoolean)
        assertEquals(1, ack.entrySet().size)
        assertFalse(ack.toString().contains("T0KEN"))
    }

    @Test
    fun `clipboard ack shape is stable for every kind`() {
        assertEquals(setOf("copied"), WebBridgePolicy.copiedAck().keySet())
    }

    // --- 事件信封与前端一致 -----------------------------------------------

    @Test
    fun `event envelope uses payload key not detail`() {
        val json = WebBridgeProtocol.event(WebBridgeProtocol.EVENT_INSETS, JsonParser.parseString("""{"top":0}"""))
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue("payload must be present", obj.has("payload"))
        assertFalse("detail must not leak into bridge events", obj.has("detail"))
        assertEquals(0, obj.getAsJsonObject("payload").get("top").asInt)
    }

    @Test
    fun `null payload becomes an empty object`() {
        val obj = JsonParser.parseString(WebBridgeProtocol.event(WebBridgeProtocol.EVENT_BACK, null)).asJsonObject
        assertTrue(obj.getAsJsonObject("payload").entrySet().isEmpty())
    }

    // --- 主 WebView 导航与本地资源 ----------------------------------------

    @Test
    fun `host navigation allows only the trusted assets subtree`() {
        assertEquals(
            WebBridgePolicy.NavigationDecision.Allow,
            WebBridgePolicy.hostNavigationDecision("https://appassets.androidplatform.net/assets/web/index.html"),
        )
        assertEquals(
            WebBridgePolicy.NavigationDecision.Allow,
            WebBridgePolicy.hostNavigationDecision("https://appassets.androidplatform.net/assets/web/index.html#/verification"),
        )
        for (bad in listOf(
            "https://appassets.androidplatform.net.evil.com/assets/web/index.html",
            "https://appassets.androidplatform.net:8443/assets/web/index.html",
            "http://appassets.androidplatform.net/assets/web/index.html",
            "https://user:pw@appassets.androidplatform.net/assets/web/index.html",
            "https://appassets.androidplatform.net/assets/skills/x.json",
            "https://appassets.androidplatform.net/assets/web/../skills/x.json",
            "https://appassets.androidplatform.net/assets/web/%2e%2e/skills/x.json",
            "https://appassets.androidplatform.net/assets/web/..%2fskills/x.json",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "",
        )) {
            assertEquals("must block $bad", WebBridgePolicy.NavigationDecision.Block, WebBridgePolicy.hostNavigationDecision(bad))
        }
    }

    @Test
    fun `local resources outside assets web are denied instead of deferred to network`() {
        assertEquals(
            WebBridgePolicy.LocalResource.Serve,
            WebBridgePolicy.localResourceDecision("https://appassets.androidplatform.net/assets/web/index.html"),
        )
        for (bad in listOf(
            "https://appassets.androidplatform.net/assets/skills/legado.md",
            "https://evil.example.com/assets/web/index.html",
            "https://appassets.androidplatform.net/assets/web/%2e%2e/skills/x",
            "file:///data/data/com.mina.legadostudio/files/x",
            "https://appassets.androidplatform.net/",
        )) {
            assertEquals("must deny $bad", WebBridgePolicy.LocalResource.Deny, WebBridgePolicy.localResourceDecision(bad))
        }
    }

    @Test
    fun `verification page may load its own subresources but never the privileged origin`() {
        assertEquals(
            WebBridgePolicy.LocalResource.Serve,
            WebBridgePolicy.verificationResourceDecision("https://challenge.example.com/cdn/app.js"),
        )
        assertEquals(
            WebBridgePolicy.LocalResource.Serve,
            WebBridgePolicy.verificationResourceDecision("data:image/png;base64,AAAA"),
        )
        for (bad in listOf(
            "https://appassets.androidplatform.net/assets/web/index.html",
            "file:///sdcard/secret.txt",
            "content://com.android.providers.media/documents/1",
            "intent://x",
            "",
        )) {
            assertEquals("must deny $bad", WebBridgePolicy.LocalResource.Deny, WebBridgePolicy.verificationResourceDecision(bad))
        }
    }

    // --- 完成会话的 finalUrl 来源 -----------------------------------------

    @Test
    fun `final url prefers the live observation then the saved value`() {
        assertEquals(
            "https://site.example.com/after",
            StudioWebBridge.pickFinalUrl("https://site.example.com/after", "https://site.example.com/saved", "https://site.example.com/start"),
        )
        assertEquals(
            "https://site.example.com/saved",
            StudioWebBridge.pickFinalUrl(null, "https://site.example.com/saved", "https://site.example.com/start"),
        )
        assertEquals(
            "https://site.example.com/start",
            StudioWebBridge.pickFinalUrl("  ", "", "https://site.example.com/start"),
        )
    }

    // --- SAF 严格 UTF-8 ---------------------------------------------------

    @Test
    fun `saf import decodes valid utf8 and rejects malformed bytes`() {
        assertEquals("中文 ok", SafLauncher.decodeUtf8Strict("中文 ok".toByteArray(Charsets.UTF_8)))
        val broken = byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)
        val failure = runCatching { SafLauncher.decodeUtf8Strict(broken) }.exceptionOrNull()
        assertTrue("malformed utf8 must fail, got $failure", failure is java.io.IOException)
    }

    @Test
    fun `saf import cap matches the bridge request budget`() {
        assertEquals(WebBridgeProtocol.MAX_REQUEST_BYTES, SafLauncher.MAX_IMPORT_BYTES)
    }
}
