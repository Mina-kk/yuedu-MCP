package com.mina.legadostudio.domain

import com.mina.legadostudio.runtime.LegadoRuntime

/**
 * 把 LegadoRuntime.DebugReport 的 data/lines（或其 JSON 序列化形态）变成结构化可用性证据：
 * HTTP 200 ≠ 可用——搜索必须真的产出可访问条目，目录必须有 >0 章节，正文必须有真实内容。
 * 全部由已落地的 DebugReport 字段判定，不猜页面。
 *
 * 判定口径要点：
 * - 搜索看「首个可用项」（name+bookUrl 均非空），不是列表首项——首项为空、后项有效不算失败。
 * - 详情对文本源要求 name 与 tocUrl 同时非空（缺一不足以推进目录）；非文本源任一非空即可。
 * - 正文对文本源要求 contentTotalChars>0 且样本不是登录/付费拦截页；非文本源（音频/图片/文件/视频）
 *   不拿文字字数硬判，只接受「抓取侧二进制资源记录」或「正文规则直出的纯资源 URL」两类证据，
 *   其他情况如实标 media_unverified（调用方据此封顶 partial，不给 full）。
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
        /** 结构化分类：network / verification / captcha / config / empty / media_unverified / not_run / unrecognized / unknown；ok 时 null。 */
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
                val facts = mapOf(
                    "contentTotalChars" to chars, "pages" to pages,
                    "mergedTotalChars" to (map?.get("mergedTotalChars") as? Number)?.toInt(),
                    "binaryResource" to binaryNote,
                )
                when {
                    map == null -> StageEvidence(stage, true, false, "DebugReport.data 不是正文结构（实际 ${r.data?.javaClass?.simpleName ?: "null"}）", "unknown", facts)
                    // 拦截页特征先于一切：chars>0 的登录/付费页不能虚报「正文可用」（文本/非文本同口径）
                    looksLikeGatePage(content, mergedBefore) ->
                        StageEvidence(stage, true, false, "正文样本像登录/付费/拦截页（${gateMarker(content + mergedBefore)}），不能算正文可用", "empty", facts)
                    sourceType <= 0 && chars <= 0 ->
                        StageEvidence(stage, true, false, "正文 0 字符：content 规则未命中或页面为图片/付费页", "empty", facts)
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

    /** 登录/付费/验证码拦截页特征：chars>0 的文本页不能因此虚报「正文可用」。 */
    private fun looksLikeGatePage(content: String, merged: String): Boolean = gateMarker(content + merged) != null

    private fun gateMarker(text: String): String? {
        if (text.isBlank()) return null
        val markers = listOf(
            "请先登录", "登录后查看", "需要登录", "vip专享", "付费章节", "本章为付费",
            "请购买", "订阅本章", "充值", "验证码", "人机验证", "access denied", "请登录",
        )
        val lower = text.lowercase()
        return markers.firstOrNull { lower.contains(it.lowercase()) }
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
            lower.contains("illegalargument") || lower.contains("缺少") || lower.contains("配置") ||
                lower.contains("不是合法 json") || lower.contains("必须为") -> "config"
            else -> "unknown"
        }
    }

    private fun firstHttpCode(lines: List<String>): Int? =
        lines.firstNotNullOfOrNull { Regex("HTTP (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun bodyChars(lines: List<String>): Int? =
        lines.firstNotNullOfOrNull { Regex("页面 (\\d+) 字符").find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
