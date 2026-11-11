package com.mina.legadostudio.mcp

import android.content.Context
import android.content.SharedPreferences
import com.mina.legadostudio.security.SecureStringStore
import java.security.SecureRandom

class McpConfigStore internal constructor(
    private val prefs: SharedPreferences,
    private val readToken: () -> String?,
    private val writeToken: (String) -> Unit,
    private val onTokenChanged: () -> Unit,
) {
    constructor(context: Context, onTokenChanged: (() -> Unit)? = null) : this(
        context, SecureStringStore(context, "studio_mcp_token_v1", "secure_mcp_token_v1"), onTokenChanged,
    )

    private constructor(context: Context, secure: SecureStringStore, changed: (() -> Unit)?) : this(
        context.getSharedPreferences("mcp_config", Context.MODE_PRIVATE),
        { secure.get("token") },
        { token ->
            secure.put("token", token)
            // commit also waits for preceding apply writes on these encrypted preferences.
            check(context.getSharedPreferences("secure_mcp_token_v1", Context.MODE_PRIVATE).edit().commit()) {
                "无法持久化 MCP 令牌"
            }
            check(secure.get("token") == token) { "无法确认 MCP 令牌写入" }
        },
        changed ?: {
            (context.applicationContext as? com.mina.legadostudio.StudioApplication)?.contextEpoch?.bump()
                ?: ContextEpochStore(context.applicationContext).bump()
            Unit
        },
    )
    data class Config(val port: Int, val tokenRequired: Boolean, val token: String)

    fun load(): Config = synchronized(configLock) {
        if (!prefs.getBoolean("v1SecurityInitialized", false)) {
            check(prefs.edit().putBoolean("tokenRequired", prefs.getBoolean("tokenRequired", true))
                .putBoolean("v1SecurityInitialized", true).commit()) {
                "无法持久化 MCP 鉴权配置"
            }
        }
        if (!prefs.getBoolean("v2DefaultPortMigrated", false)) {
            val storedPort = prefs.getInt("port", LEGACY_DEFAULT_PORT)
            check(prefs.edit()
                .putInt("port", migrateLegacyDefaultPort(storedPort))
                .putBoolean("v2DefaultPortMigrated", true)
                .commit()) { "无法持久化 MCP 端口配置" }
        }
        val token = readToken()?.takeIf { it.isNotBlank() }
            ?: prefs.getString("token", null)?.takeIf { it.isNotBlank() }?.also {
                writeToken(it)
                check(prefs.edit().remove("token").commit()) { "无法迁移 MCP 令牌" }
            }
            ?: generateToken().also { writeToken(it) }
        Config(
            port = prefs.getInt("port", DEFAULT_PORT).takeIf { it in 1024..65530 } ?: DEFAULT_PORT,
            tokenRequired = prefs.getBoolean("tokenRequired", true),
            token = token,
        )
    }

    fun save(config: Config) = synchronized(configLock) {
        require(config.port in 1024..65530) { "端口必须在 1024..65530" }
        require(!config.tokenRequired || config.token.isNotBlank()) { "启用访问令牌校验时，访问令牌不能为空" }
        val oldToken = readToken()
        replaceToken(oldToken, config.token)
        check(prefs.edit().putInt("port", config.port).putBoolean("tokenRequired", config.tokenRequired)
            .putBoolean("v1SecurityInitialized", true).putBoolean("v2DefaultPortMigrated", true).commit()) {
            "无法持久化 MCP 配置"
        }
        check(prefs.getInt("port", DEFAULT_PORT) == config.port &&
            prefs.getBoolean("tokenRequired", true) == config.tokenRequired) { "无法确认 MCP 配置写入" }
    }

    // Invalidation precedes ordinary prefs: a failed commit does not undo a secure write.
    // Even a throwing secure writer may have changed memory/disk. Invalidate conservatively.
    private fun replaceToken(oldToken: String?, token: String) {
        try {
            if (oldToken != token || tokenWriteUnconfirmed) {
                if (oldToken != null && oldToken != token) tokenInvalidationPending = true
                tokenWriteUnconfirmed = true
                try {
                    writeToken(token)
                    check(readToken() == token) { "无法确认 MCP 令牌写入" }
                    tokenWriteUnconfirmed = false
                } finally {
                    flushTokenInvalidation()
                }
            } else {
                flushTokenInvalidation()
            }
        } catch (_: Exception) {
            // Never propagate storage errors containing the submitted secret.
            throw IllegalStateException("MCP 令牌保存或失效确认失败，结果未知")
        }
    }

    private fun flushTokenInvalidation() {
        if (tokenInvalidationPending) {
            triggerTokenChanged()
            tokenInvalidationPending = false
        }
    }

    /** Merge under the same process-wide lock as load/save: no stale port-only snapshot can re-enable auth. */
    fun updateConnection(port: Int?, tokenRequired: Boolean?, mcpToken: String? = null): Config = synchronized(configLock) {
        require(port == null || port in 1024..65530) { "端口必须在 1024..65530" }
        val replacement = mcpToken?.takeIf { it.isNotEmpty() }
        require(replacement == null || isValidReplacementToken(replacement)) { "mcpToken 格式无效" }
        try {
            val current = load()
            if (replacement != null) replaceToken(current.token, replacement)
            if (port != null || tokenRequired != null) {
                save(current.copy(port = port ?: current.port, tokenRequired = tokenRequired ?: current.tokenRequired,
                    token = replacement ?: current.token))
            }
            load().also { saved ->
                check(replacement == null || saved.token == replacement) { "无法确认 MCP 令牌写入" }
            }
        } catch (_: Exception) {
            throw IllegalStateException("MCP 连接配置保存失败，结果可能部分生效")
        }
    }

    /** 客户端接入信息的连接模式偏好（精简 tools/list）。只影响生成给客户端复制的地址，
     *  与安全配置、令牌和服务运行态无关，改动不触发 epoch/服务重启。 */
    fun loadCompactMode(): Boolean = synchronized(configLock) { prefs.getBoolean("compactMode", false) }

    fun saveCompactMode(compact: Boolean) = synchronized(configLock) {
        check(prefs.edit().putBoolean("compactMode", compact).commit()) { "无法持久化 MCP 连接模式" }
    }

    private fun triggerTokenChanged() {
        onTokenChanged()
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        // All instances (WebView, service and request providers) share the same storage.
        private val configLock = Any()
        private var tokenInvalidationPending = false
        private var tokenWriteUnconfirmed = false
        fun isValidReplacementToken(token: String): Boolean =
            token.length in 1..4096 && token.matches(Regex("[A-Za-z0-9._~+/-]+=*"))

        const val DEFAULT_PORT = 58823
        internal const val LEGACY_DEFAULT_PORT = 1237

        internal fun migrateLegacyDefaultPort(storedPort: Int): Int =
            if (storedPort == LEGACY_DEFAULT_PORT) DEFAULT_PORT else storedPort
    }
}
