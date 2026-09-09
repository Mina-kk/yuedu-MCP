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
    fun clear(url: String) {
        prefs.edit().remove(domain(url)).apply()
        val manager = CookieManager.getInstance()
        manager.getCookie(url).orEmpty().split(';').mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
            .forEach { name -> manager.setCookie(url, "$name=; Max-Age=0; Path=/") }
        manager.flush()
    }

    private fun domain(url: String): String = DomainKey.fromUrl(url)
}
