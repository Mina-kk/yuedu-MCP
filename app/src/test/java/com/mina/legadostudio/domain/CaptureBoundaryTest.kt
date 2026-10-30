package com.mina.legadostudio.domain

import com.mina.legadostudio.network.HttpFetcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CaptureOnce 依赖的边界测试（不依赖 Android/Room，可 JVM 单测）：
 * - isLoopbackOrPrivate 对 SSRF 常见绕行字面量全部拒绝；
 * - HttpFetcher 在 3xx 时把 Location resolve 成绝对目标再交 guard（//host、相对路径、公网→私网都覆盖）；
 * - 非 3xx 的 Location 不触发 guard（不错杀）；
 * - DNS guard 拦截解析到私网/回环的建连；
 * - extractPrimaryDomain 对公网域名解析为注册域。
 */
class CaptureBoundaryTest {

    @Test
    fun numericAndObfuscatedIpLiteralsAreBlocked() {
        // 十进制整数、十六进制、八进制、分段简写、IPv4-mapped IPv6、IPv6 私网
        listOf(
            "2130706433",            // 127.0.0.1
            "0x7f000001",            // 127.0.0.1
            "0177.0.0.1",            // 127.0.0.1
            "127.1",                 // 127.0.0.1 简写
            "0x7f.1",                // 127.0.0.1 简写
            "::ffff:127.0.0.1",      // IPv4-mapped IPv6
            "::ffff:10.0.0.1",
            "fe80::1",               // 链路本地
            "fc00::1",               // 唯一本地
            "fec0::1",               // 站点本地（历史段）
            "100.64.0.1",            // CGNAT
            "240.0.0.1",             // 保留段
            "http://user@127.0.0.1/",       // userinfo 剥离后仍是私网
            "http://evil.com@127.0.0.1/",   // 域名@私网 IP 绕过
            "http://attacker.com@10.0.0.5/",
        ).forEach { host ->
            assertTrue("isLoopbackOrPrivate($host)", LogFilterUtils.isLoopbackOrPrivate(host))
        }
    }

    @Test
    fun publicHostsStillAllowed() {
        assertFalse(LogFilterUtils.isLoopbackOrPrivate("https://www.69shu.cx/book/1.html"))
        assertFalse(LogFilterUtils.isLoopbackOrPrivate("http://api.biquge.com/search"))
        assertFalse(LogFilterUtils.isLoopbackOrPrivate("69shu.cx"))
        assertFalse(LogFilterUtils.isLoopbackOrPrivate("203.0.113.5"))
        assertFalse(LogFilterUtils.isLoopbackOrPrivate("8.8.8.8"))
        assertFalse(LogFilterUtils.isLoopbackOrPrivate("2606:4700:3031::ac43:d3a3"))
        assertFalse(LogFilterUtils.isLoopbackOrPrivate("https://[2606:4700:3031::ac43:d3a3]/"))
        assertNull(absoluteGuard("https://[2606:4700:3031::ac43:d3a3]/"))
    }

    // ---- redirectGuard 现在收到的是 HttpFetcher resolve 后的绝对 URL ----

    /** 与 CaptureOnce.redirectGuard 同逻辑（绝对目标校验）。 */
    private fun absoluteGuard(target: String): String? {
        if (target.isBlank()) return null
        val parsed = target.toHttpUrlOrNull() ?: return "重定向目标不是有效的 HTTP(S) 地址"
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return "重定向目标内嵌凭据"
        if (LogFilterUtils.isLoopbackOrPrivate(target)) return "重定向目标为回环/私网/保留地址"
        return null
    }

    @Test
    fun redirectGuardOnAbsoluteTargets() {
        assertNull(absoluteGuard("https://example.com/book/1"))
        assertNull(absoluteGuard("http://203.0.113.5/x"))
        assertNotNull(absoluteGuard("http://169.254.169.254/"))
        assertNotNull(absoluteGuard("http://127.0.0.1:8080/"))
        assertNotNull(absoluteGuard("http://192.168.0.1/admin"))
        assertNotNull(absoluteGuard("http://user:pass@example.com/"))
        // toHttpUrlOrNull 拒绝非 http(s)
        assertNotNull(absoluteGuard("file:///etc/passwd"))
    }

