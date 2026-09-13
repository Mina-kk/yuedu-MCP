package com.mina.legadostudio.verification

import android.content.Context
import android.webkit.CookieManager

class RuntimeCookieStore(context: Context) {
    private val prefs = context.getSharedPreferences("runtime_cookies", Context.MODE_PRIVATE)

    fun captureFromWebView(url: String): String {
        val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
        val domain = domain(url)
        if (domain.isNotEmpty() && cookie.isNotEmpty()) prefs.edit().putString(domain, cookie).apply()
        return cookie
    }

    fun contextFingerprint(): String = com.mina.legadostudio.mcp.TaskContextStore.digest(prefs.all.toSortedMap().toString())
    fun headerFor(url: String): String? = prefs.getString(domain(url), null)
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
        val manager = CookieManager.getInstance()
        manager.getCookie(url).orEmpty().split(';').mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
            .forEach { name -> manager.setCookie(url, "$name=; Max-Age=0; Path=/") }
        manager.flush()
    }

    private fun domain(url: String): String = DomainKey.fromUrl(url)
}
