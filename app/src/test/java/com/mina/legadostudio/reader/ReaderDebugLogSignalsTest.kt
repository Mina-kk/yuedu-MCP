package com.mina.legadostudio.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(ReaderDebugLogSignals.summaryForUser(s).startsWith("阅读端调试出现失败信号"))
    }

    @Test fun fullRunReportsStagesButNeverClaimsPass() {
        val logs = listOf("︽搜索页解析完成", "︽详情页解析完成", "︽目录页解析完成", "︽正文页解析完成")
        val s = ReaderDebugLogSignals.parse(logs)
        assertEquals(listOf("search", "detail", "toc", "content"), s.stagesReached)
        assertTrue(s.failureSignals.isEmpty())
        val summary = ReaderDebugLogSignals.summaryForUser(s)
        assertTrue(summary.contains("进入阶段（不代表完成）"))
        assertFalse(summary.contains("验证成功"))
    }

    @Test fun emptyLogsMeanNoStageReached() {
        val s = ReaderDebugLogSignals.parse(emptyList())
        assertTrue(ReaderDebugLogSignals.summaryForUser(s).contains("没有识别到搜索或解析阶段"))
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
        val bad = ReaderDebugLogSignals.annotate(listOf("⇒开始搜索关键字:我的", "◇书籍总数:0"))["structuredResult"] as Map<*, *>
        assertEquals("failure_signals", bad["logVerdict"])
        assertEquals(false, bad["tocReached"])
        val partial = ReaderDebugLogSignals.annotate(listOf("⇒开始搜索关键字:我的"))["structuredResult"] as Map<*, *>
        assertEquals("incomplete", partial["logVerdict"])
    }
}
