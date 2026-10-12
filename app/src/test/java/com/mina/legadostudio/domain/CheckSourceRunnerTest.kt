package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.LegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** check_source 编排层回归：阶段 timingsMs、warnings、失败阶段结构化 error、200 挑战页不静默。 */
class CheckSourceRunnerTest {

    private fun runtime() = EmbeddedLegadoRuntime(
        HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()),
    )

    private fun minimalSource(server: MockWebServer, extra: Map<String, Any?> = emptyMap()) = Gson().toJson(
        mapOf(
            "bookSourceName" to "测试",
            "bookSourceUrl" to server.url("/").toString(),
            "ruleToc" to mapOf("chapterList" to "a"),
            "ruleContent" to mapOf("content" to "#content@html"),
        ) + extra
    )

    @Test fun validSourceProducesStageResultsAndTimings() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<h1>详情页</h1>"))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = minimalSource(server), detailUrl = server.url("/book/1").toString()),
                runtime(), cacheFetch = null,
            )
            assertTrue(result["validation"] is BookSourceValidator.Report)
            assertTrue(result["详情"] is LegadoRuntime.DebugReport)
            @Suppress("UNCHECKED_CAST")
            val timings = result["timingsMs"] as Map<String, Long>
            // 阶段+总计时都存在且非负（含 validation、详情、total）
            assertTrue(timings.containsKey("validation"))
            assertTrue(timings.containsKey("详情"))
            assertTrue(timings.containsKey("total"))
            assertTrue(timings["total"]!! >= 0)
        }
    }

    @Test fun searchKeyAutoProbeAddsWarningAndStage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/1'>书名</a></div>"))
            server.start()
            val source = Gson().toJson(
                mapOf(
                    "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                    "searchUrl" to "search?q={{key}}",
                    "ruleSearch" to mapOf("bookList" to ".book", "name" to "a@text"),
                    "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#content@html"),
                )
            )
            val result = CheckSourceRunner.run(CheckSourceRunner.Request(source), runtime(), null)
            @Suppress("UNCHECKED_CAST")
            val warnings = result["warnings"] as List<String>
            assertTrue(warnings.any { it.contains("未传 searchKey") })
            assertTrue(result.containsKey("搜索"))
        }
    }

    @Test fun failingStageRecordsErrorAndTiming() = runBlocking {
        MockWebServer().use { server ->
            // 详情页 200 挑战页：详情阶段必须记 error（不被吞成静默空结果），timingsMs 仍有该阶段
            server.enqueue(MockResponse().setBody(
                """<div>内容正在载入……</div><script>var c2="x";</script><script src="/nnxswnn/a.js"></script>"""
            ))
            server.start()
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = minimalSource(server), detailUrl = server.url("/book/1").toString()),
                runtime(), null,
            )
            @Suppress("UNCHECKED_CAST")
            val detail = result["详情"] as Map<String, Any>
            assertTrue("expected error field: $detail", detail.containsKey("error"))
            assertTrue(detail["error"].toString().contains("挑战") || detail["error"].toString().contains("JS_CHALLENGE"))
            @Suppress("UNCHECKED_CAST")
            val timings = result["timingsMs"] as Map<String, Long>
            assertTrue("failed stage must still be timed", timings.containsKey("详情"))
        }
    }

    @Test fun exploreStageRunsAndFeedsEvidenceButNotCoreVerdict() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/1'>书名</a></div>"))
            server.start()
            val source = Gson().toJson(
                mapOf(
                    "bookSourceName" to "测试", "bookSourceUrl" to server.url("/").toString(),
                    "exploreUrl" to "explore?page={{page}}",
                    "ruleExplore" to mapOf("bookList" to ".book", "name" to "a@text", "bookUrl" to "a@href"),
                    "ruleToc" to mapOf("chapterList" to "a"), "ruleContent" to mapOf("content" to "#content@html"),
                )
            )
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source, exploreKey = "玄幻", exploreUrl = server.url("/explore").toString()),
                runtime(), null,
            )
            // 发现阶段跑过且是 DebugReport
            assertTrue(result["发现"] is LegadoRuntime.DebugReport)
            @Suppress("UNCHECKED_CAST")
            val evidence = result["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
            // 发现进 evidence 展示
            assertTrue(evidence.containsKey("发现"))
            // 但 verdict 只按核心四段计（无搜索/详情/目录/正文 → unverified，发现不救场）
            @Suppress("UNCHECKED_CAST")
            val verdict = result["verdict"] as Map<String, Any>
            assertEquals("unverified", verdict["summary"])
            assertEquals(0, verdict["stagesOk"])
        }
    }

    @Test fun malformedSourceThrowsUnifiedJsonHint() {
        val error = runCatching {
            runBlocking { CheckSourceRunner.run(CheckSourceRunner.Request("{"), runtime(), null) }
        }.exceptionOrNull()
        assertTrue(error != null)
        assertTrue(error!!.message.orEmpty().contains("JSON"))
    }
}
