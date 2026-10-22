package com.mina.legadostudio.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 保活门控、就绪信号与在途端点登记表的可测契约：
 * 拿不到进程保护时必须明确失败，而不是让调用方继续唤起阅读去连一个注定没人接的端口。
 */
class ReaderImportKeepAliveTest {

    private fun ensure(
        mcpActive: Boolean = false,
        serviceReady: Boolean = false,
        onMainThread: Boolean = false,
        requestStart: () -> Unit = {},
        awaitReady: (Long) -> Boolean = { true },
    ) = ReaderImportKeepAlive.ensure(mcpActive, serviceReady, onMainThread, requestStart, awaitReady)

    // ---- 保活门控 ----

    @Test
    fun reusesRunningMcpForegroundServiceWithoutNewStart() {
        var requested = false
        val outcome = ensure(mcpActive = true, requestStart = { requested = true })
        assertEquals(ReaderImportKeepAlive.Outcome.ReusedMcp, outcome)
        assertFalse("must not start a second FGS while MCP runs", requested)
    }

    @Test
    fun existingReadyServiceIsReused() {
        var requested = false
        val outcome = ensure(serviceReady = true, requestStart = { requested = true })
        assertEquals(ReaderImportKeepAlive.Outcome.Confirmed, outcome)
        assertFalse(requested)
    }

    @Test
    fun rejectedForegroundStartIsHardFailure() {
        val outcome = ensure(
            requestStart = { throw IllegalStateException("ForegroundServiceStartNotAllowedException") },
        )
        assertTrue(outcome is ReaderImportKeepAlive.Outcome.Unavailable)
        assertTrue(
            (outcome as ReaderImportKeepAlive.Outcome.Unavailable).reason
                .contains("ForegroundServiceStartNotAllowedException"),
        )
    }

    @Test
    fun mcpActiveShortCircuitsEvenWhenStartWouldFail() {
        val outcome = ensure(
            mcpActive = true,
            requestStart = { throw IllegalStateException("must not be called") },
        )
        assertEquals(ReaderImportKeepAlive.Outcome.ReusedMcp, outcome)
    }

    @Test
    fun backgroundCallerWaitsForReadiness() {
        var awaited = -1L
        val outcome = ensure(
            awaitReady = { timeoutMs ->
                awaited = timeoutMs
                true
            },
        )
        assertEquals(ReaderImportKeepAlive.Outcome.Confirmed, outcome)
        assertEquals(ReaderImportKeepAlive.READY_TIMEOUT_MS, awaited)
    }

    @Test
    fun readinessTimeoutIsHardFailure() {
        val outcome = ensure(awaitReady = { false })
        assertTrue(outcome is ReaderImportKeepAlive.Outcome.Unavailable)
        assertTrue(
            (outcome as ReaderImportKeepAlive.Outcome.Unavailable).reason
                .contains(ReaderImportKeepAlive.READY_TIMEOUT_MS.toString()),
        )
    }

    @Test
    fun mainThreadCannotBlockOnItsOwnServiceCreation() {
        var awaited = false
        val outcome = ensure(onMainThread = true, awaitReady = { awaited = true; true })
        assertEquals(ReaderImportKeepAlive.Outcome.Requested, outcome)
        assertFalse("waiting on main thread would deadlock service onCreate", awaited)
    }

    @Test
    fun mainThreadStillFailsWhenSystemRejectsTheRequest() {
        val outcome = ensure(
            onMainThread = true,
            requestStart = { throw SecurityException("not allowed") },
        )
        assertTrue(outcome is ReaderImportKeepAlive.Outcome.Unavailable)
    }

    @Test
    fun adviceIsActionableAndLeaksNoEndpoint() {
        val text = ReaderImportKeepAlive.advice("前台服务启动失败：boom")
        assertTrue(text.contains("重新发起导入"))
        assertTrue(text.contains("MCP"))
        assertTrue(text.contains("本次端点已关闭"))
        assertFalse(text.contains("127.0.0.1"))
        assertFalse(text.contains("http"))
    }

    // ---- 就绪信号 ----

    @Test
    fun readinessReleasesWaitersAfterSuccess() {
        val readiness = ServiceReadiness()
        assertFalse(readiness.isReady())
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<String?> { readiness.await(2_000) }
        Thread.sleep(100)
        readiness.markReady()
        assertNull(result.get(2, TimeUnit.SECONDS))
        assertTrue(readiness.isReady())
        assertNull("already-ready wait returns immediately", readiness.await(10))
        executor.shutdownNow()
    }

    @Test
    fun readinessPropagatesForegroundFailure() {
        val readiness = ServiceReadiness()
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<String?> { readiness.await(2_000) }
        Thread.sleep(100)
        readiness.markFailed("前台服务启动失败：boom")
        assertEquals("前台服务启动失败：boom", result.get(2, TimeUnit.SECONDS))
        assertFalse(readiness.isReady())
        executor.shutdownNow()
    }

