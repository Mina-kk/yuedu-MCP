package com.mina.legadostudio.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException

/**
 * 工具使用统计：每个注册工具名一组累计计数（不含参数、正文、URL、header、sessionId、错误消息），
 * 加上全工具汇总。只用于 app_status 概览；不落盘、不上报、不是精确 token 计数或完整 HTTP 回包大小。
 *
 * 并发模型：单把短锁保护全部可变状态。track 先在锁内捕获轮次 generation（不读钟），
 * 完成时由 record 在同一锁内做「代际校验 + 聚合」；snapshot/top 也在同一锁内一次性
 * 复制 byName+totals，排序与 Json 组装移到锁外，保证 tools 与 summary 取自同一轮、
 * 总数始终一致。持锁期间只更新计数、不调回调、不读结果内容、不读时钟。
 * clock 可注入（默认 System.nanoTime），便于单测隔离耗时语义。
 */
internal class McpToolUsage internal constructor(
    private val clock: () -> Long = { System.nanoTime() },
) {
    /** 服务重启时整体换一个新实例即可，无需复用清理。测试也可各自 new 互不干扰。 */
    internal class Stats {
        var calls = 0L
        var errors = 0L
        var cancelled = 0L
        var totalNanos = 0L
        var maxNanos = 0L
        var textChars = 0L
        var textBytesUtf8 = 0L

        fun resetZero() {
            calls = 0; errors = 0; cancelled = 0
            totalNanos = 0; maxNanos = 0; textChars = 0; textBytesUtf8 = 0
        }

        fun snapshot(): Map<String, Any> = mapOf(
            "calls" to calls,
            "errors" to errors,
            "cancelled" to cancelled,
            "meanTimeMs" to if (calls > 0) totalNanos.toDouble() / calls / 1_000_000.0 else 0.0,
            "maxTimeMs" to maxNanos.toDouble() / 1_000_000.0,
            "textChars" to textChars,
            "textBytesUtf8" to textBytesUtf8,
        )
    }

    private val lock = Any()
    private var generation = 0L
    private val byName = HashMap<String, Stats>()
    private val totals = Stats()

    /** 服务（重启）开始时调用：旧轮次在途调用完成后不落数。 */
    fun reset() {
        synchronized(lock) {
            generation++
            byName.clear()
            totals.resetZero()
        }
    }

    /**
     * 记录一次已完成的调用观察。gen 必须与 track 开头捕获的一致，否则（reset 后旧调用完成）丢弃。
     * result 为 null 表示 handler 以未捕获异常结束（SDK 上层仍会转成 isError 回包）；
     * cancelled 单列表示协程取消，不计入 errors，也不算成功。
     */
    private fun record(gen: Long, name: String, result: CallToolResult?, cancelled: Boolean, nanos: Long) {
        val text = result?.let { textMetrics(it) }
        synchronized(lock) {
            if (gen != generation) return
            val s = byName.getOrPut(name) { Stats() }
            apply(s, result, cancelled, nanos, text)
            apply(totals, result, cancelled, nanos, text)
        }
    }

    private fun apply(s: Stats, result: CallToolResult?, cancelled: Boolean, nanos: Long, text: Pair<Long, Long>?) {
        s.calls++
        if (cancelled) s.cancelled++
        else if (result == null || result.isError == true) s.errors++
        s.totalNanos += nanos
        if (nanos > s.maxNanos) s.maxNanos = nanos
        if (text != null) { s.textChars += text.first; s.textBytesUtf8 += text.second }
    }

    /** 只统计 TextContent.text：UTF-16 单位数与 UTF-8 字节数；非文本块不计。 */
    private fun textMetrics(result: CallToolResult): Pair<Long, Long> {
        var chars = 0L
        var bytes = 0L
        for (block in result.content) {
            val t = (block as? TextContent)?.text ?: continue
            chars += t.length
            bytes += t.toByteArray(Charsets.UTF_8).size
        }
        return chars to bytes
    }

    /**
     * 包一次工具调用：结果对象原样返回（不转换、不重建），抛错/取消原样向上抛。
     * app_status 在此直接排除——自我观测会把状态查询写进自己的回包形成反馈；
     * 即使注入时钟/观测路径故障，该排除也必须成立（故排除在一切度量之前）。
     * 观测自身任何异常都不盖过工具返回与原异常。
     */
    suspend fun track(name: String, block: suspend () -> CallToolResult): CallToolResult {
        if (name == "app_status") return block()
        val gen: Long
        val start: Long
        try {
            synchronized(lock) { gen = generation }
            start = clock()
        } catch (observed: Throwable) {
            // 观测自身故障（含注入时钟抛错）不阻断工具调用：以「不落数」方式放行
            return block()
        }
        try {
            val result = block()
            recordQuietly(gen, name, result, cancelled = false, start)
            return result
        } catch (cancelled: CancellationException) {
            recordQuietly(gen, name, null, cancelled = true, start)
            throw cancelled
        } catch (error: Throwable) {
            recordQuietly(gen, name, null, cancelled = false, start)
            throw error
        }
    }

    private fun recordQuietly(gen: Long, name: String, result: CallToolResult?, cancelled: Boolean, start: Long) {
        try {
            val elapsed = (clock() - start).coerceAtLeast(0)
            record(gen, name, result, cancelled, elapsed)
        } catch (_: Throwable) {
            // 观测失败不影响工具调用结果
        }
    }

    /** 锁内一次性复制 byName 与 totals，二者必属同一轮、同一份计数；排序/组装移到锁外。 */
    private fun copyState(): Pair<Map<String, Stats>, Stats> = synchronized(lock) {
        byName.mapValues { (_, s) -> Stats().also { it.copyFrom(s) } } to Stats().also { it.copyFrom(totals) }
    }

    private fun Stats.copyFrom(o: Stats) {
        calls = o.calls; errors = o.errors; cancelled = o.cancelled
        totalNanos = o.totalNanos; maxNanos = o.maxNanos
        textChars = o.textChars; textBytesUtf8 = o.textBytesUtf8
    }

    /** 按累计 textBytesUtf8 降序、名字升序稳定决胜的 top N（负 limit 视为 0）。 */
    fun topByTextBytes(limit: Int): List<Pair<String, Map<String, Any>>> {
        val (entries, _) = copyState()
        return entries.entries
            .sortedWith(compareByDescending<Map.Entry<String, Stats>> { it.value.textBytesUtf8 }.thenBy { it.key })
            .take(limit.coerceAtLeast(0))
            .map { it.key to it.value.snapshot() }
    }

    /**
     * app_status 用概览：totals + top10（防自身回包过大）。listed/totalTools/truncated 显式；
     * failureRate 只按非取消完成口径算（分母 calls-cancelled），取消单列。
     */
    fun snapshot(limit: Int = 10): Map<String, Any> {
        val (entries, totalsCopy) = copyState()
        val totalSnap = totalsCopy.snapshot()
        val calls = totalsCopy.calls
        val cancelled = totalsCopy.cancelled
        val errors = totalsCopy.errors
        val finished = calls - cancelled
        val top = entries.entries
            .sortedWith(compareByDescending<Map.Entry<String, Stats>> { it.value.textBytesUtf8 }.thenBy { it.key })
            .take(limit.coerceAtLeast(0))
            .map { mapOf("name" to it.key) + it.value.snapshot() }
        return mapOf(
            "summary" to (totalSnap + mapOf(
                "finishedCalls" to finished,
                "failureRate" to if (finished > 0) errors.toDouble() / finished else 0.0,
                "failureRateNote" to "errors/(calls-cancelled)；取消单列不计失败",
            )),
            "totalTools" to entries.size,
            "listed" to top.size,
            "truncated" to (entries.size > top.size),
            "tools" to top,
            "note" to "只含注册工具名与计数/耗时/TextContent 文本量（UTF-16字符与UTF-8字节）；非 token 数、非完整 HTTP 回包；不含参数/正文/URL/header/sessionId/错误消息；app_status 自身不计；服务重启清零",
        )
    }
}

/** 应用当前轮统计实例；McpService.startServer 每次换新的 McpToolUsage（轮次隔离）。 */
internal object McpToolUsageRegistry {
    @Volatile
    var current: McpToolUsage = McpToolUsage()
        internal set
}
