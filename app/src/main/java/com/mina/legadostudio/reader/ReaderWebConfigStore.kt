package com.mina.legadostudio.reader

import android.content.Context
import com.mina.legadostudio.security.SecureStringStore

/**
 * 阅读 App 的 Web 服务连接配置：baseUrl 明文存 SharedPreferences，访问令牌走
 * [SecureStringStore]（AndroidKeyStore AES-GCM），与 MCP 自身的 [com.mina.legadostudio.mcp.McpConfigStore]
 * 完全分离——改这里不重启 MCP、不推进 ContextEpoch。
 *
 * 保存地址与「阅读服务当前是否在线」无关：用户可以先填好局域网地址，阅读稍后再开。
 *
 * 配置带单调递增的 `revision`：保存值（origin 或令牌）真正变化时才 +1，供客户端识别过期配置。
 */
class ReaderWebConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("reader_web_config", Context.MODE_PRIVATE)
    private val secure = SecureStringStore(context.applicationContext, "studio_reader_web_token_v1", "secure_reader_web_token_v1")

    /** @property revision 配置版本号：保存值每变化一次 +1；初次保存为 1，未配置为 0。 */
    data class Config(val baseUrl: String, val token: String, val revision: Long = 0L)

    /** 返回 null 表示尚未配置；baseUrl 恒为已规范化的 origin。 */
    fun load(): Config? {
        val baseUrl = ReaderWebOrigin.normalizeOrNull(prefs.getString(KEY_BASE_URL, null)) ?: return null
        return Config(baseUrl, secure.get(KEY_TOKEN).orEmpty(), prefs.getLong(KEY_REVISION, 0L))
    }

    /**
     * 保存连接配置。令牌与 origin 绑定：地址变了且令牌框留空时清除旧令牌，
     * 避免把旧设备的令牌发往新地址。
     * @param token 传 `null` 表示「界面上留空」：origin 未变则保持已存令牌，origin 变了则清除；
     *   传空串会清除令牌；非空则覆盖。
     * @throws IllegalArgumentException 地址不合法
     */
    fun save(baseUrl: String, token: String?) {
        val previousOrigin = ReaderWebOrigin.normalizeOrNull(prefs.getString(KEY_BASE_URL, null))
        val previousToken = secure.get(KEY_TOKEN)
        val previousRevision = prefs.getLong(KEY_REVISION, 0L)
        val resolved = ReaderWebConfigPolicy.resolve(previousOrigin, previousToken, baseUrl, token)
        val originChanged = previousOrigin != resolved.origin
        if (originChanged) secure.remove(KEY_TOKEN)
        when {
            resolved.token.isNullOrEmpty() && !originChanged -> secure.remove(KEY_TOKEN)
            !resolved.token.isNullOrEmpty() && (originChanged || resolved.token != previousToken) ->
                secure.put(KEY_TOKEN, resolved.token)
        }
        val revision = ReaderWebConfigRevision.next(
            previousRevision,
            ReaderWebConfigRevision.changed(previousOrigin, previousToken, resolved.origin, resolved.token),
        )
        prefs.edit()
            .putString(KEY_BASE_URL, resolved.origin)
            .putLong(KEY_REVISION, revision)
            .apply()
    }

    /** 地址与令牌一并清除；revision 保持单调，便于识别「清除后重新配置」也是一次新状态。 */
    fun clear() {
        prefs.edit().remove(KEY_BASE_URL).apply()
        secure.remove(KEY_TOKEN)
    }

    /** 是否已存令牌；界面只用它提示「已保存」，不回显令牌本体。 */
    fun hasToken(): Boolean = !secure.get(KEY_TOKEN).isNullOrEmpty()

    companion object {
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_TOKEN = "token"
        private const val KEY_REVISION = "revision"
    }
}

/**
 * 配置 revision 的纯判定（不碰 Android prefs，JVM 可测）。
 *
 * 只有**保存值真正变化**才 +1：同样地址、同样令牌重复点保存不推进版本，
 * 否则模型无法区分「用户改了配置」与「用户又点了一次保存」。
 */
internal object ReaderWebConfigRevision {
    fun next(previous: Long, changed: Boolean): Long =
        if (changed && previous < Long.MAX_VALUE) previous + 1 else previous

    /** 令牌比较用空串归一：null 与 "" 都表示没有令牌，不算变化。 */
    fun changed(previousOrigin: String?, previousToken: String?, origin: String, token: String?): Boolean =
        previousOrigin != origin || previousToken.orEmpty() != token.orEmpty()
}
