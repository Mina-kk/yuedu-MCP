package com.mina.legadostudio.web

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor

/** Process-wide WebView proxy override, held only while an isolated verification view exists.
 * Reader's saved LAN web session is suspended explicitly; native Reader HTTP tools are unaffected.
 * Never whitelist the saved LAN origin here: an external page could target the same origin.
 */
internal object WebProxyBoundary {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val directExecutor = Executor { it.run() }
    private var owner: Any? = null
    private var proxy: PublicWebProxy? = null
    @Volatile var active = false
        private set

    suspend fun acquire(requester: Any, beforeEnable: suspend () -> Unit) = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) { "请升级 Android System WebView：当前版本不支持安全验证网络隔离" }
            check(!active) { "验证网络正在切换，请关闭验证页后重试" }
            active = true
            owner = requester
            try {
                beforeEnable()
                val local = withContext(Dispatchers.IO) { PublicWebProxy() }
                proxy = local
                val config = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:${local.port}")
                    .removeImplicitRules()
                    .build()
                val ready = CompletableDeferred<Unit>()
                ProxyController.getInstance().setProxyOverride(config, directExecutor) { ready.complete(Unit) }
                withTimeout(15_000) { ready.await() }
            } catch (error: Exception) {
                withContext(NonCancellable) { clearLocked() }
                throw error
            }
        }
    }

    /** Caller destroys the external WebView before returning this lease. */
    fun release(requester: Any) {
        scope.launch { mutex.withLock { if (owner === requester) clearLocked() } }
    }

    private suspend fun clearLocked() {
        if (proxy == null) { owner = null; active = false; return }
        // Keep the rejecting proxy alive on a failed clear; never fail open or add a DIRECT rule.
        try {
            val cleared = CompletableDeferred<Unit>()
            ProxyController.getInstance().clearProxyOverride(directExecutor) { cleared.complete(Unit) }
            withTimeout(15_000) { cleared.await() }
            proxy?.close()
            proxy = null
            owner = null
            active = false
        } catch (_: Exception) {
            com.mina.legadostudio.mcp.StudioLog.add("验证网络隔离清理未完成，请重启应用", "W", "verification")
        }
    }
}
