package com.mina.legadostudio.mcp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 连接统计。
 *
 * - clientCount：最近 idleWindowMs 内有真实工具调用的活跃会话数，按 Mcp-Session-Id 去重；传输层保活不计入。
 * - sessionTotal：本次服务启动以来累计建立的会话数。
 * - sessionClosed：SDK 报告的关闭次数。
 * - sessionReaped：由 McpSessions 主动回收的闲置会话数。
 */
object McpStats {
    @Volatile private var onChanged: (() -> Unit)? = null
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }
    /** 活跃窗口：超过该时长没有工具调用的会话不再计入活跃数。 */
    @Volatile var idleWindowMs: Long = 60_000L

    private val sessions = ConcurrentHashMap<String, Long>()
    private val total = AtomicLong(0)
    private val closed = AtomicLong(0)
    private val reaped = AtomicLong(0)
    private val lastAccess = AtomicLong(0)

    fun setListener(listener: (() -> Unit)?) { onChanged = listener }

    fun onRequest() {
        lastAccess.set(clock())
        prune()
        onChanged?.invoke()
    }

    /** 记录一次真实工具调用。带会话标识时刷新该会话的活跃时间；没有标识则只更新最近访问时间。 */
    fun touch(sessionId: String?) {
        val now = clock()
        lastAccess.set(now)
        if (!sessionId.isNullOrBlank()) sessions[sessionId.take(128)] = now
        prune()
        onChanged?.invoke()
    }

    fun connected() {
        total.incrementAndGet()
        lastAccess.set(clock())
        prune()
        onChanged?.invoke()
    }

    fun disconnected() {
        closed.incrementAndGet()
        onChanged?.invoke()
    }

    /** 某个会话最近一次请求时间；没有记录返回 null。 */
    fun lastSeen(sessionId: String): Long? = sessions[sessionId]

    /** 主动回收后立即从活跃计数中移除。 */
    fun forget(sessionIds: Collection<String>) {
        if (sessionIds.isEmpty()) return
        sessionIds.forEach { sessions.remove(it) }
        onChanged?.invoke()
    }

    fun markReaped(count: Int) {
        if (count <= 0) return
        reaped.addAndGet(count.toLong())
        onChanged?.invoke()
    }

    fun resetConnections() {
        sessions.clear()
        total.set(0)
        closed.set(0)
        reaped.set(0)
        lastAccess.set(0)
        onChanged?.invoke()
    }

    private fun prune() {
        val deadline = clock() - idleWindowMs
        val stale = sessions.filterValues { it <= deadline }.keys
        if (stale.isNotEmpty()) sessions.keys.removeAll(stale)
    }

    private fun activeSessions(): Int {
        prune()
        return sessions.size
    }

    fun snapshot(): Map<String, Long> = mapOf(
        "clientCount" to activeSessions().toLong(),
        "sessionTotal" to total.get(),
        "sessionClosed" to closed.get(),
        "sessionReaped" to reaped.get(),
        "activeWindowSeconds" to idleWindowMs / 1000,
        "lastAccessAt" to lastAccess.get(),
    )
}
