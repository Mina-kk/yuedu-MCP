package com.mina.legadostudio.reader

import com.mina.legadostudio.domain.SourceCheckEvidence

/**
 * 从阅读端调试日志里提取阶段与失败信号。只做标注，不产生“通过”结论：
 * 旧版调试通道本身不返回机器可读的成败，任何“一致/通过”说法都没有依据。
 */
object ReaderDebugLogSignals {
    data class Signals(
        val stagesReached: List<String>,
        val failureSignals: List<String>,
        val listSizeObserved: Int? = null,
    )

    val FORBIDDEN_CLAIMS = listOf("通过", "一致", "验证成功")

    private val STAGES = listOf(
        "search" to listOf("开始搜索关键字", "开始解析搜索页", "获取书籍列表", "搜索页解析完成"),
        "detail" to listOf("开始解析详情页", "获取详情页", "详情页解析完成"),
        "toc" to listOf("开始解析目录页", "目录页解析完成"),
        "content" to listOf("开始解析正文页", "正文页解析完成"),
    )

    private val FAILURES = listOf(
        "未获取到书籍", "搜索URL为空", "没有正文章节", "列表页解析成功，为空", "列表大小:0",
        "书籍总数:0", "书源不存在", "调试通道占用中", "cannot_empty", "Exception",
        "js执行超时", "未定义", "不是函数",
    )

    private val FAILURES_CI = listOf(
        "Uncaught", "ReferenceError", "TypeError",
        "Cloudflare", "Just a moment", "cf-mitigated",
    )

    /** 必须带 HTTP / 状态码 / error code 语义；裸数字 403/520 不算。 */
    private val HTTP_SITE_FAILURE = Regex(
        """(?i)(?:HTTP(?:/[0-9.]+)?\s*(?:403|520)\b|(?:status(?:\s*code)?|状态码)\s*[:：]?\s*(?:403|520)\b|error\s*code:\s*(?:403|520)\b)""",
    )

    private val LIST_SIZE = Regex("""(?:列表大小|书籍总数):(\d+)""")

    const val WEBJS_TIMEOUT_HINT =
        "webJs 返回 null 会被轮询到超时；搜索 0 结果或页面脚本异常也会表现为超时，不等于规则错误，也不等于 0 条；建议 webJs 在列表已渲染但无条目时返回空容器 HTML"

    fun parse(logs: List<String>): Signals {
        val stages = STAGES.filter { (_, marks) -> logs.any { line -> marks.any { line.contains(it) } } }.map { it.first }
        val failures = LinkedHashSet<String>()
        for (line in logs) {
            if (isFailureLine(line)) failures += line.trim().take(160)
            if (failures.size >= 12) break
        }
        return Signals(stages, failures.toList(), listSizeObserved(logs))
    }

    fun summaryForUser(signals: Signals): String {
        val base = when {
            signals.failureSignals.isNotEmpty() ->
                "阅读端调试出现失败信号：" + signals.failureSignals.take(3).joinToString("；") +
                    "。先核对阅读端响应及站点限流、网络和规则，不能以沙箱结果宣称可用"
            signals.stagesReached.isEmpty() -> "日志没有识别到搜索或解析阶段；不能宣称可用"
            else -> "阅读端日志显示进入阶段（不代表完成）：" + signals.stagesReached.joinToString("/") +
                "；旧版调试未给出通过结论，请如实转述关键日志行"
        }
        val n = signals.listSizeObserved ?: return base
        return base + "；观测到列表大小 $n"
    }

