package com.mina.legadostudio.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mina.legadostudio.MainActivity
import com.mina.legadostudio.mcp.StudioLog
import com.mina.legadostudio.service.McpService
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 阅读打开原生勾选页期间，维持本机回环 JSON 端点。
 *
 * 用户切到阅读后本进程退到后台，普通后台 Service 容易被 LMK 回收，导致阅读拉取
 * `127.0.0.1:<port>` 时报 `Failed to connect`。这里用 `specialUse` 前台服务在导入窗口
 * （端点 TTL 2 分钟）内短时保活；进程里已有 McpService 前台服务时直接复用它，
 * 不重复起通知。服务只在最后一个端点结束后停止。
 *
 * 拿不到进程保护时导入直接失败并给出可操作提示，不会唤起阅读去连一个注定没人接的端口。
 */
class ReaderImportService : Service() {
    @Volatile private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        val failure = startForegroundNotification()
        if (failure == null) {
            foregroundStarted = true
            readiness.markReady()
        } else {
            StudioLog.add("reader import foreground err", "W", "export", failure)
            readiness.markFailed("前台服务启动失败：$failure")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            closeAll()
            serviceStopSelf()
            return START_NOT_STICKY
        }
        if (foregroundStarted) readiness.markReady()
        updateNotification()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 端点由各自 monitor 线程在 TTL/配额结束时关闭；服务停止（含与新导入竞争）
        // 不能把仍在阅读里等待拉取的端点一起关掉。
        foregroundStarted = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** @return null 表示已成功进入前台，否则为失败原因。 */
    private fun startForegroundNotification(): String? = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(activeCount()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        null
    } catch (error: RuntimeException) {
        error.message ?: error.javaClass.simpleName
    }

