package com.mina.legadostudio.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 跟踪 MCP 会话及其所属 Server，用于回收长时间无请求的会话。
 *
 * SDK 的 Streamable HTTP 只有在客户端发 DELETE 时才关闭会话，且没有空闲超时；
 * 客户端重连或长期保活时会一直占用内存，因此由本对象按会话主动关闭：
 * - 空闲超过 [reapIdleMs]（默认 30 分钟）即回收；活跃度只由真实工具调用刷新，
 *   保活/SSE 等传输层请求不计入（否则 ping 流量会让会话永不释放）。
 * - 默认 30 分钟是面向交互式 MCP 客户端（Kimi/Claude 等）的取值：真人思考、
 *   翻阅页面、跨任务切换都可能超过 5 分钟，过早回收会让客户端在下次调用时
 *   撞到 404 被迫重新握手，表现为「自动断开 / 连不上」。
 * - 不再按绝对存活时间强杀：持续有工具调用的长任务会话应一直存活；
 *   只 ping 不干活的会话同样会在 [reapIdleMs] 到期时回收，内存不会泄漏。
 * 被回收的会话 id 记入 closedIds，后续请求返回 404，客户端应重新 initialize。
 *
 * 关闭协调（DELETE 释放 / orphan 清理 / reaper 回收共用同一门闩）：
 * - 每个已登记 [ServerEntry] 带一次性 CAS 门闩（closeClaimed）。登记、orphan、DELETE、
 *   reaper 对已登记实例的关闭都必须先 claim 成功，保证并发下 close 恰好一次；
 *   不引入永久强引用 closeSet（已摘除条目不驻留）。
 * - 门闩只挂在「已登记」对象上：未登记/已摘除的 Server 无门闩可 claim，
 *   `releaseOrphan`/`releaseDeleted` 对其只摘除登记、不再 close——其一次性由
 *   McpApplication 的 request 属性消费（takeOrNull）保证，本对象不承诺跨调用幂等。
 *   `register` 对已在册实例复用现有 entry，不重置门闩。
 */
object McpSessions {
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /** 无请求超过该时长即主动关闭会话。 */
    @Volatile var reapIdleMs: Long = 30 * 60_000L

    /** 单个 Server 的关闭超时，避免个别 SDK 调用卡死回收线程。 */
    @Volatile var closeTimeoutMs: Long = 3_000L

    /** 测试缝：实际关闭动作。 */
    @Volatile internal var closeServer: suspend (Server) -> Unit = { it.close() }

    /**
     * 测试缝：读取 Server 当前持有的会话 id。
     *
     * 注意：SDK 的 Server.sessions 以内部 ServerSession.sessionId 为键，与 HTTP 头
     * Mcp-Session-Id（由 transport 生成）不是同一个值；这里必须取 transport 的 sessionId，
     * 否则门禁会把每个会话的第一个请求都误判成过期。
     */
    @Volatile internal var sessionIdsOf: (Server) -> Collection<String> = { server ->
        val wire = runCatching {
            server.sessions.values.mapNotNull { session ->
                (session.transport as? StreamableHttpServerTransport)?.sessionId
            }
        }.getOrDefault(emptyList())
        if (wire.isNotEmpty()) wire
        else runCatching { server.sessions.keys.toList() }.getOrDefault(emptyList())
    }

    /**
     * 严格的 wire 会话读取：只认 transport 已生成的 Mcp-Session-Id，不回落内部 session key。
     * 供请求结束时的 orphan 判定使用：无 wire id 的 ServerSession（校验失败请求留下的内部
     * 会话）不算已交接。读取异常保守返回 null，调用方按「可能已交接」处理、不清理。
     */
    internal fun strictWireIdsOf(server: Server): Collection<String>? = runCatching {
        server.sessions.values.mapNotNull { session ->
            (session.transport as? StreamableHttpServerTransport)?.sessionId
        }
    }.getOrNull()

    private class ServerEntry(val server: Server, val created: Long) {
        /** 一次性关闭门闩：DELETE/orphan/reaper 谁先 claim 谁负责 close，保证恰好一次。 */
        val closeClaimed = AtomicBoolean(false)
    }

    private class SessionEntry(
        val id: String,
        val server: Server,
        val created: Long,
        @Volatile var lastSeen: Long,
    )

    private val servers = ConcurrentHashMap<Server, ServerEntry>()
    private val sessions = ConcurrentHashMap<String, SessionEntry>()
    private val closedIds = ConcurrentHashMap<String, Long>()
    private val reaped = AtomicInteger()
    private val scans = AtomicInteger()

