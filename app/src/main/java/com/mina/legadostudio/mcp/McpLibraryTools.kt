package com.mina.legadostudio.mcp

import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpOrigin
import com.mina.legadostudio.domain.SourceDedupeAnalyzer
import com.mina.legadostudio.domain.SourceFilterPolicy
import com.mina.legadostudio.reader.ReaderExecutionRegistry
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            val readerConfig = withContext(Dispatchers.IO) { app.readerWebConfig.load() }
            val readerRows = keptRows.map { row ->
                val sourceObj = runCatching { JsonParser.parseString(row.sourceJson).asJsonObject }.getOrNull()
                val url = sourceObj?.get("bookSourceUrl")?.asString?.trim().orEmpty()
                ReaderDeliveryRow(row.projectId, url, url.takeIf { it.isNotEmpty() }?.let {
                    ReaderExecutionRegistry.summary(it, now,
                        sourceHash = sourceObj?.let(ReaderExecutionRegistry::sourceHashOf),
                        configFingerprint = ReaderExecutionRegistry.configFingerprintOf(readerConfig))
                })
            }
            ok(app.gson.toJson(linkedMapOf<String, Any?>(
                "export" to exportText,
                "summary" to summary,
            ) + exportDeliveryFields(readerWebConfigured(), readerRows) + linkedMapOf<String, Any?>(
                "filter" to McpSourceTools.filterPayload(filterReport),
                // 可信证据命中数；externalClaims 只回显不自证
                "verifiedSources" to checkMap.size,
                "externalClaims" to McpSourceTools.externalClaimsSummary(claims),
                "externalClaimsTrusted" to false,
                "hint" to "导出内容保留每条 sourceJson 原文：完整字段/类型/#标签逐字节不动（原文拼接，不是重序列化）；collapseIdentical 只折叠结构全等的零冲突副本，冲突项绝不折叠；verified/failed 只认本实例 check_source 登记的证据，无证据默认导出、excludeUnverified 才把无证据源排除；外部自传 checkResults 仅作自述回显（untrusted）",
            )))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("import_reader", "仅唤起勾选页，未确认导入；优先Web同步：把书源推送到本机已安装的「阅读」系客户端（经 legado://import/bookSource + 本机回环 JSON 端点）。二选一提供书源：projectId（已存项目 ID，优先）或 source（完整 BookSource JSON 临时导入）。必须显式传 packageName 才发起唤起；缺省只返回本机可处理该协议的目标列表（packageName/label/known，上次所用置顶）且不启动任何界面；给定的 packageName 不在目标列表内会报错并附合法清单。唤起成功只代表阅读的勾选页被打开，用户还要在勾选页里确认——回包 launched=true、confirmation=pending_user、confirmed=false，绝不能当作已导入、也绝不能因此跳过核实；确认后用 reader_web_status 或 reader_web_verify 核实（verify 只检验阅读里已保存的书源），推荐优先用 reader_web_sync_source(projectId) 把已保存项目直接写入阅读并回读确认。", ToolSchema(properties = buildJsonObject {
        put("projectId", stringProp("可选：已存书源项目 ID（与 source 二选一，优先）"))
        put("source", stringProp("可选：完整 BookSource JSON（与 projectId 二选一）；传 JSON 字符串或已解析的对象均可"))
        put("packageName", stringProp("可选：目标阅读包名（须为 targets 之一）；缺省只返回可选目标列表、不唤起任何界面"))
    }, required = emptyList()), toolAnnotations = openWrite) { req ->
        runCatching {
            val targets = app.readerTargets()
            val projectIdArg = req.arguments.str("projectId")?.takeIf { it.isNotBlank() }
            val sourceArg = req.arguments.jsonText("source")?.takeIf { it.isNotBlank() }
            require(!(projectIdArg == null && sourceArg == null)) { "需要 projectId 或 source 之一" }
            require(!(projectIdArg != null && sourceArg != null)) { "projectId 与 source 只能传一个" }
            val sourceJson = when {
                projectIdArg != null -> app.projects.get(projectIdArg)?.sourceJson
                    ?: error("项目不存在：$projectIdArg")
                else -> sourceArg!!
            }
            val targetList = targets.map { mapOf("packageName" to it.packageName, "label" to it.label, "known" to it.known) }
            val pkg = req.arguments.str("packageName")?.takeIf { it.isNotBlank() }
                ?: return@tool ok(app.gson.toJson(linkedMapOf<String, Any?>(
                    "launched" to false, "confirmation" to "not_requested", "confirmed" to false,
                    "needsPackageName" to true,
                    "targets" to targetList,
                    // 兼容字段：旧版 imported=true 的语义是错的（只唤起、未确认），保留也必须恒为 false
                    "imported" to false,
                    "deprecated" to "imported 字段已废弃，恒为 false；本次没有发起任何导入。以 launched/confirmation/confirmed 为准",
                    "nextAction" to "用户选定 targets 中一个 packageName 后重传本工具才会唤起；若要真正写入阅读，优先用 reader_web_sync_source(projectId) 并回读确认",
                    "hint" to "未指定 packageName：以上是本机可处理 legado://import/bookSource 的阅读客户端；请选其一（上次所用置顶）作为 packageName 重新调用以唤起勾选页。",
                )))
            val target = targets.firstOrNull { it.packageName == pkg }
                ?: error("packageName 不是本机可处理书源导入的阅读客户端：$pkg；合法目标：${targets.joinToString { it.packageName }.ifBlank { "（无，请先安装阅读系客户端）" }}")
            app.importToReader(sourceJson, target.packageName)
            // 回执带推送内容的指纹：jsLib 是远程依赖，长度+sha256 让调用方确认推过去的就是本地这份
            val receipt = linkedMapOf<String, Any?>(
                // startActivity 成功只是拉起勾选页，导入仍待用户在阅读里确认
                "launched" to true, "confirmation" to "pending_user", "confirmed" to false,
                "packageName" to target.packageName, "label" to target.label,
                "sourceChars" to sourceJson.length,
                "sourceSha256" to TaskContextStore.digest(sourceJson),
                // 兼容字段：绝不回 imported=true；旧语义会误导调用方跳过核实
                "imported" to false,
                "deprecated" to "imported 字段已废弃，恒为 false；本次只唤起了阅读的勾选页，导入是否完成以 confirmed/confirmation 为准",
            )
            projectIdArg?.let { receipt["projectId"] = it }
            runCatching { JsonParser.parseString(sourceJson).asJsonObject.get("jsLib") }
                .getOrNull()?.takeIf { it.isJsonPrimitive }?.asString?.let { jsLib ->
                    receipt["jsLibChars"] = jsLib.length
                    receipt["jsLibSha256"] = TaskContextStore.digest(jsLib)
                }
            receipt["nextAction"] = if (projectIdArg != null) {
                "先让用户在阅读的勾选页确认导入，再用 reader_web_status/reader_web_verify 核实；更推荐直接优先用 reader_web_sync_source(projectId=\"$projectIdArg\") 写入阅读并回读确认"
            } else {
                "先让用户在阅读的勾选页确认导入，再用 reader_web_status/reader_web_verify 核实；本次传的是临时 source 未入库，无法 reader_web_sync_source，建议先 save_project 再同步"
            }
            receipt["hint"] = "已唤起 ${target.label} 的勾选页（launched=true），但用户尚未确认，confirmed=false——不要当作导入成功，也不要停止核实。sourceSha256/jsLibSha256 是本次推送内容的指纹，可与本地书源核对。"
            ok(app.gson.toJson(receipt))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("check_source", "工坊沙箱初筛，不代表阅读端通过；已配置阅读 Web 时还须用 reader_web_page 导航书源管理/调试并确认实际反馈，或以 reader_web_verify 本次结构化结果验证；旧 WS 日志不可判通过。按提供的搜索/详情/目录/正文入口批量运行；书源含 searchUrl 而未传 searchKey 时自动用 ruleSearch.checkKeyWord（≥2 字）或「我的」探测并在 warnings 标注，搜索 0 条按规则问题计；默认复用任务快照，refresh=true 全程实时；auto=true 自动链路推进；verdict.summary=full 仅表示沙箱静态及文本源四阶段样本通过，不保证阅读客户端可用；无法访问阅读时须标注阅读端未覆盖。返回 timingsMs 含缓存、JS 求值与联网。", checkSourceSchema(), toolAnnotations = openWrite) { req ->
        runCatching {
            val source = resolveSourceArg(req.arguments) ?: return@tool err("source/projectId 至少传一个")
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
            val sourceUrl = runCatching {
                JsonParser.parseString(source).asJsonObject.get("bookSourceUrl")?.asString?.trim()
            }.getOrNull().orEmpty()
            val readerConfig = withContext(Dispatchers.IO) { app.readerWebConfig.load() }
            val readerConfigured = readerConfig != null
            val readerEvidence = ReaderExecutionRegistry.summary(sourceUrl,
                sourceHash = ReaderExecutionRegistry.sourceHashOf(JsonParser.parseString(source).asJsonObject),
                configFingerprint = ReaderExecutionRegistry.configFingerprintOf(readerConfig))
            ok(app.gson.toJson(hinted + mapOf(
                "cache" to summary,
                "fromCache" to fromCache,
                "snapshotAgeSeconds" to ageSec,
                "readerWebVerification" to "not_performed",
                "readerWebNote" to "此结果仅为工坊沙箱样本初筛；已配置阅读 Web 时需用 reader_web_page 导航书源管理/调试确认实际反馈，或用 reader_web_verify 的本次结构化结果。网页入口可开和旧版 WS 日志均不等于书源通过；无法访问阅读则明确标记阅读端未覆盖。",
            ) + sandboxDivergenceFields(source, results) + sandboxVerdictFields(
                readerConfigured,
                readerEvidence?.get("identityMatches") == true && readerEvidence["executed"] == true,
                readerSidePassed(readerEvidence),
            ) + if (fromCache) mapOf(
                "warning" to "⚠ 复用缓存快照（${(ageSec ?: 0) / 60} 分钟前抓取），最终验收请传 refresh=true 实时校验",
            ) else emptyMap()))
        }.getOrElse { verificationAware(it, req.arguments) }
    }
}

private suspend fun StudioMcpServer.readerWebConfigured(): Boolean =
    withContext(Dispatchers.IO) { runCatching { app.readerWebConfig.load() }.getOrNull() } != null

internal fun sandboxDivergenceFields(source: String, results: Map<String, Any>): Map<String, Any?> {
    val divergences = mutableListOf<Map<String, String>>()
    val evidence = results["evidence"] as? Map<*, *>
    val stages = listOf("搜索", "详情", "目录", "正文", "发现")
    val behaviors = mapOf(
        "external_jslib_skipped" to "阅读端会加载外部 jsLib",
        "content_too_large" to "阅读端不限制正文大小",
        "binary_skipped" to "阅读端会按书源类型处理二进制资源",
        "page_limit" to "阅读端会继续翻页",
        "missing_java_api" to "阅读端提供该 Java/扩展接口",
        "signature_unsupported" to "阅读端支持该方法签名",
        "default_user_agent" to "阅读端使用自身默认 UA，沙箱 UA 可能不同",
        "content_unavailable" to "样本只有转码失败或乱码占位，不是有效正文；核对原页和阅读端，不能只凭这个样本认定规则错误",
        "site_rate_limited" to "站点疑似按 IP 限流（仅沙箱侧观测，不能据此推断阅读端也被限流，阅读端是否受限以 reader_web_debug 日志为准）；等待站点提示的间隔后重测，不是规则错误",
    )
    fun add(stage: String, reason: String?) {
        if (reason != null && reason in behaviors && divergences.none { it["stage"] == stage && it["reason"] == reason }) {
            divergences += linkedMapOf("stage" to stage, "reason" to reason, "readerBehavior" to behaviors.getValue(reason))
        }
    }
    stages.forEach { stage ->
        val ev = evidence?.get(stage) as? com.mina.legadostudio.domain.SourceCheckEvidence.StageEvidence
        add(stage, ev?.sandboxReason)
        val raw = results[stage]
        if (raw is com.mina.legadostudio.runtime.LegadoRuntime.DebugReport) {
            val lines = raw.lines.joinToString("\\n")
            if (lines.contains("jsLib 为外部 URL")) add(stage, "external_jslib_skipped")
            if (lines.contains("二进制资源") && (lines.contains("跳过") || lines.contains("未下载正文"))) add(stage, "binary_skipped")
            if (lines.contains("page_limit")) add(stage, "page_limit")
        }
        val error = (raw as? Map<*, *>)?.get("error")?.toString().orEmpty()
        add(stage, com.mina.legadostudio.domain.SourceCheckEvidence.sandboxReason(error))
        if (ev?.category == "rate_limited") add(stage, "site_rate_limited")
        if (ev?.category == "content_unavailable") add(stage, "content_unavailable")
    }
    val declaredUa = runCatching {
        val root = JsonParser.parseString(source).asJsonObject
        val header = root.get("header") ?: return@runCatching false
        val headerObject = when {
            header.isJsonObject -> header.asJsonObject
            header.isJsonPrimitive -> JsonParser.parseString(header.asString).asJsonObject
            else -> null
        }
        headerObject?.keySet()?.any { it.equals("User-Agent", ignoreCase = true) } == true
    }.getOrDefault(false)
    if (!declaredUa) add("全部", "default_user_agent")
    val reasons = divergences.mapNotNull { it["reason"] }.distinct()
    return mapOf(
        "sandboxDivergence" to divergences,
        "ruleEditPolicy" to mapOf(
            "doNotEditFor" to reasons,
            "reason" to if (reasons.isEmpty()) "未发现沙箱与阅读端行为差异；规则修改仍应以阅读端证据为准" else
                "以上差异来自验收环境，不是书源错误；在阅读端 reader_web_debug 复现前不要为此修改规则",
        ),
    )
}

/**
 * check_source 回包的口径字段（纯函数，JVM 可测）：沙箱结论永远只是 sandbox_only，
 * 已配置阅读 Web 时列出必须走完的阅读端步骤与禁止话术。
 */
internal fun sandboxVerdictFields(
    readerConfigured: Boolean,
    readerSideExecuted: Boolean,
    readerSidePassed: Boolean = false,
): Map<String, Any?> {
    val base = linkedMapOf<String, Any?>(
        "verdictScope" to "sandbox_only",
        "readerSideExecuted" to readerSideExecuted,
        "readerSidePassed" to readerSidePassed,
    )
    if (!readerConfigured) {
        base["deliveryRule"] = "未配置阅读 Web：只能说「工坊沙箱样本通过」，必须标注阅读端未覆盖"
        return base
    }
    base["requiredNextActions"] = listOf("save_source", "reader_web_sync_source", "reader_web_debug")
    base["forbiddenClaims"] = listOf("阅读端通过", "与阅读端一致", "4/4 通过即可用")
    base["deliveryRule"] = when {
        readerSidePassed ->
            "当前内容与配置取得归属本次的阅读结构化检验结果；按该次范围报告，沙箱不覆盖阅读结论"
        readerSideExecuted ->
            "当前版本已有阅读执行记录；请核对完整终止、实际字段与覆盖范围，不因沙箱失败或缺少结构化PASSED而改规则"
        else ->
            "已配置阅读 Web 但本源还没有任何阅读端执行记录：沙箱 4/4 不能作为交付结论。" +
                "必须 save_source → reader_web_sync_source（服务端会自动跑阅读端调试）→ 读顶层 logVerdict 后再交付"
    }
    return base
}

internal fun readerSidePassed(summary: Map<String, Any?>?): Boolean = ReaderExecutionRegistry.passed(summary)

internal data class ReaderDeliveryRow(val projectId: String, val bookSourceUrl: String, val execution: Map<String, Any?>?)

internal fun exportDeliveryFields(readerConfigured: Boolean, rows: List<ReaderDeliveryRow>): Map<String, Any?> {
    fun rowStatus(e: Map<String, Any?>?): String = when {
        e == null -> "sandbox_only_not_reader_verified"
        e["identityMatches"] != true -> "reader_evidence_unmatched"
        e["complete"] != true -> "reader_debug_incomplete"
        (e["failureSignals"] as? List<*>).orEmpty().isNotEmpty() -> "reader_failure_signals"
        e["executed"] != true -> "sandbox_only_not_reader_verified"
        ReaderExecutionRegistry.passed(e) -> "reader_verified"
        (e["missingStages"] as? List<*>)?.isNotEmpty() != false -> "reader_partial"
        else -> "reader_executed"
    }
    val states = rows.map { rowStatus(it.execution) }
    val status = if (!readerConfigured) "reader_web_not_configured" else listOf(
        "reader_evidence_unmatched", "reader_debug_incomplete", "reader_failure_signals",
        "sandbox_only_not_reader_verified", "reader_partial", "reader_executed",
    ).firstOrNull { it in states } ?: if (rows.isEmpty()) "sandbox_only_not_reader_verified" else "reader_verified"
    val reason = when (status) {
        "reader_web_not_configured" -> "阅读端未覆盖：未配置阅读 Web，可导出草稿，不能声称已验证"
        "reader_evidence_unmatched" -> "现有记录未确认属于当前书源内容与阅读配置；请按当前版本重新同步调试，不沿用历史成功"
        "reader_debug_incomplete" -> "当前阅读尝试缺少完整终止证据；历史失败仍在 historicalFailureSignals，仅供排查"
        "reader_failure_signals" -> "当前阅读结果有失败信号；先区分环境与规则问题，沙箱成功不能覆盖失败"
        "sandbox_only_not_reader_verified" -> "缺少当前阅读端执行记录；先 reader_web_sync_source 再核对实际输出，沙箱 4/4 不能当作阅读端可用"
        "reader_partial" -> "阅读端仅有部分阶段记录；按 missingStages 补充入口，未覆盖不等于规则失败"
        "reader_executed" -> "当前阅读调试已完整结束并记录四阶段；阶段到达不是输出正确，需对照日志字段和页面描述样本结果"
        else -> "当前版本取得归属本次的阅读结构化检验结果；只代表该次检验范围，不保证全站可用"
    }
    return linkedMapOf(
        "deliveryStatus" to status,
        "blockingReason" to reason,
        "draftExportAllowed" to true,
        "readerExecutions" to rows.map { row -> mapOf(
            "projectId" to row.projectId, "bookSourceUrl" to row.bookSourceUrl,
            "deliveryStatus" to rowStatus(row.execution),
            "readerExecution" to (row.execution ?: mapOf("executed" to false)),
            "missingStages" to (row.execution?.get("missingStages") ?: ReaderExecutionRegistry.REQUIRED_STAGES),
        ) },
    )
}
