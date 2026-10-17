package com.mina.legadostudio.mcp

import com.google.gson.GsonBuilder
import com.mina.legadostudio.network.HttpFetcher
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpResponsePayloads 的逐键/逐值契约测试：helper 的每一项输出都与抽取前 StudioMcpServer 内联
 * 表达式的 golden 复算逐项对照（键集、值、值类型、插入顺序、Gson 序列化全文）。只测纯成形，
 * 不移动 contextId 解析、存取或异常路径的业务校验。
 */
class McpResponsePayloadsContractTest {

    /** 与 StudioApplication.kt:50 同配置：prettyPrinting + disableHtmlEscaping，未 serializeNulls。 */
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private fun entry(text: String, code: Int = 200, finalUrl: String = "https://example.test/p",
        bodyNote: String = "", binaryBytes: Long = 0, elapsedMs: Long = 12L,
        headers: Map<String, String> = mapOf("content-type" to "text/html"),
    ) = TaskContextStore.Entry(
        id = "page-fixed-id", kind = "page", text = text, created = 1_000L,
        fingerprint = "fp", key = "k",
        page = HttpFetcher.FetchResult(code, finalUrl, headers, text, elapsedMs, bodyNote = bodyNote, binaryBytes = binaryBytes),
    )

    /** 抽取前 fetchPageOk 的原内联表达式，逐字符保留作 golden。 */
    private fun goldenFetchPayload(
        metadata: Map<String, Any>, id: String, e: TaskContextStore.Entry, reused: Boolean,
        mode: String, limit: Int, autoRetried: Boolean, switchedDomain: String?, repeatRef: String?,
        extra: Map<String, String>,
    ): Map<String, Any?> {
        val result = e.page!!
        val preview = if (mode == "reference" || (mode == "auto" && reused)) "" else e.text.take(limit)
        return metadata + mapOf(
            "contextId" to id, "pageId" to e.id, "cacheHit" to reused, "networkRequest" to !reused,
            "code" to result.code, "finalUrl" to result.finalUrl, "elapsedMs" to result.elapsedMs,
            "body" to preview, "bodyNote" to result.bodyNote, "binaryBytes" to result.binaryBytes,
            "truncated" to (preview.isNotEmpty() && preview.length < e.text.length),
            "bodyOmitted" to (preview.isEmpty() && e.text.isNotEmpty()), "nextOffset" to preview.length,
            "autoRetried" to autoRetried,
            "autoSwitchedToWebView" to (switchedDomain != null),
            "autoSwitchHint" to (switchedDomain?.let { "域 $it 从 auto 自动切换到 webview；恢复自动模式可调用 set_domain_mode(domain=\"$it\", mode=\"auto\")" } ?: ""),
            "repeatFetch" to (repeatRef?.let { "该 URL 本任务已抓过旧快照，可用 read_page(pageId=$it) 复用，无需重抓" } ?: ""),
            "hint" to "正文完整保存在pageId；用read_page或直接inspect_rule/analyze_html/eval_js引用，不要重复抓取。快照不是实时验证。",
        ) + extra
    }

    /** 逐键对照：键集、键序、每键的值与运行时类型全部一致；最后比对 Gson 序列化全文。 */
    private fun assertSamePayload(expected: Map<*, *>, actual: Map<*, *>, label: String) {
        assertEquals("$label keys", expected.keys.toList(), actual.keys.toList())
        for (key in expected.keys) {
            val ev = expected[key]; val av = actual[key]
            assertEquals("$label key=$key value", ev, av)
            if (ev != null && av != null) assertEquals("$label key=$key type", ev::class, av::class)
            if (ev == null || av == null) assertNull("$label key=$key null", ev)
        }
        assertEquals("$label gson", gson.toJson(expected), gson.toJson(actual))
    }

