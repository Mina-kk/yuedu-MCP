package com.mina.legadostudio.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主 bridge 的纯协议层契约（JVM 可测，不碰 Android）。
 *
 * 覆盖契约里最容易写错、也最容易被绕过的地方：
 * - 来源保护：只有 appassets 主框架能进 privileged bridge；
 * - 方法 allowlist：未登记方法一律拒绝，不做前缀/通配匹配；
 * - 请求大小与结构校验；
 * - 回包 JSON 转义（错误文本不得破坏信封，也不得被当作 HTML）；
 * - 错误脱敏（令牌/URL 凭据不进回包）。
 */
class WebBridgeProtocolTest {

    private val trusted = WebBridgeProtocol.TRUSTED_ORIGIN

    // --- 来源保护 ---------------------------------------------------------

    @Test
    fun `trusted origin main frame is accepted`() {
        val verdict = WebBridgeProtocol.authorizeOrigin(trusted, isMainFrame = true)
        assertTrue(verdict is WebBridgeProtocol.OriginVerdict.Accept)
    }

    @Test
    fun `same origin sub frame is rejected`() {
        val verdict = WebBridgeProtocol.authorizeOrigin(trusted, isMainFrame = false)
        assertEquals(WebBridgeProtocol.OriginVerdict.Reject(WebBridgeProtocol.REJECT_SUB_FRAME), verdict)
    }

    @Test
    fun `foreign origin is rejected even on main frame`() {
        val verdict = WebBridgeProtocol.authorizeOrigin("https://evil.example.com", isMainFrame = true)
        assertTrue(verdict is WebBridgeProtocol.OriginVerdict.Reject)
        assertEquals(WebBridgeProtocol.REJECT_FOREIGN_ORIGIN, (verdict as WebBridgeProtocol.OriginVerdict.Reject).code)
    }

    @Test
    fun `null origin is rejected`() {
        assertTrue(WebBridgeProtocol.authorizeOrigin(null, isMainFrame = true) is WebBridgeProtocol.OriginVerdict.Reject)
    }

    @Test
    fun `origin comparison is exact - scheme host port and suffix tricks all fail`() {
        val hostile = listOf(
            "http://appassets.androidplatform.net",
            "https://appassets.androidplatform.net:8443",
            "https://appassets.androidplatform.net.evil.com",
            "https://evil.appassets.androidplatform.net",
            "https://appassets.androidplatform.net/",
            "https://APPASSETS.androidplatform.net",
            "null",
            "",
        )
        hostile.forEach { origin ->
            assertTrue(
                "origin=$origin should be rejected",
                WebBridgeProtocol.authorizeOrigin(origin, isMainFrame = true) is WebBridgeProtocol.OriginVerdict.Reject,
            )
        }
    }

    // --- 方法 allowlist ---------------------------------------------------

    @Test
    fun `every contract method is allowlisted`() {
        val contractMethods = listOf(
            "app.bootstrap", "service.set", "settings.save", "connection.copy",
            "tools.list", "tools.call",
            "verification.list", "verification.open", "verification.complete",
            "verification.clear", "verification.close", "verification.browser.close",
            "file.export", "file.import", "app.theme", "app.exit",
        )
        contractMethods.forEach { method ->
            assertTrue("$method must be allowlisted", WebBridgeProtocol.isAllowedMethod(method))
        }
    }

    @Test
    fun `unknown lookalike methods are not allowlisted`() {
        listOf(
            "app.bootstrap.extra", "app.bootstrap ", " APP.BOOTSTRAP", "app.*", "tools.call.raw",
            "file.read", "app.eval", "settings.saveToken", "verification.browser.eval", "",
        ).forEach { assertFalse("$it must not be allowlisted", WebBridgeProtocol.isAllowedMethod(it)) }
    }

    // --- 请求解析 ---------------------------------------------------------

    @Test
    fun `valid envelope is parsed`() {
        val parsed = WebBridgeProtocol.parseRequest("""{"id":"1","method":"app.bootstrap","params":{}}""")
        val ok = parsed as WebBridgeProtocol.Parsed.Ok
        assertEquals("1", ok.id)
        assertEquals("app.bootstrap", ok.method)
    }

    @Test
    fun `numeric id is normalized to string`() {
        val ok = WebBridgeProtocol.parseRequest("""{"id":7,"method":"tools.list"}""") as WebBridgeProtocol.Parsed.Ok
        assertEquals("7", ok.id)
    }

