package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.OperationLogEntity
import org.junit.Assert.*
import org.junit.Test

class LogExportFormatterTest {
    @Test fun operationExportContainsAllFields() {
        val text = LogExportFormatter.operation(
            listOf(
                OperationLogEntity(id = 2, createdAt = 2000, level = "ERROR", category = "fetch", message = "失败", detail = "stack\nline2"),
                OperationLogEntity(id = 1, createdAt = 1000, level = "INFO", category = "mcp", message = "成功"),
            ), "2026-09-13",
        )
        assertTrue(text.contains("# 操作日志 2026-09-13 共 2 条"))
        // 按时间升序：旧记录在前
        assertTrue(text.indexOf("成功") < text.indexOf("失败"))
        assertTrue(text.contains("[ERROR] [fetch] 失败"))
        assertTrue(text.contains("  stack"))
    }

    @Test fun httpExportContainsRequestAndResponse() {
        val text = LogExportFormatter.http(
            listOf(
                HttpLogEntity(
                    id = 9, method = "POST", url = "https://a.test/search", finalUrl = "https://a.test/search",
                    statusCode = 200, durationMs = 123, requestHeaders = "UA: x", responseHeaders = "CT: html",
                    requestBody = "key=abc", responseBody = "<html>结果</html>", error = "", redirectChain = "[]", createdAt = 1000,
                ),
                HttpLogEntity(
                    id = 10, method = "GET", url = "https://a.test/b", finalUrl = "https://a.test/b",
                    statusCode = 403, durationMs = 10, requestHeaders = "", responseHeaders = "",
                    requestBody = "", responseBody = "", error = "HTTP 403", redirectChain = "[]", createdAt = 2000,
                ),
            ), "2026-09-13",
        )
        assertTrue(text.contains("# HTTP 事务 2026-09-13 共 2 条"))
        assertTrue(text.contains("==== #9"))
        assertTrue(text.contains("POST https://a.test/search"))
        assertTrue(text.contains("→ HTTP 200 123ms"))
        assertTrue(text.contains("-- 请求体 --\nkey=abc"))
        assertTrue(text.contains("<html>结果</html>"))
        assertTrue(text.contains("error: HTTP 403"))
        // 空字段段落不输出占位标题：#9 有请求/响应头，#10 全空，全文各段落标题只出现一次
        assertEquals(1, text.split("-- 请求头 --").size - 1)
        assertEquals(1, text.split("-- 响应体 --").size - 1)
    }
}
