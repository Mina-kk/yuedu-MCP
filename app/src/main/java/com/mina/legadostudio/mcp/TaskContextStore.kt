package com.mina.legadostudio.mcp

import com.mina.legadostudio.network.HttpFetcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Ephemeral capability-addressed workspaces.
 * notes 与条目正文保存在内存；传入 snapshotDir 时会异步落盘快照，进程重启后可恢复（恢复条目一律标记 stale，首用必须 refresh）。
 */
class TaskContextStore(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val idleMs: Long = 30 * 60_000L,
    private val freshMs: Long = 5 * 60_000L,
    private val maxContexts: Int = 16,
    private val maxChars: Int = 2_000_000,
    private val maxEntryChars: Int = 1_000_000,
    private val minEvictIdleMs: Long = 60_000L,
    private val snapshotDir: File? = null,
) {
    data class Entry(val id: String, val kind: String, val text: String, val created: Long,
        val fingerprint: String, val key: String? = null, val page: HttpFetcher.FetchResult? = null)
    data class Hit(val entry: Entry, val reused: Boolean)
    private data class Task(val id: String, val label: String, var touched: Long,
        var notes: String = "", val entries: LinkedHashMap<String, Entry> = linkedMapOf())
    init {
        require(maxEntryChars > 0 && maxEntryChars <= maxChars && maxContexts > 0)
        restoreFromSnapshot()
    }
    private val mutex = Mutex()
    private val tasks = linkedMapOf<String, Task>()

    suspend fun create(label: String = ""): String = mutex.withLock {
        require(label.length <= 120) { "label 最多 120 字符" }
        expire()
        if (tasks.size >= maxContexts) evictOldest()
        require(tasks.size < maxContexts) { "上下文数量已达上限，请先 clear_context" }
        val id = UUID.randomUUID().toString()
        tasks[id] = Task(id, label, clock())
        scheduleSnapshot()
        id
    }
    private fun expire() { tasks.entries.removeAll { clock() - it.value.touched >= idleMs } }

    /** 达到上限时优先回收最久未使用且已闲置的上下文，避免默认上下文把普通工具挤到报错。 */
    private fun evictOldest() {
        val deadline = clock() - minEvictIdleMs
        val victim = tasks.values.filter { it.touched <= deadline }.minByOrNull { it.touched } ?: return
        tasks.remove(victim.id)
    }
    private fun task(id: String): Task {
        expire()
        return (tasks[id] ?: error("CONTEXT_EXPIRED_OR_UNKNOWN：请 create_context；旧引用不能恢复")).also { it.touched = clock() }
    }
    private fun insert(task: Task, entry: Entry): Entry {
        require(entry.text.length <= maxEntryChars) { "CONTENT_TOO_LARGE：单项超出 $maxEntryChars 字符；未截断保存" }
        while (task.entries.isNotEmpty() && (task.entries.size >= 32 || task.entries.values.sumOf { it.text.length } + entry.text.length > maxChars)) {
            task.entries.remove(task.entries.keys.first())
        }
        task.entries[entry.id] = entry
        return entry
    }
    /** One in-flight fetch at a time; duplicate callers cannot both miss the same cache key. */
    suspend fun fetch(id: String, key: String, fingerprint: String, reusable: Boolean, refresh: Boolean,
        loader: suspend () -> HttpFetcher.FetchResult): Hit = mutex.withLock {
        val task = task(id)
        if (reusable && !refresh) {
            task.entries.values.lastOrNull { it.key == key && it.fingerprint == fingerprint && clock() - it.created < freshMs }
                ?.let { return@withLock Hit(it, true) }
        }
        task.entries.replaceAll { _, entry -> if (entry.key == key) entry.copy(key = null) else entry }
        val result = loader()
        val safeHeaders = result.headers.filterKeys { it.lowercase() in setOf("content-type", "cache-control") }.mapValues { it.value.take(1024) }
        val entry = Entry(UUID.randomUUID().toString(), "page", result.body, clock(), fingerprint,
            if (reusable && result.code in 200..299 && result.headers.none { it.key.equals("cache-control", true) && (it.value.contains("no-store", true) || it.value.contains("no-cache", true)) }) key else null,
            result.copy(headers = safeHeaders, redirectChain = emptyList()))
        task.touched = clock()
        val hit = Hit(insert(task, entry), false)
        scheduleSnapshot()
        hit
    }
    suspend fun saveResult(id: String, text: String, fingerprint: String): Entry = mutex.withLock {
        val e = insert(task(id), Entry(UUID.randomUUID().toString(), "result", text, clock(), fingerprint))
        scheduleSnapshot()
        e
    }
    suspend fun get(id: String, entryId: String, fingerprint: String): Entry = mutex.withLock {
        val entry = task(id).entries[entryId] ?: error("REFERENCE_EXPIRED_OR_UNKNOWN：引用已清理或不属于此上下文")
        require(entry.fingerprint == fingerprint) { "AUTH_CONTEXT_CHANGED：登录或运行配置已变化，请重新抓取" }
        entry
    }
    suspend fun describe(id: String, notes: String? = null): Map<String, Any> = mutex.withLock {
        val task = task(id)
        notes?.let { require(it.length <= 4000) { "notes 最多 4000 字符" }; task.notes = it; scheduleSnapshot() }
        mapOf("contextId" to task.id, "label" to task.label, "notes" to task.notes,
            "idleTtlSeconds" to idleMs / 1000, "persistence" to if (snapshotDir != null) "memory+snapshot" else "memory-only",
            "entries" to task.entries.values.map { metadata(it) },
            "usedChars" to task.entries.values.sumOf { it.text.length }, "maxChars" to maxChars)
    }

    fun limits(): Map<String, Int> = mapOf(
        "maxContexts" to maxContexts, "maxChars" to maxChars, "maxEntryChars" to maxEntryChars,
        "maxEntriesPerContext" to 32, "idleTtlSeconds" to (idleMs / 1000).toInt(),
        "minEvictIdleSeconds" to (minEvictIdleMs / 1000).toInt(),
    )

    /** 列出上下文元数据，不返回网页或结果正文。 */
    suspend fun list(): List<Map<String, Any>> = mutex.withLock {
        expire()
        tasks.values.sortedByDescending { it.touched }.map { task ->
            mapOf(
                "contextId" to task.id, "label" to task.label, "entries" to task.entries.size,
                "usedChars" to task.entries.values.sumOf { it.text.length },
                "idleMs" to (clock() - task.touched).coerceAtLeast(0),
                "notesChars" to task.notes.length, "notesPreview" to task.notes.take(120),
            )
        }
    }
    suspend fun clear(id: String): Boolean = mutex.withLock {
        val removed = tasks.remove(id) != null
        if (removed) scheduleSnapshot()
        removed
    }

    /** 在持有 mutex 的调用点捕获当前任务快照，交给防抖写入。 */
    private fun scheduleSnapshot() {
        val dir = snapshotDir ?: return
        val snapshot = tasks.values.map { t ->
            TaskContextSnapshot.TaskSnapshot(
                id = t.id, label = t.label, touched = t.touched, notes = t.notes,
                entries = t.entries.values.map { e ->
                    TaskContextSnapshot.EntrySnapshot(
                        id = e.id, kind = e.kind, text = e.text, created = e.created,
                        fingerprint = e.fingerprint, key = null,
                        code = e.page?.code, finalUrl = e.page?.finalUrl, elapsedMs = e.page?.elapsedMs,
                    )
                },
            )
        }
        TaskContextSnapshot.scheduleSave(dir) { snapshot }
    }

    /** 进程启动时恢复快照；恢复条目一律把 created 回拨到 fresh 窗口之外（stale，首用必须 refresh）。 */
    private fun restoreFromSnapshot() {
        val dir = snapshotDir ?: return
        val restored = runCatching { TaskContextSnapshot.load(dir) }.getOrDefault(emptyList())
            .sortedByDescending { it.touched }.take(maxContexts)
        restored.forEach { s ->
            val task = Task(s.id, s.label, s.touched, s.notes)
            s.entries.forEach { e ->
                val page = if (e.kind == "page") HttpFetcher.FetchResult(
                    e.code ?: 200, e.finalUrl.orEmpty(), emptyMap(), e.text, e.elapsedMs ?: 0L,
                ) else null
                // 回拨 created：保证 metadata().stale == true，旧快照不会被当新鲜数据复用
                task.entries[e.id] = Entry(e.id, e.kind, e.text, clock() - freshMs - 1, e.fingerprint, key = null, page = page)
            }
            tasks[s.id] = task
        }
    }
    fun metadata(e: Entry): Map<String, Any> = mapOf("id" to e.id, "kind" to e.kind,
        "totalChars" to e.text.length, "sha256" to digest(e.text), "ageMs" to (clock() - e.created).coerceAtLeast(0),
        "stale" to (clock() - e.created >= freshMs), "url" to (e.page?.finalUrl ?: ""))
    fun read(e: Entry, offset: Int = 0, limit: Int = 6000, query: String? = null): Map<String, Any> {
        require(offset in 0..e.text.length) { "offset 超出正文范围" }
        require(limit in 1..12000) { "limit 必须是 1..12000" }
        val start = if (query == null) offset else {
            require(query.isNotEmpty() && query.length <= 256) { "query 长度必须为 1..256" }
            e.text.indexOf(query, offset, ignoreCase = true)
        }
        if (start < 0) return metadata(e) + mapOf("found" to false, "body" to "", "offset" to offset)
        val end = (start + limit).coerceAtMost(e.text.length)
        return metadata(e) + mapOf("found" to true, "body" to e.text.substring(start, end), "offset" to start,
            "nextOffset" to end, "hasMore" to (end < e.text.length), "snapshot" to true)
    }
    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
