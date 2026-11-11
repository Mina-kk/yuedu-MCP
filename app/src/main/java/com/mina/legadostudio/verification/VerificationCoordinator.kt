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
import com.mina.legadostudio.web.WebBridgePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun verificationNotificationId(id: String): Int = 10_000 + (id.hashCode() and 0x7fffffff) % 1_000_000

internal fun shouldSkipComplete(status: String): Boolean = status == "COMPLETED"

class VerificationSessionNotFoundException(val sessionId: String) : IllegalStateException("验证任务不存在")

class VerificationCoordinator internal constructor(
    private val context: Context,
    private val dao: StudioDao,
    private val cookies: RuntimeCookieStore,
    private val webState: VerificationWebViewStateStore,
    private val dismissNotification: (String) -> Unit,
) {
    constructor(context: Context, dao: StudioDao, cookies: RuntimeCookieStore, webState: VerificationWebViewStateStore) :
        this(context, dao, cookies, webState, { id ->
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(verificationNotificationId(id))
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                VerificationOverlayManager.hideVerificationAlert(id)
            }
        })

    // All read/modify/write paths share one boundary, including WAITING reuse and deletion.
    // Pollers take snapshots under the lock but never hold it across their delay/await loops.
    private val stateLock = Mutex()

    private val browserGenerations = mutableMapOf<String, Any>()

    suspend fun beginBrowserGeneration(id: String, expectedUrl: String): Any? = stateLock.withLock {
        val row = dao.verificationSession(id) ?: return@withLock null
        if (row.status != "WAITING" || row.kind != "webview" || row.jobId == "manual" || row.url != expectedUrl) return@withLock null
        Any().also { browserGenerations[id] = it }
    }

    fun observe() = dao.observeVerificationSessions()

    suspend fun create(
        jobId: String,
        url: String,
        purpose: String,
        kind: String = "webview",
        imageData: String = "",
    ): VerificationSessionEntity = stateLock.withLock {
        // Validate before even looking up a reusable WAITING row: bad requests must not mutate
        // an existing session, fetch cookies, notify the user, or leave an unopenable record.
        VerificationRequestPolicy.requireUrl(url)
        val now = System.currentTimeMillis()
        val domain = DomainKey.fromUrl(url)
        // 复用 WAITING 会话时同步更新 url/purpose：站点常换验证地址，沿用旧 URL 会让用户白验
        dao.waitingVerification(jobId, domain)?.let { old ->
            val refreshed = if (old.url == url && old.purpose == purpose && old.kind == kind) old
                else old.copy(url = url, purpose = purpose, kind = kind, imageData = imageData, updatedAt = now)
            if (refreshed != old) { browserGenerations.remove(old.id); dao.saveVerificationSession(refreshed) }
            return@withLock refreshed
        }
        val value = VerificationSessionEntity(java.util.UUID.randomUUID().toString(), jobId, domain, url, purpose, "WAITING", "", now, now, kind, "", imageData)
        dao.saveVerificationSession(value)
        notifyVerification(value)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            VerificationOverlayManager.showVerificationAlert(context, domain, value.id, purpose)
        }
        value
    }

    /**
     * image_code 会话专用：轮询等用户把验证码写进 answer，超时返回 null。
     * 由 java.getVerificationCode 的协程调用；与 browser_verify 的 waitSec 语义一致。
     */
    suspend fun awaitAnswer(id: String, timeoutSec: Int = 120): String? {
        var waited = 0
        while (waited < timeoutSec) {
            val s = stateLock.withLock { dao.verificationSession(id) } ?: return null
            if (s.status == "COMPLETED" && s.answer.isNotBlank()) return s.answer
            if (s.status != "WAITING" && s.status != "COMPLETED") return null
            delay(1_000); waited++
        }
        return null
    }

    /**
     * webview 会话专用：轮询等用户在验证中心 WebView 里完成过盾（status==COMPLETED），
     * 返回完成后的 finalUrl；超时/用户取消返回 null。由 java.startBrowserAwait 的沙箱实现调用。
     */
    suspend fun awaitCompleted(id: String, timeoutSec: Int = 120): String? {
        var waited = 0
        while (waited < timeoutSec) {
            val s = stateLock.withLock { dao.verificationSession(id) } ?: return null
            if (s.status == "COMPLETED") return s.finalUrl.ifBlank { s.url }
            if (s.status != "WAITING") return null
            delay(1_000); waited++
        }
        return null
    }

    /** 该域最近一次 COMPLETED 会话是否仍在 ttl 内（ALWAYS 模式调用方应跳过本检查）。 */
    suspend fun isCompletedFresh(domain: String, ttlMs: Long = 30 * 60_000L): Boolean {
        val latest = stateLock.withLock { dao.latestVerification("mcp", domain) } ?: return false
        if (latest.status != "COMPLETED") return false
        return System.currentTimeMillis() - latest.updatedAt < ttlMs
    }

    suspend fun complete(id: String, finalUrl: String, answer: String = ""): VerificationSessionEntity = stateLock.withLock {
        val old = dao.verificationSession(id) ?: throw VerificationSessionNotFoundException(id)
        require(WebBridgePolicy.canCompleteVerification(old.kind, old.url)) { "验证任务缺少可打开的公网地址，请先补全地址" }
        if (shouldSkipComplete(old.status)) return@withLock old
        // image_code 会话不采 WebView Cookie（没有网页过程），只落答案；webview 会话照旧采
        if (old.kind != "image_code") cookies.captureFromWebView(finalUrl)
        val completed = old.copy(status = "COMPLETED", finalUrl = finalUrl, answer = answer, updatedAt = System.currentTimeMillis()).also { dao.saveVerificationSession(it) }
        webState.clear(id)
        dismissNotification(id)
        // 部分 CF/JS 挑战的 cookie 在页面通过后才异步下发，延迟二次采集合并
        if (old.kind != "image_code") {
            CoroutineScope(Dispatchers.IO).launch {
                delay(1_000)
                runCatching { cookies.captureFromWebView(finalUrl) }
            }
        }
        completed
    }

    /** Candidate only: persistence and generation are checked within the same mutation mutex. */
    suspend fun completeAutomatically(
        id: String, expectedUrl: String, generation: Any, finalUrl: String,
        stillCurrent: suspend () -> Boolean,
    ): VerificationSessionEntity? = stateLock.withLock {
        val old = dao.verificationSession(id) ?: return@withLock null
        if (old.status != "WAITING" || old.jobId == "manual" || old.kind != "webview" ||
            old.url != expectedUrl || browserGenerations[id] !== generation || !stillCurrent()) return@withLock null
        VerificationRequestPolicy.requireUrl(old.url)
        VerificationRequestPolicy.requireUrl(finalUrl)
        cookies.captureFromWebView(finalUrl)
        // Cookie capture may suspend. A host close/navigation must still invalidate this candidate.
        if (!stillCurrent()) return@withLock null
        val completed = old.copy(status = "COMPLETED", finalUrl = finalUrl, updatedAt = System.currentTimeMillis())
        dao.saveVerificationSession(completed)
        browserGenerations.remove(id)
        webState.clear(id)
        dismissNotification(id)
        completed
    }

    /** Explicit user repair only; never infer a missing site or reuse an old completion. */
    suspend fun repairAddress(id: String, url: String): VerificationSessionEntity = stateLock.withLock {
        VerificationRequestPolicy.requireUrl(url)
        val old = dao.verificationSession(id) ?: throw VerificationSessionNotFoundException(id)
        require(old.status == "WAITING" || old.status == "COMPLETED") { "当前验证任务状态不允许补全地址" }
        require(!WebBridgePolicy.isVerificationUrlAllowed(old.url)) { "验证任务已有可打开地址，不能通过补全地址替换" }
        val repaired = old.copy(
            domain = DomainKey.fromUrl(url), url = url, status = "WAITING", finalUrl = "", answer = "",
            updatedAt = System.currentTimeMillis(),
        )
        // Discard only this task's stale WebView navigation state. Runtime/WebView Cookies stay.
        webState.clear(id)
        dao.saveVerificationSession(repaired)
        repaired
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

    suspend fun updateCurrentUrl(id: String, url: String, stillCurrent: () -> Boolean = { true }): Unit = stateLock.withLock {
        if (!stillCurrent()) return@withLock
        val old = dao.verificationSession(id) ?: return@withLock
        if (old.status != "WAITING") return@withLock
        dao.saveVerificationSession(old.copy(finalUrl = url, updatedAt = System.currentTimeMillis()))
    }

    private fun notificationId(id: String) = verificationNotificationId(id)

    suspend fun close(id: String): Unit = stateLock.withLock {
        browserGenerations.remove(id)
        webState.clear(id)
        dismissNotification(id)
        dao.deleteVerificationSession(id)
    }

    suspend fun clear(id: String): Unit = stateLock.withLock {
        browserGenerations.remove(id)
        val old = dao.verificationSession(id) ?: return@withLock
        cookies.clear(old.url)
        webState.clear(id)
        dao.saveVerificationSession(old.copy(status = "WAITING", finalUrl = "", updatedAt = System.currentTimeMillis()))
    }
}
