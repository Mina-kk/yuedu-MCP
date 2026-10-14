package com.mina.legadostudio.domain

import com.mina.legadostudio.mcp.McpConfigStore
import com.mina.legadostudio.mcp.configureStudioMcp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
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
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolHttpIntegrationTest {
    private val legacyVersions = listOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
    private val schema = ToolSchema(
        properties = buildJsonObject { put("step", buildJsonObject { put("type", "integer") }) },
        required = listOf("step"),
    )
    private val annotations = ToolAnnotations(
        readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false,
    )

    private fun fixture(): Server {
        val count = AtomicInteger()
        return Server(
            serverInfo = Implementation("protocol-http-fixture", "1"),
            options = ServerOptions(capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
            )),
        ).also { server ->
            server.addTool(Tool(name = "counter", description = "Increment a session-local counter", inputSchema = schema, annotations = annotations)) { request ->
                val value = count.addAndGet(request.arguments!!.getValue("step").jsonPrimitive.int)
                CallToolResult(listOf(TextContent("counter=$value")))
            }
            server.addResource("fixture://static", "static", "Static fixture", "text/plain") { request ->
                ReadResourceResult(listOf(TextResourceContents("static text", request.uri, "text/plain")))
            }
            server.addResourceTemplate("fixture://items/{name}", "item", "Template fixture", "text/plain") { request, params ->
                ReadResourceResult(listOf(TextResourceContents("item=${params.getValue("name")}", request.uri, "text/plain")))
            }
        }
    }

    private data class Wire(val status: Int, val headers: Headers, val body: String) {
        fun envelope(id: JsonElement?): JsonObject {
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
            assertEquals("HTTP $status, expected response id=$id: $body", 1, matches.size)
            return matches.single().also {
                assertEquals("2.0", it.getValue("jsonrpc").jsonPrimitive.content)
                assertEquals(id, it["id"])
                assertTrue("result/error must be exclusive: $it", it.containsKey("result") xor it.containsKey("error"))
            }
        }

        fun result(id: JsonElement): JsonObject {
            assertEquals("$body", 200, status)
            return envelope(id).also { assertFalse("$it", it.containsKey("error")) }.getValue("result").jsonObject
        }

        fun error(status: Int, code: Int, id: JsonElement? = null): JsonObject {
            assertEquals(body, status, this.status)
            return envelope(id).getValue("error").jsonObject.also {
                assertEquals(body, code, it.getValue("code").jsonPrimitive.int)
                assertTrue("$it", it.getValue("message").jsonPrimitive.content.isNotBlank())
            }
        }
    }

    private class Harness(val client: HttpClient, val port: Int, val factoryCalls: AtomicInteger) {
        suspend fun post(
            body: String,
            session: String? = null,
            version: String? = "2025-11-25",
            authorization: String? = "Bearer fixture-token",
            host: String = "127.0.0.1:$port",
            origin: String? = null,
            accept: String = "application/json, text/event-stream",
            contentType: String = "application/json",
        ): Wire {
            val response = client.post("http://127.0.0.1:$port/mcp") {
                header(HttpHeaders.Host, host)
                header(HttpHeaders.Accept, accept)
                header(HttpHeaders.ContentType, contentType)
                if (authorization != null) header(HttpHeaders.Authorization, authorization)
                if (session != null) header("Mcp-Session-Id", session)
                if (version != null) header("MCP-Protocol-Version", version)
                if (origin != null) header(HttpHeaders.Origin, origin)
                setBody(body)
            }
            return Wire(response.status.value, response.headers, response.bodyAsText())
        }

        suspend fun initialize(version: String = "2025-11-25"): String {
            val response = post(initializeBody(version), version = null)
            assertEquals(version, response.result(JsonPrimitive(1)).getValue("protocolVersion").jsonPrimitive.content)
            val session = response.headers["Mcp-Session-Id"] ?: error("No session header: $response")
            assertTrue(session.isNotBlank())
            val initialized = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", session, version)
            assertEquals(202, initialized.status)
            assertEquals("", initialized.body)
            return session
        }
    }

    private fun httpTest(block: suspend Harness.() -> Unit) = runBlocking {
        val servers = CopyOnWriteArrayList<Server>()
        val factoryCalls = AtomicInteger()
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
                configProvider = { McpConfigStore.Config(1237, true, "fixture-token") },
                allowedHosts = listOf("127.0.0.1", "localhost"),
                allowedOrigins = listOf("http://127.0.0.1:1237", "http://localhost:1237"),
            ) {
                fixture().also { factoryCalls.incrementAndGet(); servers.add(it) }
            }
        }
        try {
            withTimeout(30_000) {
                engine.start(wait = false)
                val port = engine.engine.resolvedConnectors().single().port
                Harness(client, port, factoryCalls).block()
            }
        } finally {
            client.close()
            try {
                withContext(NonCancellable) { withTimeout(5_000) { servers.forEach { it.close() } } }
            } finally {
                engine.stop(100, 500)
            }
        }
    }

    private suspend fun Harness.legacySession(version: String) {
        val session = initialize(version)
        val tools = post(rpc(2, "tools/list"), session, version).result(JsonPrimitive(2))
        assertEquals(listOf("counter"), tools.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        for (value in 1..2) {
            val result = post(counter(value + 2), session, version).result(JsonPrimitive(value + 2))
            assertEquals("counter=$value", result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        }
        assertEquals(1, factoryCalls.get())
    }

    @Test(timeout = 45_000) fun legacy20241105() = httpTest { legacySession("2024-11-05") }
    @Test(timeout = 45_000) fun legacy20250326() = httpTest { legacySession("2025-03-26") }
    @Test(timeout = 45_000) fun legacy20250618() = httpTest { legacySession("2025-06-18") }
    @Test(timeout = 45_000) fun legacy20251125() = httpTest { legacySession("2025-11-25") }

    @Test(timeout = 45_000) fun unknownInitializeVersionFallsBackToLatestLegacy() = httpTest {
        val response = post(initializeBody("2099-01-01"), version = null)
        assertEquals("2025-11-25", response.result(JsonPrimitive(1)).getValue("protocolVersion").jsonPrimitive.content)
        val session = response.headers["Mcp-Session-Id"]!!
        post(rpc(2, "tools/list"), session).result(JsonPrimitive(2))
        assertEquals(1, factoryCalls.get())
    }

    @Test(timeout = 45_000) fun versionHeadersValidateMembershipNotNegotiatedEquality() = httpTest {
        val session = initialize("2025-03-26")
        post(rpc(2, "tools/list"), session, "invalid-version").error(400, -32000, JsonPrimitive(2))
        post(rpc(3, "tools/list"), session, null).result(JsonPrimitive(3))
        legacyVersions.forEachIndexed { index, version ->
            post(rpc(index + 4, "tools/list"), session, version).result(JsonPrimitive(index + 4))
        }
        assertEquals("counter=1", post(counter(8), session, "2025-03-26").result(JsonPrimitive(8))
            .getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(1, factoryCalls.get())
    }

    @Test(timeout = 45_000) fun bearerIsCheckedOnEveryRequestAndHealthIsPublicWithNoStore() = httpTest {
        val health = client.get("http://127.0.0.1:$port/health")
        assertEquals(200, health.status.value)
        assertEquals("no-store", health.headers[HttpHeaders.CacheControl])
        assertEquals(buildJsonObject { put("status", "ok"); put("mcpPath", "/mcp") }, Json.parseToJsonElement(health.bodyAsText()))
        post(initializeBody(), authorization = null).also {
            assertEquals(401, it.status)
            assertEquals("no-store", it.headers[HttpHeaders.CacheControl])
        }
        assertEquals(0, factoryCalls.get())
        val session = initialize()
        for (authorization in listOf(null, "Bearer wrong", "Basic fixture-token")) {
            val denied = post(counter(2), session, authorization = authorization)
            assertEquals(401, denied.status)
            assertEquals("no-store", denied.headers[HttpHeaders.CacheControl])
        }
        val valid = post(counter(3), session, authorization = "bEaReR fixture-token")
        assertEquals("no-store", valid.headers[HttpHeaders.CacheControl])
        assertEquals("counter=1", valid.result(JsonPrimitive(3)).getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(1, factoryCalls.get())
    }

    @Test(timeout = 45_000) fun hostAndOriginProtectionCharacterizesHostnameOnlyPortMatching() = httpTest {
        val session = initialize()
        for (host in listOf("hostile.example:$port", "127.0.0.1.hostile.example:$port", "bad host")) {
            assertEquals(host, 403, post(rpc(2, "tools/list"), session, host = host).status)
        }
        for (origin in listOf("http://hostile.example", "null", "not-a-url", "http://127.0.0.1.hostile.example")) {
            assertEquals(origin, 403, post(rpc(3, "tools/list"), session, origin = origin).status)
        }
        post(rpc(4, "tools/list"), session, origin = null).result(JsonPrimitive(4))
        post(rpc(5, "tools/list"), session, origin = "http://127.0.0.1:1237").result(JsonPrimitive(5))
        post(rpc(6, "tools/list"), session, origin = "http://127.0.0.1:4321", host = "127.0.0.1:4321").result(JsonPrimitive(6))
        assertEquals(1, factoryCalls.get())
    }

    @Test(timeout = 45_000) fun missingUnknownAndDuplicateSessionsHaveDistinctErrors() = httpTest {
        post(rpc(2, "tools/list"), session = null).error(400, -32000, JsonPrimitive(2))
        assertEquals(1, factoryCalls.get())
        post(rpc(3, "tools/list"), session = "unknown-fixture-session").error(404, -32000)
        assertEquals(1, factoryCalls.get())
        val session = initialize()
        post(initializeBody(), session).error(400, -32600, JsonPrimitive(1))
        post(counter(4), session).result(JsonPrimitive(4))
        assertEquals(2, factoryCalls.get())
    }

    @Test(timeout = 45_000) fun malformedSyntaxPrimitiveMethodAndTypedParamsReturnErrorEnvelopes() = httpTest {
        val session = initialize()
        post("{", session).error(400, -32700)
        post("42", session).error(400, -32600)
        post(rpc(2, "not/a/method"), session).error(200, -32601, JsonPrimitive(2))
        post(rpc(3, "tools/call", buildJsonObject { put("name", JsonObject(emptyMap())) }), session).error(200, -32602, JsonPrimitive(3))
        post(counter(4), session).result(JsonPrimitive(4))
        assertEquals(1, factoryCalls.get())
    }

    @Test(timeout = 45_000) fun toolSchemaAnnotationsCallsAndUnknownToolArePreserved() = httpTest {
        val session = initialize()
        val tool = post(rpc(2, "tools/list"), session).result(JsonPrimitive(2)).getValue("tools").jsonArray.single().jsonObject
        assertEquals("counter", tool.getValue("name").jsonPrimitive.content)
        assertEquals("Increment a session-local counter", tool.getValue("description").jsonPrimitive.content)
        assertEquals("object", tool.getValue("inputSchema").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(schema.properties, tool.getValue("inputSchema").jsonObject.getValue("properties"))
        assertEquals(listOf("step"), tool.getValue("inputSchema").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        for (hint in listOf("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint")) {
            assertEquals(JsonPrimitive(false), tool.getValue("annotations").jsonObject.getValue(hint))
        }
        val result = post(counter(3, 7), session).result(JsonPrimitive(3))
        val content = result.getValue("content").jsonArray.single().jsonObject
        assertEquals("text", content.getValue("type").jsonPrimitive.content)
        assertEquals("counter=7", content.getValue("text").jsonPrimitive.content)
        assertFalse(result["isError"] == JsonPrimitive(true))
        val unknown = post(rpc(4, "tools/call", buildJsonObject { put("name", "unknown-tool"); put("arguments", JsonObject(emptyMap())) }), session)
            .result(JsonPrimitive(4))
        assertEquals(JsonPrimitive(true), unknown.getValue("isError"))
        assertEquals("text", unknown.getValue("content").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(unknown.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content.isNotBlank())
    }

    @Test(timeout = 45_000) fun resourceListsTemplatesAndReadsPreserveOuterUriAndMimeType() = httpTest {
        val session = initialize()
        val resource = post(rpc(2, "resources/list"), session).result(JsonPrimitive(2)).getValue("resources").jsonArray.single().jsonObject
        assertEquals("fixture://static", resource.getValue("uri").jsonPrimitive.content)
        assertEquals("static", resource.getValue("name").jsonPrimitive.content)
        assertEquals("text/plain", resource.getValue("mimeType").jsonPrimitive.content)
        val template = post(rpc(3, "resources/templates/list"), session).result(JsonPrimitive(3))
            .getValue("resourceTemplates").jsonArray.single().jsonObject
        assertEquals("fixture://items/{name}", template.getValue("uriTemplate").jsonPrimitive.content)
        assertEquals("item", template.getValue("name").jsonPrimitive.content)
        assertEquals("text/plain", template.getValue("mimeType").jsonPrimitive.content)
        listOf("fixture://static" to "static text", "fixture://items/alice" to "item=alice").forEachIndexed { index, (uri, text) ->
            val id = JsonPrimitive(index + 4)
            val contents = post(rpc(index + 4, "resources/read", buildJsonObject { put("uri", uri) }), session)
                .result(id).getValue("contents").jsonArray.single().jsonObject
            assertEquals(uri, contents.getValue("uri").jsonPrimitive.content)
            assertEquals("text/plain", contents.getValue("mimeType").jsonPrimitive.content)
            assertEquals(text, contents.getValue("text").jsonPrimitive.content)
        }
        post(rpc(6, "resources/read", buildJsonObject { put("uri", "fixture://unknown") }), session)
            .error(200, -32002, JsonPrimitive(6))
    }

    @Test(timeout = 45_000) fun acceptAndContentTypeFailuresDoNotInvalidateSession() = httpTest {
        val session = initialize()
        for (accept in listOf("application/json", "text/event-stream", "text/plain")) {
            post(rpc(2, "tools/list"), session, accept = accept).error(406, -32000)
        }
        post(rpc(3, "tools/list"), session, contentType = "text/plain").error(415, -32000)
        post(rpc(4, "tools/list"), session, contentType = "application/json; charset=utf-8").result(JsonPrimitive(4))
        assertEquals(1, factoryCalls.get())
    }

    @Test(timeout = 45_000) fun current20260728DiscoveryAndPerRequestMetaAreNotModernSupport() = httpTest {
        val fallback = post(initializeBody("2026-07-28"), version = null)
        assertEquals("2025-11-25", fallback.result(JsonPrimitive(1)).getValue("protocolVersion").jsonPrimitive.content)
        val session = fallback.headers["Mcp-Session-Id"]!!
        post(rpc(2, "tools/list"), session, version = "2026-07-28").error(400, -32000, JsonPrimitive(2))
        val discovery = """{"jsonrpc":"2.0","id":"discover","method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"http-fixture","version":"1"}}}}"""
        post(discovery, session, version = null).error(200, -32601, JsonPrimitive("discover"))
        assertEquals(1, factoryCalls.get())
        post(discovery, session = null, version = "2026-07-28").error(400, -32000, JsonPrimitive("discover"))
        val metaOnly = """{"jsonrpc":"2.0","id":"meta-only","method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{},"io.modelcontextprotocol/clientInfo":{"name":"http-fixture","version":"1"}}}}"""
        post(metaOnly, session, version = null).result(JsonPrimitive("meta-only"))
        post(metaOnly, session = null, version = null).error(400, -32000, JsonPrimitive("meta-only"))
        assertEquals(3, factoryCalls.get())
    }

    companion object {
        private fun initializeBody(version: String = "2025-11-25") =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"http-fixture","version":"1"}}}"""

        private fun rpc(id: Int, method: String, params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

        private fun counter(id: Int, step: Int = 1) = rpc(id, "tools/call", buildJsonObject {
            put("name", "counter")
            put("arguments", buildJsonObject { put("step", step) })
        })
    }
}
