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
            // 搜索失败 → 后续阶段不应出现（没有入口可推进）
            assertNull(evidence["详情"])
            assertNull(evidence["目录"])
            assertNull(evidence["正文"])
            assertEquals("unusable", (result["verdict"] as Map<*, *>)["summary"])
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
        // 文本源详情只有 name 没有 tocUrl → 不足以推进目录，不能算详情 ok
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

    @Test fun cancellationDuringAutoChainPropagates() = runBlocking {
        // 取消发生在 auto 推进的中途（详情阶段），CancellationException 必须上浮而不是被吞
        val runtime = object : LegadoRuntime {
            var detailCalls = 0
            override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
            override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
                return when {
                    entry == "我" -> LegadoRuntime.DebugReport("搜索", entry, listOf("ok"),
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
}
