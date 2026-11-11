package com.mina.legadostudio.web

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.data.db.ProjectEntity
import com.mina.legadostudio.domain.CheckSourceRunner
import com.mina.legadostudio.domain.SourceDedupeAnalyzer
import com.mina.legadostudio.domain.SourceFilterPolicy
import com.mina.legadostudio.export.ReaderImport
import com.mina.legadostudio.mcp.McpSourceTools
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Activity-scoped UI operations. No MCP registration/semantics changes. Host must route sources.* here. */
internal class SourceManagementModule(
    private val activity: Activity,
    private val saveText: suspend (JsonObject) -> JsonElement,
) {
    private val app = activity.applicationContext as StudioApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private data class Snapshot(val token: String, val rows: List<ProjectEntity>, val excludes: Set<SourceFilterPolicy.Exclude>, val collapse: Boolean, val selection: List<String>, val created: Long)
    private var snapshot: Snapshot? = null
    private data class Check(val id: String, val projectId: String, var state: String = "running", var job: Job? = null, var error: String? = null)
    private var check: Check? = null
    private fun obj(vararg entries: Pair<String, Any?>): JsonObject = app.gson.toJsonTree(linkedMapOf(*entries)).asJsonObject
    private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: error("缺少 $key")
    private fun JsonObject.flag(key: String): Boolean {
        val v = get(key) ?: return false
        require(v.isJsonPrimitive && v.asJsonPrimitive.isBoolean) { "$key 必须为布尔值" }
        return v.asBoolean
    }
    suspend fun dispatch(method: String, params: JsonObject): JsonElement = mutex.withLock {
        when (method) {
            "sources.analyze" -> analyze(params)
            "sources.deliver" -> deliver(params)
            "sources.delete" -> delete(params)
            "sources.checkStart" -> start(params)
            "sources.checkStatus" -> status(params)
            "sources.checkCancel" -> {
                val c = currentCheck(params)
                if (c.state == "running") { c.state = "cancelling"; c.job?.cancel() }
                status(params)
            }
            else -> error("未知书源管理方法")
        }
    }
    fun close() { scope.cancel(); snapshot = null }
    private fun plan(rows: List<ProjectEntity>, excludes: Set<SourceFilterPolicy.Exclude>, collapse: Boolean): Pair<SourceFilterPolicy.Report, List<ProjectEntity>> {
        val trusted = McpSourceTools.trustedCheckResults(rows.map { it.sourceJson }, System.currentTimeMillis())
        val filtered = SourceFilterPolicy.filter(rows.map { it.sourceJson }, trusted, excludes)
        val dedupe = SourceDedupeAnalyzer.analyze(rows)
        val conflicts = dedupe.conflicts.flatMap { it.projectIds }.toSet()
        val kept = filtered.kept.map { rows[it.index].id }.toSet() + conflicts
        val dropped = if (collapse) dedupe.clusters.filter { it.action == SourceDedupeAnalyzer.ACTION_EXPORT_ONE && it.memberIds.none { id -> id in conflicts } }
            .flatMap { it.memberIds.filter { id -> id != it.keepProjectId } }.toSet() else emptySet()
        return filtered to rows.filter { it.id in kept && it.id !in dropped }
    }
    private fun domain(row: ProjectEntity): String = SourceDedupeAnalyzer.identify(row.id, row.siteUrl, row.sourceJson).identity?.host?.takeIf { it.isNotBlank() } ?: "（无可识别域名）"
    private suspend fun analyze(p: JsonObject): JsonObject {
        val excludesObject = p.getAsJsonObject("exclude") ?: JsonObject()
        val excludes = SourceFilterPolicy.Exclude.values().filter { excludesObject.flag(it.arg) }.toSet()
        val collapse = p.flag("collapseIdentical")
        val rows = app.projects.list()
        val (filter, selected) = plan(rows, excludes, collapse)
        val report = SourceDedupeAnalyzer.analyze(rows)
        val conflicts = report.conflicts.flatMap { it.projectIds }.toSet()
        val keptIndices = filter.kept.map { it.index }.toSet() + rows.indices.filter { rows[it].id in conflicts }
        val base = McpSourceTools.filterPayload(filter)
        val kept = (base["kept"] as List<*>) + filter.excluded.filter { rows[it.index].id in conflicts }.map { obj("index" to it.index, "projectId" to rows[it.index].id, "reasons" to listOf("冲突保护：始终保留")) }
        val excluded = (base["excluded"] as List<*>).filterIndexed { i, _ -> rows[filter.excluded[i].index].id !in conflicts }
        val token = UUID.randomUUID().toString()
        snapshot = Snapshot(token, rows, excludes, collapse, selected.map { it.id }, System.currentTimeMillis())
        val payload = McpSourceTools.reportPayload(report, emptyMap())
        return obj("token" to token, "total" to rows.size, "keptCount" to keptIndices.size,
            "excludedCount" to rows.size - keptIndices.size, "exportCount" to selected.size,
            "kept" to kept, "excluded" to excluded, "clusters" to payload["clusters"], "conflicts" to payload["conflicts"],
            "groups" to rows.groupBy(::domain).map { (domain, members) -> obj("domain" to domain, "ids" to members.map { it.id }) })
    }
    private suspend fun current(p: JsonObject): Snapshot {
        val s = snapshot ?: error("请重新分析全库")
        require(p.str("token") == s.token) { "分析快照已失效" }
        require(System.currentTimeMillis() - s.created < 60_000) { "分析快照已过期，请重新分析" }
        require(app.projects.list().associateBy { it.id } == s.rows.associateBy { it.id }) { "书源库已变化，请重新分析" }
        require(plan(s.rows, s.excludes, s.collapse).second.map { it.id } == s.selection) { "可信证据已变化或过期，请重新分析" }
        return s
    }
    private fun array(rows: List<String>): String {
        require(rows.isNotEmpty()) { "没有可导出的书源" }
        rows.forEach {
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(it)).apply {
                strictness = com.google.gson.Strictness.STRICT
            }
            reader.use {
                require(JsonParser.parseReader(reader).isJsonObject && reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT) {
                    "书源不是合法 JSON 对象，无法导出"
                }
            }
        }
        return rows.joinToString(",\n", "[\n", "\n]")
    }
    private suspend fun deliver(p: JsonObject): JsonElement {
        val text = if (p.has("source")) array(listOf(p.str("source"))) else {
            val s = current(p); array(s.rows.filter { it.id in s.selection }.map { it.sourceJson })
        }
        return when (p.str("action")) {
            "targets" -> obj("targets" to ReaderImport.resolve(activity).map { obj("packageName" to it.packageName, "label" to it.label, "known" to it.known) })
            "reader" -> {
                val pkg = p.str("packageName")
                require(ReaderImport.resolve(activity).any { it.packageName == pkg }) { "阅读客户端不存在或不能处理导入" }
                withContext(Dispatchers.Main) {
                    // ReaderImport.launch normalizes JSON; publish our validated raw array directly.
                    val url = com.mina.legadostudio.export.ReaderImportService.prepare(activity, text, ReaderImport.TTL_MS)
                    try {
                        activity.startActivity(com.mina.legadostudio.export.ReaderCatalog.importIntent(pkg, url))
                        ReaderImport.remember(activity, pkg)
                    } catch (e: Exception) {
                        com.mina.legadostudio.export.ReaderImportService.cancel(activity, url)
                        throw e
                    }
                }
                obj("launched" to true, "confirmed" to false, "confirmation" to "pending_user", "packageName" to pkg)
            }
            "copy" -> withContext(Dispatchers.Main) {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("书源 JSON", text))
                require(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text) { "剪贴板回读不一致" }
                obj("copied" to true)
            }
            "share" -> {
                val dir = File(activity.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, "sources-${UUID.randomUUID()}.json").apply { writeText(text, Charsets.UTF_8) }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("书源 JSON", uri) }
                    activity.startActivity(Intent.createChooser(intent, "分享书源 JSON"))
                }
                obj("launched" to true)
            }
            "save" -> saveText(obj("name" to "book-sources.json", "text" to text, "mime" to "application/json"))
            else -> error("未知导出操作")
        }
    }
    private suspend fun delete(p: JsonObject): JsonObject {
        require(check?.state !in setOf("running", "cancelling")) { "检测运行或取消结算期间不能删除" }
        val s = snapshot ?: error("请重新分析全库")
        require(p.str("token") == s.token) { "分析快照已失效" }
        require(System.currentTimeMillis() - s.created < 60_000) { "分析快照已过期，请重新分析" }
        val all = when (p.str("scope")) {
            "all" -> true
            "domain" -> false
            else -> error("未知删除范围")
        }
        val expectedConfirmation = if (all) "清空全库" else p.str("domain")
        require(p.str("confirmation") == expectedConfirmation) { "删除确认文字不一致" }
        val ids = s.rows.filter { all || domain(it) == expectedConfirmation }.map { it.id }
        require(ids.isNotEmpty()) { "确认范围没有书源，请重新分析" }
        // Do NOT use current()+delete(ids): only the DAO transaction can exclude all writers.
        val count = app.projects.deleteFromSnapshot(s.rows, ids)
        snapshot = null
        // Writes after commit are allowed. Report them honestly, never perform a second DELETE.
        val remaining = app.projects.list()
        val absent = remaining.none { it.id in ids }
        return JsonObject().apply {
            addProperty("verified", absent)
            addProperty("deletedCount", count)
            add("deletedIds", JsonArray().apply { ids.forEach { add(it) } })
            addProperty("remainingCount", remaining.size)
            addProperty("allCleared", all && remaining.isEmpty())
            addProperty("scope", if (all) "all" else "domain")
        }
    }
    private fun currentCheck(p: JsonObject): Check = check?.takeIf { it.id == p.str("id") } ?: error("检测任务不存在")
    private fun status(p: JsonObject): JsonObject = currentCheck(p).let { obj("id" to it.id, "projectId" to it.projectId, "state" to it.state, "error" to it.error) }
    private suspend fun start(p: JsonObject): JsonObject {
        require(check?.state !in setOf("running", "cancelling")) { "已有检测运行中" }
        val row = app.projects.get(p.str("projectId")) ?: error("书源不存在")
        val c = Check(UUID.randomUUID().toString(), row.id)
        check = c; snapshot = null
        c.job = scope.launch {
            try {
                val results = CheckSourceRunner.run(CheckSourceRunner.Request(row.sourceJson, refresh = true, auto = true), app.runtime, null)
                ensureActive()
                mutex.withLock {
                    ensureActive()
                    // Never register cancelled or changed-source evidence.
                    require(app.projects.get(row.id)?.sourceJson == row.sourceJson) { "书源已变化，本次证据失效" }
                    McpSourceTools.CheckEvidenceRegistry.register(row.sourceJson, results, true, System.currentTimeMillis())
                    c.state = "completed"
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { mutex.withLock { c.state = "cancelled" } }
            } catch (e: Exception) { mutex.withLock { c.state = "failed"; c.error = "检测失败，请检查书源和网络后重试" } }
        }
        c.job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) scope.launch {
                mutex.withLock { if (c.state == "running" || c.state == "cancelling") c.state = "cancelled" }
            }
        }
        return obj("id" to c.id, "projectId" to c.projectId, "state" to c.state)
    }
}
