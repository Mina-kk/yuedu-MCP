package com.mina.legadostudio.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 工具入参读取的边界锁：缺省、类型不符、数字/布尔的宽松解析。
 * 这里锁定的是现有行为；改动前先改测试并在 CHANGELOG 说明。
 */
class McpArgsTest {
    private fun failure(block: () -> Unit): String {
        val e = runCatching(block).exceptionOrNull()
        assertTrue("应抛 IllegalArgumentException，实际 $e", e is IllegalArgumentException)
        return e!!.message.orEmpty()
    }

    @Test fun missingArgsOrKeyReturnNull() {
        assertNull(McpArgs.str(null, "url"))
        assertNull(McpArgs.int(null, "limit"))
        assertNull(McpArgs.bool(null, "refresh"))
        val empty = JsonObject(emptyMap())
        assertNull(McpArgs.str(empty, "url"))
        assertNull(McpArgs.int(empty, "limit"))
        assertNull(McpArgs.bool(empty, "refresh"))
    }

    @Test fun strAcceptsOnlyJsonString() {
        val args = buildJsonObject {
            put("s", "abc"); put("blank", ""); put("n", 1); put("b", true)
            put("nul", JsonNull)
            putJsonObject("o") { put("k", "v") }
            put("a", buildJsonArray { })
        }
        assertEquals("abc", McpArgs.str(args, "s"))
        assertEquals("", McpArgs.str(args, "blank"))
        assertTrue(failure { McpArgs.str(args, "n") }.contains("收到 number"))
        assertTrue(failure { McpArgs.str(args, "b") }.contains("收到 boolean"))
        assertTrue(failure { McpArgs.str(args, "nul") }.contains("收到 null"))
        val obj = failure { McpArgs.str(args, "o") }
        assertTrue(obj.startsWith("o 需要 JSON 字符串"))
        assertTrue(obj.contains("收到 object") && obj.contains("请先序列化为 JSON 字符串"))
        assertTrue(failure { McpArgs.str(args, "a") }.contains("收到 array"))
    }

    @Test fun intRejectsContainersAndToleratesUnparsablePrimitives() {
        val args = buildJsonObject {
            put("n", 42); put("neg", -1); put("zero", 0); put("numStr", "50")
            put("frac", 3.5); put("word", "abc"); put("huge", 3_000_000_000L); put("b", true)
            put("nul", JsonNull)
            putJsonObject("o") { }
            put("a", buildJsonArray { })
        }
        assertEquals(42, McpArgs.int(args, "n"))
        assertEquals(-1, McpArgs.int(args, "neg"))
        assertEquals(0, McpArgs.int(args, "zero"))
        assertEquals("字符串数字按内容解析", 50, McpArgs.int(args, "numStr"))
        assertNull("小数不是整数，回落到工具默认值", McpArgs.int(args, "frac"))
        assertNull(McpArgs.int(args, "word"))
        assertNull("超出 Int 范围回落默认值", McpArgs.int(args, "huge"))
        assertNull(McpArgs.int(args, "b"))
        assertNull("JSON null 视同缺省", McpArgs.int(args, "nul"))
        assertEquals("o 需要 JSON 数字（type: integer），收到 object", failure { McpArgs.int(args, "o") })
        assertTrue(failure { McpArgs.int(args, "a") }.contains("收到 array"))
    }

    @Test fun boolRejectsContainersAndToleratesStringBooleans() {
        val args = buildJsonObject {
            put("t", true); put("f", false); put("tStr", "true"); put("word", "yes"); put("n", 1)
            put("nul", JsonNull)
            putJsonObject("o") { }
            put("a", buildJsonArray { })
        }
        assertEquals(true, McpArgs.bool(args, "t"))
        assertEquals(false, McpArgs.bool(args, "f"))
        assertEquals(true, McpArgs.bool(args, "tStr"))
        assertNull(McpArgs.bool(args, "word"))
        assertNull(McpArgs.bool(args, "n"))
        assertNull(McpArgs.bool(args, "nul"))
        assertEquals("o 需要 JSON 布尔（type: boolean），收到 object", failure { McpArgs.bool(args, "o") })
        assertTrue(failure { McpArgs.bool(args, "a") }.contains("收到 array"))
    }

    @Test fun jsonTypeNamesEveryKind() {
        val args = buildJsonObject {
            put("s", "x"); put("numStr", "12"); put("n", 1.5); put("b", false); put("nul", JsonNull)
            putJsonObject("o") { }
            put("a", buildJsonArray { })
        }
        val types = listOf("s", "numStr", "n", "b", "nul", "o", "a").map { McpArgs.jsonType(args.getValue(it)) }
        assertEquals(listOf("string", "number", "number", "boolean", "null", "object", "array"), types)
    }
}
