package com.mina.legadostudio.domain

import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.LegadoRuntime
import kotlinx.coroutines.CancellationException

/**
 * `check_source` 工具的纯逻辑编排：组装各阶段入口、跑 validation + debug、
 * 产出逐阶段结果与 timingsMs（含缓存、JS 求值与联网耗时）。
 * 不依赖 Android Context，可被 JVM 单测直接实例化。
 *
 * 与旧版的差异（并行任务要求）：
 * - 成功不等于 HTTP 200：每阶段按 SourceCheckEvidence 判定（搜索首个可用项、
 *   详情 name+tocUrl、目录有可读章节 url、正文非拦截页且有字符/媒体证据）。
 * - `auto=true` 时做有限链路推进：从**已通过**阶段的 DebugReport 取推进入口
 *   （搜索首个可用项 bookUrl → 详情 tocUrl → 目录第一个可读章节 url → 正文）。
 *   只补用户没显式传入口的阶段，不覆盖显式参数；失败阶段绝不拿来推进。
 * - 阶段 error 分 verification / captcha / network / config / sandbox / 探测类 / unknown 类别。
 * - CancellationException 必须传播（不被吞成 error Map）。
 */
object CheckSourceRunner {

    data class Request(
        val source: String,
        val searchKey: String? = null,
        val detailUrl: String? = null,
        val tocUrl: String? = null,
        val contentUrl: String? = null,
        /**
         * 可选发现入口：传任意一个即跑「发现」阶段。
         * - exploreKey 为分类名时入口为「分类名::exploreUrl」（对应书源 exploreUrl/ruleExplore 的发现链路）；
         * - 只传 exploreUrl 时入口为该 URL 本身（无分类名，等价于 exploreKey 为空的「::URL」）。
         * 「发现」不是四段 verdict 的组成（full 仍以搜索/详情/目录/正文判），只作可选附加段取证。
         */
        val exploreKey: String? = null,
        val exploreUrl: String? = null,
        /** true = 全程实时（不用任务快照缓存）。 */
        val refresh: Boolean = false,
        /**
         * true = 启用有限链路推进：搜索成功后取首个可用项 bookUrl 跑详情，详情成功后取 tocUrl 跑目录，
         * 目录成功后取第一个可读章节 url 跑正文。用户显式传入的入口优先，auto 只补缺。
         * 默认 false 保持旧行为（只跑显式传的阶段）。
         */
        val auto: Boolean = false,
    )

    suspend fun run(
        request: Request,
        runtime: LegadoRuntime,
        cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?,
        origin: com.mina.legadostudio.network.HttpOrigin? = null,
    ): Map<String, Any> {
        // 与 save_source/debug_source 同一套 JSON 错误提示：语法错直接回行列定位片段
        val sourceObj = runCatching { JsonParser.parseString(request.source).asJsonObject }.getOrNull()
            ?: error("source 不是合法 JSON 对象")
        val sourceType = runCatching {
            sourceObj.get("bookSourceType")?.takeIf { it.isJsonPrimitive }?.asInt
        }.getOrNull() ?: 0
        val externalJsLib = runCatching {
            sourceObj.get("jsLib")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                ?.let { it.startsWith("http://", true) || it.startsWith("https://", true) } == true
        }.getOrDefault(false)

        val checks = linkedMapOf<String, String>()
        request.searchKey?.takeIf { it.isNotBlank() }?.let { checks["搜索"] = it }
        request.detailUrl?.takeIf { it.isNotBlank() }?.let { checks["详情"] = it }
        request.tocUrl?.takeIf { it.isNotBlank() }?.let { checks["目录"] = "++$it" }
        request.contentUrl?.takeIf { it.isNotBlank() }?.let { checks["正文"] = "--$it" }
        // 发现阶段：入口 = 分类名::URL（对应 debug_source 的「分类::URL」发现链路）。
        // exploreUrl 为主键；exploreKey 仅作分类名前缀（可空 → "::URL"）。
        request.exploreUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val cat = request.exploreKey.orEmpty()
            checks["发现"] = "$cat::$url"
        }

