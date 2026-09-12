package com.mina.legadostudio.mcp

import android.content.Context
import android.content.SharedPreferences

/**
 * 记录运行环境 Epoch。
 * 仅在 MCP Token 变更、User-Agent 变更、书源类型变更时 bump。
 * Cookie 或域名模式变更不改变 Epoch，避免使已抓取网页/结果引用作废。
 */
class ContextEpochStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("context_epoch", Context.MODE_PRIVATE))

    val value: Int
        get() = prefs.getInt(KEY_EPOCH, 0)

    @Synchronized
    fun bump(): Int {
        val next = value + 1
        prefs.edit().putInt(KEY_EPOCH, next).apply()
        return next
    }

    companion object {
        private const val KEY_EPOCH = "epoch"
    }
}
