package com.mina.legadostudio.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `?compact=1` 纯函数边界锁：句末截断、上限硬截、schema 结构不变、完整定义可回读。 */
class McpToolCompactorTest {

    // ---------- truncate ----------

    @Test fun nullAndEmptyPassThrough() {
        assertNull(McpToolCompactor.truncate(null))
        assertEquals("", McpToolCompactor.truncate(""))
    }

    @Test fun singleSentenceUnderLimitIsVerbatim() {
        val s = "保存任务进度、已验证规则、下一步等简短笔记"
        assertEquals(s, McpToolCompactor.truncate(s))
    }

    @Test fun cutsAtFirstSentenceEnd() {
        val s = "按pageId读取已保存网页片段，不联网。offset/limit为字符范围；query为字面文本搜索。"
        // 首个边界是第 16 位的「。」（0-indexed 16），保留含句号的前一句
        val t = McpToolCompactor.truncate(s)
        assertTrue(t!!.endsWith("。"))
        assertEquals("按pageId读取已保存网页片段，不联网。", t)
    }

    @Test fun cutsAtSemicolonBoundary() {
        val s = "第一句；第二句"
        assertEquals("第一句；", McpToolCompactor.truncate(s))
    }

    @Test fun longSingleSentenceHardTruncatesWithEllipsis() {
        val s = "长".repeat(150)
        val t = McpToolCompactor.truncate(s)!!
        assertEquals(McpToolCompactor.MAX_DESCRIPTION_CHARS + 1, t.length)
        assertTrue(t.endsWith("…"))
    }

    @Test fun longSentenceWithLateBoundaryHardTruncates() {
        // 边界在 120 位（超过上限）→ 不取边界，硬截
        val s = "长".repeat(120) + "。尾巴"
        val t = McpToolCompactor.truncate(s)!!
        assertTrue(t.endsWith("…"))
        assertEquals(McpToolCompactor.MAX_DESCRIPTION_CHARS + 1, t.length)
    }

    @Test fun englishPeriodIsNotABoundary() {
        val s = "用 a.b 模式调用，不截断"
        assertEquals(s, McpToolCompactor.truncate(s))
    }

    // ---------- compactSchema ----------

    private fun toolSchema(desc: String) = ToolSchema(properties = buildJsonObject {
        put("url", buildJsonObject { put("type", "string"); put("description", desc) })
        putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 12000) }
    }, required = listOf("url"))

    @Test fun compactSchemaTruncatesOnlyPropertyDescriptions() {
        val schema = toolSchema("第一句。第二句。第三句")
        val compact = McpToolCompactor.compactSchema(schema)
        val props = compact.properties!!
        assertEquals("第一句。", (props.getValue("url") as JsonObject).getValue("description").jsonPrimitive.content)
        // 非描述键逐字保留
        assertEquals("string", (props.getValue("url") as JsonObject).getValue("type").jsonPrimitive.content)
        assertEquals("12000", (props.getValue("limit") as JsonObject).getValue("maximum").jsonPrimitive.content)
        assertEquals(listOf("url"), compact.required)
    }

    @Test fun compactSchemaKeepsPropertyWithoutDescription() {
        val schema = ToolSchema(properties = buildJsonObject {
            putJsonObject("limit") { put("type", "integer"); put("minimum", 0) }
        }, required = listOf("limit"))
        val compact = McpToolCompactor.compactSchema(schema)
        assertEquals(schema.properties, compact.properties)
        assertEquals(schema.required, compact.required)
    }

    // ---------- compactTool / resourcePayload ----------

    private fun tool(name: String, desc: String) = Tool(
        name = name, description = desc, inputSchema = toolSchema("入参说明。补充"),
        annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    )

    @Test fun compactToolKeepsNameAndAnnotations() {
        val t = McpToolCompactor.compactTool(tool("read_page", "读取。其余"))
        assertEquals("read_page", t.name)
        assertEquals("读取。", t.description)
        assertEquals(true, t.annotations?.readOnlyHint)
        assertEquals(false, t.annotations?.openWorldHint)
    }

    @Test fun compactToolAppendsResourcePointerWhenHardTruncated() {
        val t = McpToolCompactor.compactTool(tool("t", "长".repeat(200)))
        assertTrue(t.description!!.endsWith(McpToolCompactor.TRUNCATED_NOTE))
        assertTrue(t.description!!.contains("studio://tools/"))
    }

    @Test fun resourcePayloadContainsFullDefinition() {
        val original = tool("fetch_page", "完整描述。第二句")
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(
            McpToolCompactor.resourcePayload(original)
        ) as JsonObject
        assertEquals("fetch_page", payload.getValue("name").jsonPrimitive.content)
        assertEquals("完整描述。第二句", payload.getValue("description").jsonPrimitive.content)
        val schema = payload.getValue("inputSchema") as JsonObject
        val props = schema.getValue("properties") as JsonObject
        assertEquals("入参说明。补充", (props.getValue("url") as JsonObject).getValue("description").jsonPrimitive.content)
        // annotations 一并回传
        val ann = payload.getValue("annotations") as JsonObject
        assertEquals(true, ann.getValue("readOnlyHint").jsonPrimitive.content.toBoolean())
    }

    @Test fun resourcePayloadNullAnnotationsEmitsNull() {
        val noAnn = Tool(name = "x", description = "d", inputSchema = toolSchema("s"))
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(
            McpToolCompactor.resourcePayload(noAnn)
        ) as JsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, payload.getValue("annotations"))
        assertFalse(payload.getValue("description").jsonPrimitive.content.contains("…"))
    }
}
