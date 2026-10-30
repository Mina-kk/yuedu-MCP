package com.mina.legadostudio.domain

import androidx.annotation.Keep
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.mina.legadostudio.runtime.LegadoRuntime

/**
 * 把 LegadoRuntime.DebugReport 的 data/lines（或其 JSON 序列化形态）变成结构化样本检查证据。
 * HTTP 成功、规则有输出与样本通过分开判断；不证明全文完整、解密语义或目标阅读版本兼容。
 *
 * 判定口径要点：
 * - 搜索看「首个可用项」（name+bookUrl 均非空），不是列表首项——首项为空、后项有效不算失败。
 *   列表 0 条时：lines 含站点限流或 HTTP 429 为 rate_limited；
 *   Cloudflare 挑战（cf-mitigated / Just a moment / cf_chl / challenge-platform）为 verification，
 *   高于 HTTP 403 的 network；HTTP 520（或 error code: 520）为 network。
 *   HTTP 200 真空列表仍 empty；裸 403/404 等非 2xx 不改判站点问题。
 *   facts 可记 statusCode，不记正文或凭证。沙箱观测不能替代真机阅读结果。
 * - 详情对文本源要求 name 与 tocUrl 同时非空（缺一不足以推进目录）；非文本源任一非空即可。
 *   运行时：源未配置 tocUrl 规则且存在 ruleToc.chapterList、详情 HTTP 2xx 时，会把详情页 URL 写入 tocUrl
 *   （对齐阅读 BookInfo 默认）；显式 tocUrl 规则取空不兜底，本层仍判失败。
 * - 目录看「第一个可读章节」（非 isVolume 且 url 非空），不是列表首项——卷名空 URL、后面有章链不算失败。
 *   非卷名空 URL 出现在任何可读章之前仍失败（不能把任意第一章取空静默吞掉）；仅卷名、无可读章节仍失败。
 *   达到沙箱 100 页上限时仍 ok（不算规则失败）但 category=sandbox、tocTruncated=true，
 *   CheckSourceRunner verdict 不得为 full。
 * - 正文拦截已知非成功状态、不完整分页、明确提示和整页 HTML；缺失观测不补成成功。
 *   JS 解析失败退回原始响应时：裸 error code: 520、完整 JSON 包、CF 挑战页不得标为有效正文或 full，
 *   facts.contentSuspect 记具体种类；含数字 520 或花括号的正常小说正文不误伤。
 *   lines/jsLogs 出现 js执行超时不得视作阶段成功（sandbox/webjs_timeout，不是规则 empty）。
 * - 非文本源不拿文字字数硬判，只接受二进制资源记录或纯资源 URL；否则标 media_unverified。
 * - 序列化路径：check_source 结果经 Gson roundtrip 后阶段值是 {type,entry,lines,data} 形 Map，
 *   这里会识别并重建为 DebugReport 再判定（不是只找 error 字段——否则真实通过的报告会被误判失败）。
 *   无法识别的任意 Map（无 error/lines/data/evidence 结构）不算「已跑」的证据：ran=false，
 *   不作成功也不作失败，由上层按「未验证/无法证实」处理。
 *
 * 判定结果是纯数据（StageEvidence），CheckSourceRunner / SourceFilterPolicy / 测试复用同一口径。
 */
object SourceCheckEvidence {

    /**
     * 一个阶段的结构化证据。
     * check_source 回包的 evidence 值经 Gson 序列化出包：字段名即对外 JSON 键名，
     * release 混淆下必须用 SerializedName 钉死（曾出现过 category→e、facts→f 的事故）。
     * @Keep 同时保证类/构造器不被 R8 移除或改名。
     */
    @Keep
    data class StageEvidence(
        @SerializedName("stage") val stage: String,
        /** ran=false = 该阶段没跑（无入口/未传参数）或结果无法识别为已跑证据；ran=true 才有 ok/failedReason。 */
        @SerializedName("ran") val ran: Boolean,
        @SerializedName("ok") val ok: Boolean,
        /** 失败/未跑原因（面向用户的可读说明）；ok 时为 null。 */
        @SerializedName("reason") val reason: String? = null,
        /** 结构化分类：network / verification / captcha / sandbox / rate_limited / config / empty / media_unverified / content_unverified / content_unavailable / not_run / unrecognized / unknown。 */
        @SerializedName("category") val category: String? = null,
        /** 关键观测值（搜索条数、章节数、正文字数…），供 filter / 调用方写理由。 */
        @SerializedName("facts") val facts: Map<String, Any?> = emptyMap(),
        @SerializedName("sandboxReason") val sandboxReason: String? = null,
    )