        val warnings = mutableListOf<String>()
        // 真机严格模式：&&/||/%% 分支内的模式前缀真机不识别，提前告警
        warnings += BookSourceRuleSanitizer.strictModeWarnings(request.source)
        // UA 语义与真机一致（书源 header 显式 User-Agent 覆盖默认），但默认值不同：真机用 App 内 UA
        val declaresUa = runCatching {
            val header = sourceObj.get("header") ?: return@runCatching false
            val obj = when {
                header.isJsonObject -> header.asJsonObject
                header.isJsonPrimitive -> JsonParser.parseString(header.asString).asJsonObject
                else -> null
            }
            obj?.keySet()?.any { it.equals("User-Agent", true) } == true
        }.getOrDefault(false)
        if (!declaresUa) {
            warnings += "书源未声明 User-Agent：阅读端使用自身默认 UA，沙箱 UA 可能不同；保留原写法，属验收环境限制"
        }
        val hasSearchUrl = runCatching {
            sourceObj.get("searchUrl")?.takeIf { it.isJsonPrimitive }?.asString?.isNotBlank()
        }.getOrNull() == true
        if ("搜索" !in checks && hasSearchUrl) {
            val probe = probeKeyword(sourceObj)
            checks["搜索"] = probe
            warnings += "未传 searchKey：已用关键词「$probe」自动探测搜索链路；若无结果，请核对搜索地址与 bookList 规则，或用站内真实存在的书名重测"
        }

        val timingsMs = linkedMapOf<String, Long>()
        val totalStart = System.nanoTime()
        val results = linkedMapOf<String, Any>()
        results["validation"] = timed("validation", timingsMs) { runtime.validate(request.source) }

        suspend fun runStage(name: String, entry: String) {
            // CancellationException 必须上浮（整个 check_source 被取消时不吞、不转成 error Map）
            results[name] = try {
                timed(name, timingsMs) { runtime.debug(request.source, entry, cacheFetch, origin) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                // 只吞 Exception：OOM/StackOverflow 等 fatal Error 不能伪装成「阶段失败」继续产出误导性 verdict
                val message = t.message.orEmpty()
                val externalJsLibFailure = externalJsLib && !message.contains("【规则错误】") &&
                    (message.contains("is not defined") || message.contains("未定义"))
                val category = if (externalJsLibFailure) "sandbox"
                    else SourceCheckEvidence.categorizeMessage(message, t)
                val sandboxReason = if (externalJsLibFailure) "external_jslib_skipped"
                    else SourceCheckEvidence.sandboxReason(message, t)
                buildMap<String, Any> {
                    put("error", message)
                    put("category", category)
                    if (sandboxReason != null) put("sandboxReason", sandboxReason)
                    when (t) {
                        is com.mina.legadostudio.verification.VerificationRequiredException -> {
                            put("verificationUrl", t.verificationUrl)
                            put("domain", t.domain)
                        }
                        is com.mina.legadostudio.network.JsChallengeException -> {
                            put("challengeMarker", t.challengeMarker)
                        }
                    }
                }
            }
        }

        // 先跑用户显式传入/自动探测出的阶段
        checks.forEach { (name, entry) -> runStage(name, entry) }

        // auto：按链路推进补齐缺失阶段（搜索→详情→目录→正文）。入口只从「已通过」阶段的
        // DebugReport.data 取——失败报告里的字段是残缺的，不能拿来推进。
        if (request.auto) {
            autoChain(results, warnings, sourceType, ::runStage)
        }

        if (warnings.isNotEmpty()) results["warnings"] = warnings
        results["cache"] = if (cacheFetch == null) "实时请求" else "本任务快照（最终验收请传 refresh=true）"
        timingsMs["total"] = (System.nanoTime() - totalStart) / 1_000_000
        results["timingsMs"] = timingsMs
        val evidence = collectEvidence(results, sourceType)
        results["evidence"] = evidence
        results["verdict"] = verdict(results, sourceType)
        val bookSourceUrl = sourceObj.get("bookSourceUrl")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        RecentCheckOutcomes.record(
            bookSourceUrl,
            hadFailure = evidence.values.any { it.ran && !it.ok && it.category !in NON_RULE_CATEGORIES },
        )
        return results
    }

