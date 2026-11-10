package com.mina.legadostudio.verification

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.gson.Gson
import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.network.CaptureActivity
import com.mina.legadostudio.network.HttpLogRecorder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the headless session owner, real write gate and real recorder FIFO, not an active mock. */
class WebViewHeadlessFailureLifecycleTest {
    private fun allocate(type: Class<*>): Any {
        val klass = Class.forName("sun.misc.Unsafe")
        val unsafe = klass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return checkNotNull(klass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }

    private enum class Failure { EXCEPTION, TIMEOUT, CANCEL }

    @Test fun exceptionClosesGateButRetainsOwnershipUntilWritesDrain() = checkFailure(Failure.EXCEPTION)
    @Test fun timeoutClosesGateButRetainsOwnershipUntilWritesDrain() = checkFailure(Failure.TIMEOUT)
    @Test fun cancellationClosesGateButRetainsOwnershipUntilWritesDrain() = checkFailure(Failure.CANCEL)
    @Test fun disabledRecordingAfterCancellationStillDrainsPendingWrites() = checkFailure(Failure.CANCEL, disabled = true)
    @Test fun failedSettlementWriteStillReleasesOwnership() = checkFailure(Failure.EXCEPTION, failSettlement = true)

    private fun checkFailure(failure: Failure, disabled: Boolean = false, failSettlement: Boolean = false) = runBlocking {
        val enabled = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rows = Collections.synchronizedList(mutableListOf<HttpLogEntity>())
        val executor = Executors.newSingleThreadExecutor()
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            if (method.name == "getBoolean") enabled.get() else null
        }
        val dao = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StudioDao::class.java)) { _, method, args ->
            if (method.name == "addHttpLog") {
                val row = args!![0] as HttpLogEntity
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                if (failSettlement && row.originKind == WebViewCaptureEngine.ORIGIN_WEBVIEW_SESSION) error("settlement disk failure")
                rows.add(row)
                1L
            } else null
        }
        val recorder = allocate(HttpLogRecorder::class.java) as HttpLogRecorder
        for ((name, value) in mapOf("prefs" to prefs, "dao" to dao, "gson" to Gson(), "asyncWriter" to executor)) {
            HttpLogRecorder::class.java.getDeclaredField(name).apply { isAccessible = true }.set(recorder, value)
        }
        val engine = WebViewCaptureEngine(allocate(ContextWrapper::class.java) as ContextWrapper, recorder, { emptyList() }, { enabled.get() })
        val sessionReady = CompletableDeferred<WebViewCapture.Session>()
        val failureSignal = IllegalStateException("WebView setup failed")
        var session: WebViewCapture.Session? = null
        val job = async {
            runCatching {
                engine.withHeadlessSession("https://example.com/") { current, _ ->
                    // Model a producer that entered before failure, but enqueues its row after cleanup.
                    check(current.beginWrite())
                    sessionReady.complete(current)
                    when (failure) {
                        Failure.EXCEPTION -> throw failureSignal
                        Failure.TIMEOUT -> withTimeout(20) { awaitCancellation() }
                        Failure.CANCEL -> awaitCancellation()
                    }
                }
            }
        }
        try {
            session = withTimeout(5000) { sessionReady.await() }
            if (disabled) enabled.set(false)
            if (failure == Failure.CANCEL) job.cancel()
            job.join()
            if (failure != Failure.CANCEL) {
                val error = job.await().exceptionOrNull()
                if (failure == Failure.EXCEPTION) assertSame(failureSignal, error)
                else assertTrue(error is TimeoutCancellationException)
            } else assertTrue(job.isCancelled)
            assertFalse("failure must reject late callback writes", session.beginWrite())
            assertTrue("in-flight producer still owns capture", engine.isCaptureSessionActive(session.contextId))
            assertFalse(CaptureActivity.deleteIfInactive(session.contextId) { fail("deleted before producer exited") })

            // Ensure a real pending DB write even when recording was switched off during cancellation.
            enabled.set(true)
            recorder.recordAsync(HttpLogRecorder.Draft("GET", session.entryUrl, contextId = session.contextId))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (disabled) enabled.set(false)
            session.endWrite()
            assertTrue("FIFO must not release while DAO is blocked", engine.isCaptureSessionActive(session.contextId))
            assertFalse(CaptureActivity.deleteIfInactive(session.contextId) { fail("deleted before FIFO drained") })
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertFalse(engine.isCaptureSessionActive(session.contextId))
            assertEquals(if (disabled || failSettlement) 1 else 2, rows.size)
            if (!disabled && !failSettlement) {
                assertEquals(WebViewCaptureEngine.ORIGIN_WEBVIEW_SESSION, rows.last().originKind)
            }
            assertFalse("closed gate must stay closed after settlement", session.beginWrite())
            assertTrue(CaptureActivity.deleteIfInactive(session.contextId) { rows.clear() })
            assertTrue(rows.isEmpty())
        } finally {
            release.countDown()
            job.cancelAndJoin()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            session?.let { CaptureActivity.finish(it.contextId) }
        }
    }
}
