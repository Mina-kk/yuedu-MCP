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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** get_app_info 的构建标识字段：纯函数便于 JVM 单测锁定契约。 */
internal fun buildMetadata(): Map<String, String> = mapOf(
    "buildRevision" to BuildConfig.BUILD_REVISION,
    "buildType" to BuildConfig.BUILD_TYPE,
)

/** 缺省或 true = 完整 workflow（后向兼容）；仅显式 false 走精简。 */
internal fun appInfoIncludeWorkflow(detail: Boolean?): Boolean = detail != false

internal const val APP_INFO_BUILD_REVISION_NOTE =
    "buildRevision 为构建时注入的源码修订号；development 表示本地构建未注入修订号，与 buildType(debug/release) 互不相关，版本以 versionName/versionCode 为准"

internal const val APP_INFO_CONTEXT_WORKFLOW =
    "match_sources(语料命中) → create_context → fetch_page → read_page/inspect_rule/analyze_html/eval_js(pageId) → update_context；JS渲染页用 webview_render(url 或 url+pageId) 转真实DOM；半成品存 save_project、成品 save_source 后 get_source 回读；check_source(refresh=true) 仅为可选沙箱取证，不是保存或阅读调试的前置条件；阅读端闭环默认执行且网页优先：reader_web_status 探测（读 originEcho 的当前真实端口、不猜默认端口；webPageReachable/apiProfile 只是能力提示，status=auth_required/authReady=false 时 401 是已响应、不判离线/不是阅读，先补令牌 configure_reader_web_token，不继续结构化同步/检验）→按授权 reader_web_sync_source(projectId,keyword) 同步当前版本并自动调试（旧源不同默认 conflict 不写，须用户授权才 overwrite=true；confirmed=true 只说明字段回读一致，不代表规则通过）→reader_web_debug(bookSourceUrl,keyword) 在阅读端针对未覆盖阶段真实调试（machineVerdict 仍为 unverifiable，四阶段到达不等于字段正确）→reader_web_page(action=open/snapshot/click/press_enter/fill/scroll/back/close) 在用户保存的阅读网页里核对实际搜索、书架、目录、正文和发现；status=web_page_available 或 apiProfile=mismatch 只说明已知接口不匹配，网页照常用、不断言不是阅读、不要求升级；status=api_only_available 只说明接口可达、网页入口与书源都未证实；根路径 200 不算书源通过；click/fill 有副作用，保存/覆盖/删除书源须用户确认；reader_web_search(keyword) 是可选全局搜索、不可冒充单源；reader_web_verify(bookSourceUrl,keyword) 仅在结构化接口可用时是可选快捷，旧版仅 legacy_debug_only/unverifiable，不判 PASSED；阅读 Web 未配置/不可达/鉴权未通过如实标未覆盖，import_reader 只唤起阅读导入页、不等于导入成功；新装 APK 后客户端须重连刷新 tools/list；重连后get_context或list_contexts"

internal const val APP_INFO_CONTEXT_HINT =
    "调用时传contextId；并行做多个书源时每个任务各自 create_context 并全程显式传同一 contextId，互不串数据；POST搜索也入缓存；大结果用read_result不重复运行；check_source/debug_source默认复用本任务快照；沙箱要获取新样本时才传refresh=true，不能替代阅读端执行；check_source 可加 exploreUrl/exploreKey 分析发现链；阅读网页证据与结构化 PASSED 均与本机 check_source 证据互不替代"

internal const val APP_INFO_CAPTURE_WORKFLOW =
    "capture_once(url,method,headers,body) 发起→返回 contextId/logs/hopLogIds/finalLogIds→get_capture(contextId) 回看逐跳→get_http_log(id) 看单条详情→list_captures 翻历史会话"

internal const val APP_INFO_CAPTURE_PER_HOP =
    "重定向中间跳另记 originKind=capture_hop 独立日志（3xx+Location，同 cap:contextId/sourceAnchor），被SSRF拦截的跳也先落库"

internal const val APP_INFO_CAPTURE_PRIVACY =
    "URL/query 可能带令牌；正文/头经 get_http_log 查看且已脱敏（Cookie/Authorization/Set-Cookie 打 ***）"

internal const val APP_INFO_CORPUS_HINT = "内置语料特征索引，做源前先 match_sources(域名)"

internal const val APP_INFO_BOOK_SOURCE_TYPE_HINT =
    "用户在 MCP 页选择的目标书源类型：-1 自动 / 0 文本 / 1 音频 / 2 图片 / 3 文件 / 4 视频；save_source 缺省时自动写入所选类型（自动则不写入，保留 JSON 原样），fetch_page 按类型提示二进制资源"

