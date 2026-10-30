package com.mina.legadostudio.diagnostic

/**
 * 把异常链做成可落盘、可回传的短文本：脱敏 URL/令牌，限制长度，保留类名与前几帧。
 * 供操作日志、崩溃摘录和 `inspect_rule` 之后的 NCDFE 对照「首次故障」。
 */
object ExceptionDigest {
    private val URL = Regex("(?i)https?://\\S+")
    private val QUERY_SECRET = Regex("(?i)([?&](?:token|key|auth|password|cookie|authorization|bearer))=([^&\\s]+)")

    fun sanitize(text: String): String = LogRedactor.redact(
        QUERY_SECRET.replace(URL.replace(text, "[URL已隐藏]"), "$1=***"),
    )

    /**
     * 因果链（本类 → cause…）+ 前几帧堆栈，总长不超过 [maxChars]。
     * 不含完整 `stackTraceToString()`，避免把请求 URL/Cookie 打进日志。
     */
    fun chain(error: Throwable, maxChars: Int = 800): String {
        val lines = ArrayList<String>(12)
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            val prefix = if (depth == 0) "" else "Caused by: "
            val message = sanitize((current.message ?: "").replace('\n', ' ')).take(180)
            lines += prefix + current.javaClass.name + if (message.isBlank()) "" else ": $message"
            current = current.cause
            depth++
        }
        error.stackTrace.take(6).forEach { frame ->
            lines += "  at ${sanitize(frame.toString())}"
        }
        return lines.joinToString("\n").take(maxChars)
    }

    fun firstLine(error: Throwable): String {
        val message = sanitize((error.message ?: "").replace('\n', ' ')).take(180)
        return error.javaClass.name + if (message.isBlank()) "" else ": $message"
    }
}
