package com.mina.legadostudio.mcp

import com.google.gson.JsonParser
import com.mina.legadostudio.data.db.ProjectEntity
import com.mina.legadostudio.domain.SourceFilterPolicy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * analyze_sources / export_checked_sources / save_source / list_sources 的纯函数层回归：
 * 全部走 McpSourceTools（不依赖 Android Context、不联网），覆盖
 * - 入参边界（数量/大小/类型）与明确报错（不静默跳过）；
 * - save_source 身份判定：同 URL 不同 JSON → 新版本，完全一致 → 幂等覆写，
 *   尾部 / 与 #标签差异视为不同身份（旧 trimEnd('/') 归并已移除）；
 * - export 折叠口径 = dedupe export_one：冲突/不确定簇绝不折叠，坏 JSON 立即报错不产伪可用对象。
 */
class McpSourceToolsTest {

    private fun project(id: String, siteUrl: String, sourceJson: String, updatedAt: Long = 0L) =
        ProjectEntity(id = id, name = "n-$id", siteUrl = siteUrl, sourceJson = sourceJson,
            stage = "VALIDATED", notes = "", createdAt = 0L, updatedAt = updatedAt)

    private fun source(url: String, name: String = "示例", type: Int = 0, extra: String = "") =
        """{"bookSourceName":"$name","bookSourceUrl":"$url","bookSourceType":$type$extra}"""

    private fun saveObj(url: String, extra: String = "") =
        JsonParser.parseString(source(url, extra = extra)).asJsonObject

    // ---------- parseSourceArray 边界 ----------

    @Test fun `sources array parsed and order preserved`() {
        val (rows, err) = McpSourceTools.parseSourceArray(
            """[${source("https://a.com/x")},${source("https://b.com/y")}]""")
        assertNull(err)
        assertEquals(2, rows!!.size)
        assertTrue(rows[0].contains("a.com"))
    }

    @Test fun `sources non-array is explicit error`() {
        val (_, err) = McpSourceTools.parseSourceArray("""{"bookSourceUrl":"https://a.com"}""")
        assertTrue(err!!.contains("数组"))
    }

    @Test fun `sources malformed json is explicit error`() {
        val (_, err) = McpSourceTools.parseSourceArray("[{broken")
        assertTrue(err != null)
    }

    @Test fun `sources oversized item rejected`() {
        val big = "x".repeat(McpSourceTools.MAX_ITEM_CHARS + 1)
        val (_, err) = McpSourceTools.parseSourceArray("""[{"bookSourceUrl":"https://a.com","pad":"$big"}]""")
        assertTrue(err!!.contains("过大"))
    }

    @Test fun `sources count cap enforced`() {
        val arr = (1..McpSourceTools.MAX_IDS + 1).joinToString(",") { source("https://a.com/$it") }
        val (_, err) = McpSourceTools.parseSourceArray("[$arr]")
        assertTrue(err!!.contains("最多"))
    }

    // ---------- parseExcludes ----------

    @Test fun `unknown exclude dimension is explicit error`() {
        val args = buildJsonObject { putJsonObject("exclude") { put("excludeVip", true) } }
        val (_, err) = McpSourceTools.parseExcludes(args)
        assertTrue(err!!.contains("excludeVip"))
    }

    @Test fun `non-boolean exclude value is explicit error`() {
        val args = buildJsonObject { putJsonObject("exclude") { put("excludeLogin", "yes") } }
        val (_, err) = McpSourceTools.parseExcludes(args)
        assertTrue(err!!.contains("布尔"))
    }

    @Test fun `default empty excludes keeps everything`() {
        val (set, err) = McpSourceTools.parseExcludes(buildJsonObject {})
        assertNull(err); assertTrue(set.isEmpty())
    }

    // ---------- parseCheckResults（外部自述，不做 verified 判定） ----------

    @Test fun `checkResults bad key rejected`() {
        val (_, err) = McpSourceTools.parseCheckResults("""{"x":{"搜索":{}}}""")
        assertTrue(err != null)
    }

