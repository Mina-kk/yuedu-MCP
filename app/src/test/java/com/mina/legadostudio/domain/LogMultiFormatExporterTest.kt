package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.OperationLogEntity
import org.junit.Assert.*
import org.junit.Test

class LogMultiFormatExporterTest {
    private fun http(
        id: Long = 1,
        url: String = "https://a.test/s",
        requestHeaders: String = "",
        requestBody: String = "",
        responseBody: String = "",
        createdAt: Long = 1000,
    ) = HttpLogEntity(
        id = id, method = "GET", url = url, finalUrl = url, statusCode = 200, durationMs = 5,
        requestHeaders = requestHeaders, responseHeaders = "", requestBody = requestBody,
        responseBody = responseBody, error = "", redirectChain = "[]", createdAt = createdAt,
    )

    @Test fun csvStartsWithBomAndUsesCrlf() {
        val csv = LogMultiFormatExporter.operation(
            listOf(OperationLogEntity(id = 1, createdAt = 1000, level = "INFO", category = "mcp", message = "ok")),
            "2026-09-13", LogExportFormat.CSV,
        )
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.startsWith("\uFEFFid,time,level,category,message,detail\r\n"))
        assertTrue(csv.endsWith("\r\n"))
        assertEquals(2, csv.split("\r\n").size - 1)
    }

    @Test fun csvQuotesCommaQuoteAndNewlineFields() {
        assertEquals("plain", LogMultiFormatExporter.escapeCsv("plain"))
        assertEquals("\"a,b\"", LogMultiFormatExporter.escapeCsv("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", LogMultiFormatExporter.escapeCsv("say \"hi\""))
        assertEquals("\"l1\nl2\"", LogMultiFormatExporter.escapeCsv("l1\nl2"))
        assertEquals("\"l1\rl2\"", LogMultiFormatExporter.escapeCsv("l1\rl2"))
        val csv = LogMultiFormatExporter.operation(
            listOf(OperationLogEntity(id = 7, createdAt = 1000, level = "ERROR", category = "a,b", message = "他说\"停\"", detail = "x\ny")),
            "2026-09-13", LogExportFormat.CSV,
        )
        assertTrue(csv.contains(",\"a,b\",\"他说\"\"停\"\"\",\"x\ny\"\r\n"))
    }

    @Test fun htmlEscapesFiveCharsWithAmpersandFirst() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", LogMultiFormatExporter.escapeHtml("&<>\"'"))
        assertEquals("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;", LogMultiFormatExporter.escapeHtml("<script>alert('x')</script>"))
        assertEquals("&amp;lt;", LogMultiFormatExporter.escapeHtml("&lt;"))
        val html = LogMultiFormatExporter.http(listOf(http(responseBody = "<b>A&B</b> \"q\" 'p'")), "2026-09-13", LogExportFormat.HTML)
        assertTrue(html.contains("<meta charset=\"utf-8\">"))
        assertTrue(html.contains("&lt;b&gt;A&amp;B&lt;/b&gt; &quot;q&quot; &#39;p&#39;"))
        assertFalse(html.contains("&amp;lt;"))
        assertFalse(html.contains("&amp;amp;"))
        assertFalse(html.contains("<b>A&B</b>"))
    }

    @Test fun jsonEscapesQuotesBackslashAndControlChars() {
        assertEquals("a\\\"b\\\\c\\nd\\re\\tf\\u0001g\\u001f", LogMultiFormatExporter.escapeJson("a\"b\\c\nd\re\tf\u0001g\u001f"))
        val json = LogMultiFormatExporter.http(
            listOf(http(id = 3, requestBody = "{\"k\":\"v\"}\n\u0000end")),
            "2026-09-13", LogExportFormat.JSON,
        )
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        assertTrue(json.contains("\"id\": 3"))
        assertTrue(json.contains("\"statusCode\": 200"))
        assertTrue(json.contains("\"requestBody\": \"{\\\"k\\\":\\\"v\\\"}\\n\\u0000end\""))
        assertFalse(json.any { it < '\u0020' && it != '\n' })
    }

    @Test fun emptyListDoesNotCrashInAnyFormat() {
        LogExportFormat.entries.forEach { format ->
            val op = LogMultiFormatExporter.operation(emptyList(), "2026-09-13", format)
            val hp = LogMultiFormatExporter.http(emptyList(), "2026-09-13", format)
            assertTrue(op.isNotEmpty())
            assertTrue(hp.isNotEmpty())
        }
        assertEquals("[]", LogMultiFormatExporter.operation(emptyList(), "d", LogExportFormat.JSON))
        assertEquals("\uFEFFid,time,level,category,message,detail\r\n", LogMultiFormatExporter.operation(emptyList(), "d", LogExportFormat.CSV))
        assertTrue(LogMultiFormatExporter.http(emptyList(), "d", LogExportFormat.HTML).contains("</html>"))
        assertTrue(LogMultiFormatExporter.http(emptyList(), "d", LogExportFormat.TEXT).contains("共 0 条"))
    }

    @Test fun redactMasksSecretsOnlyWhenEnabled() {
        val logs = listOf(http(url = "https://a.test/s?token=abc123&q=1", requestHeaders = "Authorization: Bearer sk-secret"))
        val raw = LogMultiFormatExporter.http(logs, "d", LogExportFormat.TEXT, redact = false)
        val safe = LogMultiFormatExporter.http(logs, "d", LogExportFormat.TEXT, redact = true)
        assertTrue(raw.contains("sk-secret"))
        assertTrue(raw.contains("abc123"))
        assertFalse(safe.contains("sk-secret"))
        assertFalse(safe.contains("abc123"))
        assertTrue(safe.contains("authorization=***"))
        assertFalse("脱敏后不得泄漏 URL 主机", safe.contains("a.test"))
        assertTrue(safe.contains("[URL已隐藏]"))
    }

    @Test fun textFormatReusesExistingFormatter() {
        val logs = listOf(OperationLogEntity(id = 1, createdAt = 1000, level = "INFO", category = "mcp", message = "成功"))
        val text = LogMultiFormatExporter.operation(logs, "2026-09-13", LogExportFormat.TEXT)
        assertTrue(text.contains("# 操作日志 2026-09-13 共 1 条"))
        assertTrue(text.contains("[INFO] [mcp] 成功"))
    }

    @Test fun mimeAndExtensionPerFormat() {
        assertEquals("text/plain" to "txt", LogExportFormat.TEXT.mime to LogExportFormat.TEXT.extension)
        assertEquals("application/json" to "json", LogExportFormat.JSON.mime to LogExportFormat.JSON.extension)
        assertEquals("text/csv" to "csv", LogExportFormat.CSV.mime to LogExportFormat.CSV.extension)
        assertEquals("text/html" to "html", LogExportFormat.HTML.mime to LogExportFormat.HTML.extension)
    }

    @Test fun fileNameHasTimestampAndExtension() {
        val name = LogMultiFormatExporter.fileName("HTTP日志", "2026-09-13", LogExportFormat.CSV, now = 0L)
        assertTrue(name, Regex("^HTTP日志_2026-09-13_\\d{8}_\\d{6}\\.csv$").matches(name))
    }

    // ---------- 锚点分组导出 ----------

    private fun httpAnchored(
        id: Long, url: String, sourceAnchor: String? = null,
        requestHeaders: String = "{}", redirectChain: String = "[]", createdAt: Long = id,
    ) = HttpLogEntity(
        id = id, method = "GET", url = url, finalUrl = url, statusCode = 200, durationMs = 5,
        requestHeaders = requestHeaders, responseHeaders = "{}", requestBody = "", responseBody = "",
        error = "", redirectChain = redirectChain, sourceAnchor = sourceAnchor, createdAt = createdAt,
    )

    @Test fun groupedTextPartitionsByAnchorWithEvidenceHeader() {
        val logs = listOf(
            httpAnchored(1, "https://m.ali75.com/x", sourceAnchor = "ali75.com"),
            httpAnchored(2, "https://dm.downshu321.shop/r", requestHeaders = "{\"referer\":\"https://dm.aqxsw66.com/f\"}"),
            httpAnchored(3, "https://unrelated.example/"),
        )
        val text = LogMultiFormatExporter.http(logs, "2026-09-30", LogExportFormat.TEXT, groupByAnchor = true)
        // 锚点小节头 + 归属桶
        assertTrue(text.contains("## ali75.com"))
        assertTrue(text.contains("## aqxsw66.com"))   // Referer 归并
        assertTrue(text.contains("## 未归属"))
        assertTrue(text.contains("置信度"))
        // 小节之间记录不串：aqxsw66 小节只含 Referer 那条
        val aqxswSection = text.substringAfter("## aqxsw66.com").substringBefore("## ")
        assertTrue(aqxswSection.contains("downshu321.shop"))
        assertFalse(aqxswSection.contains("ali75.com"))
    }

    @Test fun groupedJsonCarriesAnchorColumns() {
        val logs = listOf(
            httpAnchored(1, "https://m.ali75.com/x", sourceAnchor = "ali75.com"),
            httpAnchored(2, "https://unrelated.example/"),
        )
        val json = LogMultiFormatExporter.http(logs, "d", LogExportFormat.JSON, groupByAnchor = true)
        assertTrue(json.contains("\"anchor\": \"ali75.com\""))
        assertTrue(json.contains("\"anchor\": \"__unattributed__\""))
        assertTrue(json.contains("\"attribution\""))
        assertTrue(json.contains("\"confidence\""))
    }

    @Test fun groupedExportScalesPast500WithoutTruncation() {
        // >500 条跨两个锚点：分组导出不得截断，每桶数量精确
        val logs = (1..600).map { i ->
            if (i % 2 == 0) httpAnchored(i.toLong(), "https://m.ali75.com/$i", sourceAnchor = "ali75.com", createdAt = i.toLong())
            else httpAnchored(i.toLong(), "https://dm.aqxsw66.com/$i", sourceAnchor = "aqxsw66.com", createdAt = i.toLong())
        }
        val text = LogMultiFormatExporter.http(logs, "d", LogExportFormat.TEXT, groupByAnchor = true)
        assertTrue(text.contains("共 600 条"))
        // 每桶 300 条：通过数条目行校验
        val aliSection = text.substringAfter("## ali75.com").substringBefore("## aqxsw66.com")
        assertEquals(300, Regex("==== #\\d+").findAll(aliSection).count())
        val aqxswSection = text.substringAfter("## aqxsw66.com")
        assertEquals(300, Regex("==== #\\d+").findAll(aqxswSection).count())
    }

    @Test fun oneAnchorPlusUnattributedStillGroups() {
        // UI 口径：归属桶数 >1 即分节——1 个真实锚点 + 未归属桶也算分组，不平铺
        val logs = listOf(
            httpAnchored(1, "https://m.ali75.com/x", sourceAnchor = "ali75.com"),
            httpAnchored(2, "https://unrelated.example/"),
        )
        val text = LogMultiFormatExporter.http(logs, "d", LogExportFormat.TEXT, groupByAnchor = true)
        assertTrue(text.contains("## ali75.com"))
        assertTrue(text.contains("## 未归属"))
    }

    @Test fun explicitCandidateAnchorsAttributeLegacyLogs() {
        // 调用方传入候选锚点时，sourceAnchor=null 但 URL 指向所选域的旧日志必须归并进桶；
        // 不传候选（内部仅从 sourceAnchor 推导）时同一批日志只能进未归属桶。
        val logs = listOf(
            httpAnchored(1, "https://m.ali75.com/x"),          // 无 sourceAnchor，URL 命中候选
            httpAnchored(2, "https://unrelated.example/"),
        )
        val withAnchors = LogMultiFormatExporter.http(logs, "d", LogExportFormat.TEXT, groupByAnchor = true, anchors = listOf("ali75.com"))
        assertTrue(withAnchors.contains("## ali75.com"))
        assertTrue(withAnchors.contains("## 未归属"))
        val aliSection = withAnchors.substringAfter("## ali75.com").substringBefore("## ")
        assertTrue(aliSection.contains("m.ali75.com"))
        // 对照：不传候选时 ali75 没有 sourceAnchor 证据，两组都进未归属
        val without = LogMultiFormatExporter.http(logs, "d", LogExportFormat.TEXT, groupByAnchor = true)
        assertFalse(without.contains("## ali75.com"))
        assertTrue(without.contains("## 未归属"))
    }
}
