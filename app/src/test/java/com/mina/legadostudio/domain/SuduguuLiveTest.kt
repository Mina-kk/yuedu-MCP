package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.LegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import com.mina.legadostudio.testsupport.LiveNetworkTest
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * 速读谷（www.suduguu.com）书源真实联网验证。
 *
 * 输入必须是交付产物本体 work/suduguu/suduguu.json，测试内不另写规则。
 * 结果写回 work/suduguu/runtime-results.json；仅 :app:liveTestDebug 执行。
 */
@Category(LiveNetworkTest::class)
class SuduguuLiveTest {

    private val workDir = File(System.getProperty("user.dir")).let {
        if (File(it, "work/suduguu").isDirectory) File(it, "work/suduguu")
        else File(it.parentFile, "work/suduguu")
    }
    private val sourceFile = File(workDir, "suduguu.json")
    private val resultsFile = File(workDir, "runtime-results.json")

    private fun runtime() = EmbeddedLegadoRuntime(
        HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson())
    )

    @Before fun requireLiveOptInAndMaterial() {
        Assume.assumeTrue(
            "未设置 -Dlegadostudio.liveTests=true，跳过真实联网用例",
            System.getProperty("legadostudio.liveTests") == "true"
        )
        assertTrue("已开启 live 测试但缺少交付产物：$sourceFile", sourceFile.isFile)
    }

    private fun sourceJson(): String {
        val arr = JsonParser.parseString(sourceFile.readText()).asJsonArray
        assertEquals("交付产物必须是恰好 1 条源的可导入数组", 1, arr.size())
        return arr[0].asJsonObject.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun chapters(report: LegadoRuntime.DebugReport): List<Map<String, Any>> =
        (report.data as Map<*, *>)["chapters"] as List<Map<String, Any>>

    private fun delay() = Thread.sleep(1200)

    @Test fun liveSourceEndToEnd() = runBlocking {
        val source = sourceJson()
        val rt = runtime()

        val report = rt.validate(source)
        val validation = mapOf(
            "isValid" to report.isValid,
            "issues" to report.issues.map { "${it.path}: ${it.message}" },
            "warnings" to report.warnings.map { "${it.path}: ${it.message}" },
        )
        assertTrue("静态校验失败：${report.issues}", report.isValid)

        val searchResults = mutableListOf<Map<String, Any?>>()
        for ((keyword, expectName, expectAuthor) in listOf(
            Triple("捞尸人", "捞尸人", "纯洁滴小龙"),
            Triple("辰东", "夜无疆", "辰东"),
        )) {
            delay()
            val res = rt.debug(source, keyword)
            val items = res.data as List<*>
            assertTrue("搜索为空：$keyword\n${res.lines.joinToString("\n")}", items.isNotEmpty())
            val first = items.first() as Map<*, *>
            assertEquals("搜索首项书名：$keyword", expectName, first["name"])
            assertEquals("搜索首项作者：$keyword", expectAuthor, first["author"])
            assertTrue(first["bookUrl"].toString().matches(Regex("https://www\\.suduguu\\.com/\\d+/")))
            searchResults += mapOf(
                "keyword" to keyword, "count" to items.size,
                "firstName" to first["name"], "firstAuthor" to first["author"],
                "firstKind" to first["kind"], "firstBookUrl" to first["bookUrl"],
                "firstLastChapter" to first["lastChapter"],
            )
        }

        val books = listOf(
            "https://www.suduguu.com/51/" to "纯洁滴小龙",
            "https://www.suduguu.com/4/" to "辰东",
        )
        val bookResults = mutableListOf<Map<String, Any?>>()
        for ((bookUrl, expectAuthor) in books) {
            delay()
            val info = rt.debug(source, bookUrl)
            val infoData = info.data as Map<*, *>
            val name = infoData["name"]?.toString().orEmpty()
            val author = infoData["author"]?.toString().orEmpty()
            assertTrue("详情 name 为空：$bookUrl", name.isNotBlank())
            assertEquals("详情 author", expectAuthor, author)
            assertEquals("详情目录同页", bookUrl + "#dir", infoData["tocUrl"])
            assertTrue("简介为空", infoData["intro"].toString().isNotBlank())

            delay()
            val toc = rt.debug(source, "++${infoData["tocUrl"]}")
            val tocData = toc.data as Map<*, *>
            val total = (tocData["totalChapters"] as Number).toInt()
            val chs = chapters(toc)
            assertTrue("目录过短：$bookUrl total=$total", total > 100)
            assertTrue("目录含相关小说等非本书链接",
                chs.all { it["url"].toString().startsWith(bookUrl) && it["url"].toString().endsWith(".html") })

            val chapterChecks = mutableListOf<Map<String, Any?>>()
            for (ch in listOf(chs.first(), chs.last()).distinctBy { it["url"] }) {
                delay()
                val content = rt.debug(source, "--${ch["url"]}")
                val cData = content.data as Map<*, *>
                val text = cData["content"].toString()
                val chars = (cData["contentTotalChars"] as Number).toInt()
                assertTrue("正文为空：${ch["url"]}", chars > 0)
                assertTrue("正文混入相关小说/页脚：${ch["url"]}", !text.contains("相关小说") && !text.contains("谷内无错"))
                chapterChecks += mapOf(
                    "name" to ch["name"], "url" to ch["url"],
                    "chars" to chars, "pages" to (cData["pages"] as Number).toInt(),
                )
            }
            bookResults += mapOf(
                "bookUrl" to bookUrl, "name" to name, "author" to author,
                "kind" to infoData["kind"], "wordCount" to infoData["wordCount"],
                "lastChapter" to infoData["lastChapter"],
                "tocChapters" to total, "firstChapter" to chs.first()["name"], "lastTocChapter" to chs.last()["name"],
                "sampledChapters" to chapterChecks,
            )
        }
        val firstChapterPages = ((bookResults.first()["sampledChapters"] as List<*>).first() as Map<*, *>)["pages"] as Int
        assertEquals("捞尸人第一章在站点上是 4 页，应全部续页", 4, firstChapterPages)

        val tailChapters = mutableListOf<Map<String, Any?>>()
        for (url in listOf("https://www.suduguu.com/51/4419509.html", "https://www.suduguu.com/4/4757761.html")) {
            delay()
            val cData = rt.debug(source, "--$url").data as Map<*, *>
            val chars = (cData["contentTotalChars"] as Number).toInt()
            assertTrue("末尾章节正文为空：$url", chars > 0)
            tailChapters += mapOf("url" to url, "chars" to chars, "pages" to (cData["pages"] as Number).toInt())
        }

        val exploreResults = mutableListOf<Map<String, Any?>>()
        val declaredExplore = JsonParser.parseString(source).asJsonObject.get("exploreUrl").asString
        val secondPages = listOf(
            "都市第二页::https://www.suduguu.com/dushi/2.html",
            "排行榜第二页::https://www.suduguu.com/paihang/2.html",
        )
        for (line in declaredExplore.lines() + secondPages) {
            delay()
            val exp = rt.debug(source, line)
            val items = exp.data as List<*>
            assertTrue("发现列表为空：$line", items.isNotEmpty())
            val first = items.first() as Map<*, *>
            assertTrue("发现首项 bookUrl：$line", first["bookUrl"].toString().matches(Regex("https://www\\.suduguu\\.com/\\d+/")))
            assertTrue("发现首项作者为空：$line", first["author"]?.toString().orEmpty().isNotBlank())
            exploreResults += mapOf(
                "category" to line.substringBefore("::"), "url" to line.substringAfter("::"),
                "count" to items.size, "firstName" to first["name"], "firstAuthor" to first["author"],
                "firstBookUrl" to first["bookUrl"],
            )
        }

        delay()
        val checkResult = CheckSourceRunner.run(
            CheckSourceRunner.Request(
                source = source, searchKey = "捞尸人", auto = true, refresh = true,
                exploreKey = "都市", exploreUrl = "https://www.suduguu.com/dushi/",
            ),
            rt, cacheFetch = null,
        )
        val verdict = checkResult["verdict"] as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val evidence = checkResult["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        for (stage in listOf("搜索", "详情", "目录", "正文", "发现")) {
            assertTrue("$stage evidence 未通过：${evidence[stage]}", evidence[stage]?.ok == true)
        }

        val summary = linkedMapOf<String, Any?>(
            "site" to "https://www.suduguu.com",
            "engineVersion" to com.mina.legadostudio.BuildConfig.VERSION_NAME,
            "sourceSha256" to java.security.MessageDigest.getInstance("SHA-256").digest(sourceFile.readBytes()).joinToString("") { "%02x".format(it) },
            "testedAt" to java.time.Instant.now().toString(),
            "validation" to validation,
            "search" to searchResults,
            "books" to bookResults,
            "tailChapters" to tailChapters,
            "explore" to exploreResults,
            "checkSourceVerdict" to mapOf(
                "summary" to verdict["summary"], "stagesRan" to verdict["stagesRan"],
                "stagesOk" to verdict["stagesOk"], "stagesFailed" to verdict["stagesFailed"],
                "note" to verdict["note"],
            ),
            "checkSourceWarnings" to checkResult["warnings"],
        )
        resultsFile.writeText(Gson().newBuilder().setPrettyPrinting().create().toJson(summary))
        println("=== suduguu runtime results ===")
        println(Gson().toJson(summary))
    }

    @Test fun sameHtmlBrokenSelectorProbe() = runBlocking {
        val html = File(workDir, "search_laoshiren.html").readText()
        val root = JsonParser.parseString(sourceJson()).asJsonObject.deepCopy()
        root.add("ruleSearch", JsonParser.parseString("""{"bookList":".deliberately-missing-selector","name":"a@text","bookUrl":"a@href"}"""))
        assertTrue(runtime().validate(root.toString()).isValid)
        val observations = listOf("我", "捞尸人").map { keyword ->
            val result = CheckSourceRunner.run(
                CheckSourceRunner.Request(root.toString(), searchKey = keyword), runtime(),
                cacheFetch = { request -> HttpFetcher.FetchResult(200, request.url!!, emptyMap(), html, 0) },
            )
            val evidence = (result["evidence"] as Map<*, *>)["搜索"] as SourceCheckEvidence.StageEvidence
            assertTrue(!evidence.ok)
            mapOf("keyword" to keyword, "evidence" to evidence, "verdict" to result["verdict"], "warnings" to result["warnings"])
        }
        val result = mapOf(
            "engineVersion" to com.mina.legadostudio.BuildConfig.VERSION_NAME,
            "purpose" to "同一真实搜索结果页快照，故意错误的 bookList 选择器，对照两版的分类与提示",
            "observations" to observations,
        )
        File(workDir, "classification-probe.json").writeText(Gson().newBuilder().setPrettyPrinting().create().toJson(result))
    }
}
