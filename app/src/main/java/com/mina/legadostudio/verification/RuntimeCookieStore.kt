package com.mina.legadostudio.verification

import android.content.Context
import android.webkit.CookieManager
import com.mina.legadostudio.network.StudioCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 显式 Cookie 仓库：prefs 按可注册域（DomainKey eTLD+1）存整串
 * （WebView 采集 / 手工 setCookie / cookie.setCookie JS API）。
 *
 * [sessionJar] 是 HttpFetcher 的会话 Cookie jar：这里**不**把显式串种进 jar——显式串的
 * 「按可注册域共享整串」是 RuntimeCookieStore 既有语义（跨子域共享，与历史一致，刻意保留），
 * 由 StudioCookieJar 的 loadForRequest overlay 在取回时合流。这里仅持有引用用于 clear() 时
 * 同步清掉服务端 Set-Cookie 落库的同桶 cookie，保证"清除该域 Cookie"对两个通道都生效。
 * 不传 sessionJar 时保持旧行为（仅 prefs + WebView）。
 */
class RuntimeCookieStore(context: Context, private val sessionJar: StudioCookieJar? = null) {
    private val prefs = context.getSharedPreferences("runtime_cookies", Context.MODE_PRIVATE)

    suspend fun captureFromWebView(url: String): String {
        val cookie = withContext(Dispatchers.Main) {
            CookieManager.getInstance().flush()
            delay(300)
            CookieManager.getInstance().getCookie(url).orEmpty()
        }
        val domain = domain(url)
        if (domain.isNotEmpty() && cookie.isNotEmpty()) prefs.edit().putString(domain, cookie).apply()
        return cookie
    }

    fun contextFingerprint(): String = com.mina.legadostudio.mcp.TaskContextStore.digest(prefs.all.toSortedMap().toString())
    fun headerFor(url: String): String? = prefs.getString(domain(url), null)
    /** Native-only redaction inputs; never serialize this snapshot into the Web bridge. */
    internal fun sensitiveValues(): List<String> = prefs.all.values.filterIsInstance<String>().flatMap { header ->
        listOf(header) + header.split(';').mapNotNull { pair -> pair.substringAfter('=', "").takeIf { it.length >= 16 } }
    }
    fun set(url: String, cookie: String) {
        require(cookie.contains('=')) { "Cookie 必须包含 name=value" }
        prefs.edit().putString(domain(url), cookie).apply()
        cookie.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { CookieManager.getInstance().setCookie(url, it) }
        CookieManager.getInstance().flush()
    }
    /** 按 Cookie 名合并进该域已有串，未点名的旧值保留（官方 cookie.setCookie 语义）；整串替换用 set() */
    fun merge(url: String, cookie: String) {
        require(cookie.contains('=')) { "Cookie 必须包含 name=value" }
        val pairs = linkedMapOf<String, String>()
        fun absorb(raw: String) = raw.split(';').map { it.trim() }.filter { it.contains('=') }
            .forEach { pairs[it.substringBefore('=').trim()] = it.substringAfter('=').trim() }
        absorb(headerFor(url).orEmpty())
        absorb(cookie)
        set(url, pairs.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }
    fun clear(url: String) {
        prefs.edit().remove(domain(url)).apply()
        sessionJar?.clearDomain(url)
        val manager = CookieManager.getInstance()
        manager.getCookie(url).orEmpty().split(';').mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
            .forEach { name -> manager.setCookie(url, "$name=; Max-Age=0; Path=/") }
        manager.flush()
    }

    private fun domain(url: String): String = DomainKey.fromUrl(url)
}
