package com.mina.legadostudio.reader

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 阅读 Web 服务地址的规范化与校验（纯逻辑，JVM 可测）。
 *
 * 只接受 `http(s)://主机[:端口]` 形式的 origin：
 * - 文件里的路径、查询串、`#` 片段、内嵌账号密码一律拒绝，避免把任意 URL 带进后续请求；
 * - 端口必须显式落在 1..65535；不写端口时按 http/https 的默认端口处理；
 * - 末尾 `/` 归一化掉，输出始终是可直接拼固定路线的 origin。
 *
 * 回环别名只在**输入整体就是 origin** 时改写，且只改写与 `127.0.0.1` **严格等价**的写法：
 * - `127.0.0.1`、`127.0.01`、`127.00.0.01`（每段十进制与八进制读法相同，可证明等价）；
 * - 2 段 `127.1`、3 段 `127.0.1`（inet_aton 语义下 24 位值恰为 1）；
 * - `localhost` 归一为 `127.0.0.1`（阅读服务监听回环，等价）；
 * - `[::1]` 已是规范 IPv6 回环写法，不再改写。
 *
 * 明确**不改写**、也不猜测：
 * - 完整四段的其它回环地址（`127.0.0.2`、`127.0.255.1` 等）原样保留——它们是不同的回环主机，
 *   把它们的令牌改发到 `.1` 是绝对错误；
 * - 非等价、含义不确定的 2/3 段缩写（`127.2`、`127.0.0`）直接拒绝，既不重写也不交给平台解析；
 * - 不实现 inet_aton 的十六进制/整数写法，也不解析任何非 127/8 的主机，
 *   避免依赖桌面 JVM 与 Android 不一致的 InetAddress 解析、或把私网地址偷偷改写。
 */
object ReaderWebOrigin {

    const val INVALID_MESSAGE =
        "阅读 Web 服务地址无效：只填 http://主机:端口 或 https://主机:端口，不要带路径、参数、# 或账号密码"

    /** 只匹配「整个输入就是一个 origin」的形态；带路径/参数的不改写，交给严格校验拒绝。 */
    private val ORIGIN_SHAPE = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://([^/?#]+)/?$")

    fun normalize(raw: String): String {
        val text = raw.trim()
        require(text.isNotEmpty()) { "阅读 Web 服务地址不能为空" }
        rejectAmbiguousLoopbackAbbreviation(text)
        val url = rewriteLoopbackAlias(text).toHttpUrlOrNull() ?: throw IllegalArgumentException(INVALID_MESSAGE)
        require(url.scheme == "http" || url.scheme == "https") { INVALID_MESSAGE }
        require(url.username.isEmpty() && url.password.isEmpty()) { INVALID_MESSAGE }
        require(url.encodedPath == "/") { INVALID_MESSAGE }
        require(url.query == null && url.fragment == null) { INVALID_MESSAGE }
        require(url.port in 1..65535) { INVALID_MESSAGE }
        return url.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
    }

    /** 从已保存（可能已损坏）的配置安全取值；无效地址返回 null，不抛异常。 */
    fun normalizeOrNull(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { normalize(it) }.getOrNull() }

    /** 已规范化 origin 的主机名（IPv6 去掉方括号）；无效地址抛异常。 */
    fun hostOf(origin: String): String = normalize(origin).toHttpUrlOrNull()!!.host

    /**
     * 一键填入的回环地址：协议与**用户输入的端口**原样保留，只把主机换成 `127.0.0.1`。
     * 仅供用户主动点选，绝不自动改写用户填写的地址；无效输入返回 null。
     */
    fun loopbackFor(raw: String): String? {
        val url = normalizeOrNull(raw)?.toHttpUrlOrNull() ?: return null
        val defaultPort = when (url.scheme) {
            "https" -> 443
            else -> 80
        }
        return buildString {
            append(url.scheme).append("://127.0.0.1")
            if (url.port != defaultPort) append(':').append(url.port)
        }
    }

