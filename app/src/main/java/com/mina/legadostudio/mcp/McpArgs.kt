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

    fun int(args: JsonObject?, key: String): Int? {
        val value = args?.get(key) ?: return null
        require(value is JsonPrimitive) {
            "$key 需要 JSON 数字（type: integer），收到 ${jsonType(value)}"
        }
        return value.jsonPrimitive.intOrNull
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
