package com.mina.legadostudio.domain

import com.mina.legadostudio.mcp.McpConfigStore
import com.mina.legadostudio.mcp.McpSessions
import com.mina.legadostudio.mcp.configureStudioMcp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败请求实例留存回归（真实 CIO 引擎 + 真实 HTTP 客户端 + 生产 register/onClose 登记缝）。
 *
 * 背景：SDK 0.15.0 对无 `Mcp-Session-Id` 的 POST 一律先调用 serverFactory 创建 Server 并建立
 * 内部 ServerSession，再做 Accept/Content-Type/正文/方法校验。生产工厂在 create() 内立刻
 * `McpSessions.register`（且挂 onClose 注销），校验失败的实例拿不到 wire session id，
 * 旧代码会把这些无 wire 实例一直留在 tracker 里只能等 reaper 兜底，失败突发时形成临时留存。
 *
 * 修复后：请求结束时，本请求新创建且确认无 wire 会话、响应也未签发 `Mcp-Session-Id` 的实例
 * 由 McpApplication 的 finally 摘除登记并 best-effort 关闭；已交接会话（有 wire id 或响应
 * 已带会话头）不误关，避免客户端拿到会话后立即 DELETE 时被二次关闭。
 *
 * 本套件不清空全局 tracker、不动其它用例/进程登记的外来 Server：只断言本用例 fixture 实例
 * 的登记与 close 次数，并在 finally 仅关闭本用例创建的实例、恢复覆盖过的全局缝。
 * 注意：DELETE 是另一条生命周期——SDK 只关 transport/session、不调 server.close()，对从未
 * 被工具调用 touch 的已交接实例，reaper 扫不到其 tracker 条目，是否回收属未覆盖边界。
 */
class McpRequestOrphanIntegrationTest {

    private data class Wire(val status: Int, val headers: Headers, val body: String) {
        /** 解析 JSON 或 SSE 帧中匹配 id 的 JSON-RPC envelope；无 id（通知/传输级错误）传 null。 */
        fun envelope(id: JsonPrimitive?): JsonObject {
            val trimmed = body.trim()
            val payloads = if (trimmed.startsWith("{")) listOf(trimmed) else {
                val events = mutableListOf<String>()
                val data = mutableListOf<String>()
                for (line in (body.replace("\r\n", "\n") + "\n\n").lineSequence()) {
                    if (line.isEmpty()) {
                        if (data.isNotEmpty()) events.add(data.joinToString("\n"))
                        data.clear()
                    } else if (line.startsWith("data:")) {
                        data.add(line.removePrefix("data:").removePrefix(" "))
                    }
                }
                events
            }
            val matches = payloads.map { Json.parseToJsonElement(it).jsonObject }
                .filter { it["id"] == id && (it.containsKey("result") || it.containsKey("error")) }
            assertEquals("HTTP $status, expected envelope id=$id: $body", 1, matches.size)
            return matches.single()
        }

        fun errorCode(id: JsonPrimitive? = null): Int = envelope(id).getValue("error")
            .jsonObject.getValue("code").jsonPrimitive.int

        fun result(id: JsonPrimitive): JsonObject {
            assertEquals("$body", 200, status)
            return envelope(id).getValue("result").jsonObject
        }
    }

    /** 默认关闭缝：委托真实 Server.close()，同时记录调用（断言 closed exactly once）。 */
    private val closeCalls = CopyOnWriteArrayList<Server>()

