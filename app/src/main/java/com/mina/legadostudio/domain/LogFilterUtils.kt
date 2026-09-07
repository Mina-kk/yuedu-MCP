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
        if (host == "localhost" || host == "::1" || host == "0.0.0.0") return true

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
            // 0.0.0.0/8
            if (first == 0) return true
        }
        return false
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
        val hostWithoutPort = if (hostPart.startsWith("[")) {
            hostPart.substringBefore(']').trimStart('[')
        } else {
            hostPart.substringBefore(':')
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
