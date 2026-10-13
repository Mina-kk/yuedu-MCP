package com.mina.legadostudio.mcp

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `?compact=1` 的端到端契约：同一组工具在默认 / 精简两条会话上分别跑 tools/list，
 * 校验名称顺序一致、schema 类型与约束逐字不变、描述被截断，且 studio://tools/{name}
 * 能把完整定义读回。StudioMcpServer 依赖 Android Context 无法进 testApplication，
 * 这里用同一 [McpToolCompactor] 变换在内存 Server 上复现完整链路（这是本用例的真实端点）。
 */
class McpCompactToolsContractTest {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val fullTools = listOf(
        Tool(
            name = "match_sources",
            description = "在内置26861书源语料中按域名/名称查现成书源与模板族。做书源前先查",
            inputSchema = schema(
                mapOf("query" to "域名、URL或站点名称关键词", "limit" to "1..20，默认10"),
                listOf("query"),
            ),
            annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ),
        Tool(
            name = "update_context",
            description = "保存任务进度、已验证规则、下一步等简短笔记，整体替换notes，最多4000字符。不要存密码。",
            inputSchema = schema(mapOf("notes" to "完整的新任务笔记"), listOf("notes")),
            annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        ),
        Tool(
            name = "get_domain_modes",
            description = "读取全部每域验证模式（auto/always/webview）",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
            annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
        ),
    )

    private fun schema(props: Map<String, String>, required: List<String>) = ToolSchema(
        properties = buildJsonObject {
            props.forEach { (k, v) -> put(k, buildJsonObject { put("type", "string"); put("description", v) }) }
        },
        required = required,
    )