internal const val APP_INFO_WORKFLOW_HINT = "完整 workflow 见 get_app_info(detail=true) 或 get_skill"

internal val APP_INFO_RUNTIME = listOf("official-css", "official-xpath", "official-jsonpath", "official-regex", "official-rhino", "webview")
internal val APP_INFO_UI = listOf("mcp", "sources", "skills", "verification", "logs")

internal data class AppInfoFacts(
    val versionName: String,
    val versionCode: Int,
    val packageName: String,
    val mcpPath: String,
    val corpusSourceCount: Int,
    val bookSourceType: Int,
    val bookSourceTypeName: String,
    val buildRevision: String = buildMetadata().getValue("buildRevision"),
    val buildType: String = buildMetadata().getValue("buildType"),
)

/** get_app_info 回包：detail=true（及缺省）保持原字段契约；detail=false 去掉 workflow 散文。 */
internal fun buildAppInfoPayload(detail: Boolean, facts: AppInfoFacts): Map<String, Any> = buildMap {
    put("name", "阅读书源MCP")
    put("packageName", facts.packageName)
    put("version", facts.versionName)
    put("versionName", facts.versionName)
    put("versionCode", facts.versionCode)
    put("buildRevision", facts.buildRevision)
    put("buildType", facts.buildType)
    if (detail) put("buildRevisionNote", APP_INFO_BUILD_REVISION_NOTE)
    put("mcpPath", facts.mcpPath)
    put(
        "contextProtocol",
        if (detail) mapOf(
            "version" to 2,
            "workflow" to APP_INFO_CONTEXT_WORKFLOW,
            "cacheTtlSeconds" to 300,
            "idleTtlSeconds" to 1800,
            "persistence" to "memory+snapshot",
            "maxInlineChars" to 12000,
            "hint" to APP_INFO_CONTEXT_HINT,
        ) else mapOf(
            "version" to 2,
            "cacheTtlSeconds" to 300,
            "idleTtlSeconds" to 1800,
            "persistence" to "memory+snapshot",
            "maxInlineChars" to 12000,
        ),
    )
    if (detail) {
        put(
            "captureProtocol",
            mapOf(
                "workflow" to APP_INFO_CAPTURE_WORKFLOW,
                "perHop" to APP_INFO_CAPTURE_PER_HOP,
                "privacy" to APP_INFO_CAPTURE_PRIVACY,
            ),
        )
    }
    put(
        "corpus",
        if (detail) mapOf("sources" to facts.corpusSourceCount, "hint" to APP_INFO_CORPUS_HINT)
        else mapOf("sources" to facts.corpusSourceCount),
    )
    put("license", "GPL-3.0")
    put("ai", false)
    put("role", "mcp-runtime")
    put("runtime", APP_INFO_RUNTIME)
    put("ui", APP_INFO_UI)
    put("bookSourceType", facts.bookSourceType)
    put("bookSourceTypeName", facts.bookSourceTypeName)
    if (detail) put("bookSourceTypeHint", APP_INFO_BOOK_SOURCE_TYPE_HINT)
    else put("workflowHint", APP_INFO_WORKFLOW_HINT)
}

internal fun StudioMcpServer.registerProjectTools(server: Server) {
    server.tool("get_app_info", "读取阅读书源MCP版本和能力。detail=false 只回版本/包名/能力；缺省或 true 含完整 workflow", ToolSchema(properties = buildJsonObject {
        putJsonObject("detail") { put("type", "boolean"); put("description", "false=精简（版本/包名/能力/语料计数）；缺省或 true=完整 workflow（后向兼容）") }
    }, required = emptyList()), toolAnnotations = readOnly) { req ->
        val detail = appInfoIncludeWorkflow(req.arguments.bool("detail"))
        ok(app.gson.toJson(buildAppInfoPayload(
            detail = detail,
            facts = AppInfoFacts(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                packageName = BuildConfig.APPLICATION_ID,
                mcpPath = McpAccess.PATH,
                corpusSourceCount = app.corpus.sourceCount,
                bookSourceType = app.runtimeConfig.bookSourceType,
                bookSourceTypeName = com.mina.legadostudio.network.RuntimeConfigStore.typeName(app.runtimeConfig.bookSourceType),
            ),
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
            "runtimeCheck" to com.mina.legadostudio.domain.RuntimeSelfCheck.inspect(),
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
    server.tool("delete_projects", "删除项目", arraySchema("ids", "项目 ID 列表"), toolAnnotations = destructive) { req ->
        val ids = (req.arguments?.get("ids") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        ok("已删除 ${app.projects.delete(ids)} 个项目")
    }
}
