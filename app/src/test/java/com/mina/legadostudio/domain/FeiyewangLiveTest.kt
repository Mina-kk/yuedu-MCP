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
 * 飞叶文学网（m.feiyewang.cn）书源真实联网验证。
 *
 * 输入必须是交付产物本体 [work/feiyewang/feiyewang.json]——测试内不另写规则，
 * 保证测的就是用户要导入的那份 JSON。每本书目录只暴露前 4 章试读，
 * 「点击获取更多章节」是站外导流链接（非本站章节），按 href^=/chapter/ 前缀排除。
 *
 * 结果汇总写回 work/feiyewang/runtime-results.json（类别/书名/作者/目录数/每章字符数/范围限制）。
 * 仅 :app:liveTestDebug 执行（默认单测任务整类排除 LiveNetworkTest）。
 */
@Category(LiveNetworkTest::class)
class FeiyewangLiveTest {

    private val workDir = File(System.getProperty("user.dir")).let {
        if (File(it, "work/feiyewang").isDirectory) File(it, "work/feiyewang")
        else File(it.parentFile, "work/feiyewang")
    }
    private val sourceFile = File(workDir, "feiyewang.json")
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

    /** 交付 JSON 是「1 条源的数组」——取出第 1 条对象给运行时。 */
    private fun sourceJson(): String {
        val arr = JsonParser.parseString(sourceFile.readText()).asJsonArray
        assertEquals("交付产物必须是恰好 1 条源的可导入数组", 1, arr.size())
        return arr[0].asJsonObject.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun chapters(report: LegadoRuntime.DebugReport): List<Map<String, Any>> =
        (report.data as Map<*, *>)["chapters"] as List<Map<String, Any>>

    private fun delay() = Thread.sleep(1200) // 串行限速，不批量抓站

    @Test fun liveSourceEndToEnd() = runBlocking {
        val source = sourceJson()
        val rt = runtime()

        // ---- 静态校验：交付产物自身必须过 validator ----
        val report = rt.validate(source)
        val validation = mapOf(
            "isValid" to report.isValid,
            "issues" to report.issues.map { "${it.path}: ${it.message}" },
            "warnings" to report.warnings.map { "${it.path}: ${it.message}" },
        )
        assertTrue("静态校验失败：${report.issues}", report.isValid)

        // ---- 详情 + 目录 + 正文（两本书，每本首章+末章） ----
        val books = listOf(
            "https://m.feiyewang.cn/book/lwhcicdy0982.html",
            "https://m.feiyewang.cn/book/ghsqyssy5631.html",
        )
        val bookResults = mutableListOf<Map<String, Any?>>()
        for (bookUrl in books) {
            delay()
            val info = rt.debug(source, bookUrl)
            val infoData = info.data as Map<*, *>
            val name = infoData["name"]?.toString().orEmpty()
            val author = infoData["author"]?.toString().orEmpty()
            assertTrue("详情 name 为空：$bookUrl", name.isNotBlank())
            assertEquals("详情 author 不应包含前缀或规则文本", "渡庸人", author)
            assertEquals("详情目录同页", bookUrl, infoData["tocUrl"])
            assertTrue("简介不应混入精彩节选全文", infoData["intro"].toString().length in 1..1200)

            delay()
            // 目录（ruleToc）：只收 /chapter/ 前缀链接，导流「更多章节」不计入
            val toc = rt.debug(source, "++$bookUrl")
            val tocData = toc.data as Map<*, *>
            val total = (tocData["totalChapters"] as Number).toInt()
            val chs = chapters(toc)
            assertTrue("目录为空：$bookUrl", total > 0)
            assertTrue("目录含非 /chapter/ 链接（导流外链混入）：${chs.map { it["url"] }}",
                chs.all { it["url"].toString().contains("/chapter/") })

            // 正文：首章 + 末章
            val chapterChecks = mutableListOf<Map<String, Any?>>()
            val targets = listOf(chs.first(), chs.last()).distinctBy { it["url"] }
            for (ch in targets) {
                delay()
                val content = rt.debug(source, "--${ch["url"]}")
                val cData = content.data as Map<*, *>
                val text = cData["content"].toString()
                val chars = (cData["contentTotalChars"] as Number).toInt()
                assertTrue("正文为空：${ch["url"]}", chars > 0)
                assertTrue("正文混进「全文阅读」广告首段：${ch["url"]}",
                    !text.lines().firstOrNull().orEmpty().contains("全文阅读"))
                chapterChecks += mapOf(
                    "name" to ch["name"], "url" to ch["url"],
                    "chars" to chars, "pages" to (cData["pages"] as Number).toInt(),
                )
            }
            bookResults += mapOf(
                "bookUrl" to bookUrl, "name" to name, "author" to author,
                "tocChapters" to total, "sampledChapters" to chapterChecks,
            )
        }

        val exploreResults = mutableListOf<Map<String, Any?>>()
        val declaredExplore = JsonParser.parseString(source).asJsonObject.get("exploreUrl").asString
        for (line in declaredExplore.lines() + "言情第二页::https://m.feiyewang.cn/yanqing/index_2.html") {
            delay()
            val exp = rt.debug(source, line)
            val items = exp.data as List<*>
            assertTrue("发现列表为空：$line", items.isNotEmpty())
            val first = items.first() as Map<*, *>
            assertTrue("发现首项无 bookUrl：$line", first["bookUrl"]?.toString().orEmpty().startsWith("https://m.feiyewang.cn/book/"))
            if (line.startsWith("言情::")) assertEquals("渡庸人", first["author"])
            val count = Regex("列表 (\\d+) 条").find(exp.lines.joinToString("\n"))?.groupValues?.get(1)?.toInt()
            exploreResults += mapOf(
                "category" to line.substringBefore("::"), "urlTemplate" to line.substringAfter("::"),
                "inlineCount" to items.size, "totalCount" to count,
                "firstName" to first["name"], "firstBookUrl" to first["bookUrl"],
            )
        }

        // ---- CheckSourceRunner 编排层复核：auto 链路 + 发现段 ----
        delay()
        val checkResult = CheckSourceRunner.run(
            CheckSourceRunner.Request(
                source = source, auto = true, refresh = true,
                detailUrl = books.first(),
                exploreKey = "言情", exploreUrl = "https://m.feiyewang.cn/yanqing/",
            ),
            rt, cacheFetch = null,
        )
        val verdict = checkResult["verdict"] as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val evidence = checkResult["evidence"] as Map<String, SourceCheckEvidence.StageEvidence>
        // 无 searchUrl → 搜索段不跑，verdict 以已跑段计
        assertTrue("详情 evidence 未通过", evidence["详情"]!!.ok)
        assertTrue("目录 evidence 未通过", evidence["目录"]!!.ok)
        assertTrue("正文 evidence 未通过", evidence["正文"]!!.ok)
        assertTrue("发现 evidence 未通过", evidence["发现"]!!.ok)

        // ---- 汇总写回 runtime-results.json ----
        val summary = linkedMapOf<String, Any?>(
            "site" to "https://m.feiyewang.cn",
            "engineVersion" to com.mina.legadostudio.BuildConfig.VERSION_NAME,
            "sourceSha256" to java.security.MessageDigest.getInstance("SHA-256").digest(sourceFile.readBytes()).joinToString("") { "%02x".format(it) },
            "testedAt" to java.time.Instant.now().toString(),
            "sourceFile" to "work/feiyewang/feiyewang.json",
            "validation" to validation,
            "books" to bookResults,
            "explore" to exploreResults,
            "checkSourceVerdict" to mapOf(
                "summary" to verdict["summary"], "stagesRan" to verdict["stagesRan"],
                "stagesOk" to verdict["stagesOk"], "stagesFailed" to verdict["stagesFailed"],
                "note" to verdict["note"],
            ),
            "scopeLimit" to "站点为试读导流站：每本书仅目录内 /chapter/ 前缀链接为本站可读章节（实测 4 章），" +
                "目录中「点击获取更多章节」为 xshuoba.com 站外导流，不属本站内容、已按前缀排除；" +
                "书源仅覆盖公开试读范围，不含全文。已检查的公开页面未发现可验证关键词搜索入口，未配 searchUrl。",
        )
        resultsFile.writeText(Gson().newBuilder().setPrettyPrinting().create().toJson(summary))
        println("=== feiyewang runtime results ===")
        println(Gson().toJson(summary))
    }

    @Test fun sameHtmlBrokenSelectorProbe() = runBlocking {
        val html = File(workDir, "home.html").readText()
        val root = JsonParser.parseString(sourceJson()).asJsonObject.deepCopy()
        root.addProperty("searchUrl", "https://m.feiyewang.cn/?key={{key}}")
        root.add("ruleSearch", JsonParser.parseString("""{"bookList":".deliberately-missing-selector","name":"a@text","bookUrl":"a@href"}"""))
        assertTrue(runtime().validate(root.toString()).isValid)
        val observations = listOf("我", "林晚贺川").map { keyword ->
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
            "purpose" to "同一真实首页快照，故意错误选择器的分类对照；不是站点搜索功能测试，也不进入交付书源",
            "observations" to observations,
        )
        File(workDir, "classification-probe.json").writeText(Gson().newBuilder().setPrettyPrinting().create().toJson(result))
    }
}
