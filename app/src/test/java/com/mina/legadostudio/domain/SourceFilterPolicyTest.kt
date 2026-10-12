package com.mina.legadostudio.domain

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SourceFilterPolicy 纯函数回归：login/captcha/failed/unverified 各维度，
 * 含「单字段含验证码字样不删」「Cookie/认证 header 不算登录必删」「保留原始 JSON」等边界。
 */
class SourceFilterPolicyTest {

    private fun source(vararg pairs: Pair<String, Any?>) = Gson().toJson(mapOf(*pairs))

    private fun minimal() = source(
        "bookSourceName" to "示例", "bookSourceUrl" to "https://a.com",
        "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#c"),
    )

    @Test fun defaultFilterKeepsEverything() {
        val report = SourceFilterPolicy.filter(listOf(minimal()))
        assertEquals(1, report.keptCount)
        assertEquals(0, report.excludedCount)
        assertTrue(report.kept[0].reasons.isEmpty())
    }

    @Test fun loginUrlTriggersLoginExclude() {
        val withLogin = source(
            "bookSourceName" to "x", "bookSourceUrl" to "https://a.com", "loginUrl" to "https://a.com/login",
            "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#c"),
        )
        val report = SourceFilterPolicy.filter(listOf(withLogin), excludes = setOf(SourceFilterPolicy.Exclude.LOGIN))
        assertEquals(0, report.keptCount)
        assertTrue(report.excluded[0].reasons.any { it.contains("loginUrl") })
        // 原始 JSON 保留（可导出/回读）
        assertEquals("https://a.com/login", report.excluded[0].source.get("loginUrl").asString)
    }

    @Test fun loginUiTriggersLoginExclude() {
        val withLoginUi = source(
            "bookSourceName" to "x", "bookSourceUrl" to "https://a.com",
            "loginUi" to """[{"name":"user","type":"text"}]""",
            "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#c"),
        )
        val report = SourceFilterPolicy.filter(listOf(withLoginUi), excludes = setOf(SourceFilterPolicy.Exclude.LOGIN))
        assertEquals(0, report.keptCount)
        assertTrue(report.excluded[0].reasons.any { it.contains("loginUi") })
    }

    @Test fun cookieHeaderDoesNotTriggerLoginExclude() {
        // 书源 header 里写 Cookie/Authorization 是凭据不是登录表单：不能算「需要登录」
        val withCookie = source(
            "bookSourceName" to "x", "bookSourceUrl" to "https://a.com",
            "header" to """{"Cookie":"sid=abc","Authorization":"Bearer t"}""",
            "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#c"),
        )
        val report = SourceFilterPolicy.filter(listOf(withCookie), excludes = setOf(SourceFilterPolicy.Exclude.LOGIN))
        assertEquals(1, report.keptCount)
    }

    @Test fun singleCaptchaWordInCommentDoesNotExclude() {
        // 只有注释里出现「验证码」三个字，没有任何实际挑战/验证证据 → 不删
        val noted = source(
            "bookSourceName" to "x", "bookSourceUrl" to "https://a.com",
            "bookSourceComment" to "搜索页有时弹验证码，请手动过一下",
            "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#c"),
        )
        val report = SourceFilterPolicy.filter(listOf(noted), excludes = setOf(SourceFilterPolicy.Exclude.CAPTCHA))
        assertEquals(1, report.keptCount)
    }

    @Test fun captchaCheckErrorExcludesSource() {
        val check = mapOf<String, Any>(
            "搜索" to mapOf("error" to "请求被 JS 人机挑战拦截（HTTP 200，特征：载入中文案+c2 挑战变量+/nnxswnn/ 挑战脚本）", "category" to "captcha"),
        )
        val report = SourceFilterPolicy.filter(
            listOf(minimal()),
            checkResults = mapOf(0 to check),
            excludes = setOf(SourceFilterPolicy.Exclude.CAPTCHA),
        )
        assertEquals(0, report.keptCount)
        assertTrue(report.excluded[0].reasons.any { it.contains("挑战") || it.contains("验证") })
    }

    @Test fun verificationCheckErrorExcludesSource() {
        val check = mapOf<String, Any>(
            "详情" to mapOf("error" to "网站需要在 App 内完成验证", "category" to "verification", "verificationUrl" to "https://a.com/verify"),
        )
        val report = SourceFilterPolicy.filter(
            listOf(minimal()),
            checkResults = mapOf(0 to check),
            excludes = setOf(SourceFilterPolicy.Exclude.CAPTCHA),
        )
        assertEquals(0, report.keptCount)
        assertTrue(report.excluded[0].reasons.any { it.contains("验证") })
    }

    @Test fun failedStageExcludesOnFailed() {
        val check = mapOf<String, Any>(
            "搜索" to mapOf("error" to "SocketTimeoutException: timeout", "category" to "network"),
        )
        val report = SourceFilterPolicy.filter(
            listOf(minimal()),
            checkResults = mapOf(0 to check),
            excludes = setOf(SourceFilterPolicy.Exclude.FAILED),
        )
        assertEquals(0, report.keptCount)
        assertTrue(report.excluded[0].reasons.any { it.contains("搜索") })
    }

    @Test fun okStageDoesNotExcludeOnFailed() {
        val check = mapOf<String, Any>(
            "搜索" to com.mina.legadostudio.runtime.LegadoRuntime.DebugReport(
                "搜索", "我", listOf("HTTP 200", "列表 1 条"),
                listOf(mapOf("name" to "书名", "bookUrl" to "https://a.com/b")),
            ),
        )
        val report = SourceFilterPolicy.filter(
            listOf(minimal()),
            checkResults = mapOf(0 to check),
            excludes = setOf(SourceFilterPolicy.Exclude.FAILED),
        )
        assertEquals(1, report.keptCount)
    }

    @Test fun missingCheckTriggersUnverified() {
        val report = SourceFilterPolicy.filter(listOf(minimal()), excludes = setOf(SourceFilterPolicy.Exclude.UNVERIFIED))
        assertEquals(0, report.keptCount)
        assertTrue(report.excluded[0].reasons.any { it.contains("未") })
    }

    private fun okSearchReport() = com.mina.legadostudio.runtime.LegadoRuntime.DebugReport(
        "搜索", "我", listOf("HTTP 200", "列表 1 条"), listOf(mapOf("name" to "n", "bookUrl" to "u")),
    )
    private fun okDetailReport() = com.mina.legadostudio.runtime.LegadoRuntime.DebugReport(
        "详情", "u", listOf("HTTP 200"), mapOf("name" to "n", "tocUrl" to "https://a.com/t"),
    )
    private fun okTocReport() = com.mina.legadostudio.runtime.LegadoRuntime.DebugReport(
        "目录", "t", listOf("目录总数 1"), mapOf("totalChapters" to 1, "chapters" to listOf(mapOf("url" to "https://a.com/c1"))),
    )
    private fun okContentReport() = com.mina.legadostudio.runtime.LegadoRuntime.DebugReport(
        "正文", "c", listOf("合并正文 10 字符"), mapOf("pages" to 1, "content" to "正文内容正文内容", "contentTotalChars" to 10),
    )

    @Test fun ranCheckDoesNotTriggerUnverified() {
        // 四段全部实际跑过才不算未验证
        val check = mapOf<String, Any>(
            "搜索" to okSearchReport(), "详情" to okDetailReport(),
            "目录" to okTocReport(), "正文" to okContentReport(),
        )
        val report = SourceFilterPolicy.filter(
            listOf(minimal()),
            checkResults = mapOf(0 to check),
            excludes = setOf(SourceFilterPolicy.Exclude.UNVERIFIED),
        )
        assertEquals(1, report.keptCount)
    }

    @Test fun partialCheckStillCountsAsUnverified() {
        // 只跑了搜索一段 → 关键阶段缺详情/目录/正文，仍是未验证（修复「任一 ran 即已验证」假阳性）
        val check = mapOf<String, Any>("搜索" to okSearchReport())
        val report = SourceFilterPolicy.filter(
            listOf(minimal()),
            checkResults = mapOf(0 to check),
            excludes = setOf(SourceFilterPolicy.Exclude.UNVERIFIED),
        )
        assertEquals(0, report.keptCount)
        assertTrue(report.excluded[0].reasons.any { it.contains("未跑齐") && it.contains("详情") })
    }

    @Test fun mediaSourceUnverifiedDoesNotRequireContentStage() {
        // 音频源（type=1）：搜索+详情+目录跑过即可，正文缺不算「未跑齐关键阶段」
        val audio = source(
            "bookSourceName" to "音频", "bookSourceUrl" to "https://a.com", "bookSourceType" to 1,
            "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#c"),
        )
        val check = mapOf<String, Any>(
            "搜索" to okSearchReport(), "详情" to okDetailReport(), "目录" to okTocReport(),
        )
        val report = SourceFilterPolicy.filter(
            listOf(audio),
            checkResults = mapOf(0 to check),
            excludes = setOf(SourceFilterPolicy.Exclude.UNVERIFIED),
        )
        assertEquals(1, report.keptCount)
    }

    // ---------- checkResults 序列化形态与可信度边界（回归：不要把序列化 DebugReport 误判失败） ----------

    /** 手造一份「check_source 完整返回」的进程内 Map（含 verdict+evidence+validation）。 */
    private fun fullCheckMap(): Map<String, Any> {
        val stages = mapOf(
            "搜索" to okSearchReport(), "详情" to okDetailReport(),
            "目录" to okTocReport(), "正文" to okContentReport(),
        )
        val evidence = stages.mapValues { (s, r) ->
            SourceCheckEvidence.of(s, r)
        }
        return mapOf(
            "搜索" to stages["搜索"]!!, "详情" to stages["详情"]!!,
            "目录" to stages["目录"]!!, "正文" to stages["正文"]!!,
            "validation" to BookSourceValidator().validate(minimal()),
            "evidence" to evidence,
            "verdict" to mapOf("summary" to "full", "stagesRan" to 4, "stagesOk" to 4,
                "stagesFailed" to emptyList<String>(), "validationOk" to true, "validationIssues" to 0),
        )
    }

    @Test fun serializedDebugReportMapIsEvaluatedNotMisread() {
        // 回归：checkResults 经 Gson roundtrip 后阶段值是 {type,entry,lines,data} Map——
        // 必须按 DebugReport 内容复算，不是「找不到 error 字段就当作 unknown 失败」。
        val gson = Gson()
        val serializedCheck = gson.fromJson(gson.toJson(fullCheckMap()), Map::class.java) as Map<String, Any>
        val report = SourceFilterPolicy.filter(
            listOf(minimal()),
            checkResults = mapOf(0 to serializedCheck),
            excludes = setOf(SourceFilterPolicy.Exclude.FAILED, SourceFilterPolicy.Exclude.UNVERIFIED),
        )
        assertEquals("序列化 DebugReport 必须被正确判为可用", 1, report.keptCount)
        assertTrue(report.kept[0].checkVerified)
    }

    @Test fun serializedErrorMapStillCountsAsFailed() {
        // error Map（{error,category}）经 roundtrip 后仍按失败处理
        val gson = Gson()
        val check = gson.fromJson(gson.toJson(mapOf(
            "搜索" to mapOf("error" to "SocketTimeoutException: timeout", "category" to "network"),
        )), Map::class.java) as Map<String, Any>
        val report = SourceFilterPolicy.filter(
            listOf(minimal()),
            checkResults = mapOf(0 to check),
            excludes = setOf(SourceFilterPolicy.Exclude.FAILED),
        )
        assertEquals(0, report.keptCount)
        assertTrue(report.excluded[0].reasons.any { it.contains("搜索") })
    }

    @Test fun handCraftedEmptyStageMapIsNotEvidence() {
        // 手造 {"搜索":{}}：无 error/lines/data → 不算「跑过」，FAILED 不误杀、UNVERIFIED 不误放
        val check = mapOf<String, Any>("搜索" to emptyMap<String, Any>())
        val failed = SourceFilterPolicy.filter(
            listOf(minimal()), checkResults = mapOf(0 to check),
            excludes = setOf(SourceFilterPolicy.Exclude.FAILED),
        )
        assertEquals("空 Map 不能算「跑过但失败」", 1, failed.keptCount)
        val unverified = SourceFilterPolicy.filter(
            listOf(minimal()), checkResults = mapOf(0 to check),
            excludes = setOf(SourceFilterPolicy.Exclude.UNVERIFIED),
        )
        assertEquals("空 Map 不能算「已验证」", 0, unverified.keptCount)
        assertTrue(unverified.excluded[0].reasons.any { it.contains("未") })
    }

    @Test fun forgedOkEvidenceWithoutSummaryIsNotTrusted() {
        // 手造「{搜索:{ran:true,ok:true}}」自述 evidence（无 check_source 摘要）→ 不采信
        val check = mapOf<String, Any>(
            "搜索" to mapOf("stage" to "搜索", "ran" to true, "ok" to true),
        )
        val report = SourceFilterPolicy.filter(
            listOf(minimal()), checkResults = mapOf(0 to check),
            excludes = setOf(SourceFilterPolicy.Exclude.UNVERIFIED),
        )
        assertEquals("无摘要的自述 ok 不能算已验证", 0, report.keptCount)
        assertFalse(report.excluded[0].checkVerified)
    }

    @Test fun forgedSummaryContradictingStageDataIsRejected() {
        // 手造摘要声称 full，但阶段值复算出来是失败 → 整体不采信，按数据真实结论算
        val forged = mapOf<String, Any>(
            "搜索" to mapOf("error" to "SocketTimeoutException", "category" to "network"),
            "evidence" to mapOf(
                "搜索" to mapOf("stage" to "搜索", "ran" to true, "ok" to true),
                "详情" to mapOf("stage" to "详情", "ran" to true, "ok" to true),
                "目录" to mapOf("stage" to "目录", "ran" to true, "ok" to true),
                "正文" to mapOf("stage" to "正文", "ran" to true, "ok" to true),
            ),
            "verdict" to mapOf("summary" to "full", "stagesRan" to 4, "stagesOk" to 4),
        )
        val report = SourceFilterPolicy.filter(
            listOf(minimal()), checkResults = mapOf(0 to forged),
            excludes = setOf(SourceFilterPolicy.Exclude.FAILED, SourceFilterPolicy.Exclude.UNVERIFIED),
        )
        assertEquals("摘要与阶段数据矛盾时必须按数据判定", 0, report.keptCount)
        assertFalse(report.excluded[0].checkVerified)
        assertTrue(report.excluded[0].reasons.any { it.contains("搜索") })
    }

    @Test fun summaryCoherentCheckWithoutStageValuesUsesEvidence() {
        // check_source 摘要自洽但阶段原始值省略（MCP 裁剪场景）→ 采信 evidence 摘要
        val gson = Gson()
        val slim = gson.fromJson(gson.toJson(mapOf(
            "evidence" to mapOf(
                "搜索" to mapOf("stage" to "搜索", "ran" to true, "ok" to true, "facts" to mapOf("resultCount" to 1)),
                "详情" to mapOf("stage" to "详情", "ran" to true, "ok" to true, "facts" to mapOf("name" to "n", "tocUrl" to "t")),
                "目录" to mapOf("stage" to "目录", "ran" to true, "ok" to true, "facts" to mapOf("totalChapters" to 5)),
                "正文" to mapOf("stage" to "正文", "ran" to true, "ok" to true, "facts" to mapOf("contentTotalChars" to 100)),
            ),
            "verdict" to mapOf("summary" to "full", "stagesRan" to 4, "stagesOk" to 4,
                "stagesFailed" to emptyList<String>(), "validationOk" to true, "validationIssues" to 0),
            "validation" to mapOf("issues" to emptyList<Any>(), "isValid" to true),
        )), Map::class.java) as Map<String, Any>
        val report = SourceFilterPolicy.filter(
            listOf(minimal()), checkResults = mapOf(0 to slim),
            excludes = setOf(SourceFilterPolicy.Exclude.UNVERIFIED, SourceFilterPolicy.Exclude.FAILED),
        )
        assertEquals(1, report.keptCount)
        assertTrue(report.kept[0].checkVerified)
    }

    @Test fun multipleExcludesCombine() {
        val login = source(
            "bookSourceName" to "L", "bookSourceUrl" to "https://a.com", "loginUrl" to "https://a.com/login",
            "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#c"),
        )
        val clean = minimal()
        val report = SourceFilterPolicy.filter(
            listOf(login, clean),
            excludes = setOf(SourceFilterPolicy.Exclude.LOGIN),
        )
        assertEquals(1, report.keptCount)
        assertEquals(1, report.excludedCount)
        assertEquals("示例", report.kept[0].source.get("bookSourceName").asString)
        assertEquals("L", report.excluded[0].source.get("bookSourceName").asString)
    }

    @Test fun malformedSourceExcludedRegardless() {
        val report = SourceFilterPolicy.filter(listOf("{broken"), excludes = emptySet())
        assertEquals(0, report.keptCount)
        assertTrue(report.excluded[0].reasons.any { it.contains("JSON") })
    }
}
