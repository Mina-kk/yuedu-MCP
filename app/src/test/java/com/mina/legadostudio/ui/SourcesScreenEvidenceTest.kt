package com.mina.legadostudio.ui

import com.google.gson.JsonParser
import com.mina.legadostudio.data.db.ProjectEntity
import com.mina.legadostudio.domain.SourceFilterPolicy
import com.mina.legadostudio.mcp.McpSourceTools
import com.mina.legadostudio.ui.screens.exportArrayJson
import com.mina.legadostudio.ui.screens.resolveCheckEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SourcesScreen「分析/过滤」证据隔离与导出决策的纯函数回归：
 * - checkEvidence 按 projectId + sourceJson 指纹存，列表排序/内容变更不串结果；
 * - 导出决策走 SourceFilterPolicy.filter + 本地数组组装（无 MCP 内联上限），
 *   预览保留集之外的源绝不进导出，空保留集阻止空导入。
 */
class SourcesScreenEvidenceTest {

    private fun project(id: String, siteUrl: String, sourceJson: String, updatedAt: Long = 0L) =
        ProjectEntity(id = id, name = "n-$id", siteUrl = siteUrl, sourceJson = sourceJson,
            stage = "VALIDATED", notes = "", createdAt = 0L, updatedAt = updatedAt)

    private fun source(url: String, name: String = "示例", extra: String = "") =
        """{"bookSourceName":"$name","bookSourceUrl":"$url"$extra}"""

    private fun check(tag: String): Map<String, Any> = mapOf("tag" to tag, "搜索" to mapOf("ok" to true))

    // ---------- resolveCheckEvidence：按 projectId+指纹隔离，不按位置 ----------

    @Test fun `evidence follows projectId across reorder`() {
        val a = project("p1", "https://a.com/x", source("https://a.com/x"))
        val b = project("p2", "https://b.com/y", source("https://b.com/y"))
        // 检测发起时 p1 在下标 0；库里后来插入新项使 p1 挪到下标 1，结果仍须认对
        val evidence = mapOf("p1" to (a.sourceJson to check("for-p1")))
        val reordered = listOf(b, a)
        val resolved = resolveCheckEvidence(reordered, evidence)
        assertEquals(mapOf(1 to check("for-p1")), resolved.byIndex)
        assertEquals(0, resolved.staleCount)
    }

    @Test fun `stale fingerprint evidence is dropped not reused`() {
        val oldJson = source("https://a.com/x")
        val newJson = source("https://a.com/x", extra = ",\"ruleContent\":{\"content\":\"#new\"}")
        val p = project("p1", "https://a.com/x", newJson) // 同 ID 内容已更新
        val evidence = mapOf("p1" to (oldJson to check("stale")))
        val resolved = resolveCheckEvidence(listOf(p), evidence)
        assertTrue(resolved.byIndex.isEmpty())
        assertEquals(1, resolved.staleCount)
    }

    @Test fun `deleted project evidence is dropped silently`() {
        val evidence = mapOf("ghost" to (source("https://a.com/x") to check("x")))
        val resolved = resolveCheckEvidence(listOf(project("p1", "https://a.com/x", source("https://a.com/x"))), evidence)
        assertTrue(resolved.byIndex.isEmpty())
        assertEquals(0, resolved.staleCount)
    }

    @Test fun `mixed evidence keeps valid and drops stale`() {
        val a = project("p1", "https://a.com/x", source("https://a.com/x"))
        val b = project("p2", "https://b.com/y", source("https://b.com/y"))
        val evidence = mapOf(
            "p1" to (a.sourceJson to check("ok")),
            "p2" to ("${b.sourceJson} " to check("stale")), // 指纹差一个空格也算内容变了
        )
        val resolved = resolveCheckEvidence(listOf(a, b), evidence)
        assertEquals(setOf(0), resolved.byIndex.keys)
        assertEquals(1, resolved.staleCount)
    }

    // ---------- 导出决策：预览保留集之外的源不进导出；空集阻止 ----------

    private fun exportRows(projects: List<ProjectEntity>, keptIdx: Set<Int>, collapse: Boolean): Pair<String?, String?> {
        val rows = keptIdx.sorted().map { projects[it] }
            .map { McpSourceTools.ExportRow(it.id, it.siteUrl, it.sourceJson, it.updatedAt) }
        // 直接调被测函数 exportArrayJson（internal 顶层），不再复制其管线——真回归。
        return exportArrayJson(rows, collapse)
    }

