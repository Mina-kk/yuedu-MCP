package com.mina.legadostudio.domain

import com.mina.legadostudio.runtime.LegadoRuntime

/**
 * 把 LegadoRuntime.DebugReport 的 data/lines（或其 JSON 序列化形态）变成结构化样本检查证据。
 * HTTP 成功、规则有输出与样本通过分开判断；不证明全文完整、解密语义或目标阅读版本兼容。
 *
 * 判定口径要点：
 * - 搜索看「首个可用项」（name+bookUrl 均非空），不是列表首项——首项为空、后项有效不算失败。
 * - 详情对文本源要求 name 与 tocUrl 同时非空（缺一不足以推进目录）；非文本源任一非空即可。
 * - 正文拦截已知非成功状态、不完整分页、明确提示和整页 HTML；缺失观测不补成成功。
 * - 非文本源不拿文字字数硬判，只接受二进制资源记录或纯资源 URL；否则标 media_unverified。
 * - 序列化路径：check_source 结果经 Gson roundtrip 后阶段值是 {type,entry,lines,data} 形 Map，
 *   这里会识别并重建为 DebugReport 再判定（不是只找 error 字段——否则真实通过的报告会被误判失败）。
 *   无法识别的任意 Map（无 error/lines/data/evidence 结构）不算「已跑」的证据：ran=false，
 *   不作成功也不作失败，由上层按「未验证/无法证实」处理。
 *
 * 判定结果是纯数据（StageEvidence），CheckSourceRunner / SourceFilterPolicy / 测试复用同一口径。
 */
object SourceCheckEvidence {

    /** 一个阶段的结构化证据。 */
    data class StageEvidence(
        val stage: String,
        /** ran=false = 该阶段没跑（无入口/未传参数）或结果无法识别为已跑证据；ran=true 才有 ok/failedReason。 */
        val ran: Boolean,
        val ok: Boolean,
        /** 失败/未跑原因（面向用户的可读说明）；ok 时为 null。 */
        val reason: String? = null,
        /** 结构化分类：network / verification / captcha / sandbox / config / empty / media_unverified / content_unverified / not_run / unrecognized / unknown；ok 时 null。 */
        val category: String? = null,
        /** 关键观测值（搜索条数、章节数、正文字数…），供 filter / 调用方写理由。 */
        val facts: Map<String, Any?> = emptyMap(),
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
            is LegadoRuntime.DebugReport -> report(stage, result, sourceType)
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
        )

    /** DebugReport.data 形态：列表=List<Map>，目录/正文=Map，详情=Map<String,String?>。 */
    private fun report(stage: String, r: LegadoRuntime.DebugReport, sourceType: Int): StageEvidence {
        val lines = r.lines
        return when (stage) {
            // 发现与搜索同形（List<name/bookUrl/...>）：判定口径一致——首个可用项 name+bookUrl 非空。
            "搜索", "发现" -> {
                val list = r.data as? List<*>
                val count = list?.size ?: 0
                val facts = linkedMapOf<String, Any?>("resultCount" to count)
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
                when {
                    list == null -> StageEvidence(stage, true, false, "DebugReport.data 不是列表（实际 ${r.data?.javaClass?.simpleName ?: "null"}）", "unknown", facts)
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
                    // 文本源详情必须同时有名+目录地址才足以推进全链路；只有其一不算「详情 OK」
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
                val facts = mapOf("totalChapters" to total, "firstChapterUrl" to firstUrl, "pagesFetched" to lines.count { it.contains("页 HTTP") })
                when {
                    map == null -> StageEvidence(stage, true, false, "DebugReport.data 不是目录结构（实际 ${r.data?.javaClass?.simpleName ?: "null"}）", "unknown", facts)
                    total <= 0 -> StageEvidence(stage, true, false, "目录 0 条：chapterList 规则未命中", "empty", facts)
                    firstUrl.isBlank() -> StageEvidence(stage, true, false, "目录首项 url 为空：chapterUrl 规则未命中", "empty", facts)
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
                val endIncomplete = endReason == "cycle" || endReason == "page_limit"
                val fallbackHit = fallbackMarker(content)
                val gateHit = gateMarker(content)
                val wholeHtml = looksLikeWholeHtml(content)
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
                fun failed(reason: String, category: String) = StageEvidence(stage, true, false, reason, category, facts)
                when {
                    map == null -> StageEvidence(stage, true, false, "DebugReport.data 不是正文结构（实际 ${r.data?.javaClass?.simpleName ?: "null"}）", "unknown", facts)
                    non2xx != null ->
                        failed("正文抓取返回非成功状态：无法确认正文可用", "network")
                    endIncomplete ->
                        failed("正文分页未正常终止（endReason=$endReason）：抓取样本不完整，无法确认正文可用", "content_unverified")
                    fallbackHit != null ->
                        failed("正文整段匹配已知兜底/加载提示（$fallbackHit），无法确认正文可用", "content_unverified")
                    wholeHtml ->
                        failed("正文样本保留整页 HTML 结构（doctype/html 根），无法确认正文可用", "content_unverified")
                    gateHit != null ->
                        failed("正文样本是登录/付费拦截提示（$gateHit），无法确认正文可用", "empty")
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

    internal fun fallbackMarker(content: String): String? {
        val norm = normalizeContent(bodyText(content)).trimEnd('.', '。', '…', '~', '！', '!', '？', '?', '，', ',', '、')
        if (norm.isEmpty()) return null
        return FALLBACK_CORES.firstOrNull { core ->
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
        val category = map["category"]?.toString() ?: categorizeMessage(message)
        return StageEvidence(stage, ran = true, ok = false, reason = message.ifBlank { "未知错误" }, category = category)
    }

    private fun throwable(stage: String, t: Throwable): StageEvidence {
        val message = t.message.orEmpty()
        return StageEvidence(stage, ran = true, ok = false, reason = message.ifBlank { t.javaClass.simpleName }, category = categorizeMessage(message, t))
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
            lower.contains("js_challenge") || lower.contains("jschallenge") || lower.contains("人机挑战") -> "captcha"
            lower.contains("captcha") || lower.contains("验证码") -> "captcha"
            lower.contains("unknownhost") || lower.contains("无法解析") || lower.contains("sockettimeout") ||
                lower.contains("connecttimeout") || lower.contains("failed to connect") || lower.contains("timed out") ||
                lower.contains("连接超时") || lower.contains("network") || lower.contains("unreachable") -> "network"
            isSandboxLimit(message, throwable) -> "sandbox"
            lower.contains("illegalargument") || lower.contains("缺少") || lower.contains("配置") ||
                lower.contains("不是合法 json") || lower.contains("必须为") -> "config"
            else -> "unknown"
        }
    }

    private val SANDBOX_MARKERS = listOf("属验收环境限制", "沙箱不支持", "沙箱未实现", "【API 提示】")
    private val ENGINE_INTERNAL = Regex("ClassCastException|NullPointerException|NoSuchMethod|NoClassDefFound|UnsupportedOperationException|NoSuchField")

    private fun isSandboxLimit(message: String, throwable: Throwable?): Boolean {
        if (SANDBOX_MARKERS.any { message.contains(it) }) return true
        if (ENGINE_INTERNAL.containsMatchIn(message)) return true
        return generateSequence(throwable) { it.cause }.any { ENGINE_INTERNAL.containsMatchIn(it.javaClass.name) }
    }

    private fun firstHttpCode(lines: List<String>): Int? =
        lines.firstNotNullOfOrNull { Regex("HTTP (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun bodyChars(lines: List<String>): Int? =
        lines.firstNotNullOfOrNull { Regex("页面 (\\d+) 字符").find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