    private fun fetchCase(
        mode: String, limit: Int, text: String, reused: Boolean,
        autoRetried: Boolean = false, switchedDomain: String? = null,
        repeatPageId: String? = null, extra: Map<String, String> = emptyMap(),
    ) {
        val e = entry(text)
        val metadata = mapOf<String, Any>("id" to e.id, "kind" to "page", "totalChars" to e.text.length,
            "sha256" to "s".repeat(64), "ageMs" to 0, "stale" to false, "url" to "https://example.test/p")
        val expected = goldenFetchPayload(metadata, "ctx-1", e, reused, mode, limit,
            autoRetried, switchedDomain, repeatPageId, extra)
        val result = e.page!!
        val actual = McpResponsePayloads.fetchPagePayload(
            metadata, "ctx-1", e.id, reused, result.code, result.finalUrl, result.elapsedMs,
            result.bodyNote, result.binaryBytes,
            McpResponsePayloads.pagePreview(e.text, mode, reused, limit), e.text.length,
            autoRetried, switchedDomain, repeatPageId, extra,
        )
        assertSamePayload(expected, actual, "mode=$mode limit=$limit reused=$reused")
    }

    /** 走 pagePreviewLimit 缺省选择的 golden 对照（模拟 fetchPageOk 新调用序）。 */
    private fun fetchCaseByMode(
        compact: Boolean, mode: String, explicit: Int?, text: String, reused: Boolean,
        extra: Map<String, String> = emptyMap(),
    ) {
        val e = entry(text)
        val metadata = mapOf<String, Any>("id" to e.id, "kind" to "page", "totalChars" to e.text.length,
            "sha256" to "s".repeat(64), "ageMs" to 0, "stale" to false, "url" to "https://example.test/p")
        val limit = McpResponsePayloads.pagePreviewLimit(compact, mode, explicit)
        val expected = goldenFetchPayload(metadata, "ctx-1", e, reused, mode, limit,
            autoRetried = false, switchedDomain = null, repeatRef = null, extra = extra)
        val result = e.page!!
        val actual = McpResponsePayloads.fetchPagePayload(
            metadata, "ctx-1", e.id, reused, result.code, result.finalUrl, result.elapsedMs,
            result.bodyNote, result.binaryBytes,
            McpResponsePayloads.pagePreview(e.text, mode, reused, limit), e.text.length,
            false, null, null, extra,
        )
        assertSamePayload(expected, actual, "compact=$compact mode=$mode explicit=$explicit reused=$reused")
    }

    @Test fun fetchPayloadMatchesGoldenForAllModesAndCacheStates() {
        val text = "x".repeat(9000)
        for (mode in listOf("auto", "preview", "reference")) {
            for (limit in listOf(0, 6000, 12000)) {
                for (reused in listOf(false, true)) {
                    fetchCase(mode, limit, text, reused)
                }
            }
        }
    }

    @Test fun pagePreviewLimitDefaultMatrix() {
        val p = McpResponsePayloads
        // 普通连接：三种模式缺省一律 6000
        for (mode in listOf("auto", "preview", "reference")) {
            assertEquals("normal mode=$mode", 6000, p.pagePreviewLimit(false, mode, null))
        }
        // compact：仅 auto 缺省 2000；preview/reference 缺省仍 6000
        assertEquals(2000, p.pagePreviewLimit(true, "auto", null))
        assertEquals(6000, p.pagePreviewLimit(true, "preview", null))
        assertEquals(6000, p.pagePreviewLimit(true, "reference", null))
        // 显式 maxChars 跨 mode 与 compact 一律优先（0/边界/超默认原样返回，范围校验留在 fetchPageOk 的 require）
        for (explicit in listOf(0, 2000, 4000, 6000, 12000)) {
            for (mode in listOf("auto", "preview", "reference")) {
                for (compact in listOf(false, true)) {
                    assertEquals("explicit=$explicit mode=$mode compact=$compact",
                        explicit, p.pagePreviewLimit(compact, mode, explicit))
                }
            }
        }
    }

