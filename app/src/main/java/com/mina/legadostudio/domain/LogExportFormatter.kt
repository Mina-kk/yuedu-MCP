package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.OperationLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志导出为纯文本，排查问题时可直接贴给 AI 或开发者。 */
object LogExportFormatter {
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun operation(logs: List<OperationLogEntity>, dateKey: String): String = buildString {
        appendLine("# 操作日志 $dateKey 共 ${logs.size} 条（导出 ${time.format(Date())}）")
        logs.sortedBy { it.createdAt }.forEach { log ->
            appendLine("[${time.format(Date(log.createdAt))}] [${log.level}] [${log.category}] ${log.message}")
            if (log.detail.isNotBlank()) appendLine(log.detail.prependIndent("  "))
        }
    }

    fun http(logs: List<HttpLogEntity>, dateKey: String): String = buildString {
        appendLine("# HTTP 事务 $dateKey 共 ${logs.size} 条（导出 ${time.format(Date())}）")
        logs.sortedBy { it.createdAt }.forEach { log ->
            appendLine("==== #${log.id} [${time.format(Date(log.createdAt))}] ${log.method} ${log.url}")
            appendLine("→ HTTP ${log.statusCode} ${log.durationMs}ms final=${log.finalUrl}")
            if (log.error.isNotBlank()) appendLine("error: ${log.error}")
            if (log.redirectChain.isNotBlank()) appendLine("redirects: ${log.redirectChain}")
            if (log.requestHeaders.isNotBlank()) appendLine("-- 请求头 --\n${log.requestHeaders}")
            if (log.requestBody.isNotBlank()) appendLine("-- 请求体 --\n${log.requestBody}")
            if (log.responseHeaders.isNotBlank()) appendLine("-- 响应头 --\n${log.responseHeaders}")
            if (log.responseBody.isNotBlank()) appendLine("-- 响应体 --\n${log.responseBody}")
        }
    }
}
