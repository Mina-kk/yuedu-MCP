package com.mina.legadostudio.network

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.gson.Gson
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.verification.WebViewCaptureEngine
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real producer/recorder lifecycles; only Android preferences and Room's IO boundary are faked. */
class CaptureDeletionLifecycleTest {
    private val unsafeClass = Class.forName("sun.misc.Unsafe")
    private val unsafe = unsafeClass.getDeclaredField("theUnsafe").let {
        it.isAccessible = true
        it.get(null)
    }
    private fun allocate(type: Class<*>): Any = checkNotNull(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))

    private class Harness(val recorder: HttpLogRecorder, val enabled: AtomicBoolean,
        val entered: CountDownLatch, val release: CountDownLatch,
        val executor: java.util.concurrent.ExecutorService)

    private fun recorder(): Harness {
        val enabled = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            if (method.name == "getBoolean") enabled.get() else null
        }
        val dao = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StudioDao::class.java)) { _, method, _ ->
            if (method.name == "addHttpLog") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                1L
            } else null
        }
        val executor = Executors.newSingleThreadExecutor()
        // Avoid android.jar stub constructors; retain the real recorder methods and FIFO executor.
        val recorder = allocate(HttpLogRecorder::class.java) as HttpLogRecorder
        for ((name, value) in mapOf("prefs" to prefs, "dao" to dao, "gson" to Gson(), "asyncWriter" to executor)) {
            HttpLogRecorder::class.java.getDeclaredField(name).apply { isAccessible = true }.set(recorder, value)
        }
        return Harness(recorder, enabled, entered, release, executor)
    }

    private fun engine(h: Harness) = WebViewCaptureEngine(
        allocate(ContextWrapper::class.java) as ContextWrapper,
        h.recorder, { emptyList() }, { h.enabled.get() },
    )

    @Test fun deletionReservationExcludesOnlyItsExactContextAndReleasesOnFailure() = runBlocking {
        val id = "cap:deletion-race"
        val other = "cap:other-live"
        CaptureActivity.start(other)
        try {
            assertTrue(CaptureActivity.deleteIfInactive(id) {
                assertFalse(CaptureActivity.deleteIfInactive(id) { fail("double delete") })
                assertTrue(runCatching { CaptureActivity.start(id) }.isFailure)
            })
            CaptureActivity.start(id)
            assertFalse(CaptureActivity.deleteIfInactive(id) { fail("active delete") })
            CaptureActivity.finish(id)
            assertTrue(runCatching {
                CaptureActivity.deleteIfInactive(id) { error("disk failure") }
            }.isFailure)
            assertTrue(CaptureActivity.deleteIfInactive(id) {})
        } finally {
            CaptureActivity.finish(id)
            CaptureActivity.finish(other)
        }
    }

    @Test fun disabledSettlementCannotOvertakeEarlierDatabaseWrite() {
        val h = recorder()
        val engine = engine(h)
        val session = engine.newSession("https://example.com/")
        try {
            h.recorder.recordAsync(HttpLogRecorder.Draft("GET", "https://example.com/", contextId = session.contextId))
            assertTrue(h.entered.await(5, TimeUnit.SECONDS))
            h.enabled.set(false)
            // The old inline callback also invokes android.util.Log (a JVM stub).
            try {
                engine.finishSession(session, session.entryUrl, System.currentTimeMillis())
            } catch (error: RuntimeException) {
                // Only android.jar's known Log stub is tolerated, never lifecycle failures.
                check(error.message.orEmpty().contains("Method w in android.util.Log not mocked")) { error }
            }
            assertTrue("pending resource must protect the session after recording is disabled", engine.isCaptureSessionActive(session.contextId))
        } finally {
            h.release.countDown()
            h.executor.shutdown()
            assertTrue(h.executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertFalse(engine.isCaptureSessionActive(session.contextId))
    }

    @Test fun filteredSettlementStillFollowsEarlierDatabaseWrite() {
        val h = recorder()
        val settled = CountDownLatch(1)
        try {
            h.recorder.recordAsync(HttpLogRecorder.Draft("GET", "https://example.com/"))
            assertTrue(h.entered.await(5, TimeUnit.SECONDS))
            h.recorder.recordAsync(HttpLogRecorder.Draft("GET", "http://127.0.0.1/")) { settled.countDown() }
            assertEquals("filtered callbacks must also be FIFO barriers", 1L, settled.count)
        } finally {
            h.release.countDown()
            h.executor.shutdown()
            assertTrue(h.executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(0L, settled.count)
    }

    @Test fun captureOnceRemainsActiveBetweenRedirectAndFinalResponse() = runBlocking {
        val h = recorder()
        val engine = engine(h)
        val server = MockWebServer()
        val hopSeen = CompletableDeferred<String>()
        val continueHop = CountDownLatch(1)
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
        server.enqueue(MockResponse().setBody("done"))
        server.start()
        val rows = java.util.Collections.synchronizedList(mutableListOf<CaptureOnce.LogEntry>())
        val capture = CaptureOnce(
            fetcherFactory = { redirect, dns, hop -> HttpFetcher(
                unsafeRedirectGuard = redirect, unsafeDnsGuard = dns, unsafePerHopRecorder = hop,
                unsafeSyncRecorder = { rows.add(CaptureOnce.LogEntry(2, "GET", it.url, it.finalUrl, 200, 0, "", "capture_once")) },
            ) },
            logStore = object : CaptureOnce.LogStore {
                override val recordingEnabled = true
                override suspend fun logsByContextId(contextId: String) = rows.toList()
                override fun recordHop(hop: HttpFetcher.RedirectHop, sourceAnchor: String?, contextId: String, originKind: String) {
                    rows.add(CaptureOnce.LogEntry(1, "GET", hop.requestUrl, "", 302, 0, "", originKind))
                    hopSeen.complete(contextId)
                    check(continueHop.await(5, TimeUnit.SECONDS))
                }
            },
            requiresWebView = { false }, entryTargetValidator = {},
            guardOverrides = CaptureOnce.GuardOverrides(redirect = { null }, dns = { null }),
        )
        val job = async(Dispatchers.Default) { capture.run(CaptureOnce.Params(server.url("/start").toString())) }
        var id: String? = null
        try {
            id = withTimeout(5000) { hopSeen.await() }
            assertTrue("capture_once's first persisted hop is not a completed capture", engine.isCaptureSessionActive(id))
            assertFalse(CaptureActivity.deleteIfInactive(id) { rows.clear() })
            assertEquals(1, rows.size)
        } finally {
            continueHop.countDown()
            job.await()
            server.shutdown()
            h.release.countDown()
            h.executor.shutdownNow()
        }
        assertFalse(engine.isCaptureSessionActive(id))
        assertEquals(2, rows.size)
        assertTrue(CaptureActivity.deleteIfInactive(id) { rows.clear() })
        assertTrue(rows.isEmpty())
    }
}