    /**
     * 从任意 debug 结果构造阶段证据。支持：
     * - DebugReport（进程内 debug_source/check_source 直接产物）；
     * - DebugReport 的 JSON 序列化 Map（{type,entry,lines,data}，Gson roundtrip 形态）；
     * - error Map（CheckSourceRunner 失败兜底 {error,category}）；
     * - 任意 Throwable（mock runtime 测试直接塞 Exception 时走这里）。
     * 识别不了的 Map（如手造 {"搜索":{}}）返回 ran=false 的 unrecognized——
     * 无证据的任意结构既不能算成功也不算「跑过但失败」。
     *
     * @param sourceType 书源 bookSourceType（-1 自动 / 0 文本 / 1 音频 / 2 图片 / 3 文件 / 4 视频），
     *                   默认 0=文本。非文本源的正文判定不依赖文字字数。
     */
    fun of(stage: String, result: Any?, sourceType: Int = 0): StageEvidence {
        return when (result) {
            null -> StageEvidence(stage, ran = false, ok = false, reason = "该阶段未执行", category = "not_run")
            is StageEvidence -> result
            is LegadoRuntime.DebugReport -> {
                val evidence = report(stage, result, sourceType)
                val lineText = result.lines.joinToString("\\n")
                val reason = sandboxReason(lineText)
                val externalJsLib = result.lines.any { it.contains("jsLib 为外部 URL") }
                val undefinedError = result.lines.any { UNDEFINED_ERROR_LINE.containsMatchIn(it) }
                val errorReason = result.lines.asSequence()
                    .filterNot { it.contains("jsLib 为外部 URL") }
                    .mapNotNull { sandboxReason(it) }
                    .firstOrNull { it in FAILURE_SANDBOX_REASONS }
                val adjusted = when {
                    evidence.sandboxReason != null -> evidence
                    evidence.facts["pagesEndReason"] == "page_limit" ||
                        evidence.facts["tocEndReason"] == "page_limit" ||
                        evidence.facts["tocTruncated"] == true -> evidence.copy(
                        category = if (evidence.ok) "sandbox" else evidence.category,
                        sandboxReason = "page_limit",
                    )
                    evidence.ok -> evidence.copy(sandboxReason = reason)
                    evidence.category in setOf("network", "rate_limited", "content_unavailable", "verification") -> evidence
                    errorReason != null -> evidence.copy(category = "sandbox", sandboxReason = errorReason)
                    externalJsLib && undefinedError -> evidence.copy(
                        category = "sandbox",
                        sandboxReason = "external_jslib_skipped",
                    )
                    else -> evidence
                }
                applyJsTimeout(adjusted, hasJsTimeout(result.lines, result.jsLogs))
            }
            is Map<*, *> -> mapResult(stage, result, sourceType)
            is Throwable -> throwable(stage, result)
            else -> StageEvidence(stage, ran = true, ok = false, reason = "无法识别的结果类型 ${result.javaClass.simpleName}", category = "unknown")
        }
    }

    /**
     * Map 形态分流：error Map → 阶段失败证据；序列化 DebugReport（含 data/lines 或 type+entry）→
     * 重建后按真实数据判定；其余 Map（含空 Map、手造 {ok:true} 这类无数据声明）→ 无法识别，
     * ran=false。注意：serialized StageEvidence（{stage,ran,ok,facts} 形）在本层同样不采信——
     * 「声称的证据」必须由 SourceFilterPolicy 在 check_source 摘要（verdict/evidence/validation）
     * 一致性核对通过后才可采纳，单独一个 Map 不足以证明它是 check_source 产物。
     */
    private fun mapResult(stage: String, map: Map<*, *>, sourceType: Int): StageEvidence {
        return when {
            "error" in map -> mapError(stage, map)
            looksLikeDebugReportMap(map) -> report(stage, debugReportFromMap(stage, map), sourceType)
            else -> StageEvidence(
                stage, ran = false, ok = false,
                reason = "阶段结果是无法识别的结构（无 error/lines/data 字段）：不能作为该阶段已执行的证据",
                category = "unrecognized",
            )
        }
    }

    /** 序列化 DebugReport 的形态判定：check_source JSON roundtrip 后必带 type+entry+lines+data。 */
    internal fun looksLikeDebugReportMap(map: Map<*, *>): Boolean =
        map.containsKey("data") || map.containsKey("lines") ||
            (map.containsKey("type") && map.containsKey("entry"))

    /** 序列化 StageEvidence 的形态判定：{stage,ran,ok,...}，仅形状检查——采信与否由调用方按摘要一致性决定。 */
    internal fun looksLikeStageEvidenceMap(map: Map<*, *>): Boolean =
        map["ran"] is Boolean && map["ok"] is Boolean

