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
    @Test fun expiredContextLeavesTombstoneWithReason() = runBlocking {
        // 墓碑：idle 过期后报错要给出「何时失效+为何失效」，而不是笼统的 CONTEXT_EXPIRED_OR_UNKNOWN
        var now = 0L; val store = TaskContextStore(clock = { now }, idleMs = 100)
        val id = store.create("过期任务")
        store.describe(id) // touch @0
        now = 200 // 闲置 200ms > idleMs=100，下次访问即过期
        val ex = runCatching { store.describe(id) }.exceptionOrNull()!!
        assertTrue(ex.message!!.contains("CONTEXT_EXPIRED_OR_UNKNOWN"))
        assertTrue("应报失效时间：${ex.message}", ex.message!!.contains("分钟前失效"))
        assertTrue("应报失效原因：${ex.message}", ex.message!!.contains("闲置上限"))
        assertTrue(ex.message!!.contains("最后活动"))
        assertTrue(store.hasTombstone(id))
    }
    @Test fun unknownContextKeepsOriginalError() = runBlocking {
        // 从未见过的 id：没有墓碑，保持原「请 create_context」文案，不编造失效时间
        val store = TaskContextStore()
        val ex = runCatching { store.describe("never-existed") }.exceptionOrNull()!!
        assertTrue(ex.message!!.contains("CONTEXT_EXPIRED_OR_UNKNOWN"))
        assertTrue(ex.message!!.contains("请 create_context"))
        assertFalse(ex.message!!.contains("分钟前失效"))
        assertFalse(store.hasTombstone("never-existed"))
    }
    @Test fun clearedContextLeavesTombstone() = runBlocking {
        val store = TaskContextStore()
        val id = store.create("待删")
        assertTrue(store.clear(id))
        assertTrue(store.hasTombstone(id))
        val ex = runCatching { store.describe(id) }.exceptionOrNull()!!
        assertTrue(ex.message!!.contains("分钟前失效"))
        assertTrue(ex.message!!.contains("已删除"))
    }
    @Test fun evictedContextLeavesTombstone() = runBlocking {
        var now = 0L; val store = TaskContextStore(clock = { now }, maxContexts = 2, minEvictIdleMs = 10, idleMs = 10_000)
        val a = store.create("a")
        now = 1; val b = store.create("b")
        now = 20; store.create("c") // 容量满 → 回收最久闲置的 a
        assertTrue(store.hasTombstone(a))
        val ex = runCatching { store.describe(a) }.exceptionOrNull()!!
        assertTrue(ex.message!!.contains("超上限被回收"))
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
    @Test fun snapshotRestoreKeepsStoreUsable() = runBlocking {
        // 回归：restoreFromSnapshot 曾在 tasks 初始化之前执行，升级后存量快照让整个存储 NPE
        val now = 10_000_000L
        val dir = java.nio.file.Files.createTempDirectory("ctx-snap").toFile()
        try {
            val snap = com.mina.legadostudio.mcp.TaskContextSnapshot.TaskSnapshot(
                id = "restored-1", label = "旧任务", touched = now, notes = "n",
                entries = listOf(com.mina.legadostudio.mcp.TaskContextSnapshot.EntrySnapshot(
                    "e1", "page", "body", now, "fp", code = 200, finalUrl = "https://example.org/")),
            )
            com.mina.legadostudio.mcp.TaskContextSnapshot.save(dir, listOf(snap))
            val store = TaskContextStore(clock = { now }, snapshotDir = dir)
            val listed = store.list()
            assertEquals(1, listed.size)
            assertEquals("restored-1", listed.single()["contextId"])
            val entry = store.get("restored-1", "e1", "fp")
            assertEquals(true, store.metadata(entry)["stale"])
            // 恢复后所有写路径必须可用：create / fetch / saveResult / describe / clear
            val id = store.create("new")
            val hit = store.fetch(id, "GET/u", "fp", true, false) { page("p") }
            assertFalse(hit.reused)
            store.saveResult(id, "big-result", "fp")
            store.describe(id, "notes")
            assertEquals(2, store.list().size)
            assertTrue(store.clear("restored-1"))
        } finally {
            dir.deleteRecursively()
        }
    }
    @Test fun binaryEntriesAreExcludedFromSnapshotAndRestore() = runBlocking {
        // 回归：scheduleSnapshot 曾不带 kind 筛选——saveBinary 自己不写快照，但后续
        // create/saveResult/describe 触发的快照会把 binary 条目的 kind/id/label 写盘；
        // 重启后恢复出没有 bytes 的 ghost 引用（binary() 报 BINARY_DATA_MISSING），
        // 违反「二进制槽不入快照/进程重启即失效」约定。
        val dir = java.nio.file.Files.createTempDirectory("ctx-snap-bin").toFile()
        val fp = "auth-fp"
        try {
            val store = TaskContextStore(snapshotDir = dir)
            val id = store.create("with-bin")
            val keep = store.saveResult(id, "keep-me", fp)
            val bin = store.saveBinary(id, byteArrayOf(1, 2, 3), fp, "font-a")
            store.saveResult(id, "trigger-snap", fp) // saveBinary 不写快照；由其它 mutator 触发

            // 等防抖落盘（scheduleSave 内部 delay(2000)）：快照文件里出现 trigger-snap 即最终写完成
            val deadline = System.currentTimeMillis() + 15_000
            var snap: com.mina.legadostudio.mcp.TaskContextSnapshot.TaskSnapshot? = null
            while (System.currentTimeMillis() < deadline) {
                snap = com.mina.legadostudio.mcp.TaskContextSnapshot.load(dir)
                    .firstOrNull { it.id == id }
                if (snap != null && snap.entries.any { it.text == "trigger-snap" }) break
                kotlinx.coroutines.delay(150)
            }
            assertNotNull("快照未在限时内落盘", snap)
            val kinds = snap!!.entries.map { it.kind }
            assertTrue(kinds.contains("result"))
            assertFalse("binary 槽不得写入快照", kinds.contains(TaskContextStore.KIND_BINARY))

            // 模拟进程重启：同一 snapshotDir 重建 store
            val store2 = TaskContextStore(snapshotDir = dir)
            val entries2 = store2.describe(id)["entries"] as List<*>
            val kinds2 = entries2.map { (it as Map<*, *>)["kind"] }
            assertTrue(kinds2.contains("result"))
            assertFalse(kinds2.contains(TaskContextStore.KIND_BINARY))
            // 旧 binary 引用不留 ghost：条目不存在，get/binary 均失败
            assertTrue(runCatching { store2.get(id, bin.id, fp) }.isFailure)
            assertTrue(runCatching { store2.binary(id, bin.id, fp) }.isFailure)
            // 普通条目完好保留（恢复条目标 stale，但正文与元数据还在）
            assertEquals("keep-me", store2.get(id, keep.id, fp).text)
        } finally {
            dir.deleteRecursively()
        }
    }
    @Test fun legacyBinarySnapshotEntriesAreSkippedOnRestore() = runBlocking {
        // 旧版本已把 binary 条目写进快照的场景：恢复时跳过，不产出 ghost 引用
        val now = 10_000_000L
        val dir = java.nio.file.Files.createTempDirectory("ctx-snap-legacy").toFile()
        try {
            val snap = com.mina.legadostudio.mcp.TaskContextSnapshot.TaskSnapshot(
                id = "legacy-ctx", label = "旧任务", touched = now, notes = "n",
                entries = listOf(
                    com.mina.legadostudio.mcp.TaskContextSnapshot.EntrySnapshot(
                        "pg1", "page", "page-body", now, "fp", code = 200, finalUrl = "https://example.org/"),
                    com.mina.legadostudio.mcp.TaskContextSnapshot.EntrySnapshot(
                        "bin1", TaskContextStore.KIND_BINARY, "font-label", now, "fp"),
                ),
            )
            com.mina.legadostudio.mcp.TaskContextSnapshot.save(dir, listOf(snap))
            val store = TaskContextStore(clock = { now }, snapshotDir = dir)
            val entries = store.describe("legacy-ctx")["entries"] as List<*>
            val kinds = entries.map { (it as Map<*, *>)["kind"] }
            assertEquals(listOf("page"), kinds) // binary 快照条目被跳过，普通页保留
            assertTrue(runCatching { store.binary("legacy-ctx", "bin1", "fp") }.isFailure)
        } finally {
            dir.deleteRecursively()
        }
    }
    @Test fun corruptSnapshotFileNeverBreaksStore() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("ctx-snap-bad").toFile()
        try {
            java.io.File(dir, "bad.json").writeText("{\"id\":\"x\",\"entries\":null}")
            java.io.File(dir, "garbage.json").writeText("not json at all")
            val store = TaskContextStore(snapshotDir = dir)
            assertTrue(store.list().isEmpty())
            val id = store.create("ok")
            store.fetch(id, "GET/u", "fp", true, false) { page() }
            assertEquals(1, store.list().size)
        } finally {
            dir.deleteRecursively()
        }
    }
    @Test fun crossContextReferenceErrorNamesOwner() = runBlocking {
        // 回归：引用属于别的上下文时，报错必须指出归属 contextId（AI 据此显式传参，不做跨上下文自动回退）
        val store = TaskContextStore(); val a = store.create("书源A"); val b = store.create("书源B")
        val e = store.saveResult(a, "body-a", "fp")
        val ex = runCatching { store.get(b, e.id, "fp") }.exceptionOrNull()!!
        assertTrue(ex.message!!.contains(a))
        assertTrue(ex.message!!.contains("contextId=$a"))
        assertTrue(runCatching { store.get(b, "missing-entry", "fp") }.exceptionOrNull()!!.message!!.contains("REFERENCE_EXPIRED_OR_UNKNOWN"))
        assertEquals("body-a", store.get(a, e.id, "fp").text)
    }
}