    @Test
    fun `missing id is rejected`() {
        val bad = WebBridgeProtocol.parseRequest("""{"method":"tools.list"}""")
        assertEquals(WebBridgeProtocol.ERR_BAD_REQUEST, (bad as WebBridgeProtocol.Parsed.Bad).error.code)
    }

    @Test
    fun `blank id is rejected`() {
        assertTrue(WebBridgeProtocol.parseRequest("""{"id":"  ","method":"tools.list"}""") is WebBridgeProtocol.Parsed.Bad)
    }

    @Test
    fun `missing or blank method is rejected`() {
        assertTrue(WebBridgeProtocol.parseRequest("""{"id":"1"}""") is WebBridgeProtocol.Parsed.Bad)
        assertTrue(WebBridgeProtocol.parseRequest("""{"id":"1","method":""}""") is WebBridgeProtocol.Parsed.Bad)
    }

    @Test
    fun `non object json is rejected`() {
        listOf("[]", "\"x\"", "42", "null", "{", "").forEach {
            assertTrue("payload=$it", WebBridgeProtocol.parseRequest(it) is WebBridgeProtocol.Parsed.Bad)
        }
    }

    @Test
    fun `oversize payload is rejected before parsing`() {
        val huge = """{"id":"1","method":"app.bootstrap","params":{"x":"${"a".repeat(WebBridgeProtocol.MAX_REQUEST_CHARS)}"}}"""
        val bad = WebBridgeProtocol.parseRequest(huge) as WebBridgeProtocol.Parsed.Bad
        assertEquals(WebBridgeProtocol.ERR_PAYLOAD_TOO_LARGE, bad.error.code)
    }

    @Test
    fun `payload exactly at limit is accepted`() {
        val head = """{"id":"1","method":"app.bootstrap","params":{"x":""""
        val tail = """"}}"""
        val filler = "a".repeat(WebBridgeProtocol.MAX_REQUEST_CHARS - head.length - tail.length)
        val payload = head + filler + tail
        assertEquals(WebBridgeProtocol.MAX_REQUEST_CHARS, payload.length)
        assertEquals(WebBridgeProtocol.MAX_REQUEST_BYTES, WebBridgeProtocol.utf8Length(payload))
        assertTrue(WebBridgeProtocol.parseRequest(payload) is WebBridgeProtocol.Parsed.Ok)
    }

    @Test
    fun `non object params is rejected`() {
        val bad = WebBridgeProtocol.parseRequest("""{"id":"1","method":"tools.call","params":[1,2]}""")
        assertEquals(WebBridgeProtocol.ERR_BAD_PARAMS, (bad as WebBridgeProtocol.Parsed.Bad).error.code)
    }

    @Test
    fun `unknown method is rejected at parse time`() {
        val bad = WebBridgeProtocol.parseRequest("""{"id":"1","method":"app.eval"}""")
        assertEquals(WebBridgeProtocol.ERR_UNKNOWN_METHOD, (bad as WebBridgeProtocol.Parsed.Bad).error.code)
    }

    // --- 回包 -------------------------------------------------------------

    @Test
    fun `success envelope carries id and result`() {
        val json = WebBridgeProtocol.success("42", JsonParser.parseString("""{"a":1}"""))
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("42", obj.get("id").asString)
        assertTrue(obj.get("ok").asBoolean)
        assertEquals(1, obj.getAsJsonObject("result").get("a").asInt)
        assertFalse(obj.has("error"))
    }

    @Test
    fun `error envelope is json escaped and never carries raw html`() {
        val message = "boom \"quoted\" \\ </script><img src=x onerror=alert(1)>"
        val json = WebBridgeProtocol.failure("9", WebBridgeProtocol.ERR_TOOL_FAILED, message)
        assertFalse("must not emit raw closing script tag", json.contains("</script>"))
        val obj = JsonParser.parseString(json).asJsonObject
        assertFalse(obj.get("ok").asBoolean)
        assertEquals(WebBridgeProtocol.ERR_TOOL_FAILED, obj.getAsJsonObject("error").get("code").asString)
        assertEquals(message, obj.getAsJsonObject("error").get("message").asString)
    }

    @Test
    fun `error envelope survives newlines and unicode`() {
        val message = "第一行\n第二行\t制表符"
        val obj = JsonParser.parseString(WebBridgeProtocol.failure("1", "x", message)).asJsonObject
        assertEquals(message, obj.getAsJsonObject("error").get("message").asString)
    }

