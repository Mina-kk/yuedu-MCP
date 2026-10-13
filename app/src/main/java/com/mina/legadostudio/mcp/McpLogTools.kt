package com.mina.legadostudio.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun StudioMcpServer.registerLogTools(server: Server) {
    server.tool("export_diagnostic", "创建诊断快照（仅含版本、MCP 状态与前置条件，不含 HTTP/崩溃正文）", ToolSchema(properties = buildJsonObject {}, required = emptyList()), toolAnnotations = readOnly) {
        val snap = app.snapshots.create()
        ok(app.gson.toJson(mapOf("id" to snap.id, "title" to snap.title, "createdAt" to snap.createdAt)))
    }
    server.tool("get_logs", "读取持久化操作日志（结构化 JSON）。排查工具调用与运行时故障时优先使用，无需从 App 导出。", ToolSchema(properties = buildJsonObject {
        putJsonObject("limit") { put("type", "integer"); put("description", "默认 50，最大 200") }
        putJsonObject("category") { put("type", "string"); put("description", "可选，按 category 过滤，如 tool/mcp/service") }
        putJsonObject("level") { put("type", "string"); put("description", "可选，按级别过滤：I/W/E") }
        putJsonObject("query") { put("type", "string"); put("description", "可选，匹配 message 或 detail") }
    }, required = emptyList()), toolAnnotations = readOnly) { req ->
        val limit = (req.arguments.int("limit") ?: 50).coerceIn(1, 200)
        val category = req.arguments.str("category").orEmpty()
        val level = req.arguments.str("level").orEmpty()
        val query = req.arguments.str("query").orEmpty()
        val items = app.database.dao().latestOperationLogs(500)
            .filter { category.isBlank() || it.category.equals(category, ignoreCase = true) }
            .filter { level.isBlank() || it.level.equals(level, ignoreCase = true) }
            .filter { query.isBlank() || it.message.contains(query, ignoreCase = true) || it.detail.contains(query, ignoreCase = true) }
            .take(limit)
        ok(app.gson.toJson(mapOf("count" to items.size, "items" to items)))
    }
    server.tool("get_log", "按 ID 读取单条操作日志详情", ToolSchema(properties = buildJsonObject { putJsonObject("id") { put("type", "integer") } }, required = listOf("id")), toolAnnotations = readOnly) { req ->
        app.database.dao().operationLog((req.arguments.int("id") ?: return@tool err("id 不能为空")).toLong())?.let { ok(app.gson.toJson(it)) } ?: err("日志不存在")
    }
    server.tool("get_http_logs", "列出 HTTP 事务摘要。排查请求失败、重定向或状态码时使用；支持按日期/锚点/contextId 筛选与分页（全日>200条用 offset 续取）。传 contextId（如 cap:xxx）时只列该上下文的全部事务，按发生顺序排序，不受最近500条窗口限制。默认排除逐次抓包（cap:）事务——抓包有独立页面与 get_capture/list_captures；需要时传 includeCapture=true 一并列出。", ToolSchema(properties = buildJsonObject {
        putJsonObject("limit") { put("type", "integer"); put("description", "默认 50，最大 200；超过用 offset 翻页") }
        putJsonObject("offset") { put("type", "integer"); put("description", "可选，分页偏移（与 limit 配合）") }
        putJsonObject("date") { put("type", "string"); put("description", "可选，按日期过滤 YYYY-MM-DD（默认最近，不限制）") }
        putJsonObject("anchor") { put("type", "string"); put("description", "可选，书源锚点域名（注册域如 example.com），只列该锚点归属的事务") }
        putJsonObject("contextId") { put("type", "string"); put("description", "可选，精确匹配事务上下文（cap:xxx 抓包会话或任务 contextId）") }
        putJsonObject("query") { put("type", "string"); put("description", "可选，匹配 URL") }
        putJsonObject("includeCapture") { put("type", "boolean"); put("description", "可选，默认 false：排除 cap: 逐次抓包事务（抓包高频记录不占结果、不污染锚点分桶）；true 时含抓包记录") }
    }, required = emptyList()), toolAnnotations = readOnly) { req ->
        val query = req.arguments.str("query").orEmpty()
        val anchorParam = req.arguments.str("anchor")?.trim().orEmpty()
        val contextIdParam = req.arguments.str("contextId")?.trim().orEmpty()
        val dateParam = req.arguments.str("date")?.trim().orEmpty()
        val limit = (req.arguments.int("limit") ?: 50).coerceIn(1, 200)
        val offset = (req.arguments.int("offset") ?: 0).coerceAtLeast(0)
        // 默认排除 cap:% 抓包事务（contextId 精确查询捷径不受影响：显式传 cap:xxx 就是点名抓包会话）
        val includeCapture = req.arguments.bool("includeCapture") == true

        // 日期窗口：解析 yyyy-MM-dd 为当天 [start, end)；无 date 时用 summarize 上限（非分页模式）
        val dayWindow = dateParam.takeIf { it.isNotBlank() }?.let { d ->
            val parsed = runCatching { java.time.LocalDate.parse(d) }.getOrNull()
                ?: return@tool err("date 格式必须为 YYYY-MM-DD")
            val zone = java.time.ZoneId.systemDefault()
            val start = parsed.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = parsed.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            start to end
        }
        // 锚点参数规范化：占位桶 __unattributed__/__infrastructure__ 先特判保留原值
        // （normalizeAnchor 会把它们当域名变形返回非空串，破坏 inAnchorBucket 的占位桶分支）；
        // 其余值必须能归一化为注册域，否则报错而不是静默退化为不过滤。
        val normalizedAnchor = anchorParam.takeIf { it.isNotBlank() }?.let { raw ->
            when (raw) {
                com.mina.legadostudio.domain.HttpLogAttributor.UNATTRIBUTED_KEY,
                com.mina.legadostudio.domain.HttpLogAttributor.INFRASTRUCTURE_ANCHOR -> raw
                else -> com.mina.legadostudio.domain.HttpLogAttributor.normalizeAnchor(raw)
                    ?: return@tool err("anchor 无法识别为注册域或占位桶（__unattributed__/__infrastructure__）：$raw")
            }
        }

        val dao = app.database.dao()

        // contextId 精确过滤是轻量捷径：该上下文就是归属本身，不再跑归属器，也不受日期/锚点筛选影响。
        // 同一 contextId 行数少（一次抓包最多几十条），DAO 分页+总数即可，无需全表扫描。
        if (contextIdParam.isNotBlank()) {
            val page = dao.httpLogSummariesByContextIdPage(contextIdParam, limit, offset)
            val total = dao.countHttpLogsByContextId(contextIdParam)
            val mapped = page.map { s ->
                mapOf(
                    "id" to s.id, "method" to s.method, "url" to s.url, "finalUrl" to s.finalUrl,
                    "statusCode" to s.statusCode, "durationMs" to s.durationMs, "error" to s.error,
                    "createdAt" to s.createdAt,
                    "sourceAnchor" to s.sourceAnchor, "contextId" to s.contextId, "originKind" to s.originKind,
                )
            }
            return@tool ok(app.gson.toJson(mapOf(
                "count" to mapped.size, "total" to total, "offset" to offset, "limit" to limit,
                "contextId" to contextIdParam, "items" to mapped,
                "hint" to "已按 contextId 精确过滤（含中间跳 capture_hop）；要逐跳回看/历史会话用 get_capture/list_captures",
            )))
        }

        // 锚点筛选统一走归属器（显式 sourceAnchor + contextId 映射 + URL/重定向/Referer 证据同一套规则），
        // 保证每条日志恰好落入一个桶：占位桶（__unattributed__/__infrastructure__）与真实锚点互补无遗漏。
        // 需要含请求头/重定向链的投影才能让旧日志的证据链参与归属。
        val ctxAnchors = com.mina.legadostudio.domain.ContextAnchorRegistry.snapshot()
        val total: Int
        val items: List<com.mina.legadostudio.data.db.HttpLogEntity>
        val anchors: List<String>
        fun inAnchorBucket(d: com.mina.legadostudio.domain.HttpLogAttributor.Decision): Boolean = when {
            normalizedAnchor == null -> true
            normalizedAnchor == com.mina.legadostudio.domain.HttpLogAttributor.UNATTRIBUTED_KEY -> d.anchor == null
            normalizedAnchor == com.mina.legadostudio.domain.HttpLogAttributor.INFRASTRUCTURE_ANCHOR ->
                d.kind == com.mina.legadostudio.domain.HttpLogAttributor.Kind.INFRASTRUCTURE
            else -> d.anchor == normalizedAnchor
        }
        fun matchesQuery(log: com.mina.legadostudio.data.db.HttpLogEntity): Boolean =
            query.isBlank() || log.url.contains(query, ignoreCase = true) || log.finalUrl.contains(query, ignoreCase = true)
        if (dayWindow != null) {
            // 全日游标分页取轻量投影（含归属证据列，无 responseBody），归属判定与全量实体同口径；
            // includeCapture=false（默认）走 SQL 排除 cap:% 的过滤版，抓包记录不进候选、不污染锚点分桶
            val all = mutableListOf<com.mina.legadostudio.data.db.HttpLogEntity>()
            var off = 0
            while (true) {
                val page = if (includeCapture) {
                    dao.httpLogSummariesByDayPage(dayWindow.first, dayWindow.second, 200, off)
                } else {
                    dao.httpLogSummariesByDayPageNoCapture(dayWindow.first, dayWindow.second, 200, off)
                }
                if (page.isEmpty()) break
                page.forEach { all += it.toAttributionEntity() }
                off += page.size
            }
            // 候选锚点 = 当日显式锚点 ∪ 用户传入锚点（用户显式指定时允许旧日志凭 URL/Referer 证据归并）
            anchors = (all.mapNotNull { it.sourceAnchor } + listOfNotNull(normalizedAnchor)).distinct()
            val filtered = all.filter { log ->
                matchesQuery(log) && inAnchorBucket(com.mina.legadostudio.domain.HttpLogAttributor.attribute(log, anchors, ctxAnchors))
            }
            total = filtered.size
            items = filtered.drop(offset).take(limit)
        } else {
            // 无日期：全库投影游标扫描（不按 offset+limit 截断——锚点/Referer 证据可能在任意日期的老日志上），
            // 归属判定跑完再分页，保证 total 是过滤后的真实总数。includeCapture=false（默认）排除 cap:% 事务。
            val all = mutableListOf<com.mina.legadostudio.data.db.HttpLogEntity>()
            var off = 0
            while (true) {
                val page = if (includeCapture) {
                    dao.httpLogSummariesPage(200, off)
                } else {
                    dao.httpLogSummariesPageNoCapture(200, off)
                }
                if (page.isEmpty()) break
                page.forEach { all += it.toAttributionEntity() }
                off += page.size
            }
            anchors = (all.mapNotNull { it.sourceAnchor } + listOfNotNull(normalizedAnchor)).distinct()
            val filtered = all.filter { log ->
                matchesQuery(log) && inAnchorBucket(com.mina.legadostudio.domain.HttpLogAttributor.attribute(log, anchors, ctxAnchors))
            }
            total = filtered.size
            items = filtered.drop(offset).take(limit)
        }

        val mapped = items.map { s ->
            val d = com.mina.legadostudio.domain.HttpLogAttributor.attribute(s, anchors, ctxAnchors)
            mapOf(
                "id" to s.id, "method" to s.method, "url" to s.url, "finalUrl" to s.finalUrl,
                "statusCode" to s.statusCode, "durationMs" to s.durationMs, "error" to s.error,
                "anchor" to (d.anchor ?: com.mina.legadostudio.domain.HttpLogAttributor.UNATTRIBUTED_KEY),
                "attribution" to d.kind.name, "confidence" to d.confidence, "evidence" to d.evidence,
                "sourceAnchor" to s.sourceAnchor, "contextId" to s.contextId, "originKind" to s.originKind,
            )
        }
        ok(app.gson.toJson(mapOf("count" to mapped.size, "total" to total, "offset" to offset, "limit" to limit, "items" to mapped)))
    }
}

