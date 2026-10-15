package com.mina.legadostudio.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * MCP 响应成形纯函数：fetchPageOk / boundedResult 中原地内联的「预览选择 + JSON Map 组装 +
 * 大结果引用判定」抽到本 object，两个调用点只负责传参转调。
 *
 * 本文件不持有任何状态、不做上下文存取/网络/参数校验——contextId 解析、缓存命中、
 * TaskContextStore 存取、异常与 fallback 触发全部留在 StudioMcpServer 原处。阈值：
 * fetch_page 首次预览缺省按 [pagePreviewLimit] 选择（标准路径与原 6000 逐字符保持；
 * opt-in 的 compact 连接仅 auto 缺省降为 2000，显式 maxChars 与各非 auto 模式不变），
 * 12000 大结果界限与 3000 引用预览不变，Map 键序/null 值/中文提示逐字符保持。
 */
internal object McpResponsePayloads {
    /** 单次响应正文中段上限：maxChars 校验、内联截断与引用判定共用同一数字（不改值）。 */
    const val MAX_RESPONSE_CHARS = 12000

    /** 大结果转 resultId 后内联附带的正文预览长度。 */
    const val RESULT_PREVIEW_CHARS = 3000

    /** fetch_page 的默认预览长度（maxChars 缺省值）。 */
    const val DEFAULT_PREVIEW_CHARS = 6000

    /** compact 连接 auto 模式下的 fetch_page 默认预览长度（仅缺省路径；显式 maxChars 不受影响）。 */
    const val COMPACT_AUTO_PREVIEW_CHARS = 2000

    /**
     * fetch_page 的 maxChars 缺省选择（原 fetchPageOk 内联 `args.int("maxChars") ?: 6000` 的规则化）：
     * 显式 maxChars 永远优先；只有 compact 连接 + responseMode=auto 且缺省时取 2000，
     * 其余模式（preview/reference）与普通连接缺省一律 6000。不在本函数做 0..12000 校验——
     * 范围校验仍由调用处原有 require 负责，显式越界值原样返回照旧报错。
     */
    fun pagePreviewLimit(compact: Boolean, mode: String, explicitMaxChars: Int?): Int =
        explicitMaxChars ?: if (compact && mode == "auto") COMPACT_AUTO_PREVIEW_CHARS else DEFAULT_PREVIEW_CHARS

    /** responseMode=auto/preview/reference 下的正文预览选择（原 fetchPageOk 内联一行）。 */
    fun pagePreview(text: String, mode: String, cacheHit: Boolean, maxChars: Int): String =
        if (mode == "reference" || (mode == "auto" && cacheHit)) "" else text.take(maxChars)

    /**
     * fetchPageOk 的 JSON payload 组装：metadata 已由 store 算好，extra 为 blockHintExtra 附加项。
     * 参数顺序即原 mapOf 键序；null 值原样放入（Gson 未 serializeNulls 时序列化消失，行为不变）。
     */
    fun fetchPagePayload(
        metadata: Map<String, Any>,
        contextId: String,
        entryId: String,
        reused: Boolean,
        code: Int,
        finalUrl: String,
        elapsedMs: Long,
        bodyNote: String,
        binaryBytes: Long,
        preview: String,
        totalChars: Int,
        autoRetried: Boolean,
        switchedDomain: String?,
        repeatPageId: String?,
        extra: Map<String, String>,
    ): Map<String, Any?> = metadata + mapOf(
        "contextId" to contextId, "pageId" to entryId, "cacheHit" to reused, "networkRequest" to !reused,
        "code" to code, "finalUrl" to finalUrl, "elapsedMs" to elapsedMs,
        "body" to preview, "bodyNote" to bodyNote, "binaryBytes" to binaryBytes,
        "truncated" to (preview.isNotEmpty() && preview.length < totalChars),
        "bodyOmitted" to (preview.isEmpty() && totalChars > 0), "nextOffset" to preview.length,
        "autoRetried" to autoRetried,
        "autoSwitchedToWebView" to (switchedDomain != null),
        "autoSwitchHint" to (switchedDomain?.let { "域 $it 从 auto 自动切换到 webview；恢复自动模式可调用 set_domain_mode(domain=\"$it\", mode=\"auto\")" } ?: ""),
        "repeatFetch" to (repeatPageId?.let { "该 URL 本任务已抓过旧快照，可用 read_page(pageId=$it) 复用，无需重抓" } ?: ""),
        "hint" to "正文完整保存在pageId；用read_page或直接inspect_rule/analyze_html/eval_js引用，不要重复抓取。快照不是实时验证。",
    ) + extra

    /** 大结果转引用的判定：错误结果、非受限工具、非单条 TextContent、≤12000 字符一律原样返回。 */
    fun needsResultReference(name: String, result: CallToolResult, boundedTools: Set<String>): Boolean {
        if (result.isError == true || name !in boundedTools) return false
        val text = (result.content.singleOrNull() as? TextContent)?.text ?: return false
        return text.length > MAX_RESPONSE_CHARS
    }

    /** 超限时内联降级的完整返回文本（含原 note 尾缀）。 */
    fun inlineFallbackText(text: String): String {
        val note = "结果超过12000字符且无法保存引用；已内联返回前12000字符。请缩小范围，或先用 list_contexts 查看并清理闲置上下文后重试。"
        return text.take(MAX_RESPONSE_CHARS) + "\n…[" + note + "]"
    }

    /** 大结果保存成功后的引用 payload：metadata + contextId/resultId + 3000 预览 + 原中文 hint。 */
    fun resultReferencePayload(
        metadata: Map<String, Any>,
        contextId: String,
        resultId: String,
        text: String,
    ): Map<String, Any> = metadata + mapOf(
        "contextId" to contextId, "resultId" to resultId,
        "preview" to text.take(RESULT_PREVIEW_CHARS), "previewIsRawString" to true, "resultChars" to text.length,
        "nextOffset" to RESULT_PREVIEW_CHARS, "hasMore" to true,
        "hint" to "结果已完整保存，调用 read_result，不要重复执行原工具。preview 是原始文本的硬截断片段，不是有效JSON，不要对它做 JSON 解析。",
    )
}
