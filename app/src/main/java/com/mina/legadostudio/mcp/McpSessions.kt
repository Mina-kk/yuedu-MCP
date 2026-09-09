package com.mina.legadostudio.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 跟踪 MCP 会话及其所属 Server，用于回收长时间无请求的会话。
 *
 * SDK 的 Streamable HTTP 只有在客户端发 DELETE 时才关闭会话，且没有空闲超时；
 * 客户端重连或长期保活时会一直占用内存，因此由本对象按会话主动关闭：
 * - 空闲超过 [reapIdleMs]（默认 5 分钟）即回收；
 * - 存活超过 [maxLifetimeMs]（默认 2 小时）强制回收，防止客户端持续保活导致永不释放。
 * 被回收的会话 id 记入 closedIds，后续请求返回 404，客户端应重新 initialize。
 */
object McpSessions {
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /** 无请求超过该时长即主动关闭会话。 */
    @Volatile var reapIdleMs: Long = 5 * 60_000L

    /** 会话绝对存活上限，兜底释放长期保活的会话。 */
    @Volatile var maxLifetimeMs: Long = 2 * 60 * 60_000L

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

    private class ServerEntry(val server: Server, val created: Long)

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

    fun register(server: Server) {
        servers[server] = ServerEntry(server, clock())
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

    /** 记录一次真实工具调用；只有工具调用才刷新活跃时间，保活/SSE 等传输层请求不计入。 */
    fun touchToolCall(server: Server) {
        val now = clock()
        val existing = sessions.values.firstOrNull { it.server === server }
        if (existing != null) {
            existing.lastSeen = now
            McpStats.touch(existing.id)
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

    /** 关闭闲置或超龄会话；单个会话关闭失败不影响其它会话。 */
    suspend fun reap(): Scan {
        val now = clock()
        scans.incrementAndGet()
        lastScanAt = now
        closedIds.entries.removeAll { now - it.value > 10 * 60_000L }
        adopt(now)
        var closed = 0
        for (entry in sessions.values.toList()) {
            val idle = now - entry.lastSeen
            val age = now - entry.created
            if (idle < reapIdleMs && age < maxLifetimeMs) continue
            sessions.remove(entry.id)
            closedIds[entry.id] = now
            closeQuietly(entry.server)
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
