package com.mina.legadostudio.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** MCP 工具入参的类型读取：缺省返回 null，类型不符抛 IllegalArgumentException（经 err 回给调用方）。 */
internal object McpArgs {
    fun str(args: JsonObject?, key: String): String? {
        val value = args?.get(key) ?: return null
        require(value is JsonPrimitive && value.isString) {
            "$key 需要 JSON 字符串（type: string），收到 ${jsonType(value)}；对象/数组请先序列化为 JSON 字符串"
        }
        return value.jsonPrimitive.contentOrNull
    }

    /**
     * 读「JSON 字符串，但容忍 MCP 传输层已自动反序列化成 object」的入参：
     * 客户端按 schema 传 string 时，部分传输层/网关会把内容里本就是 JSON 的字符串
     * 再解析一次成 object 送到服务端。这里在入口归一化——收到 string 原样返回；
     * 收到 object/array 重新序列化回 JSON 文本交给下游（等价于客户端传入了序列化字符串）。
     * 用于 BookSource JSON 这类「值本身是一段 JSON」的参数（source 等），消除
     * 「客户端序列化 → 传输层又解析」的双重转换死锁。字段语义不变的标量参数仍走 str()。
     */
    fun jsonText(args: JsonObject?, key: String): String? {
        val value = args?.get(key) ?: return null
        if (value is JsonNull) return null
        if (value is JsonPrimitive) {
            require(value.isString) {
                "$key 需要 JSON 字符串或对象，收到 ${jsonType(value)}"
            }
            return value.contentOrNull
        }
        // object/array：传输层把字符串里嵌套的 JSON 又解析了一次；按原结构重新序列化交还。
        return value.toString()
    }

    fun int(args: JsonObject?, key: String): Int? {
        val value = args?.get(key) ?: return null
        if (value is JsonNull) return null
        require(value is JsonPrimitive) {
            "$key 需要 JSON 数字（type: integer），收到 ${jsonType(value)}"
        }
        val primitive = value.jsonPrimitive
        primitive.intOrNull?.let { return it }
        if (primitive.isString || primitive.booleanOrNull != null) return null
        throw IllegalArgumentException(
            "$key 需要 JSON 整数（type: integer，范围 ${Int.MIN_VALUE}..${Int.MAX_VALUE}），收到 \"${primitive.content}\" 无法表示为 Int"
        )
    }

    fun bool(args: JsonObject?, key: String): Boolean? {
        val value = args?.get(key) ?: return null
        require(value is JsonPrimitive) {
            "$key 需要 JSON 布尔（type: boolean），收到 ${jsonType(value)}"
        }
        return value.jsonPrimitive.booleanOrNull
    }

    fun jsonType(value: JsonElement): String = when (value) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        else -> when {
            value.jsonPrimitive.booleanOrNull != null -> "boolean"
            value.jsonPrimitive.contentOrNull?.toDoubleOrNull() != null -> "number"
            else -> "string"
        }
    }
}
