package com.mina.legadostudio.mcp

import com.google.gson.Gson
import com.mina.legadostudio.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * get_app_info 回包契约：缺省/detail=true 保持完整 workflow 字段；
 * 仅显式 detail=false 去掉 2000+ 字符流程散文。
 */
class AppInfoPayloadTest {

    private val gson = Gson()
    private val facts = AppInfoFacts(
        versionName = "1.3.5",
        versionCode = 196,
        packageName = "com.mina.legadostudio",
        mcpPath = "/mcp",
        corpusSourceCount = 42,
        bookSourceType = 0,
        bookSourceTypeName = "文本",
        buildRevision = "development",
        buildType = "debug",
    )

    private val originalTopKeys = listOf(
        "name", "version", "versionName", "versionCode",
        "buildRevision", "buildType", "buildRevisionNote", "mcpPath",
        "contextProtocol", "captureProtocol", "corpus", "license", "ai", "role",
        "runtime", "ui", "bookSourceType", "bookSourceTypeName", "bookSourceTypeHint",
    )

    private fun protocol(payload: Map<String, Any>): Map<*, *> =
        payload.getValue("contextProtocol") as Map<*, *>

    private fun source(): String = listOf(
        File("src/main/java/com/mina/legadostudio/mcp/McpProjectTools.kt"),
        File("app/src/main/java/com/mina/legadostudio/mcp/McpProjectTools.kt"),
    ).first { it.isFile }.readText()

    private fun getAppInfoBlock(): String {
        val src = source()
        val start = src.indexOf("server.tool(\"get_app_info\"")
        assertTrue("找不到 get_app_info 注册", start >= 0)
        val next = src.indexOf("server.tool(", start + 1)
        return src.substring(start, if (next > 0) next else src.length)
    }

    @Test
    fun omittedAndTrueKeepFullWorkflow() {
        assertTrue(appInfoIncludeWorkflow(null))
        assertTrue(appInfoIncludeWorkflow(true))
        assertFalse(appInfoIncludeWorkflow(false))
    }

    @Test
    fun defaultPayloadKeepsOriginalFieldContract() {
        val payload = buildAppInfoPayload(detail = true, facts = facts)
        originalTopKeys.forEach { key ->
            assertTrue("缺省回包缺少原字段 $key", payload.containsKey(key))
        }
        assertEquals("阅读书源MCP", payload["name"])
        assertEquals("1.3.5", payload["version"])
        assertEquals("1.3.5", payload["versionName"])
        assertEquals(196, payload["versionCode"])
        assertEquals("development", payload["buildRevision"])
        assertEquals("debug", payload["buildType"])
        assertEquals("/mcp", payload["mcpPath"])
        assertEquals(false, payload["ai"])
        assertEquals("mcp-runtime", payload["role"])
        assertEquals("GPL-3.0", payload["license"])
        assertEquals(0, payload["bookSourceType"])
        assertEquals("文本", payload["bookSourceTypeName"])
        assertEquals(APP_INFO_RUNTIME, payload["runtime"])
        assertEquals(APP_INFO_UI, payload["ui"])
        assertEquals(APP_INFO_BUILD_REVISION_NOTE, payload["buildRevisionNote"])
        assertEquals(APP_INFO_BOOK_SOURCE_TYPE_HINT, payload["bookSourceTypeHint"])

        val protocol = protocol(payload)
        assertEquals(2, protocol["version"])
        assertEquals(APP_INFO_CONTEXT_WORKFLOW, protocol["workflow"])
        assertEquals(APP_INFO_CONTEXT_HINT, protocol["hint"])
        assertEquals(300, protocol["cacheTtlSeconds"])
        assertEquals(1800, protocol["idleTtlSeconds"])
        assertEquals("memory+snapshot", protocol["persistence"])
        assertEquals(12000, protocol["maxInlineChars"])

        val capture = payload.getValue("captureProtocol") as Map<*, *>
        assertEquals(APP_INFO_CAPTURE_WORKFLOW, capture["workflow"])
        assertEquals(APP_INFO_CAPTURE_PER_HOP, capture["perHop"])
        assertEquals(APP_INFO_CAPTURE_PRIVACY, capture["privacy"])

        val corpus = payload.getValue("corpus") as Map<*, *>
        assertEquals(42, corpus["sources"])
        assertEquals(APP_INFO_CORPUS_HINT, corpus["hint"])
        assertFalse(payload.containsKey("workflowHint"))
    }

