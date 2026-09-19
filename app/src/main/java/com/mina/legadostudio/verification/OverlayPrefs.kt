package com.mina.legadostudio.verification

import android.content.Context

/**
 * 悬浮窗开关持久化：用户显式开启/关闭常驻悬浮球。
 * 未开启或未完成系统授权时不显示任何悬浮内容。
 */
object OverlayPrefs {
    private const val FILE = "studio_overlay"
    private const val KEY_ENABLED = "floating_ball_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
