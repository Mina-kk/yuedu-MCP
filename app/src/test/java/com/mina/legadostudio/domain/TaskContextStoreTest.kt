package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.mcp.TaskContextStore
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.LegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TaskContextStoreTest {
    private fun page(text: String = "abcdef", headers: Map<String,String> = emptyMap(), code: Int = 200) =
        HttpFetcher.FetchResult(code, "https://example.org/book", headers, text, 1)
    @Test fun duplicateRequestsFetchOnce() = runBlocking {
        val store = TaskContextStore(); val id = store.create(); var calls = 0
        val hits = (1..12).map { async { store.fetch(id, "key", "auth", true, false) { calls++; delay(5); page() } } }.awaitAll()
        assertEquals(1, calls); assertEquals(1, hits.count { !it.reused }); assertEquals(1, hits.map { it.entry.id }.toSet().size)
    }
    @Test fun refreshAndExpiryRequestAgain() = runBlocking {
        var now = 0L; val store = TaskContextStore(clock = { now }, freshMs = 100); val id = store.create(); var calls = 0
        suspend fun fetch(refresh: Boolean = false) = store.fetch(id, "key", "auth", true, refresh) { calls++; page() }
        fetch(); assertTrue(fetch().reused); fetch(true); now = 101; fetch(); assertEquals(3, calls)
    }
    @Test fun postAndErrorsAndNoStoreNeverHit() = runBlocking {
        val store = TaskContextStore(); val id = store.create(); var calls = 0
        repeat(2) { store.fetch(id, "post", "a", false, false) { calls++; page() } }
        repeat(2) { store.fetch(id, "fail", "a", true, false) { calls++; page(code = 500) } }
        repeat(2) { store.fetch(id, "private", "a", true, false) { calls++; page(headers = mapOf("Cache-Control" to "no-store")) } }
        assertEquals(6, calls)
    }
    @Test fun postRequestsWithReusableFlagCacheLikeGet() = runBlocking {
        // 新语义：POST 搜索也纳入缓存（reusable 由调用方传入），迭代调试搜索规则不再反复联网
        val store = TaskContextStore(); val id = store.create(); var calls = 0
        val first = store.fetch(id, "post", "a", true, false) { calls++; page("first") }
        val second = store.fetch(id, "post", "a", true, false) { calls++; page("second") }
        assertEquals(1, calls); assertTrue(first.entry.page != null); assertTrue(second.reused)
        assertEquals("first", second.entry.text)
    }
    @Test fun isolatesContextsAndCredentialChanges() = runBlocking {
        val store = TaskContextStore(); val a = store.create(); val b = store.create()
        val e = store.fetch(a, "url", "old", true, false) { page() }.entry
        assertTrue(runCatching { store.get(b, e.id, "old") }.isFailure)
        assertTrue(runCatching { store.get(a, e.id, "new") }.isFailure)
        assertFalse(store.fetch(a, "url", "new", true, false) { page() }.reused)
        assertFalse(store.fetch(b, "url", "old", true, false) { page() }.reused)
    }
    @Test fun rangeAndLiteralSearchRecoverFullText() = runBlocking {
        val store = TaskContextStore(); val id = store.create(); val e = store.saveResult(id, "abc[def]XYZ", "a")
        assertEquals("abc", store.read(e, limit = 3)["body"])
        assertEquals("[def]", store.read(e, limit = 5, query = "[def]")["body"])
        assertEquals(false, store.read(e, query = "missing")["found"])
        assertEquals(false, store.read(e, offset = 10)["hasMore"])
        assertTrue(runCatching { store.read(e, -1) }.isFailure)
        assertTrue(runCatching { store.read(e, limit = 12001) }.isFailure)
        val joined = store.read(e, limit = 3)["body"].toString() + store.read(e, offset = 3)["body"]
        assertEquals(e.text, joined)
    }
    @Test fun expirationDeletionAndLimitsAreExplicit() = runBlocking {
        var now = 0L; val store = TaskContextStore(clock = { now }, idleMs = 100, maxContexts = 1, maxChars = 10, maxEntryChars = 10)
        val id = store.create(); assertTrue(runCatching { store.create() }.isFailure)
        val old = store.saveResult(id, "123456", "a")
        store.saveResult(id, "abcdef", "a")
        assertTrue(runCatching { store.get(id, old.id, "a") }.isFailure)
        assertTrue(runCatching { store.saveResult(id, "x".repeat(11), "a") }.isFailure)
        now = 101; assertTrue(runCatching { store.describe(id) }.isFailure)
        val next = store.create(); assertTrue(store.clear(next)); assertFalse(store.clear(next))
    }
    @Test fun notesAndHeadersStayBounded() = runBlocking {
        val store = TaskContextStore(); val id = store.create("task")
        assertEquals("next: inspect title", store.describe(id, "next: inspect title")["notes"])
        assertTrue(runCatching { store.describe(id, "a".repeat(4001)) }.isFailure)
        val e = store.fetch(id, "key", "a", true, false) { page(headers = mapOf("Set-Cookie" to "secret")) }.entry
        assertFalse(e.page!!.headers.containsKey("Set-Cookie"))
    }
    @Test fun snapshotRuleIterationsAndRhinoDoNotFetchAgain() = runBlocking {
        MockWebServer().use { web ->
            web.enqueue(MockResponse().setBody("<h1>Book</h1><p>Author</p>")); web.start()
            val fetcher = HttpFetcher(); val runtime = EmbeddedLegadoRuntime(fetcher, BookSourceValidator(), rhino = RhinoEvaluator(fetcher, Gson()))
            val store = TaskContextStore(); val id = store.create()
            val hit = store.fetch(id, "url", "auth", true, false) { fetcher.fetch(HttpFetcher.FetchRequest(web.url("/").toString())) }
            val title = runtime.inspectSnapshot(LegadoRuntime.InspectRequest(hit.entry.page!!.finalUrl, rule = "h1@text"), hit.entry.page!!)
            val author = runtime.inspectSnapshot(LegadoRuntime.InspectRequest(hit.entry.page!!.finalUrl, rule = "p@text"), hit.entry.page!!)
            assertEquals("Book", title.output!!.first); assertEquals("Author", author.output!!.first)
            assertEquals(hit.entry.text.length.toString(), runtime.evaluate("String(result.length)", previous = hit.entry.text).value)
            assertEquals(1, web.requestCount)
        }
    }
    @Test fun failedRefreshDoesNotResurrectPreviousCache() = runBlocking {
        val store = TaskContextStore(); val id = store.create()
        store.fetch(id, "key", "a", true, false) { page() }
        assertTrue(runCatching { store.fetch(id, "key", "a", true, true) { error("offline") } }.isFailure)
        assertFalse(store.fetch(id, "key", "a", true, false) { page() }.reused)
    }
    @Test fun savedLargeResultReconstructsExactly() = runBlocking {
        val store = TaskContextStore(); val id = store.create(); val text = "汉字abc".repeat(6000)
        val e = store.saveResult(id, text, "a"); var offset = 0; val output = StringBuilder()
        while (offset < text.length) {
            val part = store.read(e, offset, 6000); output.append(part["body"]); offset = part["nextOffset"] as Int
        }
        assertEquals(text, output.toString()); assertEquals(TaskContextStore.digest(text), store.metadata(e)["sha256"])
    }
    @Test fun boundedDownloadRejectsRatherThanSilentlyTruncates() {
        MockWebServer().use { web ->
            web.enqueue(MockResponse().setBody("x".repeat(4096))); web.start()
            val result = runCatching { HttpFetcher().fetch(HttpFetcher.FetchRequest(web.url("/").toString(), maxBodyBytes = 1024)) }
            assertTrue(result.isFailure); assertTrue(result.exceptionOrNull()!!.message!!.contains("CONTENT_TOO_LARGE"))
        }
    }
    @Test fun changedRequestKeyNeverSharesBody() = runBlocking {
        val store = TaskContextStore(); val id = store.create()
        store.fetch(id, "GET/url?q=a", "a", true, false) { page("first") }
        val second = store.fetch(id, "GET/url?q=b", "a", true, false) { page("second") }
        assertFalse(second.reused); assertEquals("second", second.entry.text)
    }
    @Test fun fullCapacityEvictsOldestIdleContext() = runBlocking {
        var now = 0L; val store = TaskContextStore(clock = { now }, maxContexts = 2, minEvictIdleMs = 10)
        val a = store.create("a")
        now = 1; val b = store.create("b")
        now = 2; store.describe(a)
        now = 20; val c = store.create("c")
        assertTrue(runCatching { store.describe(a) }.isSuccess)
        assertTrue(runCatching { store.describe(b) }.isFailure)
        assertTrue(runCatching { store.describe(c) }.isSuccess)
    }
    @Test fun fullCapacityStillFailsWhenEveryContextIsFresh() = runBlocking {
        var now = 0L; val store = TaskContextStore(clock = { now }, maxContexts = 1, minEvictIdleMs = 10)
        store.create("a")
        assertTrue(runCatching { store.create("b") }.isFailure)
    }
    @Test fun listReportsMetadataWithoutBodies() = runBlocking {
        val store = TaskContextStore()
        val id = store.create("book")
        store.saveResult(id, "secret-body", "a")
        store.describe(id, "notes-here")
        val item = store.list().single()
        assertEquals(id, item["contextId"])
        assertEquals("book", item["label"])
        assertEquals(1, item["entries"])
        assertEquals(11, item["usedChars"])
        assertEquals(10, item["notesChars"])
        assertEquals("notes-here", item["notesPreview"])
        assertFalse(item.toString().contains("secret-body"))
        assertEquals(16, store.limits()["maxContexts"])
    }
}
