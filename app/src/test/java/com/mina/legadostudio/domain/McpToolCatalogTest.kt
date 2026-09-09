package com.mina.legadostudio.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpToolCatalogTest {
    private fun studioMcpServerSource(): String {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/mcp/StudioMcpServer.kt"),
            File("app/src/main/java/com/mina/legadostudio/mcp/StudioMcpServer.kt"),
        )
        return candidates.first { it.isFile }.readText()
    }

    private fun toolNames(): Set<String> =
        Regex("""(?:addTool|tool)\("([^"]+)"""").findAll(studioMcpServerSource()).map { it.groupValues[1] }.toSet()

    @Test
    fun rejectsAiAndJobTools() {
        val forbidden = setOf(
            "list_models",
            "test_model",
            "ai_generate_source",
            "ai_repair_source",
            "start_job",
            "get_job",
            "cancel_job",
            "resume_job",
        )
        val found = toolNames().intersect(forbidden)
        assertTrue("AI/job tools still registered: $found", found.isEmpty())
    }

    @Test
    fun keepsSkillAndRuntimeTools() {
        val required = setOf(
            "list_skills",
            "get_skill",
            "save_skill",
            "delete_skill",
            "search_knowledge",
            "list_sources",
            "get_source",
            "save_source",
            "debug_source",
            "check_source",
            "inspect_rule",
            "eval_js",
            "get_logs",
            "get_log",
            "get_http_logs",
            "get_http_log",
            "get_crash_logs",
            "get_crash_log",
            "get_diagnostic_snapshots",
            "get_diagnostic_snapshot",
            "create_context",
            "get_context",
            "update_context",
            "clear_context",
            "list_contexts",
            "read_page",
            "read_result",
        )
        val missing = required - toolNames()
        assertTrue("missing required tools: $missing", missing.isEmpty())
    }

    @Test
    fun getAppInfoDeclaresNoAi() {
        val source = studioMcpServerSource()
        assertTrue(source.contains("\"ai\" to false"))
        assertTrue(source.contains("\"role\" to \"mcp-runtime\""))
        assertTrue(source.contains("\"ui\" to listOf(\"mcp\", \"sources\", \"skills\", \"verification\", \"logs\")"))
        assertTrue(source.contains("默认覆盖当前成品"))
        assertTrue(source.contains("newVersion"))
    }
}
