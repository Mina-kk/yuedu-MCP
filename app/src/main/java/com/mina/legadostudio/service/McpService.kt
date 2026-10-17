package com.mina.legadostudio.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mina.legadostudio.MainActivity
import com.mina.legadostudio.mcp.McpAccess
import com.mina.legadostudio.mcp.McpConfigStore
import com.mina.legadostudio.mcp.McpSessions
import com.mina.legadostudio.mcp.McpStats
import com.mina.legadostudio.mcp.McpToolCompactor
import com.mina.legadostudio.mcp.StudioLog
import com.mina.legadostudio.mcp.StudioMcpServer
import com.mina.legadostudio.mcp.configureStudioMcp
import com.mina.legadostudio.verification.VerificationOverlayManager
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiLock? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var lastAddresses: Set<String> = emptySet()
    private val networkHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification("MCP 服务启动中", "正在绑定本机回环 Endpoint"))
        
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yueduMCP:service")
            wakeLock?.acquire()
        }.onFailure { StudioLog.add("wakelock acquire err", "W", "mcp", it.localizedMessage.orEmpty()) }
        
        @Suppress("DEPRECATION")
        runCatching {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yueduMCP:service")
            wifiLock?.acquire()
        }.onFailure { StudioLog.add("wifilock acquire err", "W", "mcp", it.localizedMessage.orEmpty()) }
        
        McpStats.setListener(::scheduleNotificationUpdate)
        VerificationOverlayManager.refresh(this)
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            private val debounceRunnable = Runnable { checkNetworkChange() }
            
            override fun onAvailable(network: Network) {
                networkHandler.removeCallbacks(debounceRunnable)
                networkHandler.postDelayed(debounceRunnable, 2000)
            }
            
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                networkHandler.removeCallbacks(debounceRunnable)
                networkHandler.postDelayed(debounceRunnable, 2000)
            }
        }
        networkCallback = callback
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }.onFailure { StudioLog.add("network callback register err", "W", "mcp", it.localizedMessage.orEmpty()) }
    }
    
    private fun checkNetworkChange() {
        val current = McpAccess.localAddresses().map { it.hostAddress ?: "" }.toSet()
        if (current != lastAddresses) {
            StudioLog.add("mcp network change", "I", "mcp", "old=${lastAddresses.size} new=${current.size}")
            startServer()
        }
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
        networkCallback?.let {
            runCatching {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        networkHandler.removeCallbacksAndMessages(null)
        
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null
        
        McpStats.setListener(null)
        VerificationOverlayManager.hideAll()
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
        // 重启开始新一轮工具使用统计：旧轮次在途调用完成后不落进新统计（代际隔离）
        com.mina.legadostudio.mcp.McpToolUsageRegistry.current = com.mina.legadostudio.mcp.McpToolUsage()
        val store = McpConfigStore(this)
        val config = store.load()
        val addresses = McpAccess.localAddresses()
        lastAddresses = addresses.map { it.hostAddress ?: "" }.toSet()
        val hosts = McpAccess.allowedHosts(addresses)
        val origins = McpAccess.allowedOrigins(hosts)
        try {
            engine = embeddedServer(CIO, host = "0.0.0.0", port = config.port) {
                configureStudioMcp(store::load, hosts, origins) {
                    // 新会话 initialize 的 URL 决定本连接是否精简 tools/list；?compact=1 时工具/入参
                    // 描述截断，完整定义仍可经 studio://tools/{name} 读回。默认逐字不变。
                    val compact = call.request.queryParameters[McpToolCompactor.QUERY_PARAM] == McpToolCompactor.QUERY_VALUE
                    StudioMcpServer(this@McpService, compact = compact).create()
                }
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
            runCatching {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification("MCP 启动失败", error.localizedMessage.orEmpty()))
            }.onFailure { StudioLog.add("notification err", "W", "mcp", it.localizedMessage.orEmpty()) }
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

    private fun clientEndpoint(): String =
        endpoints.firstOrNull()
            ?.let { McpAccess.connectionUrl(it, McpConfigStore(this).loadCompactMode()) }
            .orEmpty()

    private fun copyEndpoint() {
        val endpoint = clientEndpoint()
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
            append(clientEndpoint().ifEmpty { "暂无回环 Endpoint" })
            append(" · ${stats["clientCount"] ?: 0} 个活跃会话")
            append("\n最近访问：")
            append(if ((stats["lastAccessAt"] ?: 0) > 0) java.text.DateFormat.getTimeInstance().format(stats["lastAccessAt"]) else "暂无")
        }
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(title, detail))
        }.onFailure { StudioLog.add("notification err", "W", "mcp", it.localizedMessage.orEmpty()) }
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
            )
        }
    }
}
