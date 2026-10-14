package com.mina.legadostudio.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * McpToolUsage 纯 JVM 单测：每个用例各自 new 独立实例（构造注入可控 clock），
 * 不复位、不触碰任何全局状态，用例间天然隔离。
 */
class McpToolUsageTest {
    private class TickClock {
        val now = AtomicLong(0)
        fun clock(): () -> Long = { now.get() }
        fun tick(nanos: Long) { now.addAndGet(nanos) }
    }

    private fun text(text: String, isError: Boolean? = null) = CallToolResult(listOf(TextContent(text)), isError = isError)

    private fun perTool(usage: McpToolUsage, name: String): Map<String, Any> {
        val hit = usage.topByTextBytes(Int.MAX_VALUE).firstOrNull { it.first == name }?.second
        return hit ?: fail("工具 $name 未出现在统计里").let { unreachable() }
    }

    private fun unreachable(): Nothing = error("unreachable")

    @Suppress("UNCHECKED_CAST")
    private fun summary(usage: McpToolUsage): Map<String, Any> =
        usage.snapshot()["summary"] as Map<String, Any>

    @Test fun resultIdentityAndTimingRecorded() = runBlocking {
        val tick = TickClock()
        val usage = McpToolUsage(tick.clock())
        val expected = text("hello")
        val returned = usage.track("t1") {
            tick.tick(7_000_000) // 块内推进 7ms 单调时钟
            expected
        }
        assertSame("返回对象必须是同一个实例（不转换、不重建）", expected, returned)
        val s = perTool(usage, "t1")
        assertEquals(1L, s["calls"])
        assertEquals(0L, s["errors"])
        assertEquals(0L, s["cancelled"])
        assertEquals(7.0, s["meanTimeMs"] as Double, 0.0001)
        assertEquals(7.0, s["maxTimeMs"] as Double, 0.0001)
        assertEquals(5L, s["textChars"])
        assertEquals(5L, s["textBytesUtf8"])
    }

    @Test fun isErrorCountsAsFailure() = runBlocking {
        val usage = McpToolUsage()
        val result = usage.track("t") { text("bad", isError = true) }
        assertEquals(true, result.isError)
        val s = perTool(usage, "t")
        assertEquals(1L, s["calls"])
        assertEquals(1L, s["errors"])
        assertEquals(0L, s["cancelled"])
    }

    @Test fun thrownExceptionKeepsIdentityAndCountsAsError() = runBlocking {
        val usage = McpToolUsage()
        val failure = IllegalStateException("boom")
        val caught = try {
            usage.track("t") { throw failure }
            null
        } catch (t: Throwable) { t }
        assertSame(failure, caught)
        val s = perTool(usage, "t")
        assertEquals(1L, s["calls"])
        assertEquals(1L, s["errors"])
        assertEquals(0L, s["cancelled"])
    }

    @Test fun cancellationCountsSeparatelyAndRethrowsSameInstance() = runBlocking {
        val usage = McpToolUsage()
        val cancelled = CancellationException("stop")
        val caught = try {
            usage.track("t") { throw cancelled }
            null
        } catch (t: Throwable) { t }
        assertSame(cancelled, caught)
        val s = perTool(usage, "t")
        assertEquals(1L, s["calls"])
        assertEquals(0L, s["errors"])
        assertEquals(1L, s["cancelled"])
        val summary = summary(usage)
        assertEquals(1L, summary["calls"])
        assertEquals(0L, summary["errors"])
        assertEquals(1L, summary["cancelled"])
        assertEquals(0L, summary["finishedCalls"])
        assertEquals(0.0, summary["failureRate"])
    }

    @Test fun utf16AndUtf8MetricsForCjkEmojiMultiBlockAndNonText() = runBlocking {
        val usage = McpToolUsage()
        // "中" UTF16=1 / UTF8=3；emoji "😀" UTF16=2 / UTF8=4；"ab" = 2/2；ImageContent 不计文本
        usage.track("t") {
            CallToolResult(listOf(
                TextContent("中"),
                TextContent("😀"),
                ImageContent(data = "aGk=", mimeType = "image/png"),
                TextContent("ab"),
            ))
        }
        val s = perTool(usage, "t")
        assertEquals(1 + 2 + 2, (s["textChars"] as Long).toInt())
        assertEquals(3 + 4 + 2, (s["textBytesUtf8"] as Long).toInt())
    }

