package com.mina.legadostudio.diagnostic

object LogRedactor {
    /** `authorization=…` / `Authorization: Bearer|Basic …` / `Authorization Bearer …`（无冒号）。 */
    private val authHeader = Regex("(?i)authorization(?:\\s*[:=]\\s*(?:bearer\\s+|basic\\s+)?|\\s+(?:bearer|basic)\\s+)\\S+")
    private val cookieHeader = Regex("(?i)(?:set-)?cookie\\s*[:=]\\s*[^\\r\\n]+")
    private val tokenLike = Regex(
        "(?i)(api[-_ ]?key|access[-_ ]?key|client[_-]?secret|secret|password|passwd|pwd|token)\\s*[:=]\\s*\\S+",
    )
    private val url = Regex("(?i)https?://\\S+")

    private fun sensitive(key: String): Boolean {
        val normalized = key.lowercase().filter { it.isLetterOrDigit() }
        return listOf("authorization", "cookie", "apikey", "accesskey", "secret", "password", "passwd", "token").any { it in normalized } || normalized == "pwd"
    }

    /** Parse before textual replacement: quoted keys, arrays and nested values are not HTTP lines. */
    fun redact(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            // Body-file markers are not JSON; preserve the resource reference for omission accounting.
            if (Regex("""(?:\[binary [0-9]+B [^\]\r\n]+\] )?\[bodyFile=captures/[A-Za-z0-9_./:-]+\]""").matches(trimmed)) return redactText(value)
            return runCatching { redactElement(com.google.gson.JsonParser.parseString(trimmed)).toString() }
                .getOrDefault("[无法解析的结构化内容已隐藏]")
        }
        return redactText(value)
    }

    private fun redactElement(value: com.google.gson.JsonElement): com.google.gson.JsonElement = when {
        value.isJsonObject -> com.google.gson.JsonObject().apply {
            value.asJsonObject.entrySet().forEach { (key, child) ->
                add(key, if (sensitive(key)) com.google.gson.JsonPrimitive("***") else redactElement(child))
            }
        }
        value.isJsonArray -> com.google.gson.JsonArray().apply { value.asJsonArray.forEach { add(redactElement(it)) } }
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> com.google.gson.JsonPrimitive(redact(value.asString))
        else -> value
    }

    private fun redactText(value: String): String = value
        .replace(authHeader, "authorization=***")
        .replace(cookieHeader, "cookie=***")
        .replace(tokenLike) { match -> "${match.groupValues[1]}=***" }
        .replace(url, "[URL已隐藏]")
}
