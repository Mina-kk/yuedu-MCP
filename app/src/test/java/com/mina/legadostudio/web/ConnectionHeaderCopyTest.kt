package com.mina.legadostudio.web

import org.junit.Assert.*
import org.junit.Test

class ConnectionHeaderCopyTest {
    @Test fun headerCopyWritesExactTextAndReturnsOnlyExactReceipt() = kotlinx.coroutines.runBlocking {
        val token = "fixture-only-token"
        val expected = mapOf("header-name" to "Authorization", "header-value" to "Bearer $token", "header-line" to "Authorization: Bearer $token")
        for ((wire, text) in expected) {
            var written: String? = null
            val ack = WebBridgeProtocol.copyConnectionHeader(WebBridgeProtocol.parseConnectionKind(wire)!!, true, token) { written = it }
            assertEquals(text, written)
            assertEquals(setOf("copied", "kind"), ack.keySet())
            assertTrue(ack.get("copied").asBoolean)
            assertEquals(wire, ack.get("kind").asString)
            assertFalse(ack.toString().contains(token))
            var called = false
            assertTrue(runCatching { WebBridgeProtocol.copyConnectionHeader(WebBridgeProtocol.parseConnectionKind(wire)!!, false, token) { called = true } }.isFailure)
            assertFalse(called)
            val failed = runCatching { WebBridgeProtocol.copyConnectionHeader(WebBridgeProtocol.parseConnectionKind(wire)!!, true, token) { error("clipboard denied") } }
            assertTrue(failed.isFailure)
        }
    }

    @Test fun headerKindsAreStrictlyAllowlisted() {
        for (wire in listOf("header-name", "header-value", "header-line")) {
            assertNotNull("Missing header copy kind: $wire", WebBridgeProtocol.parseConnectionKind(wire))
        }
        for (wire in listOf("HEADER-NAME", "header", "header-value ", " header-line", "")) {
            assertNull(WebBridgeProtocol.parseConnectionKind(wire))
        }
        for (wire in listOf("url", "token", "json")) assertNotNull(WebBridgeProtocol.parseConnectionKind(wire))
    }
}