    /** 真实并行：Dispatchers.Default 多工作线程 + 启动栅栏，块内不等事件循环。 */
    @Test fun concurrentCallsOnMultipleNamesHaveExactCounts() = runBlocking {
        val usage = McpToolUsage()
        val names = listOf("alpha", "beta", "gamma")
        val per = 50
        val startLatch = CountDownLatch(1)
        val jobs = names.flatMap { name ->
            (1..per).map { i ->
                async(Dispatchers.Default) {
                    startLatch.await() // 全部就绪后同时放行
                    usage.track(name) {
                        when {
                            i % 10 == 0 -> throw CancellationException("c")
                            i % 3 == 0 -> text("x", isError = true)
                            else -> text("ok")
                        }
                    }
                }
            }
        }
        startLatch.countDown()
        // 逐个点收：取消的协程以 CancellationException 结束，不炸掉整批
        val collected = jobs.map { runCatching { it.await() } }
        val okCount = collected.count { it.isSuccess }
        val cancelledCount = collected.count { it.exceptionOrNull() is CancellationException }
        assertEquals(names.size * per, okCount + cancelledCount)
        // i in 1..50：cancelled={10,20,30,40,50} 5个；isError 且非 cancelled = i%3==0 且 i%10!=0 → 15个（30被排除）
        for (name in names) {
            val s = perTool(usage, name)
            assertEquals("name=$name calls", 50L, s["calls"])
            assertEquals("name=$name cancelled", 5L, s["cancelled"])
            assertEquals("name=$name errors", 15L, s["errors"])
        }
        val summary = summary(usage)
        assertEquals(150L, summary["calls"])
        assertEquals(15L, summary["cancelled"])
        assertEquals(45L, summary["errors"])
    }

    /**
     * 快照一致性：在 writer/resetter **仍在运行**期间，主协程连续读 snapshot，
     * 每次快照内部 tools 各行之和必须等于 summary 同字段、listed==totalTools、truncated==false
     * （证明 entries 与 totals 取自同一份锁内原子复制，不串轮次）。
     * 固定工具名集合避免 map 无限增长；CompletableDeferred 确认双方已开始；finally 停 worker 有界等待。
     */
    @Test fun snapshotIsConsistentWhileWritersAndResetterRun() = runBlocking {
        val usage = McpToolUsage()
        val names = listOf("w0", "w1", "w2") // 固定工具名：byName 至多 3 项
        val writerReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resetterReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)

        val writer = async(Dispatchers.Default) {
            writerReady.complete(Unit)
            var i = 0
            while (!stop.get()) {
                usage.track(names[i % names.size]) { text("x") }
                i++
                kotlinx.coroutines.yield() // 让出，保证单 CPU runner 上 snapshot/resetter 不被饿死
            }
        }
        val resetter = async(Dispatchers.Default) {
            resetterReady.complete(Unit)
            while (!stop.get()) {
                usage.reset()
                kotlinx.coroutines.yield()
            }
        }

        try {
            // 确认写与重置两条线程都已真实开始（有界等待，避免挂死）
            kotlinx.coroutines.withTimeout(5_000) {
                writerReady.await()
                resetterReady.await()
            }
            // writer/resetter 运行期间连续快照：每次快照自身必须内部一致
            repeat(60) {
                val snap = usage.snapshot(limit = Int.MAX_VALUE)
                @Suppress("UNCHECKED_CAST")
                val tools = snap["tools"] as List<Map<String, Any>>
                @Suppress("UNCHECKED_CAST")
                val sum = snap["summary"] as Map<String, Any>
                assertEquals(tools.sumOf { it["calls"] as Long }, sum["calls"])
                assertEquals(tools.sumOf { it["errors"] as Long }, sum["errors"])
                assertEquals(tools.sumOf { it["cancelled"] as Long }, sum["cancelled"])
                assertEquals(tools.sumOf { it["textBytesUtf8"] as Long }, sum["textBytesUtf8"])
                assertEquals(tools.size, snap["totalTools"])
                assertEquals(tools.size, snap["listed"])
                assertEquals(false, snap["truncated"])
                // 固定工具名集合内：totalTools ∈ 0..3
                assertTrue("totalTools 不应超过固定名字集合：${snap["totalTools"]}",
                    (snap["totalTools"] as Int) <= names.size)
                kotlinx.coroutines.yield()
            }
        } finally {
            stop.set(true)
            kotlinx.coroutines.withTimeout(5_000) { writer.await(); resetter.await() }
        }

