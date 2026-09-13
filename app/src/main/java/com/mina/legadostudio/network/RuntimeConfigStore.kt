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
     * -1 自动(不干预：不写入 bookSourceType、二进制资源仅记录元数据) / 0 文本(默认) / 1 音频 / 2 图片 / 3 文件 / 4 视频。
     * 图文漫画等类型不确定的站点用「自动」；其余类型按对应媒体处理。
     */
    var bookSourceType: Int
        get() = prefs.getInt("bookSourceType", 0).takeIf { it in -1..4 } ?: 0
        set(value) {
            require(value in -1..4) { "书源类型必须在 -1..4" }
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
        const val TYPE_AUTO = -1
        val TYPE_NAMES = listOf("文本", "音频", "图片", "文件", "视频")
        fun typeName(type: Int): String = if (type == TYPE_AUTO) "自动" else TYPE_NAMES.getOrElse(type) { "文本" }
    }
}
