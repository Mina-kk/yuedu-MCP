package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 书源过滤纯函数集合：按用户可选的排除维度（login/captcha/失败/未验证）筛掉书源，
 * 每条被排除的源给一句理由。全部是无 IO 的纯函数，结果 Map/List 直接可序列化进 MCP 响应。
 *
 * 判定基于「字段证据 + check_source 结果」两条线索：
 * - 字段线索（loginUrl/loginUi；loginUrl 指向验证页时另列验证码理由）用于可选排除；
 * - check_source 的阶段结果说明「实际跑时撞上了验证/挑战/网络失败/规则未命中」。
 * 两条线索各自给理由，不互相覆盖。
 *
 * checkResults 的可信度边界（调用方传入的 JSON，来源不可核验——这不是密码学信任）：
 * - 完整 check_source roundtrip（带自洽的 verdict+evidence+validation 摘要）：按摘要逐阶段核对，
 *   核对通过的阶段证据被采纳（serialized StageEvidence 或 DebugReport Map 均可）；
 * - 只有裸阶段值（无摘要）：仅采纳「序列化 DebugReport Map（含 data/lines）与 {error} Map」
 *   这两类可独立复算的结构——内部数据说话，不采信任何「声称 ok」的自述字段；
 * - 既无摘要又不可识别的值（如手造 {"搜索":{}}）：ran=false 的 unrecognized，不作成功也不作失败。
 */
object SourceFilterPolicy {

    /** 过滤维度；对应 MCP 参数的布尔开关。 */
    enum class Exclude(val arg: String, val label: String) {
        LOGIN("excludeLogin", "需要登录"),
        CAPTCHA("excludeCaptcha", "需要验证码/JS 挑战"),
        FAILED("excludeFailed", "check_source 阶段失败"),
        UNVERIFIED("excludeUnverified", "check_source 未跑齐关键阶段"),
    }

    /** 单个书源的过滤结果。source 原始 JSON 原样带回（调用方要导出/展示用）。 */
    data class Decision(
        val index: Int,
        val keep: Boolean,
        val reasons: List<String>,
        val source: JsonObject,
        val evidence: Map<String, SourceCheckEvidence.StageEvidence>? = null,
        /** true = checkResults 形如 check_source 返回且摘要自洽核对通过（仍属未核验的外部输入）。 */
        val checkVerified: Boolean = false,
    )

    data class Report(
        val total: Int,
        val kept: List<Decision>,
        val excluded: List<Decision>,
    ) {
        val keptCount: Int get() = kept.size
        val excludedCount: Int get() = excluded.size
    }

