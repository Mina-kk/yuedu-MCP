package com.mina.legadostudio.verification

import android.content.Context

/** 每域验证模式：AUTO=按需（先人工一次，再 WebView），ALWAYS=每次都人工验（适合一搜一验、验证地址总变的站点），WEBVIEW=固定走 WebView 抓取。 */
enum class DomainVerifyMode { AUTO, ALWAYS, WEBVIEW }

class DomainModeStore(context: Context) {
    private val prefs = context.getSharedPreferences("domain_runtime_modes", Context.MODE_PRIVATE)

    fun contextFingerprint(): String = com.mina.legadostudio.mcp.TaskContextStore.digest(prefs.all.toSortedMap().toString())

    fun modeFor(url: String): DomainVerifyMode {
        val domain = DomainKey.fromUrl(url)
        val stored = prefs.getString("mode:$domain", null)
        if (stored != null) {
            return runCatching { DomainVerifyMode.valueOf(stored.uppercase()) }.getOrDefault(DomainVerifyMode.AUTO)
        }
        // 旧版布尔 key 懒迁移：webview:<domain>=true 视为 WEBVIEW 模式
        if (prefs.getBoolean("webview:$domain", false)) {
            prefs.edit().putString("mode:$domain", DomainVerifyMode.WEBVIEW.name).remove("webview:$domain").apply()
            return DomainVerifyMode.WEBVIEW
        }
        return DomainVerifyMode.AUTO
    }

    fun setMode(domain: String, mode: DomainVerifyMode) {
        prefs.edit().putString("mode:$domain", mode.name).apply()
    }

    fun requiresWebView(url: String): Boolean = modeFor(url) == DomainVerifyMode.WEBVIEW

    fun requireWebView(url: String, required: Boolean = true) {
        // 兼容旧调用：等价于在 AUTO 与 WEBVIEW 之间切换
        setMode(DomainKey.fromUrl(url), if (required) DomainVerifyMode.WEBVIEW else DomainVerifyMode.AUTO)
    }

    /** 列出全部已设置的每域模式（domain -> auto/always/webview），供 MCP 查询。 */
    fun allModes(): Map<String, String> = prefs.all.mapNotNull { (key, value) ->
        (key as? String)?.takeIf { it.startsWith("mode:") }?.let { domain ->
            val mode = (value as? String)?.lowercase() ?: return@let null
            domain.removePrefix("mode:") to mode
        }
    }.toMap()
}