    private fun fixture(): Server = Server(
        serverInfo = Implementation("orphan-fixture", "1"),
        options = ServerOptions(capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false),
        )),
    ).also { server ->
        server.addTool(
            Tool(
                name = "ping", description = "fixture ping",
                inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
                annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
            ),
        ) { CallToolResult(listOf(TextContent("pong"))) }
    }

    private class Harness(
        val port: Int,
        val client: HttpClient,
        val servers: CopyOnWriteArrayList<Server>,
        val baselineSessions: Int,
    ) {
        suspend fun post(
            body: String,
            session: String? = null,
            version: String? = "2025-11-25",
            authorization: String? = "Bearer fixture-token",
            host: String? = null,
            accept: String = "application/json, text/event-stream",
            contentType: String = "application/json",
        ): Wire {
            val response = client.post("http://127.0.0.1:$port/mcp") {
                header(HttpHeaders.Host, host ?: "127.0.0.1:$port")
                header(HttpHeaders.Accept, accept)
                header(HttpHeaders.ContentType, contentType)
                if (authorization != null) header(HttpHeaders.Authorization, authorization)
                if (session != null) header("Mcp-Session-Id", session)
                if (version != null) header("MCP-Protocol-Version", version)
                setBody(body)
            }
            return Wire(response.status.value, response.headers, response.bodyAsText())
        }

        suspend fun delete(session: String): Wire {
            val response = client.delete("http://127.0.0.1:$port/mcp") {
                header(HttpHeaders.Host, "127.0.0.1:$port")
                header(HttpHeaders.Authorization, "Bearer fixture-token")
                header("Mcp-Session-Id", session)
            }
            return Wire(response.status.value, response.headers, response.bodyAsText())
        }

        suspend fun initialize(): String {
            val response = post(INITIALIZE, version = null)
            assertEquals("$response", 200, response.status)
            val session = response.headers["Mcp-Session-Id"]
            assertNotNull("initialize 响应必须签发会话头", session)
            val initialized = post(INITIALIZED, session, "2025-11-25")
            assertEquals(202, initialized.status)
            return session!!
        }
    }

    /**
     * 运行 harness：覆盖的全局缝先存原值再还原；不调用 `McpSessions.clear()`、不做快照恢复，
     * 外来 tracker 状态原样保留。记录 baseline 计数，断言只针对本用例 fixture 实例。
     * 兜底只关闭本用例 servers 列表里的实例，不碰外来 Server。
     */
    private fun httpTest(block: suspend Harness.() -> Unit) = runBlocking {
        val oldClock = McpSessions.clock
        val oldReapIdleMs = McpSessions.reapIdleMs
        val oldCloseTimeoutMs = McpSessions.closeTimeoutMs
        val oldCloseServer = McpSessions.closeServer
        val oldSessionIdsOf = McpSessions.sessionIdsOf
        closeCalls.clear()
        McpSessions.closeServer = { server -> closeCalls.add(server); server.close() }
        val servers = CopyOnWriteArrayList<Server>()
        val factoryCalls = AtomicInteger()
        val client = HttpClient(ClientCIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
        }
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            configureStudioMcp(
                configProvider = { McpConfigStore.Config(1237, true, "fixture-token") },
                allowedHosts = listOf("127.0.0.1", "localhost"),
                allowedOrigins = listOf("http://127.0.0.1", "http://localhost"),
            ) {
                // 与生产 StudioMcpServer.create() 相同的登记缝：register + onClose 注销
                fixture().also {
                    factoryCalls.incrementAndGet()
                    servers.add(it)
                    McpSessions.register(it)
                    it.onClose { McpSessions.onServerClosed(it) }
                }
            }
        }
        try {
            withTimeout(45_000) {
                engine.start(wait = false)
                val port = engine.engine.resolvedConnectors().single().port
                Harness(
                    port, client, servers,
                    baselineSessions = McpSessions.trackedSessions(),
                ).block()
            }
        } finally {
            client.close()
            try {
                // 兜底只关本用例创建的实例，不碰外来 Server。
                withContext(NonCancellable) { withTimeout(5_000) { servers.forEach { it.close() } } }
            } finally {
                engine.stop(100, 500)
                McpSessions.clock = oldClock
                McpSessions.reapIdleMs = oldReapIdleMs
                McpSessions.closeTimeoutMs = oldCloseTimeoutMs
                McpSessions.closeServer = oldCloseServer
                McpSessions.sessionIdsOf = oldSessionIdsOf
            }
        }
    }

    /** 有限轮询等待异步 close（onClose→摘除登记）传播完成；超时即失败，不做无限等待。 */
    private suspend fun awaitCondition(describe: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) throw AssertionError("awaitCondition 超时：$describe")
            delay(10)
        }
    }

    /** 本用例实例中仍被 tracker 登记的数量。 */
    private fun trackedOrphans(servers: List<Server>) = servers.count { McpSessions.isTrackedForTest(it) }

    @Test(timeout = 60_000) fun orphanServersReleasedAfterFailedSessionlessPosts() = httpTest {
        val missing = post(rpc(2, "tools/list"), session = null)
        assertEquals(400, missing.status)
        assertEquals(-32000, missing.errorCode(JsonPrimitive(2)))
        val badAccept = post(INITIALIZE, accept = "text/plain")
        assertEquals(406, badAccept.status)
        assertEquals(-32000, badAccept.errorCode())
        val badContentType = post(INITIALIZE, contentType = "text/plain")
        assertEquals(415, badContentType.status)
        assertEquals(-32000, badContentType.errorCode())
        val malformed = post("{", session = null)
        assertEquals(400, malformed.status)
        assertEquals(-32700, malformed.errorCode())

        assertEquals("每个无会话失败请求各创建一次 Server", 4, servers.size)
        assertEquals(
            "无 wire 会话的失败实例应从 tracker 摘除（留存即复现 bug）",
            0, trackedOrphans(servers),
        )
        assertEquals("tracker 不得新增本会话登记", baselineSessions, McpSessions.trackedSessions())
        assertEquals("每个 orphan 仅关闭一次", servers.size, closeCalls.size)
        assertEquals(closeCalls.toSet().size, closeCalls.size)
    }

    @Test(timeout = 60_000) fun concurrentOrphansAreReleasedIndividually() = httpTest {
        val wires = coroutineScope {
            (1..8).map { i ->
                async(Dispatchers.IO) {
                    if (i % 2 == 0) post(rpc(i, "tools/list"), session = null)
                    else post(INITIALIZE, accept = "text/plain")
                }
            }.awaitAll()
        }
        // 逐个断言状态与错误码：偶数缺会话 400/-32000（id 保留），奇数坏 Accept 406/-32000。
        wires.forEachIndexed { idx, wire ->
            val i = idx + 1
            if (i % 2 == 0) {
                assertEquals("req#$i $wire", 400, wire.status)
                assertEquals("req#$i $wire", -32000, wire.errorCode(JsonPrimitive(i)))
            } else {
                assertEquals("req#$i $wire", 406, wire.status)
                assertEquals("req#$i $wire", -32000, wire.errorCode())
            }
        }
        assertEquals("8 个并发失败各创建独立实例", 8, servers.size)
        assertEquals(0, trackedOrphans(servers))
        awaitCondition(describe = "并发 orphan 全部关闭") { closeCalls.containsAll(servers) }
        assertEquals("每个 orphan 仅关闭一次", 8, closeCalls.size)
        assertEquals(closeCalls.toSet().size, closeCalls.size)
    }

    @Test(timeout = 60_000) fun orphanCleanupCoexistsWithLiveSession() = httpTest {
        val session = initialize()
        val live = servers.last()
        // 与正常会话并存的失败请求：只关新实例，不误关活会话
        assertEquals(400, post(rpc(2, "tools/list"), session = null).status)
        assertEquals(406, post(INITIALIZE, accept = "text/plain").status)
        assertEquals(3, servers.size)
        assertTrue("活会话不得被 orphan 清理误关", McpSessions.isTrackedForTest(live))
        assertEquals(0, trackedOrphans(servers - live))
        val tool = post(rpc(3, "tools/call", buildJsonObject {
            put("name", "ping"); put("arguments", JsonObject(emptyMap()))
        }), session)
        // 按 JSON-RPC envelope 解析 SSE/JSON 的 result.content[].text，不靠正文子串
        val text = tool.result(JsonPrimitive(3))
            .getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertEquals("pong", text)
    }

    @Test(timeout = 60_000) fun liveSessionSurvivesLaterTransportErrorsAndImmediateDelete() = httpTest {
        val session = initialize()
        val live = servers.last()
        // 正常会话成立后，后续非法 Accept / 非法版本头 / 未知会话请求不误关活实例
        assertEquals(406, post(rpc(2, "tools/list"), session, accept = "text/plain").status)
        assertEquals(400, post(rpc(3, "tools/list"), session, version = "not-a-version").status)
        assertEquals(404, post(rpc(4, "tools/list"), session = "unknown-fixture-session").status)
        assertTrue(McpSessions.isTrackedForTest(live))
        assertEquals("带会话请求不新建实例", 1, servers.size)
        // initialize 响应签发会话头后立即 DELETE（首个且唯一的后续请求）：wire 会话已交接，
        // finally 不得二次 close。SDK 的 DELETE 只关 transport/session，不调 server.close()，
        // 故 onClose 注销不触发——这是 SDK 既有生命周期，不属于本 orphan 清理范围。
        val delete = delete(session)
        assertEquals(200, delete.status)
        awaitCondition(describe = "DELETE 后 wire 会话终止 (wire=${McpSessions.strictWireIdsOf(live)})") {
            McpSessions.strictWireIdsOf(live)?.isEmpty() == true
        }
        assertEquals("orphan 清理不得关闭已交接实例", 0, closeCalls.count { it === live })
        assertEquals(0, trackedOrphans(servers - live))
    }

    @Test(timeout = 60_000) fun authHostAndUnknownSessionCreateNoFactoryInstances() = httpTest {
        assertEquals(401, post(INITIALIZE, authorization = null).status)
        assertEquals(401, post(INITIALIZE, authorization = "Bearer wrong").status)
        assertEquals(403, post(INITIALIZE, host = "hostile.example:$port").status)
        assertEquals(0, servers.size)
        val unknown = post(rpc(2, "tools/list"), session = "unknown-fixture-session")
        assertEquals(404, unknown.status)
        assertEquals("未知会话走 existing transport 路径，不创建实例", 0, servers.size)
        assertEquals("本用例不新增会话登记", baselineSessions, McpSessions.trackedSessions())
    }

    @Test(timeout = 60_000) fun orphanCloseFailureStillReleasesTrackerEntryAndKeepsResponse() = httpTest {
        val failing = AtomicInteger()
        McpSessions.closeServer = { server ->
            closeCalls.add(server)
            if (failing.getAndIncrement() == 0) throw IllegalStateException("close boom")
            server.close()
        }
        val first = post(rpc(2, "tools/list"), session = null)
        assertEquals("close 异常不得改变 HTTP 响应", 400, first.status)
        assertEquals("close 失败也必须摘除登记", 0, trackedOrphans(servers))
        val second = post(rpc(3, "tools/list"), session = null)
        assertEquals(400, second.status)
        assertEquals(0, trackedOrphans(servers))
        assertEquals(2, closeCalls.size)
    }

    companion object {
        private const val INITIALIZE = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"orphan-fixture","version":"1"}}}"""
        private const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

        private fun rpc(id: Int, method: String, params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
    }
}
