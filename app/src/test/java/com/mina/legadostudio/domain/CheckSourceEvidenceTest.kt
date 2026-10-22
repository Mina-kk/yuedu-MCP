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

    @Test fun cycleAndPageLimitAreUnverified() {
        for (reason in listOf("cycle", "page_limit")) {
            val diag = mapOf("endReason" to reason, "pages" to listOf(mapOf("page" to 1, "httpStatus" to 200)))
            val ev = runContent(contentRuntime("有一些正文", extraData = mapOf("contentDiagnostics" to diag)))
            assertFalse("endReason=$reason 不能算可用", ev.ok)
            assertEquals("content_unverified", ev.category)
        }
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
}
