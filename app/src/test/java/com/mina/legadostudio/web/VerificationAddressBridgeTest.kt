package com.mina.legadostudio.web

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class VerificationAddressBridgeTest {
    private fun row(url: String, kind: String = "webview") = WebBridgePolicy.VerificationRow(
        "qa-session", "", url, "", "WAITING", kind, "", "", "", 1L, 2L, "mcp",
    )

    @Test fun emptyAndUnsupportedUrlsAreExplicitlyNotOpenable() {
        val values = WebBridgePolicy.verificationListPayload(listOf(row(""), row("file:///private"), row("https://example.org/")))
            .getAsJsonArray("sessions")
        assertEquals(false, values[0].asJsonObject["canOpen"].asBoolean)
        assertEquals("missing_url", values[0].asJsonObject["addressIssue"].asString)
        assertEquals(false, values[1].asJsonObject["canOpen"].asBoolean)
        assertEquals("unsupported_url", values[1].asJsonObject["addressIssue"].asString)
        assertEquals(true, values[2].asJsonObject["canOpen"].asBoolean)
        assertEquals("", values[2].asJsonObject["addressIssue"].asString)
    }

    @Test fun completeUsesStableExplicitSessionKeysAndKeepsImageAnswer() {
        val value = WebBridgePolicy.verificationSessionPayload(row("https://example.org/", "image_code").copy(answer = "QA-only"))
        assertEquals("qa-session", value["id"].asString)
        assertEquals("https://example.org/", value["url"].asString)
        assertEquals("QA-only", value["answer"].asString)
        assertTrue(value.has("createdAt"))
        assertFalse(value.has("a"))
    }

    @Test fun webSessionsWithInvalidAddressCannotBeMarkedCompleteButImageAnswersCan() {
        assertFalse(WebBridgePolicy.canCompleteVerification("webview", ""))
        assertFalse(WebBridgePolicy.canCompleteVerification("webview", "http://127.0.0.1/"))
        assertTrue(WebBridgePolicy.canCompleteVerification("webview", "https://example.org/"))
        assertTrue(WebBridgePolicy.canCompleteVerification("image_code", ""))
    }

    @Test fun repairRequestRequiresExactFieldsAndPublicUrl() {
        assertTrue(WebBridgeProtocol.isAllowedMethod("verification.repairAddress"))
        val valid = WebBridgeProtocol.parseVerificationRepair(JsonParser.parseString("""{"id":"qa","url":"https://example.org/#tag"}""").asJsonObject)
        assertNotNull(valid)
        assertEquals("qa", valid!!.id)
        assertEquals("https://example.org/#tag", valid.url)
        for (raw in listOf(
            """{"id":"qa","url":""}""",
            """{"id":"qa","url":"http://127.0.0.1/"}""",
            """{"id":"qa","url":"file:///x"}""",
            """{"id":"qa","url":"https://name:secret@example.org/"}""",
            """{"id":1,"url":"https://example.org/"}""",
            """{"id":"qa","url":"https://example.org/","status":"COMPLETED"}""",
        )) assertNull(raw, WebBridgeProtocol.parseVerificationRepair(JsonParser.parseString(raw).asJsonObject))
    }
}