    @Test
    fun failureListenerRunsSoUnprotectedEndpointsCanBeClosed() {
        val readiness = ServiceReadiness()
        var seen: String? = null
        readiness.onFailure { seen = it }
        readiness.markFailed("boom")
        assertEquals("boom", seen)
    }

    @Test
    fun readinessTimesOutWhenServiceNeverStarts() {
        val readiness = ServiceReadiness()
        val startedAt = System.currentTimeMillis()
        val reason = readiness.await(150)
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("must report timeout, got $reason", reason != null && reason.contains("150"))
        assertTrue("must not block much longer than the timeout, took ${elapsed}ms", elapsed < 2_000)
    }

    @Test
    fun resetClearsStaleReadiness() {
        val readiness = ServiceReadiness()
        readiness.markReady()
        readiness.reset()
        assertFalse(readiness.isReady())
        assertTrue(readiness.await(50) != null)
    }

    // ---- 在途端点登记表 ----

    private class FakeEndpoint(val name: String) : Closeable {
        @Volatile var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun registryEnforcesParallelLimitWithoutDroppingExisting() {
        val registry = EndpointRegistry<FakeEndpoint>(2)
        val a = FakeEndpoint("a")
        val b = FakeEndpoint("b")
        val c = FakeEndpoint("c")
        assertTrue(registry.register("a", a))
        assertTrue(registry.register("b", b))
        assertFalse(registry.register("c", c))
        assertEquals(2, registry.size)
        assertFalse("rejected endpoint must be closed by the caller, not registered", c.closed)
        assertFalse(a.closed)
        assertFalse(b.closed)
    }

    @Test
    fun cancellingOneEndpointKeepsOthersAlive() {
        val registry = EndpointRegistry<FakeEndpoint>(4)
        val a = FakeEndpoint("a")
        val b = FakeEndpoint("b")
        registry.register("a", a)
        registry.register("b", b)

        registry.remove("a")?.close()

        assertTrue(a.closed)
        assertFalse(b.closed)
        assertEquals(1, registry.size)
        assertTrue(registry.contains(b))
        assertFalse(registry.contains(a))
    }

    @Test
    fun abortUnconfirmedOnlyClosesMainThreadRequests() {
        val registry = EndpointRegistry<FakeEndpoint>(4)
        val confirmed = FakeEndpoint("confirmed")
        val pending = FakeEndpoint("pending")
        registry.register("confirmed", confirmed)
        registry.register("pending", pending)
        registry.markUnconfirmed("pending")

        assertEquals(1, registry.abortUnconfirmed())

        assertFalse(confirmed.closed)
        assertTrue(pending.closed)
        assertEquals(1, registry.size)
    }

    @Test
    fun abortedEndpointIsNotCountedTwice() {
        val registry = EndpointRegistry<FakeEndpoint>(4)
        val pending = FakeEndpoint("pending")
        registry.register("pending", pending)
        registry.markUnconfirmed("pending")

        assertEquals(1, registry.abortUnconfirmed())
        assertEquals("second abort must be a no-op", 0, registry.abortUnconfirmed())
        assertEquals(0, registry.size)
    }

    @Test
    fun stopIfEmptyRunsOnlyWhenNoEndpointIsPending() {
        val registry = EndpointRegistry<FakeEndpoint>(4)
        var stopped = 0
        assertTrue(registry.stopIfEmpty { stopped++ })

        registry.register("a", FakeEndpoint("a"))
        assertFalse(registry.stopIfEmpty { stopped++ })
        assertEquals(1, stopped)
    }

    @Test
    fun clearAllReleasesEverything() {
        val registry = EndpointRegistry<FakeEndpoint>(4)
        val a = FakeEndpoint("a")
        val b = FakeEndpoint("b")
        registry.register("a", a)
        registry.register("b", b)
        registry.markUnconfirmed("b")

        registry.clearAll()

        assertTrue(a.closed)
        assertTrue(b.closed)
        assertTrue(registry.isEmpty())
        assertEquals(0, registry.abortUnconfirmed())
    }

    @Test
    fun registryIsConcurrencySafe() {
        val registry = EndpointRegistry<FakeEndpoint>(3)
        val accepted = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = (1..12).map { index ->
            Thread {
                val endpoint = FakeEndpoint("e$index")
                if (registry.register("e$index", endpoint)) {
                    accepted += "e$index"
                } else {
                    endpoint.close()
                }
            }.apply { start() }
        }
        threads.forEach { it.join(5_000) }
        assertEquals(3, accepted.size)
        assertEquals(3, registry.size)
    }
}
