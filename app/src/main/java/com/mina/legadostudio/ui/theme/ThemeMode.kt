package com.mina.legadostudio.ui.theme

/**
 * 主题模式：跟随系统 / 浅色 / 深色。
 *
 * 纯逻辑（无 Compose / 无 Android 依赖），行为与旧 Compose 设置页一致：
 * - [fromKey] 未知/空 key 一律回落 [SYSTEM]；
 * - [resolveDark] 只做「模式 + 系统深色」→ 是否深色的判定，真实取色由前端负责。
 *
 * 持久化已迁出原生侧（键名 `studio_theme` / 值 `mode`，取值即 [key]），
 * 由 Rust/Vue 前端按同一键值语义读写，因此这里的枚举顺序与 key/label 属对外契约。
 */
enum class ThemeMode(val key: String, val label: String) {
    LIGHT("light", "浅色"),
    SYSTEM("system", "跟随系统"),
    DARK("dark", "深色");

    companion object {
        /** 持久化键名（旧 SharedPreferences 文件名，前端沿用同一键）。 */
        const val PREFS_NAME: String = "studio_theme"

        /** 持久化值键名。 */
        const val PREFS_MODE_KEY: String = "mode"

        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM

        fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
            SYSTEM -> systemDark
            LIGHT -> false
            DARK -> true
        }
    }
}