    /**
     * @param sources       书源 JSON 字符串列表（每项是完整书源对象）
     * @param checkResults  与 sources 同 index 对应的 check_source 结果；每项可以是
     *                      进程内的 Map（CheckSourceRunner.run 返回）或 Gson roundtrip 后的 JsonObject/Map。
     *                      传 null 表示「还没跑过 check」，对应 UNVERIFIED 维度。
     *                      注意：这是调用方提供的输入，本函数只做结构/一致性核对，不核验来源真实性。
     * @param excludes      启用哪些排除维度；默认空集 = 不排除任何源（全保留）。
     */
    fun filter(
        sources: List<String>,
        checkResults: Map<Int, *>? = null,
        excludes: Set<Exclude> = emptySet(),
    ): Report {
        val kept = mutableListOf<Decision>()
        val excluded = mutableListOf<Decision>()
        sources.forEachIndexed { index, json ->
            val source = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            if (source == null) {
                // 整条 JSON 无法解析 = 必然不可用；无论开哪个维度都排掉，理由写明
                excluded += Decision(index, keep = false, reasons = listOf("书源 JSON 无法解析"), source = JsonObject())
                return@forEachIndexed
            }
            val check = checkResults?.get(index)?.let { anyToMap(it) }
            val reasons = mutableListOf<String>()
            val sourceType = runCatching {
                source.get("bookSourceType")?.takeIf { it.isJsonPrimitive }?.asInt
            }.getOrNull() ?: 0
            // 证据装配：先判断 check 是否带「可自洽核对的 check_source 摘要」。
            // 带摘要 → 严格逐阶段核对（serialized evidence 与阶段结果互证，防手造 {ok:true}）；
            // 无摘要 → 只采纳能独立复算的 DebugReport Map / {error} Map，自述性字段一律不采信。
            val summaryCoherent = check?.let { checkSummaryCoherent(it, sourceType) } == true
            val declaredEvidence = if (summaryCoherent) check?.get("evidence") as? Map<*, *> else null
            val evidence = mutableMapOf<String, SourceCheckEvidence.StageEvidence>()
            if (check != null) {
                for (stage in listOf("搜索", "详情", "目录", "正文")) {
                    val declared = (declaredEvidence?.get(stage) as? Map<*, *>)
                        ?.let { evMap -> stageEvidenceFromMap(stage, evMap) }
                    evidence[stage] = if (declared != null) {
                        check[stage]?.let { raw ->
                            // 摘要与阶段值互证：两边都要有可复算结构且结论一致才采信；
                            // 摘要声称 ran 但数据复算 !ran / 或摘要声称未跑但数据实际已跑，都以数据为准
                            val derived = SourceCheckEvidence.of(stage, raw, sourceType)
                            when {
                                derived.ran && derived.ok == declared.ok -> declared
                                derived.ran && declared.ran -> derived   // ran 一致但 ok 打架：按数据
                                derived.ran && !declared.ran -> derived  // 摘要漏标 ran，以数据为准
                                else -> declared                          // 数据无法复算：采信摘要（它已过自洽核对）
                            }
                        } ?: declared   // 摘要自洽时，evidence 摘要本身就是阶段记录（阶段原始值可缺省）
                    } else {
                        check[stage]?.let { SourceCheckEvidence.of(stage, it, sourceType) }
                            ?: continue   // 阶段键缺失：不补占位，保持「未跑」语义
                    }
                }
            }

            if (Exclude.LOGIN in excludes) {
                loginReasons(source)?.let(reasons::addAll)
            }
            if (Exclude.CAPTCHA in excludes) {
                captchaReasons(source, evidence)?.let(reasons::addAll)
            }
            if (Exclude.FAILED in excludes) {
                failedReasons(evidence)?.let(reasons::addAll)
            }
            if (Exclude.UNVERIFIED in excludes) {
                unverifiedReasons(check, evidence, sourceType)?.let(reasons::addAll)
            }

            val decision = Decision(index, keep = reasons.isEmpty(), reasons = reasons, source = source,
                evidence = evidence.takeIf { it.isNotEmpty() }, checkVerified = summaryCoherent)
            if (decision.keep) kept += decision else excluded += decision
        }
        return Report(total = sources.size, kept = kept, excluded = excluded)
    }