    private fun buildServer(compact: Boolean): Server {
        val catalog = LinkedHashMap<String, Tool>()
        fullTools.forEach { catalog[it.name] = it }
        return Server(
            serverInfo = Implementation("compact-it", "1"),
            options = ServerOptions(capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                resources = ServerCapabilities.Resources(),
            )),
        ).also { server ->
            server.addResourceTemplate(
                McpToolCompactor.RESOURCE_URI_TEMPLATE, McpToolCompactor.RESOURCE_NAME,
                McpToolCompactor.RESOURCE_DESCRIPTION, McpToolCompactor.RESOURCE_MIME,
            ) { req, params ->
                val name = params["name"]
                    ?: req.uri.removePrefix(McpToolCompactor.RESOURCE_URI_PREFIX).takeIf { it.isNotBlank() }
                val tool = name?.let { catalog[it] } ?: throw IllegalArgumentException("未知工具")
                ReadResourceResult(listOf(TextResourceContents(McpToolCompactor.resourcePayload(tool), req.uri, McpToolCompactor.RESOURCE_MIME)))
            }
            fullTools.forEach { tool ->
                val wire = if (compact) McpToolCompactor.compactTool(tool) else tool
                server.addTool(wire) { CallToolResult(listOf(TextContent("{}"))) }
            }
        }
    }

    private fun initializeBody() = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""

    private suspend fun io.ktor.client.HttpClient.startSession(url: String): String {
        val init = post(url) {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody(initializeBody())
        }
        return init.headers["Mcp-Session-Id"] ?: error("initialize 未回 Mcp-Session-Id：${init.status} ${init.bodyAsText()}")
    }

    /** 从一次 POST 的 SSE 帧里取首个 `data:` JSON-RPC 响应的 result。 */
    private suspend fun rpcResult(client: io.ktor.client.HttpClient, url: String, session: String, body: String): JsonObject {
        val resp = client.post(url) {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header("Mcp-Session-Id", session)
            setBody(body)
        }
        val text = resp.bodyAsText()
        val data = text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
            .firstOrNull { it.contains("\"result\"") || it.contains("\"error\"") }
            ?: text // enableJsonResponse 打开时整个 body 就是 JSON
        return json.parseToJsonElement(data).jsonObject.getValue("result").jsonObject
    }

    private fun toolsList(client: io.ktor.client.HttpClient, url: String, session: String): JsonObject =
        kotlinx.coroutines.runBlocking {
            rpcResult(client, url, session, """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
        }

    private fun readResource(client: io.ktor.client.HttpClient, url: String, session: String, uri: String): JsonObject =
        kotlinx.coroutines.runBlocking {
            rpcResult(client, url, session,
                """{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"$uri"}}""")
        }

    private fun toolsOf(result: JsonObject) = result.getValue("tools").jsonArray.map { it.jsonObject }

    @Test fun defaultSessionIsVerbatim() = testApplication {
        application {
            configureStudioMcp(
                configProvider = { McpConfigStore.Config(1237, false, "") },
                allowedHosts = listOf("localhost"),
                allowedOrigins = listOf("http://localhost"),
            ) { buildServer(compact = false) }
        }
        val session = client.startSession("/mcp")
        val tools = toolsOf(toolsList(client, "/mcp", session))
        assertEquals(listOf("match_sources", "update_context", "get_domain_modes"), tools.map { it.getValue("name").jsonPrimitive.content })
        // 默认模式逐字不变
        val update = tools.first { it.getValue("name").jsonPrimitive.content == "update_context" }
        assertEquals("保存任务进度、已验证规则、下一步等简短笔记，整体替换notes，最多4000字符。不要存密码。", update.getValue("description").jsonPrimitive.content)
        val props = update.getValue("inputSchema").jsonObject.getValue("properties").jsonObject
        assertEquals("完整的新任务笔记", (props.getValue("notes") as JsonObject).getValue("description").jsonPrimitive.content)
    }

    @Test fun compactSessionTruncatesButKeepsShapeAndReadsBack() = testApplication {
        application {
            configureStudioMcp(
                configProvider = { McpConfigStore.Config(1237, false, "") },
                allowedHosts = listOf("localhost"),
                allowedOrigins = listOf("http://localhost"),
            ) { buildServer(compact = true) }
        }
        val session = client.startSession("/mcp?compact=1")
        val tools = toolsOf(toolsList(client, "/mcp?compact=1", session))
        // 名称与顺序不变、数量不增
        assertEquals(listOf("match_sources", "update_context", "get_domain_modes"), tools.map { it.getValue("name").jsonPrimitive.content })

        val update = tools.first { it.getValue("name").jsonPrimitive.content == "update_context" }
        val desc = update.getValue("description").jsonPrimitive.content
        // 描述截到首句边界
        assertEquals("保存任务进度、已验证规则、下一步等简短笔记，整体替换notes，最多4000字符。", desc)
        // schema：type/required/数值约束不变，description 被截断
        val schema = update.getValue("inputSchema").jsonObject
        assertEquals(listOf("notes"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val props = schema.getValue("properties").jsonObject
        val notes = props.getValue("notes") as JsonObject
        assertEquals("string", notes.getValue("type").jsonPrimitive.content)
        assertEquals("完整的新任务笔记", notes.getValue("description").jsonPrimitive.content)

        // 完整定义经 studio://tools/{name} 读回
        val resource = readResource(client, "/mcp?compact=1", session, "studio://tools/update_context")
        val contents = resource.getValue("contents").jsonArray
        val text = (contents[0].jsonObject.getValue("text").jsonPrimitive.content)
        val full = json.parseToJsonElement(text).jsonObject
        assertEquals("update_context", full.getValue("name").jsonPrimitive.content)
        assertEquals("保存任务进度、已验证规则、下一步等简短笔记，整体替换notes，最多4000字符。不要存密码。", full.getValue("description").jsonPrimitive.content)
        val fullProps = full.getValue("inputSchema").jsonObject.getValue("properties").jsonObject
        assertEquals("完整的新任务笔记", (fullProps.getValue("notes") as JsonObject).getValue("description").jsonPrimitive.content)
        assertEquals(false, (full.getValue("annotations") as JsonObject).getValue("readOnlyHint").jsonPrimitive.content.toBoolean())
    }

    @Test fun compactDoesNotChangeToolCount() = testApplication {
        application {
            configureStudioMcp(
                configProvider = { McpConfigStore.Config(1237, false, "") },
                allowedHosts = listOf("localhost"),
                allowedOrigins = listOf("http://localhost"),
            ) { buildServer(compact = call.request.queryParameters[McpToolCompactor.QUERY_PARAM] == McpToolCompactor.QUERY_VALUE) }
        }
        val ds = client.startSession("/mcp")
        val cs = client.startSession("/mcp?compact=1")
        val defaultCount = toolsOf(toolsList(client, "/mcp", ds)).size
        val compactCount = toolsOf(toolsList(client, "/mcp?compact=1", cs)).size
        assertEquals(defaultCount, compactCount)
        assertEquals(fullTools.size, compactCount)
    }
}
