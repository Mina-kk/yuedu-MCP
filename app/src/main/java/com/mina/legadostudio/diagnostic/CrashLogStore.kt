package com.mina.legadostudio.diagnostic

import android.content.Context
import com.mina.legadostudio.BuildConfig
import java.io.File
import java.time.Instant

class CrashLogStore(private val context: Context) {
    private val dir = File(context.filesDir, "crash-logs").apply { mkdirs() }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(thread, error) }
            // 只有主线程异常才交给系统终止进程；后台线程（例如脚本自建线程）异常记录后不再杀进程，
            // 否则一段书源脚本就能把 MCP 服务一起带崩。
            if (thread === android.os.Looper.getMainLooper()?.thread) {
                previous?.uncaughtException(thread, error) ?: throw error
            }
        }
    }

    fun list(): List<CrashItem> = CrashLogFiles.list(dir)

    fun read(name: String, limit: Int = 100_000): String = CrashLogFiles.read(dir, name, limit)

    fun delete(names: Collection<String>): Int = CrashLogFiles.delete(dir, names)

    fun latest(limit: Int = 5): List<Map<String, String>> = list().take(limit).map {
        mapOf("name" to it.name, "content" to read(it.name))
    }

    fun clear() { dir.listFiles().orEmpty().forEach(File::delete) }

    /**
     * 已捕获但仍需留档的 Error（如规则引擎 [ExceptionInInitializerError]）：
     * 不会走未捕获处理器，不进默认崩溃文件，必须显式写入，否则 get_crash_logs 为空。
     */
    fun recordCaught(error: Throwable, tag: String) {
        write(Thread.currentThread(), error, prefix = tag)
    }

    private fun write(thread: Thread, error: Throwable, prefix: String? = null) {
        val text = buildString {
            appendLine("time=${Instant.now()}")
            appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} SDK ${android.os.Build.VERSION.SDK_INT}")
            appendLine("thread=${thread.name}")
            if (!prefix.isNullOrBlank()) appendLine("tag=$prefix")
            appendLine()
            append(ExceptionDigest.chain(error, maxChars = 4000))
        }
        val name = if (prefix != null) "fault-${System.currentTimeMillis()}.txt" else "crash-${System.currentTimeMillis()}.txt"
        File(dir, name).writeText(text)
        dir.listFiles().orEmpty().sortedByDescending(File::lastModified).drop(10).forEach(File::delete)
    }
}