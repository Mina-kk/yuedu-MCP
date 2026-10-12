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
 *   详情 name+tocUrl、目录 totalChapters>0 且首项 url、正文非拦截页且有字符/媒体证据）。
 * - `auto=true` 时做有限链路推进：从**已通过**阶段的 DebugReport 取推进入口
 *   （搜索首个可用项 bookUrl → 详情 tocUrl → 目录首项 chapter url → 正文）。
 *   只补用户没显式传入口的阶段，不覆盖显式参数；失败阶段绝不拿来推进。
 * - 阶段 error 分 verification / captcha / network / config / unknown 类别。
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
         * 目录成功后取首项 chapter url 跑正文。用户显式传入的入口优先，auto 只补缺。
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
            warnings += "书源未在 header 中显式声明 User-Agent：沙箱请求使用工具默认 UA（${HttpFetcher.DEFAULT_UA.take(40)}…），真机 Legado 使用其 App 默认 UA——两者不同，遇 UA 校验/WAF 站点可能表现不一致；要完全一致请在书源 header 显式写上目标 UA"
        }
        val hasSearchUrl = runCatching {
            sourceObj.get("searchUrl")?.takeIf { it.isJsonPrimitive }?.asString?.isNotBlank()
        }.getOrNull() == true
        if ("搜索" !in checks && hasSearchUrl) {
            checks["搜索"] = "我"
            warnings += "未传 searchKey：已用关键词「我」自动探测搜索链路；若「搜索」报告显示「列表 0 条」而站点实际有结果，说明 ruleSearch 选择器与搜索结果页结构不匹配（常见错误：直接套用发现/列表页选择器），请 fetch_page 搜索页实测后修正规则并显式传 searchKey 复验"
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
                val category = SourceCheckEvidence.categorizeMessage(t.message.orEmpty(), t)
                buildMap<String, Any> {
                    put("error", t.message.orEmpty())
                    put("category", category)
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
        results["evidence"] = collectEvidence(results, sourceType)
        results["verdict"] = verdict(results, sourceType)
        return results
    }

    /**
     * 链路推进：只从「判定为 ok」的 DebugReport 取下一跳入口。
     * 搜索取首个可用项（name+bookUrl 均非空）的 bookUrl，不是列表首项——首项为空、后项有效时仍能推进。
     * 详情取 tocUrl、目录取首项 chapter url；失败的 DebugReport（error Map 或 !ok）一律不拿来推进。
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
                warnings += "auto：搜索无可用项（name+bookUrl 均非空）或搜索未通过，跳过详情/目录/正文推进"
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
        // 正文：目录成功后取首项 chapter url；若用户已传 contentUrl 则跳过
        if ("正文" !in results) {
            val chapterUrl = ((okReport("目录")?.data as? Map<*, *>)?.get("chapters") as? List<*>)
                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("url") }?.toString()
                ?.takeIf { it.isNotBlank() }
            if (chapterUrl != null) {
                runStage("正文", "--$chapterUrl")
            } else if ("目录" in results) {
                warnings += "auto：目录未通过或首项无 url，跳过正文推进"
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
     * unusable = 跑了但全部失败；unverified = 没有任何可判定阶段（例如只跑了 validation）。
     * 结论只看阶段证据+validation，不看 HTTP code——200 空列表 / 200 挑战页都不算可用；
     * validation.isValid=false 时即使四段全 ok 也只给 partial（full 必须是「校验+链路」双重通过）。
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
        val summary = when {
            evidence.isEmpty() -> "unverified"
            ran == 0 -> "unverified"
            // 非文本源（音频/图片/文件/视频）：正文链路无法用文字字数证明真实可播/可显示，
            // 四段全 ok 也只标 partial——「full」保留给「校验+全文字链路」双重通过的文本源。
            ok == 4 && validationOk && sourceType > 0 -> "partial"
            ok == 4 && validationOk -> "full"
            ok == 4 -> "partial"   // 四段全通但静态校验有 issue：不能算「全可用」
            ok > 0 -> "partial"
            else -> "unusable"
        }
        return buildMap {
            put("summary", summary)
            put("stagesRan", ran)
            put("stagesOk", ok)
            put("stagesFailed", failed.map { it.stage })
            put("validationOk", validationOk)
            put("validationIssues", validation?.issues?.size ?: -1)
            put("note", when {
                summary == "full" -> "静态校验通过且搜索/详情/目录/正文全部有可用证据（外部传入的 checkResults 属未核验来源，仅供参考）"
                summary == "partial" && ok == 4 && sourceType > 0 && validationOk ->
                    "非文本源（type=$sourceType）四段均有记录，但音视频/图片/文件正文无文字级可用性证据，封顶 partial"
                summary == "partial" && ok == 4 -> "四段全通但静态校验未通过（${validation?.issues?.size ?: 0} 个 issue），不能声称全链路可用"
                summary == "partial" -> "仅部分阶段可用；未跑齐的阶段不可声称全链路可用"
                summary == "unusable" -> "已跑阶段全部失败"
                else -> "没有可判定的阶段证据"
            })
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
