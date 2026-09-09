package com.mina.legadostudio.domain

import com.mina.legadostudio.mcp.McpConfigStore
import com.mina.legadostudio.mcp.McpSessions
import com.mina.legadostudio.mcp.McpStats
import com.mina.legadostudio.mcp.configureStudioMcp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端到端回收链路（真实 CIO 引擎 + 真实 HTTP 客户端 + 假时钟）。
 *
 * 关键语义：活跃度只由真实工具调用（touchToolCall）刷新；
 * 保活/SSE 等传输层请求（这里用 tools/list POST 模拟）不得给会话续命。
 */
class McpSessionReaperIntegrationTest {
    private var fakeNow = 1_000_000L
    private var factoryCalls = 0
    private val servers = mutableListOf<Server>()
    private val closed = mutableListOf<Server>()

    private val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
    private val listTools = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""

    @After fun cleanup() {
        McpSessions.clear()
        McpSessions.clock = { System.currentTimeMillis() }
        McpSessions.reapIdleMs = 5 * 60_000L
        McpSessions.maxLifetimeMs = 2 * 60 * 60_000L
        McpSessions.closeTimeoutMs = 3_000L
        McpSessions.closeServer = { it.close() }
        McpSessions.sessionIdsOf = { server ->
            val wire = runCatching {
                server.sessions.values.mapNotNull { session ->
                    (session.transport as? io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport)?.sessionId
                }
            }.getOrDefault(emptyList())
            if (wire.isNotEmpty()) wire else runCatching { server.sessions.keys.toList() }.getOrDefault(emptyList())
        }
        McpStats.resetConnections()
        McpStats.clock = { System.currentTimeMillis() }
        McpStats.idleWindowMs = 60_000L
    }

    private fun server() = Server(
        serverInfo = Implementation("reap-it", "1"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )

    private class Harness(val port: Int, val client: HttpClient, val engine: io.ktor.server.engine.EmbeddedServer<*, *>)

    private suspend fun start(): Harness {
        McpSessions.clear()
        McpStats.resetConnections()
        McpSessions.clock = { fakeNow }
        McpStats.clock = { fakeNow }
        McpSessions.closeServer = { server -> closed.add(server); server.close() }
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            configureStudioMcp(
                configProvider = { McpConfigStore.Config(1237, true, "secret") },
                allowedHosts = listOf("127.0.0.1", "localhost"),
                allowedOrigins = listOf("http://127.0.0.1", "http://localhost"),
            ) {
                server().also { factoryCalls++; servers.add(it); McpSessions.register(it) }
            }
        }
        engine.start(wait = false)
        val port = engine.engine.resolvedConnectors().first().port
        return Harness(port, HttpClient(ClientCIO), engine)
    }

    private suspend fun Harness.initialize(): io.ktor.client.statement.HttpResponse = client.post("http://127.0.0.1:$port/mcp") {
        header(HttpHeaders.Host, "127.0.0.1:$port")
        header(HttpHeaders.ContentType, ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header("X-Studio-Token", "secret")
        setBody(initialize)
    }

    private suspend fun Harness.keepAlive(sessionId: String): io.ktor.client.statement.HttpResponse =
        client.post("http://127.0.0.1:$port/mcp") {
            header(HttpHeaders.Host, "127.0.0.1:$port")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header("X-Studio-Token", "secret")
            header("Mcp-Session-Id", sessionId)
            setBody(listTools)
        }

    @Test fun keepAliveTrafficDoesNotPreventIdleReap() = runBlocking {
        val h = start()
        try {
            val init = h.initialize()
            val wireId = init.headers["Mcp-Session-Id"]!!
            assertTrue("门禁应认得 wire 会话 id", McpSessions.isKnown(wireId))
            McpSessions.touchToolCall(servers.last())
            assertEquals(1, McpSessions.trackedSessions())

            repeat(4) {
                fakeNow += 60_000L
                val ping = h.keepAlive(wireId)
                assertEquals(HttpStatusCode.OK, ping.status)
                assertEquals("保活请求不得续命", 0, McpSessions.reap().closed)
            }
            assertEquals(1, McpSessions.trackedSessions())

            fakeNow += 60_000L
            val scan = McpSessions.reap()
            assertEquals("最后一次工具调用满 5 分钟即回收", 1, scan.closed)
            assertEquals(1, closed.size)
            assertEquals(0, McpSessions.trackedSessions())
            assertEquals(1L, McpStats.snapshot()["sessionReaped"])

            val stale = h.keepAlive(wireId)
            assertEquals(HttpStatusCode.NotFound, stale.status)
            assertTrue(stale.bodyAsText().contains("MCP session expired"))

            val reinit = h.initialize()
            assertEquals(HttpStatusCode.OK, reinit.status)
            val newId = reinit.headers["Mcp-Session-Id"]!!
            assertTrue(newId != wireId)
            McpSessions.touchToolCall(servers.last())
            assertEquals(HttpStatusCode.OK, h.keepAlive(newId).status)
        } finally {
            h.client.close()
            h.engine.stop(100, 500)
        }
    }

    @Test fun toolCallsKeepSessionAlive() = runBlocking {
        val h = start()
        try {
            val init = h.initialize()
            val wireId = init.headers["Mcp-Session-Id"]!!
            repeat(7) {
                fakeNow += 4 * 60_000L
                McpSessions.touchToolCall(servers.last())
                assertEquals(HttpStatusCode.OK, h.keepAlive(wireId).status)
                assertEquals(0, McpSessions.reap().closed)
            }
            assertEquals(1, McpSessions.trackedSessions())
            assertEquals(1, factoryCalls)
        } finally {
            h.client.close()
            h.engine.stop(100, 500)
        }
    }

    @Test fun hardCapClosesLongRunningSession() = runBlocking {
        val h = start()
        try {
            McpSessions.reapIdleMs = 10 * 60_000L
            McpSessions.maxLifetimeMs = 1000L
            val init = h.initialize()
            val wireId = init.headers["Mcp-Session-Id"]!!
            McpSessions.touchToolCall(servers.last())
            fakeNow += 900L
            McpSessions.touchToolCall(servers.last())
            assertEquals(0, McpSessions.reap().closed)
            fakeNow += 100L
            assertEquals(1, McpSessions.reap().closed)
            assertEquals(HttpStatusCode.NotFound, h.keepAlive(wireId).status)
        } finally {
            h.client.close()
            h.engine.stop(100, 500)
        }
    }
}