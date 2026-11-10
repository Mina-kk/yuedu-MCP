package com.mina.legadostudio.verification

import android.content.ContextWrapper
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.data.db.VerificationSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class VerificationCoordinatorRepairTest {
    @Test fun repairsInvalidWaitingAddressWithoutChangingIdentityOrCookies() = runBlocking {
        Fixture(row()).use { fixture ->
            val old = fixture.row!!
            val repaired = repair(fixture.coordinator, old.id, "https://sub.example.org/verify?q=1")
            assertEquals(old.id, repaired.id)
            assertEquals(old.jobId, repaired.jobId)
            assertEquals(old.purpose, repaired.purpose)
            assertEquals(old.createdAt, repaired.createdAt)
            assertEquals(old.kind, repaired.kind)
            assertEquals("https://sub.example.org/verify?q=1", repaired.url)
            assertEquals("example.org", repaired.domain)
            assertEquals("WAITING", repaired.status)
            assertEquals("", repaired.finalUrl)
            assertEquals("", repaired.answer)
            assertTrue(repaired.updatedAt >= old.updatedAt)
            assertEquals(repaired, fixture.row)
            assertEquals(listOf("verificationSession", "saveVerificationSession"), fixture.calls)
            assertFalse(fixture.stateFile.exists())
            assertTrue(fixture.otherStateFile.exists())
        }
    }

    @Test fun repairsInvalidCompletedAddressAsWaitingWithoutOldSuccessEvidence() = runBlocking {
        Fixture(row(status = "COMPLETED", finalUrl = "https://old.example.org/", answer = "old-answer")).use { fixture ->
            val repaired = repair(fixture.coordinator, fixture.row!!.id, "https://example.org/challenge")
            assertEquals("WAITING", repaired.status)
            assertEquals("", repaired.finalUrl)
            assertEquals("", repaired.answer)
            assertEquals(repaired, fixture.row)
            assertFalse(fixture.stateFile.exists())
        }
    }

    @Test fun refusesToReplaceAnAlreadyAllowedAddressEvenWhenCompleted() = runBlocking {
        for (status in listOf("WAITING", "COMPLETED")) {
            Fixture(row(status = status, url = "https://example.org/original")).use { fixture ->
                val old = fixture.row
                val error = runCatching { repair(fixture.coordinator, old!!.id, "https://other.example.com/") }.exceptionOrNull()
                assertTrue("got $error", error is IllegalArgumentException)
                assertEquals(old, fixture.row)
                assertEquals(listOf("verificationSession"), fixture.calls)
                assertTrue(fixture.stateFile.exists())
            }
        }
    }

    @Test fun refusesUnsupportedStatusesAndMissingRecord() = runBlocking {
        for (old in listOf(row(status = "CANCELLED"), row(status = "FAILED"), null)) {
            Fixture(old).use { fixture ->
                val error = runCatching { repair(fixture.coordinator, "repair-test", "https://example.org/") }.exceptionOrNull()
                assertTrue("got $error", error is IllegalArgumentException || error is IllegalStateException)
                assertEquals(old, fixture.row)
                assertEquals(listOf("verificationSession"), fixture.calls)
                assertTrue(fixture.stateFile.exists())
            }
        }
    }

    @Test fun rejectedReplacementNeverReadsOrMutatesStoredRecord() = runBlocking {
        for (url in listOf("", " ", "not-a-url", "http://127.0.0.1/", "https://user:secret@example.org/", "javascript:alert(1)")) {
            Fixture(row()).use { fixture ->
                val old = fixture.row
                val error = runCatching { repair(fixture.coordinator, old!!.id, url) }.exceptionOrNull()
                assertTrue("got $error", error is IllegalArgumentException)
                assertEquals(old, fixture.row)
                assertTrue(fixture.calls.isEmpty())
                assertTrue(fixture.stateFile.exists())
            }
        }
    }

    private class Fixture(var row: VerificationSessionEntity?) : AutoCloseable {
        val calls = mutableListOf<String>()
        private val dir = Files.createTempDirectory(File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")).toPath(), "verification-repair-").toFile()
        val stateFile = File(dir, "repair-test.bin").apply { writeText("old webview state") }
        val otherStateFile = File(dir, "other-session.bin").apply { writeText("unrelated state") }
        private val dao = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StudioDao::class.java)) { _, method, args ->
            calls += method.name
            when (method.name) {
                "verificationSession" -> row?.takeIf { it.id == args!![0] }
                "saveVerificationSession" -> { row = args!![0] as VerificationSessionEntity; Unit }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as StudioDao
        private val webState = allocate(VerificationWebViewStateStore::class.java).also {
            VerificationWebViewStateStore::class.java.getDeclaredField("dir").apply { isAccessible = true }.set(it, dir)
        }
        // Uninitialized cookie/context dependencies are deliberate: every accidental Cookie,
        // notification or Android UI operation fails rather than being silently mocked away.
        val coordinator = VerificationCoordinator(allocate(ContextWrapper::class.java), dao, allocate(RuntimeCookieStore::class.java), webState)
        override fun close() { dir.deleteRecursively() }
    }

    // Exercise the real suspend entry point, including its shared completion lock and DAO write.
    private suspend fun repair(coordinator: VerificationCoordinator, id: String, url: String): VerificationSessionEntity =
        coordinator.repairAddress(id, url)

    companion object {
        private fun row(status: String = "WAITING", url: String = "", finalUrl: String = "", answer: String = "") = VerificationSessionEntity(
            id = "repair-test", jobId = "mcp", domain = "", url = url, purpose = "original purpose", status = status,
            finalUrl = finalUrl, createdAt = 1L, updatedAt = 2L, kind = "webview", answer = answer, imageData = "",
        )
        private fun <T> allocate(type: Class<T>): T {
            val klass = Class.forName("sun.misc.Unsafe")
            val unsafe = klass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return type.cast(klass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
        }
    }
}
