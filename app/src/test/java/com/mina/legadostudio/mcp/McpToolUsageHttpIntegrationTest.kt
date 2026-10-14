package com.mina.legadostudio.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实本机 JVM HTTP（CIO 引擎，非 testApplication 内存传输）集成验证：
 * tools/call 经由 SDK → production 的 McpToolUsage.track 聚合，HTTP 返回体与统计两侧核对。
 * fixture 是独立内存 Server（非 Android StudioMcpServer），只验证 track 与 dispatch 的挂法；
 * 不代表 Android 端 67 个工具逐一实测。全局 McpToolUsageRegistry.current 用例结束还原。
 */
class McpToolUsageHttpIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val schema = ToolSchema(properties = buildJsonObject {}, required = emptyList())

    /** 与 StudioMcpServer.dispatchToolCall 同款挂法：外层 track 包住业务 handler。 */
    private fun fixture(usage: McpToolUsage): Server {
        val boomCalls = AtomicInteger()
        val appStatusCalls = AtomicInteger()
        return Server(
            serverInfo = Implementation("tool-usage-fixture", "1"),
            options = ServerOptions(capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            )),
        ).also { server ->
            suspend fun tracked(name: String, block: suspend () -> CallToolResult): CallToolResult =
                usage.track(name) { block() }
            server.addTool(Tool(name = "u_ok", description = "returns ok text", inputSchema = schema)) {
                tracked("u_ok") { CallToolResult(listOf(TextContent("ok-body-中😀"))) }
            }
            server.addTool(Tool(name = "u_err", description = "returns isError result", inputSchema = schema)) {
                tracked("u_err") { CallToolResult(listOf(TextContent("bad")), isError = true) }
            }
            server.addTool(Tool(name = "u_boom", description = "throws (SDK converts to isError)", inputSchema = schema)) {
                tracked("u_boom") { boomCalls.incrementAndGet(); throw IllegalStateException("boom-$boomCalls") }
            }
            // 真正注册 app_status：经同一 production track，验证它自身不落统计（不反馈进回包）。
            // 与 McpProjectTools 生产路径一致：Gson().toJson(mapOf("toolUsage" to snapshot))。
            server.addTool(Tool(name = "app_status", description = "status probe", inputSchema = schema)) {
                tracked("app_status") {
                    appStatusCalls.incrementAndGet()
                    CallToolResult(listOf(TextContent(
                        com.google.gson.Gson().toJson(mapOf("toolUsage" to usage.snapshot()))
                    )))
                }
            }
        }
    }

    private data class Harness(
        val client: HttpClient,
        val port: Int,
        val servers: CopyOnWriteArrayList<Server>,
        val json: Json,
    ) {
        var currentSession: String? = null
        var protocolVersion: String = "2025-11-25"

        suspend fun post(body: String, session: String? = currentSession): Pair<Int, String> {
            val response = client.post("http://127.0.0.1:$port/mcp") {
                header(HttpHeaders.Host, "127.0.0.1:$port")
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                header(HttpHeaders.ContentType, "application/json")
                header("MCP-Protocol-Version", protocolVersion)
                if (session != null) header("Mcp-Session-Id", session)
                setBody(body)
            }
            return response.status.value to response.bodyAsText()
        }

        fun envelope(body: String, id: Int): JsonObject {
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
            val matches = payloads.map { json.parseToJsonElement(it).jsonObject }
                .filter { it["id"] == JsonPrimitive(id) && (it.containsKey("result") || it.containsKey("error")) }
            assertEquals("expected one envelope for id=$id: $body", 1, matches.size)
            return matches.single()
        }

        suspend fun callTool(id: Int, name: String): JsonObject {
            val (status, body) = post(
                """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":{}}}""",
            )
            assertEquals("id=$id body=$body", 200, status)
            return envelope(body, id).getValue("result").jsonObject
        }

        /** initialize → 拿会话头 → 发 notifications/initialized（带正确协议版本头，期望 202）。 */
        suspend fun startSession(): String {
            val response = client.post("http://127.0.0.1:$port/mcp") {
                header(HttpHeaders.Host, "127.0.0.1:$port")
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$protocolVersion","capabilities":{},"clientInfo":{"name":"it","version":"1"}}}""")
            }
            assertEquals(200, response.status.value)
            val session = response.headers["Mcp-Session-Id"] ?: error("no session header")
            currentSession = session
            val (status, _) = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            assertEquals("notifications/initialized", 202, status)
            return session
        }
    }

    private fun httpTest(block: suspend Harness.() -> Unit) = runBlocking {
        val servers = CopyOnWriteArrayList<Server>()
        val usage = McpToolUsage()
        val old = McpToolUsageRegistry.current
        McpToolUsageRegistry.current = usage
        val client = HttpClient(ClientCIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 5_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 5_000
            }
        }
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            configureStudioMcp(
                configProvider = { McpConfigStore.Config(1237, false, "") },
                allowedHosts = listOf("127.0.0.1", "localhost"),
                allowedOrigins = listOf("http://127.0.0.1:1237", "http://localhost:1237"),
            ) { fixture(usage).also { servers.add(it) } }
        }
        try {
            withTimeout(30_000) {
                engine.start(wait = false)
                val port = engine.engine.resolvedConnectors().single().port
                Harness(client, port, servers, json).block()
            }
        } finally {
            client.close()
            try {
                withContext(NonCancellable) { withTimeout(5_000) { servers.forEach { it.close() } } }
            } finally {
                engine.stop(100, 500)
                McpToolUsageRegistry.current = old
            }
        }
    }

    @Test(timeout = 45_000) fun toolsCallThroughProductionTrackRecordsIntoUsage() = httpTest {
        startSession()
        val ok = callTool(2, "u_ok")
        assertEquals("ok-body-中😀",
            ok.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertFalse(ok["isError"] == JsonPrimitive(true))

        val err = callTool(3, "u_err")
        assertEquals(JsonPrimitive(true), err["isError"])

        // handler 抛错经 SDK 转为 isError=true 回包；track 端按未捕获异常计 errors
        val boom = callTool(4, "u_boom")
        assertEquals(JsonPrimitive(true), boom["isError"])
        assertTrue(boom.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content.isNotBlank())

        val usage = McpToolUsageRegistry.current
        val snap = usage.snapshot()
        @Suppress("UNCHECKED_CAST")
        val summary = snap["summary"] as Map<String, Any>
        assertEquals(3L, summary["calls"])
        assertEquals(2L, summary["errors"]) // u_err 的 isError + u_boom 的未捕获异常
        assertEquals(0L, summary["cancelled"])
        assertEquals(3, snap["totalTools"]) // app_status 不被 track，不进 byName

        val per = usage.topByTextBytes(10).toMap()
        val okStats = per.getValue("u_ok")
        assertEquals(1L, okStats["calls"])
        assertEquals(0L, okStats["errors"])
        // "ok-body-中😀": UTF16 = 8+1+2=11；UTF8 = 8+3+4=15
        assertEquals(11L, okStats["textChars"])
        assertEquals(15L, okStats["textBytesUtf8"])
        assertEquals(1L, per.getValue("u_err")["errors"])
        assertEquals(1L, per.getValue("u_boom")["errors"])
        // 快照不含结果正文内容
        val blob = snap.toString()
        assertFalse(blob.contains("ok-body"))
        assertFalse(blob.contains("boom-1"))
    }

    /** fixture 注册了 app_status：真实 tools/call 走 production track，自身不计数、结果原样回、把当时的 toolUsage JSON 读回。 */
    @Test(timeout = 45_000) fun appStatusCallRunsThroughProductionTrackButIsNotCounted() = httpTest {
        startSession()
        callTool(2, "u_ok") // 先积累一条其它工具的统计
        assertEquals(1L, summaryCalls())

        val status = callTool(3, "app_status")
        assertFalse(status["isError"] == JsonPrimitive(true))
        // 回包是 app_status 执行时拍的 toolUsage JSON（Gson 序列化，与生产同形）
        val payload = status.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        val toolUsage = json.parseToJsonElement(payload).jsonObject.getValue("toolUsage").jsonObject
        @Suppress("UNCHECKED_CAST")
        val summary = toolUsage.getValue("summary").jsonObject
        // 自身调用不反馈进统计：snapshot 时刻 calls 仍为 1（只有 u_ok）
        assertEquals(1, summary.getValue("calls").jsonPrimitive.content.toLong())
        assertEquals(1, toolUsage.getValue("totalTools").jsonPrimitive.content.toInt())
        assertEquals(1, toolUsage.getValue("listed").jsonPrimitive.content.toInt())
        assertFalse(toolUsage.getValue("truncated").jsonPrimitive.content.toBoolean())
        val tools = toolUsage.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(listOf("u_ok"), tools.map { it.getValue("name").jsonPrimitive.content })
        assertFalse(tools.any { it.getValue("name").jsonPrimitive.content == "app_status" })
        assertEquals(1, tools.single().getValue("calls").jsonPrimitive.content.toLong())
        // 调用后统计里仍没有 app_status 自身，u_ok 计数不变
        assertEquals(1L, summaryCalls())
        assertEquals(1, McpToolUsageRegistry.current.snapshot()["totalTools"])
        val per = McpToolUsageRegistry.current.topByTextBytes(10).toMap()
        assertFalse(per.containsKey("app_status"))
        assertEquals(1L, per.getValue("u_ok")["calls"])
    }

    private fun summaryCalls(): Long {
        @Suppress("UNCHECKED_CAST")
        val summary = McpToolUsageRegistry.current.snapshot()["summary"] as Map<String, Any>
        return summary["calls"] as Long
    }
}
