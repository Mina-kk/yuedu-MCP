package com.mina.legadostudio.web

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 未回包请求的登记表（id → 已受理）。
 *
 * 只做**一次结算**：Activity 销毁、来源失效、超时、宿主拒绝时统一 [drain]，
 * 前端据此把对应 Promise 全部 reject，而不是永远 loading。
 *
 * 上限保护：恶意/失控页面可以疯狂发请求，登记表必须有界。
 */
internal class WebBridgePending(private val limit: Int = DEFAULT_LIMIT) {
    private val ids = ConcurrentHashMap.newKeySet<String>()

    val size: Int get() = ids.size

    /** 登记一个新请求；id 非法或重复或超限时返回 null（调用方据此回 bad_request）。 */
    fun add(id: String): String? {
        if (id.isBlank() || id.length > WebBridgeProtocol.MAX_ID_CHARS) return null
        if (id.any { it.isISOControl() }) return null
        if (ids.size >= limit) return null
        return if (ids.add(id)) id else null
    }

    /** 结算；返回 false 表示该 id 已被结算过（重复回包不得二次生效）。 */
    fun remove(id: String): Boolean = ids.remove(id)

    /** 取走全部未结算 id 并清空。 */
    fun drain(): Set<String> {
        val snapshot = ids.toSet()
        ids.clear()
        return snapshot
    }

    companion object {
        const val DEFAULT_LIMIT = 64
    }
}

/**
 * 验证中心与隔离验证页的纯策略（JVM 可测）。
 *
 * 关键约束：
 * - 隔离验证页**永远**没有 JS bridge（[verificationBrowserGetsBridge] 恒 false）；
 * - 只允许把已保存验证任务里的公网 http(s) URL 交给隔离页，禁止回环/私网/自定义 scheme；
 * - 列表回包不含 Cookie、令牌或任何凭据字段；
 * - 剪贴板内容只写本机剪贴板，回包固定 `{copied:true}`。
 */
internal object WebBridgePolicy {

    /** 隔离验证页是否挂 bridge：恒 false，写死以防后续误加开关。 */
    const val verificationBrowserGetsBridge = false

    /** 验证列表单条（与 Room 实体字段一一对应，不含任何凭据）。 */
    data class VerificationRow(
        val id: String,
        val domain: String,
        val url: String,
        val purpose: String,
        val status: String,
        val kind: String,
        val imageData: String,
        val finalUrl: String,
        val answer: String,
        val createdAt: Long,
        val updatedAt: Long,
        val jobId: String,
    )

    fun verificationListPayload(rows: List<VerificationRow>): JsonObject {
        val sessions = JsonArray()
        rows.forEach { row ->
            val obj = JsonObject()
            obj.addProperty("id", row.id)
            obj.addProperty("domain", row.domain)
            obj.addProperty("url", row.url)
            obj.addProperty("purpose", row.purpose)
            obj.addProperty("status", row.status)
            obj.addProperty("kind", row.kind)
            obj.addProperty("imageData", row.imageData)
            obj.addProperty("finalUrl", row.finalUrl)
            obj.addProperty("answer", row.answer)
            obj.addProperty("createdAt", row.createdAt)
            obj.addProperty("updatedAt", row.updatedAt)
            obj.addProperty("jobId", row.jobId)
            sessions.add(obj)
        }
        return JsonObject().apply { add("sessions", sessions) }
    }

    /**
     * 隔离验证页只接受公网 http(s)：拒绝自定义 scheme、回环、私网、链路本地、
     * 内嵌凭据，以及 privileged 主页面自身的 origin（防止在无 bridge 的页面里
     * 加载宿主资源造成误导）。
     */
    fun isVerificationUrlAllowed(raw: String?): Boolean {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return false
        if (url.any { it.isWhitespace() || it.isISOControl() }) return false
        val parsed = runCatching { url.toHttpUrlOrNull() }.getOrNull() ?: return false
        if (parsed.scheme != "http" && parsed.scheme != "https") return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        val host = parsed.host.lowercase()
        if (host == "localhost" || host.endsWith(".localhost")) return false
        if (host == "appassets.androidplatform.net") return false
        if (com.mina.legadostudio.domain.LogFilterUtils.isLoopbackOrPrivate(url)) return false
        return true
    }