    @Test fun `checkResults parses to JsonObject map for echo only`() {
        val (claims, err) = McpSourceTools.parseCheckResults("""{"0":{"搜索":{"type":"x"}},"2":{"verdict":{}}}""")
        assertNull(err)
        assertEquals(setOf(0, 2), claims!!.keys)
        assertTrue(claims[0]!!.has("搜索"))
    }

    @Test fun `external claims summary echoes index and stages without trusting`() {
        val claims = mapOf(1 to JsonParser.parseString("""{"搜索":{},"详情":{},"verdict":{}}""").asJsonObject)
        val summary = McpSourceTools.externalClaimsSummary(claims)
        assertEquals(1, summary.size)
        assertEquals(1, summary[0]["index"])
        assertEquals(true, summary[0]["hasVerdict"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("搜索", "详情").sorted(), (summary[0]["declaredStages"] as List<String>).sorted())
    }

    // ---------- CheckEvidenceRegistry（verified/failed 唯一可信来源） ----------

    private fun okReport(stage: String) = com.mina.legadostudio.runtime.LegadoRuntime.DebugReport(
        stage, "e", listOf("HTTP 200"),
        when (stage) {
            "搜索" -> listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b"))
            "详情" -> mapOf("name" to "书", "tocUrl" to "https://a.com/t")
            "目录" -> mapOf("totalChapters" to 1, "chapters" to listOf(mapOf("url" to "https://a.com/c1")))
            else -> mapOf("pages" to 1, "content" to "正文内容正文", "contentTotalChars" to 6)
        },
    )

    @Test fun `registry binds evidence to exact sourceJson hash`() {
        McpSourceTools.CheckEvidenceRegistry.clear()
        val src = source("https://h.com/x")
        val now = System.currentTimeMillis()
        McpSourceTools.CheckEvidenceRegistry.register(src, mapOf("搜索" to okReport("搜索")), refresh = true, now = now)
        // 同一串命中；改一个字符（不同内容）不命中
        assertTrue(McpSourceTools.CheckEvidenceRegistry.hasTrustedEvidence(src, now))
        assertFalse(McpSourceTools.CheckEvidenceRegistry.hasTrustedEvidence(source("https://h.com/other"), now))
    }

    @Test fun `trusted evidence feeds filter and unverified sources stay unverified`() {
        McpSourceTools.CheckEvidenceRegistry.clear()
        val checked = source("https://h.com/checked")
        val unchecked = source("https://h.com/unchecked")
        val now = System.currentTimeMillis()
        // 只给 checked 登记四段全通的证据
        McpSourceTools.CheckEvidenceRegistry.register(
            checked,
            mapOf("搜索" to okReport("搜索"), "详情" to okReport("详情"), "目录" to okReport("目录"), "正文" to okReport("正文")),
            refresh = true, now = now,
        )
        val checkMap = McpSourceTools.trustedCheckResults(listOf(checked, unchecked), now)
        assertEquals(setOf(0), checkMap.keys) // 只有 index 0 有可信证据
        val report = SourceFilterPolicy.filter(
            listOf(checked, unchecked), checkMap, setOf(SourceFilterPolicy.Exclude.UNVERIFIED))
        assertEquals(1, report.keptCount)
        assertEquals(0, report.kept[0].index)
    }

    @Test fun `expired evidence no longer counts`() {
        McpSourceTools.CheckEvidenceRegistry.clear()
        val src = source("https://h.com/x")
        val t0 = 1_000_000L
        McpSourceTools.CheckEvidenceRegistry.register(src, mapOf("搜索" to okReport("搜索")), refresh = true, now = t0)
        // 超过 TTL 后不再算已验
        assertFalse(McpSourceTools.CheckEvidenceRegistry.hasTrustedEvidence(src, t0 + McpSourceTools.CHECK_EVIDENCE_TTL_MS + 1))
    }

    // ---------- buildExport 原文保真 ----------

    @Test fun `export preserves raw source text verbatim including whitespace and key order`() {
        // 原文带特殊空白/键序/#标签——逐字节拼接后仍原样
        val raw = "{  \"bookSourceUrl\":\"https://h.com/a#tag?x=1&y=2\" ,\n \"z\":1, \"a\":2 }"
        val row = McpSourceTools.ExportRow("p1", "https://h.com/a#tag", raw, 0L)
        val (text, _, err) = McpSourceTools.buildExport(listOf(row), collapseIdentical = false, asArray = false)
        assertNull(err)
        assertEquals(raw, text) // 单对象导出 = 原文字符串，逐字节
    }

    @Test fun `export array joins raw source strings without reserialization`() {
        val rawA = "{\"bookSourceUrl\":\"https://h.com/a\",\"keep\":\"  spaced  \"}"
        val rawB = "{\"bookSourceUrl\":\"https://h.com/b\"}"
        val rows = listOf(McpSourceTools.ExportRow("p1", "u1", rawA, 0L), McpSourceTools.ExportRow("p2", "u2", rawB, 0L))
        val (text, _, err) = McpSourceTools.buildExport(rows, collapseIdentical = false, asArray = true)
        assertNull(err)
        assertEquals("[$rawA,$rawB]", text) // 逐字节拼接，空白不丢
    }

    // ---------- mergeFieldsInto：fields null 真正删除顶层键 ----------

    @Test fun `mergeFields null deletes top-level key not writes null`() {
        val obj = JsonParser.parseString(
            """{"bookSourceName":"n","bookSourceUrl":"https://h.com","loginUrl":"https://h.com/login"}""").asJsonObject
        val fields = JsonParser.parseString("""{"loginUrl":null,"bookSourceName":"改名"}""").asJsonObject
        McpSourceTools.mergeFieldsInto(obj, fields)
        assertFalse(obj.has("loginUrl")) // null → 键被删除，不是写入 null
        assertEquals("改名", obj.get("bookSourceName").asString) // 非 null → 覆盖
        assertEquals("https://h.com", obj.get("bookSourceUrl").asString) // 未点名保留
    }

    @Test fun `mergeFields does not deep merge nested objects`() {
        // 顶层键整体覆盖：ruleSearch 整个被 fields 值替换，不递归合并子键
        val obj = JsonParser.parseString(
            """{"bookSourceUrl":"https://h.com","ruleSearch":{"bookList":".old","name":"a@text"}}""").asJsonObject
        val fields = JsonParser.parseString("""{"ruleSearch":{"bookList":".new"}}""").asJsonObject
        McpSourceTools.mergeFieldsInto(obj, fields)
        assertEquals(".new", obj.getAsJsonObject("ruleSearch").get("bookList").asString)
        assertFalse(obj.getAsJsonObject("ruleSearch").has("name")) // 子键被整体替换掉
    }

    @Test fun `mergeFields normalizes json-string valued fields received as object`() {
        // header/loginUi 等字段合法值是「JSON 字符串」；传输层把 "{\"a\":1}" 再解析成 object 时归一化回字符串
        val obj = JsonParser.parseString("""{"bookSourceUrl":"https://h.com"}""").asJsonObject
        val fields = JsonParser.parseString(
            """{"header":{"User-Agent":"UA","Referer":"https://h.com"},"ruleSearch":{"bookList":".x"},"bookSourceName":"n"}""").asJsonObject
        McpSourceTools.mergeFieldsInto(obj, fields)
        // header 落库为 JSON 字符串而非对象
        assertTrue(obj.get("header").isJsonPrimitive)
        val headerObj = JsonParser.parseString(obj.get("header").asString).asJsonObject
        assertEquals("UA", headerObj.get("User-Agent").asString)
        // 非 JSON 字符串字段不受影响：ruleSearch 仍是对象
        assertTrue(obj.get("ruleSearch").isJsonObject)
        assertEquals("n", obj.get("bookSourceName").asString)
    }

    @Test fun `mergeFields json-string valued field passed as string stays string`() {
        val obj = JsonParser.parseString("""{"bookSourceUrl":"https://h.com"}""").asJsonObject
        val fields = JsonParser.parseString("""{"header":"{\"User-Agent\":\"UA\"}"}""").asJsonObject
        McpSourceTools.mergeFieldsInto(obj, fields)
        assertEquals("{\"User-Agent\":\"UA\"}", obj.get("header").asString)
    }

    // ---------- resolveGetSource：ID 精确优先 / URL 恰一条才读 / 多条报 ID ----------

    @Test fun `getSource by id wins over url`() {
        val byId = project("p1", "https://h.com/x", source("https://h.com/x"))
        val urlOther = project("p2", "https://h.com/x", source("https://h.com/x", extra = ",\"a\":1"))
        val r = McpSourceTools.resolveGetSource(byId, listOf(byId, urlOther))
        assertTrue(r is McpSourceTools.SourceLookup.Found)
        assertEquals("p1", (r as McpSourceTools.SourceLookup.Found).project.id)
    }

    @Test fun `getSource single url match returns it`() {
        val p = project("p1", "https://h.com/x", source("https://h.com/x"))
        val r = McpSourceTools.resolveGetSource(null, listOf(p))
        assertEquals("p1", (r as McpSourceTools.SourceLookup.Found).project.id)
    }

    @Test fun `getSource multiple url matches is ambiguous not silent pick`() {
        val a = project("p1", "https://h.com/x", source("https://h.com/x"))
        val b = project("p2", "https://h.com/x", source("https://h.com/x", extra = ",\"a\":1"))
        val r = McpSourceTools.resolveGetSource(null, listOf(a, b))
        assertTrue(r is McpSourceTools.SourceLookup.Ambiguous)
        assertEquals(listOf("p1", "p2"), (r as McpSourceTools.SourceLookup.Ambiguous).projectIds)
    }

    @Test fun `getSource no match is notFound`() {
        assertTrue(McpSourceTools.resolveGetSource(null, emptyList()) is McpSourceTools.SourceLookup.NotFound)
    }

    // ---------- decideSave：身份不合并 ----------

    @Test fun `trailing slash is a different identity`() {
        // 旧逻辑 trimEnd('/') 会把 https://h.com/x/ 与 https://h.com/x 合并；现在必须是两个身份
        val existing = project("p1", "https://h.com/x", source("https://h.com/x"))
        val d = McpSourceTools.decideSave(saveObj("https://h.com/x/"), false, listOf(existing), now = 100)
        assertTrue(d is McpSourceTools.SaveDecision.NewVersion)
        assertEquals("https://h.com/x/", d.project.siteUrl) // 落库保留原 URL 尾部 /
    }

    @Test fun `hash tag is a different identity`() {
        val existing = project("p1", "https://h.com/x", source("https://h.com/x"))
        val d = McpSourceTools.decideSave(saveObj("https://h.com/x#tagA"), false, listOf(existing), now = 100)
        assertTrue(d is McpSourceTools.SaveDecision.NewVersion)
        assertEquals("https://h.com/x#tagA", d.project.siteUrl)
    }

    @Test fun `same url same json overwrites latest idempotently`() {
        val a = project("a", "https://h.com/x", source("https://h.com/x"), updatedAt = 10)
        val b = project("b", "https://h.com/x", source("https://h.com/x"), updatedAt = 20)
        val d = McpSourceTools.decideSave(saveObj("https://h.com/x"), false, listOf(a, b), now = 100)
        assertTrue(d is McpSourceTools.SaveDecision.Overwrite)
        assertEquals("b", d.project.id) // 覆写最新一条
        assertEquals(0, (d as McpSourceTools.SaveDecision.Overwrite).conflicts.size)
    }

    @Test fun `same url different json becomes new version not overwrite`() {
        val existing = project("p1", "https://h.com/x", source("https://h.com/x", extra = ",\"ruleContent\":{\"content\":\"#a\"}"))
        val d = McpSourceTools.decideSave(
            saveObj("https://h.com/x", extra = ",\"ruleContent\":{\"content\":\"#b\"}"),
            newVersion = false, all = listOf(existing), now = 100,
        )
        assertTrue(d is McpSourceTools.SaveDecision.NewVersion)
        assertTrue(d.project.id != "p1")
        assertEquals(listOf("p1"), d.conflicts) // 冲突点名，不静默
        assertTrue(d.note.contains("冲突"))
    }

    @Test fun `newVersion true but identical content does not create empty copy`() {
        val existing = project("p1", "https://h.com/x", source("https://h.com/x"), updatedAt = 10)
        val d = McpSourceTools.decideSave(saveObj("https://h.com/x"), true, listOf(existing), now = 100)
        assertTrue(d is McpSourceTools.SaveDecision.Overwrite)
        assertEquals("p1", d.project.id)
    }

    @Test fun `new identity creates new project`() {
        val d = McpSourceTools.decideSave(saveObj("https://new.com/s"), false, emptyList(), now = 100)
        assertTrue(d is McpSourceTools.SaveDecision.NewVersion)
        assertTrue(d.note.contains("新身份"))
    }

    // ---------- buildExport ----------

    private fun row(id: String, url: String, extra: String = "", updatedAt: Long = 0L) =
        McpSourceTools.ExportRow(id, url, source(url, extra = extra), updatedAt)

    @Test fun `export preserves order and bytes including hash tags`() {
        val rows = listOf(
            row("p2", "https://h.com/b#tag"),
            row("p1", "https://h.com/a/"),
            row("p3", "https://h.com/a"),
        )
        val (text, summary, err) = McpSourceTools.buildExport(rows, collapseIdentical = false, asArray = true)
        assertNull(err)
        val arr = JsonParser.parseString(text!!).asJsonArray
        assertEquals(3, arr.size())
        assertEquals("https://h.com/b#tag", arr[0].asJsonObject.get("bookSourceUrl").asString)
        assertEquals("https://h.com/a/", arr[1].asJsonObject.get("bookSourceUrl").asString) // 尾部 / 保留
        assertEquals(3, summary["exported"])
    }

    @Test fun `export single object without asArray returns object`() {
        val (text, _, err) = McpSourceTools.buildExport(listOf(row("p1", "https://h.com/a")), false, asArray = false)
        assertNull(err)
        assertTrue(JsonParser.parseString(text!!).isJsonObject)
    }

    @Test fun `export with asArray always array`() {
        val (text, summary, _) = McpSourceTools.buildExport(listOf(row("p1", "https://h.com/a")), false, asArray = true)
        assertTrue(JsonParser.parseString(text!!).isJsonArray)
        assertEquals(true, summary["asArray"])
    }

    @Test fun `malformed member aborts export instead of emitting empty object`() {
        val bad = McpSourceTools.ExportRow("bad", "https://h.com/x", "{broken json", 0L)
        val (text, _, err) = McpSourceTools.buildExport(listOf(row("ok", "https://h.com/a"), bad), false, true)
        assertNull(text)
        assertTrue(err!!.contains("bad"))
    }

    @Test fun `collapseIdentical only folds fully identical zero-conflict clusters`() {
        val same1 = row("p1", "https://h.com/x", updatedAt = 1)
        val same2 = row("p2", "https://h.com/x", updatedAt = 2) // 结构相同（键序可能不同），updatedAt 最新
        val diff = row("p3", "https://h.com/y", extra = ",\"ruleContent\":{\"content\":\"#c\"}")
        val (text, summary, err) = McpSourceTools.buildExport(
            listOf(same1, same2, diff), collapseIdentical = true, asArray = true)
        assertNull(err)
        val arr = JsonParser.parseString(text!!).asJsonArray
        assertEquals(2, arr.size()) // p1 被折叠进 keep=p2
        @Suppress("UNCHECKED_CAST")
        val collapsed = summary["collapsed"] as List<Map<String, Any?>>
        assertEquals(1, collapsed.size)
        assertEquals("p2", collapsed[0]["kept"])
    }

    @Test fun `collapseIdentical never folds conflicting cluster`() {
        // 同 URL 不同 JSON：dedupe 里 action=none → collapse 后两者都保留
        val a = row("p1", "https://h.com/x")
        val b = row("p2", "https://h.com/x", extra = ",\"ruleContent\":{\"content\":\"#c\"}")
        val (text, _, err) = McpSourceTools.buildExport(listOf(a, b), collapseIdentical = true, asArray = true)
        assertNull(err)
        assertEquals(2, JsonParser.parseString(text!!).asJsonArray.size())
    }

    @Test fun `empty kept is explicit error not empty export`() {
        val (text, _, err) = McpSourceTools.buildExport(emptyList(), false, true)
        assertNull(text)
        assertTrue(err != null)
    }

    // ---------- SourceFilterPolicy 集成（无 checkResults 时 excludeUnverified 排除，不伪装已验） ----------

    @Test fun `excludeUnverified without checkResults excludes all`() {
        val report = SourceFilterPolicy.filter(listOf(source("https://h.com/x")), excludes = setOf(SourceFilterPolicy.Exclude.UNVERIFIED))
        assertEquals(0, report.keptCount)
        assertTrue(report.excluded[0].reasons.any { it.contains("未") })
    }

    // ---------- latestOnly / decideArchive（list_sources latestOnly 与 archive_source_versions 的纯函数） ----------

    @Test fun `latestOnly keeps newest per siteUrl and sorts desc`() {
        val projects = listOf(
            project("a1", "https://h.com/x", source("https://h.com/x"), updatedAt = 10),
            project("a2", "https://h.com/x", source("https://h.com/x", extra = ",\"ruleContent\":{\"content\":\"#c\"}"), updatedAt = 30),
            project("b1", "https://h.com/y", source("https://h.com/y"), updatedAt = 20),
        )
        val latest = McpSourceTools.latestOnly(projects)
        assertEquals(listOf("a2", "b1"), latest.map { it.id }) // a2(updatedAt=30) 在 b1(20) 前
        // latestOnly 不看内容异同：a1/a2 JSON 不同仍同组折叠
        val counts = McpSourceTools.versionCounts(projects)
        assertEquals(2, counts["https://h.com/x"])
        assertEquals(1, counts["https://h.com/y"])
    }

    @Test fun `latestOnly treats trailing-slash and tag variants as different groups`() {
        val projects = listOf(
            project("p1", "https://h.com/x", source("https://h.com/x"), updatedAt = 1),
            project("p2", "https://h.com/x/", source("https://h.com/x"), updatedAt = 2),
            project("p3", "https://h.com/x#tag", source("https://h.com/x"), updatedAt = 3),
        )
        // siteUrl 逐字符精确分组：尾部 / 与 #标签不归并（与 get_source 口径一致）
        assertEquals(3, McpSourceTools.latestOnly(projects).size)
    }

    @Test fun `decideArchive keeps newest N and deletes the rest`() {
        val projects = listOf(
            project("v1", "https://h.com/x", source("https://h.com/x"), updatedAt = 10),
            project("v2", "https://h.com/x", source("https://h.com/x"), updatedAt = 30),
            project("v3", "https://h.com/x", source("https://h.com/x"), updatedAt = 20),
            project("other", "https://h.com/y", source("https://h.com/y"), updatedAt = 99),
        )
        val (group, keep, toDelete) = McpSourceTools.decideArchive(projects, "https://h.com/x", keepLatest = 1)
        assertEquals(3, group.size)
        assertEquals(listOf("v2"), keep.map { it.id })       // updatedAt=30 最新
        assertEquals(listOf("v3", "v1"), toDelete.map { it.id }) // 倒序砍尾
        // keepLatest=2 留两条
        val (_, keep2, del2) = McpSourceTools.decideArchive(projects, "https://h.com/x", keepLatest = 2)
        assertEquals(listOf("v2", "v3"), keep2.map { it.id })
        assertEquals(listOf("v1"), del2.map { it.id })
    }

    @Test fun `decideArchive exact-match and empty cases`() {
        val projects = listOf(project("p1", "https://h.com/x", source("https://h.com/x"), updatedAt = 1))
        // 逐字符精确：不同 URL 不命中
        assertTrue(McpSourceTools.decideArchive(projects, "https://h.com/x/", 1).first.isEmpty())
        assertTrue(McpSourceTools.decideArchive(projects, "https://h.com/z", 1).first.isEmpty())
        // 版本数 ≤ keepLatest：命中但不删
        val (group, keep, toDelete) = McpSourceTools.decideArchive(projects, "https://h.com/x", 1)
        assertEquals(1, group.size); assertEquals(1, keep.size); assertTrue(toDelete.isEmpty())
        // keepLatest=0 被调用方兜底成 1
        assertEquals(1, McpSourceTools.decideArchive(projects, "https://h.com/x", 0).second.size)
    }
}
