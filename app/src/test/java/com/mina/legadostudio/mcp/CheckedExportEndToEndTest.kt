package com.mina.legadostudio.mcp

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.mina.legadostudio.domain.BookSourceValidator
import com.mina.legadostudio.domain.CheckSourceRunner
import com.mina.legadostudio.domain.SourceFilterPolicy
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * check_source 真实链路 → CheckEvidenceRegistry → SourceFilterPolicy → buildExport 端到端：
 *
 * - 用 MockWebServer 本机假站点跑真实 `CheckSourceRunner.run(auto=true)`，
 *   搜索→详情→目录→正文四段全通后 verdict=full（非 mock runtime、非手造 checkResults）；
 * - 把**当次原始结果**登记进 `McpSourceTools.CheckEvidenceRegistry`
 *   （模拟 StudioMcpServer 在 check_source 成功后的登记行为），再经
 *   `trustedCheckResults` 取回喂给 `SourceFilterPolicy.filter(excludeUnverified=true)`；
 * - 断言：真跑通的源被导出，同批另一个**从未登记**的源被排除——
 *   「未验证」不会被放行，外部自述不参与 verified 判定（这里根本不传自述）；
 * - 导出文本可被 Gson 按 Legado 书源数组形态解析，且数组成员与登记的原始
 *   sourceJson **逐字节一致**（原文拼接保真，不经过重序列化）。
 *
 * 不宣称真机 Legado / 生产 MCP 服务行为：本测试只覆盖本工程内
 * runner→registry→filter→export 的 JVM 链路。
 */
class CheckedExportEndToEndTest {

    private fun runtime() = EmbeddedLegadoRuntime(
        HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson()),
    )

    /** 能跑通搜索→详情→目录→正文全链路的 MockWebServer 书源（fixture 与 CheckSourceEvidenceTest 同风格）。 */
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

    @Test fun realCheckThenRegistryThenFilterThenExportKeepsOnlyCheckedSource() = runBlocking {
        McpSourceTools.CheckEvidenceRegistry.clear()
        MockWebServer().use { server ->
            server.dispatcher = fullChainDispatcher()
            server.start()

            // 1) 真实 check_source 全链路：auto=true 搜索→详情→目录→正文
            val checkedJson = fullChainSource(server)
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(source = checkedJson, auto = true),
                runtime(), cacheFetch = null,
            )
            val verdict = result["verdict"] as Map<*, *>
            assertEquals("真实链路四段应全通为 full（实际=$verdict）", "full", verdict["summary"])

            // 2) 登记当次原始结果（与 StudioMcpServer 对 check_source 成功后的登记口径一致）
            val now = System.currentTimeMillis()
            val record = McpSourceTools.CheckEvidenceRegistry.register(
                checkedJson, result, refresh = true, now = now,
            )
            assertNotNull("四段结果应登记成功", record)

            // 3) 同批第二个源从未跑过 check → trustedCheckResults 只命中 index 0
            val uncheckedJson = Gson().toJson(mapOf(
                "bookSourceName" to "未检", "bookSourceUrl" to server.url("/").toString(),
                "ruleToc" to mapOf("chapterList" to "a"),
                "ruleContent" to mapOf("content" to "#content@html"),
            ))
            val sources = listOf(checkedJson, uncheckedJson)
            val trusted = McpSourceTools.trustedCheckResults(sources, now)
            assertEquals("只有跑过 check_source 的源才有可信证据", setOf(0), trusted.keys)

            // 4) excludeUnverified：可信证据驱动过滤，未检查源必须被排除（低误判：未验证不放行）
            val report = SourceFilterPolicy.filter(
                sources, trusted, setOf(SourceFilterPolicy.Exclude.UNVERIFIED))
            assertEquals(1, report.keptCount)
            assertEquals(0, report.kept[0].index)
            // 注册表回传的是阶段原文（无 verdict/evidence 自述摘要）→ checkVerified=false，
            // 但证据是经 SourceCheckEvidence.of 独立复算的真 DebugReport，四段全部 ran&&ok
            assertTrue("evidence=" + report.kept[0].evidence?.mapValues { "${it.value.ran}/${it.value.ok}/${it.value.reason}" },
                report.kept[0].evidence!!.values.count { it.ran && it.ok } == 4)
            assertEquals(1, report.excludedCount)
            assertEquals(1, report.excluded[0].index)
            assertTrue(report.excluded[0].reasons.any { it.contains("未运行 check_source") })

            // 5) buildExport：只导出被保留的源
            val (text, summary, err) = McpSourceTools.buildExport(
                report.kept.map { McpSourceTools.ExportRow("p${it.index}", "u${it.index}", sources[it.index], 0L) },
                collapseIdentical = false, asArray = true,
            )
            assertNull(err)
            assertEquals(1, summary["exported"])

            // 6) 导出 JSON 可被 Legado/Gson 解析为书源数组，且成员是登记的 sourceJson 原文
            //    （buildExport 承诺逐字节拼接原文：导出文本必含 checkedJson 整段，且不含 uncheckedJson）
            val arr = JsonParser.parseString(text!!).asJsonArray
            assertEquals(1, arr.size())
            val exported = arr[0].asJsonObject
            assertEquals("全链路", exported.get("bookSourceName").asString)
            assertTrue("导出文本必须逐字节包含登记的 sourceJson 原文", text.contains(checkedJson))
            assertTrue("未检查源的 JSON 不应出现在导出文本中", !text.contains(uncheckedJson))
        }
    }

    @Test fun unregisteredSourceAloneIsExcludedAndExportAborts() = runBlocking {
        McpSourceTools.CheckEvidenceRegistry.clear()
        val sources = listOf(Gson().toJson(mapOf(
            "bookSourceName" to "未检", "bookSourceUrl" to "https://h.com/x",
            "ruleToc" to mapOf("chapterList" to "a"),
            "ruleContent" to mapOf("content" to "#c"),
        )))
        val now = System.currentTimeMillis()
        val trusted = McpSourceTools.trustedCheckResults(sources, now)
        assertTrue("未登记源不应有任何可信证据", trusted.isEmpty())
        val report = SourceFilterPolicy.filter(
            sources, trusted, setOf(SourceFilterPolicy.Exclude.UNVERIFIED))
        assertEquals(0, report.keptCount)
        // 过滤后为空 → buildExport 给明确错误而不是产出空数组伪装成功
        val (text, _, err) = McpSourceTools.buildExport(emptyList(), collapseIdentical = false, asArray = true)
        assertNull(text)
        assertTrue(err != null)
    }
}
