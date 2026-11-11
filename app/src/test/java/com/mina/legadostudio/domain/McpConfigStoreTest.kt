package com.mina.legadostudio.domain

import com.mina.legadostudio.mcp.McpConfigStore
import com.mina.legadostudio.testsupport.McpConfigDiskFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.mina.legadostudio.web.WebBridgeProtocol
import com.google.gson.JsonParser
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class McpConfigStoreTest {
    @get:Rule val directory = TemporaryFolder()

    private fun update(store: McpConfigStore, json: String): McpConfigStore.Config {
        val draft = (WebBridgeProtocol.parseSettings(JsonParser.parseString(json).asJsonObject) as WebBridgeProtocol.SettingsParse.Ok).settings
        return store.updateConnection(draft.port, draft.tokenRequired, draft.mcpToken)
    }

    @Test fun uncertainSecureWriteRequiresConfirmedRewriteOnRetry() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        val old = disk.store().load()
        disk.afterTokenWrite = { error("private storage detail") }
        assertThrows(IllegalStateException::class.java) {
            update(disk.store(), """{"mcpToken":"fixture-replacement"}""")
        }
        assertTrue(disk.store().load().token != old.token)
        assertEquals(1, disk.tokenChanges)
        disk.afterTokenWrite = null
        update(disk.store(), """{"mcpToken":"fixture-replacement"}""")
        assertEquals(2, disk.tokenWrites)
    }

    @Test fun epochFailureIsNotAcknowledgedAndRetryFlushesPendingInvalidation() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        disk.store().load()
        disk.beforeEpoch = { error("private callback detail") }
        val error = assertThrows(IllegalStateException::class.java) {
            update(disk.store(), """{"mcpToken":"fixture-replacement"}""")
        }
        assertFalse(error.message.orEmpty().contains("private"))
        assertEquals(0, disk.tokenChanges)
        disk.beforeEpoch = null
        update(disk.store(), """{"mcpToken":"fixture-replacement"}""")
        assertEquals(1, disk.tokenChanges)
        assertEquals(1, disk.tokenWrites)
    }

    @Test fun writeBeforeFailureInvalidatesConservativelyAndCanRetry() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        val old = disk.store().load()
        disk.beforeTokenWrite = { error("private storage detail") }
        assertThrows(IllegalStateException::class.java) {
            update(disk.store(), """{"mcpToken":"fixture-replacement"}""")
        }
        assertTrue(disk.store().load().token == old.token)
        assertEquals(1, disk.tokenChanges)
        disk.beforeTokenWrite = null
        update(disk.store(), """{"mcpToken":"fixture-replacement"}""")
        assertEquals(1, disk.tokenWrites)
    }

    @Test fun emptyPatchNeverRegeneratesAndEnabledPatchPreservesPort() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        val old = disk.store().load()
        update(disk.store(), """{"mcpToken":""}""")
        update(disk.store(), """{"mcpToken":null}""")
        assertTrue(disk.store().load().token == old.token)
        assertEquals(0, disk.tokenWrites)
        val patched = update(disk.store(), """{"mcpToken":"fixture-replacement"}""")
        assertTrue(patched.tokenRequired)
        assertEquals(old.port, patched.port)
        assertTrue(patched.token != old.token)
    }

    @Test fun tokenAndAuthAndPortPatchesSerializeAcrossInstances() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        disk.store().load()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(3)
        disk.beforeTokenWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            val token = pool.submit { update(disk.store(), """{"mcpToken":"fixture-replacement"}""") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val auth = pool.submit { started.countDown(); update(disk.store(), """{"tokenRequired":false}""") }
            val port = pool.submit { started.countDown(); update(disk.store(), """{"port":45678}""") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
            listOf(token, auth, port).forEach { it.get(5, TimeUnit.SECONDS) }
            val saved = disk.store().load()
            assertFalse(saved.tokenRequired)
            assertEquals(45678, saved.port)
            assertTrue(saved.token == "fixture-replacement")
            assertEquals(1, disk.tokenWrites)
            assertEquals(1, disk.tokenChanges)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun invalidOtherFieldPreventsTokenWrite() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        disk.store().load()
        assertTrue(WebBridgeProtocol.parseSettings(JsonParser.parseString(
            """{"mcpToken":"fixture-replacement","port":80}""").asJsonObject) is WebBridgeProtocol.SettingsParse.Bad)
        assertThrows(IllegalArgumentException::class.java) {
            disk.store().updateConnection(80, null, "fixture-replacement")
        }
        assertEquals(0, disk.tokenWrites)
    }

    @Test fun mismatchedReadbackIsNotSuccessAndRetryConfirmsDisk() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        val old = disk.store().load()
        disk.afterTokenWrite = { disk.tokenReadOverride = { old.token } }
        assertThrows(IllegalStateException::class.java) {
            update(disk.store(), """{"mcpToken":"fixture-replacement"}""")
        }
        assertEquals(1, disk.tokenChanges)
        disk.afterTokenWrite = null
        disk.tokenReadOverride = null
        val saved = update(disk.store(), """{"mcpToken":"fixture-replacement"}""")
        assertTrue(saved.token != old.token)
        assertEquals(2, disk.tokenWrites)
    }

    @Test fun tokenPatchPreservesExplicitOffAndIsIdempotent() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        update(disk.store(), """{"tokenRequired":false}""")
        disk.failCommit = true // token-only must not touch unrelated prefs
        val patched = update(disk.store(), """{"mcpToken":"fixture-replacement"}""")
        assertTrue(patched.token == "fixture-replacement")
        assertFalse(patched.tokenRequired)
        assertEquals(McpConfigStore.DEFAULT_PORT, patched.port)
        update(disk.store(), """{"mcpToken":"fixture-replacement"}""")
        assertEquals(1, disk.tokenWrites)
        assertEquals(1, disk.tokenChanges)
        assertTrue(disk.store().load().token == patched.token)
    }

    @Test fun incrementalBridgeSettingsPreserveOffAndNeverRewriteToken() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        val original = disk.store().load()
        assertFalse(update(disk.store(), """{"tokenRequired":false}""").tokenRequired)
        assertFalse(update(disk.store(), """{"port":45678}""").tokenRequired)
        assertFalse(update(disk.store(), """{"appearance":{"theme":"dark"}}""").tokenRequired)
        assertFalse(update(disk.store(), """{"compact":true,"tokenRequired":null}""").tokenRequired)
        disk.store().saveCompactMode(true)
        assertFalse(disk.store().load().tokenRequired)
        assertTrue(disk.store().loadCompactMode())
        assertEquals(45678, disk.store().load().port)
        assertEquals(original.token, disk.store().load().token)
        assertEquals(0, disk.tokenWrites)
        assertEquals(0, disk.applyCalls)
    }

    @Test fun parallelPortPatchCannotOverwriteAuthenticationPatchAcrossStoreInstances() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        disk.store().load()
        val readEntered = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val once = AtomicBoolean()
        val pool = Executors.newFixedThreadPool(2)
        disk.afterRead = { key ->
            if (key == "port" && once.compareAndSet(false, true)) {
                readEntered.countDown()
                check(allowRead.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            val first = pool.submit<McpConfigStore.Config> { update(disk.store(), """{"tokenRequired":false}""") }
            assertTrue(readEntered.await(5, TimeUnit.SECONDS))
            val second = pool.submit<McpConfigStore.Config> {
                secondStarted.countDown()
                update(disk.store(), """{"port":45678}""")
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            allowRead.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertFalse(disk.store().load().tokenRequired)
            assertEquals(45678, disk.store().load().port)
            assertEquals(0, disk.tokenWrites)
        } finally {
            allowRead.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun explicitOffSurvivesLoadSaveAndStoreRecreationWithoutChangingToken() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        disk.seed("tokenRequired" to false, "port" to 45678)
        val original = disk.store().load()
        assertFalse(original.tokenRequired)
        assertEquals(45678, original.port)
        disk.store().save(original.copy(port = 45679))
        val restarted = disk.store().load()
        assertFalse(restarted.tokenRequired)
        assertEquals(45679, restarted.port)
        assertEquals(original.token, restarted.token)
        assertEquals("false", disk.stored("tokenRequired"))
        assertEquals(0, disk.tokenWrites)
        assertEquals(0, disk.tokenChanges)
        assertEquals(0, disk.applyCalls)
    }

    @Test fun newInstallDefaultsOnAndExplicitTogglePreservesExistingToken() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        val original = disk.store().load()
        assertTrue(original.tokenRequired)
        disk.store().save(original.copy(tokenRequired = false))
        assertFalse(disk.store().load().tokenRequired)
        disk.store().save(original.copy(tokenRequired = true))
        assertTrue(disk.store().load().tokenRequired)
        assertEquals(original.token, disk.store().load().token)
        assertEquals(0, disk.tokenWrites)
        assertEquals(0, disk.tokenChanges)
        assertEquals(0, disk.applyCalls)
    }

    @Test fun completelyNewInstallGeneratesOneTokenAndKeepsItAcrossRecreation() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        disk.clearToken()
        val first = disk.store().load()
        assertTrue(first.tokenRequired)
        assertTrue(first.token.matches(Regex("[A-Za-z0-9_-]{32}")))
        assertEquals(first, disk.store().load())
        assertEquals(1, disk.tokenWrites)
        assertEquals(0, disk.tokenChanges)
    }

    @Test fun legacyPlainTokenMigratesWithoutEnablingExplicitOff() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        disk.clearToken()
        disk.seed("tokenRequired" to false, "token" to "fixture-legacy-token", "port" to 1237)
        val first = disk.store().load()
        assertFalse(first.tokenRequired)
        assertEquals("fixture-legacy-token", first.token)
        assertEquals(58823, first.port)
        assertNull(disk.stored("token"))
        assertEquals(first, disk.store().load())
        assertEquals(1, disk.tokenWrites)
        assertEquals(0, disk.tokenChanges)
        assertEquals(0, disk.applyCalls)
    }

    @Test fun changedTokenInvalidatesEpochEvenWhenPreferencesCommitFails() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        val original = disk.store().load()
        disk.failCommit = true
        assertThrows(IllegalStateException::class.java) {
            disk.store().save(original.copy(port = 45678, token = "fixture-replacement"))
        }
        assertTrue(disk.store().load().token != original.token)
        assertEquals(1, disk.tokenChanges)
    }

    @Test fun failedCommitIsNotReportedAsSuccessfulSave() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        val original = disk.store().load()
        disk.failCommit = true
        assertThrows(IllegalStateException::class.java) { disk.store().save(original.copy(port = 45678)) }
        assertEquals(original, disk.store().load())
    }

    @Test fun requiredTokenCannotBeBlankAndValidationPrecedesWrites() {
        val disk = McpConfigDiskFixture(directory.newFolder())
        val original = disk.store().load()
        assertThrows(IllegalArgumentException::class.java) { disk.store().save(original.copy(token = "")) }
        assertThrows(IllegalArgumentException::class.java) { disk.store().save(original.copy(port = 80, tokenRequired = false)) }
        assertEquals(original, disk.store().load())
    }

    @Test fun defaultPortIs58823() {
        assertEquals(58823, McpConfigStore.DEFAULT_PORT)
    }

    @Test fun legacyDefaultPortMigratesButCustomPortIsPreserved() {
        assertEquals(58823, McpConfigStore.migrateLegacyDefaultPort(1237))
        assertEquals(45678, McpConfigStore.migrateLegacyDefaultPort(45678))
    }
}
