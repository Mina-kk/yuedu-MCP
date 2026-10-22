package com.mina.legadostudio.reader

/**
 * 从阅读端调试日志里提取阶段与失败信号。只做标注，不产生“通过”结论：
 * 旧版调试通道本身不返回机器可读的成败，任何“一致/通过”说法都没有依据。
 */
object ReaderDebugLogSignals {
    data class Signals(val stagesReached: List<String>, val failureSignals: List<String>)

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
    )

    fun parse(logs: List<String>): Signals {
        val stages = STAGES.filter { (_, marks) -> logs.any { line -> marks.any { line.contains(it) } } }.map { it.first }
        val failures = LinkedHashSet<String>()
        for (line in logs) {
            for (mark in FAILURES) if (line.contains(mark)) failures += line.trim().take(160)
            if (failures.size >= 12) break
        }
        return Signals(stages, failures.toList())
    }

    fun summaryForUser(signals: Signals): String = when {
        signals.failureSignals.isNotEmpty() ->
            "阅读端调试出现失败信号：" + signals.failureSignals.take(3).joinToString("；") +
                "。先核对阅读端响应及站点限流、网络和规则，不能以沙箱结果宣称可用"
        signals.stagesReached.isEmpty() -> "日志没有识别到搜索或解析阶段；不能宣称可用"
        else -> "阅读端日志显示进入阶段（不代表完成）：" + signals.stagesReached.joinToString("/") +
            "；旧版调试未给出通过结论，请如实转述关键日志行"
    }

    /**
     * 机器可读的日志结论：只陈述日志事实（到达了哪些阶段、有没有失败行），不是「通过」判定。
     * all_stages_reached_no_failure = 搜索/详情/目录/正文四段都出现阶段日志且无失败行；
     * failure_signals = 出现失败行；incomplete = 无失败行但没走完四段。
     */
    fun structuredResult(signals: Signals): Map<String, Any?> {
        val reached = signals.stagesReached.toSet()
        val verdict = when {
            signals.failureSignals.isNotEmpty() -> "failure_signals"
            reached.containsAll(STAGES.map { it.first }) -> "all_stages_reached_no_failure"
            else -> "incomplete"
        }
        return linkedMapOf(
            "logVerdict" to verdict,
            "stagesReached" to signals.stagesReached,
            "failureSignals" to signals.failureSignals,
            "searchReached" to ("search" in reached),
            "detailReached" to ("detail" in reached),
            "tocReached" to ("toc" in reached),
            "contentReached" to ("content" in reached),
        )
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
}
