package com.mina.legadostudio.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpToolContextPolicy 的 dispatch 级回归（对应真实 wrapper 行为，非字符串断言）：
 * P0 —— wrapper 曾对所有 contextId 一律 describe(cap:xxx)，把抓包会话当任务上下文查，
 * 于是 get_capture / poll_capture / get_http_logs(contextId=cap:...) 在 handler 之前
 * 被 CONTEXT_EXPIRED_OR_UNKNOWN 拦死。本测试直接调生产判定函数锁定放行业界。
 */
class McpToolContextPolicyTest {

    // ---- cap: 抓包会话 id：一律放行给 handler，不送 TaskContextStore ----

    @Test fun `cap contextId never hits task-context store regardless of tool`() {
        val cap = "cap:9f1c2a-...-z"
        // 抓包会话读取工具
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("get_capture", cap))
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("poll_capture", cap))
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("get_capture_resource", cap))
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("get_http_logs", cap))
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("list_captures", cap))
        // 即使误传给任务上下文工具，也不送 describe（handler 内部 cap:/contextId 校验拦截）
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("fetch_page", cap))
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("read_page", cap))
    }

    // ---- 普通任务 contextId：只有任务上下文工具校验 ----

    @Test fun `task contextId validates only for task-context tools`() {
        val taskId = "5f2a1b3c-real-uuid"
        // 任务上下文工具：校验
        assertTrue(McpToolContextPolicy.shouldValidateTaskContext("fetch_page", taskId))
        assertTrue(McpToolContextPolicy.shouldValidateTaskContext("read_page", taskId))
        assertTrue(McpToolContextPolicy.shouldValidateTaskContext("eval_js", taskId))
        assertTrue(McpToolContextPolicy.shouldValidateTaskContext("debug_source", taskId))
        // 非任务工具：不校验（其 contextId 是日志归属筛选参数，describe 反而 touch 无关任务）
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("get_http_logs", taskId))
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("get_capture", taskId))
    }

    // ---- 缺省/空 contextId：不校验 ----

    @Test fun `absent or blank contextId skips validation`() {
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("fetch_page", null))
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("fetch_page", ""))
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("fetch_page", "   "))
        assertFalse(McpToolContextPolicy.shouldValidateTaskContext("get_capture", null))
    }

    // ---- CONTEXT_TOOLS 与 schema 注入名单一致（防两处漂移） ----

    @Test fun `context tool allowlist covers the task-context surface`() {
        val tools = McpToolContextPolicy.CONTEXT_TOOLS
        // 任务上下文核心工具都在名单内
        listOf("fetch_page", "read_page", "read_result", "inspect_rule", "analyze_html",
            "eval_js", "debug_source", "check_source", "create_context", "get_context",
            "update_context", "clear_context", "fetch_font", "get_font_map", "webview_render",
            "webview_eval").forEach {
            assertTrue("$it 应在 CONTEXT_TOOLS", it in tools)
        }
        // 抓包/日志工具绝不在名单内（否则 cap: 会被拦）
        listOf("get_capture", "poll_capture", "get_capture_resource", "get_http_logs",
            "get_http_log", "list_captures", "capture_once", "webview_capture").forEach {
            assertFalse("$it 不应在 CONTEXT_TOOLS", it in tools)
        }
    }
}
