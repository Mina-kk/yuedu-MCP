package com.mina.legadostudio.device

import androidx.annotation.Keep
import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mina.legadostudio.mcp.McpAccess
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceReadiness(private val context: Context) {
    @Keep
    data class State(
        val notificationPermission: Boolean,
        val notificationsEnabled: Boolean,
        val mcpChannelEnabled: Boolean,
        val batteryUnrestricted: Boolean,
        val overlayPermission: Boolean,
        val localAddresses: List<String>,
        val portAvailable: Boolean,
        val manufacturer: String,
    )

    fun inspect(port: Int, serviceRunning: Boolean): State {
        val permission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fun channelEnabled(id: String) = notificationManager.getNotificationChannel(id)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } ?: true
        val battery = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
        val overlay = Settings.canDrawOverlays(context)
        val addresses = McpAccess.localAddresses().mapNotNull { it.hostAddress }
        return State(permission, NotificationManagerCompat.from(context).areNotificationsEnabled(), channelEnabled("studio_mcp"), battery, overlay, addresses, serviceRunning || canBind(port), Build.MANUFACTURER.orEmpty())
    }

    suspend fun checkMcpHealth(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            (URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection).run {
                 connectTimeout = 500
                 readTimeout = 500
                requestMethod = "GET"
                useCaches = false
                try { responseCode == 200 && inputStream.bufferedReader().use { it.readText() }.contains("\"status\":\"ok\"") } finally { disconnect() }
            }
        }.getOrDefault(false)
    }

    fun openNotificationSettings() = launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    fun openNotificationChannel(channelId: String) = launch(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channelId))
    fun openOverlaySettings() = launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))

    fun requestBatteryUnrestricted() {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        if (manufacturer.contains("xiaomi", true) || manufacturer.contains("redmi", true)) {
            val miui = Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", context.packageName)
                .putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString())
            if (launch(miui)) return
        }
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        if (!launch(direct)) launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun openAppDetails() = launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    fun openAutoStart() {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        )
        val opened = candidates.any { launch(Intent().setComponent(it)) }
        if (!opened) openAppDetails()
    }

    private fun canBind(port: Int): Boolean = runCatching { ServerSocket(port).use { true } }.getOrDefault(false)
    private fun launch(intent: Intent): Boolean = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
}
