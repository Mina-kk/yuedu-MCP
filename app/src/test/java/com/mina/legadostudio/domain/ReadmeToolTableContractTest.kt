package com.mina.legadostudio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadmeToolTableContractTest {
    private fun firstExisting(vararg paths: String): File =
        paths.map(::File).firstOrNull { it.isFile } ?: error("not found: ${paths.toList()}")

    private fun registeredTools(): Set<String> {
        val mcpDir = listOf(
            File("src/main/java/com/mina/legadostudio/mcp"),
            File("app/src/main/java/com/mina/legadostudio/mcp"),
        ).first { it.isDirectory }
        val pattern = Regex("""(?:addTool|tool)\("([a-z_]+)"""")
        return mcpDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> pattern.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()
    }

    private fun readmeTools(): Set<String> {
        val lines = firstExisting("../README.md", "README.md").readLines()
        val start = lines.indexOfFirst { it.trim() == "## MCP 工具一览" }
        assertTrue("README 缺少「## MCP 工具一览」章节", start >= 0)
        val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
        return section
            .filter { it.startsWith("|") && !it.startsWith("|---") }
            .flatMap { row -> Regex("""`([a-z_]+)`""").findAll(row).map { it.groupValues[1] } }
            .toSet()
    }

    @Test
    fun readmeToolTableMatchesRegisteredTools() {
        val code = registeredTools()
        val readme = readmeTools()
        assertTrue("未解析到任何注册工具", code.isNotEmpty())
        assertEquals("代码已注册但 README 工具表缺失", emptySet<String>(), code - readme)
        assertEquals("README 工具表列出但代码未注册", emptySet<String>(), readme - code)
    }

    @Test
    fun readmeToolCountLineMatches() {
        val readme = firstExisting("../README.md", "README.md").readText()
        val declared = Regex("""共 (\d+) 个工具""").find(readme)?.groupValues?.get(1)?.toInt()
        assertEquals("README「共 N 个工具」与实际注册数不一致", registeredTools().size, declared)
    }

    @Test
    fun readmeVersionMatchesGradle() {
        val gradle = firstExisting("build.gradle.kts", "app/build.gradle.kts").readText()
        val name = Regex("""versionName\s*=\s*"([^"]+)"""").find(gradle)!!.groupValues[1]
        val code = Regex("""versionCode\s*=\s*(\d+)""").find(gradle)!!.groupValues[1]
        val readme = firstExisting("../README.md", "README.md").readText()
        assertTrue("README 版本号未同步为 $name（versionCode $code）", readme.contains("`$name`（versionCode $code"))
    }
}