    /**
     * 机器可读的日志结论：只陈述日志事实（到达了哪些阶段、有没有失败行），不是「通过」判定。
     * all_stages_reached_no_failure = 搜索/详情/目录/正文四段都出现阶段日志且无失败行；
     * failure_signals = 出现失败行；incomplete = 无失败行但没走完四段。
     *
     * failureCategories 在调用 [SourceCheckEvidence.categorizeMessage] 前先走阅读端专用分类：
     * `js执行超时`→`webjs_timeout`；`Uncaught …`→`page_script_error`；
     * `ReferenceError`/`未定义`/`TypeError`/`不是函数`→`rule_js_error`；
     * Cloudflare/`Just a moment`/`cf-mitigated`→`verification`；HTTP 403/520→`site_blocked`。
     * 其余回落旧逻辑（sandbox 仍映射为 unknown）。
     *
     * 出现 `js执行超时` 时附加 `webJsTimeoutHint`：webJs 返回 null 会被轮询到超时，
     * 搜索 0 结果或页面脚本异常也会表现为超时，不等于规则错误，也不等于 0 条。
     * `listSizeObserved` 取日志里最后一个 `列表大小:N` / `书籍总数:N`，没有则为 null。
     */
    fun structuredResult(signals: Signals): Map<String, Any?> {
        val reached = signals.stagesReached.toSet()
        val verdict = when {
            signals.failureSignals.isNotEmpty() -> "failure_signals"
            reached.containsAll(STAGES.map { it.first }) -> "all_stages_reached_no_failure"
            else -> "incomplete"
        }
        val failureCategories = signals.failureSignals
            .map(::categorizeReaderFailure)
            .distinct()
        val result = linkedMapOf<String, Any?>(
            "logVerdict" to verdict,
            "stagesReached" to signals.stagesReached,
            "failureSignals" to signals.failureSignals,
            "failureCategories" to failureCategories,
            "searchReached" to ("search" in reached),
            "detailReached" to ("detail" in reached),
            "tocReached" to ("toc" in reached),
            "contentReached" to ("content" in reached),
            "listSizeObserved" to signals.listSizeObserved,
        )
        if (signals.failureSignals.any { it.contains("js执行超时") }) {
            result["webJsTimeoutHint"] = WEBJS_TIMEOUT_HINT
        }
        return result
    }

    /** 给调试回包追加的统一字段。 */
    fun annotate(logs: List<String>): Map<String, Any?> {
        val signals = parse(logs)
        return mapOf(
            "logSignals" to mapOf(
                "stagesReached" to signals.stagesReached,
                "failureSignals" to signals.failureSignals,
            ),
            "structuredResult" to structuredResult(signals),
            "forbiddenClaims" to FORBIDDEN_CLAIMS,
            "summaryForUser" to summaryForUser(signals),
        )
    }

    /**
     * 阅读端专用失败分类，顺序即优先级：超时 / 页面 Uncaught / 规则 JS /
     * 过盾 / HTTP 403·520；未命中再回落 [SourceCheckEvidence.categorizeMessage]。
     */
    private fun categorizeReaderFailure(message: String): String {
        val lower = message.lowercase()
        return when {
            message.contains("js执行超时") -> "webjs_timeout"
            lower.contains("uncaught") -> "page_script_error"
            lower.contains("referenceerror") || message.contains("未定义") ||
                lower.contains("typeerror") || message.contains("不是函数") -> "rule_js_error"
            lower.contains("cloudflare") || lower.contains("just a moment") ||
                lower.contains("cf-mitigated") -> "verification"
            HTTP_SITE_FAILURE.containsMatchIn(message) -> "site_blocked"
            else -> SourceCheckEvidence.categorizeMessage(message).let { if (it == "sandbox") "unknown" else it }
        }
    }

    private fun isFailureLine(line: String): Boolean =
        FAILURES.any { line.contains(it) } ||
            FAILURES_CI.any { line.contains(it, ignoreCase = true) } ||
            HTTP_SITE_FAILURE.containsMatchIn(line)

    private fun listSizeObserved(logs: List<String>): Int? {
        var last: Int? = null
        for (line in logs) {
            LIST_SIZE.findAll(line).forEach { last = it.groupValues[1].toIntOrNull() }
        }
        return last
    }
}
