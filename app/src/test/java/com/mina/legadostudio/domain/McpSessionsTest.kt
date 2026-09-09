package com.mina.legadostudio.domain

import com.mina.legadostudio.mcp.McpSessions
import com.mina.legadostudio.mcp.McpStats
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
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
        McpSessions.reapIdleMs = 5 * 60_000L
        McpSessions.maxLifetimeMs = 2 * 60 * 60_000L
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

    @Test fun hardCapClosesKeepAliveSession() = runBlocking {
        useClock()
        McpSessions.reapIdleMs = 10 * 60_000L
        McpSessions.maxLifetimeMs = 1000
        val server = server()
        attach(server, "sid-keep")
        McpSessions.register(server)
        McpSessions.touchToolCall(server)
        now = 900
        McpSessions.touchToolCall(server)
        assertEquals(0, McpSessions.reap().closed)
        now = 1000
        assertEquals(1, McpSessions.reap().closed)
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
}