    @Test
    fun `error message length is bounded`() {
        val json = WebBridgeProtocol.failure("1", "x", "e".repeat(50_000))
        val obj = JsonParser.parseString(json).asJsonObject
        val message = obj.getAsJsonObject("error").get("message").asString
        assertTrue(message.length <= WebBridgeProtocol.MAX_ERROR_CHARS + 1)
    }

    // --- 脱敏 -------------------------------------------------------------

    @Test
    fun `secrets in error text are redacted`() {
        val raw = "Authorization: Bearer abc123SECRET\nurl=https://user:pass@example.com/x?token=zzz&a=1"
        val redacted = WebBridgeProtocol.redact(raw)
        assertFalse(redacted.contains("abc123SECRET"))
        assertFalse(redacted.contains("zzz"))
        assertFalse(redacted.contains(":pass@"))
        assertTrue(redacted.contains("example.com"))
    }

    @Test
    fun `redaction keeps ordinary diagnostics readable`() {
        val raw = "HTTP 403 cloudflare_challenge at https://site.example.com/search"
        assertEquals(raw, WebBridgeProtocol.redact(raw))
    }

    @Test
    fun `redaction handles token query params and credentials`() {
        val raw = "GET https://h.example.com/p?access_token=AAA&key=BBB&sig=CCC&q=ok"
        val redacted = WebBridgeProtocol.redact(raw)
        assertFalse(redacted.contains("AAA"))
        assertFalse(redacted.contains("BBB"))
        assertFalse(redacted.contains("CCC"))
        assertTrue(redacted.contains("q=ok"))
    }

    // --- 参数校验 ---------------------------------------------------------

    @Test
    fun `service action validation`() {
        assertEquals(ServiceAction.START, WebBridgeProtocol.parseServiceAction("start"))
        assertEquals(ServiceAction.STOP, WebBridgeProtocol.parseServiceAction("stop"))
        assertEquals(ServiceAction.RESTART, WebBridgeProtocol.parseServiceAction("restart"))
        assertNull(WebBridgeProtocol.parseServiceAction("kill"))
        assertNull(WebBridgeProtocol.parseServiceAction(null))
        assertNull(WebBridgeProtocol.parseServiceAction("START"))
    }

    @Test
    fun `connection kind validation`() {
        assertEquals(ConnectionKind.URL, WebBridgeProtocol.parseConnectionKind("url"))
        assertEquals(ConnectionKind.TOKEN, WebBridgeProtocol.parseConnectionKind("token"))
        assertEquals(ConnectionKind.JSON, WebBridgeProtocol.parseConnectionKind("json"))
        assertNull(WebBridgeProtocol.parseConnectionKind("file"))
        assertNull(WebBridgeProtocol.parseConnectionKind(null))
    }

    @Test
    fun `export name is sanitized and unsafe names rejected`() {
        assertEquals("book-source.json", WebBridgeProtocol.sanitizeFileName("book-source.json"))
        assertEquals("a_b.json", WebBridgeProtocol.sanitizeFileName("../../a/b.json"))
        assertEquals("x.json", WebBridgeProtocol.sanitizeFileName("x.json\u0000"))
        assertNull(WebBridgeProtocol.sanitizeFileName(""))
        assertNull(WebBridgeProtocol.sanitizeFileName("..."))
        assertNull(WebBridgeProtocol.sanitizeFileName("a".repeat(200)))
    }

    @Test
    fun `export text limit matches request budget`() {
        assertTrue(WebBridgeProtocol.checkExportText("x".repeat(WebBridgeProtocol.MAX_REQUEST_CHARS)) == null)
        assertNotNull(WebBridgeProtocol.checkExportText("x".repeat(WebBridgeProtocol.MAX_REQUEST_CHARS + 1)))
    }

    @Test
    fun `mime is restricted to a plain type`() {
        assertEquals("application/json", WebBridgeProtocol.sanitizeMime("application/json"))
        assertEquals("text/plain", WebBridgeProtocol.sanitizeMime(null))
        assertEquals("text/plain", WebBridgeProtocol.sanitizeMime("text/plain; charset=utf-8"))
        assertEquals("text/plain", WebBridgeProtocol.sanitizeMime("text/plain\r\nX-Injected: 1"))
        assertEquals("text/plain", WebBridgeProtocol.sanitizeMime("not a mime"))
    }

