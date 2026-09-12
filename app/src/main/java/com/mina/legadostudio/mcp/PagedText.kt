package com.mina.legadostudio.mcp

/**
 * 静态文本（知识库、Skill 参考、语料分片）的统一分页读取。
 * 语义与 [TaskContextStore.read] 保持一致：offset/limit 定位，query 为字面文本搜索跳转。
 */
object PagedText {
    fun page(text: String, offset: Int, limit: Int, query: String? = null): Map<String, Any> {
        require(offset in 0..text.length) { "offset 超出正文范围" }
        require(limit in 1..12000) { "limit 必须是 1..12000" }
        val start = if (query == null) offset else {
            require(query.length in 1..256) { "query 长度必须为 1..256" }
            text.indexOf(query, offset, ignoreCase = true)
        }
        if (start < 0) return mapOf("found" to false, "body" to "", "offset" to offset, "totalChars" to text.length)
        val end = (start + limit).coerceAtMost(text.length)
        return mapOf(
            "found" to true, "body" to text.substring(start, end), "offset" to start,
            "nextOffset" to end, "hasMore" to (end < text.length), "totalChars" to text.length,
        )
    }
}
