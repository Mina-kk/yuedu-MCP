package com.mina.legadostudio.web

import com.google.gson.JsonElement
import com.google.gson.JsonObject

import com.google.gson.JsonPrimitive

/** Applies only to the local Web UI; authenticated network MCP tools remain unchanged. */
internal object WebToolResultPolicy {
    private const val REDACTED = "[REDACTED]"


    fun forWeb(toolName: String, result: JsonElement, knownSecrets: Collection<String> = emptyList()): JsonElement {
        if (toolName == "get_cookies") {
            // The tool was dispatched, but raw login cookies are not a Web UI capability.
            return JsonObject().apply {
                addProperty("isError", true)
                add("content", com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "Cookie 已保存在宿主；Web 界面不回显登录凭据。需要原值时使用已鉴权 MCP 客户端。")
                    })
                })
            }
        }
        val secrets = knownSecrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        fun scrubText(text: String): String = secrets.fold(text) { current, secret -> current.replace(secret, REDACTED) }
        fun scrub(value: JsonElement, depth: Int): JsonElement {
            if (depth > 64) return JsonPrimitive("[Web preview depth limit]")
            return when {
                value.isJsonObject -> JsonObject().apply {
                    value.asJsonObject.entrySet().forEach { (key, item) ->
                        add(key, scrub(item, depth + 1))
                    }
                }
                value.isJsonArray -> com.google.gson.JsonArray().apply { value.asJsonArray.forEach { add(scrub(it, depth + 1)) } }
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                    // Source text is opaque: never reformat JSON or reinterpret arbitrary rule keys.
                    JsonPrimitive(scrubText(value.asString))
                }
                else -> value.deepCopy()
            }
        }
        return scrub(result, 0)
    }
}