    @Test
    fun `call params require a tool name and object arguments`() {
        val ok = WebBridgeProtocol.parseToolCall(JsonParser.parseString("""{"name":"list_projects","arguments":{"a":1}}""").asJsonObject)
        assertEquals("list_projects", (ok as WebBridgeProtocol.ToolCallParse.Ok).name)
        assertEquals(1, ok.arguments.get("a").asInt)

        val noName = WebBridgeProtocol.parseToolCall(JsonParser.parseString("""{"arguments":{}}""").asJsonObject)
        assertEquals(WebBridgeProtocol.ERR_BAD_PARAMS, (noName as WebBridgeProtocol.ToolCallParse.Bad).code)

        val arrayArgs = WebBridgeProtocol.parseToolCall(JsonParser.parseString("""{"name":"x","arguments":[1]}""").asJsonObject)
        assertEquals(WebBridgeProtocol.ERR_BAD_PARAMS, (arrayArgs as WebBridgeProtocol.ToolCallParse.Bad).code)

        val noArgs = WebBridgeProtocol.parseToolCall(JsonParser.parseString("""{"name":"x"}""").asJsonObject)
        assertTrue((noArgs as WebBridgeProtocol.ToolCallParse.Ok).arguments.entrySet().isEmpty())
    }

    @Test
    fun `tool name with control chars or absurd length is rejected`() {
        listOf("a\nb", "a\u0000b", "x".repeat(200), " ").forEach { name ->
            val params = JsonObject().apply { addProperty("name", name) }
            assertTrue("name=$name", WebBridgeProtocol.parseToolCall(params) is WebBridgeProtocol.ToolCallParse.Bad)
        }
    }

    // --- 设置整体校验 -----------------------------------------------------