    @Test fun compactAutoDefaultPayloadMatchesGolden() {
        val text = "x".repeat(9000)
        // compact auto 首次：缺省 2000 预览、truncated=true；全文仍按 pageId 保存
        fetchCaseByMode(compact = true, mode = "auto", explicit = null, text = text, reused = false)
        // compact auto 缓存命中：limit 取值仍为 2000，但 body 输出为空（pagePreview 原有规则不变）
        fetchCaseByMode(compact = true, mode = "auto", explicit = null, text = text, reused = true)
        // compact preview/reference 首次：缺省 6000 与普通连接一致
        fetchCaseByMode(compact = true, mode = "preview", explicit = null, text = text, reused = false)
        fetchCaseByMode(compact = true, mode = "reference", explicit = null, text = text, reused = false)
        // 普通连接 auto：缺省仍是 6000（防漂移）
        fetchCaseByMode(compact = false, mode = "auto", explicit = null, text = text, reused = false)
        // 显式 maxChars 在 compact auto 下覆盖 2000 缺省
        fetchCaseByMode(compact = true, mode = "auto", explicit = 6000, text = text, reused = false)
        fetchCaseByMode(compact = true, mode = "auto", explicit = 0, text = text, reused = false)
    }

    @Test fun previewSelectionBoundary() {
        val p = McpResponsePayloads
        assertEquals("", p.pagePreview("abc", "reference", false, 6000))
        assertEquals("", p.pagePreview("abc", "auto", true, 6000))
        assertEquals("abc", p.pagePreview("abc", "auto", false, 6000))
        assertEquals("abc", p.pagePreview("abc", "preview", true, 6000)) // preview 模式命中也给正文
        assertEquals("", p.pagePreview("abc", "preview", false, 0))    // maxChars=0 → 空
        assertEquals("ab", p.pagePreview("abc😀def", "preview", false, 2))
    }

    @Test fun flagsTruncatedBodyOmittedNextOffset() {
        // 截断：preview 非空且短于全文
        fetchCase("auto", 6000, "x".repeat(9000), reused = false)
        val e = entry("x".repeat(9000))
        val metadata = mapOf<String, Any>()
        val r = e.page!!
        // bodyOmitted：preview 空但全文非空
        val omitted = McpResponsePayloads.fetchPagePayload(metadata, "c", e.id, true,
            r.code, r.finalUrl, r.elapsedMs, r.bodyNote, r.binaryBytes,
            McpResponsePayloads.pagePreview(e.text, "auto", true, 6000), e.text.length,
            false, null, null, emptyMap())
        assertEquals("", omitted["body"])
        assertEquals(true, omitted["bodyOmitted"])
        assertEquals(false, omitted["truncated"])
        assertEquals(0, omitted["nextOffset"])
        assertEquals(true, omitted["cacheHit"])
        assertEquals(false, omitted["networkRequest"])
        // 全文 ≤ limit：既不 truncated 也不 omitted
        val full = McpResponsePayloads.fetchPagePayload(metadata, "c", e.id, false,
            r.code, r.finalUrl, r.elapsedMs, r.bodyNote, r.binaryBytes,
            McpResponsePayloads.pagePreview(e.text, "preview", false, 12000), e.text.length,
            false, null, null, emptyMap())
        assertEquals(false, full["truncated"])
        assertEquals(false, full["bodyOmitted"])
        assertEquals(9000, full["nextOffset"])
    }

    @Test fun utf16BoundaryPreview() {
        // surrogate pair：take 按 UTF-16 code unit 切，可能切断 emoji
        val text = "a".repeat(5999) + "😀" + "b".repeat(10)
        val preview = McpResponsePayloads.pagePreview(text, "preview", false, 6000)
        assertEquals(6000, preview.length)
        assertEquals('a', preview[5998])
        assertEquals(text[5999], preview[5999]) // 高位代理被完整取到边界
        fetchCase("preview", 6000, text, reused = false)
    }

