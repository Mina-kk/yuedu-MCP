package com.mina.legadostudio.verification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.mina.legadostudio.MainActivity
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.data.db.VerificationSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI

class VerificationCoordinator(
    private val context: Context,
    private val dao: StudioDao,
    private val cookies: RuntimeCookieStore,
    private val webState: VerificationWebViewStateStore,
) {
    fun observe() = dao.observeVerificationSessions()

    suspend fun create(jobId: String, url: String, purpose: String): VerificationSessionEntity {
        val now = System.currentTimeMillis()
        val domain = DomainKey.fromHost(runCatching { URI(url).host }.getOrNull().orEmpty())
        // 复用 WAITING 会话时同步更新 url/purpose：站点常换验证地址，沿用旧 URL 会让用户白验
        dao.waitingVerification(jobId, domain)?.let { old ->
            val refreshed = if (old.url == url && old.purpose == purpose) old
                else old.copy(url = url, purpose = purpose, updatedAt = now)
            if (refreshed != old) dao.saveVerificationSession(refreshed)
            return refreshed
        }
        val value = VerificationSessionEntity(java.util.UUID.randomUUID().toString(), jobId, domain, url, purpose, "WAITING", "", now, now)
        dao.saveVerificationSession(value)
        notifyVerification(value)
        return value
    }

    /** 该域最近一次 COMPLETED 会话是否仍在 ttl 内（ALWAYS 模式调用方应跳过本检查）。 */
    suspend fun isCompletedFresh(domain: String, ttlMs: Long = 30 * 60_000L): Boolean {
        val latest = dao.latestVerification("mcp", domain) ?: return false
        if (latest.status != "COMPLETED") return false
        return System.currentTimeMillis() - latest.updatedAt < ttlMs
    }

    suspend fun complete(id: String, finalUrl: String): VerificationSessionEntity {
        val old = dao.verificationSession(id) ?: error("验证任务不存在")
        cookies.captureFromWebView(finalUrl)
        val completed = old.copy(status = "COMPLETED", finalUrl = finalUrl, updatedAt = System.currentTimeMillis()).also { dao.saveVerificationSession(it) }
        webState.clear(id)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId(id))
        // 部分 CF/JS 挑战的 cookie 在页面通过后才异步下发，延迟二次采集合并
        CoroutineScope(Dispatchers.IO).launch {
            delay(1_000)
            runCatching { cookies.captureFromWebView(finalUrl) }
        }
        return completed
    }

    private fun notifyVerification(session: VerificationSessionEntity) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("studio_mcp", "MCP 服务", NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(context, MainActivity::class.java).putExtra("route", "verification")
        val pending = PendingIntent.getActivity(context, session.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "studio_mcp")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("阅读书源MCP等待网站验证")
            .setContentText("${session.domain} · 点击在 App 内完成")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId(session.id), notification)
    }

    suspend fun updateCurrentUrl(id: String, url: String) {
        val old = dao.verificationSession(id) ?: return
        dao.saveVerificationSession(old.copy(finalUrl = url, updatedAt = System.currentTimeMillis()))
    }

    private fun notificationId(id: String) = 7_000 + id.hashCode().and(0x0fff)

    suspend fun close(id: String) {
        webState.clear(id)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId(id))
        dao.deleteVerificationSession(id)
    }

    suspend fun clear(id: String) {
        val old = dao.verificationSession(id) ?: return
        cookies.clear(old.url)
        webState.clear(id)
        dao.saveVerificationSession(old.copy(status = "WAITING", finalUrl = "", updatedAt = System.currentTimeMillis()))
    }
}