    /**
     * 链路推进：只从「判定为 ok」的 DebugReport 取下一跳入口。
     * 搜索取首个可用项（name+bookUrl 均非空）的 bookUrl，不是列表首项——首项为空、后项有效时仍能推进。
     * 详情取 tocUrl（源未配置 tocUrl 规则时 runtime 可能已回填详情页 URL）、
     * 目录取第一个可读章节 url（跳过 isVolume 卷名空 URL，不把非卷名空链接当卷名吞掉）；
     * 失败的 DebugReport（error Map 或 !ok）一律不拿来推进。
     */
    private suspend fun autoChain(
        results: MutableMap<String, Any>,
        warnings: MutableList<String>,
        sourceType: Int,
        runStage: suspend (String, String) -> Unit,
    ) {
        fun okReport(name: String): LegadoRuntime.DebugReport? {
            val r = results[name] as? LegadoRuntime.DebugReport ?: return null
            return r.takeIf { SourceCheckEvidence.of(name, it, sourceType).ok }
        }

        // 详情：搜索成功后取首个可用项 bookUrl；若用户已传 detailUrl 则跳过
        if ("详情" !in results) {
            val bookUrl = (okReport("搜索")?.data as? List<*>)?.let { list ->
                list.firstNotNullOfOrNull { item ->
                    val m = item as? Map<*, *> ?: return@firstNotNullOfOrNull null
                    val name = m["name"]?.toString().orEmpty().trim()
                    val url = m["bookUrl"]?.toString().orEmpty().trim()
                    if (name.isNotBlank() && url.isNotBlank()) url else null
                }
            }
            if (bookUrl != null) {
                runStage("详情", bookUrl)
            } else if ("搜索" in results) {
                warnings += "auto：搜索无可用项（name+bookUrl 均非空）或搜索未通过，跳过详情/目录/正文推进；可传 detailUrl / tocUrl / contentUrl 单独验证详情/目录/正文"
            }
        }
        // 目录：详情成功后取 tocUrl；若用户已传 tocUrl 则跳过
        if ("目录" !in results) {
            val tocUrl = (okReport("详情")?.data as? Map<*, *>)?.get("tocUrl")?.toString()
                ?.takeIf { it.isNotBlank() }
            if (tocUrl != null) {
                runStage("目录", "++$tocUrl")
            } else if ("详情" in results) {
                warnings += "auto：详情未通过或无 tocUrl，跳过目录/正文推进"
            }
        }
        // 正文：目录成功后取第一个可读章节 url；若用户已传 contentUrl 则跳过
        if ("正文" !in results) {
            val chapterUrl = ((okReport("目录")?.data as? Map<*, *>)?.get("chapters") as? List<*>)
                ?.let { SourceCheckEvidence.firstUsableChapterUrl(it) }
            if (chapterUrl != null) {
                runStage("正文", "--$chapterUrl")
            } else if ("目录" in results) {
                warnings += "auto：目录未通过或无可读章节 url，跳过正文推进"
            }
        }
    }

    /** 参与 evidence/verdict 的核心四段（发现是可选附加段，不计入 full 判定）。 */
    private val CORE_STAGES = listOf("搜索", "详情", "目录", "正文")
    /** evidence 展示的完整阶段序（核心四段 + 可选发现）。 */
    private val ALL_STAGES = CORE_STAGES + "发现"

    /** 汇总各阶段证据（不含 validation/warnings/cache/timingsMs 等非阶段键）。 */
    private fun collectEvidence(results: Map<String, Any>, sourceType: Int): Map<String, SourceCheckEvidence.StageEvidence> {
        val evidence = linkedMapOf<String, SourceCheckEvidence.StageEvidence>()
        for (stage in ALL_STAGES) {
            results[stage]?.let { evidence[stage] = SourceCheckEvidence.of(stage, it, sourceType) }
        }
        return evidence
    }

