package com.mina.legadostudio.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDebugLogSignalsTest {
    @Test fun stagesAndFailuresAreExtractedFromRealLogLines() {
        val logs = listOf(
            "⇒开始搜索关键字:我的",
            "︾开始解析搜索页",
            "┌获取书籍列表",
            "└列表为空,按详情页解析",
            "◇书籍总数:0",
            "︽未获取到书籍",
        )
        val s = ReaderDebugLogSignals.parse(logs)
        assertEquals(listOf("search"), s.stagesReached)
        assertEquals(listOf("◇书籍总数:0", "︽未获取到书籍"), s.failureSignals)
        assertEquals(0, s.listSizeObserved)
        val summary = ReaderDebugLogSignals.summaryForUser(s)
        assertTrue(summary.startsWith("阅读端调试出现失败信号"))
        assertTrue(summary.contains("观测到列表大小 0"))
    }

    @Test fun fullRunReportsStagesButNeverClaimsPass() {
        val logs = listOf("︽搜索页解析完成", "︽详情页解析完成", "︽目录页解析完成", "︽正文页解析完成")
        val s = ReaderDebugLogSignals.parse(logs)
        assertEquals(listOf("search", "detail", "toc", "content"), s.stagesReached)
        assertTrue(s.failureSignals.isEmpty())
        assertNull(s.listSizeObserved)
        val summary = ReaderDebugLogSignals.summaryForUser(s)
        assertTrue(summary.contains("进入阶段（不代表完成）"))
        assertFalse(summary.contains("验证成功"))
        assertFalse(summary.contains("观测到列表大小"))
    }

    @Test fun emptyLogsMeanNoStageReached() {
        val s = ReaderDebugLogSignals.parse(emptyList())
        assertTrue(ReaderDebugLogSignals.summaryForUser(s).contains("没有识别到搜索或解析阶段"))
        assertNull(s.listSizeObserved)
    }

    @Test fun failureSignalsAreTruncatedAndCapped() {
        val logs = (1..30).map { "Exception $it " + "x".repeat(300) }
        val s = ReaderDebugLogSignals.parse(logs)
        assertEquals(12, s.failureSignals.size)
        assertTrue(s.failureSignals.all { it.length <= 160 })
    }

    @Test fun annotateCarriesForbiddenClaims() {
        val a = ReaderDebugLogSignals.annotate(listOf("cannot_empty"))
        assertEquals(listOf("通过", "一致", "验证成功"), a["forbiddenClaims"])
        val signals = a["logSignals"] as Map<*, *>
        assertEquals(listOf("cannot_empty"), signals["failureSignals"])
        assertTrue(a["summaryForUser"].toString().contains("失败信号"))
    }

    @Test fun structuredResultGivesMachineReadableLogVerdict() {
        val full = ReaderDebugLogSignals.annotate(listOf("︽搜索页解析完成", "︽详情页解析完成", "︽目录页解析完成", "︽正文页解析完成"))
        val ok = full["structuredResult"] as Map<*, *>
        assertEquals("all_stages_reached_no_failure", ok["logVerdict"])
        assertEquals(true, ok["contentReached"])
        assertNull(ok["listSizeObserved"])
        assertFalse(ok.containsKey("webJsTimeoutHint"))
        val bad = ReaderDebugLogSignals.annotate(listOf("⇒开始搜索关键字:我的", "◇书籍总数:0"))["structuredResult"] as Map<*, *>
        assertEquals("failure_signals", bad["logVerdict"])
        assertEquals(false, bad["tocReached"])
        assertEquals(listOf("unknown"), bad["failureCategories"])
        assertEquals(0, bad["listSizeObserved"])
        assertFalse((bad["failureCategories"] as List<*>).contains("sandbox"))
        val categorized = ReaderDebugLogSignals.annotate(listOf(
            "未获取到书籍", "书籍总数:0", "Exception: captcha", "Exception: CAPTCHA", "Exception: ClassCastException",
        ))["structuredResult"] as Map<*, *>
        assertEquals(listOf("unknown", "captcha"), categorized["failureCategories"])
        val partial = ReaderDebugLogSignals.annotate(listOf("⇒开始搜索关键字:我的"))["structuredResult"] as Map<*, *>
        assertEquals("incomplete", partial["logVerdict"])
        assertEquals(emptyList<String>(), partial["failureCategories"])
        assertNull(partial["listSizeObserved"])
    }

    @Test fun readerSpecificFailuresAreClassifiedBeforeSandboxFallback() {
        fun result(vararg lines: String) =
            ReaderDebugLogSignals.annotate(lines.toList())["structuredResult"] as Map<*, *>

        val timeout = result("js执行超时")
        assertEquals("failure_signals", timeout["logVerdict"])
        assertEquals(listOf("webjs_timeout"), timeout["failureCategories"])
        assertEquals(ReaderDebugLogSignals.WEBJS_TIMEOUT_HINT, timeout["webJsTimeoutHint"])
        assertTrue(timeout["webJsTimeoutHint"].toString().contains("webJs 返回 null"))
        assertTrue(timeout["webJsTimeoutHint"].toString().contains("不等于规则错误"))
        assertTrue(timeout["webJsTimeoutHint"].toString().contains("空容器 HTML"))

        assertEquals(listOf("page_script_error"), result("Uncaught TypeError: x is not a function")["failureCategories"])
        assertEquals(listOf("rule_js_error"), result("ReferenceError: foo is not defined")["failureCategories"])
        assertEquals(listOf("rule_js_error"), result("TypeError: bar")["failureCategories"])
        assertEquals(listOf("rule_js_error"), result("java 未定义")["failureCategories"])
        assertEquals(listOf("rule_js_error"), result("foo 不是函数")["failureCategories"])
        assertEquals(listOf("verification"), result("Cloudflare challenge")["failureCategories"])
        assertEquals(listOf("verification"), result("Just a moment...")["failureCategories"])
        assertEquals(listOf("verification"), result("cf-mitigated: challenge")["failureCategories"])
        assertEquals(listOf("site_blocked"), result("HTTP 403 Forbidden")["failureCategories"])
        assertEquals(listOf("site_blocked"), result("HTTP 520")["failureCategories"])
        assertEquals(listOf("site_blocked"), result("error code: 520")["failureCategories"])
        assertEquals(listOf("site_blocked"), result("状态码:403")["failureCategories"])
        assertEquals(listOf("verification"), result("HTTP 403 Cloudflare")["failureCategories"])
    }

    @Test fun bare403Or520AreNotFailureSignals() {
        val bare = ReaderDebugLogSignals.parse(listOf("retry 403 then 520", "共403章", "错误520"))
        assertTrue(bare.failureSignals.isEmpty())
        val http = ReaderDebugLogSignals.parse(listOf("HTTP 403", "error code: 520"))
        assertEquals(listOf("HTTP 403", "error code: 520"), http.failureSignals)
    }

    @Test fun listSizeObservedTakesLastMatchAndDoesNotTreatNonZeroAsFailure() {
        val last = ReaderDebugLogSignals.annotate(listOf("└列表大小:5", "◇书籍总数:0"))
        val sr = last["structuredResult"] as Map<*, *>
        assertEquals(0, sr["listSizeObserved"])
        assertTrue(last["summaryForUser"].toString().contains("观测到列表大小 0"))

        val size = ReaderDebugLogSignals.parse(listOf("└列表大小:12"))
        assertEquals(12, size.listSizeObserved)
        assertTrue(size.failureSignals.isEmpty())
        assertTrue(ReaderDebugLogSignals.summaryForUser(size).contains("观测到列表大小 12"))

        val none = ReaderDebugLogSignals.annotate(listOf("⇒开始搜索关键字:我的"))["structuredResult"] as Map<*, *>
        assertNull(none["listSizeObserved"])
    }
}
