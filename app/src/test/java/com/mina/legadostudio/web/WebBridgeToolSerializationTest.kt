package com.mina.legadostudio.web

import com.google.gson.JsonParser
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tools.list` / `tools.call` 的 SDK 原形序列化（`.tasks/host-remote-build.md` 第 1 条）。
 *
 * 手拼 JSON 的旧实现有两个硬伤：
 * - `inputSchema` 是 `ToolSchema` 而不是 `KJsonObject`，手拼类型不符（编译不过）或丢字段；
 * - `CallToolResult` 手拼只带 `content`/`isError`，`structuredContent` 丢失。
 *
 * 这里锁住「用 SDK 自身 serializer 编码」的形状，前端拿到的与网络侧 MCP 客户端一致。
 */
class WebBridgeToolSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private fun sampleTool() = Tool(
        name = "fetch_page",
        description = "抓取网页",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "目标地址")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("minimum", 1)
                }
            },
            required = listOf("url"),
        ),
        annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    )

    @Test
    fun `tool serializes its input schema as a json schema object`() {
        val encoded = json.encodeToString(Tool.serializer(), sampleTool())
        val obj = JsonParser.parseString(encoded).asJsonObject
        assertEquals("fetch_page", obj.get("name").asString)
        assertEquals("抓取网页", obj.get("description").asString)
        val schema = obj.getAsJsonObject("inputSchema")
        assertNotNull("inputSchema must be present", schema)
        assertEquals("object", schema.get("type").asString)
        val properties = schema.getAsJsonObject("properties")
        assertEquals("string", properties.getAsJsonObject("url").get("type").asString)
        assertEquals(1, properties.getAsJsonObject("limit").get("minimum").asInt)
        assertEquals("url", schema.getAsJsonArray("required")[0].asString)
        assertTrue(obj.getAsJsonObject("annotations").get("readOnlyHint").asBoolean)
    }

    @Test
    fun `call tool result keeps content isError and structuredContent`() {
        val result = CallToolResult(
            content = listOf(TextContent("""{"ok":true}""")),
            isError = false,
            structuredContent = buildJsonObject { put("count", 3) },
        )
        val obj = JsonParser.parseString(json.encodeToString(CallToolResult.serializer(), result)).asJsonObject
        val content = obj.getAsJsonArray("content")
        assertEquals(1, content.size())
        assertEquals("text", content[0].asJsonObject.get("type").asString)
        assertEquals("""{"ok":true}""", content[0].asJsonObject.get("text").asString)
        assertFalse(obj.get("isError").asBoolean)
        assertEquals(3, obj.getAsJsonObject("structuredContent").get("count").asInt)
    }

    @Test
    fun `error result keeps the error flag`() {
        val result = CallToolResult(content = listOf(TextContent("boom")), isError = true)
        val obj = JsonParser.parseString(json.encodeToString(CallToolResult.serializer(), result)).asJsonObject
        assertTrue(obj.get("isError").asBoolean)
        assertEquals("boom", obj.getAsJsonArray("content")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `tool list envelope keeps registration order`() {
        val tools = listOf(sampleTool(), sampleTool().copy(name = "get_source"))
        val array = tools.map { JsonParser.parseString(json.encodeToString(Tool.serializer(), it)) }
        assertEquals(listOf("fetch_page", "get_source"), array.map { it.asJsonObject.get("name").asString })
    }
}