    @Test fun extrasOverrideAndNullPreserved() {
        // blockHintExtra 为空时原样收尾；非空 blockHint 附加在最后
        fetchCase("auto", 6000, "page-body", reused = false, extra = mapOf("blockHint" to "h"))
        // extra 覆盖同名键（Map.plus 语义）：golden 与 helper 同序对照已覆盖
        fetchCase("auto", 6000, "page-body", reused = false, extra = mapOf("hint" to "overridden"))
        // switchedDomain=null / repeatRef=null 产生空字符串值而非 null
        val e = entry("body")
        val payload = McpResponsePayloads.fetchPagePayload(mapOf(), "c", e.id, false,
            200, "u", 1L, "", 0L, "body", e.text.length, false, null, null, emptyMap())
        assertEquals("", payload["autoSwitchHint"])
        assertEquals("", payload["repeatFetch"])
        assertEquals(false, payload["autoSwitchedToWebView"])
    }

    @Test fun needsResultReferenceBoundary() {
        val bounded = setOf("inspect_rule", "get_logs")
        fun ok(t: String) = CallToolResult(listOf(TextContent(t)))
        // error 结果一律不引用
        assertFalse(McpResponsePayloads.needsResultReference(
            "inspect_rule", CallToolResult(listOf(TextContent("x".repeat(20000))), isError = true), bounded))
        // 非受限工具不引用
        assertFalse(McpResponsePayloads.needsResultReference("app_status", ok("x".repeat(20000)), bounded))
        // 非 TextContent / 多 content 不引用
        assertFalse(McpResponsePayloads.needsResultReference("inspect_rule",
            CallToolResult(listOf(ImageContent(data = "aGk=", mimeType = "image/png"))), bounded))
        assertFalse(McpResponsePayloads.needsResultReference("inspect_rule",
            ok("x".repeat(20000)).let { CallToolResult(it.content + TextContent("second")) }, bounded))
        // 空 content 不引用
        assertFalse(McpResponsePayloads.needsResultReference("inspect_rule", CallToolResult(emptyList()), bounded))
        // 12000 / 12001 边界
        assertFalse(McpResponsePayloads.needsResultReference("inspect_rule", ok("x".repeat(12000)), bounded))
        assertTrue(McpResponsePayloads.needsResultReference("inspect_rule", ok("x".repeat(12001)), bounded))
    }

    @Test fun resultReferencePayloadMatchesGolden() {
        val text = "x".repeat(15000)
        val metadata = mapOf<String, Any>("id" to "r1", "kind" to "result", "totalChars" to 15000)
        val expected = metadata + mapOf("contextId" to "ctx", "resultId" to "r1",
            "preview" to text.take(3000), "previewIsRawString" to true, "resultChars" to text.length,
            "nextOffset" to 3000, "hasMore" to true,
            "hint" to "结果已完整保存，调用 read_result，不要重复执行原工具。preview 是原始文本的硬截断片段，不是有效JSON，不要对它做 JSON 解析。")
        val actual = McpResponsePayloads.resultReferencePayload(metadata, "ctx", "r1", text)
        assertSamePayload(expected, actual, "resultReference")
    }

    @Test fun inlineFallbackTextMatchesGolden() {
        val text = "x".repeat(15000)
        val note = "结果超过12000字符且无法保存引用；已内联返回前12000字符。请缩小范围，或先用 list_contexts 查看并清理闲置上下文后重试。"
        val expected = text.take(12000) + "\n…[" + note + "]"
        assertEquals(expected, McpResponsePayloads.inlineFallbackText(text))
        // UTF-16 边界：前 12000 code unit + 固定尾缀 "\n…[" + note + "]"
        assertEquals(12000 + 3 + note.length + 1, McpResponsePayloads.inlineFallbackText(text).length)
    }
}
