package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.LegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * check_source 结构化证据与链路推进回归：
 * 零结果、空目录、有效链路、挑战/验证、取消传播、mock runtime 各异阶段形态都覆盖。
 */
class CheckSourceEvidenceTest {

    private fun runtime() = EmbeddedLegadoRuntime(
        HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()),
    )

    /** 能跑通搜索→详情→目录→正文全链路的 MockWebServer 书源。 */
    private fun fullChainSource(server: MockWebServer) = Gson().toJson(mapOf(
        "bookSourceName" to "全链路", "bookSourceUrl" to server.url("/").toString(),
        "searchUrl" to "search?q={{key}}",
        "ruleSearch" to mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
        "ruleBookInfo" to mapOf("name" to "h1@text", "tocUrl" to "#toc@href"),
        "ruleToc" to mapOf("chapterList" to ".ch", "chapterName" to "a@text", "chapterUrl" to "a@href"),
        "ruleContent" to mapOf("content" to "#content@text"),
    ))

    private fun fullChainDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path?.startsWith("/search") == true -> MockResponse().setBody(
                """<div class="book"><a href="/book/1">测试书名</a><span>作者</span></div>""")
            request.path == "/book/1" -> MockResponse().setBody(
                """<h1>测试书名</h1><a id="toc" href="/book/1/toc">目录</a>""")
            request.path == "/book/1/toc" -> MockResponse().setBody(
                """<div class="ch"><a href="/book/1/1">第一章</a></div><div class="ch"><a href="/book/1/2">第二章</a></div>""")
            request.path == "/book/1/1" -> MockResponse().setBody("""<div id="content">正文内容正文内容</div>""")
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** 详情与目录同页：源未配置独立 tocUrl 规则。 */
    private fun samePageChainSource(server: MockWebServer) = Gson().toJson(mapOf(
        "bookSourceName" to "同页目录", "bookSourceUrl" to server.url("/").toString(),
        "searchUrl" to "search?q={{key}}",
        "ruleSearch" to mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
        "ruleBookInfo" to mapOf("name" to "h1@text"),
        "ruleToc" to mapOf("chapterList" to ".ch", "chapterName" to "a@text", "chapterUrl" to "a@href"),
        "ruleContent" to mapOf("content" to "#content@text"),
    ))

    private fun samePageDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path?.startsWith("/search") == true -> MockResponse().setBody(
                """<div class="book"><a href="/book/1">测试书名</a></div>""")
            request.path == "/book/1" -> MockResponse().setBody(
                """<h1>测试书名</h1><div class="ch"><a href="/book/1/1">第一章</a></div>""")
            request.path == "/book/1/1" -> MockResponse().setBody("""<div id="content">正文内容正文内容</div>""")
            else -> MockResponse().setResponseCode(404)
        }
    }

    @Test fun autoChainFollowsSearchBookUrlTocUrlChapterUrl() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = fullChainDispatcher()
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = fullChainSource(server), auto = true),
                runtime(), cacheFetch = null,
            )
            val verdict = result["verdict"] as Map<*, *>
            assertEquals("full", verdict["summary"])
            assertEquals(4, verdict["stagesOk"])
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertTrue(evidence["搜索"]!!.ok)
            assertEquals("测试书名", evidence["搜索"]!!.facts["firstUsableName"])
            assertTrue(evidence["详情"]!!.ok)
            assertTrue(evidence["目录"]!!.ok)
            assertEquals(2, evidence["目录"]!!.facts["totalChapters"])
            assertTrue(evidence["正文"]!!.ok)
            assertTrue((evidence["正文"]!!.facts["contentTotalChars"] as Int) > 0)
        }
    }

    @Test fun autoChainUsesDetailUrlWhenTocUrlRuleAbsent() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = samePageDispatcher()
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = samePageChainSource(server), auto = true),
                runtime(), cacheFetch = null,
            )
            val verdict = result["verdict"] as Map<*, *>
            assertEquals("full", verdict["summary"])
            assertEquals(4, verdict["stagesOk"])
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertTrue(evidence["搜索"]!!.ok)
            assertTrue(evidence["详情"]!!.ok)
            val tocUrl = evidence["详情"]!!.facts["tocUrl"]?.toString().orEmpty()
            assertTrue("未配置 tocUrl 时应回填详情页 URL，实际=$tocUrl", tocUrl.contains("/book/1"))
            assertFalse("回填不得指向独立目录路径", tocUrl.contains("/toc"))
            assertTrue(evidence["目录"]!!.ok)
            assertEquals(1, evidence["目录"]!!.facts["totalChapters"])
            assertTrue(evidence["正文"]!!.ok)
            assertTrue((evidence["正文"]!!.facts["contentTotalChars"] as Int) > 0)
        }
    }

    @Test fun autoChainDoesNotFallbackWhenExplicitTocUrlRuleEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = samePageDispatcher()
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "显式坏 tocUrl", "bookSourceUrl" to server.url("/").toString(),
                "searchUrl" to "search?q={{key}}",
                "ruleSearch" to mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
                "ruleBookInfo" to mapOf("name" to "h1@text", "tocUrl" to "#missing@href"),
                "ruleToc" to mapOf("chapterList" to ".ch", "chapterName" to "a@text", "chapterUrl" to "a@href"),
                "ruleContent" to mapOf("content" to "#content@text"),
            ))
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = source, auto = true),
                runtime(), cacheFetch = null,
            )
            val verdict = result["verdict"] as Map<*, *>
            assertEquals("partial", verdict["summary"])
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertTrue(evidence["搜索"]!!.ok)
            assertFalse("显式 tocUrl 规则取空不能静默回填", evidence["详情"]!!.ok)
            assertEquals("empty", evidence["详情"]!!.category)
            assertTrue(evidence["详情"]!!.facts["tocUrl"]?.toString().isNullOrBlank())
            assertNull(evidence["目录"])
            assertNull(evidence["正文"])
        }
    }

    @Test fun autoChainDoesNotTreatDetail404AsSuccessWhenTocUrlRuleAbsent() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/search") == true -> MockResponse().setBody(
                        """<div class="book"><a href="/book/1">测试书名</a></div>""")
                    request.path == "/book/1" -> MockResponse().setResponseCode(404).setBody("<h1>测试书名</h1>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = samePageChainSource(server), auto = true),
                runtime(), cacheFetch = null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertTrue(evidence["搜索"]!!.ok)
            assertFalse("详情 404 不能靠回填 tocUrl 伪造成功", evidence["详情"]!!.ok)
            assertNull(evidence["目录"])
            assertNull(evidence["正文"])
        }
    }

    @Test fun autoChainDoesNotTreatEmptyNameAsSuccessWhenTocUrlRuleAbsent() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/search") == true -> MockResponse().setBody(
                        """<div class="book"><a href="/book/1">测试书名</a></div>""")
                    request.path == "/book/1" -> MockResponse().setBody(
                        """<div class="ch"><a href="/book/1/1">第一章</a></div>""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = samePageChainSource(server), auto = true),
                runtime(), cacheFetch = null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertTrue(evidence["搜索"]!!.ok)
            assertFalse("空书名不能靠回填 tocUrl 伪造成功", evidence["详情"]!!.ok)
            assertNull(evidence["目录"])
            assertNull(evidence["正文"])
        }
    }

    @Test fun autoStopsWhenSearchEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html><body>无结果</body></html>"))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = fullChainSource(server), auto = true),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertFalse(evidence["搜索"]!!.ok)
            assertEquals("empty", evidence["搜索"]!!.category)
            assertEquals(CheckSourceRunner.DEFAULT_PROBE_KEYWORD, (result["搜索"] as LegadoRuntime.DebugReport).entry)
            // 搜索失败 → 后续阶段不应出现（没有入口可推进）
            assertNull(evidence["详情"])
            assertNull(evidence["目录"])
            assertNull(evidence["正文"])
            val verdict = result["verdict"] as Map<*, *>
            assertEquals("unusable", verdict["summary"])
            assertEquals("rule", verdict["failureKind"])
            @Suppress("UNCHECKED_CAST")
            val warnings = result["warnings"] as List<String>
            assertTrue(warnings.none { it.contains("无需修改规则") })
        }
    }

    @Test fun explicitStagesAreNotOverriddenByAuto() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/detail-explicit" -> MockResponse().setBody("<h1>显式详情</h1><a id='toc' href='/x/toc'>目录</a>")
                    else -> MockResponse().setBody("<html></html>")
                }
            }
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(
                    source = fullChainSource(server),
                    detailUrl = server.url("/detail-explicit").toString(),
                    auto = true,
                ),
                runtime(), null,
            )
            val detail = result["详情"] as LegadoRuntime.DebugReport
            assertTrue(detail.entry.contains("/detail-explicit"))
        }
    }

    @Test fun verificationErrorIsClassifiedSeparately() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody(
                "<html><title>人机验证</title><div>Verify Yourself</div></html>"))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = fullChainSource(server), detailUrl = server.url("/v").toString()),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val detail = result["详情"] as Map<String, Any>
            assertTrue(detail.containsKey("error"))
            assertEquals("verification", detail["category"])
            assertTrue(detail.containsKey("verificationUrl"))
        }
    }

    @Test fun jsChallengeErrorIsClassifiedAsCaptcha() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """<div>内容正在载入……</div><script>var c2="x";var c1="y";</script><script src="/nnxswnn/a.js"></script><meta http-equiv="refresh" content="5">"""))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = fullChainSource(server), detailUrl = server.url("/c").toString()),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val detail = result["详情"] as Map<String, Any>
            assertEquals("captcha", detail["category"])
        }
    }

    @Test fun networkFailureIsClassifiedAsNetwork() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val port = server.port
            server.shutdown() // 端口立即释放 → 连接被拒
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = fullChainSource(server).let {
                    // 指向已关闭的端口，制造 connect refused
                    Gson().toJson(mapOf(
                        "bookSourceName" to "x", "bookSourceUrl" to "http://127.0.0.1:$port/",
                        "searchUrl" to "search?q={{key}}",
                        "ruleSearch" to mapOf("bookList" to ".b", "name" to "a@text"),
                        "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#c"),
                    ))
                }, searchKey = "我"),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val search = result["搜索"] as Map<String, Any>
            assertTrue(search.containsKey("error"))
            assertEquals("network", search["category"])
        }
    }

    @Test fun cancellationPropagates() {
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
                throw CancellationException("取消")
            }
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val ex = runCatching {
            runBlocking {
                CheckSourceRunner.run(
                    CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", detailUrl = "https://a.com/b"),
                    runtime, null,
                )
            }
        }.exceptionOrNull()
        assertTrue("CancellationException 必须上浮，实际=${ex?.javaClass}", ex is CancellationException)
    }

    @Test fun mockRuntimeZeroResultSearchIsNotOk() = runBlocking {
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String) =
                LegadoRuntime.DebugReport("搜索", entry, listOf("HTTP 200 x", "列表 0 条（内联前 0 条）"), emptyList<Map<String, String>>())
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","searchUrl":"s{{key}}","ruleSearch":{"bookList":".b"},"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", searchKey = "我"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse(evidence["搜索"]!!.ok)
        assertEquals("empty", evidence["搜索"]!!.category)
        assertEquals(0, evidence["搜索"]!!.facts["resultCount"])
    }

    private fun capturingRuntime(entries: MutableList<String>) = object : LegadoRuntime {
        override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
        override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
            entries += entry
            return LegadoRuntime.DebugReport("搜索", entry, listOf("列表 0 条"), emptyList<Map<String, String>>())
        }
        override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
        override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
    }

    private fun probeSource(checkKeyWord: String?): String = Gson().toJson(buildMap {
        put("bookSourceName", "x"); put("bookSourceUrl", "https://a.com"); put("searchUrl", "s{{key}}")
        put("ruleSearch", buildMap { put("bookList", ".b"); checkKeyWord?.let { put("checkKeyWord", it) } })
        put("ruleToc", mapOf("chapterList" to "a")); put("ruleContent", mapOf("content" to "#c"))
    })

    @Test fun probeKeywordPrefersMultiCharCheckKeyWord() = runBlocking {
        val entries = mutableListOf<String>()
        CheckSourceRunner.run(CheckSourceRunner.Request(probeSource(" 捞尸人 ")), capturingRuntime(entries), null)
        assertEquals(listOf("捞尸人"), entries)
    }

    @Test fun probeKeywordNeverUsesSingleCharacter() = runBlocking {
        for (keyword in listOf("我", " 我 ", "", null)) {
            val entries = mutableListOf<String>()
            val result = CheckSourceRunner.run(CheckSourceRunner.Request(probeSource(keyword)), capturingRuntime(entries), null)
            assertEquals("checkKeyWord=$keyword", listOf("我的"), entries)
            @Suppress("UNCHECKED_CAST")
            val warnings = result["warnings"] as List<String>
            assertTrue(warnings.any { it.contains("「我的」") })
        }
    }

    private fun searchOnlyRuntime(lines: List<String>) = object : LegadoRuntime {
        override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
        override suspend fun debug(sourceJson: String, entry: String) =
            LegadoRuntime.DebugReport("搜索", entry, lines, emptyList<Map<String, String>>())
        override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
        override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
    }

    private val searchOnlySource = """{"bookSourceName":"x","bookSourceUrl":"https://a.com","searchUrl":"s{{key}}","ruleSearch":{"bookList":".b"},"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}"""

    @Test fun siteRateLimitedSearchIsInconclusiveNotRuleError() = runBlocking {
        val runtime = searchOnlyRuntime(listOf("HTTP 200 x", "列表 0 条（内联前 0 条）", "【站点限流】搜索间隔【15】秒，请于 17:48:45 后再进行搜索"))
        val result = CheckSourceRunner.run(CheckSourceRunner.Request(searchOnlySource, searchKey = "斗破苍穹"), runtime, null)
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse(evidence["搜索"]!!.ok)
        assertEquals("rate_limited", evidence["搜索"]!!.category)
        assertTrue(evidence["搜索"]!!.facts["rateLimitHint"].toString().contains("搜索间隔"))
        val verdict = result["verdict"] as Map<*, *>
        assertEquals("inconclusive", verdict["summary"])
        assertEquals("site", verdict["failureKind"])
        assertTrue(verdict["note"].toString().contains("限流"))
    }

    @Test fun plainZeroResultSearchWithRealKeywordStaysEmpty() = runBlocking {
        val runtime = searchOnlyRuntime(listOf("HTTP 200 x", "列表 0 条（内联前 0 条）"))
        val result = CheckSourceRunner.run(CheckSourceRunner.Request(searchOnlySource, searchKey = "斗破苍穹"), runtime, null)
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertEquals("empty", evidence["搜索"]!!.category)
        assertEquals("rule", (result["verdict"] as Map<*, *>)["failureKind"])
    }

    @Test fun siteRateLimitSnippetDetectsHintAnd429Only() {
        val limited = "<html><body><div class='msg'>搜索间隔【15】秒，请于 17:48:45 后再进行搜索！</div></body></html>"
        assertNotNull(SourceCheckEvidence.siteRateLimitSnippet(limited, 200))
        assertTrue(SourceCheckEvidence.siteRateLimitSnippet(limited, 200)!!.contains("搜索间隔"))
        assertNull(SourceCheckEvidence.siteRateLimitSnippet("<html><body><div class='b'>无结果</div></body></html>", 200))
        assertEquals("HTTP 429", SourceCheckEvidence.siteRateLimitSnippet("", 429))
    }

    @Test fun runtimeMarksRateLimitedSearchPage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html><body><p>搜索间隔【15】秒，请于 17:48:45 后再进行搜索</p></body></html>"))
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "限流", "bookSourceUrl" to server.url("/").toString(),
                "searchUrl" to "search?q={{key}}",
                "ruleSearch" to mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
                "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#c"),
            ))
            val result = CheckSourceRunner.run(CheckSourceRunner.Request(source, searchKey = "斗破苍穹"), runtime(), null)
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertEquals("rate_limited", evidence["搜索"]!!.category)
        }
    }

    // ---------- 搜索/发现空列表：403 CF 挑战 / 520 源站故障不是规则 empty ----------

    private fun emptyListReport(stage: String, lines: List<String>, data: Any? = emptyList<Any>()) =
        LegadoRuntime.DebugReport(stage, "夜跑别在雷雨天", lines, data)

    private fun emptyListEvidence(stage: String, lines: List<String>, data: Any? = emptyList<Any>()) =
        SourceCheckEvidence.of(stage, emptyListReport(stage, lines, data))

    private fun assertNoSecretsInFacts(ev: SourceCheckEvidence.StageEvidence) {
        val blob = ev.facts.entries.joinToString(" ") { "${it.key}=${it.value}" }
        assertFalse("facts 不得记录正文/凭证: $blob", blob.contains("cf_clearance", ignoreCase = true))
        assertFalse("facts 不得记录正文/凭证: $blob", blob.contains("SECRET"))
        assertFalse("facts 不得记录正文/凭证: $blob", blob.contains("<html", ignoreCase = true))
        assertFalse("facts 不得记录正文/凭证: $blob", blob.contains("Just a moment", ignoreCase = true))
        assertTrue("facts 不得含 body/cookie 键: ${ev.facts.keys}", ev.facts.keys.none {
            it.contains("body", true) || it.contains("cookie", true) || it.contains("header", true)
        })
    }

    @Test fun searchHttp403CfChallengeIsVerificationNotEmpty() {
        val lines = listOf(
            "GET https://www.pilishuwu.com/search/0/x/1.html",
            "HTTP 403  cf-mitigated: challenge  title: Just a moment...",
            "set-cookie: cf_clearance=SECRET",
            "<html>challenge body</html>",
            "列表 0 条（内联前 0 条）",
        )
        for (stage in listOf("搜索", "发现")) {
            val ev = emptyListEvidence(stage, lines)
            assertFalse(stage, ev.ok)
            assertEquals(stage, "verification", ev.category)
            assertEquals(stage, 403, ev.facts["statusCode"])
            assertEquals(stage, 0, ev.facts["resultCount"])
            assertTrue(stage, ev.reason!!.contains("HTTP 403"))
            assertTrue(stage, ev.reason!!.contains("挑战") || ev.reason!!.contains("验证"))
            assertTrue(stage, ev.reason!!.contains("沙箱观测不能替代真机阅读结果"))
            assertNoSecretsInFacts(ev)
        }
        val signal = SourceCheckEvidence.listHttpSiteSignal(
            "搜索", listOf("HTTP 403  cf-mitigated: challenge  title: Just a moment..."),
        )
        assertEquals("verification", signal!!.category)
        assertEquals(403, signal.statusCode)
    }

    @Test fun searchHttp520IsNetworkNotEmpty() {
        val lines = listOf(
            "HTTP 520 https://www.pilishuwu.com/search/0/x/1.html",
            "error code: 520",
            "列表 0 条（内联前 0 条）",
        )
        for (stage in listOf("搜索", "发现")) {
            val ev = emptyListEvidence(stage, lines)
            assertFalse(stage, ev.ok)
            assertEquals(stage, "network", ev.category)
            assertEquals(stage, 520, ev.facts["statusCode"])
            assertTrue(stage, ev.reason!!.contains("HTTP 520"))
            assertTrue(stage, ev.reason!!.contains("源站"))
            assertTrue(stage, ev.reason!!.contains("沙箱观测不能替代真机阅读结果"))
            assertNoSecretsInFacts(ev)
        }
        val byErrorCode = emptyListEvidence("搜索", listOf("error code: 520", "列表 0 条"))
        assertEquals("network", byErrorCode.category)
        assertEquals(520, byErrorCode.facts["statusCode"])
        assertEquals("network", SourceCheckEvidence.listHttpSiteSignal("发现", lines)!!.category)
    }

    @Test fun searchHttp200EmptyListStaysEmpty() {
        val ev = emptyListEvidence("搜索", listOf("HTTP 200 https://a.com/search", "列表 0 条（内联前 0 条）"))
        assertFalse(ev.ok)
        assertEquals("empty", ev.category)
        assertEquals(200, ev.facts["statusCode"])
        assertEquals(0, ev.facts["resultCount"])
        assertNull(SourceCheckEvidence.listHttpSiteSignal("搜索", listOf("HTTP 200 https://a.com/search", "列表 0 条")))
    }

    @Test fun searchNon2xxWithoutSiteEvidenceStaysEmpty() {
        for ((code, extra) in listOf(
            403 to "Forbidden",
            404 to "Not Found",
            500 to "Internal Server Error",
            503 to "Service Unavailable",
        )) {
            val ev = emptyListEvidence("搜索", listOf("HTTP $code https://a.com/search", extra, "列表 0 条"))
            assertEquals("HTTP $code 不能仅因非 2xx 改判站点问题", "empty", ev.category)
            assertEquals(code, ev.facts["statusCode"])
            assertNull("HTTP $code", SourceCheckEvidence.listHttpSiteSignal("搜索", listOf("HTTP $code https://a.com/search", extra)))
        }
        val explore404 = emptyListEvidence("发现", listOf("HTTP 404 https://a.com/list", "列表 0 条"))
        assertEquals("empty", explore404.category)
        assertEquals(404, explore404.facts["statusCode"])
    }

    @Test fun searchHttp429WithoutPrefixStaysRateLimited() {
        val ev = emptyListEvidence("搜索", listOf("HTTP 429 https://a.com/search", "列表 0 条"))
        assertFalse(ev.ok)
        assertEquals("rate_limited", ev.category)
        assertEquals(429, ev.facts["statusCode"])
        assertEquals("HTTP 429", ev.facts["rateLimitHint"])
    }

    @Test fun searchNonEmptyListWith403CfLinesStillOk() {
        val ev = emptyListEvidence(
            "搜索",
            listOf("HTTP 403  cf-mitigated: challenge  title: Just a moment...", "列表 1 条（内联前 1 条）"),
            listOf(mapOf("name" to "夜跑别在雷雨天", "bookUrl" to "https://a.com/b")),
        )
        assertTrue("有可用项时不能因 lines 里的 403 改判失败", ev.ok)
        assertEquals(403, ev.facts["statusCode"])
        assertEquals(1, ev.facts["resultCount"])
    }

    @Test fun search403CfChallengeSurvivesGsonRoundtrip() {
        val report = emptyListReport(
            "搜索",
            listOf("HTTP 403  cf-mitigated: challenge  title: Just a moment...", "列表 0 条"),
        )
        @Suppress("UNCHECKED_CAST")
        val roundtrip = Gson().fromJson(Gson().toJson(report), Map::class.java) as Map<*, *>
        val ev = SourceCheckEvidence.of("搜索", roundtrip)
        assertEquals("verification", ev.category)
        assertEquals(false, ev.ok)
        assertEquals(403, ev.facts["statusCode"])
    }

    @Test fun verificationRequiredExceptionOnSearchStaysVerification() {
        val thrown = com.mina.legadostudio.verification.VerificationRequiredException(
            "https://www.pilishuwu.com/search", "www.pilishuwu.com",
            marker = "cf-chl-", code = 403,
        )
        val fromThrowable = SourceCheckEvidence.of("搜索", thrown)
        assertEquals("verification", fromThrowable.category)
        assertFalse(fromThrowable.ok)
        val fromMap = SourceCheckEvidence.of(
            "搜索",
            mapOf("error" to thrown.message, "category" to "verification", "verificationUrl" to thrown.verificationUrl),
        )
        assertEquals("verification", fromMap.category)
        assertFalse(fromMap.ok)
    }

    private fun listOnlySource(server: MockWebServer, explore: Boolean = false): String = Gson().toJson(buildMap {
        put("bookSourceName", "x")
        put("bookSourceUrl", server.url("/").toString())
        put("searchUrl", "search?q={{key}}")
        put("ruleSearch", mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"))
        put("ruleToc", mapOf("chapterList" to "a"))
        put("ruleContent", mapOf("content" to "#c"))
        if (explore) {
            put("exploreUrl", "全部::list")
            put("ruleExplore", mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"))
        }
    })

    @Test fun mockWebServerSearchHttp520IsNetworkNotEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(520).setHeader("content-type", "text/plain; charset=UTF-8").setBody("error code: 520"))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(listOnlySource(server), searchKey = "夜跑别在雷雨天"),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertFalse(evidence["搜索"]!!.ok)
            assertEquals("network", evidence["搜索"]!!.category)
            assertEquals(520, evidence["搜索"]!!.facts["statusCode"])
            assertEquals(0, evidence["搜索"]!!.facts["resultCount"])
            assertEquals("network", (result["verdict"] as Map<*, *>)["failureKind"])
            assertNoSecretsInFacts(evidence["搜索"]!!)
        }
    }

    @Test fun mockWebServerExploreHttp520IsNetworkNotEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(520).setBody("error code: 520"))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(listOnlySource(server, explore = true), exploreUrl = server.url("/list").toString()),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertFalse(evidence["发现"]!!.ok)
            assertEquals("network", evidence["发现"]!!.category)
            assertEquals(520, evidence["发现"]!!.facts["statusCode"])
        }
    }

    @Test fun mockWebServerSearchHttp200EmptyStaysEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html><body><p>无结果</p></body></html>"))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(listOnlySource(server), searchKey = "夜跑别在雷雨天"),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertEquals("empty", evidence["搜索"]!!.category)
            assertEquals(200, evidence["搜索"]!!.facts["statusCode"])
            assertEquals("rule", (result["verdict"] as Map<*, *>)["failureKind"])
        }
    }

    @Test fun mockWebServerSearchHttp404StaysEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(listOnlySource(server), searchKey = "夜跑别在雷雨天"),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertEquals("empty", evidence["搜索"]!!.category)
            assertEquals(404, evidence["搜索"]!!.facts["statusCode"])
        }
    }

    @Test fun mockWebServerSearchHttp403WithoutChallengeStaysEmpty() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(listOnlySource(server), searchKey = "夜跑别在雷雨天"),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertEquals("empty", evidence["搜索"]!!.category)
            assertEquals(403, evidence["搜索"]!!.facts["statusCode"])
        }
    }

    @Test fun mockWebServerSearchHttp403VerificationStillVerification() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody(
                "<html><title>人机验证</title><div>Verify Yourself</div></html>"))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(listOnlySource(server), searchKey = "夜跑别在雷雨天"),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val search = result["搜索"] as Map<String, Any>
            assertTrue(search.containsKey("error"))
            assertEquals("verification", search["category"])
            assertTrue(search.containsKey("verificationUrl"))
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertEquals("verification", evidence["搜索"]!!.category)
            assertFalse(evidence["搜索"]!!.ok)
        }
    }

    @Test fun mockWebServerSearchHttp429StaysRateLimited() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody(""))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(listOnlySource(server), searchKey = "夜跑别在雷雨天"),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertEquals("rate_limited", evidence["搜索"]!!.category)
            assertEquals(429, evidence["搜索"]!!.facts["statusCode"])
        }
    }

    @Test fun mockRuntimeEmptyTocIsNotOk() = runBlocking {
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String) =
                LegadoRuntime.DebugReport("目录", entry, listOf("目录第1页 HTTP 200，0 条", "目录总数 0"), mapOf("totalChapters" to 0, "chapters" to emptyList<Any>()))
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", tocUrl = "https://a.com/t"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse(evidence["目录"]!!.ok)
        assertEquals("empty", evidence["目录"]!!.category)
        assertEquals(0, evidence["目录"]!!.facts["totalChapters"])
    }

    @Test fun mockRuntimeContentZeroCharsIsNotOk() = runBlocking {
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String) =
                LegadoRuntime.DebugReport("正文", entry, listOf("第1页 HTTP 200，正文 0 字符", "合并正文 0 字符"), mapOf("pages" to 1, "content" to "", "contentTotalChars" to 0))
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", contentUrl = "https://a.com/c"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse(evidence["正文"]!!.ok)
        assertEquals("empty", evidence["正文"]!!.category)
        assertEquals(0, evidence["正文"]!!.facts["contentTotalChars"])
    }

    @Test fun unverifiedWhenNoStagesRun() = runBlocking {
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}"""),
            runtime(), null,
        )
        val verdict = result["verdict"] as Map<*, *>
        assertEquals("unverified", verdict["summary"])
        assertEquals(0, verdict["stagesRan"])
    }

    @Test fun partialWhenOnlySomeStagesOk() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/search") == true -> MockResponse().setBody(
                        """<div class="book"><a href="/book/1">书名</a></div>""")
                    request.path == "/book/1" -> MockResponse().setBody("<h1>书名</h1>") // 无 #toc → 详情缺 tocUrl，不算 ok
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = fullChainSource(server), auto = true),
                runtime(), null,
            )
            val verdict = result["verdict"] as Map<*, *>
            assertEquals("partial", verdict["summary"])
            // 详情缺 tocUrl → 详情 !ok，只有搜索 ok；目录/正文未跑
            assertEquals(1, verdict["stagesOk"])
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertTrue(evidence["搜索"]!!.ok)
            assertFalse(evidence["详情"]!!.ok)
            assertNull(evidence["目录"])
            assertNull(evidence["正文"])
        }
    }

    @Test fun evidenceAbsentFromResultWhenNoStages() = runBlocking {
        // 兼容：旧客户端读 Map 不崩，新增键也不影响原有字段
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}"""),
            runtime(), null,
        )
        assertTrue(result["validation"] is BookSourceValidator.Report)
        assertNotNull(result["timingsMs"])
        assertTrue(result["evidence"] is Map<*, *>)
        assertTrue(result["verdict"] is Map<*, *>)
    }

    // ---------- 高危假阳性回归 ----------

    @Test fun searchFirstItemEmptyButLaterUsableIsOk() = runBlocking {
        // 搜索列表首项空（规则误匹配到的占位节点），但第二项有效 → 不能误判为失败
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String) = LegadoRuntime.DebugReport(
                "搜索", entry, listOf("HTTP 200", "列表 2 条（内联前 2 条）"),
                listOf(
                    mapOf("name" to "", "bookUrl" to ""),                       // 首项空
                    mapOf("name" to "有效书名", "bookUrl" to "https://a.com/b"), // 后项有效
                ),
            )
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","searchUrl":"s{{key}}","ruleSearch":{"bookList":".b"},"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", searchKey = "我"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertTrue("首项空、后项有效时搜索必须算 ok", evidence["搜索"]!!.ok)
        assertEquals(1, evidence["搜索"]!!.facts["usableIndex"])
        assertEquals("有效书名", evidence["搜索"]!!.facts["firstUsableName"])
    }

    @Test fun searchAllItemsMissingNameOrUrlIsNotOk() = runBlocking {
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String) = LegadoRuntime.DebugReport(
                "搜索", entry, listOf("HTTP 200", "列表 2 条（内联前 2 条）"),
                listOf(mapOf("name" to "只有名", "bookUrl" to ""), mapOf("name" to "", "bookUrl" to "https://a.com/b")),
            )
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","searchUrl":"s{{key}}","ruleSearch":{"bookList":".b"},"ruleContent":{"content":"#c"}}""", searchKey = "我"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse(evidence["搜索"]!!.ok)
        assertEquals("empty", evidence["搜索"]!!.category)
    }

    @Test fun detailMissingTocUrlIsNotOkForTextSource() = runBlocking {
        // 证据层：文本源详情只有 name 没有 tocUrl → 不足以推进目录，不能算详情 ok。
        // mock runtime 直接交空 tocUrl；显式规则取空的真实 runtime 路径见 autoChainDoesNotFallbackWhenExplicitTocUrlRuleEmpty。
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String) = LegadoRuntime.DebugReport(
                "详情", entry, listOf("HTTP 200", "页面 100 字符"),
                mapOf("name" to "书名", "author" to "作者", "tocUrl" to ""),
            )
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", detailUrl = "https://a.com/b"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse("详情缺 tocUrl 不能算 ok", evidence["详情"]!!.ok)
    }

    @Test fun contentGatePageIsNotOk() = runBlocking {
        // chars>0 但内容是登录页 → 不能虚报「正文可用」
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String) = LegadoRuntime.DebugReport(
                "正文", entry, listOf("第1页 HTTP 200，正文 30 字符", "合并正文 30 字符"),
                mapOf("pages" to 1, "content" to "请先登录后查看本章内容", "contentTotalChars" to 30),
            )
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", contentUrl = "https://a.com/c"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse("登录页内容不能算正文可用", evidence["正文"]!!.ok)
        assertEquals("empty", evidence["正文"]!!.category)
    }

    @Test fun mediaSourceContentWithoutTextIsMediaUnverified() = runBlocking {
        // 音频源（type=1）：正文规则未产出文本也无资源证据 → media_unverified，不是「正文可用」
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String) = LegadoRuntime.DebugReport(
                "正文", entry, listOf("第1页 HTTP 200，正文 0 字符", "合并正文 0 字符"),
                mapOf("pages" to 1, "content" to "", "contentTotalChars" to 0),
            )
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","bookSourceType":1,"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", contentUrl = "https://a.com/c"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse(evidence["正文"]!!.ok)
        assertEquals("media_unverified", evidence["正文"]!!.category)
    }

    @Test fun mediaSourceContentWithResourceUrlIsOk() = runBlocking {
        // 图片源（type=2）：content 规则直出纯资源 URL（整段全是 http(s) token）→ 算正文链路可用
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String) = LegadoRuntime.DebugReport(
                "正文", entry, listOf("第1页 HTTP 200，正文 45 字符", "合并正文 45 字符"),
                mapOf("pages" to 1, "content" to "https://a.com/img/1.jpg", "contentTotalChars" to 45,
                    "mergedBeforeReplace" to "https://a.com/img/1.jpg"),
            )
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","bookSourceType":2,"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", contentUrl = "https://a.com/c"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertTrue("非文本源产出纯资源 URL 时正文链路算可用", evidence["正文"]!!.ok)
        assertEquals("resource", evidence["正文"]!!.facts["mediaEvidence"])
    }

    @Test fun mediaSourceArbitraryTextIsNotMediaEvidence() = runBlocking {
        // 回归：非文本源正文是普通文本（非纯 URL、无二进制记录）→ media_unverified，不造假阳性
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String) = LegadoRuntime.DebugReport(
                "正文", entry, listOf("第1页 HTTP 200，正文 20 字符", "合并正文 20 字符"),
                mapOf("pages" to 1, "content" to "这是一段普通文字不是资源", "contentTotalChars" to 20,
                    "mergedBeforeReplace" to "这是一段普通文字不是资源"),
            )
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","bookSourceType":1,"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", contentUrl = "https://a.com/c"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse(evidence["正文"]!!.ok)
        assertEquals("media_unverified", evidence["正文"]!!.category)
    }

    @Test fun mediaSourceNeverGetsFullVerdict() = runBlocking {
        // 回归：音频源四段全 ok 也封顶 partial——音视频正文无文字级可用性证据
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport = when {
                entry.startsWith("--") -> LegadoRuntime.DebugReport("正文", entry, listOf("二进制资源（当前书源类型：音频），未下载正文"),
                    mapOf("pages" to 1, "content" to "", "contentTotalChars" to 0))
                entry.startsWith("++") -> LegadoRuntime.DebugReport("目录", entry, listOf("目录总数 1"),
                    mapOf("totalChapters" to 1, "chapters" to listOf(mapOf("url" to "https://a.com/c1"))))
                entry.startsWith("http") -> LegadoRuntime.DebugReport("详情", entry, listOf("ok"), mapOf("name" to "书", "tocUrl" to "https://a.com/t"))
                else -> LegadoRuntime.DebugReport("搜索", entry, listOf("ok"), listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b")))
            }
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","bookSourceType":1,"searchUrl":"s{{key}}","ruleSearch":{"bookList":".b"},"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""",
                searchKey = "我", detailUrl = "https://a.com/b", tocUrl = "https://a.com/t", contentUrl = "https://a.com/c"),
            runtime, null,
        )
        val verdict = result["verdict"] as Map<*, *>
        assertEquals(4, verdict["stagesOk"])
        assertEquals("partial", verdict["summary"])
        assertTrue(verdict["note"].toString().contains("封顶"))
    }

    @Test fun fatalErrorPropagatesNotSwallowed() = runBlocking {
        // 回归：StackOverflowError 等 fatal Error 不能吞成 error Map——必须上浮
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
                throw StackOverflowError("模拟规则递归爆栈")
            }
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val ex = runCatching {
            runBlocking {
                CheckSourceRunner.run(
                    CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", detailUrl = "https://a.com/b"),
                    runtime, null,
                )
            }
        }.exceptionOrNull()
        assertTrue("fatal Error 必须上浮，实际=${ex?.javaClass}", ex is StackOverflowError)
    }

    @Test fun fullRequiresValidationOk() = runBlocking {
        // 四段全 ok 但 validation 有 issue → verdict 不能是 full
        val runtime = object : LegadoRuntime {
            var call = 0
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
                call++
                return when {
                    entry.startsWith("--") -> LegadoRuntime.DebugReport("正文", entry, listOf("ok"), mapOf("pages" to 1, "content" to "正文内容", "contentTotalChars" to 4))
                    entry.startsWith("++") -> LegadoRuntime.DebugReport("目录", entry, listOf("ok"), mapOf("totalChapters" to 1, "chapters" to listOf(mapOf("url" to "https://a.com/c1"))))
                    entry.startsWith("http") -> LegadoRuntime.DebugReport("详情", entry, listOf("ok"), mapOf("name" to "书", "tocUrl" to "https://a.com/t"))
                    else -> LegadoRuntime.DebugReport("搜索", entry, listOf("ok"), listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b")))
                }
            }
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            // validation 故意返回 issue：缺 ruleToc.chapterList
            override fun validate(sourceJson: String) = BookSourceValidator().validate("""{"bookSourceName":"x"}""")
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","searchUrl":"s{{key}}","ruleSearch":{"bookList":".b"},"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", searchKey = "我", detailUrl = "https://a.com/b", tocUrl = "https://a.com/t", contentUrl = "https://a.com/c"),
            runtime, null,
        )
        val verdict = result["verdict"] as Map<*, *>
        assertEquals(4, verdict["stagesOk"])
        assertFalse("validation 不通过时不能报 full", "full" == verdict["summary"])
        assertEquals("partial", verdict["summary"])
        assertEquals(false, verdict["validationOk"])
        // 文案只说「有样本证据」，不把四段称成「站点通」
        val note = verdict["note"].toString()
        assertTrue(note, note.contains("四段均有样本证据"))
        assertFalse(note, note.contains("四段全通"))
    }

    @Test fun autoChainDoesNotAdvanceFromFailedReport() = runBlocking {
        // 搜索返回失败 DebugReport（!ok）→ auto 不能从它身上取首项推进详情
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
                // 搜索返回残缺的「失败报告」：首项有 bookUrl 但 name 空 → !ok，不能拿来推进
                if (!entry.startsWith("http") && !entry.startsWith("++") && !entry.startsWith("--")) {
                    return LegadoRuntime.DebugReport("搜索", entry, listOf("HTTP 200", "列表 1 条"),
                        listOf(mapOf("name" to "", "bookUrl" to "https://a.com/b")))
                }
                error("不应推进到后续阶段")
            }
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","searchUrl":"s{{key}}","ruleSearch":{"bookList":".b"},"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", auto = true),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse(evidence["搜索"]!!.ok)
        assertNull("失败搜索不能推进详情", evidence["详情"])
        assertNull(evidence["目录"])
        assertNull(evidence["正文"])
        @Suppress("UNCHECKED_CAST")
        val warnings = result["warnings"] as List<String>
        assertTrue(warnings.any { it.contains("auto") })
    }

    @Test fun stageRuntimeExceptionBecomesErrorMap() = runBlocking {
        // 脚本/规则执行期异常（非取消）必须吞成 {error,category} 而不是让 check_source 整体崩掉
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
                throw RuntimeException("JS 求值失败：test rule 抛出异常")
            }
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", detailUrl = "https://a.com/b"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val detail = result["详情"] as Map<String, Any>
        assertTrue(detail.containsKey("error"))
        assertTrue(detail["error"].toString().contains("JS 求值失败"))
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse(evidence["详情"]!!.ok)
        assertEquals("unusable", (result["verdict"] as Map<*, *>)["summary"])
    }

    @Test fun sandboxOnlyFailuresAreInconclusiveNotUnusable() = runBlocking {
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
                throw RuntimeException("JS 求值失败：找不到函数 foo\n【签名提示】沙箱不支持该签名：java.foo()；属验收环境限制，非书源错误。")
            }
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", detailUrl = "https://a.com/b"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertEquals("sandbox", evidence["详情"]!!.category)
        val verdict = result["verdict"] as Map<*, *>
        assertEquals("inconclusive", verdict["summary"])
        assertEquals("sandbox", verdict["failureKind"])
    }

    @Test fun cancellationDuringAutoChainPropagates() = runBlocking {
        // 取消发生在 auto 推进的中途（详情阶段），CancellationException 必须上浮而不是被吞
        val runtime = object : LegadoRuntime {
            var detailCalls = 0
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
                return when {
                    entry == "我的" -> LegadoRuntime.DebugReport("搜索", entry, listOf("ok"),
                        listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b")))
                    entry.startsWith("http") -> {
                        detailCalls++
                        throw CancellationException("详情阶段被取消")
                    }
                    else -> error("unexpected entry $entry")
                }
            }
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val ex = runCatching {
            runBlocking {
                CheckSourceRunner.run(
                    CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","searchUrl":"s{{key}}","ruleSearch":{"bookList":".b"},"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", auto = true),
                    runtime, null,
                )
            }
        }.exceptionOrNull()
        assertTrue("autoChain 中的 CancellationException 必须上浮，实际=${ex?.javaClass}", ex is CancellationException)
    }

    // ---------- 正文误判修复回归：HTTP 非 2xx / 分页未完整 / 兜底短语 / 整页 HTML / 拦截词误杀 ----------

    private fun contentRuntime(content: String, lines: List<String> = listOf("第1页 HTTP 200，正文 ${content.length} 字符"), extraData: Map<String, Any?> = emptyMap()) = object : LegadoRuntime {
        override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
        override suspend fun debug(sourceJson: String, entry: String) = LegadoRuntime.DebugReport(
            "正文", entry, lines,
            mapOf("pages" to 1, "content" to content, "contentTotalChars" to content.length) + extraData,
        )
        override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
        override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
    }

    private fun runContent(runtime: LegadoRuntime): SourceCheckEvidence.StageEvidence = runBlocking {
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", contentUrl = "https://a.com/c"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        (result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>)["正文"]!!
    }

    @Test fun normalShortContentIsOk() {
        // 正常短正文不因字数少被杀
        val ev = runContent(contentRuntime("他点了点头。"))
        assertTrue(ev.ok)
    }

    @Test fun dialogueWithPayLoginWordsIsNotGate() {
        // 小说对白/正文含充值、验证码、请登录 → 不算拦截页
        assertTrue(runContent(contentRuntime("他说：你要充值才能看。她答：验证码发你了，请登录后再试。")).ok)
        assertTrue("首行以「请登录，」开头但不是明确整段提示，不能误杀",
            runContent(contentRuntime("请登录，这是他给机器下达的第一条指令。\n随后他继续写下后续内容。")).ok)
        assertTrue("「请购买这份报纸」是叙事不是拦截",
            runContent(contentRuntime("请购买这份报纸。她把铜币放在柜台。")).ok)
    }

    @Test fun fixedFallbackMessageIsUnverified() {
        for (msg in listOf("获取失败", "解密失败请重试", "解密失败，请稍后重试。", "内容正在加载", "加载失败", "加载中")) {
            val ev = runContent(contentRuntime(msg))
            assertFalse("'$msg' 整段兜底提示不能算正文可用", ev.ok)
            assertEquals("content_unverified", ev.category)
        }
        // 简单 HTML 片段内提示也算（Jsoup 剥标签后归一化）
        val wrapped = runContent(contentRuntime("<p>获取失败</p>"))
        assertFalse(wrapped.ok)
        assertEquals("content_unverified", wrapped.category)
        // 正常故事含「解密失败」不误杀
        assertTrue(runContent(contentRuntime("解密失败，他却笑了。")).ok)
    }

    @Test fun unavailableContentIsNotSuccessOrRuleFailure() = runBlocking {
        for (sample in listOf("章节内容转码失败", "转码失败，请稍后重试。", "<p>内容转码失败</p>", "??", "？ ? \uFFFD")) {
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""", contentUrl = "https://a.com/c"),
                contentRuntime(sample), null,
            )
            val ev = (result["evidence"] as Map<*, *>)["正文"] as SourceCheckEvidence.StageEvidence
            assertFalse(sample, ev.ok)
            assertEquals("content_unavailable", ev.category)
            assertEquals("inconclusive", (result["verdict"] as Map<*, *>)["summary"])
            assertEquals("content", (result["verdict"] as Map<*, *>)["failureKind"])
            assertEquals(false, RecentCheckOutcomes.get("https://a.com")?.hadFailure)
        }
    }

    @Test fun storyMentioningTranscodeFailureRemainsContent() {
        assertTrue(runContent(contentRuntime("章节内容转码失败，他看着屏幕叹了口气。接着故事继续了。妳在哪里？")).ok)
        assertTrue(runContent(contentRuntime("?他问。?")).ok)
        assertTrue(runContent(contentRuntime("短章。")).ok)
    }

    @Test fun fullHttpChainWithErrorPlaceholderCannotBecomeFull() = runBlocking {
        MockWebServer().use { server ->
            val normal = fullChainDispatcher()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/book/1/1") MockResponse().setBody("<div id='content'>章节内容转码失败</div>")
                    else normal.dispatch(request)
            }
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(fullChainSource(server), searchKey = "测试书名", auto = true),
                runtime(), null,
            )
            assertEquals("partial", (result["verdict"] as Map<*, *>)["summary"])
            assertEquals("content", (result["verdict"] as Map<*, *>)["failureKind"])
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun wholeHtmlContentIsUnverified() {
        for (html in listOf("<!DOCTYPE html><html><body><p>章节正文</p></body></html>",
            "<html lang=\"zh\"><body>x</body></html>", "<!-- c --><html><body>x</body></html>", "\uFEFF  <html><body>x</body></html>")) {
            val ev = runContent(contentRuntime(html))
            assertFalse("$html", ev.ok)
            assertEquals("content_unverified", ev.category)
        }
        assertTrue(runContent(contentRuntime("<p>这就是一段正文。</p>")).ok)
        assertTrue(runContent(contentRuntime("他写下<html>标签，接着解释了网页结构。")).ok)
        assertTrue(runContent(contentRuntime("<htmlish>不是根元素</htmlish>")).ok)
        assertTrue(runContent(contentRuntime("<!DOCTYPE htmlish>不是 HTML 文档声明")).ok)
    }

    @Test fun explicitGateMessageIsEmpty() {
        // 明确整段拦截提示仍判 empty（含新增的验证码/人机验证/Access Denied）
        for (msg in listOf("请先登录后查看本章内容", "请输入验证码", "请完成人机验证", "Access Denied")) {
            val ev = runContent(contentRuntime(msg))
            assertFalse("'$msg' 是明确拦截提示，不能算正文可用", ev.ok)
            assertEquals("empty", ev.category)
        }
    }

    @Test fun httpNon2xxPageIsNetworkFailure() {
        // 有正文但抓取页 HTTP 404（回退 lines）→ network
        val ev = runContent(contentRuntime("残留正文", lines = listOf("第1页 HTTP 404，正文 4 字符")))
        assertFalse(ev.ok)
        assertEquals("network", ev.category)
    }

    @Test fun diagnosticsNon2xxIsNetwork() {
        // contentDiagnostics：先 200 后 500 → network（HTTP 是 loginCheckJs 前真实状态）
        val diag = mapOf("endReason" to "complete", "pages" to listOf(
            mapOf("page" to 1, "httpStatus" to 200, "transport" to "http", "cacheUsed" to false),
            mapOf("page" to 2, "httpStatus" to 500, "transport" to "http", "cacheUsed" to false),
        ))
        val ev = runContent(contentRuntime("第二页挂了", extraData = mapOf("contentDiagnostics" to diag)))
        assertFalse(ev.ok)
        assertEquals("network", ev.category)
    }

    @Test fun cacheFetchReportedNon2xxIsNotOk() {
        // cache_fetch 通道：raw httpStatus=null（无实际观测），reported=500 → 不得 ok/full
        val diag = mapOf("endReason" to "complete", "pages" to listOf(
            mapOf("page" to 1, "httpStatus" to null, "reportedStatus" to 500, "transport" to "cache_fetch", "cacheUsed" to null),
        ))
        val ev = runContent(contentRuntime("缓存正文", extraData = mapOf("contentDiagnostics" to diag)))
        assertFalse("cache_fetch reported 500 不能算可用", ev.ok)
        assertEquals("network", ev.category)
    }

    @Test fun cacheFetchReported200RawUnknownIsOkButNotFabricated() {
        // reported 200 但 raw 未知（null）：可判 ok（内容本身可用），但 facts 不伪造 raw=200
        val diag = mapOf("endReason" to "complete", "pages" to listOf(
            mapOf("page" to 1, "httpStatus" to null, "reportedStatus" to 200, "transport" to "cache_fetch", "cacheUsed" to null),
        ))
        val ev = runContent(contentRuntime("正常正文", extraData = mapOf("contentDiagnostics" to diag)))
        assertTrue(ev.ok)
        assertNull("raw 未知不能回填 200", ev.facts["httpStatusMin"])
        assertNull(ev.facts["httpStatusMax"])
        assertEquals(200, ev.facts["reportedStatusMin"])
    }

    @Test fun cacheFetchReported500SurvivesGsonRoundtripInFilter() {
        // 序列化 roundtrip 后：reported 500 正文经 SourceFilterPolicy 仍判失败
        val stages = mapOf(
            "搜索" to LegadoRuntime.DebugReport("搜索", "我", listOf("ok"), listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b"))),
            "详情" to LegadoRuntime.DebugReport("详情", "u", listOf("ok"), mapOf("name" to "书", "tocUrl" to "https://a.com/t")),
            "目录" to LegadoRuntime.DebugReport("目录", "t", listOf("ok"), mapOf("totalChapters" to 1, "chapters" to listOf(mapOf("url" to "https://a.com/c1")))),
            "正文" to LegadoRuntime.DebugReport("正文", "c", listOf("第1页 HTTP 200，正文 3 字符"),
                mapOf("pages" to 1, "content" to "正文", "contentTotalChars" to 2,
                    "contentDiagnostics" to mapOf("endReason" to "complete", "pages" to listOf(
                        mapOf("page" to 1, "httpStatus" to null, "reportedStatus" to 500, "transport" to "cache_fetch"))))),
        )
        val check = mapOf(
            "搜索" to stages["搜索"]!!, "详情" to stages["详情"]!!, "目录" to stages["目录"]!!, "正文" to stages["正文"]!!,
            "validation" to BookSourceValidator().validate("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}"""),
            "evidence" to stages.mapValues { (s, r) -> SourceCheckEvidence.of(s, r) },
            "verdict" to mapOf("summary" to "partial", "stagesRan" to 4, "stagesOk" to 3,
                "stagesFailed" to listOf("正文"), "validationOk" to true, "validationIssues" to 0),
        )
        val gson = Gson()
        val roundtrip = gson.fromJson(gson.toJson(check), Map::class.java) as Map<String, Any>
        val report = SourceFilterPolicy.filter(
            listOf("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}"""),
            checkResults = mapOf(0 to roundtrip),
            excludes = setOf(SourceFilterPolicy.Exclude.FAILED),
        )
        assertEquals("reported 500 经 roundtrip 仍不能过 FAILED 过滤", 0, report.keptCount)
    }

    @Test fun cycleIsUnverifiedButPageLimitIsOnlySandboxDivergence() {
        val cycle = mapOf("endReason" to "cycle", "pages" to listOf(mapOf("page" to 1, "httpStatus" to 200)))
        val cycleEvidence = runContent(contentRuntime("有一些正文", extraData = mapOf("contentDiagnostics" to cycle)))
        assertFalse("endReason=cycle 不能算可用", cycleEvidence.ok)
        assertEquals("content_unverified", cycleEvidence.category)

        val pageLimit = mapOf("endReason" to "page_limit", "pages" to listOf(mapOf("page" to 1, "httpStatus" to 200)))
        val pageLimitEvidence = runContent(contentRuntime("有一些正文", extraData = mapOf("contentDiagnostics" to pageLimit)))
        assertTrue("page_limit 仅记录沙箱分歧，不判正文失败", pageLimitEvidence.ok)
        assertEquals("sandbox", pageLimitEvidence.category)
        assertEquals("page_limit", pageLimitEvidence.sandboxReason)
    }

    private fun fourStageRuntime(tocData: Map<String, Any?>, tocLines: List<String> = listOf("目录第1页 HTTP 200，1 条", "目录总数 1")) = object : LegadoRuntime {
        override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
        override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport = when {
            entry.startsWith("--") -> LegadoRuntime.DebugReport(
                "正文", entry, listOf("第1页 HTTP 200，正文 4 字符"),
                mapOf("pages" to 1, "content" to "正文内容", "contentTotalChars" to 4),
            )
            entry.startsWith("++") -> LegadoRuntime.DebugReport("目录", entry, tocLines, tocData)
            entry.startsWith("http") -> LegadoRuntime.DebugReport("详情", entry, listOf("ok"), mapOf("name" to "书", "tocUrl" to "https://a.com/t"))
            else -> LegadoRuntime.DebugReport("搜索", entry, listOf("ok"), listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b")))
        }
        override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
        override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
    }

    private val fourStageSource = """{"bookSourceName":"x","bookSourceUrl":"https://a.com","searchUrl":"s{{key}}","ruleSearch":{"bookList":".b"},"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}"""

    @Test fun tocPageLimitFourStagesAreNotFull() = runBlocking {
        val tocData = mapOf(
            "totalChapters" to 100,
            "chapters" to listOf(mapOf("url" to "https://a.com/c1", "name" to "一")),
            "tocDiagnostics" to mapOf(
                "endReason" to "page_limit",
                "pagesFetched" to 100,
                "maxPages" to 100,
                "truncated" to true,
            ),
        )
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request(fourStageSource, searchKey = "我", detailUrl = "https://a.com/b", tocUrl = "https://a.com/t", contentUrl = "https://a.com/c"),
            fourStageRuntime(tocData, listOf("目录第1页 HTTP 200，1 条", "目录分页达到上限 100 页", "目录总数 100")),
            null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertTrue("上限采集有章节，不算书源规则失败", evidence["目录"]!!.ok)
        assertEquals("sandbox", evidence["目录"]!!.category)
        assertEquals("page_limit", evidence["目录"]!!.sandboxReason)
        assertEquals(true, evidence["目录"]!!.facts["tocTruncated"])
        val verdict = result["verdict"] as Map<*, *>
        assertEquals(4, verdict["stagesOk"])
        assertNotEquals("full", verdict["summary"])
        assertEquals("partial", verdict["summary"])
        assertTrue(verdict["note"].toString().contains("不完整") || verdict["note"].toString().contains("上限"))
        assertNull("sandbox 上限不是规则失败", verdict["failureKind"])
    }

    @Test fun tocTwentyEightPagesFourStagesCanBeFull() = runBlocking {
        val tocData = mapOf(
            "totalChapters" to 28,
            "chapters" to listOf(mapOf("url" to "https://a.com/c1", "name" to "一")),
            "tocDiagnostics" to mapOf(
                "endReason" to "complete",
                "pagesFetched" to 28,
                "maxPages" to 100,
                "truncated" to false,
            ),
        )
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request(fourStageSource, searchKey = "我", detailUrl = "https://a.com/b", tocUrl = "https://a.com/t", contentUrl = "https://a.com/c"),
            fourStageRuntime(tocData, listOf("目录第1页 HTTP 200，1 条", "目录总数 28")),
            null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertTrue(evidence["目录"]!!.ok)
        assertNull(evidence["目录"]!!.sandboxReason)
        assertEquals(false, evidence["目录"]!!.facts["tocTruncated"])
        assertEquals("complete", evidence["目录"]!!.facts["tocEndReason"])
        val verdict = result["verdict"] as Map<*, *>
        assertEquals(4, verdict["stagesOk"])
        assertEquals("full", verdict["summary"])
    }

    @Test fun tocPageLimitFallbackFromChineseLogLine() {
        val report = LegadoRuntime.DebugReport(
            "目录", "https://a.com/t",
            listOf("目录第1页 HTTP 200，20 条", "目录分页达到上限 100 页", "目录总数 100"),
            mapOf("totalChapters" to 100, "chapters" to listOf(mapOf("url" to "https://a.com/c1"))),
        )
        val evidence = SourceCheckEvidence.of("目录", report)
        assertTrue(evidence.ok)
        assertEquals("sandbox", evidence.category)
        assertEquals("page_limit", evidence.sandboxReason)
        assertEquals(true, evidence.facts["tocTruncated"])
        assertEquals("page_limit", evidence.facts["tocEndReason"])
    }

    @Test fun completeAndNextChapterDiagnosticsAreNotFailed() {
        // complete/next_chapter 只说明观测分页终止，不证语义完整——但不因此判失败
        for (reason in listOf("complete", "next_chapter")) {
            val diag = mapOf("endReason" to reason, "pages" to listOf(mapOf("page" to 1, "httpStatus" to 200)))
            val ev = runContent(contentRuntime("正常内容", extraData = mapOf("contentDiagnostics" to diag)))
            assertTrue("endReason=$reason 不该判失败", ev.ok)
        }
    }

    @Test fun legacyReportWithoutDiagnosticsIsCompatible() {
        // 旧报告无 contentDiagnostics：diagnosticsAvailable=false，按现有内容判，不伪造 HTTP 成功
        val ev = runContent(contentRuntime("正常正文内容"))
        assertTrue(ev.ok)
        assertEquals(false, ev.facts["diagnosticsAvailable"])
        assertNull(ev.facts["pagesEndReason"])
        assertNull(ev.facts["httpStatusMin"])
        assertNull(ev.facts["httpStatusMax"])
        assertEquals(200, ev.facts["reportedStatusMin"])
    }

    @Test fun diagnosticsFieldsDoNotLeakRawData() {
        // facts 只带有限枚举/计数/状态，不转发 URL/正文/头等原始诊断字段
        val diag = mapOf("endReason" to "complete", "pages" to listOf(
            mapOf("page" to 1, "httpStatus" to 200, "transport" to "webview", "cacheUsed" to true, "url" to "https://a.com/secret")))
        val ev = runContent(contentRuntime("正常", extraData = mapOf("contentDiagnostics" to diag)))
        assertTrue(ev.ok)
        val factKeys = ev.facts.keys
        assertTrue(factKeys.none { it in setOf("url", "content", "headers", "transport", "cacheUsed") })
        assertTrue(ev.facts.values.none { it.toString().contains("a.com/secret") })
        assertTrue(ev.facts.values.none { it.toString().contains("webview") })
    }

    @Test fun failedContentIsNotFullAfterGsonRoundtrip() {
        // 四段全跑但正文是兜底提示 → 序列化 roundtrip 后仍不能判 full
        val stages = mapOf(
            "搜索" to LegadoRuntime.DebugReport("搜索", "我", listOf("ok"), listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b"))),
            "详情" to LegadoRuntime.DebugReport("详情", "u", listOf("ok"), mapOf("name" to "书", "tocUrl" to "https://a.com/t")),
            "目录" to LegadoRuntime.DebugReport("目录", "t", listOf("ok"), mapOf("totalChapters" to 1, "chapters" to listOf(mapOf("url" to "https://a.com/c1")))),
            "正文" to LegadoRuntime.DebugReport("正文", "c", listOf("第1页 HTTP 200，正文 4 字符"), mapOf("pages" to 1, "content" to "获取失败", "contentTotalChars" to 4)),
        )
        val check = mapOf(
            "搜索" to stages["搜索"]!!, "详情" to stages["详情"]!!, "目录" to stages["目录"]!!, "正文" to stages["正文"]!!,
            "validation" to BookSourceValidator().validate("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}"""),
            "evidence" to stages.mapValues { (s, r) -> SourceCheckEvidence.of(s, r) },
            "verdict" to mapOf("summary" to "partial", "stagesRan" to 4, "stagesOk" to 3,
                "stagesFailed" to listOf("正文"), "validationOk" to true, "validationIssues" to 0),
        )
        val gson = Gson()
        val roundtrip = gson.fromJson(gson.toJson(check), Map::class.java) as Map<String, Any>
        val report = SourceFilterPolicy.filter(
            listOf("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}"""),
            checkResults = mapOf(0 to roundtrip),
            excludes = setOf(SourceFilterPolicy.Exclude.FAILED),
        )
        assertEquals("兜底提示正文不能判 full/通过过滤", 0, report.keptCount)
        assertTrue(report.excluded[0].reasons.any { it.contains("正文") })
    }

    @Test fun rhinoDecryptionThrowBecomesErrorNotSwallowed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div>加密内容</div>"))
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "x", "bookSourceUrl" to server.url("/").toString(),
                "ruleToc" to mapOf("chapterList" to "a"),
                "ruleContent" to mapOf("content" to "@js:java.createSymmetricCrypto('AES/ECB/PKCS5Padding','0123456789abcdef').decryptStr('!!!not-base64!!!')"),
            ))
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source, contentUrl = server.url("/chapter").toString()),
                runtime(), null,
            )
            val failure = result["正文"] as Map<*, *>
            assertTrue(failure["error"].toString().contains("decryptStr：密文必须是合法的标准 Base64 字符串"))
            val evidence = result["evidence"] as Map<*, *>
            assertFalse((evidence["正文"] as SourceCheckEvidence.StageEvidence).ok)
            assertNotEquals("full", (result["verdict"] as Map<*, *>)["summary"])
            assertEquals("/chapter", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun fullRuntimeFailuresSurviveRoundtripAndFiltering() = runBlocking {
        for ((code, body) in listOf(200 to "获取失败", 500 to "仍有部分正文")) {
            MockWebServer().use { server ->
                val normal = fullChainDispatcher()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path == "/book/1/1") MockResponse().setResponseCode(code).setBody("<div id='content'>$body</div>")
                        else normal.dispatch(request)
                }
                server.start()
                val source = fullChainSource(server)
                val result = CheckSourceRunner.run(CheckSourceRunner.Request(source, auto = true), runtime(), null)
                val verdict = result["verdict"] as Map<*, *>
                assertEquals("partial", verdict["summary"])
                assertEquals(3, verdict["stagesOk"])
                val gson = Gson()
                val roundtrip = gson.fromJson(gson.toJson(result), Map::class.java)
                val filtered = SourceFilterPolicy.filter(listOf(source), mapOf(0 to roundtrip), setOf(SourceFilterPolicy.Exclude.FAILED))
                assertEquals(0, filtered.keptCount)
                assertTrue(filtered.excluded.single().reasons.any { it.contains("正文") })
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun cleanedContentIgnoresNavigationInPreReplaceSample() {
        val evidence = runContent(contentRuntime("他推开门，屋内一片寂静。", extraData = mapOf("mergedBeforeReplace" to "请先登录后查看本章内容")))
        assertTrue(evidence.ok)
    }

    // ---------- 目录卷名空 URL：用第一个可读章，不把非卷名空链接静默吞掉 ----------

    private fun tocReport(chapters: List<Map<String, Any?>>, total: Int = chapters.size, lines: List<String> = listOf("目录第1页 HTTP 200，${chapters.size} 条", "目录总数 $total")) =
        LegadoRuntime.DebugReport("目录", "https://a.com/t", lines, mapOf("totalChapters" to total, "chapters" to chapters))

    private fun tocEvidence(chapters: List<Map<String, Any?>>, total: Int = chapters.size, lines: List<String> = listOf("目录第1页 HTTP 200，${chapters.size} 条", "目录总数 $total")) =
        SourceCheckEvidence.of("目录", tocReport(chapters, total, lines))

    private fun volumeThenChapters(count: Int = 94): List<Map<String, Any?>> = buildList {
        add(mapOf("name" to "正文", "url" to "", "isVolume" to true))
        repeat(count) { i ->
            add(mapOf("name" to "第${i + 1}章", "url" to "https://a.com/c${i + 1}", "isVolume" to false))
        }
    }

    @Test fun tocVolumeFirstEmptyUrlWithLaterChaptersIsOk() {
        val chapters = volumeThenChapters(94)
        val ev = tocEvidence(chapters)
        assertTrue("卷名空 URL、后面有章链时目录必须 ok", ev.ok)
        assertEquals(95, ev.facts["totalChapters"])
        assertEquals(1, ev.facts["usableChapterIndex"])
        assertEquals("https://a.com/c1", ev.facts["firstUsableChapterUrl"])
        assertTrue(ev.facts["firstChapterUrl"].toString().isBlank())
        assertEquals(1, ev.facts["volumeCount"])
        assertTrue("facts 不得泄露章节正文或章名", ev.facts.values.none { it.toString().contains("第1章") || it.toString().contains("正文内容") })
    }

    @Test fun tocAllItemsWithoutUrlFail() {
        val onlyVolumes = tocEvidence(listOf(
            mapOf("name" to "卷一", "url" to "", "isVolume" to true),
            mapOf("name" to "卷二", "url" to "", "isVolume" to true),
        ))
        assertFalse(onlyVolumes.ok)
        assertEquals("empty", onlyVolumes.category)
        assertTrue(onlyVolumes.reason!!.contains("仅卷名"))
        assertEquals(-1, onlyVolumes.facts["usableChapterIndex"])
        assertEquals("", onlyVolumes.facts["firstUsableChapterUrl"])

        val allEmpty = tocEvidence(listOf(
            mapOf("name" to "章一", "url" to ""),
            mapOf("name" to "章二", "url" to ""),
        ))
        assertFalse(allEmpty.ok)
        assertEquals("empty", allEmpty.category)
        assertTrue(allEmpty.reason!!.contains("非卷名空 url") || allEmpty.reason!!.contains("无可读章节"))
    }

    @Test fun tocFirstEmptyUrlWithoutIsVolumeIsNotSkipped() {
        val chapters = listOf(
            mapOf("name" to "坏首章", "url" to ""),
            mapOf("name" to "第一章", "url" to "https://a.com/c1"),
        )
        val withoutFlag = tocEvidence(chapters)
        assertFalse("没有 isVolume 时不能把空 URL 首项当卷名跳过", withoutFlag.ok)
        assertEquals("empty", withoutFlag.category)
        assertEquals(-1, withoutFlag.facts["usableChapterIndex"])
        assertTrue(withoutFlag.reason!!.contains("非卷名空 url"))

        val explicitFalse = tocEvidence(listOf(
            mapOf("name" to "坏首章", "url" to "", "isVolume" to false),
            mapOf("name" to "第一章", "url" to "https://a.com/c1", "isVolume" to false),
        ))
        assertFalse(explicitFalse.ok)
        assertEquals(-1, explicitFalse.facts["usableChapterIndex"])
    }

    @Test fun tocIsVolumeTrueTextIsOfficialTrue() {
        assertTrue(SourceCheckEvidence.legadoIsTrue("正文"))
        assertTrue(SourceCheckEvidence.legadoIsTrue("true"))
        assertTrue(SourceCheckEvidence.legadoIsTrue(true))
        assertFalse(SourceCheckEvidence.legadoIsTrue(""))
        assertFalse(SourceCheckEvidence.legadoIsTrue("false"))
        assertFalse(SourceCheckEvidence.legadoIsTrue(false))
        val ev = tocEvidence(listOf(
            mapOf("name" to "正文", "url" to "", "isVolume" to "正文"),
            mapOf("name" to "第一章", "url" to "https://a.com/c1"),
        ))
        assertTrue(ev.ok)
        assertEquals(1, ev.facts["usableChapterIndex"])
    }

    @Test fun tocHttp520And403AreNetworkNotRule() {
        for (code in listOf(403, 520)) {
            val ev = tocEvidence(
                emptyList(),
                total = 0,
                lines = listOf("目录第1页 HTTP $code，0 条", "目录总数 0"),
            )
            assertFalse("HTTP $code", ev.ok)
            assertEquals("HTTP $code 不能当规则错", "network", ev.category)
            assertTrue(ev.reason!!.contains("HTTP $code"))
            assertEquals(code, ev.facts["httpCode"])
        }
        assertEquals("network", SourceCheckEvidence.categorizeMessage("HTTP 520 Origin Error"))
        assertEquals("network", SourceCheckEvidence.categorizeMessage("error code: 520"))
        assertEquals("network", SourceCheckEvidence.categorizeMessage("GET x HTTP 403"))
    }

    @Test fun tocVolumeFirstSurvivesGsonRoundtrip() {
        val report = tocReport(volumeThenChapters(3))
        val gson = Gson()
        @Suppress("UNCHECKED_CAST")
        val roundtrip = gson.fromJson(gson.toJson(report), Map::class.java) as Map<*, *>
        val ev = SourceCheckEvidence.of("目录", roundtrip)
        assertTrue(ev.ok)
        assertEquals(1, ev.facts["usableChapterIndex"])
        assertEquals("https://a.com/c1", ev.facts["firstUsableChapterUrl"])
    }

    @Test fun autoChainUsesFirstUsableChapterNotVolume() = runBlocking {
        val entries = mutableListOf<String>()
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
                entries += entry
                return when {
                    entry.startsWith("--") -> {
                        assertTrue("正文入口必须是可读章 URL，实际=$entry", entry.contains("/c1"))
                        LegadoRuntime.DebugReport("正文", entry, listOf("第1页 HTTP 200，正文 4 字符"),
                            mapOf("pages" to 1, "content" to "正文内容", "contentTotalChars" to 4))
                    }
                    entry.startsWith("++") -> tocReport(volumeThenChapters(94))
                    entry.startsWith("http") -> LegadoRuntime.DebugReport("详情", entry, listOf("ok"), mapOf("name" to "书", "tocUrl" to "https://a.com/t"))
                    else -> LegadoRuntime.DebugReport("搜索", entry, listOf("ok"), listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b")))
                }
            }
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(CheckSourceRunner.Request(fourStageSource, auto = true), runtime, null)
        assertEquals("full", (result["verdict"] as Map<*, *>)["summary"])
        assertTrue(entries.any { it == "--https://a.com/c1" })
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertTrue(evidence["目录"]!!.ok)
        assertEquals(1, evidence["目录"]!!.facts["usableChapterIndex"])
        assertEquals("https://a.com/c1", evidence["目录"]!!.facts["firstUsableChapterUrl"])
        assertTrue(evidence["正文"]!!.ok)
    }

    @Test fun autoChainDoesNotAdvanceFromEmptyNonVolumeFirstChapter() = runBlocking {
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
                if (entry.startsWith("--")) error("非卷名空 URL 首项不能推进正文")
                return when {
                    entry.startsWith("++") -> tocReport(listOf(
                        mapOf("name" to "坏首章", "url" to ""),
                        mapOf("name" to "第一章", "url" to "https://a.com/c1"),
                    ))
                    entry.startsWith("http") -> LegadoRuntime.DebugReport("详情", entry, listOf("ok"), mapOf("name" to "书", "tocUrl" to "https://a.com/t"))
                    else -> LegadoRuntime.DebugReport("搜索", entry, listOf("ok"), listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b")))
                }
            }
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(CheckSourceRunner.Request(fourStageSource, auto = true), runtime, null)
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertTrue(evidence["搜索"]!!.ok)
        assertTrue(evidence["详情"]!!.ok)
        assertFalse(evidence["目录"]!!.ok)
        assertNull(evidence["正文"])
        assertEquals("partial", (result["verdict"] as Map<*, *>)["summary"])
    }

    private fun volumeChainSource(server: MockWebServer, withIsVolume: Boolean) = Gson().toJson(mapOf(
        "bookSourceName" to "卷名目录", "bookSourceUrl" to server.url("/").toString(),
        "searchUrl" to "search?q={{key}}",
        "ruleSearch" to mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
        "ruleBookInfo" to mapOf("name" to "h1@text", "tocUrl" to "#toc@href"),
        "ruleToc" to buildMap {
            put("chapterList", "li.item")
            put("chapterName", "a@text||span.vol@text||text")
            put("chapterUrl", "a@href")
            if (withIsVolume) put("isVolume", "span.vol@text")
        },
        "ruleContent" to mapOf("content" to "#content@text"),
    ))

    private fun volumeTocHtml(chapterCount: Int): String {
        val chapters = (1..chapterCount).joinToString("") { n ->
            """<li class="item"><a href="/book/1/$n">第${n}章</a></li>"""
        }
        return """<ul><li class="item"><span class="vol">正文</span></li>$chapters</ul>"""
    }

    private fun volumeChainDispatcher(chapterCount: Int = 94) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path?.startsWith("/search") == true -> MockResponse().setBody(
                """<div class="book"><a href="/book/1">测试书名</a></div>""")
            request.path == "/book/1" -> MockResponse().setBody(
                """<h1>测试书名</h1><a id="toc" href="/book/1/toc">目录</a>""")
            request.path == "/book/1/toc" -> MockResponse().setBody(volumeTocHtml(chapterCount))
            request.path?.matches(Regex("/book/1/\\d+")) == true -> MockResponse().setBody(
                """<div id="content">正文内容正文内容</div>""")
            else -> MockResponse().setResponseCode(404)
        }
    }

    @Test fun autoChainVolumeFirstNinetyFourChaptersWithIsVolumeIsFull() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = volumeChainDispatcher(94)
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = volumeChainSource(server, withIsVolume = true), auto = true),
                runtime(), null,
            )
            val verdict = result["verdict"] as Map<*, *>
            assertEquals("full", verdict["summary"])
            assertEquals(4, verdict["stagesOk"])
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertTrue(evidence["目录"]!!.ok)
            assertEquals(95, evidence["目录"]!!.facts["totalChapters"])
            assertEquals(1, evidence["目录"]!!.facts["usableChapterIndex"])
            val usableUrl = evidence["目录"]!!.facts["firstUsableChapterUrl"].toString()
            assertTrue("可用章应是第一章，实际=$usableUrl", usableUrl.contains("/book/1/1"))
            assertTrue(evidence["目录"]!!.facts["firstChapterUrl"].toString().isBlank())
            assertTrue(evidence["正文"]!!.ok)
            val content = result["正文"] as LegadoRuntime.DebugReport
            assertTrue("正文入口不得用卷名空 URL，实际=${content.entry}", content.entry.contains("/book/1/1"))
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun autoChainVolumeFirstWithoutIsVolumeDoesNotSwallowEmptyFirst() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = volumeChainDispatcher(94)
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = volumeChainSource(server, withIsVolume = false), auto = true),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertTrue(evidence["搜索"]!!.ok)
            assertTrue(evidence["详情"]!!.ok)
            assertFalse("无 isVolume 时不能把空 URL 首项当卷名跳过", evidence["目录"]!!.ok)
            assertEquals("empty", evidence["目录"]!!.category)
            assertNull(evidence["正文"])
            assertEquals("partial", (result["verdict"] as Map<*, *>)["summary"])
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun mockWebServerAllChapterUrlsEmptyFails() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/toc" -> MockResponse().setBody(
                        """<ul><li class="item"><span class="vol">卷一</span></li><li class="item"><span class="vol">卷二</span></li></ul>""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "仅卷名", "bookSourceUrl" to server.url("/").toString(),
                "ruleToc" to mapOf(
                    "chapterList" to "li.item",
                    "chapterName" to "span.vol@text||text",
                    "chapterUrl" to "a@href",
                    "isVolume" to "span.vol@text",
                ),
                "ruleContent" to mapOf("content" to "#content@text"),
            ))
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source, tocUrl = server.url("/toc").toString()),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertFalse(evidence["目录"]!!.ok)
            assertEquals("empty", evidence["目录"]!!.category)
            assertTrue(evidence["目录"]!!.reason!!.contains("仅卷名") || evidence["目录"]!!.reason!!.contains("无可读章节"))
            assertNull(evidence["正文"])
        }
    }

    @Test fun mockWebServerFirstBrokenUrlNotVolumeFailsEvenWithLaterChapters() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/toc" -> MockResponse().setBody(
                        """<ul><li class="item"><span>无链接章</span></li><li class="item"><a href="/book/1/1">第一章</a></li></ul>""")
                    request.path == "/book/1/1" -> MockResponse().setBody("""<div id="content">正文内容正文内容</div>""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val source = Gson().toJson(mapOf(
                "bookSourceName" to "坏首章", "bookSourceUrl" to server.url("/").toString(),
                "ruleToc" to mapOf(
                    "chapterList" to "li.item",
                    "chapterName" to "a@text||text",
                    "chapterUrl" to "a@href",
                ),
                "ruleContent" to mapOf("content" to "#content@text"),
            ))
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source, tocUrl = server.url("/toc").toString(), auto = true),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            assertFalse(evidence["目录"]!!.ok)
            assertEquals("empty", evidence["目录"]!!.category)
            assertNull("非卷名空 URL 不能推进正文", evidence["正文"])
            assertEquals(1, server.requestCount)
        }
    }

    // ---------- CF 挑战 / js执行超时 / 正文 JS 回退原始响应（书海阁、霹雳书屋） ----------

    @Test fun categorizeMessagePrefersCfChallengeOverHttp403Network() {
        assertEquals("verification", SourceCheckEvidence.categorizeMessage("HTTP 403  cf-mitigated: challenge  title: Just a moment..."))
        assertEquals("verification", SourceCheckEvidence.categorizeMessage("cf-mitigated: challenge"))
        assertEquals("verification", SourceCheckEvidence.categorizeMessage("Just a moment..."))
        assertEquals("verification", SourceCheckEvidence.categorizeMessage("GET x cf_chl_opt=x"))
        assertEquals("verification", SourceCheckEvidence.categorizeMessage("script /cdn-cgi/challenge-platform/scripts/jsd/main.js"))
        assertEquals("network", SourceCheckEvidence.categorizeMessage("GET x HTTP 403"))
        assertEquals("network", SourceCheckEvidence.categorizeMessage("error code: 520"))
        assertEquals("sandbox", SourceCheckEvidence.categorizeMessage("js执行超时"))
        assertEquals("webjs_timeout", SourceCheckEvidence.sandboxReason("js执行超时"))
        assertTrue(SourceCheckEvidence.isSandboxLimit("js执行超时", null))
    }

    @Test fun searchHttp200CfChallengeIsVerificationNotEmpty() {
        for (marker in listOf(
            "title: Just a moment...",
            "cf-mitigated: challenge",
            "cf_chl_rc_i=1",
            "/cdn-cgi/challenge-platform/scripts/jsd/main.js",
        )) {
            val ev = emptyListEvidence("搜索", listOf("HTTP 200 https://a.com/search", marker, "列表 0 条"))
            assertFalse(marker, ev.ok)
            assertEquals(marker, "verification", ev.category)
            assertEquals(marker, 200, ev.facts["statusCode"])
            assertTrue(marker, ev.reason!!.contains("挑战") || ev.reason!!.contains("验证"))
        }
        assertEquals(
            "verification",
            SourceCheckEvidence.listHttpSiteSignal("搜索", listOf("HTTP 200 x", "Just a moment..."))!!.category,
        )
        assertNull(
            "裸 403 仍不能仅因非 2xx 改判",
            SourceCheckEvidence.listHttpSiteSignal("搜索", listOf("HTTP 403 https://a.com/search", "Forbidden")),
        )
    }

    @Test fun jsTimeoutInLinesIsNotStageSuccess() = runBlocking {
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String) = LegadoRuntime.DebugReport(
                "搜索", entry, listOf("HTTP 200", "js执行超时", "列表 1 条"),
                listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b")),
            )
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request(searchOnlySource, searchKey = "南北鸣"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse("js执行超时不能视正常", evidence["搜索"]!!.ok)
        assertEquals("sandbox", evidence["搜索"]!!.category)
        assertEquals("webjs_timeout", evidence["搜索"]!!.sandboxReason)
        val verdict = result["verdict"] as Map<*, *>
        assertEquals("inconclusive", verdict["summary"])
        assertEquals("sandbox", verdict["failureKind"])
        assertTrue(verdict["note"].toString().contains("js执行超时"))
        assertNotEquals("full", verdict["summary"])
    }

    @Test fun jsTimeoutInJsLogsSurvivesGsonRoundtrip() {
        val report = LegadoRuntime.DebugReport(
            "搜索", "南北鸣", listOf("HTTP 200", "列表 0 条"),
            emptyList<Map<String, String>>(),
            jsLogs = listOf("js执行超时"),
        )
        val direct = SourceCheckEvidence.of("搜索", report)
        assertFalse(direct.ok)
        assertEquals("sandbox", direct.category)
        assertEquals("webjs_timeout", direct.sandboxReason)
        @Suppress("UNCHECKED_CAST")
        val roundtrip = Gson().fromJson(Gson().toJson(report), Map::class.java) as Map<*, *>
        val ev = SourceCheckEvidence.of("搜索", roundtrip)
        assertEquals("sandbox", ev.category)
        assertFalse(ev.ok)
    }

    @Test fun contentFallbackErrorCode520IsNotValidContent() {
        val ev = runContent(contentRuntime("error code: 520"))
        assertFalse(ev.ok)
        assertEquals("network", ev.category)
        assertEquals("error_code_520", ev.facts["contentSuspect"])
        assertTrue(ev.reason!!.contains("contentSuspect=error_code_520"))
        assertEquals("error_code_520", SourceCheckEvidence.contentSuspect("error code: 520"))
        assertEquals("error_code_520", SourceCheckEvidence.contentSuspect("  error code: 520 \n"))
        assertEquals("error_code_520", SourceCheckEvidence.contentSuspect("<p>error code: 520</p>"))
    }

    @Test fun contentFallbackJsonPackageIsNotValidContent() {
        val json = """{"code":200,"data":{"chapter":{"content":["抱歉，我来迟了。"]}},"msg":"ok"}"""
        val ev = runContent(contentRuntime(json))
        assertFalse(ev.ok)
        assertEquals("content_unverified", ev.category)
        assertEquals("json_package", ev.facts["contentSuspect"])
        assertTrue(ev.reason!!.contains("contentSuspect=json_package"))
        assertEquals("json_package", SourceCheckEvidence.contentSuspect(json))
        assertEquals("json_package", SourceCheckEvidence.contentSuspect("""{"msg":"请求无效"}"""))
        assertEquals("json_package", SourceCheckEvidence.contentSuspect("""[{"p":"a"},{"p":"b"}]"""))
        assertTrue(SourceCheckEvidence.looksLikeCompleteJsonPackage("{\n  \"code\": 400\n}"))
    }

    @Test fun contentFallbackCfChallengeIsVerificationNotFull() {
        val html = """<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>
            <script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script>
            </body></html>"""
        val ev = runContent(contentRuntime(html))
        assertFalse(ev.ok)
        assertEquals("verification", ev.category)
        assertEquals("cf_challenge", ev.facts["contentSuspect"])
        assertEquals("cf_challenge", SourceCheckEvidence.contentSuspect(html))
        assertEquals("cf_challenge", SourceCheckEvidence.contentSuspect("Just a moment..."))
        assertEquals("cf_challenge", SourceCheckEvidence.contentSuspect("cf-mitigated: challenge"))
        assertEquals("cf_challenge", SourceCheckEvidence.contentSuspect("set-cookie: cf_chl_opt=1; path=/"))
    }

    @Test fun novelWith520OrBracesIsNotContentSuspect() {
        val samples = listOf(
            "他住在520号房间，门牌上的数字被雨淋花了。",
            "她说：房间是520，今晚别来。他点了点头。",
            "error code: 520 是他随口编的暗号，然后推开门走进雨里。",
            "他写下{请勿打扰}，把纸条贴在520的门上。",
            "Just a moment, he thought, then walked into room 520 and closed the door behind him.",
            "{他说：这不可能。}她却笑了，把花括号写进日记。",
            "他收到了 {ok} 两个字，心里一空。",
        )
        for (sample in samples) {
            assertNull("不得误伤小说正文: $sample", SourceCheckEvidence.contentSuspect(sample))
            val ev = runContent(contentRuntime(sample))
            assertTrue("小说正文必须仍算可用: $sample", ev.ok)
            assertNull(ev.facts["contentSuspect"])
        }
        assertFalse("残缺花括号不是 JSON 包", SourceCheckEvidence.looksLikeCompleteJsonPackage("{他说：520}"))
        assertNull(SourceCheckEvidence.contentSuspect("共403章，错误520次后他放弃了。"))
    }

    @Test fun businessPageWithChallengePlatformFooterIsNotCfChallenge() {
        val chapter = """<!DOCTYPE html><html><body>
            <div id="nr1">懿丰三十年二月。风过林梢，裴渊在榻上猛然睁开眼睛。</div>
            <script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script>
            </body></html>"""
        assertNull("业务页页尾 challenge-platform 不能当挑战页", SourceCheckEvidence.contentSuspect(chapter))
        val ev = runContent(contentRuntime(chapter))
        assertFalse("整页 HTML 仍不能当正文", ev.ok)
        assertEquals("content_unverified", ev.category)
        assertNull(ev.facts["contentSuspect"])
    }

    @Test fun contentSuspect520FourStagesAreNotFull() = runBlocking {
        val runtime = object : LegadoRuntime {
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport = when {
                entry.startsWith("--") -> LegadoRuntime.DebugReport(
                    "正文", entry, listOf("第1页 HTTP 200，正文 16 字符"),
                    mapOf("pages" to 1, "content" to "error code: 520", "contentTotalChars" to 16),
                )
                entry.startsWith("++") -> LegadoRuntime.DebugReport("目录", entry, listOf("ok"), mapOf("totalChapters" to 1, "chapters" to listOf(mapOf("url" to "https://a.com/c1"))))
                entry.startsWith("http") -> LegadoRuntime.DebugReport("详情", entry, listOf("ok"), mapOf("name" to "书", "tocUrl" to "https://a.com/t"))
                else -> LegadoRuntime.DebugReport("搜索", entry, listOf("ok"), listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b")))
            }
            override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
            override fun validate(sourceJson: String) = BookSourceValidator().validate(sourceJson)
        }
        val result = CheckSourceRunner.run(
            CheckSourceRunner.Request(fourStageSource, searchKey = "我", detailUrl = "https://a.com/b", tocUrl = "https://a.com/t", contentUrl = "https://a.com/c"),
            runtime, null,
        )
        @Suppress("UNCHECKED_CAST")
        val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        assertFalse(evidence["正文"]!!.ok)
        assertEquals("error_code_520", evidence["正文"]!!.facts["contentSuspect"])
        val verdict = result["verdict"] as Map<*, *>
        assertNotEquals("full", verdict["summary"])
        assertEquals("network", verdict["failureKind"])
    }

    @Test fun contentSuspectJsonRoundtripIsNotFull() {
        val stages = mapOf(
            "搜索" to LegadoRuntime.DebugReport("搜索", "我", listOf("ok"), listOf(mapOf("name" to "书", "bookUrl" to "https://a.com/b"))),
            "详情" to LegadoRuntime.DebugReport("详情", "u", listOf("ok"), mapOf("name" to "书", "tocUrl" to "https://a.com/t")),
            "目录" to LegadoRuntime.DebugReport("目录", "t", listOf("ok"), mapOf("totalChapters" to 1, "chapters" to listOf(mapOf("url" to "https://a.com/c1")))),
            "正文" to LegadoRuntime.DebugReport("正文", "c", listOf("第1页 HTTP 200，正文 40 字符"),
                mapOf("pages" to 1, "content" to """{"code":200,"data":{"content":"x"}}""", "contentTotalChars" to 40)),
        )
        val check = mapOf(
            "搜索" to stages["搜索"]!!, "详情" to stages["详情"]!!, "目录" to stages["目录"]!!, "正文" to stages["正文"]!!,
            "validation" to BookSourceValidator().validate("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}"""),
            "evidence" to stages.mapValues { (s, r) -> SourceCheckEvidence.of(s, r) },
            "verdict" to mapOf("summary" to "partial", "stagesRan" to 4, "stagesOk" to 3,
                "stagesFailed" to listOf("正文"), "validationOk" to true, "validationIssues" to 0),
        )
        val gson = Gson()
        val roundtrip = gson.fromJson(gson.toJson(check), Map::class.java) as Map<String, Any>
        val report = SourceFilterPolicy.filter(
            listOf("""{"bookSourceName":"x","bookSourceUrl":"https://a.com","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}"""),
            checkResults = mapOf(0 to roundtrip),
            excludes = setOf(SourceFilterPolicy.Exclude.FAILED),
        )
        assertEquals("完整 JSON 包不能判 full/通过过滤", 0, report.keptCount)
        @Suppress("UNCHECKED_CAST")
        val evidence = roundtrip["evidence"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val content = evidence["正文"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        assertEquals("json_package", (content["facts"] as Map<String, Any>)["contentSuspect"])
    }

    // ---------- release JSON 字段契约（StageEvidence 经 Gson 出包的键名） ----------

    @Test fun stageEvidenceSerializesWithStableJsonKeys() {
        // R2 事故回归：release 混淆把字段改成 a..g，回包出现 e/d/f 而非 category/reason/facts。
        // 字段名以 @SerializedName 钉死，键名必须与源码一致——混淆后也要逐个相同。
        val evidence = SourceCheckEvidence.StageEvidence(
            stage = "搜索", ran = true, ok = false,
            reason = "列表 0 条", category = "empty",
            facts = mapOf("resultCount" to 0), sandboxReason = "missing_java_api",
        )
        @Suppress("UNCHECKED_CAST")
        val json = Gson().fromJson(Gson().toJson(evidence), Map::class.java) as Map<String, Any>
        for (key in listOf("stage", "ran", "ok", "reason", "category", "facts", "sandboxReason")) {
            assertTrue("evidence JSON 缺字段键 $key：实际键=${json.keys}", json.containsKey(key))
        }
        assertEquals("搜索", json["stage"])
        assertEquals(true, json["ran"])
        assertEquals(false, json["ok"])
        assertEquals("列表 0 条", json["reason"])
        assertEquals("empty", json["category"])
        assertEquals("missing_java_api", json["sandboxReason"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(0.0, (json["facts"] as Map<String, Any>)["resultCount"])
    }

    @Test fun evidenceMapSerializesWithStageKeysAfterRunner() = runBlocking {
        // 端到端键名契约：check_source 回包 evidence 的每层键——阶段名在内层、
        // 结构化字段在外层——经 Gson 序列化后都不漂移（模拟 MCP ok(gson.toJson(results)) 路径）。
        val runtime = searchOnlyRuntime(listOf("HTTP 200 x", "列表 0 条（内联前 0 条）"))
        val result = CheckSourceRunner.run(CheckSourceRunner.Request(searchOnlySource, searchKey = "斗破苍穹"), runtime, null)
        val gson = Gson()
        @Suppress("UNCHECKED_CAST")
        val roundtrip = gson.fromJson(gson.toJson(result), Map::class.java) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val evidence = roundtrip["evidence"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val search = evidence["搜索"] as Map<String, Any>
        for (key in listOf("stage", "ran", "ok", "reason", "category", "facts")) {
            assertTrue("evidence.搜索 缺字段键 $key：实际键=${search.keys}", search.containsKey(key))
        }
        assertEquals("empty", search["category"])
        assertEquals(true, search["ran"])
        assertEquals(false, search["ok"])
        // 混淆出的单字母键绝不能出现在回包里（a..g 是 R8 给 StageEvidence 的历史混淆名）
        for (bad in listOf("a", "b", "c", "d", "e", "f", "g")) {
            assertFalse("evidence.搜索 出现混淆键 $bad", search.containsKey(bad))
        }
    }
}