    /** 把序列化 Map 重建为 DebugReport：lines 只收字符串项，data 原样（List/Map 结构由 Gson 保证）。 */
    internal fun debugReportFromMap(stage: String, map: Map<*, *>): LegadoRuntime.DebugReport =
        LegadoRuntime.DebugReport(
            type = map["type"]?.toString() ?: stage,
            entry = map["entry"]?.toString().orEmpty(),
            lines = (map["lines"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            data = map["data"],
            jsLogs = (map["jsLogs"] as? List<*>)?.mapNotNull { it?.toString() },
        )

    /** DebugReport.data 形态：列表=List<Map>，目录/正文=Map，详情=Map<String,String?>。 */
    private fun report(stage: String, r: LegadoRuntime.DebugReport, sourceType: Int): StageEvidence {
        val lines = r.lines
        val evidence = when (stage) {
            // 发现与搜索同形（List<name/bookUrl/...>）：判定口径一致——首个可用项 name+bookUrl 非空。
            // 列表 0 条时看 lines 的 HTTP 证据：429/限流→rate_limited，CF 挑战→verification（高于 HTTP 403 network），
            // 520→network；HTTP 200 真空仍 empty；裸 403/404 等非 2xx 不改判站点问题。facts 只记 statusCode，不记正文/凭证。
            "搜索", "发现" -> {
                val list = r.data as? List<*>
                val count = list?.size ?: 0
                val statusCode = listStatusCode(lines)
                val facts = linkedMapOf<String, Any?>("resultCount" to count)
                if (statusCode != null) facts["statusCode"] = statusCode
                // 首个「可用」项：name+bookUrl 都非空。列表首项为空、后项有效不算规则失败。
                val usableIndex = list?.indexOfFirst { item ->
                    val m = item as? Map<*, *> ?: return@indexOfFirst false
                    !m["name"]?.toString().isNullOrBlank() && !m["bookUrl"]?.toString().isNullOrBlank()
                } ?: -1
                val usable = usableIndex.takeIf { it >= 0 }?.let { list?.get(it) as? Map<*, *> }
                val usableName = usable?.get("name")?.toString().orEmpty().trim()
                val usableBookUrl = usable?.get("bookUrl")?.toString().orEmpty().trim()
                facts["usableIndex"] = usableIndex
                facts["firstUsableName"] = usableName
                facts["firstUsableBookUrl"] = usableBookUrl
                val site = listHttpSiteSignal(stage, lines, statusCode)
                when {
                    list == null -> StageEvidence(stage, true, false, "DebugReport.data 不是列表（实际 ${r.data?.javaClass?.simpleName ?: "null"}）", "unknown", facts)
                    count == 0 && site != null -> StageEvidence(
                        stage, true, false, site.reason, site.category,
                        if (site.rateLimitHint != null) facts + ("rateLimitHint" to site.rateLimitHint) else facts,
                    )
                    count == 0 -> StageEvidence(stage, true, false, "列表 0 条：搜索页无结果或 bookList 规则不匹配", "empty", facts)
                    usableIndex < 0 -> StageEvidence(stage, true, false, "全部 $count 项均缺 name 或 bookUrl：规则未产出可访问条目", "empty", facts)
                    else -> StageEvidence(stage, true, true, facts = facts)
                }
            }
            "详情" -> {
                val fields = r.data as? Map<*, *>
                val name = fields?.get("name")?.toString().orEmpty().trim()
                val tocUrl = fields?.get("tocUrl")?.toString().orEmpty().trim()
                val facts = mapOf(
                    "name" to name, "tocUrl" to tocUrl,
                    "httpCode" to firstHttpCode(lines), "bodyChars" to bodyChars(lines),
                )
                when {
                    fields == null -> StageEvidence(stage, true, false, "DebugReport.data 不是字段表（实际 ${r.data?.javaClass?.simpleName ?: "null"}）", "unknown", facts)
                    // 文本源详情必须同时有名+目录地址才足以推进全链路；只有其一不算「详情 OK」。
                    // 未配置 tocUrl 规则时 runtime 会先回填详情页 URL；显式规则取空走到这里仍失败。
                    sourceType <= 0 && (name.isBlank() || tocUrl.isBlank()) ->
                        StageEvidence(stage, true, false,
                            if (name.isBlank() && tocUrl.isBlank()) "name 与 tocUrl 均为空：详情规则未命中或页面不是书籍详情"
                            else "详情只取到 ${if (name.isBlank()) "tocUrl" else "name"}（缺 ${if (name.isBlank()) "name" else "tocUrl"}）：不足以推进目录，不能算详情可用",
                            "empty", facts)
                    // 非文本源：详情规则可能只产书名/资源页，任一非空即算有详情证据
                    sourceType > 0 && name.isBlank() && tocUrl.isBlank() ->
                        StageEvidence(stage, true, false, "name 与 tocUrl 均为空：详情规则未命中或页面不是书籍详情", "empty", facts)
                    else -> StageEvidence(stage, true, true, facts = facts)
                }
            }
            "目录" -> {
                val map = r.data as? Map<*, *>
                val total = (map?.get("totalChapters") as? Number)?.toInt() ?: 0
                val chapters = map?.get("chapters") as? List<*> ?: emptyList<Any>()
                val firstUrl = chapters.firstOrNull()?.let { (it as? Map<*, *>)?.get("url") }?.toString().orEmpty().trim()
                val scan = scanTocChapters(chapters)
                val usable = scan.reportedUsable
                val diag = map?.get("tocDiagnostics") as? Map<*, *>
                val diagEnd = diag?.get("endReason")?.toString()?.takeIf { it in TOC_END_REASONS }
                val diagTruncated = diag?.get("truncated") == true || diagEnd == "page_limit"
                val lineTruncated = lines.any { it.contains("目录分页达到上限") }
                val truncated = diagTruncated || lineTruncated
                val pagesFetched = (diag?.get("pagesFetched") as? Number)?.toInt()
                    ?: lines.count { it.contains("页 HTTP") }
                val maxPages = (diag?.get("maxPages") as? Number)?.toInt()
                val endReason = diagEnd ?: if (truncated) "page_limit" else if (diag != null) "complete" else null
                val httpCode = firstHttpCode(lines)
                val facts = mutableMapOf<String, Any?>(
                    "totalChapters" to total,
                    "firstChapterUrl" to firstUrl,
                    "usableChapterIndex" to (usable?.index ?: -1),
                    "firstUsableChapterUrl" to usable?.url.orEmpty(),
                    "volumeCount" to scan.volumeCount,
                    "pagesFetched" to pagesFetched,
                    "tocTruncated" to truncated,
                    "tocEndReason" to endReason,
                    "maxTocPages" to maxPages,
                    "diagnosticsAvailable" to (diag != null),
                    "httpCode" to httpCode,
                )
                val tocNetwork = httpCode.takeIf { it in TOC_NETWORK_CODES }
                when {
                    map == null -> StageEvidence(stage, true, false, "DebugReport.data 不是目录结构（实际 ${r.data?.javaClass?.simpleName ?: "null"}）", "unknown", facts)
                    tocNetwork != null && (total <= 0 || usable == null) ->
                        StageEvidence(stage, true, false, "目录抓取返回 HTTP $tocNetwork：无法确认目录可用，不是规则问题", "network", facts)
                    total <= 0 -> StageEvidence(stage, true, false, "目录 0 条：chapterList 规则未命中", "empty", facts)
                    scan.blockedByEmptyNonVolume ->
                        StageEvidence(stage, true, false, "目录存在非卷名空 url：chapterUrl 规则未命中，不能把空链接首项当作卷名跳过", "empty", facts)
                    usable == null && scan.volumeCount > 0 ->
                        StageEvidence(stage, true, false, "目录仅卷名、无可读章节", "empty", facts)
                    usable == null ->
                        StageEvidence(stage, true, false, "目录无可读章节 url：chapterUrl 规则未命中", "empty", facts)
                    truncated -> StageEvidence(
                        stage, true, true,
                        category = "sandbox",
                        sandboxReason = "page_limit",
                        facts = facts,
                    )
                    else -> StageEvidence(stage, true, true, facts = facts)
                }
            }
            "正文" -> {
                val map = r.data as? Map<*, *>
                val chars = (map?.get("contentTotalChars") as? Number)?.toInt() ?: 0
                val pages = (map?.get("pages") as? Number)?.toInt() ?: 0
                val content = map?.get("content")?.toString().orEmpty()
                val mergedBefore = map?.get("mergedBeforeReplace")?.toString().orEmpty()
                val binaryNote = lines.any { it.contains("二进制资源") || it.contains("rawBytes") || it.contains("已按当前书源类型") }
                // 抓取侧诊断（runtime 契约 data.contentDiagnostics）：endReason + 逐页真实 HTTP 状态。
                // 旧报告无此字段 → 仅按「第N页 HTTP code」行回退，不猜分页完整性。
                val diag = map?.get("contentDiagnostics") as? Map<*, *>
                val endReason = diag?.get("endReason")?.toString()?.takeIf { it in CONTENT_END_REASONS }
                val pageDiags = (diag?.get("pages") as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
                // raw=httpStatus（直连真实码；webview/cache_fetch 不可知为 null）；reported=回调上报告（cache_fetch 伪码、非实际观测）
                val rawStatus = pageDiags.mapNotNull { (it["httpStatus"] as? Number)?.toInt() }
                val reportedStatus = pageDiags.mapNotNull { (it["reportedStatus"] as? Number)?.toInt() }
                // 回退仅在完全无诊断时看「第N页 HTTP code」行；diag 存在即不回退（缺失不补 200）
                val lineStatus = if (diag != null) emptyList() else lines.mapNotNull { CONTENT_PAGE_HTTP.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                val non2xx = (rawStatus + lineStatus + reportedStatus).firstOrNull { it !in 200..299 }
                val observedPages = if (diag != null) pageDiags.size else lineStatus.size
                val endIncomplete = endReason == "cycle"
                val fallbackHit = fallbackMarker(content)
                val gateHit = gateMarker(content)
                val wholeHtml = looksLikeWholeHtml(content)
                val suspect = contentSuspect(content)
                val facts = mutableMapOf<String, Any?>(
                    "contentTotalChars" to chars, "pages" to pages,
                    "mergedTotalChars" to (map?.get("mergedTotalChars") as? Number)?.toInt(),
                    "binaryResource" to binaryNote,
                    "diagnosticsAvailable" to (diag != null),
                    "pagesObserved" to observedPages,
                    "pagesEndReason" to endReason,
                    "httpStatusMin" to rawStatus.minOrNull(),
                    "httpStatusMax" to rawStatus.maxOrNull(),
                    "reportedStatusMin" to (reportedStatus + lineStatus).minOrNull(),
                    "reportedStatusMax" to (reportedStatus + lineStatus).maxOrNull(),
                )
                if (suspect != null) facts["contentSuspect"] = suspect
                fun failed(reason: String, category: String) = StageEvidence(stage, true, false, reason, category, facts)
                when {
                    map == null -> StageEvidence(stage, true, false, "DebugReport.data 不是正文结构（实际 ${r.data?.javaClass?.simpleName ?: "null"}）", "unknown", facts)
                    // CF 挑战页高于 HTTP 403 的 network：JS 回退到挑战页不能当有效正文。
                    suspect == "cf_challenge" ->
                        failed("正文样本是 Cloudflare 挑战页（contentSuspect=cf_challenge），无法确认正文可用", "verification")
                    non2xx != null ->
                        failed("正文抓取返回非成功状态：无法确认正文可用", "network")
                    suspect == "error_code_520" ->
                        failed("正文样本是 Cloudflare 源站故障页（contentSuspect=error_code_520），不是有效正文", "network")
                    suspect == "json_package" ->
                        failed("正文样本是完整 JSON 包（contentSuspect=json_package），JS 解析未展开，不能当作有效正文", "content_unverified")
                    endIncomplete ->
                        failed("正文分页未正常终止（endReason=$endReason）：抓取样本不完整，无法确认正文可用", "content_unverified")
                    fallbackHit != null ->
                        failed("正文整段匹配已知兜底/加载提示（$fallbackHit），无法确认正文可用", "content_unverified")
                    wholeHtml ->
                        failed("正文样本保留整页 HTML 结构（doctype/html 根），无法确认正文可用", "content_unverified")
                    gateHit != null ->
                        failed("正文样本是登录/付费拦截提示（$gateHit），无法确认正文可用", "empty")
                    sourceType <= 0 && (fallbackMarker(content, UNAVAILABLE_CONTENT_CORES) != null ||
                        UNREADABLE_PLACEHOLDER.matches(normalizeContent(bodyText(content)))) ->
                        failed("正文样本只有转码/解码失败提示或乱码占位，不能当作有效正文；先核对原页与阅读端内容，不据此直接改规则", "content_unavailable")
                    sourceType <= 0 && chars <= 0 ->
                        failed("正文 0 字符：content 规则未命中或页面为图片/付费页", "empty")
                    sourceType <= 0 ->
                        StageEvidence(stage, true, true, facts = facts)
                    // 非文本源（音频/图片/文件/视频）：不拿文字字数硬判，且宁缺毋滥。
                    // 可信证据仅两类：①抓取侧记录到二进制资源（binaryNote）；②正文规则直出纯资源 URL 列表。
                    // 任意非空 mergedBeforeReplace 不算数（普通文本页也有字符串，会酿成 full 假阳性）。
                    binaryNote || looksLikeResourceUrls(content) || looksLikeResourceUrls(mergedBefore) ->
                        StageEvidence(stage, true, true, facts = facts + ("mediaEvidence" to "resource"))
                    else ->
                        StageEvidence(stage, true, false,
                            "非文本源正文无可靠媒体证据：未抓到二进制资源、content 规则也未直出资源 URL（contentTotalChars=$chars）",
                            "media_unverified", facts)
                }
            }
            else -> StageEvidence(stage, ran = true, ok = true, facts = mapOf("lines" to lines.size))
        }
        return applyJsTimeout(evidence, hasJsTimeout(lines, r.jsLogs))
    }

    /**
     * 正文内容是否为「纯资源 URL 列表」：整段全是 http(s) URL token（可含多行/多页 URL）。
     * 小说正文、登录页、JSON 数据块等含普通文字的都不算——防止把任意字符串当媒体证据。
     */
    private fun looksLikeResourceUrls(text: String): Boolean {
        if (text.isBlank()) return false
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.isNotEmpty() && tokens.all { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** 正文页 HTTP 状态回退行：「第N页 HTTP code」。 */
    private val CONTENT_PAGE_HTTP = Regex("第\\d+页 HTTP (\\d+)")

    private val CONTENT_END_REASONS = setOf("complete", "next_chapter", "cycle", "page_limit")
    private val TOC_END_REASONS = setOf("complete", "page_limit")

    private fun normalizeContent(text: String): String =
        text.lowercase().replace(Regex("[\\s ]+"), "").trim()

    /** 简单 HTML 片段剥标签取文本；非 HTML 原样。 */
    private fun bodyText(content: String): String = runCatching {
        if (content.contains('<')) org.jsoup.Jsoup.parseBodyFragment(content).body()?.text() ?: content else content
    }.getOrDefault(content)

    /**
     * 固定兜底/加载/解密提示：归一化后整段等于「核心短语」或「核心+有限尾缀/标点」。
     * 完整匹配，不含词即失败。
     */
    private val FALLBACK_CORES = listOf("获取失败", "解密失败", "加载失败", "加载中", "内容正在加载", "内容正在载入")
    private val FALLBACK_TAILS = listOf(
        "请重试", "请稍后重试", "请稍候", "请稍后", "请刷新", "请刷新重试",
    )

    private val UNAVAILABLE_CONTENT_CORES = listOf("转码失败", "内容转码失败", "章节内容转码失败", "解码失败")
    private val UNREADABLE_PLACEHOLDER = Regex("[?？\\uFFFD]+")

    internal fun fallbackMarker(content: String, cores: List<String> = FALLBACK_CORES): String? {
        val norm = normalizeContent(bodyText(content)).trimEnd('.', '。', '…', '~', '！', '!', '？', '?', '，', ',', '、')
        if (norm.isEmpty()) return null
        return cores.firstOrNull { core ->
            if (!norm.startsWith(core)) return@firstOrNull false
            val tail = norm.removePrefix(core).trimStart(',', '，', '、', ' ', ' ').trimEnd('.', '。', '…', '~', '！', '!', '？', '?', '，', ',', '、')
            tail.isEmpty() || FALLBACK_TAILS.any { tail == it }
        }
    }

    private fun looksLikeWholeHtml(content: String): Boolean {
        var root = content.trimStart().removePrefix("\uFEFF").trimStart()
        while (root.startsWith("<!--")) root = root.substringAfter("-->", "").trimStart()
        return Regex("^(?:<!doctype\\s+html(?=[\\s>])|<html(?=[\\s>/]))", RegexOption.IGNORE_CASE).containsMatchIn(root)
    }

    /** 登录/付费/验证拦截提示：归一化整段==明确提示短语或「短语+有限尾缀」；不看 merged、不含词判、不看行。 */
    private val GATE_CORES = listOf(
        "请先登录", "登录后查看", "需要登录", "请登录", "请先注册", "请输入验证码",
        "请完成人机验证", "accessdenied", "vip专享", "付费章节", "本章为付费", "请购买",
        "订阅本章", "请先登录后查看本章内容", "开通vip", "开通会员", "本章需购买",
    )
    private val GATE_TAILS = listOf("后查看", "后可查看", "后继续阅读", "后阅读", "后可阅读", "后查看本章内容", "查看本章内容")

    private fun gateMarker(content: String): String? {
        if (content.isBlank()) return null
        val norm = normalizeContent(bodyText(content)).trimEnd('.', '。', '…', '~', '！', '!', '？', '?', '，', ',')
        return GATE_CORES.firstOrNull { core ->
            if (!norm.startsWith(core)) return@firstOrNull false
            val tail = norm.removePrefix(core).trimStart(',', '，', '、').trimEnd('.', '。', '…', '~', '！', '!', '？', '?', '，', ',')
            tail.isEmpty() || GATE_TAILS.any { tail == it }
        }
    }

    /** CheckSourceRunner 把异常塞成 {error=...} 时，按错误文本分类；VerificationRequiredException/JsChallenge 单独归类。 */
    private fun mapError(stage: String, map: Map<*, *>): StageEvidence {
        val message = map["error"]?.toString().orEmpty()
        val sandboxReason = map["sandboxReason"]?.toString() ?: sandboxReason(message)
        val category = map["category"]?.toString() ?: categorizeMessage(message)
        return StageEvidence(stage, ran = true, ok = false, reason = message.ifBlank { "未知错误" }, category = category, sandboxReason = sandboxReason)
    }

    private fun throwable(stage: String, t: Throwable): StageEvidence {
        val message = t.message.orEmpty()
        return StageEvidence(stage, ran = true, ok = false, reason = message.ifBlank { t.javaClass.simpleName }, category = categorizeMessage(message, t), sandboxReason = sandboxReason(message, t))
    }

    /**
     * 按错误文本/异常类型归类，VerificationRequiredException（verification）与
     * JsChallengeException（captcha）与网络失败（network）分开——调用方/filter 按类别决策。
     * sandbox = 沙箱自身能力不足（缺 API/签名、引擎内部异常），不代表规则错误，需到阅读端取证。
     */
    fun categorizeMessage(message: String, throwable: Throwable? = null): String {
        val lower = message.lowercase()
        return when {
            throwable is com.mina.legadostudio.verification.VerificationRequiredException -> "verification"
            throwable is com.mina.legadostudio.network.JsChallengeException -> "captcha"
            lower.contains("verificationrequired") || lower.contains("需要在 app 内完成验证") -> "verification"
            // CF 挑战高于 HTTP 403 的 network：同一条「HTTP 403 cf-mitigated Just a moment」归 verification。
            hasCfChallengeMarker(message) -> "verification"
            lower.contains("js_challenge") || lower.contains("jschallenge") || lower.contains("人机挑战") -> "captcha"
            lower.contains("captcha") || lower.contains("验证码") -> "captcha"
            message.contains("js执行超时") -> "sandbox"
            lower.contains("unknownhost") || lower.contains("无法解析") || lower.contains("sockettimeout") ||
                lower.contains("connecttimeout") || lower.contains("failed to connect") || lower.contains("timed out") ||
                lower.contains("连接超时") || lower.contains("network") || lower.contains("unreachable") ||
                lower.contains("http 403") || lower.contains("http 520") ||
                lower.contains("error code: 520") || lower.contains("error code: 403") -> "network"
            isSandboxLimit(message, throwable) -> "sandbox"
            lower.contains("illegalargument") || lower.contains("缺少") || lower.contains("配置") ||
                lower.contains("不是合法 json") || lower.contains("必须为") -> "config"
            else -> "unknown"
        }
    }

    /** 列表 0 条且页面命中站点限流提示时，runtime 在 lines 追加的标记行前缀。 */
    const val SITE_RATE_LIMIT_LINE = "【站点限流】"
    val SITE_RATE_LIMIT_MARKERS = listOf(
        "搜索间隔", "访问过于频繁", "操作过于频繁", "搜索过于频繁", "请求过于频繁",
        "后再进行搜索", "请稍后再搜索", "too many requests",
    )

    fun isSiteRateLimitText(text: String): Boolean {
        val lower = text.lowercase()
        return SITE_RATE_LIMIT_MARKERS.any { lower.contains(it) }
    }

    /** 列表页 HTTP 429 或正文命中限流提示时返回提示片段（≤80 字），否则 null。 */
    fun siteRateLimitSnippet(body: String, httpCode: Int): String? {
        val text = runCatching { org.jsoup.Jsoup.parse(body).text() }.getOrDefault(body)
        val lower = text.lowercase()
        val hit = SITE_RATE_LIMIT_MARKERS.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull()
        return when {
            hit != null -> text.substring((hit - 20).coerceAtLeast(0), (hit + 60).coerceAtMost(text.length)).trim()
            httpCode == 429 -> "HTTP 429"
            else -> null
        }
    }

    private val SANDBOX_MARKERS = listOf("属验收环境限制", "沙箱不支持", "沙箱未实现", "【API 提示】")
    private val ENGINE_INTERNAL = Regex("ClassCastException|NullPointerException|NoSuchMethod|NoClassDefFound|UnsupportedOperationException|NoSuchField")
    private val UNDEFINED_ERROR_LINE = Regex("is not defined|未定义|ReferenceError", RegexOption.IGNORE_CASE)
    private val FAILURE_SANDBOX_REASONS = setOf("content_too_large", "binary_skipped", "missing_java_api", "signature_unsupported", "webjs_timeout")
    private val LEGADO_API_NAMES = setOf(
        "Packages", "org", "java.lang", "javax", "android", "java.getElement", "java.getElements",
        "importScript", "t2s", "s2t", "createAsymmetricCrypto", "createSymmetricCrypto",
        "getVerificationCode", "startBrowser", "startBrowserAwait",
    )
    private val UNDEFINED_NAME = Regex("(?:^|[\\s:])([\\w$]+(?:\\.[\\w$]+)*)\\s+(?:is not defined|未定义)", RegexOption.IGNORE_CASE)
    private val API_NAME_ERROR = Regex(
        "([\\w$]+(?:\\.[\\w$]+)*)\\s+(?:is not a function|is not callable)|(?:Cannot find function|找不到函数)\\s+[\\\"“]?([\\w$]+(?:\\.[\\w$]+)*)",
        RegexOption.IGNORE_CASE,
    )

    fun sandboxReason(message: String, throwable: Throwable? = null): String? {
        val lower = message.lowercase()
        return when {
            lower.contains("content_too_large") -> "content_too_large"
            message.contains("js执行超时") -> "webjs_timeout"
            message.contains("二进制资源") && (message.contains("跳过") || message.contains("未下载正文")) -> "binary_skipped"
            message.contains("page_limit") -> "page_limit"
            message.contains("【签名提示】") || message.contains("签名不支持") -> "signature_unsupported"
            message.contains("jsLib 为外部 URL") || message.contains("外部 jsLib") -> "external_jslib_skipped"
            message.contains("【API 提示】") || message.contains("属验收环境限制") || lower.contains("missing_api") -> "missing_java_api"
            message.contains("User-Agent") && (message.contains("默认 UA") || message.contains("default UA")) -> "default_user_agent"
            listOfNotNull(
                UNDEFINED_NAME.find(message)?.groupValues?.get(1),
                API_NAME_ERROR.find(message)?.let { match -> match.groupValues[1].ifBlank { match.groupValues[2] } },
            ).any { name ->
                name in LEGADO_API_NAMES ||
                    LEGADO_API_NAMES.any { it.startsWith("java.") && it != "java.lang" && it.substringAfterLast('.') == name } ||
                    name.startsWith("org.") || name.startsWith("java.lang.") ||
                    name.startsWith("javax.") || name.startsWith("android.") || name.startsWith("Packages.")
            } -> "missing_java_api"
            throwable != null && generateSequence(throwable) { it.cause }.any { ENGINE_INTERNAL.containsMatchIn(it.javaClass.name) } -> "missing_java_api"
            else -> null
        }
    }

    fun isSandboxLimit(message: String, throwable: Throwable?): Boolean {
        if (sandboxReason(message, throwable) != null) return true
        if (SANDBOX_MARKERS.any { message.contains(it) }) return true
        if (ENGINE_INTERNAL.containsMatchIn(message)) return true
        return generateSequence(throwable) { it.cause }.any { ENGINE_INTERNAL.containsMatchIn(it.javaClass.name) }
    }

    private fun firstHttpCode(lines: List<String>): Int? =
        lines.firstNotNullOfOrNull { Regex("HTTP (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun bodyChars(lines: List<String>): Int? =
        lines.firstNotNullOfOrNull { Regex("页面 (\\d+) 字符").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private val TOC_NETWORK_CODES = setOf(403, 502, 503, 520, 521, 522, 523, 524)

    private val CF_CHALLENGE_MARKERS = listOf(
        "cf-mitigated", "just a moment", "checking your browser",
        "challenges.cloudflare.com", "cf-chl-", "cf_chl", "challenge-platform",
    )
    private val CF_INTERSTITIAL_MARKERS = listOf(
        "cf-mitigated", "cf_chl", "cf-chl-", "challenges.cloudflare.com",
    )
    private val ERROR_CODE_520 = Regex("(?i)error code:\\s*520\\b")
    private val NAKED_ERROR_CODE_520 = Regex("(?i)^error\\s*code:\\s*520\\s*$")

    /** 错误文本/日志行是否带 Cloudflare 挑战特征（不含正文样本——正文走 [contentSuspect]）。 */
    internal fun hasCfChallengeMarker(text: String): Boolean {
        val lower = text.lowercase()
        return CF_CHALLENGE_MARKERS.any { lower.contains(it) }
    }

    const val WEBJS_TIMEOUT_REASON =
        "js执行超时：webJs 返回 null 会被轮询到超时，搜索 0 结果或页面脚本异常也会表现为超时，不等于规则错误，也不等于 0 条"

    internal fun hasJsTimeout(lines: List<String>, jsLogs: List<String>? = null): Boolean =
        lines.any { it.contains("js执行超时") } || jsLogs.orEmpty().any { it.contains("js执行超时") }

    /**
     * js执行超时不能视作阶段成功：站点挑战/空列表/页内脚本异常都会表现为超时。
     * 已归 verification/captcha/network/rate_limited 的更具体站点信号保留类别，只保证 !ok。
     */
    internal fun applyJsTimeout(evidence: StageEvidence, timeout: Boolean): StageEvidence {
        if (!timeout) return evidence
        if (evidence.category in setOf("verification", "captcha", "network", "rate_limited")) {
            return if (evidence.ok) evidence.copy(ok = false) else evidence
        }
        return evidence.copy(
            ok = false,
            category = "sandbox",
            sandboxReason = "webjs_timeout",
            reason = WEBJS_TIMEOUT_REASON,
        )
    }

    /**
     * 正文样本是否像「JS 解析失败后退回的原始响应」而不是小说正文。
     * 返回稳定种类码写入 facts.contentSuspect；整段匹配，不含词即失败——
     * 小说里出现数字 520 或花括号不算。
     * - error_code_520：整段就是 Cloudflare 源站故障正文
     * - json_package：整段是完整 JSON 对象/数组
     * - cf_challenge：CF 挑战页（cf-mitigated / cf_chl / 短 Just a moment；challenge-platform 需配合挑战文案或无章节散文的短壳页）
     */
    internal fun contentSuspect(content: String): String? {
        if (content.isBlank()) return null
        val trimmed = content.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return null
        val text = bodyText(trimmed).trim()
        if (NAKED_ERROR_CODE_520.matches(trimmed) || NAKED_ERROR_CODE_520.matches(text)) return "error_code_520"
        if (isCfChallengeContent(trimmed, text)) return "cf_challenge"
        if (looksLikeCompleteJsonPackage(trimmed)) return "json_package"
        return null
    }

    /**
     * CF 挑战页：结构标记（cf-mitigated/cf_chl）单独即可；
     * challenge-platform 常见于业务页页尾，必须再配挑战文案，或可见文本不像章节散文的短壳页。
     * 「Just a moment」只在整段几乎就是这句时成立，不误伤小说对白。
     */
    private fun isCfChallengeContent(raw: String, text: String): Boolean {
        val lower = raw.lowercase()
        if (CF_INTERSTITIAL_MARKERS.any { lower.contains(it) }) return true
        val hasPlatform = lower.contains("challenge-platform")
        val hasMoment = lower.contains("just a moment") || lower.contains("checking your browser")
        if (hasPlatform && hasMoment) return true
        if (hasPlatform && looksLikeWholeHtml(raw) && isSparseChallengeShell(text)) return true
        val compact = normalizeContent(text).replace(Regex("[.,…\\-—!?~'\"“”]"), "")
        return compact in setOf(
            "justamoment", "checkingyourbrowser",
            "justamomentcheckingyourbrowser", "justamomentpleasewait",
        )
    }

    /** 无章节散文的短挑战壳：可见文本很少且几乎没有汉字，避免业务页页尾脚本误伤。 */
    private fun isSparseChallengeShell(text: String): Boolean {
        if (text.isBlank()) return true
        if (text.length >= 80) return false
        val cjk = text.count { it in '\u4e00'..'\u9fff' }
        return cjk < 8
    }

    /** 整段是完整 JSON 对象或数组；小说里夹花括号不会 parse 成功。 */
    internal fun looksLikeCompleteJsonPackage(text: String): Boolean {
        val t = text.trim().removePrefix("\uFEFF").trim()
        if (t.length < 2) return false
        val start = t.first()
        val end = t.last()
        if (!((start == '{' && end == '}') || (start == '[' && end == ']'))) return false
        return runCatching {
            val parsed = JsonParser.parseString(t)
            parsed.isJsonObject || parsed.isJsonArray
        }.getOrDefault(false)
    }

    /**
     * 搜索/发现 lines 上的 HTTP 状态：优先 `HTTP 123`，否则仅在无 2xx 码时认 `error code: 520`。
     * 只返回状态码，不解析 URL/正文/凭证。
     */
    internal fun listStatusCode(lines: List<String>): Int? {
        firstHttpCode(lines)?.let { return it }
        return if (lines.any { ERROR_CODE_520.containsMatchIn(it) }) 520 else null
    }

    /**
     * 列表 0 条时的站点侧信号。仅在明确证据下归类，不把所有非 2xx 算站点问题：
     * 429 / 【站点限流】→ rate_limited；CF 挑战特征 → verification（高于 HTTP 403 的 network）；HTTP 520 → network。
     */
    internal data class ListHttpSiteSignal(
        val category: String,
        val reason: String,
        val statusCode: Int?,
        val rateLimitHint: String? = null,
    )

    internal fun listHttpSiteSignal(stage: String, lines: List<String>, statusCode: Int? = listStatusCode(lines)): ListHttpSiteSignal? {
        val label = if (stage == "搜索") "搜索" else "列表"
        val blob = lines.joinToString("\n").lowercase()
        val rateHint = lines.firstOrNull { it.startsWith(SITE_RATE_LIMIT_LINE) }?.removePrefix(SITE_RATE_LIMIT_LINE)
        return when {
            rateHint != null || statusCode == 429 -> ListHttpSiteSignal(
                category = "rate_limited",
                reason = "${label}页返回站点限流提示，不是规则问题；按站点提示等待后用真实书名重测，无需修改规则",
                statusCode = statusCode ?: 429,
                rateLimitHint = rateHint ?: "HTTP 429",
            )
            hasCfChallengeMarker(blob) -> ListHttpSiteSignal(
                category = "verification",
                reason = "${label}页返回 Cloudflare 挑战${statusCode?.let { "（HTTP $it）" }.orEmpty()}，不是规则空结果；需在 App 内完成验证后重测。沙箱观测不能替代真机阅读结果",
                statusCode = statusCode,
            )
            statusCode == 520 -> ListHttpSiteSignal(
                category = "network",
                reason = "${label}页返回 HTTP 520 Cloudflare 源站故障，不是规则空结果。沙箱观测不能替代真机阅读结果",
                statusCode = 520,
            )
            else -> null
        }
    }

    /**
     * 对齐阅读 `String.isTrue()`：空白为 false；false/no/not/off/0/null 为 false；其余非空为 true。
     * 卷名规则常取出「正文」「第一卷」等非布尔文本，官方同样视为 true。
     */
    internal fun legadoIsTrue(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> {
            val text = value.toString().trim()
            text.isNotEmpty() && !text.matches(Regex("(?i)false|no|not|off|0|null"))
        }
    }

    internal data class TocUsableChapter(val index: Int, val url: String)

    internal data class TocChapterScan(
        val usable: TocUsableChapter?,
        val volumeCount: Int,
        val firstEmptyNonVolumeIndex: Int,
    ) {
        val blockedByEmptyNonVolume: Boolean
            get() = firstEmptyNonVolumeIndex >= 0 &&
                (usable == null || firstEmptyNonVolumeIndex < usable.index)
        val reportedUsable: TocUsableChapter?
            get() = usable.takeUnless { blockedByEmptyNonVolume }
    }

    /**
     * 扫描目录项：跳过官方 isVolume 卷名，定位第一个可读章节 URL。
     * 非卷名空 URL 若出现在可读章之前，不算可用——不能把任意第一章取空静默吞掉。
     */
    internal fun scanTocChapters(chapters: List<*>): TocChapterScan {
        var volumeCount = 0
        var usable: TocUsableChapter? = null
        var firstEmptyNonVolumeIndex = -1
        chapters.forEachIndexed { index, raw ->
            val item = raw as? Map<*, *> ?: return@forEachIndexed
            if (legadoIsTrue(item["isVolume"])) {
                volumeCount++
                return@forEachIndexed
            }
            val url = item["url"]?.toString().orEmpty().trim()
            if (url.isNotBlank()) {
                if (usable == null) usable = TocUsableChapter(index, url)
            } else if (firstEmptyNonVolumeIndex < 0) {
                firstEmptyNonVolumeIndex = index
            }
        }
        return TocChapterScan(usable, volumeCount, firstEmptyNonVolumeIndex)
    }

    /** autoChain / 证据层共用：目录判定 ok 时的正文入口；被非卷名空 URL 挡住时返回 null。 */
    internal fun firstUsableChapterUrl(chapters: List<*>): String? =
        scanTocChapters(chapters).reportedUsable?.url
}
