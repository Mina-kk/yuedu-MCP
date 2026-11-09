package com.mina.legadostudio.domain

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.LegadoRuntime
import com.mina.legadostudio.testsupport.LiveNetworkTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
import java.security.MessageDigest

/** Explicit QA only: replay previously recorded public responses; not a fresh live/readership pass. */
@Category(LiveNetworkTest::class)
class PublicSourceSnapshotReplayTest {
    @Test fun originalSuduguFieldsSurviveSequentialExtraction() = runBlocking {
        val base = File(System.getenv("YUEDU_PUBLIC_QA_DIR") ?: "/root/.hermes/profiles/yd-mcp/reports/five-public-sources")
        val source = File(base, "03-original.json").readText()
        val manifest = JsonParser.parseString(File(base, "manifest.json").readText()).asJsonObject["sources"].asJsonArray[2].asJsonObject
        val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(manifest["sha256"].asString, hash)
        val recorded = JsonParser.parseString(File(base, "sandbox-03.json").readText()).asJsonObject
        val requests = recorded["requests"].asJsonArray.map { it.asJsonObject }
        val fetch: suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult = { request ->
            val index = requests.indexOfFirst { it["url"].asString == request.url }
            check(index >= 0) { "Replay has no response for requested URL" }
            val row = requests[index]
            HttpFetcher.FetchResult(row["code"].asInt, row["finalUrl"].asString, emptyMap(), File(base, "sandbox-03-http-%02d.txt".format(index+1)).readText(), 0)
        }
        val runtime = EmbeddedLegadoRuntime(HttpFetcher(), BookSourceValidator(), rhino = com.mina.legadostudio.runtime.RhinoEvaluator(HttpFetcher(), GsonBuilder().create()))
        val discovery = runtime.debug(source, "最近更新::/top/lastupdate/{{page}}.html", fetch)
        val first = (discovery.data as List<*>).first() as Map<*, *>
        assertEquals("田隶", first["author"])
        assertEquals("连载中\n玄幻小说", first["kind"])
        assertEquals("第336章 登龙(9月12万月票加更)", first["lastChapter"])
        val content = runtime.debug(source, "--https://www.sudugu.cc/1659/1388382.html", fetch)
        assertEquals(4, (content.data as Map<*, *>)["pages"])
        assertTrue(content.contentLength!! > 2000)
        File(base, "sandbox-03-fixed-replay.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "scope" to "Original source and saved HTTP snapshot replay; NOT fresh live or structured reader PASSED",
            "sourceSha256" to hash, "discovery" to discovery, "content" to content,
        )))
    }
}