internal fun StudioMcpServer.registerCrashDiagnosticTools(server: Server) {
    server.tool("get_crash_logs", "列出本地崩溃记录摘要", ToolSchema(properties = buildJsonObject { putJsonObject("limit") { put("type", "integer"); put("description", "默认 10，最大 20") } }, required = emptyList()), toolAnnotations = readOnly) { req ->
        val items = app.crashLogs.list().take((req.arguments.int("limit") ?: 10).coerceIn(1, 20)).map {
            mapOf("name" to it.name, "createdAt" to it.createdAt, "size" to it.size)
        }
        ok(app.gson.toJson(mapOf("count" to items.size, "items" to items)))
    }
    server.tool("get_crash_log", "按文件名读取崩溃记录正文", schema(mapOf("name" to "崩溃文件名"), listOf("name")), toolAnnotations = readOnly) { req ->
        runCatching { ok(app.crashLogs.read(req.arguments.str("name") ?: error("name 不能为空"))) }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("get_diagnostic_snapshots", "列出诊断快照摘要", ToolSchema(properties = buildJsonObject {}, required = emptyList()), toolAnnotations = readOnly) {
        val items = app.database.dao().observeDiagnosticSnapshots().first().map { mapOf("id" to it.id, "title" to it.title, "createdAt" to it.createdAt) }
        ok(app.gson.toJson(mapOf("count" to items.size, "items" to items)))
    }
    server.tool("get_diagnostic_snapshot", "按 ID 读取诊断快照内容", schema(mapOf("id" to "快照 ID"), listOf("id")), toolAnnotations = readOnly) { req ->
        val snap = app.database.dao().diagnosticSnapshot(req.arguments.str("id") ?: return@tool err("id 不能为空")) ?: return@tool err("快照不存在")
        ok(app.snapshots.read(snap).ifBlank { "（空）" })
    }
}
