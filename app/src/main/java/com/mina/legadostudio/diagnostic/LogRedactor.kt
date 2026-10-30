package com.mina.legadostudio.diagnostic

object LogRedactor {
    /** `authorization=…` / `Authorization: Bearer|Basic …` / `Authorization Bearer …`（无冒号）。 */
    private val authHeader = Regex("(?i)authorization(?:\\s*[:=]\\s*(?:bearer\\s+|basic\\s+)?|\\s+(?:bearer|basic)\\s+)\\S+")
    private val cookieHeader = Regex("(?i)(?:set-)?cookie\\s*[:=]\\s*[^\\r\\n]+")
    private val tokenLike = Regex(
        "(?i)(api[-_ ]?key|access[-_ ]?key|client[_-]?secret|secret|password|passwd|pwd|token)\\s*[:=]\\s*\\S+",
    )
    private val url = Regex("(?i)https?://\\S+")

    fun redact(value: String): String = value
        .replace(authHeader, "authorization=***")
        .replace(cookieHeader, "cookie=***")
        .replace(tokenLike) { match -> "${match.groupValues[1]}=***" }
        .replace(url, "[URL已隐藏]")
}
