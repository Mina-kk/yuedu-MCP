package com.mina.legadostudio.web

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.domain.LogExportFormat
import com.mina.legadostudio.domain.LogMultiFormatExporter
import com.mina.legadostudio.domain.HttpLogAttributor
import com.mina.legadostudio.domain.HttpLogExportPlan
import com.mina.legadostudio.domain.ContextAnchorRegistry
import com.mina.legadostudio.export.CaptureSessionExporter
import com.mina.legadostudio.diagnostic.LogRedactor
import com.mina.legadostudio.network.CaptureActivity
import com.mina.legadostudio.verification.WebViewCapture
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.Writer

/** Trusted local routes. Call on Dispatchers.IO. Does not delete captures or bypass their activity barrier. */
class LogManagementBridge(private val app: StudioApplication) {
    private val dao get() = app.database.dao()
    private fun JsonObject.string(key: String): String = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
    private fun ids(p: JsonObject): List<String> = p.getAsJsonArray("ids")?.map {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "ID 必须为字符串" }; it.asString
    }?.also { require(it.isNotEmpty() && it.size <= 100 && it.distinct().size == it.size) }.orEmpty()

    suspend fun list(p: JsonObject): JsonObject {
        require(p.string("kind") == "operation")
        val window = p.string("date").takeIf { it.isNotEmpty() }?.let { LogManagementRules.dayWindow(it) }
        val rows = dao.latestOperationLogs(Int.MAX_VALUE).filter { r ->
            (window == null || r.createdAt in window.first..window.second) &&
                (p.string("level").isEmpty() || r.level.equals(p.string("level"), true)) &&
                (p.string("category").isEmpty() || r.category.equals(p.string("category"), true)) &&
                (p.string("query").isEmpty() || r.message.contains(p.string("query"), true) || r.detail.contains(p.string("query"), true))
        }
        val offset = p.get("offset")?.asInt ?: 0
        val limit = p.get("limit")?.asInt ?: 20
        require(offset >= 0 && limit in 1..200)
        return JsonObject().apply {
            addProperty("total", rows.size)
            add("items", JsonArray().apply { rows.drop(offset).take(limit).forEach { r -> add(JsonObject().apply {
                addProperty("id", r.id.toString()); addProperty("createdAt", r.createdAt)
                addProperty("level", r.level); addProperty("category", r.category)
                addProperty("message", r.message); addProperty("detail", r.detail)
            }) } })
        }
    }

    suspend fun day(p: JsonObject, delete: Boolean): JsonObject {
        val kind = p.string("kind"); require(kind in setOf("operation", "http"))
        val date = p.string("date"); val (start, end) = LogManagementRules.dayWindow(date)
        suspend fun count() = if (kind == "operation") dao.countOperationLogsByDay(start, end) else dao.countHttpLogsByDayNoCapture(start, end)
        if (!delete) return JsonObject().apply { addProperty("kind", kind); addProperty("date", date); addProperty("count", count()) }
        require(p.get("confirmed")?.asBoolean == true) { "需要整日删除确认" }
        val deleted = if (kind == "operation") dao.deleteOperationLogsByDay(start, end) else dao.deleteHttpLogsByDayNoCapture(start, end)
        return JsonObject().apply {
            addProperty("kind", kind); addProperty("date", date); addProperty("deleted", deleted)
            addProperty("remaining", count()) // concurrent new rows are reported, never claimed deleted
        }
    }

    class PreparedLogExport(val file: File, val name: String, val mime: String, val count: Int,
        val resources: CaptureSessionExporter.ResourceReport) : AutoCloseable {
        fun receipt() = JsonObject().apply {
            addProperty("count", count); addProperty("partial", resources.missingResources > 0)
            addProperty("missingResources", resources.missingResources)
            addProperty("omittedResources", resources.omittedResources)
        }
        override fun close() { file.delete() }
    }

    suspend fun export(p: JsonObject, save: suspend (File, String, String) -> Unit): JsonObject {
        val prepared = prepare(p)
        try {
            save(prepared.file, prepared.name, prepared.mime)
            return prepared.receipt().apply { addProperty("saved", true) }
        } finally { prepared.close() }
    }

    suspend fun prepare(p: JsonObject): PreparedLogExport {
        val dir = File(app.cacheDir, "exports/log-share").apply { check(isDirectory || mkdirs()) }
        val file = File.createTempFile("logs-", ".tmp", dir)
        try {
            return file.bufferedWriter().use { out -> write(p, file, out) }
        } catch (failure: Throwable) { file.delete(); throw failure }
    }

    private suspend fun write(p: JsonObject, file: File, out: Writer): PreparedLogExport {
        val kind = p.string("kind"); val scope = p.string("scope")
        require(kind in setOf("operation", "http", "capture", "crash", "diagnostic"))
        require(scope in setOf("single", "selected", "day", "all", "anchor"))
        val format = LogExportFormat.valueOf(p.string("format"))
        require(p.get("redact")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } == true)
        val redact = p.get("redact").asBoolean
        val chosen = if (scope in setOf("single", "selected")) ids(p).also { require(it.isNotEmpty()); if (scope == "single") require(it.size == 1) } else emptyList()
        val window = if (scope == "day") LogManagementRules.dayWindow(p.string("date")) else null
        require(scope != "day" || kind in setOf("operation", "http"))
        require(scope != "anchor" || kind == "http")
        var count: Int
        val resources = CaptureSessionExporter.ResourceReport()
        val text = when (kind) {
            "operation" -> {
                val rows = if (chosen.isNotEmpty()) dao.operationLogsByIds(chosen.map(LogManagementRules::exactId)) else dao.latestOperationLogs(Int.MAX_VALUE)
                if (chosen.isNotEmpty()) LogManagementRules.requireComplete(chosen.size, rows.size)
                val filtered = rows.filter { window == null || it.createdAt in window.first..window.second }
                count = filtered.size
                LogMultiFormatExporter.operation(filtered, p.string("date").ifEmpty { "all" }, format, redact)
            }
            "http" -> {
                val all = if (chosen.isNotEmpty()) dao.httpLogsByIds(chosen.map(LogManagementRules::exactId)) else {
                    val rows = mutableListOf<com.mina.legadostudio.data.db.HttpLogEntity>()
                    var beforeId = Long.MAX_VALUE
                    while (true) {
                        val page = dao.httpLogSummariesByDayBeforeIdNoCapture(window?.first ?: Long.MIN_VALUE, window?.second ?: Long.MAX_VALUE, beforeId, 200)
                        if (page.isEmpty()) break
                        // Keyset avoids offset drift from new rows. Read bodies one at a time
                        // rather than exceeding Android CursorWindow with 200 large bodies.
                        for (summary in page) rows += dao.httpLog(summary.id) ?: error("日志已变化，请重试")
                        beforeId = page.last().id
                        require(rows.size <= HttpLogExportPlan.MAX_ROWS) { "导出过大，请缩小范围" }
                    }
                    rows
                }
                if (chosen.isNotEmpty()) LogManagementRules.requireComplete(chosen.size, all.size)
                val anchor = if (scope == "anchor") p.string("anchor").also { require(it.isNotBlank()) } else null
                val normalized = anchor?.let { if (it in setOf(HttpLogAttributor.UNATTRIBUTED_KEY, HttpLogAttributor.INFRASTRUCTURE_ANCHOR)) it else HttpLogAttributor.normalizeAnchor(it) ?: error("锚点无效") }
                val candidates = (all.mapNotNull { it.sourceAnchor } + all.mapNotNull { HttpLogAttributor.refererDomain(it) } + listOfNotNull(normalized)).distinct()
                val rows = all.filter { HttpLogExportPlan.inAnchorBucket(HttpLogAttributor.attribute(it, candidates, ContextAnchorRegistry.snapshot()), normalized) }
                count = rows.size
                LogMultiFormatExporter.http(rows, p.string("date").ifEmpty { "all" }, format, redact, anchors = candidates)
            }
            "capture" -> {
                require(format == LogExportFormat.JSON) { "完整抓包（含资源）仅支持 JSON" }
                val contexts = if (chosen.isNotEmpty()) chosen else {
                    val result = mutableListOf<String>(); var offset = 0
                    while (true) { val page = dao.captureSessionSummaries(200, offset); if (page.isEmpty()) break; result += page.map { it.contextId }; offset += page.size }
                    result
                }
                out.write("["); count = 0
                var first = true
                for (context in contexts) {
                    require(Regex("cap:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(context))
                    // Reuse the reservation boundary: no producer may register or write during this snapshot.
                    check(CaptureActivity.deleteIfInactive(context) {
                        val total = dao.countHttpLogsByContextId(context); require(total > 0) { "抓包不存在" }
                        if (!first) out.write(",")
                        first = false
                        val actual = CaptureSessionExporter.writeSessionJson(out, context, System.currentTimeMillis(), total,
                            page = { offset -> dao.httpLogsByContextIdPage(context, 50, offset).map { if (redact) LogMultiFormatExporter.redactHttp(it) else it } },
                            fileReader = { path -> WebViewCapture.captureResourcePath(app.cacheDir, context, path)?.takeIf { it.isFile }?.readBytes() },
                            omitResources = redact, report = resources)
                        LogManagementRules.requireComplete(total, actual); count += actual
                    }) { "活跃抓包尚未结算，请结束后导出" }
                }
                out.write("]")
                ""
            }
            else -> {
                require(format in setOf(LogExportFormat.JSON, LogExportFormat.TEXT)) { "崩溃/诊断支持 JSON 或 TEXT" }
                val names = if (chosen.isNotEmpty()) chosen else if (kind == "crash") app.crashLogs.list().map { it.name } else dao.observeDiagnosticSnapshots().first().map { it.id }
                val documents = names.map { id ->
                    val content = if (kind == "crash") {
                        require(Regex("(?:crash|fault)-[0-9]{1,19}\\.txt").matches(id)); app.crashLogs.read(id)
                    } else app.snapshots.read(dao.diagnosticSnapshot(id) ?: error("诊断不存在"))
                    id to if (redact) LogRedactor.redact(content) else content
                }
                count = documents.size
                if (format == LogExportFormat.TEXT) documents.joinToString("\n\n") { (id, body) -> "# $id\n$body" }
                else JsonArray().apply { documents.forEach { (id, body) -> add(JsonObject().apply { addProperty("id", id); addProperty("body", body) }) } }.toString()
            }
        }
        out.write(text)
        return PreparedLogExport(file, "logs-$kind-${System.currentTimeMillis()}.${format.extension}", format.mime, count, resources)
    }
}
