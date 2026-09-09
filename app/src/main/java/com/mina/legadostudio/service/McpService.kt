package com.mina.legadostudio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mina.legadostudio.MainActivity
import com.mina.legadostudio.mcp.McpAccess
import com.mina.legadostudio.mcp.McpConfigStore
import com.mina.legadostudio.mcp.McpSessions
import com.mina.legadostudio.mcp.McpStats
import com.mina.legadostudio.mcp.StudioLog
import com.mina.legadostudio.mcp.StudioMcpServer
import com.mina.legadostudio.mcp.configureStudioMcp
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

class McpService : Service() {
    private var engine: EmbeddedServer<*, *>? = null
    private var notificationPending = false
    private var lastNotificationAt = 0L
    private var reaper: java.util.concurrent.ScheduledExecutorService? = null
    @Volatile private var reapBusy = false
    private var reapWorker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification("MCP 服务启动中", "正在绑定本机回环 Endpoint"))
        McpStats.setListener(::scheduleNotificationUpdate)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_COPY -> copyEndpoint()
            ACTION_RESTART -> startServer()
            else -> startServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        McpStats.setListener(null)
        // 关停引擎丢到后台线程，避免主线程被阻塞
        val old = engine
        engine = null
        if (old != null) Thread { old.stop(0, 80) }.start()
        StudioLog.add("mcp stop", category = "mcp")
        running = false
        starting = false
        endpoints = emptyList()
        stopReaper()
        McpSessions.clear()
        McpStats.resetConnections()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized private fun startServer() {
        stopEngine()
        stopReaper()
        McpSessions.clear()
        McpStats.resetConnections()
        val store = McpConfigStore(this)
        val config = store.load()
        val addresses = McpAccess.localAddresses()
        val hosts = McpAccess.allowedHosts(addresses)
        val origins = McpAccess.allowedOrigins(hosts)
        try {
            engine = embeddedServer(CIO, host = "0.0.0.0", port = config.port) {
                configureStudioMcp(store::load, hosts, origins) { StudioMcpServer(this@McpService).create() }
            }.also { it.start(wait = false) }
            endpoints = McpAccess.endpoints(config.port)
            running = true
            starting = false
            StudioLog.add("mcp start port=${config.port}", category = "mcp")
            updateNotification()
            startReaper()
        } catch (error: Exception) {
            running = false
            starting = false
            endpoints = emptyList()
            StudioLog.add("mcp start err", "E", "mcp", error.localizedMessage.orEmpty())
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification("MCP 启动失败", error.localizedMessage.orEmpty()))
        }
    }

    /** 定期回收长时间无请求的会话，避免 SDK 的 Streamable HTTP 会话一直占用。 */
    private fun startReaper() {
        stopReaper()
        val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mcp-session-reaper").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay({
            if (!reapBusy && reapWorker?.isAlive != true) {
                reapBusy = true
                try {
                    val result = java.util.concurrent.atomic.AtomicReference<McpSessions.Scan?>()
                    val worker = Thread({
                        result.set(runCatching { kotlinx.coroutines.runBlocking { McpSessions.reap() } }.getOrNull())
                    }, "mcp-reap-worker").apply { isDaemon = true }
                    reapWorker = worker
                    worker.start()
                    runCatching { worker.join(10_000L) }
                    if (worker.isAlive) StudioLog.add("mcp reap scan timeout", "W", "mcp")
                    val scan = result.get()
                    if (scan != null && scan.closed > 0) {
                        StudioLog.add("mcp reap closed=${scan.closed} sessions=${scan.sessions} idle=${scan.idle}", category = "mcp")
                    }
                } finally {
                    reapBusy = false
                }
            }
        }, 60L, 60L, java.util.concurrent.TimeUnit.SECONDS)
        reaper = executor
    }

    private fun stopReaper() {
        reaper?.shutdownNow()
        reaper = null
        reapWorker = null
    }

    private fun stopEngine() {
        engine?.stop(0, 80)
        engine = null
    }

    private fun copyEndpoint() {
        val endpoint = endpoints.firstOrNull().orEmpty()
        if (endpoint.isNotEmpty()) {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Endpoint", endpoint))
            Toast.makeText(this, "Endpoint 已复制", Toast.LENGTH_SHORT).show()
        }
        updateNotification()
    }

    private fun scheduleNotificationUpdate() {
        if (notificationPending) return
        notificationPending = true
        val delay = (500 - (System.currentTimeMillis() - lastNotificationAt)).coerceAtLeast(0)
        android.os.Handler(mainLooper).postDelayed({
            notificationPending = false
            lastNotificationAt = System.currentTimeMillis()
            if (running) updateNotification()
        }, delay)
    }

    private fun updateNotification() {
        val stats = McpStats.snapshot()
        val title = if (running) "阅读书源MCP · 运行中" else "阅读书源MCP · 已停止"
        val detail = buildString {
            append(endpoints.firstOrNull() ?: "暂无回环 Endpoint")
            append(" · ${stats["clientCount"] ?: 0} 个活跃会话")
            append("\n最近访问：")
            append(if ((stats["lastAccessAt"] ?: 0) > 0) java.text.DateFormat.getTimeInstance().format(stats["lastAccessAt"]) else "暂无")
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(title, detail))
    }

    private fun notification(title: String, text: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "阅读书源MCP", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("route", "mcp"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(code: Int, action: String) = PendingIntent.getService(this, code, Intent(this, McpService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "复制 Endpoint", action(1, ACTION_COPY))
            .addAction(0, "重启", action(2, ACTION_RESTART))
            .addAction(0, "停止", action(3, ACTION_STOP))
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "studio_mcp"
        private const val NOTIFICATION_ID = 1237
        private const val ACTION_STOP = "com.mina.legadostudio.STOP_MCP"
        private const val ACTION_COPY = "com.mina.legadostudio.COPY_MCP"
        private const val ACTION_RESTART = "com.mina.legadostudio.RESTART_MCP"
        @Volatile var running = false
        @Volatile var starting = false
        @Volatile var endpoints: List<String> = emptyList()

        fun start(context: Context) {
            starting = true
            ContextCompat.startForegroundService(context, Intent(context, McpService::class.java))
        }
        fun restart(context: Context) {
            starting = true
            ContextCompat.startForegroundService(context, Intent(context, McpService::class.java).setAction(ACTION_RESTART))
        }
        fun stop(context: Context) {
            starting = false
            running = false
            context.stopService(Intent(context, McpService::class.java))
        }
        fun status(context: Context, includeToken: Boolean = true): Map<String, Any> {
            val config = McpConfigStore(context).load()
            val base = mutableMapOf<String, Any>("running" to (running || starting), "endpoints" to endpoints, "port" to config.port, "tokenRequired" to config.tokenRequired)
            if (includeToken) base["token"] = config.token
            return base + McpStats.snapshot() + mapOf(
                "trackedServers" to McpSessions.tracked(),
                "trackedSessions" to McpSessions.trackedSessions(),
                "idleSessions" to McpSessions.idleSessions(),
                "reapScans" to McpSessions.scanCount(),
                "lastScanAt" to McpSessions.lastScan(),
                "reapIdleSeconds" to McpSessions.reapIdleMs / 1000,
                "maxLifetimeSeconds" to McpSessions.maxLifetimeMs / 1000,
            )
        }
    }
}
