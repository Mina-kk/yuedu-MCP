package com.mina.legadostudio.verification

import android.content.ContextWrapper
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.data.db.VerificationSessionEntity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real coordinator read/modify/write operations; the DAO pauses an actual persisted snapshot. */
class VerificationCoordinatorConcurrencyTest {
    @Test fun repairCannotOverwriteCreateRefresh() = runBlocking {
        Fixture(row(url = "")).use { f ->
            val gate = f.pauseNextRead("waitingVerification")
            val create = async(Dispatchers.IO) { f.coordinator.create("mcp", NEW_URL, "new purpose", "image_code") }
            gate.awaitEntered()
            val repair = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.coordinator.repairAddress(ID, REPAIR_URL) } }
            gate.release.countDown()
            create.await()
            assertTrue("repair must re-read refreshed public URL", repair.await().exceptionOrNull() is IllegalArgumentException)
            assertEquals(NEW_URL, f.row!!.url)
            assertEquals("new purpose", f.row!!.purpose)
        }
    }

    @Test fun createSnapshotCannotResetConcurrentCompletion() = runBlocking {
        Fixture(row()).use { f ->
            val gate = f.pauseNextRead("waitingVerification")
            val create = async(Dispatchers.IO) { f.coordinator.create("mcp", NEW_URL, "new purpose", "image_code") }
            gate.awaitEntered()
            val complete = async(start = CoroutineStart.UNDISPATCHED) { f.complete() }
            gate.release.countDown()
            create.await(); complete.await()
            assertEquals("COMPLETED", f.row!!.status)
            assertEquals(NEW_URL, f.row!!.url)
            assertEquals(FINAL_URL, f.row!!.finalUrl)
            assertEquals("1234", f.row!!.answer)
        }
    }

    @Test fun updateSnapshotCannotUndoCompletion() = runBlocking {
        Fixture(row()).use { f ->
            val gate = f.pauseNextRead("verificationSession")
            val update = async(Dispatchers.IO) { f.coordinator.updateCurrentUrl(ID, "https://example.org/loading") }
            gate.awaitEntered()
            val complete = async(start = CoroutineStart.UNDISPATCHED) { f.complete() }
            gate.release.countDown()
            update.await(); complete.await()
            assertEquals("COMPLETED", f.row!!.status)
            assertEquals(FINAL_URL, f.row!!.finalUrl)
            assertEquals("1234", f.row!!.answer)
        }
    }

    @Test fun lateNavigationCannotReplaceCompletedFinalUrl() = runBlocking {
        Fixture(row(status = "COMPLETED", finalUrl = FINAL_URL, answer = "1234")).use { f ->
            val old = f.row
            f.coordinator.updateCurrentUrl(ID, "https://example.org/stale")
            assertEquals(old, f.row)
        }
    }

    @Test fun webviewCompletionRejectsInvalidStoredUrlInsideCoordinator() = runBlocking {
        Fixture(row(url = "", kind = "webview")).use { f ->
            val old = f.row
            val error = runCatching { f.coordinator.complete(ID, FINAL_URL) }.exceptionOrNull()
            assertTrue("must validate before Cookie/Android access, got $error", error is IllegalArgumentException)
            assertEquals(old, f.row)
        }
    }

    @Test fun closeCannotBeUndoneByInflightNavigationUpdate() = runBlocking {
        Fixture(row()).use { f ->
            val gate = f.pauseNextRead("verificationSession")
            val update = async(Dispatchers.IO) { f.coordinator.updateCurrentUrl(ID, NEW_URL) }
            gate.awaitEntered()
            val close = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.close(ID) }
            gate.release.countDown()
            update.await(); close.await()
            assertNull("deleted row must not be resurrected", f.row)
        }
    }

    @Test fun closeCannotBeUndoneByInflightCreateReuse() = runBlocking {
        Fixture(row()).use { f ->
            val gate = f.pauseNextRead("waitingVerification")
            val create = async(Dispatchers.IO) { f.coordinator.create("mcp", NEW_URL, "new purpose", "image_code") }
            gate.awaitEntered()
            val close = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.close(ID) }
            gate.release.countDown()
            create.await(); close.await()
            assertNull("deleted row must not be resurrected", f.row)
        }
    }

    @Test fun pausedRepairCannotOverwriteNewerCreateUrl() = runBlocking {
        Fixture(row(url = "")).use { f ->
            val gate = f.pauseNextRead("verificationSession")
            val repair = async(Dispatchers.IO) { f.coordinator.repairAddress(ID, REPAIR_URL) }
            gate.awaitEntered()
            val create = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.create("mcp", NEW_URL, "new purpose", "image_code") }
            gate.release.countDown()
            repair.await(); create.await()
            assertEquals(NEW_URL, f.row!!.url)
            assertEquals("new purpose", f.row!!.purpose)
        }
    }

    @Test fun automaticRejectsDeletedReopenedManualAndChangedUrl() = runBlocking {
        Fixture(row(kind = "webview")).use { f ->
            val original = f.row!!
            val generation = f.coordinator.beginBrowserGeneration(ID, original.url)!!
            assertNull(f.coordinator.completeAutomatically(ID, original.url, generation, FINAL_URL) { false })
            val next = f.coordinator.beginBrowserGeneration(ID, original.url)!!
            assertNull(f.coordinator.completeAutomatically(ID, original.url, generation, FINAL_URL) { true })
            f.row = original.copy(jobId = "manual")
            assertNull(f.coordinator.completeAutomatically(ID, original.url, next, FINAL_URL) { true })
            f.row = original.copy(url = NEW_URL)
            assertNull(f.coordinator.completeAutomatically(ID, original.url, next, FINAL_URL) { true })
            f.row = original.copy(status = "COMPLETED")
            assertNull(f.coordinator.completeAutomatically(ID, original.url, next, FINAL_URL) { true })
            f.row = original
            f.coordinator.close(ID)
            assertNull(f.coordinator.completeAutomatically(ID, original.url, next, FINAL_URL) { true })
            assertNull(f.row)
        }
    }

    @Test fun automaticRechecksLiveGenerationAfterBlockedDaoRead() = runBlocking {
        Fixture(row(kind = "webview")).use { f ->
            val original = f.row!!
            val generation = f.coordinator.beginBrowserGeneration(ID, original.url)!!
            val live = AtomicBoolean(true)
            val gate = f.pauseNextRead("verificationSession")
            val completion = async(Dispatchers.IO) {
                f.coordinator.completeAutomatically(ID, original.url, generation, FINAL_URL) { live.get() }
            }
            gate.awaitEntered(); live.set(false); gate.release.countDown()
            assertNull(completion.await())
            assertEquals(original, f.row)
        }
    }

    private class Gate(val method: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val claimed = AtomicBoolean()
        fun awaitEntered() { assertTrue("DAO read not reached", entered.await(5, TimeUnit.SECONDS)) }
    }

    private class Fixture(@Volatile var row: VerificationSessionEntity?) : AutoCloseable {
        private var gate: Gate? = null
        private val dir = Files.createTempDirectory(File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir")).toPath(), "verification-race-").toFile()
        private val webState = allocate(VerificationWebViewStateStore::class.java).also {
            it.javaClass.getDeclaredField("dir").apply { isAccessible = true }.set(it, dir)
        }
        private val dao = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StudioDao::class.java)) { _, method, args ->
            when (method.name) {
                "waitingVerification", "verificationSession" -> {
                    val snapshot = row?.takeIf { method.name != "waitingVerification" || (it.status == "WAITING" && it.jobId == args!![0] && it.domain == args[1]) }
                    gate?.takeIf { it.method == method.name && it.claimed.compareAndSet(false, true) }?.let {
                        it.entered.countDown()
                        check(it.release.await(5, TimeUnit.SECONDS)) { "DAO latch timed out" }
                    }
                    snapshot
                }
                "saveVerificationSession" -> {
                    row = args!![0] as VerificationSessionEntity
                    Unit
                }
                "deleteVerificationSession" -> { row = null; Unit }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as StudioDao
        // Only the Android notification endpoint is replaced; persistence, locks, validation,
        // completion, repair, state-file removal and close execute the production methods.
        val coordinator = VerificationCoordinator(allocate(ContextWrapper::class.java), dao, allocate(RuntimeCookieStore::class.java), webState) { }
        fun pauseNextRead(method: String) = Gate(method).also { gate = it }
        suspend fun complete() {
            assertEquals("COMPLETED", coordinator.complete(ID, FINAL_URL, "1234").status)
        }
        override fun close() { gate?.release?.countDown(); dir.deleteRecursively() }
    }

    companion object {
        private const val ID = "race-test"
        private const val NEW_URL = "https://example.org/new"
        private const val REPAIR_URL = "https://example.org/repair"
        private const val FINAL_URL = "https://example.org/done"
        private fun row(url: String = "https://example.org/old", status: String = "WAITING", kind: String = "image_code", finalUrl: String = "", answer: String = "") = VerificationSessionEntity(
            ID, "mcp", "example.org", url, "old purpose", status, finalUrl, 1, 2, kind, answer, "",
        )
        private fun <T : Any> allocate(type: Class<T>): T {
            val klass = Class.forName("sun.misc.Unsafe")
            val unsafe = klass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return requireNotNull(type.cast(klass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)))
        }
    }
}
