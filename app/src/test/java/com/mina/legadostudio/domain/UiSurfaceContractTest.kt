package com.mina.legadostudio.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiSurfaceContractTest {
    private fun appKt(): String {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/StudioApp.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/StudioApp.kt"),
        )
        return candidates.first { it.isFile }.readText()
    }

    private fun walkAppSources(): Sequence<File> {
        val roots = listOf(File("src/main"), File("app/src/main")).filter { it.isDirectory }
        return roots.asSequence().flatMap { it.walkTopDown() }.filter { it.isFile && it.extension == "kt" }
    }

    @Test
    fun bottomBarHasMcpSourcesSkillsVerificationAndLogs() {
        val source = appKt()
        assertTrue(source.contains("StudioTab(\"mcp\""))
        assertTrue(source.contains("StudioTab(\"sources\""))
        assertTrue(source.contains("StudioTab(\"skills\""))
        assertTrue(source.contains("StudioTab(\"logs\""))
        assertFalse(source.contains("StudioTab(\"projects\""))
        assertTrue(source.contains("StudioTab(\"verification\", \"验证中心\""))
        assertFalse(source.contains("StudioTab(\"settings\""))
        assertFalse(source.contains("composable(\"projects\")"))
        assertFalse(source.contains("composable(\"settings\")"))
        assertFalse(source.contains("composable(\"webWorkbench\")"))
        assertFalse(source.contains("composable(\"advanced\")"))
        assertTrue(source.contains("composable(\"skills\")"))
        assertTrue(source.contains("composable(\"sources\")"))
        assertTrue(source.contains("composable(\"verification\")"))
        assertTrue(source.contains("composable(\"logs\")"))
    }

    @Test
    fun appSourcesDoNotNameClientBrands() {
        val hits = walkAppSources()
            .filter { file ->
                val text = file.readText()
                Regex("(?i)rikkahub|operit").containsMatchIn(text)
            }
            .map { it.path }
            .toList()
        assertTrue("client brand mentions: $hits", hits.isEmpty())
    }

    @Test
    fun mcpScreenCopiesAuthMaterialWithoutSourceList() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("复制 MCP"))
        assertTrue(source.contains("MCP 宿主"))
        assertTrue(source.contains("复制局域网 MCP"))
        assertTrue(source.contains("lanEndpoints"))
        assertTrue(source.contains("复制鉴权请求头"))
        assertTrue(source.contains("复制访问令牌"))
        assertTrue(source.contains("tokenHeaderLine"))
        assertFalse(source.contains("已保存书源"))
        assertFalse(source.contains("导入至阅读"))
        assertFalse(source.contains("ReaderImport"))
        assertFalse(source.contains("复制 JSON"))
        assertFalse(source.contains("复制 Token"))
        assertFalse(source.contains("全局请求头"))
        assertFalse(source.contains("MCP 条目"))
        assertFalse(source.contains("启动后显示本机回环链接"))
    }

    @Test
    fun sourcesScreenImportsAndDeletes() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/SourcesScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/SourcesScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("导入至阅读"))
        assertTrue(source.contains("projects.delete"))
        assertTrue(source.contains("launchReaderImport"))
        assertTrue(source.contains("删除书源"))
        assertTrue(source.contains("SourceCatalog.groupByDomain"))
        assertTrue(source.contains("展开"))
        assertFalse(source.contains("复制 JSON"))
    }

    @Test
    fun logsScreenHasNoExport() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertFalse(source.contains("导出选中"))
        assertFalse(source.contains("exportSelected"))
        assertFalse(source.contains("DiagnosticExporter"))
        assertTrue(source.contains("删除"))
        assertTrue(source.contains("LocalStudioFullscreen"))
        assertTrue(source.contains("HttpLogDetail"))
    }

    @Test
    fun mcpServerExposesStructuredLogTools() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/mcp/StudioMcpServer.kt"),
            File("app/src/main/java/com/mina/legadostudio/mcp/StudioMcpServer.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("\"get_logs\""))
        assertTrue(source.contains("\"get_log\""))
        assertTrue(source.contains("\"get_crash_logs\""))
        assertTrue(source.contains("\"get_crash_log\""))
        assertTrue(source.contains("\"get_diagnostic_snapshots\""))
        assertTrue(source.contains("\"get_diagnostic_snapshot\""))
        assertTrue(source.contains("listOf(\"mcp\", \"sources\", \"skills\", \"verification\", \"logs\")"))
    }

    @Test
    fun skillsScreenManagesCustomSkills() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/SkillsScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/SkillsScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("新增"))
        assertTrue(source.contains("导入"))
        assertTrue(source.contains("导出"))
        assertTrue(source.contains("删除"))
        assertTrue(source.contains("内置"))
        assertTrue(source.contains("自定义"))
        assertTrue(source.contains("onDelete = null"))
        assertTrue(source.contains("importPackage"))
        assertTrue(source.contains("exportPackage"))
        assertFalse(source.contains("webWorkbench"))
    }

    @Test
    fun manifestRegistersReaderImport() {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("android:scheme=\"legado\""))
        assertTrue(source.contains(".export.ReaderImportService"))
        assertTrue(source.contains("android.intent.action.VIEW"))
    }

    @Test
    fun skillAssetsDoNotUseYueDuNames() {
        val roots = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).filter { it.isDirectory }
        val hits = roots.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "md" }
            .filter { Regex("YueDU|mcp__YueDU").containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()
        assertTrue("YueDU leftovers: $hits", hits.isEmpty())
    }
}
