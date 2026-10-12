package com.mina.legadostudio.mcp

/**
 * MCP 工具包装层的 contextId 判定策略（纯函数，JVM 可测）。
 *
 * 背景：工具 wrapper 曾对所有 `contextId` 参数一律 `TaskContextStore.describe` 校验，
 * 结果把抓包会话 id（`cap:xxx`，形如 `cap:<uuid>`）当成任务上下文去查——
 * `cap:` 从不进 TaskContextStore，于是 get_capture / poll_capture / get_http_logs(contextId=cap:...) /
 * get_capture_resource 全部在 handler 之前被 CONTEXT_EXPIRED_OR_UNKNOWN 拦死。
 *
 * 修复原则（不宽泛绕过任务鉴权）：
 * - 只有「真正消费任务上下文」的工具（[CONTEXT_TOOLS]）才把 contextId 交给 TaskContextStore 校验；
 *   它们收到 `cap:` 前缀的 id 视为误传（抓包会话 id 不是任务上下文），不校验、交给 handler
 *   内部的 cap: 白名单/`contextId(args)` 校验拦截——不会静默放行到 fetch_page 等真任务路径。
 * - 抓包/日志查询工具不在 [CONTEXT_TOOLS]：它们的 contextId 是数据筛选参数（cap: 会话 id 或
 *   任务上下文字符串做日志归属匹配），不送 TaskContextStore.describe——handler 自己按
 *   `cap:` 前缀 / DAO 精确匹配判定存在性，误传普通任务 id 也不会被静默 touch（describe 会
 *   刷新闲置计时，反而给无关任务上下文延寿）。
 */
object McpToolContextPolicy {

    /** 抓包会话 id 前缀（cap:<uuid>）：与 [com.mina.legadostudio.network.CaptureOnce] 生成口径一致。 */
    const val CAPTURE_CONTEXT_PREFIX = "cap:"

    /**
     * 该 (toolName, contextId) 组合是否应交 TaskContextStore.describe 做「任务上下文存在性」校验。
     *
     * @return true = 需要 describe（任务上下文工具 + 非 cap: 的任务 contextId）；
     *   false = 放行给 handler（非任务工具，或 contextId 是 cap: 抓包会话 id）。
     */
    fun shouldValidateTaskContext(toolName: String, contextId: String?): Boolean {
        if (contextId.isNullOrBlank()) return false                 // 没传 contextId：无可校验
        if (contextId.startsWith(CAPTURE_CONTEXT_PREFIX)) return false // cap: 抓包会话 id：非任务上下文，放行给 handler
        return toolName in CONTEXT_TOOLS                             // 仅任务上下文工具校验普通 contextId
    }

    /**
     * 真正使用任务上下文的工具白名单（与 StudioMcpServer.CONTEXT_TOOLS 同源——
     * schema 注入 contextId 与 dispatch 校验共用同一份名单，两处不再漂移）。
     */
    val CONTEXT_TOOLS: Set<String> = setOf(
        "fetch_page", "read_page", "read_result", "inspect_rule", "analyze_html", "eval_js",
        "debug_source", "check_source", "create_context", "get_context", "update_context", "clear_context",
        "fetch_font", "get_font_map", "webview_render", "webview_eval",
    )
}