    /**
     * 把用户输入里的回环别名改写为 `127.0.0.1`，端口与协议原样保留；
     * 输入不是可识别的 origin 或不是与 `127.0.0.1` 等价的别名时原样返回，后续严格校验照旧生效。
     */
    private fun rewriteLoopbackAlias(text: String): String {
        val match = ORIGIN_SHAPE.matchEntire(text) ?: return text
        val scheme = match.groupValues[1]
        val authority = match.groupValues[2]
        if (authority.contains('@')) return text
        val (host, portSuffix) = splitHostPort(authority) ?: return text
        if (!isLoopbackAlias(host)) return text
        return "$scheme://127.0.0.1$portSuffix"
    }

    /** @return 主机与原始端口后缀（含冒号；未写端口时为空串） */
    private fun splitHostPort(authority: String): Pair<String, String>? {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val host = authority.substring(1, close)
            val rest = authority.substring(close + 1)
            if (rest.isNotEmpty() && !rest.startsWith(":")) return null
            if (rest.drop(1).any { !it.isDigit() }) return null
            return host to rest
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to ""
        val host = authority.substring(0, colon)
        if (host.contains(':')) return null
        return host to authority.substring(colon)
    }

    /**
     * 拒绝含义不确定的 127 回环写法：
     * - 2/3 段缩写里不是 `127.0.0.1` 等价的（`127.2`、`127.0.0`、`127.0.2`）——inet_aton 下是别的主机，
     *   而 OkHttp 会把它当普通域名放行；
     * - 4 段但带前导零、且数值不是 `127.0.0.1`（`127.0.01.1`、`127.000.000.002`）——八/十进制读法有歧义。
     *
     * 纯四段的其它回环主机（`127.0.0.2`、`127.0.255.1`）不在此列：它们是用户明确写出的地址，原样保留。
     * 既不改写成 `.1`（会把令牌发错主机），也不依赖平台解析（桌面 JVM 与 Android 语义不同）。
     */
    private fun rejectAmbiguousLoopbackAbbreviation(text: String) {
        val match = ORIGIN_SHAPE.matchEntire(text) ?: return
        val authority = match.groupValues[2]
        if (authority.contains('@')) return
        val (host, _) = splitHostPort(authority) ?: return
        val parts = host.lowercase().split('.')
        if (parts.size !in 2..4) return
        if (parts.first() != "127") return
        if (parts.any { part -> part.isEmpty() || part.any { c -> !c.isDigit() } }) return
        if (isLoopbackAlias(host)) return
        val ambiguous = parts.size != 4 || parts.any { it.length > 1 && it.startsWith('0') }
        if (!ambiguous) return
        throw IllegalArgumentException(
            "回环地址写法有歧义：$host 不是 127.0.0.1。请写完整四段规范写法（如 127.0.0.1、127.0.0.2），" +
                "或改用阅读显示的局域网地址；本 App 不会猜测它指向哪台主机",
        )
    }

    /**
     * 只把**与 `127.0.0.1` 严格等价**的回环写法改写为 `127.0.0.1`：
     * - `localhost`；
     * - 2 段 `127.1`、3 段 `127.0.1`（inet_aton 语义下 24 位值为 1）；
     * - 4 段且数值恰为 `127.0.0.1`，允许前导零（每段十进制与八进制读法相同，可证明等价）。
     *
     * 完整四段的其它回环地址（`127.0.0.2`、`127.0.255.1`）返回 false：它们是不同的回环主机；
     * 含义不确定的缩写（`127.2`、`127.0.0`）同样返回 false，由 [normalize] 按严格 URL 校验拒绝。
     */
    private fun isLoopbackAlias(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "localhost") return true
        if (lower == "::1") return false
        val parts = lower.split('.')
        if (parts.size !in 2..4) return false
        if (parts.first() != "127") return false
        if (parts.any { part -> part.isEmpty() || part.any { !it.isDigit() } }) return false
        val values = parts.map { it.toLongOrNull() ?: return false }
        if (values.any { it > 255 }) return false
        return when (parts.size) {
            2 -> values[1] == 1L
            3 -> values[1] == 0L && values[2] == 1L
            else -> values == listOf(127L, 0L, 0L, 1L)
        }
    }
}
