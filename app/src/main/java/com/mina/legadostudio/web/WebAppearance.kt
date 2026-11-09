package com.mina.legadostudio.web

import android.content.Context

/**
 * Web UI 外观（palette + 模式）的宿主侧持久化。
 *
 * 契约来源：`docs/VUE_UI_CONTRACT.md`（10 套配色、名称、顺序、确切 hex 与
 * `frontend-vue/src/lib/palettes.js` 一致）与 `.tasks/COORDINATION.md`
 * （旧 UI 清理后原生侧不再有 Compose 主题 store，宿主必须自建）。
 *
 * 关键点：
 * - **palette 存的是 ID**（`lime`…`cherry`），不是颜色 hex；hex 只用于系统栏/背景同步；
 * - 模式在宿主侧是 `light/system/dark`，Vue 侧是 `light/auto/dark`：
 *   `system ↔ auto` 是唯一映射差异，在 [toVueMode] / [fromVueMode] 明确转换，别让两边互猜；
 * - 旧键 `studio_theme/mode`（取值 light/system/dark）首次读取时迁移，迁移后保留旧键不动
 *   （前端仍可能读它），避免破坏既有用户设置。
 */
object WebAppearance {

    /** 10 套配色，顺序与 `palettes.js` 完全一致。 */
    val PALETTE_KEYS: List<String> = listOf(
        "lime", "lava", "royal", "pink", "neon", "mint", "crystal", "crimson", "porcelain", "cherry",
    )

    const val DEFAULT_PALETTE = "lime"

    /** 宿主侧模式（与旧 ThemeMode 一致）。 */
    val HOST_MODES: List<String> = listOf("light", "system", "dark")

    /** Vue 侧模式。 */
    val VUE_MODES: List<String> = listOf("light", "auto", "dark")

    const val DEFAULT_HOST_MODE = "system"

    /** 旧 Compose 主题 store 的键（只读迁移源）。 */
    private const val LEGACY_PREFS = "studio_theme"
    private const val LEGACY_MODE_KEY = "mode"

    /** 新宿主外观 store。 */
    private const val PREFS = "studio_web_appearance"
    private const val KEY_PALETTE = "palette"
    private const val KEY_MODE = "mode"
    private const val KEY_MIGRATED = "migratedFromLegacy"

    data class State(val palette: String, val mode: String) {
        val vueMode: String get() = toVueMode(mode)
        val vuePaletteIndex: Int get() = PALETTE_KEYS.indexOf(palette).coerceAtLeast(0)
    }

    /** 未知/空 palette 回落 lime；未知模式回落 system（与旧 ThemeMode.fromKey 语义一致）。 */
    fun normalizePalette(raw: String?): String {
        val key = raw?.trim()?.lowercase().orEmpty()
        return if (key in PALETTE_KEYS) key else DEFAULT_PALETTE
    }

    fun normalizeMode(raw: String?): String {
        val key = raw?.trim()?.lowercase().orEmpty()
        return if (key in HOST_MODES) key else DEFAULT_HOST_MODE
    }

    fun toVueMode(hostMode: String): String = when (normalizeMode(hostMode)) {
        "light" -> "light"
        "dark" -> "dark"
        else -> "auto"
    }

    fun fromVueMode(vueMode: String?): String = when (vueMode?.trim()?.lowercase()) {
        "light" -> "light"
        "dark" -> "dark"
        "auto", "system" -> "system"
        else -> DEFAULT_HOST_MODE
    }

    fun isDark(mode: String, systemDark: Boolean): Boolean = when (normalizeMode(mode)) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }

    fun load(context: Context): State {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            val legacy = context.applicationContext
                .getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getString(LEGACY_MODE_KEY, null)
            prefs.edit()
                .putString(KEY_MODE, normalizeMode(legacy))
                .putString(KEY_PALETTE, DEFAULT_PALETTE)
                .putBoolean(KEY_MIGRATED, true)
                .apply()
        }
        return State(
            palette = normalizePalette(prefs.getString(KEY_PALETTE, null)),
            mode = normalizeMode(prefs.getString(KEY_MODE, null)),
        )
    }

    /** 保存：只写变化过的字段；palette 必须是已知 ID（调用方已校验）。 */
    fun save(context: Context, palette: String?, mode: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().putBoolean(KEY_MIGRATED, true)
        palette?.let { editor.putString(KEY_PALETTE, normalizePalette(it)) }
        mode?.let { editor.putString(KEY_MODE, normalizeMode(it)) }
        editor.apply()
    }

    /** 同步旧键，保证前端若仍读 `studio_theme/mode` 时看到一致值。 */
    fun mirrorLegacyMode(context: Context, mode: String) {
        context.applicationContext
            .getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .edit().putString(LEGACY_MODE_KEY, normalizeMode(mode)).apply()
    }
}
