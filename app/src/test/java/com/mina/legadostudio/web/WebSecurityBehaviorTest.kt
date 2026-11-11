package com.mina.legadostudio.web

import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebSecurityBehaviorTest {
    @get:org.junit.Rule val directory = org.junit.rules.TemporaryFolder()

    @Test fun sharedBridgeSaveLockKeepsTokenPatchAndReadbackTogether() = kotlinx.coroutines.test.runTest {
        // Use the actual companion-owned bridge mutex, not a test-local substitute.
        val field = StudioWebBridge::class.java.getDeclaredField("settingsLock").apply { isAccessible = true }
        val lock = field.get(null) as kotlinx.coroutines.sync.Mutex
        val disk = com.mina.legadostudio.testsupport.McpConfigDiskFixture(directory.newFolder())
        disk.store().load()
        val patched = kotlinx.coroutines.CompletableDeferred<Unit>()
        val allowReadback = kotlinx.coroutines.CompletableDeferred<Unit>()
        var secondEntered = false
        val first = async {
            lock.withLock {
                disk.store().updateConnection(null, false, "fixture-replacement")
                patched.complete(Unit)
                allowReadback.await()
                disk.store().load()
            }
        }
        patched.await()
        val second = async {
            lock.withLock {
                secondEntered = true
                disk.store().updateConnection(45678, true)
            }
        }
        testScheduler.runCurrent()
        assertFalse("another bridge save must wait through readback", secondEntered)
        allowReadback.complete(Unit)
        val receipt = first.await()
        assertFalse(receipt.tokenRequired)
        assertEquals(com.mina.legadostudio.mcp.McpConfigStore.DEFAULT_PORT, receipt.port)
        assertTrue(receipt.token == "fixture-replacement")
        second.await()
        assertTrue(disk.store().load().tokenRequired)
        assertEquals(45678, disk.store().load().port)
        assertTrue(disk.store().load().token == receipt.token)
    }

    @Test fun allSafOperationPairsRetainOwnershipThroughIoAndRejectStaleCompletion() {
        // All three entrypoints use this production gate (wiring checked separately).
        val kinds = listOf("export", "exportFile", "import")
        for (firstKind in kinds) for (nextKind in kinds) {
            val gate = SingleOperationGate()
            val first = Any()
            val next = Any()
            assertTrue("reserve $firstKind", gate.reserve(first))
            assertFalse("$nextKind must not replace open $firstKind picker", gate.reserve(next))
            assertSame(first, gate.current())
            // Picker return is not operation completion: copy/readback still owns the gate.
            assertFalse("$nextKind must wait for $firstKind IO/readback", gate.reserve(next))
            gate.finish(next)
            assertSame(first, gate.current())
            gate.finish(first)
            assertTrue(gate.reserve(next))
            gate.finish(first)
            assertSame("late completion must not clear a new owner", next, gate.current())
            assertSame(next, gate.close())
            assertNull(gate.current())
            assertFalse("destroyed host cannot reopen", gate.reserve(Any()))
        }
    }

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
