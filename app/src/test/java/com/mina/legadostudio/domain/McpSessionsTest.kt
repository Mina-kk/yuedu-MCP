package com.mina.legadostudio.domain

import com.mina.legadostudio.mcp.McpSessions
import com.mina.legadostudio.mcp.McpStats
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ---- DELETE 释放 / 门闩 / 竞态 ----

    /**
     * 多 wire fixture：给同一 Server 挂上携带指定 wire sessionId 的 transport 会话。
     *
     * strictWireIdsOf 只认「ServerSession.transport 是 StreamableHttpServerTransport 且
     * sessionId 非空」；transport 的 sessionId 只在 initialize 时由 sessionIdGenerator 生成，
     * 且该字段私有、无 setter——故用反射直接写 sessionId 后走公开 `Server.createSession`。
     * createSession 只建会话+connect（不调 transport.start()），全程不碰网络，可在单测用。
     */
    private fun attachWire(server: Server, wireId: String) {
        val transport = StreamableHttpServerTransport()
        val field = transport.javaClass.getDeclaredField("sessionId")
        field.isAccessible = true
        field.set(transport, wireId)
        runBlocking { server.createSession(transport) }
        ids.computeIfAbsent(server) { mutableListOf() }.add(wireId)
    }

    /** registeredOwnerOf 按真实 wire id 命中已登记 owner。 */
    @Test fun registeredOwnerOfMatchesLiveWireSession() {
        useClock()
        val server = server()
        attachWire(server, "wire-a")
        McpSessions.register(server)
        assertEquals(server, McpSessions.registeredOwnerOf("wire-a"))
        assertEquals(null, McpSessions.registeredOwnerOf("wire-other"))
    }

    /** strictWireIdsOf 读不到 wire（无 transport 的裸 fixture）时 registeredOwnerOf 保守返回 null。 */
    @Test fun registeredOwnerOfReturnsNullWhenNoWireTransport() {
        useClock()
        val server = server()
        attach(server, "sid-a")   // 只喂 sessionIdsOf 缝，无真实 transport wire
        McpSessions.register(server)
        assertEquals(null, McpSessions.registeredOwnerOf("sid-a"))
    }

    @Test fun releaseOrphanOnRegisteredClaimsLatchOnce() = runBlocking {
        useClock()
        val server = server()
        McpSessions.register(server)
        McpSessions.releaseOrphan(server)
        assertEquals(0, McpSessions.tracked())
        assertEquals(listOf(server), closeCalls)
    }

    /** 多 wire owner 删除其中一个会话后仍持其它 wire：不得摘除/关闭。 */
    @Test fun releaseDeletedKeepsOwnerWithRemainingWireSessions() = runBlocking {
        useClock()
        val server = server()
        attachWire(server, "wire-a")
        attachWire(server, "wire-b")
        McpSessions.register(server)
        // 会话级终止（=SDK DELETE 后 onSessionClosed 摘除语义）：移除 wire-a 对应的
        // ServerSession，模拟 SDK 在回 200 前已把它从注册表摘掉的状态。
        val sessionA = server.sessions.values.single {
            (it.transport as StreamableHttpServerTransport).sessionId == "wire-a"
        }
        sessionA.close()
        assertEquals(listOf("wire-b"), McpSessions.strictWireIdsOf(server)!!.sorted())
        assertEquals(false, McpSessions.releaseDeleted("wire-a", server))
        assertTrue("多 wire owner 不得被摘除", McpSessions.isTrackedForTest(server))
        assertEquals("多 wire owner 不得被关闭", 0, closeCalls.count { it === server })
        // owner 仍持有 wire-b，registeredOwnerOf 仍能找到它
        assertEquals(server, McpSessions.registeredOwnerOf("wire-b"))
    }

    /** 最后一个 wire 删除后（wire 为空）才摘除+关闭 owner。 */
    @Test fun releaseDeletedClosesOwnerWhenLastWireRemoved() = runBlocking {
        useClock()
        val server = server()
        attachWire(server, "wire-a")
        attachWire(server, "wire-b")
        McpSessions.register(server)
        // 摘除 wire-b 会话 → wire-a 还在，删 wire-b 不得关 owner
        server.sessions.values.single {
            (it.transport as StreamableHttpServerTransport).sessionId == "wire-b"
        }.close()
        assertEquals(listOf("wire-a"), McpSessions.strictWireIdsOf(server)!!.toList())
        assertEquals(false, McpSessions.releaseDeleted("wire-b", server))
        assertTrue(McpSessions.isTrackedForTest(server))
        // 再摘除 wire-a（最后一个 wire）→ 摘除+close
        server.sessions.values.single {
            (it.transport as StreamableHttpServerTransport).sessionId == "wire-a"
        }.close()
        assertEquals(true, McpSessions.releaseDeleted("wire-a", server))
        assertTrue(!McpSessions.isTrackedForTest(server))
        assertEquals(listOf(server), closeCalls)
    }

    /**
     * 受控次序一：DELETE 先摘除 owner（sessions/servers 条目已移），reaper 随后扫到同一
     * 闲置会话 → 原子 remove 失败或 owner 已不在，不写 closedIds、不 close、不计 closed。
     */
    @Test fun deleteWinsThenReaperSkipsEntry() = runBlocking {
        useClock()
        McpSessions.reapIdleMs = 1000
        val server = server()
        attach(server, "sid-del")
        McpSessions.register(server)
        McpSessions.touchToolCall(server)
        now = 1001
        // 模拟 DELETE 已摘除 owner 且摘走其 sessions 条目（releaseDeleted 语义）：
        // servers 已空 → reaper 即使扫到残留条目也在 servers.remove 处拿到 null → 跳过。
        McpSessions.releaseOrphan(server)   // 用 orphan 完成「已摘除」状态（=DELETE 摘除语义）
        assertEquals(1, closeCalls.size)
        val scan = McpSessions.reap()
        assertEquals("DELETE/orphan 已摘除的 owner 不被 reaper 计数", 0, scan.closed)
        assertEquals("sid 不得被标 reaped", false, McpSessions.isReaped("sid-del"))
        assertEquals("不二次 close", 1, closeCalls.size)
    }

    /**
     * 受控次序二：reaper 先摘 sessions 条目+写 closedIds+claim close，DELETE 随后到达
     * 只能看到「owner 已不在」→ 不 close（返回 false）。两个次序之间没有第三态。
     */
    @Test fun reaperWinsThenDeleteDoesNotClose() = runBlocking {
        useClock()
        McpSessions.reapIdleMs = 1000
        val server = server()
        attach(server, "sid-reap")
        McpSessions.register(server)
        McpSessions.touchToolCall(server)
        now = 1001
        assertEquals(1, McpSessions.reap().closed)
        assertEquals(true, McpSessions.isReaped("sid-reap"))
        assertEquals(listOf(server), closeCalls)
        // reaper 先赢：owner 已摘除，DELETE 释放返回 false、不再 close
        assertEquals(false, McpSessions.releaseDeleted("sid-reap", server))
        assertEquals("owner 已归 reaper 关闭，DELETE 不再动", 1, closeCalls.size)
    }

    /**
     * 并发 orphan + reaper 争同一已登记实例：谁先 claim 到 ServerEntry 谁 close，
     * 恰好一次（不许出现 2 次 close，也不许 0 次）。
     */
    @Test fun orphanAndReaperRaceClosesOwnerExactlyOnce() = runBlocking<Unit> {
        useClock()
        McpSessions.reapIdleMs = 1000
        val server = server()
        attach(server, "sid-x")
        McpSessions.register(server)
        McpSessions.touchToolCall(server)
        now = 1001
        // 并发：reaper（经 sessions 原子摘除+owner 门闩）与 orphan 清理争同一个 owner。
        coroutineScope {
            repeat(4) { launch { McpSessions.releaseOrphan(server) } }
            launch { McpSessions.reap() }
        }
        assertEquals(0, McpSessions.tracked())
        // 恰好一次：orphan 之间经 servers.remove 先到先得（拿 entry 才能 claim），
        // reaper 与 orphan 经同一 entry 门闩——只有一条路径能真正 close。
        assertEquals("同一实例在并发释放下恰好 close 一次", 1, closeCalls.count { it === server })
    }

    /** 门闩硬保证：owner 被 orphan claim 摘除后，reaper 扫到残留 sessions 条目也不再 close。 */
    @Test fun claimedLatchBlocksReaperClose() = runBlocking {
        useClock()
        McpSessions.reapIdleMs = 1000
        val server = server()
        attach(server, "sid-claimed")
        McpSessions.register(server)
        McpSessions.touchToolCall(server)
        now = 1001
        McpSessions.releaseOrphan(server)
        assertEquals(1, closeCalls.size)
        assertEquals(0, McpSessions.reap().closed)
        assertEquals("claim 过的实例不被 reaper 二次 close", 1, closeCalls.size)
    }

    @Test fun duplicateRegistrationKeepsOneTrackedOwner() = runBlocking {
        useClock()
        val server = server()
        McpSessions.register(server)
        McpSessions.register(server)
        assertEquals(1, McpSessions.tracked())
        McpSessions.releaseOrphan(server)
        assertEquals(0, McpSessions.tracked())
        assertEquals(listOf(server), closeCalls)
    }

    @Test fun releaseDeletedDoesNotRemoveStillActiveSession() = runBlocking {
        useClock()
        val server = server()
        attachWire(server, "wire-active")
        attach(server, "wire-active")
        McpSessions.register(server)
        McpSessions.touchToolCall(server)
        try {
            assertEquals(1, McpSessions.trackedSessions())
            assertEquals(false, McpSessions.releaseDeleted("wire-active", server))
            assertTrue(McpSessions.isTrackedForTest(server))
            assertEquals(1, McpSessions.trackedSessions())
            assertTrue(McpSessions.isKnown("wire-active"))
            assertTrue(closeCalls.isEmpty())
        } finally {
            server.close()
        }
    }

    /** expected 未登记（stray）时 releaseDeleted 返回 false 且不动 tracker。 */
    @Test fun releaseDeletedOnUnregisteredServerDoesNothing() = runBlocking {
        useClock()
        val stray = server()
        assertEquals(false, McpSessions.releaseDeleted("sid-x", stray))
        assertEquals(0, closeCalls.size)
        assertEquals(0, McpSessions.tracked())
    }
}