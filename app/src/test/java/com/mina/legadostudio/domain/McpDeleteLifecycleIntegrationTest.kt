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
 * DELETE 会话终止生命周期（真实 CIO 引擎 + 真实 HTTP 客户端 + 生产 register/onClose 登记缝）。
 *
 * 背景：SDK 0.15.0 命中会话的 DELETE 只移除 transport 分发表、关闭 transport/内部 session、
 * 回复 200，**不会**调用 Server.close()。工厂里 `McpSessions.register` 的 ServerEntry 因此残留：
 * 未被工具调用 touch 过的实例 reaper 扫不到（reaper 只遍历 sessions），形成永久留存。
 *
 * 修复后：`/mcp` 的鉴权通过 DELETE 在执行 SDK 前按请求 wire sid 捕获已登记 owner；请求 finally
 * 仅在「SDK 返回 200 且 owner 确认不再持有任何 wire 会话」时摘除登记并 best-effort 关闭一次。
 * 无 sid 的 DELETE 仍走 orphan 清理路径；401/未知 sid/非法协议头不得误关已登记实例。
 *
 * 本套件不清空全局 tracker、不碰其它用例/进程登记的外来 Server：只断言本用例 fixture 实例，
 * finally 仅关闭本用例创建的实例并恢复覆盖过的全局缝。
 */
class McpDeleteLifecycleIntegrationTest {

    private data class Wire(val status: Int, val headers: Headers, val body: String) {
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
        serverInfo = Implementation("delete-fixture", "1"),
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

    private inner class Harness(
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

        suspend fun delete(
            session: String?,
            authorization: String? = "Bearer fixture-token",
            version: String? = null,
        ): Wire {
            val response = client.delete("http://127.0.0.1:$port/mcp") {
                header(HttpHeaders.Host, "127.0.0.1:$port")
                if (authorization != null) header(HttpHeaders.Authorization, authorization)
                if (session != null) header("Mcp-Session-Id", session)
                if (version != null) header("MCP-Protocol-Version", version)
            }
            return Wire(response.status.value, response.headers, response.bodyAsText())
        }

        /** 只发 initialize（不发 initialized 通知）：isolate 「initialize 响应即 DELETE」场景。 */
        suspend fun initializeOnly(): String {
            val response = post(INITIALIZE, version = null)
            assertEquals("$response", 200, response.status)
            val session = response.headers["Mcp-Session-Id"]
            assertNotNull("initialize 响应必须签发会话头", session)
            return session!!
        }

        suspend fun initialize(): String {
            val session = initializeOnly()
            val initialized = post(INITIALIZED, session, "2025-11-25")
            assertEquals(202, initialized.status)
            return session
        }
    }

    /**
     * 运行 harness：覆盖的全局缝先存原值再还原；不调用 `McpSessions.clear()`、不做快照恢复，
     * 外来 tracker 状态原样保留。兜底只关闭本用例 servers 列表里的实例，不碰外来 Server。
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
            // harness 收尾断言：门闩协调下任何本用例实例的 close 至多一次（不双关）；
            // 存活/未删实例可以 0 次（不被误关），被终止/清理的实例恰好 1 次（各用例自行断言）。
            for (server in servers.toList()) {
                assertTrue(
                    "任何实例不得被重复 close（closeCalls=${closeCalls.size}）",
                    closeCalls.count { it === server } <= 1,
                )
            }
        } finally {
            client.close()
            try {
                // 兜底只关本用例创建的实例，不碰外来 Server；绕过 closeServer 缝以免污染计数。
                withContext(NonCancellable) {
                    withTimeout(5_000) {
                        servers.forEach { runCatching { it.close() } }
                    }
                }
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

    /**
     * 基线（红灯证据）：initialize 成功后第一个操作即 DELETE → SDK 回 200，
     * 但应用登记的 ServerEntry 未被摘除、close 未被调用。该断言锁定旧行为以便观察修复变绿。
     */
    @Test(timeout = 60_000) fun deleteAfterInitializeReleasesRegisteredOwner() = httpTest {
        val session = initialize()
        val owner = servers.last()
        assertTrue("initialize 后实例必须已登记", McpSessions.isTrackedForTest(owner))

        val delete = delete(session)
        assertEquals("SDK 对命中会话的 DELETE 返回 200", 200, delete.status)

        awaitCondition(describe = "DELETE 200 后 owner 应从 tracker 摘除") {
            !McpSessions.isTrackedForTest(owner)
        }
        // close 是 suspend 且经 onClose 传播，摘除先行——等 close 计数到位再断言恰好一次
        awaitCondition(describe = "owner close 完成 (closeCalls=${closeCalls.size})") {
            closeCalls.count { it === owner } >= 1
        }
        assertEquals("owner 应被关闭恰好一次", 1, closeCalls.count { it === owner })
        assertTrue("wire 会话已终止", McpSessions.strictWireIdsOf(owner)?.isEmpty() == true)
        // DELETE 掉的 sid 重试：SDK 返回 404 JSON -32000，不得被标成 reaper 过期（不进 closedIds）
        assertTrue("DELETE 终止的 sid 不得记入 closedIds", !McpSessions.isReaped(session))
        val retry = delete(session)
        assertEquals("已终止会话重试 DELETE 由 SDK 回 404，而非 reaped 语义", 404, retry.status)
        assertEquals(-32000, retry.errorCode())
        // 正常 DELETE 不算「回收的会话」：不写 closedIds、不计 reaped
        assertEquals("正常 DELETE 不产生 reaped 统计", baselineSessions, McpSessions.trackedSessions())
    }

