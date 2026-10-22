package com.mina.legadostudio.reader

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 单次阅读 Web HTTP 请求的**脱敏诊断**。
 *
 * 只回答「哪条固定路由、卡在哪个阶段、什么类别的失败、试了几次、有没有复用连接」。
 * 刻意**不含** host、port、查询串、访问令牌、书源正文或原始异常 URL：这些字段一旦回包，
 * 就会把用户私网地址或凭据带进界面/模型，而定位问题并不需要它们。
 *
 * - [method]：`GET` / `POST`（由有没有请求体决定，不接受外部指定）
 * - [path]：固定 allowlist 路由（如 `/getBookSource`），**不含查询串**
 * - [stage]：业务阶段，见 [ReaderWebClient.STAGE_*]（`token_probe` / `lookup` / `readback` / `save` 等）
 * - [phase]：失败发生在 [Phase.CONNECT] / [Phase.HEADERS] / [Phase.BODY] / [Phase.PARSE] 哪一步
 * - [httpStatus]：已收到的 HTTP 状态码；连响应头都没拿到时为 null
 * - [exceptionCategory]：见 [Category]；响应形状不符与业务失败为 null（由异常 code 区分）
 * - [attempts]：本次逻辑请求实际发出的次数（含 401 Bearer 挑战后的认证重试）；
 *   GET/HEAD 的传输重试最多让它是 2，POST 恒为 1
 * - [reusedConnection]：本次尝试是否**没有**新建 TCP 连接（生产恒为 false：零 idle 连接池 + `Connection: close`）；
 *   null = 该 client 没有连接观测器，无法测量（不伪造成 false）
 * - [authRetry]：该诊断是否来自「同 origin 401 Bearer 挑战后的一次认证请求」
 */
data class ReaderWebRequestDiagnostic(
    val method: String,
    val path: String,
    val stage: String,
    val phase: String,
    val httpStatus: Int?,
    val exceptionCategory: String?,
    val attempts: Int,
    val reusedConnection: Boolean? = null,
    val authRetry: Boolean = false,
) {
    /** `reader_web_status` / 工具回包使用的键；测试据此锁定不泄漏地址与令牌。 */
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "method" to method,
        "path" to path,
        "stage" to stage,
        "phase" to phase,
        "httpStatus" to httpStatus,
        "exceptionCategory" to exceptionCategory,
        "attempts" to attempts,
        "reusedConnection" to reusedConnection,
        "authRetry" to authRetry,
    )

    /** 失败阶段：连接建立 / 等响应头 / 读正文 / 解析内容；`none` = 本次交换成功、未发生失败。 */
    object Phase {
        const val NONE = "none"
        const val CONNECT = "connect"
        const val HEADERS = "headers"
        const val BODY = "body"
        const val PARSE = "parse"
    }

    /** 异常类别：只按可判定的 JDK/OkHttp 异常分类，不猜具体站点。 */
    object Category {
        const val DNS = "DNS"
        const val CONNECT = "CONNECT"
        const val RESET = "RESET"
        const val EOF = "EOF"
        const val PROTOCOL = "PROTOCOL"
        const val TLS = "TLS"
        const val TIMEOUT = "TIMEOUT"
        const val UNKNOWN = "UNKNOWN"
    }

    companion object {
        /**
         * 可在**新建连接**上重试的类别：连接被重置、响应提前 EOF、HTTP 协议数据不完整。
         * 超时、DNS、TLS、连接被拒不在其中——重试只会白耗预算，且不能证明是陈旧连接。
         */
        val RECOVERABLE_CATEGORIES: Set<String> = setOf(Category.RESET, Category.EOF, Category.PROTOCOL)

        /**
         * 把底层异常归到一个稳定类别（不含异常原文）。
         *
         * OkHttp 常把底层错误包一层普通 `IOException`（如 `unexpected end of stream` 的 cause 是
         * `SocketException: Connection reset`），因此沿 cause 链找第一个能判定的类别，
         * 都不命中时再看消息特征，最后才落 [Category.UNKNOWN]。
         */
        fun categoryOf(error: Throwable): String {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 8) {
                classify(current)?.let { return it }
                current = current.cause?.takeIf { it !== current }
                depth++
            }
            return if (error.message.orEmpty().contains("unexpected end of stream", ignoreCase = true)) {
                Category.EOF
            } else {
                Category.UNKNOWN
            }
        }

        private fun classify(error: Throwable): String? = when {
            error is SSLException -> Category.TLS
            error is UnknownHostException -> Category.DNS
            // ConnectException 是 SocketException 的子类：必须先判连接被拒/不可达
            error is ConnectException || error is NoRouteToHostException -> Category.CONNECT
            error is SocketTimeoutException || error is InterruptedIOException -> Category.TIMEOUT
            error is EOFException -> Category.EOF
            error is ProtocolException -> Category.PROTOCOL
            error is SocketException -> Category.RESET
            else -> null
        }
    }
}