    @Test
    fun fetcherResolvesSchemeRelativeLocation() {
        // Location: //127.0.0.1/ 是 scheme-relative，旧实现对原始字符串判不过；现在 resolve 成 http://127.0.0.1/ 再拦
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "//127.0.0.1/"))
            server.start()
            val fetcher = HttpFetcher(unsafeRedirectGuard = ::absoluteGuard)
            val err = runCatching { fetcher.fetch(HttpFetcher.FetchRequest(server.url("/").toString())) }.exceptionOrNull()
            assertNotNull(err)
            assertTrue(err!!.message.orEmpty().contains("重定向"))
        }
    }

    @Test
    fun fetcherResolvesRelativeLocationToPrivate() {
        // Location: /next 相对当前 host；若当前 host 是公网，resolve 后仍公网放行。
        // 这里验证 resolve 确实发生（服务器返回 302 到自身路径会被 resolve 成绝对 URL 交给 guard）。
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            server.start()
            val seen = mutableListOf<String>()
            val fetcher = HttpFetcher(unsafeRedirectGuard = { seen += it; null })
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/").toString()))
            assertEquals(200, result.code)
            // guard 收到的是绝对 URL 而不是 "/final"
            assertTrue(seen.isNotEmpty() && seen.all { it.startsWith("http") })
        }
    }

    @Test
    fun fetcherDoesNotGuardNonRedirectLocation() {
        // 200 响应带 Location 头不应触发 guard（不错杀）
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", "http://127.0.0.1/x").setBody("ok"))
            server.start()
            val fetcher = HttpFetcher(unsafeRedirectGuard = { "不应被调用" })
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/").toString()))
            assertEquals(200, result.code)
        }
    }

    @Test
    fun redirectGuardStopsPrivateLocation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://169.254.169.254/latest/meta-data"))
            server.start()
            val fetcher = HttpFetcher(unsafeRedirectGuard = ::absoluteGuard)
            val err = runCatching { fetcher.fetch(HttpFetcher.FetchRequest(server.url("/").toString())) }.exceptionOrNull()
            assertNotNull(err)
            assertTrue(err!!.message.orEmpty().contains("重定向"))
        }
    }

    @Test
    fun redirectGuardStopsNonHttpScheme() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "file:///etc/passwd"))
            server.start()
            val fetcher = HttpFetcher(unsafeRedirectGuard = ::absoluteGuard)
            val err = runCatching { fetcher.fetch(HttpFetcher.FetchRequest(server.url("/").toString())) }.exceptionOrNull()
            assertNotNull(err)
            assertTrue(err!!.message.orEmpty().contains("重定向"))
        }
    }

    @Test
    fun dnsGuardBlocksResolvedPrivateAddress() {
        // MockWebServer host 是 127.0.0.1：unsafeDnsGuard 在解析阶段就拒绝，连接根本不会建
        MockWebServer().use { server ->
            server.start()
            val fetcher = HttpFetcher(unsafeDnsGuard = { addr ->
                if (LogFilterUtils.isLoopbackOrPrivate(addr.hostAddress.orEmpty())) "解析到私网/回环地址" else null
            })
            val err = runCatching { fetcher.fetch(HttpFetcher.FetchRequest(server.url("/").toString())) }.exceptionOrNull()
            assertNotNull(err)
            assertTrue(err!!.message.orEmpty().contains("DNS") || err.message.orEmpty().contains("私网"))
        }
    }

    @Test
    fun dnsGuardAllowsPublicResolution() {
        // 127.0.0.1 之外的地址放行（MockWebServer 域名 localhost 解析到 127.0.0.1，这里用放行 guard 验证不拦）
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            server.start()
            val fetcher = HttpFetcher(unsafeDnsGuard = { null })
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/").toString()))
            assertEquals(200, result.code)
        }
    }

    @Test
    fun anchorNormalizationUsesRegisteredDomain() {
        assertEquals("69shu.cx", LogFilterUtils.extractPrimaryDomain("https://www.69shu.cx/modules/article/search.php?key=x"))
        assertNull(LogFilterUtils.extractPrimaryDomain("http://127.0.0.1/mcp"))
        assertNull(LogFilterUtils.extractPrimaryDomain("http://10.0.0.1/"))
    }

    @Test
    fun postWithCustomHeadersReachesServer() {
        // 抓包链路（HttpFetcher）真实发出 POST：正文与自定义头到达服务器，证明表单参数确实生效。
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("posted"))
            server.start()
            val fetcher = HttpFetcher(unsafeRedirectGuard = { null }, unsafeDnsGuard = { null })
            val result = fetcher.fetch(
                HttpFetcher.FetchRequest(
                    url = server.url("/submit").toString(),
                    method = "POST",
                    headers = mapOf("X-Custom" to "yes", "Referer" to "https://ref.example/"),
                    body = "key=value&x=1",
                )
            )
            assertEquals(200, result.code)
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("key=value&x=1", req.body.readUtf8())
            assertEquals("yes", req.getHeader("X-Custom"))
            assertEquals("https://ref.example/", req.getHeader("Referer"))
        }
    }

    @Test
    fun fetchWritesOneTransactionWithRedirectChain() {
        // 诚实语义：一次 fetch 只产生一条 FetchResult（最终落点），redirectChain 记各跳 URL，而非每跳独立记录。
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/hop2"))
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/final"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("done"))
            server.start()
            val fetcher = HttpFetcher(unsafeRedirectGuard = { null }, unsafeDnsGuard = { null })
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/start").toString()))
            assertEquals(200, result.code)
            assertTrue(result.finalUrl.endsWith("/final"))
            // redirectChain = 起始 + 各中间跳 + 最终
            assertEquals(listOf("/start", "/hop2", "/final").size, result.redirectChain.size)
            assertTrue(result.redirectChain.first().endsWith("/start"))
            assertTrue(result.redirectChain.last().endsWith("/final"))
        }
    }
}