    @Test fun `excluded source never appears in export payload`() {
        val login = project("p1", "https://a.com/l", source("https://a.com/l", extra = ",\"loginUrl\":\"https://a.com/l\""))
        val ok = project("p2", "https://b.com/ok", source("https://b.com/ok"))
        // 模拟 UI 管线：excludeLogin 排除 p1，只导 p2
        val filter = SourceFilterPolicy.filter(listOf(login.sourceJson, ok.sourceJson), null, setOf(SourceFilterPolicy.Exclude.LOGIN))
        val keptIdx = filter.kept.map { it.index }.toSet()
        val (payload, err) = exportRows(listOf(login, ok), keptIdx, collapse = false)
        assertNull(err)
        val urls = JsonParser.parseString(payload).asJsonArray.map { it.asJsonObject.get("bookSourceUrl").asString }
        assertEquals(listOf("https://b.com/ok"), urls)
    }

    @Test fun `empty kept blocks export`() {
        val (payload, err) = exportRows(listOf(project("p1", "https://a.com/x", source("https://a.com/x"))), emptySet(), collapse = false)
        assertNull(payload)
        assertTrue(err != null)
    }

    @Test fun `collapse folds only export_one cluster`() {
        val same = source("https://a.com/x")
        val a = project("p1", "https://a.com/x", same, updatedAt = 1)
        val b = project("p2", "https://a.com/x", same, updatedAt = 2)
        val c1 = project("p3", "https://a.com/y", source("https://a.com/y", extra = ",\"ruleContent\":{\"content\":\"#a\"}"))
        val c2 = project("p4", "https://a.com/y", source("https://a.com/y", extra = ",\"ruleContent\":{\"content\":\"#b\"}"))
        val (payload, _) = exportRows(listOf(a, b, c1, c2), setOf(0, 1, 2, 3), collapse = true)
        assertEquals(3, JsonParser.parseString(payload).asJsonArray.size())
    }

    // ---------- exportArrayJson 逐字节原文保真（回归：不得用 JsonArray 重序列化） ----------

    @Test fun `export preserves sourceJson byte-for-byte and kept order`() {
        // 刻意的非常规 JSON：键序非字典序、带空白缩进、值里有逗号/中文——任何重序列化都会改形
        val weirdOrder = "{\n  \"bookSourceUrl\":\"https://a.com/z\",\n  \"bookSourceName\":\"z，名\"\n}"
        val normal = source("https://b.com/ok")
        // kept 顺序故意与传入项目序不同（keptIdx 传入时已排序，这里直接构造 ExportRow 列表控序）
        val rows = listOf(
            McpSourceTools.ExportRow("pz", "https://a.com/z", weirdOrder, 0L),
            McpSourceTools.ExportRow("pb", "https://b.com/ok", normal, 0L),
        )
        val (payload, err) = exportArrayJson(rows, collapseIdentical = false)
        assertNull(err)
        // 结果 = "[" + 原文0 + "," + 原文1 + "]"：逐字节相等，顺序不变，无重序列化
        assertEquals("[$weirdOrder,$normal]", payload)
        // 且仍是合法 JSON 数组、成员与原文逐字节一致
        val arr = JsonParser.parseString(payload).asJsonArray
        assertEquals(2, arr.size())
        assertEquals("z，名", arr[0].asJsonObject.get("bookSourceName").asString)
        assertEquals("https://b.com/ok", arr[1].asJsonObject.get("bookSourceUrl").asString)
    }

    @Test fun `export rejects invalid json member instead of emitting placeholder`() {
        val good = source("https://b.com/ok")
        val bad = "{broken json" // 非合法 JSON 对象
        val rows = listOf(
            McpSourceTools.ExportRow("p1", "https://b.com/ok", good, 0L),
            McpSourceTools.ExportRow("p2", "https://c.com/bad", bad, 0L),
        )
        val (payload, err) = exportArrayJson(rows, collapseIdentical = false)
        assertNull("坏成员必须中止导出而不是产出伪可用对象", payload)
        assertTrue(err != null && err.contains("p2"))
    }

    @Test fun `collapse still emits kept member as raw sourceJson`() {
        // 折叠后留下的那一条仍须是原文（不因为走了 dedupe 就被改写）
        val same = source("https://a.com/x", name = "同", extra = ",\"ruleContent\":{\"content\":\"#c\"}")
        val a = project("p1", "https://a.com/x", same, updatedAt = 1)
        val b = project("p2", "https://a.com/x", same, updatedAt = 2)
        val (payload, err) = exportRows(listOf(a, b), setOf(0, 1), collapse = true)
        assertNull(err)
        assertEquals("[$same]", payload) // 折叠成一条，且是被保留那条的原文
    }
}
