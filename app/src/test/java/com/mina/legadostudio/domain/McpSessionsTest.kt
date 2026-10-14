package com.mina.legadostudio.domain

import com.mina.legadostudio.mcp.McpSessions
import com.mina.legadostudio.mcp.McpStats
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class McpSessionsTest {
    private var now = 0L
    private val ids = mutableMapOf<Server, MutableList<String>>()
    private val closeCalls = mutableListOf<Server>()

    private fun server(name: String = "reap-test") = Server(
        serverInfo = Implementation(name, "1"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )

    @After fun cleanup() {
        McpSessions.clear()
        McpSessions.clock = { System.currentTimeMillis() }
        McpSessions.reapIdleMs = 30 * 60_000L
        McpSessions.closeTimeoutMs = 3_000L
        McpSessions.closeServer = { it.close() }
        McpSessions.sessionIdsOf = { server -> runCatching { server.sessions.keys.toList() }.getOrDefault(emptyList()) }
        McpStats.resetConnections()
        McpStats.clock = { System.currentTimeMillis() }
        McpStats.idleWindowMs = 60_000L
    }

    private fun useClock() {
        McpSessions.clear()
        McpStats.resetConnections()
        McpSessions.clock = { now }
        McpStats.clock = { now }
        McpSessions.sessionIdsOf = { server -> ids[server].orEmpty() }
        McpSessions.closeServer = { server -> closeCalls.add(server) }
    }

    private fun attach(server: Server, vararg sessionIds: String) {
        ids[server] = sessionIds.toMutableList()
    }

    @Test fun reapsIdleSessionAndRejectsReuse() = runBlocking {
        useClock()
        McpSessions.reapIdleMs = 1000
        val server = server()
        attach(server, "sid-a")
        McpSessions.register(server)
        McpSessions.touchToolCall(server)
        assertEquals(1, McpSessions.trackedSessions())
        assertEquals(true, McpSessions.isKnown("sid-a"))
        assertEquals(0, McpSessions.reap().closed)
        now = 999
        assertEquals(0, McpSessions.reap().closed)
        now = 1001
        val scan = McpSessions.reap()
        assertEquals(1, scan.closed)
        assertEquals(listOf(server), closeCalls)
        assertEquals(false, McpSessions.isKnown("sid-a"))
        assertEquals(0, McpSessions.trackedSessions())
        assertEquals(1L, McpStats.snapshot()["sessionReaped"])
        assertEquals(1, McpSessions.reapedTotal())
    }

    @Test fun toolCallRegistersSessionAndMarksActive() = runBlocking {
        useClock()
        val server = server()
        attach(server, "sid-a")
        McpSessions.register(server)
        assertEquals(0, McpSessions.trackedSessions())
        McpSessions.touchToolCall(server)
        assertEquals(1, McpSessions.trackedSessions())
        assertEquals(1L, McpStats.snapshot()["clientCount"])
        assertEquals(true, McpSessions.isKnown("sid-a"))
    }

    @Test fun toolCallKeepsSessionFresh() = runBlocking {
        useClock()
        McpSessions.reapIdleMs = 1000
        val server = server()
        attach(server, "sid-a")
        McpSessions.register(server)
        McpSessions.touchToolCall(server)
        now = 900
        McpSessions.touchToolCall(server)
        now = 1500
        assertEquals(0, McpSessions.reap().closed)
        assertEquals(true, McpSessions.isKnown("sid-a"))
    }

    @Test fun activeLongLivedSessionIsNeverForceClosed() = runBlocking {
        useClock()
        // 交互式客户端的长任务会话：持续有工具调用即使跨越数小时也不得被强杀
        val server = server()
        attach(server, "sid-keep")
        McpSessions.register(server)
        repeat(40) {
            now += 5 * 60_000L
            McpSessions.touchToolCall(server)
            assertEquals("第 $it 轮 5 分钟间隔不应触发回收", 0, McpSessions.reap().closed)
        }
        assertEquals(1, McpSessions.trackedSessions())
        assertEquals(true, McpSessions.isKnown("sid-keep"))
    }

    @Test fun closeFailureDoesNotStopOtherSessions() = runBlocking {
        useClock()
        McpSessions.reapIdleMs = 1000
        val first = server("first")
        val second = server("second")
        attach(first, "sid-a")
        attach(second, "sid-b")
        McpSessions.register(first)
        McpSessions.register(second)
        McpSessions.touchToolCall(first)
        McpSessions.touchToolCall(second)
        McpSessions.closeServer = { server ->
            if (server === first) throw IllegalStateException("boom") else closeCalls.add(server)
        }
        now = 1001
        val scan = McpSessions.reap()
        assertEquals(2, scan.closed)
        assertEquals(listOf(second), closeCalls)
        assertEquals(false, McpSessions.isKnown("sid-a"))
        assertEquals(false, McpSessions.isKnown("sid-b"))
    }

    @Test fun idleSessionsAndScanCountersTrackLiveness() = runBlocking {
        useClock()
        McpSessions.reapIdleMs = 1000
        val server = server()
        attach(server, "sid-a")
        McpSessions.register(server)
        McpSessions.touchToolCall(server)
        assertEquals(0, McpSessions.idleSessions())
        now = 1200
        assertEquals(1, McpSessions.idleSessions())
        now = 1500
        McpSessions.reap()
        assertEquals(1, McpSessions.scanCount())
        assertEquals(1500L, McpSessions.lastScan())
    }

    @Test fun unknownSessionIsNotKnown() {
        useClock()
        assertEquals(false, McpSessions.isKnown("sid-missing"))
        val server = server()
        McpSessions.register(server)
        assertEquals(false, McpSessions.isKnown("sid-missing"))
    }

    @Test fun serverCloseDropsReleasedSessions() {
        useClock()
        val server = server()
        attach(server, "sid-a")
        McpSessions.register(server)
        McpSessions.touchToolCall(server)
        attach(server)
        McpSessions.onServerClosed(server)
        assertEquals(0, McpSessions.trackedSessions())
        assertEquals(0, McpSessions.tracked())
    }

    @Test fun serverCloseKeepsStillHeldSessions() {
        useClock()
        val server = server()
        attach(server, "sid-a")
        McpSessions.register(server)
        McpSessions.touchToolCall(server)
        McpSessions.onServerClosed(server)
        assertEquals(1, McpSessions.trackedSessions())
        assertEquals(1, McpSessions.tracked())
    }

    @Test fun releaseOrphanRemovesEntryAndCloses() = runBlocking {
        useClock()
        val server = server()
        McpSessions.register(server)
        McpSessions.releaseOrphan(server)
        // orphan 不算「回收的会话」，不进 closedIds/reaped 统计
        assertEquals(false, McpSessions.isReaped("sid-a"))
        assertEquals(0, McpSessions.reapedTotal())
        assertEquals(0, McpSessions.tracked())
        assertEquals(listOf(server), closeCalls)
    }

    /**
     * 请求被取消（client abort / 协程取消）后 finally 里的清理仍应完成：releaseOrphan 由
     * McpApplication 包在 NonCancellable 中调用，close 必须在已取消的父作业下仍执行。
     * close 只走 withTimeoutOrNull 的协作预算，不承诺硬限时。
     */
    @Test fun releaseOrphanUnderCancelledContextStillCloses() = runBlocking {
        useClock()
        val server = server()
        McpSessions.register(server)
        // closeServer 在进入后挂起，等到父作业已取消才放行——保证 finally 的 NonCancellable
        // 清理是在「请求已被取消」的协程上下文里真正执行，而非取消前抢跑完成。
        val entered = kotlinx.coroutines.Job()
        val release = kotlinx.coroutines.Job()
        McpSessions.closeServer = { srv ->
            closeCalls.add(srv)
            entered.complete()
            release.join() // 等待测试放行，NonCancellable 保持关闭过程存活
        }
        val job = launch {
            withContext(NonCancellable) { McpSessions.releaseOrphan(server) }
        }
        entered.join()          // 确认清理已进入 close
        job.cancel()            // 取消父作业（模拟请求被 abort）
        release.complete()      // 放行 closeServer
        job.join()
        assertEquals(0, McpSessions.tracked())
        assertEquals("已取消上下文中 close 仍应执行", listOf(server), closeCalls)
    }
}