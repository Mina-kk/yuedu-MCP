package com.mina.legadostudio.mcp

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 任务上下文快照持久化。
 * 每上下文一个文件 contexts/<id>.json。
 * 恢复的 entry 一律标记为 stale（通过把 createdAt 回拨实现）。
 */
object TaskContextSnapshot {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var pendingJob: Job? = null
    private val lock = Any()

    data class EntrySnapshot(
        val id: String,
        val kind: String,
        val text: String,
        val created: Long,
        val fingerprint: String,
        val key: String? = null,
        val code: Int? = null,
        val finalUrl: String? = null,
        val elapsedMs: Long? = null,
    )

    data class TaskSnapshot(
        val id: String,
        val label: String,
        val touched: Long,
        val notes: String,
        val entries: List<EntrySnapshot>,
    )

    fun scheduleSave(dir: File, tasksSupplier: () -> List<TaskSnapshot>) {
        synchronized(lock) {
            pendingJob?.cancel()
            pendingJob = scope.launch {
                delay(2000)
                runCatching {
                    save(dir, tasksSupplier())
                }
            }
        }
    }

    fun save(dir: File, tasks: List<TaskSnapshot>) {
        if (!dir.exists()) dir.mkdirs()
        val currentIds = tasks.map { it.id }.toSet()
        // 清理已删除或已过期的上下文快照
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".json")) {
                val id = file.name.removeSuffix(".json")
                if (id !in currentIds) {
                    file.delete()
                }
            }
        }
        // 写入当前上下文
        tasks.forEach { task ->
            val target = File(dir, "${task.id}.json")
            val tmp = File(dir, "${task.id}.json.tmp")
            try {
                tmp.writeText(gson.toJson(task), Charsets.UTF_8)
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            } catch (_: Throwable) {
                tmp.delete()
            }
        }
    }

    fun delete(dir: File, contextId: String) {
        runCatching {
            File(dir, "$contextId.json").delete()
        }
    }

    fun load(dir: File): List<TaskSnapshot> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                val content = file.readText(Charsets.UTF_8)
                gson.fromJson(content, TaskSnapshot::class.java)
            }.getOrNull()
        }
    }
}