    /** initialize 响应即 DELETE（不发 initialized/任何中间请求），覆盖真正「第一个操作」场景。 */
    @Test(timeout = 60_000) fun deleteAsVeryFirstOperationAfterInitialize() = httpTest {
        val session = initializeOnly()
        val owner = servers.last()
        assertEquals(1, servers.size)
        val delete = delete(session)
        assertEquals(200, delete.status)
        awaitCondition(describe = "initialize 即 DELETE 后 owner 摘除") {
            !McpSessions.isTrackedForTest(owner)
        }
        awaitCondition(describe = "owner close 传播完成") { closeCalls.count { it === owner } >= 1 }
        assertEquals(1, closeCalls.count { it === owner })
    }

    /** touch（工具调用）后的已登记会话 DELETE：owner 释放、session 条目摘除、统计正确。 */
    @Test(timeout = 60_000) fun deleteAfterToolCallReleasesOwnerAndSessionEntry() = httpTest {
        val session = initialize()
        val owner = servers.last()
        val tool = post(rpc(2, "tools/call", buildJsonObject {
            put("name", "ping"); put("arguments", JsonObject(emptyMap()))
        }), session)
        val text = tool.result(JsonPrimitive(2))
            .getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertEquals("pong", text)
        // fixture 的工具 handler 不经 StudioMcpServer.dispatchToolCall，不显式 touch 就不会进
        // sessions 表；这里显式 touch 模拟生产 dispatchToolCall 的 touchToolCall 登记。
        McpSessions.touchToolCall(owner)
        assertEquals("touchToolCall 应登记 session 条目", baselineSessions + 1, McpSessions.trackedSessions())

        // DELETE 释放：touch 过的 session 条目（sessions 表）与 owner（servers 表）都摘除，
        // owner 恰好关闭一次，不计入 closedIds/reaped（正常终止非回收）。
        assertEquals(200, delete(session).status)
        awaitCondition(describe = "touch 过的 owner DELETE 后摘除") { !McpSessions.isTrackedForTest(owner) }
        awaitCondition(describe = "owner close 传播完成") { closeCalls.count { it === owner } >= 1 }
        assertEquals(1, closeCalls.count { it === owner })
        assertEquals("session 条目同步摘除", baselineSessions, McpSessions.trackedSessions())
        assertTrue(!McpSessions.isReaped(session))
    }

    /** 并发 8 个同 sid DELETE：契约允许 200/404 混合（并发请求可能同时取到 transport），owner 只关一次。 */
    @Test(timeout = 60_000) fun concurrentDeletesCloseOwnerExactlyOnce() = httpTest {
        val session = initialize()
        val owner = servers.last()
        val wires = coroutineScope {
            (1..8).map {
                async(Dispatchers.IO) { delete(session) }
            }.awaitAll()
        }
        // 每个响应只能是契约允许结果；并发下多个请求可能都先取到 transport → 不强制恰好一个 200。
        // 404 必须是 SDK 原生「session not found」的 -32000，而非 reaper 语义或裸 404。
        wires.forEach { wire ->
            assertTrue("DELETE 响应只能是 200 或 404：$wire", wire.status == 200 || wire.status == 404)
            if (wire.status == 404) assertEquals("404 必须是 SDK -32000：$wire", -32000, wire.errorCode())
        }
        awaitCondition(describe = "并发 DELETE 后 owner 摘除") { !McpSessions.isTrackedForTest(owner) }
        awaitCondition(describe = "owner close 传播完成") { closeCalls.count { it === owner } >= 1 }
        assertEquals("并发下 owner 仍恰好关闭一次", 1, closeCalls.count { it === owner })
    }

