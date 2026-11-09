package com.mina.legadostudio.web

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebSecurityBehaviorTest {
    @Test fun safGateHasOneOwnerEvenWhenBothPickersRace() {
        val gate = SingleOperationGate()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val ready = CountDownLatch(1)
            val jobs = (1..32).map { pool.submit<Boolean> { ready.await(); gate.reserve(Any()) } }
            ready.countDown()
            assertEquals(1, jobs.count { it.get(3, TimeUnit.SECONDS) })
            val owner = gate.current()
            gate.finish(Any())
            assertSame(owner, gate.current())
            gate.finish(requireNotNull(owner))
            assertTrue(gate.reserve(Any()))
            assertNotNull(gate.close())
            assertFalse(gate.reserve(Any()))
        } finally { pool.shutdownNow() }
    }
    @Test fun webCookieResultCannotExposeCredentialButMcpInputRemainsUnchanged() {
        val original = JsonParser.parseString("""{"content":[{"type":"text","text":"sid=fixture-private-cookie"}],"isError":false}""")
        val sanitized = WebToolResultPolicy.forWeb("get_cookies", original)
        assertFalse(sanitized.toString().contains("fixture-private-cookie"))
        assertTrue(sanitized.asJsonObject["isError"].asBoolean)
        assertTrue(original.toString().contains("fixture-private-cookie"))
    }
    @Test fun normalSourceJsonMustNotBeReformattedOrTreatItsRuleTokenAsASecret() {
        val raw = "{\n  \"bookSourceUrl\" : \"https://example.com\", \"token\": \"@js:result\"\n}"
        val original = com.google.gson.JsonObject().apply { addProperty("sourceJson", raw) }
        val sanitized = WebToolResultPolicy.forWeb("get_source", original)
        assertEquals(raw, sanitized.asJsonObject["sourceJson"].asString)
    }
    @Test fun knownHostTokensAreRedactedInTextAndStructuredPayloads() {
        val original = JsonParser.parseString("""{"content":[{"type":"text","text":"fixture-host-secret"}],"structuredContent":{"message":"fixture-host-secret"},"isError":false}""")
        val sanitized = WebToolResultPolicy.forWeb("get_logs", original, listOf("fixture-host-secret"))
        assertFalse(sanitized.toString().contains("fixture-host-secret"))
        assertTrue(sanitized.toString().contains("[REDACTED]"))
    }
}