    @Volatile private var lastScanAt = 0L

    data class Scan(val sessions: Int, val idle: Int, val closed: Int)

    /** 已登记实例复用现有 entry：不重置其关闭门闩，避免重复登记把已 claim 的 close 重新放开。 */
    fun register(server: Server) {
        servers.putIfAbsent(server, ServerEntry(server, clock()))
    }

    /** SDK 关闭会话或 Server 后同步移除，避免残留统计。 */
    fun onServerClosed(server: Server) {
        val still = sessionIdsOf(server).toSet()
        sessions.values.removeAll { it.server === server && !still.contains(it.id) }
        if (still.isEmpty()) servers.remove(server)
    }

    fun tracked(): Int = servers.size

    fun trackedSessions(): Int = sessions.size

    fun reapedTotal(): Int = reaped.get()

    fun scanCount(): Int = scans.get()

    fun lastScan(): Long = lastScanAt

    fun idleSessions(): Int {
        val now = clock()
        return sessions.values.count { now - it.lastSeen >= reapIdleMs }
    }

    fun clear() {
        servers.clear()
        sessions.clear()
        closedIds.clear()
        reaped.set(0)
        scans.set(0)
        lastScanAt = 0L
    }

    /** 该会话是否仍然有效；已被回收的会话返回 false。 */
    fun isKnown(sessionId: String): Boolean {
        if (closedIds.containsKey(sessionId)) return false
        if (sessions.containsKey(sessionId)) return true
        return servers.values.any { sessionIdsOf(it.server).contains(sessionId) }
    }

    /**
     * 该会话是否已被主动回收。
     *
     * 门禁只对确认回收过的会话返回 404：未知会话交给 SDK 自己判定，避免把新会话的
     * 首个请求误判成过期（否则客户端会反复重新握手并被再次拒绝，表现为无法连接）。
     */
    fun isReaped(sessionId: String): Boolean = closedIds.containsKey(sessionId)

    /**
     * 摘除一个确认无 wire 会话的请求残留实例并 best-effort 关闭。
     *
     * 仅用于「本次请求新创建、校验失败、未交接给客户端」的 Server：直接从 servers/sessions
     * 移除该实例的登记（不经过 reap 的 closedIds / reaped / McpStats 统计语义，orphan 不算
     * 「回收的会话」），随后经 [closeServer] 关闭。close 失败不影响登记摘除和 HTTP 响应；
     * [closeTimeoutMs] 仍走 withTimeoutOrNull 的协作式限时，不宣称硬性保证 SDK 释放。
     *
     * 已登记实例先经 ServerEntry 门闩 claim：并发 orphan/DELETE/reaper 只放行一个 close。
     * 未登记或已被摘除的实例（entry == null）不再 close：其 entry 已随首次摘除消费，
     * 幂等由调用方（request 属性 takeOrNull 单次消费）保证，这里不做无门闩的第二次关闭。
     */
    suspend fun releaseOrphan(server: Server) {
        val entry = servers.remove(server)
        sessions.values.removeAll { it.server === server }
        if (entry == null || !entry.closeClaimed.compareAndSet(false, true)) return
        runCatching { withTimeoutOrNull(closeTimeoutMs) { closeServer(server) } }
    }

    /**
     * 按请求 wire 会话 id 找到「已登记」的 owner Server；找不到返回 null。
     *
     * 只遍历 tracker 中已登记实例（不裸扫 SDK transport 分发表），未登记实例不归本函数管；
     * 读取异常保守返回 null，调用方按「无可释放 owner」处理、不关闭。
     */
    internal fun registeredOwnerOf(wireSessionId: String): Server? {
        if (wireSessionId.isBlank()) return null
        for (entry in servers.values) {
            val wire = strictWireIdsOf(entry.server) ?: return null
            if (wire.contains(wireSessionId)) return entry.server
        }
        return null
    }

