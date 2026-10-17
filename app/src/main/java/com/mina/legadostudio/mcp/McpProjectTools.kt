package com.mina.legadostudio.mcp

import com.google.gson.JsonParser
import com.mina.legadostudio.BuildConfig
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** get_app_info 的构建标识字段：纯函数便于 JVM 单测锁定契约。 */
internal fun buildMetadata(): Map<String, String> = mapOf(
    "buildRevision" to BuildConfig.BUILD_REVISION,
    "buildType" to BuildConfig.BUILD_TYPE,
)

internal fun StudioMcpServer.registerProjectTools(server: Server) {
    server.tool("get_app_info", "读取阅读书源MCP版本和能力", ToolSchema(properties = buildJsonObject {}, required = emptyList()), toolAnnotations = readOnly) {
        ok(app.gson.toJson(mapOf(
            "name" to "阅读书源MCP",
            "version" to BuildConfig.VERSION_NAME,
            "buildRevision" to buildMetadata().getValue("buildRevision"),
            "buildType" to buildMetadata().getValue("buildType"),
            "mcpPath" to McpAccess.PATH,
            "contextProtocol" to mapOf("version" to 2, "workflow" to "match_sources(语料命中) → create_context → fetch_page → read_page/inspect_rule/analyze_html/eval_js(pageId) → update_context；JS渲染页用 webview_render(url 或 url+pageId) 转真实DOM；半成品存 save_project、成品 save_source，推送阅读用 import_reader；重连后get_context或list_contexts", "cacheTtlSeconds" to 300, "idleTtlSeconds" to 1800, "persistence" to "memory+snapshot", "maxInlineChars" to 12000, "hint" to "调用时传contextId；并行做多个书源时每个任务各自 create_context 并全程显式传同一 contextId，互不串数据；POST搜索也入缓存；大结果用read_result不重复运行；check_source/debug_source默认复用本任务快照，最终验收传refresh=true；check_source 可加 exploreUrl/exploreKey 跑发现链"),
            "captureProtocol" to mapOf("workflow" to "capture_once(url,method,headers,body) 发起→返回 contextId/logs/hopLogIds/finalLogIds→get_capture(contextId) 回看逐跳→get_http_log(id) 看单条详情→list_captures 翻历史会话", "perHop" to "重定向中间跳另记 originKind=capture_hop 独立日志（3xx+Location，同 cap:contextId/sourceAnchor），被SSRF拦截的跳也先落库", "privacy" to "URL/query 可能带令牌；正文/头经 get_http_log 查看且已脱敏（Cookie/Authorization/Set-Cookie 打 ***）"),
            "corpus" to mapOf("sources" to app.corpus.sourceCount, "hint" to "内置语料特征索引，做源前先 match_sources(域名)"),
            "license" to "GPL-3.0",
            "ai" to false,
            "role" to "mcp-runtime",
            "runtime" to listOf("official-css", "official-xpath", "official-jsonpath", "official-regex", "official-rhino", "webview"),
            "ui" to listOf("mcp", "sources", "skills", "verification", "logs"),
            "bookSourceType" to app.runtimeConfig.bookSourceType,
            "bookSourceTypeName" to com.mina.legadostudio.network.RuntimeConfigStore.typeName(app.runtimeConfig.bookSourceType),
            "bookSourceTypeHint" to "用户在 MCP 页选择的目标书源类型：-1 自动 / 0 文本 / 1 音频 / 2 图片 / 3 文件 / 4 视频；save_source 缺省时自动写入所选类型（自动则不写入，保留 JSON 原样），fetch_page 按类型提示二进制资源",
        )))
    }
    server.tool("app_status", "读取 MCP、权限、省电、局域网和验证会话状态", ToolSchema(properties = buildJsonObject {}, required = emptyList()), toolAnnotations = readOnly) {
        val mcp = com.mina.legadostudio.service.McpService.status(app, includeToken = false)
        val readiness = com.mina.legadostudio.device.DeviceReadiness(app).inspect((mcp["port"] as? Int) ?: McpConfigStore.DEFAULT_PORT, mcp["running"] == true)
        val waiting = app.database.dao().observeVerificationSessions().first().count { it.status == "WAITING" }
        ok(app.gson.toJson(mapOf(
            "mcp" to mcp,
            "readiness" to readiness,
            "waitingVerifications" to waiting,
            // 服务运行期间的工具使用概览（重启清零；app_status 自身不计入，见 McpToolUsage 说明）
            "toolUsage" to McpToolUsageRegistry.current.snapshot(),
            "ai" to false,
            "role" to "mcp-runtime",
        )))
    }
    server.tool("list_projects", "列出书源项目摘要", ToolSchema(properties = buildJsonObject {}, required = emptyList()), toolAnnotations = readOnly) {
        ok(app.gson.toJson(app.projects.list()))
    }
    server.tool("get_project", "按项目 ID 读取完整项目", schema(mapOf("id" to "项目 ID"), listOf("id")), toolAnnotations = readOnly) { req ->
        val id = req.arguments.str("id") ?: return@tool err("id 不能为空")
        app.projects.get(id)?.let { ok(app.gson.toJson(it)) } ?: err("项目不存在")
    }
    server.tool("save_project", "保存项目 JSON", schema(mapOf("project" to "Project JSON"), listOf("project")), toolAnnotations = write) { req ->
        runCatching {
            val root = JsonParser.parseString(req.arguments.str("project") ?: error("project 不能为空")).asJsonObject
            val id = root.get("id")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
            val old = app.projects.get(id)
            val now = System.currentTimeMillis()
            val project = com.mina.legadostudio.data.db.ProjectEntity(
                id = id,
                name = root.get("name")?.takeUnless { it.isJsonNull }?.asString.orEmpty().ifBlank { "未命名书源" },
                siteUrl = root.get("siteUrl")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                sourceJson = root.get("sourceJson")?.takeUnless { it.isJsonNull }?.asString ?: old?.sourceJson ?: "{}",
                stage = root.get("stage")?.takeUnless { it.isJsonNull }?.asString ?: old?.stage ?: "DRAFT",
                notes = root.get("notes")?.takeUnless { it.isJsonNull }?.asString ?: old?.notes.orEmpty(),
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
            )
            ok(app.gson.toJson(app.projects.save(project)))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("delete_projects", "删除项目", arraySchema("ids", "项目 ID 列表"), toolAnnotations = write) { req ->
        val ids = (req.arguments?.get("ids") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        ok("已删除 ${app.projects.delete(ids)} 个项目")
    }
}
