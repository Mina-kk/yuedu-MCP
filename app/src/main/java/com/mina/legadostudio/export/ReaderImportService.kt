package com.mina.legadostudio.export

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.io.IOException

/** 阅读打开原生勾选页期间，维持本机回环 JSON 端点。 */
class ReaderImportService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        closeAll()
        super.onDestroy()
    }

    companion object {
        /** 并行制作多个书源时，允许多个待导入端点同时存在，互不顶掉。 */
        private const val MAX_ACTIVE = 4
        private val lock = Any()
        private val active = LinkedHashMap<String, OneShotJsonServer>()

        private fun stopServiceIfIdle(context: Context) {
            val app = context.applicationContext
            synchronized(lock) {
                if (active.isEmpty()) app.stopService(Intent(app, ReaderImportService::class.java))
            }
        }

        fun prepare(context: Context, json: String, ttlMs: Long): String {
            val app = context.applicationContext
            val candidate = OneShotJsonServer.start(json, ttlMs)
            synchronized(lock) {
                if (active.size >= MAX_ACTIVE) {
                    candidate.close()
                    throw IOException("已有 $MAX_ACTIVE 个待导入端点；请先在阅读里完成导入，或调用 cancel 释放")
                }
                active[candidate.url] = candidate
            }
            try {
                app.startService(Intent(app, ReaderImportService::class.java))
            } catch (error: RuntimeException) {
                synchronized(lock) { if (active[candidate.url] === candidate) active.remove(candidate.url) }
                candidate.close()
                throw error
            }
            synchronized(lock) {
                if (active[candidate.url] !== candidate || candidate.isClosed()) {
                    app.stopService(Intent(app, ReaderImportService::class.java))
                    throw IOException("loopback import endpoint stopped before launch")
                }
            }
            Thread({
                while (!candidate.isClosed()) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                val wasActive = synchronized(lock) {
                    active.remove(candidate.url) === candidate
                }
                if (wasActive) stopServiceIfIdle(app)
            }, "reader-import-service-monitor").apply { isDaemon = true }.start()
            return candidate.url
        }

        /** url 为空时取消全部待导入端点，否则只取消指定端点。 */
        fun cancel(context: Context, url: String? = null) {
            synchronized(lock) {
                if (url == null) {
                    active.values.forEach { it.close() }
                    active.clear()
                } else {
                    active.remove(url)?.close()
                }
            }
            context.applicationContext.stopService(Intent(context.applicationContext, ReaderImportService::class.java))
        }

        private fun closeAll() {
            synchronized(lock) {
                active.values.forEach { it.close() }
                active.clear()
            }
        }
    }
}