    /**
     * 总结论：full = 静态校验通过且搜索/详情/目录/正文四段全部 ok；partial = 有可跑阶段且至少一个 ok；
     * unusable = 跑了但全部失败；inconclusive = 全部失败均为沙箱能力限制、站点限流或正文样本不可用，不代表规则错误；
     * unverified = 没有任何可判定阶段（例如只跑了 validation）。
     * 结论只看阶段证据+validation，不看 HTTP code——200 空列表 / 200 挑战页都不算可用；
     * validation.isValid=false 时即使四段全 ok 也只给 partial（full 必须是「校验+链路」双重通过）。
     * 目录达到沙箱 100 页上限时阶段仍可 ok（规则有章节输出），但采集不完整，不能给 full。
     */
    private fun verdict(results: Map<String, Any>, sourceType: Int): Map<String, Any> {
        val evidence = linkedMapOf<String, SourceCheckEvidence.StageEvidence>()
        for (stage in CORE_STAGES) {
            results[stage]?.let { evidence[stage] = SourceCheckEvidence.of(stage, it, sourceType) }
        }
        val ran = evidence.values.count { it.ran }
        val ok = evidence.values.count { it.ran && it.ok }
        val failed = evidence.values.filter { it.ran && !it.ok }
        val validation = results["validation"] as? BookSourceValidator.Report
        val validationOk = validation?.isValid == true
        val tocCapped = evidence["目录"]?.let { tocPageLimit(it) } == true
        val summary = when {
            evidence.isEmpty() -> "unverified"
            ran == 0 -> "unverified"
            // 非文本源（音频/图片/文件/视频）：正文链路无法用文字字数证明真实可播/可显示，
            // 四段全 ok 也只标 partial——「full」保留给「校验+全文字链路」双重通过的文本源。
            ok == 4 && validationOk && sourceType > 0 -> "partial"
            // 目录 100 页封顶：规则有输出但不证明全书目录完整，不能给 full。
            ok == 4 && validationOk && tocCapped -> "partial"
            ok == 4 && validationOk -> "full"
            ok == 4 -> "partial"   // 四段均有样本证据但静态校验有 issue：不能算「全可用」
            ok > 0 -> "partial"
            failed.isNotEmpty() && failed.all { it.category in NON_RULE_CATEGORIES } -> "inconclusive"
            else -> "unusable"
        }
        return buildMap {
            put("summary", summary)
            put("stagesRan", ran)
            put("stagesOk", ok)
            put("stagesFailed", failed.map { it.stage })
            put("validationOk", validationOk)
            put("validationIssues", validation?.issues?.size ?: -1)
            failureKind(failed)?.let { put("failureKind", it) }
            put("note", when {
                summary == "full" -> "仅沙箱静态校验及四阶段样本检查通过，不证明阅读端可用；请以当前书源版本在阅读端的调试与页面实际输出为准。沙箱不是保存草稿或进入阅读调试的前置条件；外部 checkResults 仅供参考"
                summary == "partial" && tocCapped && ok == 4 && validationOk ->
                    "目录分页达到沙箱上限，采集不完整，不能声称全链路完整；属验收环境限制，不是书源规则失败。请以阅读端 ++目录 为准"
                summary == "partial" && ok == 4 && sourceType > 0 && validationOk ->
                    "非文本源（type=$sourceType）四段均有记录，但音视频/图片/文件正文无文字级可用性证据，封顶 partial"
                summary == "partial" && ok == 4 -> "四段均有样本证据，但静态校验未通过（${validation?.issues?.size ?: 0} 个 issue），不能声称全链路可用"
                summary == "partial" -> "仅部分阶段可用；未跑齐的阶段不可声称全链路可用"
                summary == "inconclusive" && failed.any { it.category == "rate_limited" } ->
                    "站点返回限流提示（如搜索间隔），不是规则问题；按站点提示等待后重测，无需修改规则"
                summary == "inconclusive" && failed.any { it.sandboxReason == "webjs_timeout" || it.reason?.contains("js执行超时") == true } ->
                    SourceCheckEvidence.WEBJS_TIMEOUT_REASON
                summary == "inconclusive" && failed.any { it.category == "content_unavailable" } ->
                    "正文样本是转码/解码失败提示或乱码占位，无法确认内容可用；核对原页和阅读端，不据此直接修改规则"
                summary == "inconclusive" -> "沙箱能力限制，不代表规则错误；请用 reader_web_debug 在阅读端取证后再下结论"
                summary == "unusable" -> "已跑阶段全部失败"
                else -> "没有可判定的阶段证据"
            })
        }
    }

    /** 目录阶段因沙箱页数上限截断：有章节样本，但不完整。 */
    private fun tocPageLimit(ev: SourceCheckEvidence.StageEvidence): Boolean {
        if (ev.sandboxReason == "page_limit") return true
        if (ev.facts["tocEndReason"]?.toString() == "page_limit") return true
        val truncated = ev.facts["tocTruncated"]
        return truncated == true || truncated?.toString().equals("true", ignoreCase = true) == true
    }

    private val NON_RULE_CATEGORIES = setOf("sandbox", "rate_limited", "content_unavailable")

    /** 阅读校验书源的默认关键词（官方 CheckSource.keyword 默认值）。 */
    const val DEFAULT_PROBE_KEYWORD = "我的"

    /**
     * 未传 searchKey 时的自动探测词：与阅读校验一致优先取 ruleSearch.checkKeyWord，
     * 但单字太泛（站点常拒绝或返回空），去空白后不足 2 字时改用官方默认「我的」。
     */
    fun probeKeyword(sourceObj: com.google.gson.JsonObject): String {
        val check = runCatching {
            sourceObj.getAsJsonObject("ruleSearch")?.get("checkKeyWord")
                ?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull().orEmpty().trim()
        return check.takeIf { it.length >= 2 } ?: DEFAULT_PROBE_KEYWORD
    }

    /** 失败性质：rule / sandbox / site / content / network / verification；多种并存为 mixed，无失败为 null。 */
    private fun failureKind(failed: List<SourceCheckEvidence.StageEvidence>): String? {
        val kinds = failed.map {
            when (it.category) {
                "sandbox" -> "sandbox"
                "rate_limited" -> "site"
                "content_unavailable" -> "content"
                "network" -> "network"
                "verification", "captcha" -> "verification"
                else -> "rule"
            }
        }.toSet()
        return when (kinds.size) {
            0 -> null
            1 -> kinds.first()
            else -> "mixed"
        }
    }

    private suspend fun <T> timed(stage: String, timingsMs: MutableMap<String, Long>, block: suspend () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            timingsMs[stage] = (System.nanoTime() - start) / 1_000_000
        }
    }
}