    /**
     * check_source 摘要自洽性核对（verdict + evidence + validation 三件套互证）：
     * 这是「该 Map 确实形如一次 check_source 调用输出」的结构性校验，不是来源真实性证明——
     * 调用方仍可伪造，但伪造者必须让整个摘要自洽（阶段值也要能被独立复算出同样结论）。
     */
    internal fun checkSummaryCoherent(check: Map<String, Any>, sourceType: Int): Boolean {
        val verdict = check["verdict"] as? Map<*, *> ?: return false
        val summary = verdict["summary"]?.toString()
            ?.takeIf { it in setOf("full", "partial", "unusable", "unverified") } ?: return false
        val declaredEv = check["evidence"] as? Map<*, *> ?: return false
        val stages = listOf("搜索", "详情", "目录", "正文")

        // 逐阶段核对：摘要里声明 ran/ok 的阶段，必须在 verdict 计数或阶段原始值中得到印证
        var declaredRan = 0
        var declaredOk = 0
        for (stage in stages) {
            val evMap = declaredEv[stage] as? Map<*, *> ?: continue
            if (!SourceCheckEvidence.looksLikeStageEvidenceMap(evMap)) return false
            val ev = stageEvidenceFromMap(stage, evMap) ?: return false
            if (ev.ran) declaredRan++
            if (ev.ran && ev.ok) declaredOk++
            // 阶段原始值存在时，独立复算的结论必须与摘要一致（否则摘要与数据打架，整体不采信）
            check[stage]?.let { raw ->
                val derived = SourceCheckEvidence.of(stage, raw, sourceType)
                if (derived.ran && derived.ok != ev.ok) return false
                if (!derived.ran && ev.ran && SourceCheckEvidence.looksLikeDebugReportMap(raw as? Map<*, *> ?: emptyMap<String, Any>())) return false
            }
        }
        val vRan = (verdict["stagesRan"] as? Number)?.toInt()
        val vOk = (verdict["stagesOk"] as? Number)?.toInt()
        if (vRan != null && vRan != declaredRan) return false
        if (vOk != null && vOk != declaredOk) return false
        // validation 字段存在时必须是 Report 形（issues 列表 + isValid 与之一致）
        when (val validation = check["validation"]) {
            null -> Unit
            is BookSourceValidator.Report -> Unit
            is Map<*, *> -> {
                val issues = validation["issues"] as? List<*> ?: return false
                val declaredValid = validation["isValid"] as? Boolean
                if (declaredValid != null && declaredValid != issues.isEmpty()) return false
            }
            else -> return false
        }
        // verdict.summary 与证据分布必须自洽
        return when (summary) {
            "full" -> declaredOk == 4
            "unusable" -> declaredRan > 0 && declaredOk == 0
            "unverified" -> declaredRan == 0
            else -> declaredOk in 1..3 || (declaredOk == 4 && declaredRan >= 4)
        }
    }

