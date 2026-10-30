package com.mina.legadostudio.domain

import com.mina.legadostudio.verification.DomainKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogFilterUtils { 
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 将时间戳格式化为本地日期 YYYY-MM-DD */
    fun formatDateKey(timestampMs: Long): String {
        if (timestampMs <= 0) return "未知日期"
        return synchronized(dayFormat) {
            dayFormat.format(Date(timestampMs))
        }
    }

    /**
     * 判断一个 host 或 url 是否属于本地回环、局域网私网或内部端点。
     * 书源网络日志必须严格排除此类地址，避免 127.0.0.1、localhost 等内部流量污染书源站点胶囊条。
     */
    fun isLoopbackOrPrivate(urlOrHost: String): Boolean {
        val trimmed = urlOrHost.trim()
        if (trimmed.isEmpty()) return true
        val host = extractRawHost(trimmed)
        if (host.isEmpty()) return true
        if (host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "0.0.0.0") return true

        // IPv6：回环/链路本地/唯一本地 fc00::/7/站点本地（fec0::/10，历史段）
        if (host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb") ||
            host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fec") || host.startsWith("fed") ||
            host.startsWith("fee") || host.startsWith("fef")
        ) return true

        // IPv4-mapped IPv6（如 ::ffff:127.0.0.1 / ::ffff:10.0.0.1）
        if (host.startsWith("::ffff:")) {
            val mapped = host.removePrefix("::ffff:")
            if (isLoopbackOrPrivate(mapped)) return true
        }

        // 非标准 IPv4 字面量（SSRF 常见绕行）：十进制整数、十六进制、八进制、分段简写（0x7f.1 / 127.1）
        numericIpLong(host)?.let { value -> return isPrivateIpv4Value(value) }

        // 匹配 IPv4
        val ipParts = host.split('.')
        if (ipParts.size == 4 && ipParts.all { it.toIntOrNull() in 0..255 }) {
            val first = ipParts[0].toInt()
            val second = ipParts[1].toInt()
            // 127.0.0.0/8 回环
            if (first == 127) return true
            // 10.0.0.0/8 私网
            if (first == 10) return true
            // 192.168.0.0/16 私网
            if (first == 192 && second == 168) return true
            // 172.16.0.0/12 私网 (172.16.x.x - 172.31.x.x)
            if (first == 172 && second in 16..31) return true
            // 169.254.0.0/16 链路本地
            if (first == 169 && second == 254) return true
            // 100.64.0.0/10 运营商级 NAT（不可公网路由）
            if (first == 100 && second in 64..127) return true
            // 0.0.0.0/8
            if (first == 0) return true
            // 240.0.0.0/4 保留段
            if (first >= 240) return true
        }
        return false
    }

    /** 仅按 32 位 IPv4 数值判定私网/保留段（供非标准字面量复用同一口径）。 */
    private fun isPrivateIpv4Value(value: Long): Boolean {
        val first = (value ushr 24) and 0xFF
        val second = (value ushr 16) and 0xFF
        if (first == 127L || first == 10L || first == 0L || first >= 240) return true
        if (first == 192L && second == 168L) return true
        if (first == 172L && second in 16..31) return true
        if (first == 169L && second == 254L) return true
        if (first == 100L && second in 64..127) return true
        return false
    }

    /**
     * 解析非标准 IPv4 字面量为 32 位数值：纯十进制（2130706433）、十六进制（0x7f000001）、
     * 分段简写（127.1 = 127.0.0.1、0x7f.1、0177.0.0.1）。无法解析返回 null。
     */
    private fun numericIpLong(host: String): Long? {
        val parts = host.split('.')
        if (parts.size > 4) return null
        if (!parts.all { it.matches(Regex("0[xX][0-9a-fA-F]+|0[0-7]+|[0-9]+")) }) return null
        // 单一数字且非 0 开头也不是 0x 开头的普通 IPv4 段会走到这里——必须整体当 32 位数值
        val values = parts.map { part ->
            when {
                part.startsWith("0x", true) -> part.substring(2).toLongOrNull(16)
                part.length > 1 && part.startsWith("0") -> part.toLongOrNull(8)
                else -> part.toLongOrNull(10)
            } ?: return null
        }
        if (values.size < 4) {
            // InetAddress 语义：前 N-1 段各占 1 字节，最后一段占 (5-N) 字节
            val head = values.dropLast(1)
            if (head.any { it > 255 }) return null
            val tailMax = (1L shl ((5 - values.size) * 8)) - 1
            if (values.last() > tailMax) return null
            var value = values.last()
            head.forEachIndexed { i, b -> value = value or (b shl (24 - i * 8)) }
            return value
        }
        if (values.any { it > 255 }) return null
        var value = 0L
        values.forEach { value = (value shl 8) or it }
        return value
    }

    /**
     * 从 URL 或 host 中安全提取不含端口号的纯小写 host。
     */
    fun extractRawHost(urlOrHost: String): String {
        val trimmed = urlOrHost.trim()
        if (trimmed.isEmpty()) return ""
        val withoutScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
            else -> trimmed
        }
        val hostPart = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        // 剥离 userinfo（http://user@host/ 或 http://user:pass@host/），防「域名@私网IP」绕过
        val hostOnly = hostPart.substringAfterLast('@')
        val hostWithoutPort = when {
            hostOnly.startsWith("[") -> hostOnly.substringBefore(']').trimStart('[')
            hostOnly.count { it == ':' } >= 2 -> hostOnly
            else -> hostOnly.substringBefore(':')
        }
        return hostWithoutPort.trim('.').lowercase(Locale.getDefault())
    }

    /**
     * 提取主根域名（Primary Domain / eTLD+1），如 www.69shu.cx -> 69shu.cx。
     * 若为本地回环或私网 IP 则返回 null，坚决不作为公网书源站点使用。
     */
    fun extractPrimaryDomain(urlOrHost: String): String? {
        if (isLoopbackOrPrivate(urlOrHost)) return null
        val rawHost = extractRawHost(urlOrHost)
        if (rawHost.isEmpty()) return null
        val domain = DomainKey.fromHost(rawHost)
        return domain.ifBlank { rawHost }
    }

    /**
     * 判断某个 HTTP 请求的 URL 是否匹配目标书源域名。
     * 例如 targetDomain 为 "69shu.cx"，则 "https://www.69shu.cx/book"、"https://69shu.cx/api"
     * 以及主域名相同的所有子请求均判定为属于该书源。
     */
    fun matchesDomain(url: String, targetDomain: String): Boolean {
        val target = targetDomain.trim().lowercase(Locale.getDefault())
        if (target.isEmpty()) return false
        val reqHost = extractRawHost(url)
        if (reqHost.isEmpty()) return false
        if (reqHost == target) return true
        if (reqHost.endsWith("." + target)) return true
        val reqDomain = extractPrimaryDomain(url)
        return reqDomain != null && reqDomain == target
    }
}
