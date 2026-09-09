package com.mina.legadostudio.domain

import com.mina.legadostudio.mcp.McpStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class McpStatsTest {
    private var now = 0L

    @After fun restore() {
        McpStats.clock = { System.currentTimeMillis() }
        McpStats.idleWindowMs = 5 * 60_000L
        McpStats.resetConnections()
    }

    private fun useClock(windowMs: Long = 5 * 60_000L) {
        McpStats.resetConnections()
        McpStats.clock = { now }
        McpStats.idleWindowMs = windowMs
    }

    @Test fun countsDistinctSessionsNotRequests() {
        useClock()
        repeat(20) { McpStats.touch("session-a") }
        assertEquals(1L, McpStats.snapshot()["clientCount"])
        McpStats.touch("session-b")
        assertEquals(2L, McpStats.snapshot()["clientCount"])
        assertEquals(0L, McpStats.snapshot()["sessionTotal"])
    }

    @Test fun idleSessionsLeaveActiveCount() {
        useClock(windowMs = 1000)
        McpStats.touch("session-a")
        McpStats.touch("session-b")
        now = 999
        assertEquals(2L, McpStats.snapshot()["clientCount"])
        now = 1001
        assertEquals(0L, McpStats.snapshot()["clientCount"])
    }

    @Test fun requestsWithoutSessionIdAreNotClients() {
        useClock()
        now = 1234
        McpStats.touch(null)
        McpStats.touch("")
        McpStats.onRequest()
        assertEquals(0L, McpStats.snapshot()["clientCount"])
        assertEquals(1234L, McpStats.snapshot()["lastAccessAt"])
    }

    @Test fun closeWithoutSessionIdKeepsActiveCountAndTracksTotals() {
        useClock()
        McpStats.connected()
        McpStats.touch("session-a")
        McpStats.disconnected()
        assertEquals(1L, McpStats.snapshot()["clientCount"])
        assertEquals(1L, McpStats.snapshot()["sessionTotal"])
        assertEquals(1L, McpStats.snapshot()["sessionClosed"])
    }

    @Test fun sixtySecondWindowDropsIdleSession() {
        useClock(windowMs = 60_000)
        McpStats.touch("session-a")
        now = 59_999
        assertEquals(1L, McpStats.snapshot()["clientCount"])
        now = 60_000
        assertEquals(0L, McpStats.snapshot()["clientCount"])
    }

    @Test fun forgetRemovesSessionFromActiveCount() {
        useClock()
        McpStats.touch("session-a")
        assertEquals(1L, McpStats.snapshot()["clientCount"])
        McpStats.forget(listOf("session-a"))
        assertEquals(0L, McpStats.snapshot()["clientCount"])
        assertEquals(null, McpStats.lastSeen("session-a"))
    }

    @Test fun resetClearsSessionsTotalsAndLastAccess() {
        useClock()
        McpStats.connected()
        McpStats.touch("session-a")
        McpStats.resetConnections()
        assertEquals(0L, McpStats.snapshot()["clientCount"])
        assertEquals(0L, McpStats.snapshot()["sessionTotal"])
        assertEquals(0L, McpStats.snapshot()["lastAccessAt"])
    }
}
