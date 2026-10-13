package com.mina.legadostudio.mcp

import com.google.gson.JsonParser
import com.mina.legadostudio.domain.SourceDedupeAnalyzer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun StudioMcpServer.registerBookSourceTools(server: Server) {
    server.tool("save_source", "校验并写入 BookSource JSON。同 bookSourceUrl 同内容幂等覆写最新版本；同 URL 不同 JSON 一律保留为新版本并回执 conflict，绝不静默覆盖（身份键=规范化 URL 全文，不再合并尾部 / 差异）。巨型 @js 规则务必用 fields 分字段传入（如 fields={\"ruleSearch\":{...}}），避免整包 source 手写 JSON 转义漏字符；解析失败会回传错误列附近源码片段。", ToolSchema(properties = buildJsonObject {
        put("source", stringProp("BookSource JSON（与 fields 至少传一个）。合并语义：fields 只在顶层按键覆盖——同名顶层键整个被 fields 值替换（含 null=删除该键），不递归、不合并子键；source 里没被点名的顶层键原样保留"))
        putJsonObject("newVersion") { put("type", "boolean"); put("description", "true 时强制追加新版本（同内容时不产生空副本）；默认 false：同内容覆写、不同内容也保留为新版本（不静默覆盖）") }
        putJsonObject("fields") {
            put("type", "object")
            put("description", "可选：分字段覆盖，键为书源顶层字段（bookSourceName/bookSourceUrl/searchUrl/ruleSearch/ruleToc/ruleContent/header 等）。**顶层键整体覆盖**——同名键被替换为 fields 里的值（不递归合并子键），传 null 即删除该顶层键；未点名的键保留。合并后整体校验保存；适合逐字段修补、免去整包转义")
        }
    }, required = emptyList()), toolAnnotations = write) { req ->
        runCatching {
            val sourceText = req.arguments.str("source").orEmpty().ifBlank { "{}" }
            val fieldsValue = req.arguments?.get("fields")
            require(fieldsValue == null || fieldsValue is JsonObject) {
                "fields 需要 JSON 对象（type: object），收到 ${jsonType(fieldsValue!!)}"
            }
            val fields = fieldsValue?.let { app.gson.fromJson(it.toString(), com.google.gson.JsonObject::class.java) }
            require(sourceText != "{}" || fields != null) { "source 不能为空（或改用 fields 分字段传入）" }
            // 解析失败走 validator 的错误提示路径：多行 JSON 给出行列定位的源码片段，不是只回一句「不是合法 JSON」
            val fieldsKeys = fields?.keySet()?.sorted().orEmpty()
            val fieldsSuffix = fieldsKeys.takeIf { it.isNotEmpty() }?.let { "（本次收到 fields 键：${app.gson.toJson(it)}）" }.orEmpty()
            val obj = runCatching { JsonParser.parseString(sourceText).asJsonObject }.getOrNull()
                ?: error((app.validator.validate(sourceText).issues.firstOrNull()?.let { "source 不是合法 JSON 对象：${it.message}" }
                    ?: "source 不是合法 JSON 对象") + fieldsSuffix)
            // fields 顶层键整体覆盖，null 值表示删除该顶层键（obj.remove，与工具描述一致）——
            // 不是写入 null（写入后 has(key) 仍为 true，会让下游以为字段还在）
            fields?.let { McpSourceTools.mergeFieldsInto(obj, it) }
            // 校验失败时回显本次实际收到的字段名：source 顶层键 + fields 键，
            // 帮 AI 发现自己字段名拼错/漏传（如把 ruleSearch 写成 ruleSerach）
            val receivedFieldsSuffix = "（本次收到 source 顶层键：${app.gson.toJson(obj.keySet().sorted())}；" +
                "fields 键：${app.gson.toJson(fieldsKeys)}）"
            val newVersion = req.arguments.bool("newVersion") ?: false
            val report = app.validator.validate(app.gson.toJson(obj))
            require(report.isValid) { "书源验证未通过：${app.gson.toJson(report.issues)}$receivedFieldsSuffix" }
            // 缺省时按用户在 MCP 页选择的目标类型写入 bookSourceType(0 文本/1 音频/2 图片/3 文件/4 视频)；「自动」时不干预，保留 JSON 原样
            if (app.runtimeConfig.bookSourceType >= 0 && (!obj.has("bookSourceType") || obj.get("bookSourceType").isJsonNull)) obj.addProperty("bookSourceType", app.runtimeConfig.bookSourceType)
            // 落库决策集中在 McpSourceTools.decideSave：身份 = 规范化 bookSourceUrl 全文（保留尾部/与 #标签），
            // 同 URL 不同 JSON → 新版本；完全一致 → 幂等覆写。trimEnd('/') 归并已移除——尾部有/无是两个身份。
            val now = System.currentTimeMillis()
            val decision = McpSourceTools.decideSave(obj, newVersion, app.projects.list(), now)
            val saved = app.projects.save(decision.project)
            ok(app.gson.toJson(linkedMapOf<String, Any?>(
                "project" to saved,
                "identityKey" to decision.identityKey,
                "note" to decision.note,
                "conflicts" to decision.conflicts.takeIf { it.isNotEmpty() },
                // stage 命名须如实：VALIDATED=静态校验通过，不代表已联网实测——联网实测请以 check_source 的 verdict 为准
                "stageMeaning" to "VALIDATED=静态校验通过（未联网）；联网实测结果用 check_source 的 verdict/evidence 字段判定",
            )))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("list_sources", "列出已保存 BookSource 摘要（默认不做任何折叠/合并——同 URL 多版本逐条列出，按 updatedAt 倒序；用 analyze_sources 看重复簇）。latestOnly=true 时每个 bookSourceUrl 只回最新一条代表并附 versionCount，查历史版本去掉该参数；清旧版本用 archive_source_versions。", ToolSchema(properties = buildJsonObject {
        putJsonObject("limit") { put("type", "integer"); put("description", "默认 200，最大 1000；超过用 offset 翻页") }
        putJsonObject("offset") { put("type", "integer"); put("description", "分页偏移") }
        putJsonObject("collapseIdentical") { put("type", "boolean"); put("description", "true=仅折叠结构完全相同且零冲突的重复副本（同 dedupe export_one 口径）；默认 false=全列出") }
        putJsonObject("latestOnly") { put("type", "boolean"); put("description", "true=每个 bookSourceUrl 只回 updatedAt 最新的一条（附 versionCount）；默认 false=同 URL 多版本逐条全列") }
    }, required = emptyList()), toolAnnotations = readOnly) { req ->
        runCatching {
            val all = app.projects.list().sortedByDescending { it.updatedAt }
            val limit = (req.arguments.int("limit") ?: McpSourceTools.DEFAULT_LIST_LIMIT).coerceIn(1, McpSourceTools.MAX_LIST_LIMIT)
            val offset = (req.arguments.int("offset") ?: 0).coerceAtLeast(0)
            val collapse = req.arguments.bool("collapseIdentical") == true
            val latestOnly = req.arguments.bool("latestOnly") == true
            require(!(collapse && latestOnly)) { "collapseIdentical 与 latestOnly 只能选一个：前者按内容全等折叠，后者按 URL 取最新代表" }
            val items = when {
                latestOnly -> McpSourceTools.latestOnly(all)
                !collapse -> all
                else -> {
                    // 折叠口径与 dedupe 完全一致：只收 action=export_one 簇的 keep 代表 + 非簇成员
                    val report = SourceDedupeAnalyzer.analyzeRows(all.map { McpSourceTools.rowOf(it) })
                    val keepIds = report.clusters.filter { it.action == SourceDedupeAnalyzer.ACTION_EXPORT_ONE }
                        .mapNotNull { it.keepProjectId.takeIf { k -> k.isNotBlank() } }.toSet()
                    val clustered = report.clusters.flatMap { it.memberIds }.toSet()
                    all.filter { it.id !in clustered || it.id in keepIds }
                }
            }
            val counts = McpSourceTools.versionCounts(all)
            val page = items.drop(offset).take(limit)
            ok(app.gson.toJson(linkedMapOf<String, Any?>(
                "total" to items.size, "offset" to offset, "limit" to limit,
                "hasMore" to (offset + page.size < items.size),
                "collapseIdentical" to collapse,
                "latestOnly" to latestOnly,
                "collapsedAway" to (all.size - items.size),
                "items" to page.map {
                    linkedMapOf(
                        "projectId" to it.id,
                        "bookSourceName" to it.name,
                        "bookSourceUrl" to it.siteUrl,
                        "stage" to it.stage,
                        "createdAt" to it.createdAt,
                        "updatedAt" to it.updatedAt,
                        "versionCount" to (counts[it.siteUrl.trim()] ?: 1),
                    )
                },
                "hint" to "同 URL 多版本全部列出（不做静默合并）；重复候选簇/冲突判定用 analyze_sources；latestOnly=true 只看每 URL 最新一条；archive_source_versions 按 URL 清理旧版本（保留最新 N 条）；siteUrl 逐字符精确匹配，不做 trimEnd('/') 归并",
            )))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("archive_source_versions", "按 bookSourceUrl 清理同 URL 旧版本：逐字符精确匹配该 URL 的全部项目，按 updatedAt 倒序保留最新 keepLatest 条（默认 1），其余从项目库删除。版本折叠/冲突判定不在此做——先看 list_sources(versionCount)/analyze_sources 确认该 URL 的版本簇再删；删除不可恢复。", ToolSchema(properties = buildJsonObject {
        put("bookSourceUrl", stringProp("要清理的书源 URL（逐字符精确匹配 siteUrl，含尾部 / 与 #标签）"))
        putJsonObject("keepLatest") { put("type", "integer"); put("description", "保留最新几条，默认 1；最小 1") }
        putJsonObject("dryRun") { put("type", "boolean"); put("description", "true=只返回将删清单不真删（建议先 dryRun 确认）") }
    }, required = listOf("bookSourceUrl")), toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false)) { req ->
        runCatching {
            val url = req.arguments.str("bookSourceUrl") ?: return@tool err("bookSourceUrl 不能为空")
            val keepLatest = (req.arguments.int("keepLatest") ?: 1).coerceAtLeast(1)
            val dryRun = req.arguments.bool("dryRun") == true
            val (group, keep, toDelete) = McpSourceTools.decideArchive(app.projects.list(), url, keepLatest)
            if (group.isEmpty()) return@tool err("未找到 bookSourceUrl=\"$url\" 的书源版本（siteUrl 逐字符精确匹配；先用 list_sources 确认完整 URL）")
            val deleted = if (dryRun) 0 else app.projects.delete(toDelete.map { it.id })
            ok(app.gson.toJson(linkedMapOf<String, Any?>(
                "archived" to !dryRun,
                "dryRun" to dryRun,
                "bookSourceUrl" to url,
                "matchedVersions" to group.size,
                "keepLatest" to keepLatest,
                "kept" to keep.map { mapOf("projectId" to it.id, "updatedAt" to it.updatedAt, "stage" to it.stage) },
                "deletedIds" to toDelete.map { it.id },
                "deletedCount" to (if (dryRun) 0 else deleted),
                "hint" to if (dryRun) "dryRun：以上 deletedIds 是将要删除的版本；确认无误后去掉 dryRun 重新调用执行删除。"
                    else if (toDelete.isEmpty()) "该 URL 只有 ${group.size} 个版本，未超过 keepLatest=$keepLatest，无需删除。"
                    else "已删除 ${toDelete.size} 个旧版本，保留最新 ${keep.size} 条；删除不可恢复。",
            )))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("get_source", "按项目 ID 精确读取指定版本；也可传 bookSourceUrl 精确匹配（区分大小写/尾部/与 #标签，任一差异视为不同版本——不再归并）。同 URL 命中多版本时不默认返回任一版本——回 projectId 列表请你按 ID 点名（先 list_sources 看清单）。", schema(mapOf("key" to "项目 ID（精确）；或完整书源 URL（逐字符精确匹配，含尾部/与 #标签）", "bookSourceUrl" to "可选别名：完整书源 URL（未传 key 时使用，同样逐字符精确匹配）"), emptyList()), toolAnnotations = readOnly) { req ->
        val key = req.arguments.str("key") ?: req.arguments.str("bookSourceUrl") ?: return@tool err("key 或 bookSourceUrl 不能为空")
        // ID 精确优先；URL 匹配恰一条才返回——多条时不静默挑 firstOrNull（同 URL 多版本读哪条不确定，会拿错内容）
        val matches = app.projects.list().filter { it.siteUrl.trim() == key.trim() }
        when (val r = McpSourceTools.resolveGetSource(app.projects.get(key), matches)) {
            is McpSourceTools.SourceLookup.Found -> ok(r.project.sourceJson)
            is McpSourceTools.SourceLookup.Ambiguous -> err(
                "URL \"$key\" 命中 ${r.projectIds.size} 个版本，不返回任一以免拿错；" +
                    "请按 ID 点名：ids=${app.gson.toJson(r.projectIds)}（先 list_sources 看完整清单再选）")
            McpSourceTools.SourceLookup.NotFound ->
                err("未找到书源（ID 与 URL 均为精确匹配；同 URL 多版本请先用 list_sources 拿到具体 projectId）")
        }
    }
    server.tool("validate_source", "验证 BookSource JSON", schema(mapOf("source" to "BookSource JSON"), listOf("source")), toolAnnotations = readOnly) { req ->
        ok(app.gson.toJson(app.validator.validate(req.arguments.str("source").orEmpty())))
    }
    server.tool("export_source", "校验并返回项目中的 BookSource JSON", schema(mapOf("projectId" to "项目 ID"), listOf("projectId")), toolAnnotations = readOnly) { req ->
        val project = app.projects.get(req.arguments.str("projectId") ?: return@tool err("projectId 不能为空"))
            ?: return@tool err("项目不存在")
        val report = app.validator.validate(project.sourceJson)
        if (report.isValid) ok(project.sourceJson) else err("书源验证未通过：${app.gson.toJson(report.issues)}")
    }
}