    private fun isPrivateOrLoopbackHost(host: String): Boolean {
        // IPv6：OkHttp 返回的主机名不带方括号。
        if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        if (host == "::ffff:127.0.0.1") return true
        if (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return when {
            a == 0 || a == 10 || a == 127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 100 && b in 64..127 -> true
            a >= 224 -> true
            else -> false
        }
    }

    /** 剪贴板回包：固定形状，绝不回传被复制的秘密。 */
    fun copiedAck(): JsonObject = JsonObject().apply { addProperty("copied", true) }

    fun clientConfigJson(url: String, token: String, tokenRequired: Boolean): String =
        com.mina.legadostudio.mcp.McpAccess.clientConfigJson(url, token, tokenRequired)

    /**
     * 主 WebView 的 URL 策略（两个 shouldOverrideUrlLoading 重载共用同一实现）。
     *
     * 严格比较 scheme/host/port：`startsWith("https://appassets.androidplatform.net")`
     * 会放行 `appassets.androidplatform.net.evil.com`，这里逐段比对。
     */
    fun isHostUrlAllowed(raw: String?): Boolean {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return false
        return runCatching { url.toHttpUrlOrNull() }.getOrNull()?.let { parsed ->
            parsed.scheme == "https" &&
                parsed.host == "appassets.androidplatform.net" &&
                parsed.port == 443 &&
                parsed.username.isEmpty() &&
                parsed.password.isEmpty()
        } ?: false
    }

    /** 允许的本地资源前缀：只有 WebViewAssetLoader 挂载的 assets/web/ 子树。 */
    const val ASSET_PREFIX = "/assets/web/"

    /**
     * 主 WebView 导航决策（两个 `shouldOverrideUrlLoading` 重载共用）：
     * - 只放行 trusted origin 下 `/assets/web/` 子树（页面自身、hash 路由、query 都算同页）；
     * - 相似域（`appassets.androidplatform.net.evil.com`）、其它端口、`@` 凭据、百分号编码穿越、
     *   自定义 scheme 一律拒绝——不交给系统浏览器，也不在 WebView 内加载。
     */
    fun hostNavigationDecision(rawUrl: String?): NavigationDecision {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return NavigationDecision.Block
        val parsed = runCatching { url.toHttpUrlOrNull() }.getOrNull()
            ?: return NavigationDecision.Block
        if (parsed.scheme != "https") return NavigationDecision.Block
        if (parsed.host != "appassets.androidplatform.net") return NavigationDecision.Block
        if (parsed.port != 443) return NavigationDecision.Block
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return NavigationDecision.Block
        val path = parsed.encodedPath
        if (!path.startsWith(ASSET_PREFIX)) return NavigationDecision.Block
        if (hasTraversal(path)) return NavigationDecision.Block
        return NavigationDecision.Allow
    }

    enum class NavigationDecision { Allow, Block }

    /**
     * 穿越判定：解码一次后再看 `..` 段。`%2e%2e`、`..%2f`、`%252e` 这类写法在
     * 解码后仍会留下可疑序列，因此「解码一次仍含 `..`」即拒绝（宁严不松）。
     */
    private fun hasTraversal(encodedPath: String): Boolean {
        if (encodedPath.contains("..")) return true
        val decoded = runCatching {
            java.net.URLDecoder.decode(encodedPath, Charsets.UTF_8.name())
        }.getOrDefault(encodedPath)
        if (decoded.contains("..")) return true
        if (decoded.contains('\\')) return true
        return false
    }

    /**
     * shouldInterceptRequest 决策：
     * - 本地 web 资源 → 交给 assetLoader；
     * - 其余一律返回**拒绝响应**（404 空体），绝不返回 null——返回 null 会让 WebView
     *   真的去联网加载外站资源，主页面就变成了任意站点容器。
     */
    fun localResourceDecision(rawUrl: String?): LocalResource {
        val url = rawUrl?.trim().orEmpty()
        val parsed = runCatching { url.toHttpUrlOrNull() }.getOrNull()
            ?: return LocalResource.Deny
        if (!isHostUrlAllowed(url)) return LocalResource.Deny
        val path = parsed.encodedPath
        if (!path.startsWith(ASSET_PREFIX)) return LocalResource.Deny
        if (hasTraversal(path)) return LocalResource.Deny
        return LocalResource.Serve
    }

    enum class LocalResource { Serve, Deny }

    /**
     * 隔离验证页的请求决策：外站页面**只允许它自己的 http(s) 子资源**，
     * 且不得指向 privileged origin（否则无 bridge 的外站页也能读到宿主 assets 内容）。
     * `file:`/`content:`/`data:` 之外的 scheme 一律拒绝。
     */
    fun verificationResourceDecision(rawUrl: String?): LocalResource {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return LocalResource.Deny
        if (url.startsWith("data:", ignoreCase = true) || url.startsWith("blob:", ignoreCase = true)) {
            return LocalResource.Serve
        }
        return if (isVerificationUrlAllowed(url)) LocalResource.Serve else LocalResource.Deny
    }

    /** Called only from WebView's background resource interceptor, never the UI thread. */
    fun verificationNetworkAllowed(
        rawUrl: String?,
        resolve: (String) -> List<java.net.InetAddress> = { java.net.InetAddress.getAllByName(it).toList() },
    ): Boolean {
        if (verificationResourceDecision(rawUrl) != LocalResource.Serve) return false
        val url = rawUrl.orEmpty()
        if (url.startsWith("data:", true) || url.startsWith("blob:", true)) return true
        val parsed = url.toHttpUrlOrNull() ?: return false
        return runCatching {
            val addresses = resolve(parsed.host)
            addresses.isNotEmpty() && addresses.none { address ->
                address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress ||
                    address.isLinkLocalAddress || address.isMulticastAddress ||
                    com.mina.legadostudio.domain.LogFilterUtils.isLoopbackOrPrivate(address.hostAddress.orEmpty())
            }
        }.getOrDefault(false)
    }

    /** 从原始 JSON 文本里取顶层字符串字段（用于回读校验，不信任前端传的结构）。 */
    fun topLevelString(json: String, key: String): String? = runCatching {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) return@runCatching null
        val element = root.asJsonObject.get(key) ?: return@runCatching null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
    }.getOrNull()
}