    /** 并发 DELETE 不关另一存活会话的 owner（多 wire 归属隔离）。 */
    @Test(timeout = 60_000) fun deleteDoesNotCloseOtherLiveSession() = httpTest {
        val sessionA = initialize()
        val ownerA = servers.last()
        val sessionB = initialize()
        val ownerB = servers.last()
        assertEquals(2, servers.size)
        assertTrue(ownerA !== ownerB)

        assertEquals(200, delete(sessionA).status)
        awaitCondition(describe = "ownerA 摘除") { !McpSessions.isTrackedForTest(ownerA) }
        awaitCondition(describe = "ownerA close 传播完成") { closeCalls.count { it === ownerA } >= 1 }
        assertEquals(1, closeCalls.count { it === ownerA })
        assertTrue("另一会话 owner 不得被误关", McpSessions.isTrackedForTest(ownerB))
        assertEquals(0, closeCalls.count { it === ownerB })
        // B 仍可正常调用
        val tool = post(rpc(2, "tools/call", buildJsonObject {
            put("name", "ping"); put("arguments", JsonObject(emptyMap()))
        }), sessionB)
        assertEquals("pong", tool.result(JsonPrimitive(2))
            .getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    /** 无 Bearer / 未知 sid / 非法协议头的 DELETE 不得误关已登记实例。 */
    @Test(timeout = 60_000) fun unauthorizedUnknownSidAndBadProtocolDeleteDoNotClose() = httpTest {
        val session = initialize()
        val owner = servers.last()

        assertEquals(401, delete(session, authorization = null).status)
        assertEquals(401, delete(session, authorization = "Bearer wrong").status)
        val unknown = delete("unknown-delete-session")
        assertEquals(404, unknown.status)
        assertEquals(-32000, unknown.errorCode())
        // 非法协议版本头：合法存活 sid + 非法版本 → SDK validateProtocolVersion 拒绝 400，
        // 不得触发 owner 释放（不是 404 模糊兜底——精确断言 SDK 行为）。
        val badProto = delete(session, version = "not-a-version")
        assertEquals("合法存活 sid + 非法协议头必须 400：$badProto", 400, badProto.status)
        assertEquals(-32000, badProto.errorCode())
        assertTrue("活会话不得被这些 DELETE 误关", McpSessions.isTrackedForTest(owner))
        assertEquals(0, closeCalls.count { it === owner })
        // 会话仍可用
        assertEquals(200, post(rpc(9, "tools/list"), session).status)
    }

    /** 无 sid 的 DELETE：SDK 回 400 -32000；不新建 factory 实例、不关已登记 owner。 */
    @Test(timeout = 60_000) fun sessionlessDeleteIsRejectedAndClosesNothing() = httpTest {
        val session = initialize()
        val owner = servers.last()
        val noSid = delete(session = null)
        assertEquals(400, noSid.status)
        assertEquals(-32000, noSid.errorCode())
        assertEquals("无 sid DELETE 不新建实例", 1, servers.size)
        assertTrue(McpSessions.isTrackedForTest(owner))
        assertEquals(0, closeCalls.size)
    }

    /** close 抛异常仍摘除 tracker、原 HTTP 响应不变。 */
    @Test(timeout = 60_000) fun deleteCloseFailureStillReleasesTrackerAndKeepsResponse() = httpTest {
        val failing = AtomicInteger()
        McpSessions.closeServer = { server ->
            closeCalls.add(server)
            if (server === servers.last()) throw IllegalStateException("close boom")
            server.close()
        }
        val session = initialize()
        val owner = servers.last()
        val delete = delete(session)
        assertEquals("close 异常不得改变 SDK 的 200 响应", 200, delete.status)
        awaitCondition(describe = "close 失败仍摘除登记") { !McpSessions.isTrackedForTest(owner) }
        awaitCondition(describe = "close 尝试计数到位") { closeCalls.count { it === owner } >= 1 }
        assertEquals("close 异常也计入恰好一次尝试", 1, closeCalls.count { it === owner })
    }

    /**
     * DELETE 与 reaper 竞态：会话已 touch 成 sessions 条目并被 reaper 判闲置时，DELETE 同时到达。
     * 谁先把 owner 摘出 tracker 谁负责 close——owner 恰好关闭一次；若 reaper 先赢，保留其
     * 「reaped 404」语义不覆写。
     */
    @Test(timeout = 60_000) fun deleteVersusReaperRaceClosesOwnerOnce() = httpTest {
        val fakeNow = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        McpSessions.clock = { fakeNow.get() }
        McpSessions.reapIdleMs = 5 * 60_000L
        val session = initialize()
        val owner = servers.last()
        // touch 使会话进入 sessions 表（fixture handler 不经 dispatchToolCall，显式 touch）
        McpSessions.touchToolCall(owner)
        assertEquals(baselineSessions + 1, McpSessions.trackedSessions())
        // 推进假时钟使会话闲置到期
        fakeNow.addAndGet(6 * 60_000L)
        // 并发：reaper 回收 + DELETE 终止
        coroutineScope {
            val deleteJob = async(Dispatchers.IO) { delete(session) }
            val reapJob = async(Dispatchers.Default) { McpSessions.reap() }
            val wire = deleteJob.await()
            reapJob.await()
            // 契约内结果：DELETE 先赢→200；reaper 先赢→DELETE 撞 reaped 门禁 404
            assertTrue("竞态结果只能是 200 或 reaped-404：$wire", wire.status == 200 || wire.status == 404)
        }
        awaitCondition(describe = "竞态后 owner 摘除") { !McpSessions.isTrackedForTest(owner) }
        awaitCondition(describe = "竞态 close 传播完成") { closeCalls.count { it === owner } >= 1 }
        assertEquals("DELETE/reaper 竞态 owner 恰好关闭一次", 1, closeCalls.count { it === owner })
    }

    companion object {
        private const val INITIALIZE = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"delete-fixture","version":"1"}}}"""
        private const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

        private fun rpc(id: Int, method: String, params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
    }
}