    private fun settings(json: String) = WebBridgeProtocol.parseSettings(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `settings accept a full valid payload`() {
        val parsed = settings(
            """{"port":58823,"compact":true,"userAgent":"Mozilla/5.0 (Linux; Android 16) Test","bookSourceType":0,
               "readerBaseUrl":"http://192.168.1.5:1122","readerToken":"secret-token",
               "appearance":{"theme":"dark"}}"""
        )
        val ok = parsed as WebBridgeProtocol.SettingsParse.Ok
        assertEquals(58823, ok.settings.port)
        assertEquals(true, ok.settings.compact)
        assertEquals(0, ok.settings.bookSourceType)
        assertEquals("http://192.168.1.5:1122", ok.settings.readerBaseUrl)
        assertEquals("secret-token", ok.settings.readerToken)
        assertEquals("dark", ok.settings.appearanceTheme)
    }

    @Test
    fun `settings reject out of range port`() {
        listOf("1023", "65531", "0", "-1").forEach { port ->
            val parsed = settings("""{"port":$port}""")
            assertTrue("port=$port must fail", parsed is WebBridgeProtocol.SettingsParse.Bad)
        }
    }

    @Test
    fun `settings reject short user agent and bad book source type`() {
        assertTrue(settings("""{"userAgent":"short"}""") is WebBridgeProtocol.SettingsParse.Bad)
        assertTrue(settings("""{"userAgent":"${"u".repeat(501)}"}""") is WebBridgeProtocol.SettingsParse.Bad)
        assertTrue(settings("""{"bookSourceType":5}""") is WebBridgeProtocol.SettingsParse.Bad)
        assertTrue(settings("""{"bookSourceType":-2}""") is WebBridgeProtocol.SettingsParse.Bad)
    }

    @Test
    fun `settings reject non origin reader url`() {
        listOf(
            "http://192.168.1.5:1122/path",
            "ftp://192.168.1.5",
            "http://user:pw@192.168.1.5:1122",
            "192.168.1.5:1122",
        ).forEach { url ->
            val parsed = settings("""{"readerBaseUrl":"$url"}""")
            assertTrue("url=$url must fail", parsed is WebBridgeProtocol.SettingsParse.Bad)
        }
    }

    @Test
    fun `settings reject unknown appearance theme`() {
        assertTrue(settings("""{"appearance":{"theme":"neon"}}""") is WebBridgeProtocol.SettingsParse.Bad)
    }

    @Test
    fun `settings reject type mismatches instead of coercing`() {
        assertTrue(settings("""{"port":"58823"}""") is WebBridgeProtocol.SettingsParse.Bad)
        assertTrue(settings("""{"compact":"true"}""") is WebBridgeProtocol.SettingsParse.Bad)
        assertTrue(settings("""{"readerToken":123}""") is WebBridgeProtocol.SettingsParse.Bad)
        assertTrue(settings("""{"appearance":"dark"}""") is WebBridgeProtocol.SettingsParse.Bad)
    }

    @Test
    fun `settings never accept tokenRequired override`() {
        // tokenRequired 只能由宿主保持 true；前端传 false 必须被忽略（不是报错，而是不可能生效）
        val ok = settings("""{"port":58823,"tokenRequired":false}""") as WebBridgeProtocol.SettingsParse.Ok
        assertTrue(ok.settings.tokenRequiredLocked)
        assertFalse(ok.settings.toString().contains("tokenRequired=false"))
    }

    @Test
    fun `empty settings payload is valid and changes nothing`() {
        val ok = settings("{}") as WebBridgeProtocol.SettingsParse.Ok
        assertNull(ok.settings.port)
        assertNull(ok.settings.compact)
        assertNull(ok.settings.readerBaseUrl)
        assertNull(ok.settings.readerToken)
    }

    @Test
    fun `all settings errors are reported together`() {
        val bad = settings("""{"port":1,"bookSourceType":9,"userAgent":"x"}""") as WebBridgeProtocol.SettingsParse.Bad
        assertTrue("should collect every field error, got ${bad.message}", bad.message.count { it == ';' } >= 2)
    }

    // --- 数字必须精确整数（不截断） ---------------------------------------

    @Test
    fun `fractional numbers are rejected instead of truncated`() {
        listOf("1.5", "58823.9", "1024.0001", "1e3", "1.0").forEach { raw ->
            val parsed = settings("""{"port":$raw}""")
            assertTrue("port=$raw must fail, got $parsed", parsed is WebBridgeProtocol.SettingsParse.Bad)
        }
        assertTrue(settings("""{"bookSourceType":1.5}""") is WebBridgeProtocol.SettingsParse.Bad)
    }

    @Test
    fun `out of range integers are rejected instead of clamped`() {
        listOf("2147483648", "99999999999999999999", "-2147483649").forEach { raw ->
            val parsed = settings("""{"port":$raw}""")
            assertTrue("port=$raw must fail, got $parsed", parsed is WebBridgeProtocol.SettingsParse.Bad)
        }
    }

    @Test
    fun `exactInt accepts only plain integer literals`() {
        assertEquals(0, WebBridgeProtocol.exactInt(JsonParser.parseString("0")))
        assertEquals(-1, WebBridgeProtocol.exactInt(JsonParser.parseString("-1")))
        assertEquals(58823, WebBridgeProtocol.exactInt(JsonParser.parseString("58823")))
        assertNull(WebBridgeProtocol.exactInt(JsonParser.parseString("1.5")))
        assertNull(WebBridgeProtocol.exactInt(JsonParser.parseString("\"1\"")))
        assertNull(WebBridgeProtocol.exactInt(JsonParser.parseString("true")))
        assertNull(WebBridgeProtocol.exactInt(JsonParser.parseString("2147483648")))
    }

    // --- 2MiB UTF-8 字节边界 ----------------------------------------------

    @Test
    fun `multibyte payload is bounded by utf8 bytes not chars`() {
        // 每个汉字 3 字节：字符数只有 1/3 上限，但字节数已超 2MiB。
        val head = """{"id":"1","method":"app.bootstrap","params":{"x":""""
        val tail = """"}}"""
        val budget = WebBridgeProtocol.MAX_REQUEST_BYTES - WebBridgeProtocol.utf8Length(head + tail)
        val chinese = "字".repeat(budget / 3 + 1)
        val payload = head + chinese + tail
        assertTrue(
            "chars=${payload.length} bytes=${WebBridgeProtocol.utf8Length(payload)}",
            payload.length <= WebBridgeProtocol.MAX_REQUEST_CHARS,
        )
        assertTrue(WebBridgeProtocol.utf8Length(payload) > WebBridgeProtocol.MAX_REQUEST_BYTES)
        val bad = WebBridgeProtocol.parseRequest(payload) as WebBridgeProtocol.Parsed.Bad
        assertEquals(WebBridgeProtocol.ERR_PAYLOAD_TOO_LARGE, bad.error.code)
    }

    @Test
    fun `utf8 length counts surrogate pairs once`() {
        assertEquals(4, WebBridgeProtocol.utf8Length("\uD83D\uDE00"))
        assertEquals(3, WebBridgeProtocol.utf8Length("字"))
    }
}
