package com.mina.legadostudio.mcp

import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpOrigin
import com.mina.legadostudio.domain.SourceDedupeAnalyzer
import com.mina.legadostudio.domain.SourceFilterPolicy
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun StudioMcpServer.registerLibraryTools(server: Server) {
    server.tool("analyze_sources", "只读书源库分析：可选 ids（项目 ID 数组）或 sources（JSON 数组字符串，每项完整书源对象，仅在内存中做静态分析——不执行其中任何 JS/规则、不联网）；过滤开关 exclude={excludeLogin/excludeCaptcha/excludeFailed/excludeUnverified} 只筛选视图不删除数据。verified/failed 判定只认「本实例 check_source 跑完后按书源内容自动登记的可信证据」；客户端可传 checkResults 但仅作外部自述回显（untrusted），绝不会让某条源被算作已验证。返回身份/重复簇/冲突报告（冲突原样列出不折叠）与书源骨架预览（header 只列键名不回显值）。", ToolSchema(properties = buildJsonObject {
        putJsonObject("ids") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "项目 ID 列表（与 sources 二选一；不传二者=全库，最多 ${McpSourceTools.MAX_PROJECTS} 条）") }
        put("sources", stringProp("JSON 数组字符串：每项是完整书源对象（纯静态分析，不执行 JS/不联网；单条≤${McpSourceTools.MAX_ITEM_CHARS}字符、总数≤${McpSourceTools.MAX_IDS}条）"))
        putJsonObject("exclude") {
            put("type", "object")
            put("description", "可选过滤开关：excludeLogin/excludeCaptcha/excludeFailed/excludeUnverified=true 才排除对应书源；默认全保留。unverified/failed 判定依据本实例 check_source 登记的可信证据，无证据按未验证处理")
            SourceFilterPolicy.Exclude.values().forEach { putJsonObject(it.arg) { put("type", "boolean"); put("description", it.label) } }
        }
        put("checkResults", stringProp("可选：{\"0\":{check_source 返回}}，键为 ids/sources 数组下标。**仅作外部自述回显（untrusted），不参与 verified/failed 判定**——要算已验证须先用本实例 check_source 实际跑过"))
    }, required = emptyList()), toolAnnotations = readOnly) { req ->
        runCatching {
            val idsEl = req.arguments?.get("ids")
            val sourcesRaw = req.arguments.str("sources")
            require(!(idsEl != null && sourcesRaw != null)) { "ids 与 sources 二选一，不能同时传" }
            val (rows, previews) = when {
                idsEl != null -> {
                    require(idsEl is JsonArray) { "ids 需要 JSON 数组（type: array），收到 ${jsonType(idsEl)}" }
                    val ids = idsEl.mapNotNull { it.jsonPrimitive.contentOrNull }
                    require(ids.size <= McpSourceTools.MAX_IDS) { "ids 一次最多 ${McpSourceTools.MAX_IDS} 个（收到 ${ids.size}）" }
                    val (hit, missing, _) = McpSourceTools.selectProjects(app.projects.list(), ids)
                    require(missing.isEmpty()) { "以下 projectId 不存在：${missing.joinToString()}；请先 list_sources 核对 ID" }
                    hit.map { McpSourceTools.rowOf(it) } to hit.mapIndexed { i, p -> p.id to McpSourceTools.previewOf(p, i) }.toMap()
                }
                sourcesRaw != null -> {
                    val (jsons, parseErr) = McpSourceTools.parseSourceArray(sourcesRaw)
                    require(parseErr == null) { parseErr!! }
                    val rows = jsons!!.mapIndexed { i, json ->
                        SourceDedupeAnalyzer.Row("src[$i]", "", json, 0L)
                    }
                    rows to rows.mapIndexed { i, r -> r.projectId to McpSourceTools.previewOf(
                        com.mina.legadostudio.data.db.ProjectEntity(r.projectId, "", "", r.sourceJson, "", "", 0L, 0L), i) }.toMap()
                }
                else -> {
                    val all = app.projects.list()
                    require(all.size <= McpSourceTools.MAX_PROJECTS) { "项目数 ${all.size} 超过 ${McpSourceTools.MAX_PROJECTS}，请传 ids 限定范围" }
                    all.map { McpSourceTools.rowOf(it) } to all.mapIndexed { i, p -> p.id to McpSourceTools.previewOf(p, i) }.toMap()
                }
            }
            val now = System.currentTimeMillis()
            // 可信证据只来自本实例 check_source 注册表（按 sourceJson sha256 命中）；
            // 客户端自传 checkResults 仅作外部自述回显，不进 filter —— 防伪造成功。
            val (claims, checkErr) = McpSourceTools.parseCheckResults(req.arguments.str("checkResults"))
            require(checkErr == null) { checkErr!! }
            val checkMap = McpSourceTools.trustedCheckResults(rows.map { it.sourceJson }, now)
            val (excludes, exErr) = McpSourceTools.parseExcludes(req.arguments)
            require(exErr == null) { exErr!! }
            val filterReport = SourceFilterPolicy.filter(rows.map { it.sourceJson }, checkMap, excludes)
            // 被排除的项不进 dedupe 视图（它们本就要被滤掉），但冲突里仍如实保留——不折叠冲突
            val keptRows = rows.filterIndexed { i, _ -> filterReport.kept.any { it.index == i } }
            val dedupe = SourceDedupeAnalyzer.analyzeRows(keptRows)
            ok(app.gson.toJson(linkedMapOf<String, Any?>(
                "inputMode" to when { idsEl != null -> "ids"; sourcesRaw != null -> "sources"; else -> "all" },
                "analyzed" to keptRows.size, "excluded" to filterReport.excludedCount,
                // 可信证据命中数 = 实际参与 verified 判定的条数；externalClaims 只回显不自证
                "verifiedSources" to checkMap.size,
                "externalClaims" to McpSourceTools.externalClaimsSummary(claims),
                "externalClaimsTrusted" to false,
                "filter" to McpSourceTools.filterPayload(filterReport),
                "dedupe" to McpSourceTools.reportPayload(dedupe, previews),
                "hint" to "只读分析：不联网、不执行书源内 JS、不改库；冲突一律保留待人工确认；verified/failed 只认本实例 check_source 登记的证据，外部自传 checkResults 仅作自述回显（untrusted）；export_checked_sources 可在该分析基础上导出（collapseIdentical 只折叠完全相同的副本）",
            )))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("export_checked_sources", "按显式项目 ID 导出书源 JSON（默认单对象给对象、多对象给 JSON 数组；asArray=true 恒为数组，兼容阅读「全量导入」格式；collapseIdentical=true 只折叠结构完全相同且零冲突的副本——冲突/不确定的一律原样保留。导出内容保留每条 sourceJson 原文：完整字段/类型/#标签逐字节不动）。可选 exclude 过滤（verified/failed 判定只认本实例 check_source 登记的可信证据，无证据默认导出、只有 excludeUnverified 才把无证据源排除）；客户端可传 checkResults 但仅作外部自述回显，不参与判定。", ToolSchema(properties = buildJsonObject {
        putJsonObject("ids") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "要导出的项目 ID（必填；不存在的 ID 立即报错，不静默跳过）") }
        putJsonObject("exclude") {
            put("type", "object")
            put("description", "可选过滤开关：excludeLogin/excludeCaptcha/excludeFailed/excludeUnverified=true 才排除对应书源；默认全保留。unverified/failed 判定依据本实例 check_source 登记的可信证据，无证据按未验证处理")
            SourceFilterPolicy.Exclude.values().forEach { putJsonObject(it.arg) { put("type", "boolean"); put("description", it.label) } }
        }
        put("checkResults", stringProp("可选：{\"0\":{check_source 返回}}，键为 ids 数组下标。**仅作外部自述回显（untrusted），不参与 verified/failed 判定**——要算已验证须先用本实例 check_source 实际跑过"))
        putJsonObject("collapseIdentical") { put("type", "boolean"); put("description", "true=仅折叠结构完全相同且零冲突的重复副本（dedupe export_one 口径）；默认 false=不折叠") }
        putJsonObject("asArray") { put("type", "boolean"); put("description", "true=恒导出 JSON 数组（阅读全量导入格式）；默认 false=单对象时导出对象") }
    }, required = listOf("ids")), toolAnnotations = readOnly) { req ->
        runCatching {
            val idsEl = req.arguments?.get("ids") ?: return@tool err("ids 不能为空")
            require(idsEl is JsonArray) { "ids 需要 JSON 数组（type: array），收到 ${jsonType(idsEl)}" }
            val ids = idsEl.mapNotNull { it.jsonPrimitive.contentOrNull }
            require(ids.isNotEmpty()) { "ids 不能为空数组" }
            require(ids.size <= McpSourceTools.MAX_EXPORT_IDS) { "ids 一次最多 ${McpSourceTools.MAX_EXPORT_IDS} 个（收到 ${ids.size}）" }
            val (hit, missing, _) = McpSourceTools.selectProjects(app.projects.list(), ids)
            require(missing.isEmpty()) { "以下 projectId 不存在：${missing.joinToString()}；请先 list_sources 核对 ID（不存在的 ID 不会静默跳过）" }
            val now = System.currentTimeMillis()
            // 可信证据只来自本实例 check_source 注册表；客户端自传 checkResults 仅作外部自述回显。
            val (claims, checkErr) = McpSourceTools.parseCheckResults(req.arguments.str("checkResults"))
            require(checkErr == null) { checkErr!! }
            val checkMap = McpSourceTools.trustedCheckResults(hit.map { it.sourceJson }, now)
            val (excludes, exErr) = McpSourceTools.parseExcludes(req.arguments)
            require(exErr == null) { exErr!! }
            val filterReport = SourceFilterPolicy.filter(hit.map { it.sourceJson }, checkMap, excludes)
            // 保持调用方 ids 顺序导出；kept 的 index 映射回项目行
            val keptRows = filterReport.kept.map { d ->
                McpSourceTools.ExportRow(hit[d.index].id, hit[d.index].siteUrl, hit[d.index].sourceJson, hit[d.index].updatedAt)
            }
            val (exportText, summary, exportErr) = McpSourceTools.buildExport(
                keptRows,
                collapseIdentical = req.arguments.bool("collapseIdentical") == true,
                asArray = req.arguments.bool("asArray") == true,
            )
            require(exportErr == null) { exportErr!! }
            ok(app.gson.toJson(linkedMapOf<String, Any?>(
                "export" to exportText,
                "summary" to summary,
                "filter" to McpSourceTools.filterPayload(filterReport),
                // 可信证据命中数；externalClaims 只回显不自证
                "verifiedSources" to checkMap.size,
                "externalClaims" to McpSourceTools.externalClaimsSummary(claims),
                "externalClaimsTrusted" to false,
                "hint" to "导出内容保留每条 sourceJson 原文：完整字段/类型/#标签逐字节不动（原文拼接，不是重序列化）；collapseIdentical 只折叠结构全等的零冲突副本，冲突项绝不折叠；verified/failed 只认本实例 check_source 登记的证据，无证据默认导出、excludeUnverified 才把无证据源排除；外部自传 checkResults 仅作自述回显（untrusted）",
            )))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("import_reader", "把书源推送到本机已安装的「阅读」系客户端（经 legado://import/bookSource + 本机回环 JSON 端点）。二选一提供书源：projectId（已存项目 ID，优先）或 source（完整 BookSource JSON 临时导入）。packageName 缺省时不发起导入——返回 device 上可处理该协议的目标列表（packageName/label/known，上次所用置顶）让你或用户选定后重传；给定的 packageName 不在目标列表内会报错并附合法清单。推送成功后由阅读客户端自己的勾选页完成导入，本工具只负责唤起，不保证对方最终收下。", ToolSchema(properties = buildJsonObject {
        put("projectId", stringProp("可选：已存书源项目 ID（与 source 二选一，优先）"))
        put("source", stringProp("可选：完整 BookSource JSON（与 projectId 二选一）"))
        put("packageName", stringProp("可选：目标阅读包名（须为 targets 之一）；缺省返回可选目标列表不导入"))
    }, required = emptyList()), toolAnnotations = write) { req ->
        runCatching {
            val targets = app.readerTargets()
            val projectIdArg = req.arguments.str("projectId")?.takeIf { it.isNotBlank() }
            val sourceArg = req.arguments.str("source")?.takeIf { it.isNotBlank() }
            require(!(projectIdArg == null && sourceArg == null)) { "需要 projectId 或 source 之一" }
            require(!(projectIdArg != null && sourceArg != null)) { "projectId 与 source 只能传一个" }
            val sourceJson = when {
                projectIdArg != null -> app.projects.get(projectIdArg)?.sourceJson
                    ?: error("项目不存在：$projectIdArg")
                else -> sourceArg!!
            }
            val targetList = targets.map { mapOf("packageName" to it.packageName, "label" to it.label, "known" to it.known) }
            val pkg = req.arguments.str("packageName")?.takeIf { it.isNotBlank() }
                ?: return@tool ok(app.gson.toJson(mapOf(
                    "imported" to false, "needsPackageName" to true,
                    "targets" to targetList,
                    "hint" to "未指定 packageName：以上是本机可处理 legado://import/bookSource 的阅读客户端；请选其一（上次所用置顶）作为 packageName 重新调用以真正推送。",
                )))
            val target = targets.firstOrNull { it.packageName == pkg }
                ?: error("packageName 不是本机可处理书源导入的阅读客户端：$pkg；合法目标：${targets.joinToString { it.packageName }.ifBlank { "（无，请先安装阅读系客户端）" }}")
            app.importToReader(sourceJson, target.packageName)
            // 回执带推送内容的指纹：jsLib 是远程依赖，长度+sha256 让调用方确认推过去的就是本地这份
            val receipt = linkedMapOf<String, Any?>(
                "imported" to true, "packageName" to target.packageName, "label" to target.label,
                "sourceChars" to sourceJson.length,
                "sourceSha256" to TaskContextStore.digest(sourceJson),
            )
            runCatching { JsonParser.parseString(sourceJson).asJsonObject.get("jsLib") }
                .getOrNull()?.takeIf { it.isJsonPrimitive }?.asString?.let { jsLib ->
                    receipt["jsLibChars"] = jsLib.length
                    receipt["jsLibSha256"] = TaskContextStore.digest(jsLib)
                }
            receipt["hint"] = "已唤起 ${target.label} 的导入勾选页；最终是否导入由该客户端与用户确认，本工具不保证结果。sourceSha256/jsLibSha256 是本次推送内容的指纹，可与本地书源核对。"
            ok(app.gson.toJson(receipt))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("check_source", "按提供的搜索/详情/目录/正文入口批量运行校验；书源含 searchUrl 而未传 searchKey 时会自动用关键词「我」探测搜索链路并在 warnings 标注（搜索页结构常与列表页不同，漏验会让真机搜索零结果）；默认复用本任务快照避免重复联网，refresh=true 全程实时（上线验收用）；auto=true 启用链路推进（搜索→详情→目录→正文自动补全，缺省保持旧行为只跑显式阶段）；verdict.summary 的 full 仅在静态校验及文本源搜索、详情、目录、正文四阶段全部通过时返回；refresh=false 可能复用任务快照，refresh=true 仅表示本次请求重新联网，不保证后续持续可用或阅读客户端可用；返回 timingsMs 为各阶段与总耗时（含缓存、JS 求值与联网）", checkSourceSchema(), toolAnnotations = openWrite) { req ->
        runCatching {
            val source = req.arguments.str("source") ?: return@tool err("source 不能为空")
            // 与 save_source/debug_source 同一套 JSON 错误提示：语法错直接回行列定位片段，不进后续 debug
            runCatching { JsonParser.parseString(source).asJsonObject }.getOrNull()
                ?: return@tool err(app.validator.validate(source).issues.firstOrNull()?.let { "source 不是合法 JSON 对象：${it.message}" }
                    ?: "source 不是合法 JSON 对象")
            val cacheTrace = StudioMcpServer.RuntimeCacheTrace(req.arguments.bool("refresh") == true)
            val cache = runtimeCache(req.arguments, cacheTrace)
            val anchor = sourceAnchorOf(source)
            val ctxId = contextId(req.arguments)
            if (anchor != null && ctxId.isNotBlank()) {
                com.mina.legadostudio.domain.ContextAnchorRegistry.put(ctxId, anchor)
            }
            val results = com.mina.legadostudio.domain.CheckSourceRunner.run(
                com.mina.legadostudio.domain.CheckSourceRunner.Request(
                    source = source,
                    searchKey = req.arguments.str("searchKey"),
                    detailUrl = req.arguments.str("detailUrl"),
                    tocUrl = req.arguments.str("tocUrl"),
                    contentUrl = req.arguments.str("contentUrl"),
                    exploreKey = req.arguments.str("exploreKey"),
                    exploreUrl = req.arguments.str("exploreUrl"),
                    refresh = req.arguments.bool("refresh") == true,
                    auto = req.arguments.bool("auto") == true,
                ),
                runtime = app.runtime,
                cacheFetch = cache,
                origin = HttpOrigin(sourceAnchor = anchor, contextId = ctxId, originKind = "check_source"),
            )
            // 每阶段 error 带超时/DNS/CF 特征时，在该阶段 map 里追加 blockHint，帮 AI 定位是「站挂了」还是「被拦了」
            val hinted = results.mapValues { (_, v) ->
                if (v is Map<*, *> && v["error"] is String) {
                    val hint = blockHint(0, null, null, v["error"] as String)
                    if (hint != null) (v as Map<Any, Any>) + mapOf("blockHint" to hint) else v
                } else v
            }
            // 可信证据登记：把当次原始阶段结果（DebugReport/errorMap 原文，非 roundtrip 后的 Map）
            // 按 sha256(source) 存进注册表；analyze_sources/export_checked_sources 的 verified/failed
            // 判定只认这里——客户端自传 checkResults 不绑定 sourceJson 且 DebugReport 不可 roundtrip，不能算数。
            McpSourceTools.CheckEvidenceRegistry.register(source, results, req.arguments.bool("refresh") == true, System.currentTimeMillis())
            val summary = cacheTrace.summary()
            val fromCache = (summary["cacheHits"] as? Int ?: 0) > 0
            val ageSec = cacheTrace.snapshotAgeSeconds()
            ok(app.gson.toJson(hinted + mapOf(
                "cache" to summary,
                "fromCache" to fromCache,
                "snapshotAgeSeconds" to ageSec,
            ) + if (fromCache) mapOf(
                "warning" to "⚠ 复用缓存快照（${(ageSec ?: 0) / 60} 分钟前抓取），最终验收请传 refresh=true 实时校验",
            ) else emptyMap()))
        }.getOrElse { verificationAware(it, req.arguments) }
    }
}
