package com.mina.legadostudio.web

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Trusted local bridge only. Never accepts file paths or sends bodies through Intent/JS. */
class LogShareLauncher(private val activity: Activity, private val exporter: LogManagementBridge) {
    suspend fun share(params: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        // Same validation, exact scope, formats and redaction as SAF export.
        val data = exporter.prepare(params)
        val extension = data.name.substringAfterLast('.')
        require(extension in setOf("json", "txt", "csv", "html"))
        val dir = File(activity.cacheDir, "exports/log-share").apply {
            check(isDirectory || mkdirs()) { "无法创建分享目录" }
        }
        // Keep successful files for receivers to open asynchronously; remove stale share files only.
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        dir.listFiles()?.filter { it.isFile && it.name.startsWith("logs-") && it.lastModified() < cutoff }?.forEach { it.delete() }
        val file = File(dir, "logs-${UUID.randomUUID()}.$extension")
        try {
            data.file.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            withContext(Dispatchers.Main) {
                check(!activity.isFinishing && !activity.isDestroyed) { "页面已关闭" }
                val intent = Intent(Intent.ACTION_SEND).setType(data.mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri("日志文件", uri) }
                activity.startActivity(Intent.createChooser(intent, "分享日志"))
            }
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        } finally { data.close() }
        data.receipt().apply { addProperty("launched", true) }
    }
}
