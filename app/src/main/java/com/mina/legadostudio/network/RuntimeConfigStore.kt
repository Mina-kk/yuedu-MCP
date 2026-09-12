package com.mina.legadostudio.network

import android.content.Context

class RuntimeConfigStore(
    context: Context,
    private val onConfigChanged: (() -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("runtime_network_config", Context.MODE_PRIVATE)
    var userAgent: String
        get() = prefs.getString("userAgent", null)?.takeIf { it.isNotBlank() } ?: HttpFetcher.DEFAULT_UA
        set(value) {
            require(value.trim().length in 8..500) { "User-Agent 长度必须在 8..500" }
            val current = userAgent
            val next = value.trim()
            if (current != next) {
                prefs.edit().putString("userAgent", next).apply()
                triggerBump()
            }
        }

    /**
     * 目标书源类型,与阅读(Legado)bookSourceType 对齐:
     * 0 文本(默认) / 1 音频 / 2 图片 / 3 文件 / 4 视频。
     * 文本类型下抓取会拦截图片、音视频等二进制响应;其余类型保留二进制元数据。
     */
    var bookSourceType: Int
        get() = prefs.getInt("bookSourceType", 0).takeIf { it in 0..4 } ?: 0
        set(value) {
            require(value in 0..4) { "书源类型必须在 0..4" }
            val current = bookSourceType
            if (current != value) {
                prefs.edit().putInt("bookSourceType", value).apply()
                triggerBump()
            }
        }

    private fun triggerBump() {
        onConfigChanged?.invoke() ?: runCatching {
            (appContext as? com.mina.legadostudio.StudioApplication)?.contextEpoch?.bump()
                ?: com.mina.legadostudio.mcp.ContextEpochStore(appContext).bump()
        }
    }

    companion object {
        val TYPE_NAMES = listOf("文本", "音频", "图片", "文件", "视频")
        fun typeName(type: Int): String = TYPE_NAMES.getOrElse(type) { "文本" }
    }
}
