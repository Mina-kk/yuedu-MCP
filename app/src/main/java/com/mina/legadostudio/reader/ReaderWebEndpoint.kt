package com.mina.legadostudio.reader

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 旧版阅读 WebSocket 调试接口的**唯一**目标推导规则（纯逻辑，JVM 可测）。
 *
 * 各分支阅读（原版 / MD3 / C / T）普遍把调试通道放在 Web 服务端口的**下一个端口**：
 * `ws://<用户 Web 服务主机>:<用户 Web 端口 + 1>/bookSourceDebug`。
 *
 * 安全边界：
 * - 主机只来自用户已保存并规范化过的 Web 服务 origin，本对象**不接受**单独的主机/端口参数，
 *   调用方也没有把模型输入拼进目标 URL 的入口；
 * - 端口严格按 `+1` 派生，65535 无法再加时直接拒绝（不绕到别的端口、不回落到默认端口）；
 * - 路径固定为 [DEBUG_PATH]，不带查询串与片段；协议随 origin（http→ws、https→wss）；
 * - 不解析也不改写 origin 的主机（回环别名的归一化由 [ReaderWebOrigin] 负责）。
 *
 * 令牌子协议（LegadoTeam 新版）：
 * - 未配置令牌：只发 `legado`（旧版/无鉴权分支）；
 * - 配置了令牌：`legado, legado.token.<UTF-8 令牌的 base64url 无填充>`。
 */
object ReaderWebEndpoint {

    /** 旧版调试 WebSocket 的固定路径。 */
    const val DEBUG_PATH = "/bookSourceDebug"

    const val SEARCH_PATH = "/searchBook"

    /** Legado 固定的 WebSocket 子协议名。 */
    const val SUBPROTOCOL = "legado"

    private const val TOKEN_SUBPROTOCOL_PREFIX = "legado.token."

    /**
     * 派生出的调试目标。`base` 保留用户 origin 的规范主机写法（含 IPv6 方括号处理），
     * 只替换端口与路径；`url` 是交给 OkHttp 的 http(s) 形式（OkHttp 在 `newWebSocket`
     * 里完成到 ws/wss 的握手）。
     */
    data class Target(
        val base: HttpUrl,
        val port: Int,
        val path: String = DEBUG_PATH,
    ) {
        val host: String get() = base.host

        val secure: Boolean get() = base.scheme == "https"

        val url: HttpUrl
            get() = base.newBuilder()
                .port(port)
                .encodedPath(path)
                .query(null)
                .fragment(null)
                .build()

        /** 仅用于脱敏与测试断言，不进入回包。 */
        val display: String
            get() = "${if (secure) "wss" else "ws"}://" +
                (if (host.contains(':')) "[$host]" else host) + ":$port$path"
    }

    /** @property code `INVALID_ORIGIN` / `PORT_OVERFLOW`，供调用方如实分类。 */
    class InvalidEndpointException(val code: String, message: String) : Exception(message)

    /**
     * 由已保存的 Web 服务 origin 派生固定调试目标。
     *
     * @throws InvalidEndpointException origin 不是纯 `http(s)://主机[:端口]`，或端口已无法 +1
     */
    fun derive(webOrigin: String): Target {
        val base = webOrigin.trim().toHttpUrlOrNull()
            ?: throw InvalidEndpointException("INVALID_ORIGIN", "已保存的阅读 Web 服务地址无效，请重新填写")
        if (base.scheme != "http" && base.scheme != "https") {
            throw InvalidEndpointException("INVALID_ORIGIN", "阅读 Web 服务只支持 http/https 地址")
        }
        if (base.username.isNotEmpty() || base.password.isNotEmpty()) {
            throw InvalidEndpointException("INVALID_ORIGIN", "阅读 Web 服务地址不能包含账号密码")
        }
        if (base.encodedPath != "/" || base.query != null || base.fragment != null) {
            throw InvalidEndpointException("INVALID_ORIGIN", "阅读 Web 服务地址必须是 origin，不能带路径、参数或片段")
        }
        val debugPort = base.port.toLong() + 1
        if (debugPort > 65_535) {
            throw InvalidEndpointException(
                "PORT_OVERFLOW",
                "阅读 Web 端口为 65535，没有可用的下一个端口；请改用阅读实际监听的调试端口对应的 Web 端口",
            )
        }
        return Target(base = base, port = debugPort.toInt())
    }

    /** 本次握手要发送的子协议列表；顺序与 Legado 一致（先 `legado`，再令牌）。 */
    fun subprotocols(token: String): List<String> {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return listOf(SUBPROTOCOL)
        return listOf(SUBPROTOCOL, TOKEN_SUBPROTOCOL_PREFIX + encodeToken(trimmed))
    }

    /** `Sec-WebSocket-Protocol` 头的值。 */
    fun headerValue(token: String): String = subprotocols(token).joinToString(", ")

    /** UTF-8 令牌的 base64url、无填充；只用于子协议，不用于其它请求头。 */
    fun encodeToken(token: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(token.toByteArray(StandardCharsets.UTF_8))
}
