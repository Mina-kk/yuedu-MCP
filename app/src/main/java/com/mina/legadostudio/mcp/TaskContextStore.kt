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
    /** 已失效上下文的墓碑：id→(最后活动 ms, 失效原因 idle/evicted/cleared)，最多留最近 50 个。 */
    private data class Tomb(val touched: Long, val reason: String)
    private val mutex = Mutex()
    private val tasks = linkedMapOf<String, Task>()
    private val tombstones = LinkedHashMap<String, Tomb>()
    init {
        require(maxEntryChars > 0 && maxEntryChars <= maxChars && maxContexts > 0)
        // 必须排在 tasks 声明之后：恢复快照会写 tasks，初始化顺序错误会在升级后把整个存储打成 NPE
        restoreFromSnapshot()
    }

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
    private fun expire() {
        val now = clock()
        val expired = tasks.entries.filter { now - it.value.touched >= idleMs }
        expired.forEach { (id, t) ->
            t.entries.keys.forEach(binaryDecodedCache::remove)
            rememberTomb(id, Tomb(t.touched, "idle"))
        }
        tasks.entries.removeAll { now - it.value.touched >= idleMs }
    }

    /** 留墓碑（FIFO 最多 [MAX_TOMBSTONES] 个）：墓碑 id 之后报错时给得出「何时失效、为何失效」。 */
    private fun rememberTomb(id: String, tomb: Tomb) {
        tombstones.remove(id)
        tombstones[id] = tomb
        while (tombstones.size > MAX_TOMBSTONES) tombstones.remove(tombstones.keys.first())
    }

    /**
     * 达到上限时优先回收最久未使用且已闲置（超过 minEvictIdleMs）的上下文，
     * 避免默认上下文把普通工具挤到报错。全部上下文都在活跃使用时保持硬上限——
     * 宁可靠 create 报错让调用方 clear_context，也不能偷走仍在使用的上下文使其引用瞬间失效。
     */
    private fun evictOldest() {
        val deadline = clock() - minEvictIdleMs
        val victim = tasks.values.filter { it.touched <= deadline }.minByOrNull { it.touched } ?: return
        victim.entries.keys.forEach(binaryDecodedCache::remove)
        tasks.remove(victim.id)
        rememberTomb(victim.id, Tomb(victim.touched, "evicted"))
    }

    private fun task(id: String): Task {
        expire()
        return (tasks[id] ?: run {
            val tomb = tombstones[id]
            if (tomb != null) {
                val agoMin = ((clock() - tomb.touched).coerceAtLeast(0)) / 60_000L
                val lastAt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(nowEpochMs(tomb.touched)))
                val why = when (tomb.reason) {
                    "idle" -> "闲置上限 ${idleMs / 60_000L} 分钟"
                    "evicted" -> "上下文数超上限被回收"
                    else -> "已删除"
                }
                error("CONTEXT_EXPIRED_OR_UNKNOWN：原 context 于 ${agoMin} 分钟前失效（最后活动 $lastAt，$why）；请 create_context，旧引用不能恢复")
            }
            error("CONTEXT_EXPIRED_OR_UNKNOWN：请 create_context；旧引用不能恢复")
        }).also { it.touched = clock() }
    }

    /** 相对时钟（System.nanoTime/ms）→ 绝对 epoch ms（墓碑显示用）。 */
    private fun nowEpochMs(touched: Long): Long =
        System.currentTimeMillis() - (clock() - touched).coerceAtLeast(0)
    private fun insert(task: Task, entry: Entry): Entry {
        require(entry.text.length <= maxEntryChars) { "CONTENT_TOO_LARGE：单项超出 $maxEntryChars 字符；未截断保存" }
        while (task.entries.isNotEmpty() && (task.entries.size >= 32 || task.entries.values.sumOf { it.text.length } + entry.text.length > maxChars)) {
            val evicted = task.entries.remove(task.entries.keys.first())
            evicted?.let { binaryDecodedCache.remove(it.id) }
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

    /**
     * 内存 binary 槽：存字体等不可文本化的原始字节。suspend + 与其他 mutator 同一把 Mutex，
     * 保证 tasks/entries 的 LinkedHashMap 并发安全；函数体内不嵌套调用其它 suspend+withLock
     * 方法（同 mutex 非重入，runBlocking 下单层锁安全、嵌套会挂起）。
     * - kind="binary"，text 置空（不落 entries 字符配额、不进 scheduleSnapshot——快照只恢复 text/page 字段，
     *   天然不会把二进制持久化）；
     * - 每个上下文最多 8 个、总计 ≤16MB，先进先出；read()/metadata() 对 binary 条目不返回正文。
     */
    suspend fun saveBinary(id: String, bytes: ByteArray, fingerprint: String, label: String = ""): Entry = mutex.withLock {
        val task = task(id)
        require(label.length <= 200) { "label 最多 200 字符" }
        require(bytes.size <= MAX_BINARY_BYTES) { "BINARY_TOO_LARGE：单项超过 ${MAX_BINARY_BYTES / 1024 / 1024}MB" }
        // 先按上限驱逐最旧的 binary 槽，再硬性校验（驱逐失败才报错）
        fun binaryBytes() = task.entries.values.filter { it.kind == KIND_BINARY }.sumOf { it.page?.rawBytes?.size ?: 0 }
        while (task.entries.values.count { it.kind == KIND_BINARY } >= MAX_BINARIES_PER_CONTEXT ||
            binaryBytes() + bytes.size > MAX_BINARY_TOTAL_BYTES) {
            val oldest = task.entries.entries.firstOrNull { it.value.kind == KIND_BINARY } ?: break
            task.entries.remove(oldest.key)
            binaryDecodedCache.remove(oldest.key) // 驱逐条目时同步失效其解码缓存
        }
        require(task.entries.values.count { it.kind == KIND_BINARY } < MAX_BINARIES_PER_CONTEXT) {
            "BINARY_SLOT_FULL：本上下文字体槽已满（$MAX_BINARIES_PER_CONTEXT 个），请先 clear_context"
        }
        require(binaryBytes() + bytes.size <= MAX_BINARY_TOTAL_BYTES) {
            "BINARY_TOO_LARGE：本上下文二进制总量超过 ${MAX_BINARY_TOTAL_BYTES / 1024 / 1024}MB"
        }
        val shell = HttpFetcher.FetchResult(200, label, emptyMap(), "", 0, rawBytes = bytes)
        val entry = Entry(UUID.randomUUID().toString(), KIND_BINARY, label, clock(), fingerprint, page = shell)
        task.entries[entry.id] = entry
        // 故意不 scheduleSnapshot：二进制不进快照文件
        entry
    }

    /** 读 binary 槽字节；内联 get() 校验，避免对同一把 Mutex 嵌套 withLock。 */
    suspend fun binary(id: String, entryId: String, fingerprint: String): ByteArray = mutex.withLock {
        binaryEntry(id, entryId, fingerprint).page?.rawBytes
            ?: error("BINARY_DATA_MISSING：二进制条目字节已不在内存")
    }

    /**
     * 解 binary 槽字体的 cmap 并返回 (Decoded, 已排序映射行)。
     * 按 entryId 缓存：get_font_map 分页多次调用时不必每页重跑 Brotli 解压+全量排序。
     * 缓存随 entry 驱逐而失效（条目移除后再读会重解或报 REFERENCE_EXPIRED）。
     */
    suspend fun binaryDecoded(id: String, entryId: String, fingerprint: String): Pair<com.mina.legadostudio.network.Woff2Decoder.Decoded, List<String>> = mutex.withLock {
        val entry = binaryEntry(id, entryId, fingerprint)
        binaryDecodedCache[entryId]?.let { return@withLock it }
        val bytes = entry.page?.rawBytes ?: error("BINARY_DATA_MISSING：二进制条目字节已不在内存")
        val decoded = com.mina.legadostudio.network.Woff2Decoder.decode(bytes)
        val pair = decoded to decoded.mappingLines()
        binaryDecodedCache[entryId] = pair
        pair
    }

    /** 持锁内的 binary 条目定位 + 指纹/kind 校验（binary/binaryMappingLines 共用）。 */
    private fun binaryEntry(id: String, entryId: String, fingerprint: String): Entry {
        val entry = task(id).entries[entryId] ?: run {
            val owner = tasks.entries.firstOrNull { it.value.entries.containsKey(entryId) }?.key
            error(if (owner != null) "REFERENCE_BELONGS_TO_OTHER_CONTEXT：引用 $entryId 属于上下文 $owner，请显式传入 contextId=$owner（并行任务之间不自动跨读，避免串数据）"
                else "REFERENCE_EXPIRED_OR_UNKNOWN：引用已清理或不属于此上下文")
        }
        require(entry.fingerprint == fingerprint) { "AUTH_CONTEXT_CHANGED：登录或运行配置已变化，请重新抓取" }
        require(entry.kind == KIND_BINARY) { "引用不是二进制条目" }
        return entry
    }
    private val binaryDecodedCache = java.util.concurrent.ConcurrentHashMap<String, Pair<com.mina.legadostudio.network.Woff2Decoder.Decoded, List<String>>>()
    suspend fun get(id: String, entryId: String, fingerprint: String): Entry = mutex.withLock {
        val entry = task(id).entries[entryId] ?: run {
            val owner = tasks.entries.firstOrNull { it.value.entries.containsKey(entryId) }?.key
            error(if (owner != null) "REFERENCE_BELONGS_TO_OTHER_CONTEXT：引用 $entryId 属于上下文 $owner，请显式传入 contextId=$owner（并行任务之间不自动跨读，避免串数据）"
                else "REFERENCE_EXPIRED_OR_UNKNOWN：引用已清理或不属于此上下文")
        }
        require(entry.fingerprint == fingerprint) { "AUTH_CONTEXT_CHANGED：登录或运行配置已变化，请重新抓取" }
        entry
    }
    suspend fun describe(id: String, notes: String? = null): Map<String, Any> = mutex.withLock {
        val task = task(id)
        notes?.let { require(it.length <= 4000) { "notes 最多 4000 字符" }; task.notes = it; scheduleSnapshot() }
        mapOf("contextId" to task.id, "label" to task.label, "notes" to task.notes,
            "idleTtlSeconds" to idleMs / 1000, "persistence" to if (snapshotDir != null) "memory+snapshot" else "memory-only",
            "renewed" to true,
            "entries" to task.entries.values.map { metadata(it) },
            "usedChars" to task.entries.values.sumOf { it.text.length }, "maxChars" to maxChars)
    }

    /** 供诊断/测试：某 id 是否留了失效墓碑（true=见过但已失效，报错会带失效详情）。 */
    suspend fun hasTombstone(id: String): Boolean = mutex.withLock { tombstones.containsKey(id) }

    /** 与 Entry.created/Task.touched 同一时钟域的当前值（相对 ms）；快照年龄换算用它，不要用 epoch 时钟。 */
    fun nowMs(): Long = clock()

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
        val removed = tasks.remove(id)
        removed?.entries?.keys?.forEach(binaryDecodedCache::remove)
        if (removed != null) {
            rememberTomb(id, Tomb(removed.touched, "cleared"))
            scheduleSnapshot()
        }
        removed != null
    }

    /** 在持有 mutex 的调用点捕获当前任务快照，交给防抖写入。 */
    private fun scheduleSnapshot() {
        val dir = snapshotDir ?: return
        val snapshot = tasks.values.map { t ->
            TaskContextSnapshot.TaskSnapshot(
                id = t.id, label = t.label, touched = t.touched, notes = t.notes,
                // 二进制槽（kind=binary）不进快照：字节本就不持久化，留 kind/id/label 只会产出
                // 没有 bytes 的 ghost 引用，违反「二进制槽进程重启即失效」约定。
                entries = t.entries.values.filter { it.kind != KIND_BINARY }.map { e ->
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
            // Gson 可产生字段为 null 的对象（坏文件/版本差异）：单个坏快照不许拖垮整个存储
            runCatching {
                val task = Task(s.id, s.label, s.touched, s.notes)
                s.entries.orEmpty().forEach { e ->
                    // 旧版本可能已把 binary 条目写进快照：恢复时跳过，避免无字节的 ghost 引用
                    if (e.kind == KIND_BINARY) return@forEach
                    val page = if (e.kind == "page") HttpFetcher.FetchResult(
                        e.code ?: 200, e.finalUrl.orEmpty(), emptyMap(), e.text, e.elapsedMs ?: 0L,
                    ) else null
                    // 回拨 created：保证 metadata().stale == true，旧快照不会被当新鲜数据复用
                    task.entries[e.id] = Entry(e.id, e.kind, e.text, clock() - freshMs - 1, e.fingerprint, key = null, page = page)
                }
                tasks[s.id] = task
            }
        }
    }
    fun metadata(e: Entry): Map<String, Any> {
        val base = mutableMapOf<String, Any>("id" to e.id, "kind" to e.kind,
            "totalChars" to e.text.length, "sha256" to digest(e.text), "ageMs" to (clock() - e.created).coerceAtLeast(0),
            "stale" to (clock() - e.created >= freshMs), "url" to (e.page?.finalUrl ?: ""))
        if (e.kind == KIND_BINARY) {
            e.page?.rawBytes?.let {
                base["binaryBytes"] = it.size
                base["binarySha256"] = digest(it)
            }
        }
        return base
    }
    fun read(e: Entry, offset: Int = 0, limit: Int = 6000, query: String? = null): Map<String, Any> {
        // 二进制条目没有可分页正文：read_page/read_result 只能拿到元信息
        require(e.kind != KIND_BINARY) { "引用是二进制条目：正文不支持 read，请用 get_font_map/fontId 专用接口取元信息" }
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
        const val KIND_BINARY = "binary"
        /** 失效上下文墓碑上限：只留最近 50 个，报错时给得出失效时间与原因。 */
        const val MAX_TOMBSTONES = 50
        const val MAX_BINARY_BYTES = 4 * 1024 * 1024
        const val MAX_BINARIES_PER_CONTEXT = 8
        const val MAX_BINARY_TOTAL_BYTES = 16 * 1024 * 1024
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
