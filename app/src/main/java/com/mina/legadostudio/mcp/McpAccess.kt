package com.mina.legadostudio.mcp

import java.net.InetAddress
import java.net.NetworkInterface

object McpAccess {
    const val PATH = "/mcp"
    const val AUTH_HEADER = "Authorization"

    fun localAddresses(): List<InetAddress> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { it.inetAddresses.toList() }
        .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
        .distinctBy { it.hostAddress }

    fun allowedHosts(addresses: List<InetAddress>): List<String> = buildList {
        add("localhost"); add("127.0.0.1"); add("[::1]")
        addresses.mapNotNullTo(this) { it.hostAddress }
    }.distinct()

    fun allowedOrigins(hosts: List<String>): List<String> = hosts.map { "http://$it" }

    // 对外只暴露回环地址，避免切换 Wi-Fi/蜂窝后 IP 变化导致 MCP 客户端断连。
    fun endpoints(port: Int): List<String> = listOf("http://127.0.0.1:$port$PATH")

    fun lanEndpoints(port: Int): List<String> =
        localAddresses().mapNotNull { it.hostAddress }.map { "http://$it:$port$PATH" }

    // MCP 规范（Streamable HTTP）的鉴权方式是标准 Authorization: Bearer 头。
    fun bearerTokenValue(token: String) = "Bearer $token"

    fun tokenHeaderLine(token: String) = "$AUTH_HEADER: ${bearerTokenValue(token)}"

    /**
     * 标准 MCP 客户端配置（mcpServers JSON，Claude / Cherry Studio / Cline 等通用）。
     * 鉴权使用规范要求的 Authorization: Bearer 头，已内嵌在配置里，用户整段粘贴即可。
     */
    fun clientConfigJson(url: String, token: String, tokenRequired: Boolean): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val headerBlock = if (tokenRequired && token.isNotBlank())
            ",\n      \"headers\": {\n        \"$AUTH_HEADER\": \"Bearer ${esc(token)}\"\n      }"
        else ""
        return "{\n  \"mcpServers\": {\n    \"yuedu-MCP\": {\n      \"url\": \"${esc(url)}\"$headerBlock\n    }\n  }\n}"
    }
}
