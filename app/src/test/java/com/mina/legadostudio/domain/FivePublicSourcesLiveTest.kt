package com.mina.legadostudio.domain

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.LegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import com.mina.legadostudio.testsupport.LiveNetworkTest
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

/** Opt-in public-network QA. Source bytes are preserved; failures are not replaced with fixtures. */
@Category(LiveNetworkTest::class)
class FivePublicSourcesLiveTest {
    private val base = File("/root/.hermes/profiles/yd-mcp/reports/five-public-sources")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val manifest by lazy { JsonParser.parseString(File(base, "manifest.json").readText()).asJsonObject }

    @Before fun optIn() {
        check(System.getProperty("legadostudio.liveTests") == "true") { "Explicit liveTestDebug required" }
        check(manifest["count"].asInt == 5)
    }

    @Test(timeout = 300000) fun source01Qidian() = probe(1)
    @Test(timeout = 300000) fun source02Shuba() = probe(2)
    @Test(timeout = 300000) fun source03Sudugu() = probe(3)
    @Test(timeout = 300000) fun source04Deqi() = probe(4)
    @Test(timeout = 300000) fun source05Kuaishu() = probe(5)

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun safe(text: String) = text.replace(Regex("(?i)(token|cookie|authorization|password)([=:]\\s*)[^\\s&;]+"), "$1$2[REDACTED]")
    private fun permitted(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme in setOf("http", "https") && uri.userInfo == null &&
            listOf("qidian.com", "69shuba.com", "sudugu.co", "sudugu.cc", "deqixs.com", "kuaishu5.com").any { host == it || host.endsWith(".$it") }
    }

    private fun probe(id: Int) = runBlocking {
        val spec = manifest["sources"].asJsonArray[id - 1].asJsonObject
        val sourceFile = File(base, spec["file"].asString)
        val bytes = sourceFile.readBytes()
        check(sha(bytes) == spec["sha256"].asString) { "Source bytes changed" }
        val raw = bytes.toString(Charsets.UTF_8)
        val root = JsonParser.parseString(raw).asJsonObject
        val out = File(base, "sandbox-%02d.json".format(id))
        val stages = mutableListOf<Map<String, Any?>>()
        val requests = mutableListOf<Map<String, Any?>>()
        val result = linkedMapOf<String, Any?>(
            "id" to id, "name" to spec["name"].asString, "origin" to spec["origin"].asString,
            "sourceSha256" to sha(bytes), "startedAt" to Instant.now().toString(),
            "environment" to "JVM EmbeddedLegadoRuntime/Rhino; not Android reader; no WebView or login",
            "sourceModified" to false, "keyword" to spec["keyword"].asString,
            "stages" to stages, "requests" to requests, "completeChain" to false,
        )
        fun checkpoint() { out.writeText(gson.toJson(result)) }
        checkpoint()
        val fetcher = HttpFetcher(
            unsafeRedirectGuard = { target -> if (permitted(target)) null else "QA target outside selected public source hosts" },
            unsafeDnsGuard = { address -> if (LogFilterUtils.isLoopbackOrPrivate(address.hostAddress.orEmpty())) "QA refuses private address" else null },
        )
        val runtime = EmbeddedLegadoRuntime(fetcher, BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(fetcher, gson))
        var currentStage = ""
        val fetch: suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult = { request ->
            check(permitted(request.url.orEmpty())) { "QA target outside selected public source hosts" }
            check(requests.size < 24) { "QA per-source HTTP budget reached" }
            Thread.sleep(if (id == 2) 3100L else 1100L)
            val response = fetcher.fetch(request.copy(timeoutSec = 15, maxBodyBytes = 600000))
            requests += mapOf("stage" to currentStage, "method" to request.method, "url" to safe(request.url.orEmpty()),
                "code" to response.code, "finalUrl" to safe(response.finalUrl), "bodyLength" to response.body.length,
                "elapsedMs" to response.elapsedMs, "bodySha256" to sha(response.body.toByteArray()))
            val snapshot = File(base, "sandbox-%02d-http-%02d.txt".format(id, requests.size))
            snapshot.writeText(response.body)
            checkpoint()
            response
        }
        suspend fun stage(label: String, entry: String): LegadoRuntime.DebugReport? {
            currentStage = label
            val started = System.nanoTime()
            return try {
                val report = runtime.debug(raw, entry, fetch)
                stages += mapOf("stage" to label, "entry" to safe(entry), "executed" to true,
                    "elapsedMs" to (System.nanoTime() - started) / 1000000,
                    "report" to report)
                checkpoint()
                report
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                stages += mapOf("stage" to label, "entry" to safe(entry), "executed" to false,
                    "elapsedMs" to (System.nanoTime() - started) / 1000000,
                    "errorClass" to error.javaClass.name, "error" to safe(error.message.orEmpty()).take(1600))
                checkpoint()
                null
            }
        }
        fun firstBook(report: LegadoRuntime.DebugReport?): Map<*, *>? = (report?.data as? List<*>)
            ?.filterIsInstance<Map<*, *>>()?.firstOrNull { !it["name"].toString().isBlank() && it["bookUrl"].toString().startsWith("http") }
        val search = stage("search", spec["keyword"].asString)
        var book = firstBook(search)
        result["searchFoundBook"] = book != null
        if (book == null && id != 1) {
            val explore = root.get("exploreUrl")?.asString.orEmpty().lineSequence().firstOrNull { "::" in it }
            if (explore != null) book = firstBook(stage("discovery_fallback", explore))
        }
        if (book != null) {
            result["selectedBook"] = book
            val info = stage("detail", book["bookUrl"].toString())
            val fields = info?.data as? Map<*, *>
            val toc = fields?.get("tocUrl")?.toString()?.takeIf { it.startsWith("http") }
            if (toc != null) {
                val tocReport = stage("toc", "++$toc")
                val chapters = (tocReport?.data as? Map<*, *>)?.get("chapters") as? List<*>
                val chapter = chapters?.filterIsInstance<Map<*, *>>()?.firstOrNull {
                    it["isVolume"] != true && it["url"].toString().startsWith("http") &&
                        !Regex("VIP|付费|🔒", RegexOption.IGNORE_CASE).containsMatchIn(it["name"].toString())
                }
                if (chapter != null) {
                    result["selectedChapter"] = chapter
                    val content = stage("content", "--${chapter["url"]}")
                    val text = ((content?.data as? Map<*, *>)?.get("content") as? String).orEmpty()
                    val looksBlocked = Regex("Just a moment|确认您是真人|验证码|订阅后阅读|购买本章|登录后阅读", RegexOption.IGNORE_CASE).containsMatchIn(text)
                    val noErrors = listOfNotNull(search, info, tocReport, content).all { it.fieldErrors.isNullOrEmpty() }
                    result["contentObserved"] = text.length >= 100 && !looksBlocked
                    result["completeChain"] = result["searchFoundBook"] == true && result["contentObserved"] == true && noErrors
                }
            }
        }
        result["sourceUnchangedAfterRun"] = sha(sourceFile.readBytes()) == spec["sha256"].asString
        result["finishedAt"] = Instant.now().toString()
        checkpoint()
        println("PUBLIC_SOURCE_QA id=$id completeChain=${result["completeChain"]} report=${out.absolutePath}")
        assertTrue("Source changed during live test", result["sourceUnchangedAfterRun"] == true)
        assertTrue("Public source did not complete full search/detail/toc/content chain; see ${out.absolutePath}", result["completeChain"] == true)
    }
}