        // 收尾语义不变（补充断言，不替代上面的并发阶段）：reset 后新写入落在同一新轮
        usage.reset()
        val finalWrites = async(Dispatchers.Default) { repeat(20) { usage.track("post") { text("y") } } }
        kotlinx.coroutines.withTimeout(5_000) { finalWrites.await() }
        val summary = summary(usage)
        assertEquals(20L, summary["calls"])
        assertEquals(0L, summary["errors"])
        assertEquals(20L, summary["textBytesUtf8"]) // "y" = 1 byte ×20
    }

    @Test fun resetStartsNewGenerationAndClears() = runBlocking {
        val usage = McpToolUsage()
        usage.track("old") { text("x") }
        assertEquals(1L, perTool(usage, "old")["calls"])
        usage.reset()
        val snap = usage.snapshot()
        assertEquals(0L, summary(usage)["calls"])
        assertEquals(0, snap["totalTools"])
        usage.track("new") { text("y") }
        assertEquals(1L, perTool(usage, "new")["calls"])
        assertFalse(usage.topByTextBytes(100).any { it.first == "old" })
    }

    @Test fun inFlightCallFromOldGenerationDoesNotWriteAfterReset() {
        val usage = McpToolUsage()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val done = CountDownLatch(1)
        Thread {
            runBlocking {
                usage.track("slow") {
                    entered.countDown()
                    resume.await()
                    text("done")
                }
            }
            done.countDown()
        }.apply { isDaemon = true; start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        usage.reset()
        resume.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(0L, summary(usage)["calls"])
        assertEquals(0, usage.snapshot()["totalTools"])
    }

    @Test fun appStatusIsNeverCountedRegardlessOfOutcome() = runBlocking {
        val usage = McpToolUsage()
        // 成功
        val ok = usage.track("app_status") { text("status") }
        assertEquals("status", (ok.content.single() as TextContent).text)
        // isError
        usage.track("app_status") { text("bad", isError = true) }
        // 未捕获异常
        val fail = RuntimeException("x")
        val caught = try { usage.track("app_status") { throw fail }; null } catch (t: Throwable) { t }
        assertSame(fail, caught)
        // 取消
        try { usage.track("app_status") { throw CancellationException() } } catch (_: CancellationException) {}
        // 全部不计数
        assertEquals(0L, summary(usage)["calls"])
        assertEquals(0, usage.snapshot()["totalTools"])
        // 观测故障也不得改变 app_status 排除
        val badClock: () -> Long = { throw RuntimeException("clock boom") }
        val broken = McpToolUsage(badClock)
        val r = broken.track("app_status") { text("s") }
        assertEquals("s", (r.content.single() as TextContent).text)
        assertEquals(0, broken.snapshot()["totalTools"])
    }

    @Test fun snapshotContainsOnlyAggregatesNoResultContent() = runBlocking {
        val usage = McpToolUsage()
        usage.track("t") { text("payload=secret-token-abc123 url=https://example.internal/x?session=s9") }
        val blob = usage.snapshot().toString()
        assertFalse(blob.contains("secret-token-abc123"))
        assertFalse(blob.contains("example.internal"))
        assertFalse(blob.contains("session=s9"))
        assertFalse(blob.contains("payload"))
    }

    @Test fun metricsFailureDoesNotCorruptResultOrException() = runBlocking {
        // 观测路径故障（clock 在第二次读取时抛错）：结果与原异常都原样穿透，不被覆盖
        val reads = AtomicLong(0)
        val badClock: () -> Long = {
            if (reads.incrementAndGet() > 1) throw RuntimeException("clock boom")
            0L
        }
        val usage = McpToolUsage(badClock)
        val okResult = text("fine")
        val returned = usage.track("t") { okResult }
        assertSame(okResult, returned)
        val failure = RuntimeException("tool failed")
        val caught = try { usage.track("t2") { throw failure }; null } catch (t: Throwable) { t }
        assertSame(failure, caught)
    }

    @Test fun topLimitOrderTruncationAndStableNameTie() = runBlocking {
        val usage = McpToolUsage()
        for (i in 1..13) {
            val name = "t%02d".format(i)
            usage.track(name) { text("x".repeat(20 - i)) } // t01=19B 最大，严格递减
        }
        val snap = usage.snapshot(limit = 10)
        assertEquals(13, snap["totalTools"])
        assertEquals(10, snap["listed"])
        assertEquals(true, snap["truncated"])
        @Suppress("UNCHECKED_CAST")
        val tools = snap["tools"] as List<Map<String, Any>>
        assertEquals("t01", tools.first()["name"])
        val bytesSeq = tools.map { it["textBytesUtf8"] as Long }
        assertEquals(bytesSeq.sortedDescending(), bytesSeq)
        // 并列时按工具名升序
        val tied = McpToolUsage()
        listOf("zz", "aa", "mm").forEach { n -> tied.track(n) { text("same") } }
        @Suppress("UNCHECKED_CAST")
        val tiedNames = (tied.snapshot()["tools"] as List<Map<String, Any>>).map { it["name"] as String }
        assertEquals(listOf("aa", "mm", "zz"), tiedNames)
        assertEquals(false, tied.snapshot()["truncated"])
    }

    @Test fun failureRateExcludesCancelled() = runBlocking {
        val usage = McpToolUsage()
        usage.track("ok") { text("x") }
        usage.track("err") { text("x", isError = true) }
        try { usage.track("cx") { throw CancellationException() } } catch (_: CancellationException) {}
        val summary = summary(usage)
        assertEquals(3L, summary["calls"])
        assertEquals(1L, summary["errors"])
        assertEquals(1L, summary["cancelled"])
        assertEquals(2L, summary["finishedCalls"])
        assertEquals(0.5, summary["failureRate"] as Double, 0.0001)
    }
}