    /** serialized StageEvidence（{stage,ran,ok,reason,category,facts} 形 Map）→ StageEvidence；形状不符返回 null。 */
    private fun stageEvidenceFromMap(stage: String, map: Map<*, *>): SourceCheckEvidence.StageEvidence? {
        if (!SourceCheckEvidence.looksLikeStageEvidenceMap(map)) return null
        val ran = map["ran"] as Boolean
        val ok = map["ok"] as Boolean
        val facts = (map["facts"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
        return SourceCheckEvidence.StageEvidence(
            stage = map["stage"]?.toString() ?: stage,
            ran = ran, ok = ok,
            reason = map["reason"]?.toString(),
            category = map["category"]?.toString(),
            facts = facts,
        )
    }

    /** 需要登录的判定：loginUrl 非空 或 loginUi 非空。loginCheckJs 不单独算（它是检测不是登录表单）。 */
    private fun loginReasons(source: JsonObject): List<String>? {
        val reasons = mutableListOf<String>()
        source.text("loginUrl")?.takeIf { it.isNotBlank() }?.let { reasons += "loginUrl 已配置（${it.take(60)}）：需要先在 App 登录页完成登录" }
        source.text("loginUi")?.takeIf { it.isNotBlank() }?.let { reasons += "loginUi 已配置：该源要求用户在登录界面输入凭据" }
        // 注意：header.Cookie/Authorization 是凭据不是登录 UI，不触发 LOGIN 排除（用户已显式提供）
        return reasons.takeIf { it.isNotEmpty() }
    }

    /** 需要验证码/挑战的判定：check_source 跑阶段时抛了 captcha/verification 类错误。单字段含「验证码」字样不算。 */
    private fun captchaReasons(source: JsonObject, evidence: Map<String, SourceCheckEvidence.StageEvidence>): List<String>? {
        val reasons = mutableListOf<String>()
        for ((stage, ev) in evidence) {
            when (ev.category) {
                "verification" -> reasons += "$stage 阶段要求人机验证（${ev.reason?.take(80)}）：需要先过验证才能用"
                "captcha" -> reasons += "$stage 阶段命中 JS 人机挑战（${ev.reason?.take(80)}）：需要先在 WebView 过挑战"
            }
        }
        // loginUrl/loginUi 已配置且指向验证码路径时，补充说明（不重复 LOGIN 的理由）
        val loginUrl = source.text("loginUrl").orEmpty()
        if (loginUrl.contains("captcha", true) || loginUrl.contains("verify", true)) {
            reasons += "loginUrl 指向验证/验证码页面（${loginUrl.take(60)}）"
        }
        return reasons.takeIf { it.isNotEmpty() }
    }

    /** check_source 阶段失败（不含「未跑/无法识别」）：只有已跑且 !ok 的阶段才算失败。 */
    private fun failedReasons(evidence: Map<String, SourceCheckEvidence.StageEvidence>): List<String>? {
        val reasons = mutableListOf<String>()
        for ((stage, ev) in evidence) {
            if (ev.ran && !ev.ok) reasons += "$stage 阶段失败：${ev.reason ?: ev.category ?: "未知"}"
        }
        return reasons.takeIf { it.isNotEmpty() }
    }

    /**
     * 未验证：checkResults 完全缺失（check == null，还没跑过 check_source）
     * 或跑了但关键阶段没跑齐/无法证实跑过。
     *
     * 「任一阶段 ran 就算已验证」是高危假阳性——只跑了搜索、目录正文根本没跑时，
     * 不能对调用方宣称「已验证」。关键阶段集合按书源类型取：
     * - 文本源（0/-1）：搜索+详情+目录+正文四段都必须有可判定证据；
     * - 非文本源（1..4 音频/图片/文件/视频）：搜索+详情+目录必须有可判定证据，正文不强制
     *   （媒体正文常以资源 URL/二进制形式存在，文字字数不能当判据）。
     * 手造的 { "搜索":{} } 这类无法识别的结构不算「跑过」。
     */
    private fun unverifiedReasons(check: Map<String, Any>?, evidence: Map<String, SourceCheckEvidence.StageEvidence>, sourceType: Int = 0): List<String>? {
        if (check == null) return listOf("未运行 check_source：可用性未验证")
        // 跑了但一个可判定阶段都没有 → 也按未验证处理
        if (evidence.isEmpty()) return listOf("check_source 返回中没有任何阶段结果")
        // 有证据但全部未跑/无法识别 → 未验证
        val anyRan = evidence.values.any { it.ran }
        if (!anyRan) return listOf("check_source 未实际执行任何阶段（或阶段结果无法识别）")
        // 关键阶段必须全部有已跑证据；缺一即未验证
        val required = if (sourceType > 0) listOf("搜索", "详情", "目录") else listOf("搜索", "详情", "目录", "正文")
        val missing = required.filter { evidence[it]?.ran != true }
        if (missing.isNotEmpty()) {
            return listOf("check_source 未跑齐关键阶段：缺少 ${missing.joinToString("、")}（已确认 ${evidence.values.count { it.ran }} 段，需要 ${required.size} 段）")
        }
        return null
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString

    /** checkResults 单项归一化：JsonObject → Map（Gson roundtrip），已是 Map / 其他类型原样或置 null。 */
    private fun anyToMap(value: Any?): Map<String, Any>? = when (value) {
        null -> null
        is Map<*, *> -> @Suppress("UNCHECKED_CAST") (value as? Map<String, Any>)
            ?: value.entries.associate { it.key.toString() to (it.value ?: Unit) }
        is JsonObject -> @Suppress("UNCHECKED_CAST")
            (Gson().fromJson(value, Map::class.java) as? Map<String, Any>)
        else -> null
    }
}