    private fun serviceStopSelf() {
        if (activeCount() > 0) return
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(activeCount()))
        }.onFailure {
            StudioLog.add("reader import notification err", "W", "export", it.localizedMessage.orEmpty())
        }
    }

    private fun notification(count: Int): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "阅读导入", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ReaderImportService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("阅读导入 · 等待客户端拉取")
            .setContentText("$count 个待导入端点 · 切回阅读完成导入")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "取消导入", cancel)
            .build()
    }

    companion object {
        /** 并行制作多个书源时，允许多个待导入端点同时存在，互不顶掉。 */
        private const val MAX_ACTIVE = 4
        private const val CHANNEL_ID = "reader_import"
        private const val NOTIFICATION_ID = 1238
        private const val ACTION_STOP = "com.mina.legadostudio.CANCEL_READER_IMPORT"

        /** 端点结束到停服务之间的宽限，避免与刚发起的新导入抢服务。 */
        private const val STOP_GRACE_MS = 300L

        private val registry = EndpointRegistry<OneShotJsonServer>(MAX_ACTIVE)
        private val readiness = ServiceReadiness().also { signal ->
            // 只注册一次：主线程路径无法同步确认前台就绪，等失败回调再回收这类端点。
            signal.onFailure { reason ->
                val dropped = registry.abortUnconfirmed()
                if (dropped > 0) {
                    StudioLog.add("reader import dropped=$dropped", "W", "export", reason)
                    instance?.applicationContext?.let { stopServiceWhenIdle(it) }
                }
            }
        }
        @Volatile private var instance: ReaderImportService? = null

        private fun activeCount(): Int = registry.size

        /**
         * 端点结束（TTL/次数用尽/cancel）后，只有确实没有在途端点才停服务。
         * 判空与停服在登记表锁内完成：不会出现「刚判空、新导入恰好登记」把新端点打死。
         */
        private fun stopServiceWhenIdle(context: Context) {
            val app = context.applicationContext
            Thread({
                try {
                    Thread.sleep(STOP_GRACE_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                registry.stopIfEmpty {
                    // 同步清掉就绪状态，随后的 prepare 一定会重新请求前台服务，
                    // 而不是读到即将销毁的实例误判为已就绪。
                    readiness.reset()
                    instance = null
                    runCatching { app.stopService(Intent(app, ReaderImportService::class.java)) }
                }
            }, "reader-import-idle-stop").apply { isDaemon = true }.start()
        }

        /** 撤掉本次端点；调用方负责在端点全空时安排停服务。 */
        private fun abort(candidate: OneShotJsonServer) = registry.remove(candidate.url)?.close()

        fun prepare(context: Context, json: String, ttlMs: Long): String {
            val app = context.applicationContext
            val candidate = OneShotJsonServer.start(json, ttlMs)
            if (!registry.register(candidate.url, candidate)) {
                candidate.close()
                throw IOException("已有 $MAX_ACTIVE 个待导入端点；请先在阅读里完成导入，或调用 cancel 释放")
            }
            val outcome = ReaderImportKeepAlive.ensure(
                mcpActive = McpService.running || McpService.starting,
                serviceReady = instance?.foregroundStarted == true,
                onMainThread = Looper.myLooper() == Looper.getMainLooper(),
                requestStart = {
                    ContextCompat.startForegroundService(
                        app,
                        Intent(app, ReaderImportService::class.java),
                    )
                },
                awaitReady = { timeoutMs -> readiness.await(timeoutMs) == null },
            )
            if (outcome is ReaderImportKeepAlive.Outcome.Unavailable) {
                // 本次导入没有进程保护：撤掉本次端点，并回收同样没确认的同类端点，
                // 明确抛错让调用方停下，不去唤起阅读连一个没人接的端口。
                abort(candidate)
                registry.abortUnconfirmed()
                if (registry.isEmpty()) stopServiceWhenIdle(app)
                throw IOException(ReaderImportKeepAlive.advice(outcome.reason))
            }
            if (!registry.contains(candidate) || candidate.isClosed()) {
                abort(candidate)
                if (registry.isEmpty()) stopServiceWhenIdle(app)
                throw IOException(ReaderImportKeepAlive.advice("导入端点在前台服务就绪前已被关闭"))
            }
            // 主线程没法等 onCreate：登记为未确认，前台启动失败时由失败回调优先回收。
            if (outcome is ReaderImportKeepAlive.Outcome.Requested) registry.markUnconfirmed(candidate.url)
            instance?.updateNotification()
            Thread({
                while (!candidate.isClosed()) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                registry.remove(candidate.url)
                instance?.updateNotification()
                stopServiceWhenIdle(app)
            }, "reader-import-service-monitor").apply { isDaemon = true }.start()
            return candidate.url
        }

        /**
         * url 为空时取消全部待导入端点，否则只取消指定端点。
         * 其它在途端点与它们对应的服务不受影响，服务只在端点全空时停。
         */
        fun cancel(context: Context, url: String? = null) {
            val app = context.applicationContext
            if (url == null) registry.clearAll() else registry.remove(url)?.close()
            instance?.updateNotification()
            stopServiceWhenIdle(app)
        }

        private fun closeAll() {
            registry.clearAll()
        }
    }
}

/**
 * 在途端点登记表，纯 JVM 便于单测生命周期分支。
 *
 * 「未确认」只用于主线程路径：请求已交给系统、但同步确认不到前台就绪。
 * 前台启动随后失败时只回收这些端点，不牵连已有进程保护的导入。
 */
internal class EndpointRegistry<T : Closeable>(private val maxActive: Int) {
    private val lock = Any()
    private val active = LinkedHashMap<String, T>()
    private val unconfirmed = LinkedHashSet<String>()

    val size: Int get() = synchronized(lock) { active.size }

    fun isEmpty(): Boolean = synchronized(lock) { active.isEmpty() }

    fun contains(endpoint: T): Boolean = synchronized(lock) {
        active.values.any { it === endpoint }
    }

    /** @return false 表示已达并行上限，调用方负责关闭这个端点。 */
    fun register(url: String, endpoint: T): Boolean = synchronized(lock) {
        if (active.size >= maxActive) return@synchronized false
        active[url] = endpoint
        true
    }

    fun markUnconfirmed(url: String) {
        synchronized(lock) { if (active.containsKey(url)) unconfirmed += url }
    }

    fun remove(url: String): T? = synchronized(lock) {
        unconfirmed.remove(url)
        active.remove(url)
    }

    /** 关闭并移除所有未确认端点，返回回收数量。 */
    fun abortUnconfirmed(): Int {
        val victims: List<T>
        synchronized(lock) {
            victims = unconfirmed.mapNotNull { active.remove(it) }
            unconfirmed.clear()
        }
        victims.forEach { runCatching { it.close() } }
        return victims.size
    }

    /** 判空与执行 `block` 在同一把锁内完成，避免「刚判空就被新导入登记」的竞态。 */
    fun stopIfEmpty(block: () -> Unit): Boolean = synchronized(lock) {
        if (active.isNotEmpty()) return@synchronized false
        block()
        true
    }

    fun clearAll() {
        val victims: List<T>
        synchronized(lock) {
            unconfirmed.clear()
            victims = active.values.toList()
            active.clear()
        }
        victims.forEach { runCatching { it.close() } }
    }
}

/**
 * 前台服务就绪信号：`prepare` 等它，`onCreate`/`onStartCommand` 在 `startForeground`
 * 成功后放行，失败则带着原因放行。纯 JVM 实现，便于单测。
 */
internal class ServiceReadiness {
    private val lock = Any()
    private val waiters = mutableListOf<CompletableFuture<String?>>()
    private var ready = false
    private var failure: String? = null
    private var failureListener: ((String) -> Unit)? = null

    fun isReady(): Boolean = synchronized(lock) { ready }

    /** 前台启动随后失败时回调；主线程路径无法同步确认，用它收掉未受保护的端点。 */
    fun onFailure(listener: (String) -> Unit) {
        synchronized(lock) { failureListener = listener }
    }

    /** @return null 表示已就绪；否则是失败/超时原因。 */
    fun await(timeoutMs: Long): String? {
        val waiter = CompletableFuture<String?>()
        synchronized(lock) {
            if (ready) return null
            failure?.let { return it }
            waiters += waiter
        }
        return try {
            waiter.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            "前台服务未在 ${timeoutMs}ms 内就绪"
        } catch (_: Exception) {
            "等待前台服务就绪被中断"
        } finally {
            synchronized(lock) { waiters.remove(waiter) }
        }
    }

    fun markReady() {
        val pending: List<CompletableFuture<String?>>
        synchronized(lock) {
            ready = true
            failure = null
            pending = waiters.toList()
            waiters.clear()
        }
        pending.forEach { it.complete(null) }
    }

    fun markFailed(reason: String) {
        val pending: List<CompletableFuture<String?>>
        val listener: ((String) -> Unit)?
        synchronized(lock) {
            ready = false
            failure = reason
            pending = waiters.toList()
            waiters.clear()
            listener = failureListener
        }
        pending.forEach { it.complete(reason) }
        listener?.invoke(reason)
    }

    fun reset() {
        synchronized(lock) {
            ready = false
            failure = null
        }
    }
}

/**
 * 保活门控：决定这次导入能不能拿到进程保护。
 *
 * - MCP 前台服务在跑：直接复用，进程已在前台额度内；
 * - 服务已就绪：复用现有实例；
 * - 否则同步请求短时 FGS：同步被拒（如后台启动限制）就是硬失败；
 * - 不在主线程时再等就绪信号（主线程等待会阻塞它自己的 `onCreate`，必然超时）；
 * - 主线程只能确认「请求已被系统接受」。异步失败无法在这步转成异常，由 `ServiceReadiness`
 *   的失败回调回收未确认端点；调用方此时已拿到 URL，不谎称已确认保活。
 */
internal object ReaderImportKeepAlive {
    const val READY_TIMEOUT_MS = 1_500L

    sealed class Outcome {
        /** 复用 McpService 的前台进程保护。 */
        object ReusedMcp : Outcome()

        /** 短时 FGS 已确认就绪（或服务实例本就在前台）。 */
        object Confirmed : Outcome()

        /** 请求已被系统接受，但主线程无法同步确认就绪。 */
        object Requested : Outcome()

        class Unavailable(val reason: String) : Outcome()
    }

    fun ensure(
        mcpActive: Boolean,
        serviceReady: Boolean,
        onMainThread: Boolean,
        requestStart: () -> Unit,
        awaitReady: (Long) -> Boolean,
    ): Outcome {
        if (mcpActive) return Outcome.ReusedMcp
        if (serviceReady) return Outcome.Confirmed
        try {
            requestStart()
        } catch (error: RuntimeException) {
            return Outcome.Unavailable(error.message ?: error.javaClass.simpleName)
        }
        if (onMainThread) return Outcome.Requested
        return if (awaitReady(READY_TIMEOUT_MS)) {
            Outcome.Confirmed
        } else {
            Outcome.Unavailable("前台服务未在 ${READY_TIMEOUT_MS}ms 内就绪")
        }
    }

    /** 给用户可照做的提示，不泄露端口或内部路径。 */
    fun advice(reason: String): String =
        "无法保持本机导入端点：$reason。" +
            "请回到 App 前台后重新发起导入；若 App 在后台，可在「MCP」页先启动服务" +
            "（其前台通知可保护进程）再导入。本次端点已关闭，阅读客户端不会连上。"
}