    @Test
    fun defaultWorkflowStillCarriesAuthAndReaderWebGuards() {
        val workflow = protocol(buildAppInfoPayload(detail = true, facts = facts))["workflow"].toString()
        assertTrue(workflow.contains("status=auth_required"))
        assertTrue(workflow.contains("不判离线"))
        assertTrue(workflow.contains("reader_web_page(action=open"))
        assertTrue(workflow.contains("不猜默认端口"))
        assertTrue(workflow.contains("originEcho"))
        assertTrue(workflow.contains("不断言不是阅读"))
        assertTrue("workflow 仍须是大段说明，实际 ${APP_INFO_CONTEXT_WORKFLOW.length}", APP_INFO_CONTEXT_WORKFLOW.length > 1000)
        assertTrue(
            "完整回包（含 workflow）应仍超 2000 字符",
            gson.toJson(buildAppInfoPayload(detail = true, facts = facts)).length > 2000,
        )
    }

    @Test
    fun compactOmitsWorkflowProseAndKeepsBasics() {
        val payload = buildAppInfoPayload(detail = false, facts = facts)
        assertEquals("阅读书源MCP", payload["name"])
        assertEquals("com.mina.legadostudio", payload["packageName"])
        assertEquals("1.3.5", payload["version"])
        assertEquals("1.3.5", payload["versionName"])
        assertEquals(196, payload["versionCode"])
        assertEquals("/mcp", payload["mcpPath"])
        assertEquals(APP_INFO_RUNTIME, payload["runtime"])
        assertEquals(APP_INFO_UI, payload["ui"])
        assertEquals(0, payload["bookSourceType"])
        assertEquals("文本", payload["bookSourceTypeName"])
        assertEquals(false, payload["ai"])
        assertEquals("mcp-runtime", payload["role"])
        assertEquals((payload["corpus"] as Map<*, *>)["sources"], 42)
        assertEquals(APP_INFO_WORKFLOW_HINT, payload["workflowHint"])

        val protocol = protocol(payload)
        assertEquals(2, protocol["version"])
        assertFalse(protocol.containsKey("workflow"))
        assertFalse(protocol.containsKey("hint"))
        assertFalse(payload.containsKey("captureProtocol"))
        assertFalse(payload.containsKey("buildRevisionNote"))
        assertFalse(payload.containsKey("bookSourceTypeHint"))
        assertFalse((payload["corpus"] as Map<*, *>).containsKey("hint"))

        val json = gson.toJson(payload)
        assertTrue("精简回包仍过大：${json.length}", json.length < 1200)
        assertFalse(json.contains("match_sources(语料命中)"))
        assertFalse(json.contains("reader_web_page(action=open"))
    }

    @Test
    fun compactIsMuchSmallerThanDefault() {
        val full = gson.toJson(buildAppInfoPayload(detail = true, facts = facts))
        val compact = gson.toJson(buildAppInfoPayload(detail = false, facts = facts))
        assertTrue("完整回包应仍含 2000+ workflow，实际 ${full.length}", full.length > 2500)
        assertTrue("精简应远小于完整：compact=${compact.length} full=${full.length}", compact.length * 3 < full.length)
    }

    @Test
    fun trueEqualsDefaultPayload() {
        val omitted = buildAppInfoPayload(detail = appInfoIncludeWorkflow(null), facts = facts)
        val explicit = buildAppInfoPayload(detail = appInfoIncludeWorkflow(true), facts = facts)
        assertEquals(gson.toJson(omitted), gson.toJson(explicit))
        assertEquals(APP_INFO_CONTEXT_WORKFLOW, protocol(explicit)["workflow"])
    }

    @Test
    fun liveFactsUseBuildConfigIdentity() {
        val live = AppInfoFacts(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            packageName = BuildConfig.APPLICATION_ID,
            mcpPath = McpAccess.PATH,
            corpusSourceCount = 0,
            bookSourceType = -1,
            bookSourceTypeName = "自动",
        )
        val compact = buildAppInfoPayload(detail = false, facts = live)
        assertEquals(BuildConfig.VERSION_NAME, compact["version"])
        assertEquals(BuildConfig.VERSION_CODE, compact["versionCode"])
        assertEquals(BuildConfig.APPLICATION_ID, compact["packageName"])
        assertEquals(BuildConfig.BUILD_REVISION, compact["buildRevision"])
        assertEquals(BuildConfig.BUILD_TYPE, compact["buildType"])
        assertEquals("/mcp", compact["mcpPath"])
    }

    @Test
    fun schemaAddsOptionalDetailBooleanWithoutRequired() {
        val block = getAppInfoBlock()
        assertTrue(block.contains("putJsonObject(\"detail\")"))
        assertTrue(block.contains("put(\"type\", \"boolean\")"))
        assertTrue(block.contains("required = emptyList()"))
        assertTrue(block.contains("appInfoIncludeWorkflow(req.arguments.bool(\"detail\"))"))
        assertTrue(block.contains("BuildConfig.APPLICATION_ID"))
    }
}