    /**
     * DELETE 释放：摘除已登记 owner 的 tracker 条目并对其关闭恰好一次。
     *
     * 仅供「鉴权通过的 /mcp DELETE 且 SDK 已回 200、owner 已确认不再持有 wire 会话」调用：
     * - `expected` 为请求处理前捕获的 owner；处理后再核对其 wire 集合确实不再持有该 sid
     *   （防多 wire 实例误关仍有其它会话的 owner），确认后才摘除+关闭。
     * - 关闭经 ServerEntry 门闩 claim：reaper/并发 DELETE/再来一次 DELETE 都不会二次关闭；
     *   若 reaper 先 claim 到，本调用不再关（返回 false）。
     * - close 抛异常仍完成摘除（登记一致性优先），异常吞掉不破坏原 HTTP 响应。
     * 返回值：本次调用是否真正执行了 close（用于断言恰好一次）。
     */
    suspend fun releaseDeleted(wireSessionId: String, expected: Server): Boolean {
        val wire = strictWireIdsOf(expected) ?: return false
        if (wire.contains(wireSessionId)) return false
        val session = sessions[wireSessionId]
        if (session != null && session.server === expected) sessions.remove(wireSessionId, session)
        if (wire.isNotEmpty()) return false
        val entry = servers.remove(expected) ?: return false
        sessions.values.removeAll { it.server === expected }
        if (!entry.closeClaimed.compareAndSet(false, true)) return false
        runCatching { withTimeoutOrNull(closeTimeoutMs) { closeServer(expected) } }
        return true
    }

    /** 测试缝：该 Server 当前是否仍在 tracker 登记中（测试断言本用例实例用）。 */
    internal fun isTrackedForTest(server: Server): Boolean = servers.containsKey(server)

    /** 记录一次真实工具调用；只有工具调用才刷新活跃时间，保活/SSE 等传输层请求不计入。 */
    fun touchToolCall(server: Server) {
        val now = clock()
        // 同一 Server 理论上只挂一个会话（SDK 按会话建 Server）；若因 adopt/竞态出现
        // 多个条目，全部刷新，避免活跃会话因只刷到旧条目而被误回收。
        val existing = sessions.values.filter { it.server === server }
        if (existing.isNotEmpty()) {
            existing.forEach { it.lastSeen = now }
            existing.forEach { McpStats.touch(it.id) }
            return
        }
        val id = sessionIdsOf(server).firstOrNull()?.take(128) ?: return
        sessions[id] = SessionEntry(id, server, now, now)
        McpStats.touch(id)
    }

    /** 把 SDK 已建立但尚未登记的会话纳入统计，避免漏回收。 */
    private fun adopt(now: Long) {
        servers.values.forEach { entry ->
            sessionIdsOf(entry.server).forEach { raw ->
                val id = raw.take(128)
                if (closedIds.containsKey(id) || sessions.containsKey(id)) return@forEach
                val seen = McpStats.lastSeen(id)
                sessions[id] = SessionEntry(id, entry.server, seen ?: entry.created, now)
            }
        }
    }

    /**
     * 关闭闲置会话；单个会话关闭失败不影响其它会话。不再按绝对存活时间强杀活跃会话。
     *
     * 快照条目按原子 `sessions.remove(id, entry)` 摘除：并发下若 DELETE 已摘走该会话，
     * 原子 remove 失败、直接跳过（不写 closedIds——否则 DELETE 掉的 sid 重试会拿到
     * 「reaped」404 而非 SDK 原生「session not found」）。摘除成功后再 `servers.remove`
     * 取 owner：owner 已被 DELETE 摘除说明其会话清理归属 DELETE 路径，reaper 不写
     * closedIds、不 close、不计 closed。close 经同一 ServerEntry 门闩 claim。
     */
    suspend fun reap(): Scan {
        val now = clock()
        scans.incrementAndGet()
        lastScanAt = now
        closedIds.entries.removeAll { now - it.value > 10 * 60_000L }
        adopt(now)
        var closed = 0
        for (entry in sessions.values.toList()) {
            if (now - entry.lastSeen < reapIdleMs) continue
            // 原子摘除：DELETE/orphan 已移走的条目不再回收；失败即跳过，不写 closedIds。
            if (!sessions.remove(entry.id, entry)) continue
            // 先取 owner：owner 已被 DELETE/orphan 摘走时该会话由对方路径负责，reaper 不再
            // 写 closedIds/close/计数（DELETE 掉的 sid 不会被标成 reaped）。
            val owner = servers.remove(entry.server)
            if (owner == null) continue
            closedIds[entry.id] = now
            if (owner.closeClaimed.compareAndSet(false, true)) {
                closeQuietly(entry.server)
            }
            McpStats.forget(listOf(entry.id))
            closed++
        }
        if (closed > 0) {
            reaped.addAndGet(closed)
            McpStats.markReaped(closed)
        }
        return Scan(sessions.size, sessions.values.count { now - it.lastSeen >= reapIdleMs }, closed)
    }

    private suspend fun closeQuietly(server: Server) {
        runCatching { withTimeoutOrNull(closeTimeoutMs) { closeServer(server) } }
    }
}
