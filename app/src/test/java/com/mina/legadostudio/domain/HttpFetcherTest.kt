package com.mina.legadostudio.domain

import com.mina.legadostudio.network.HttpFetcher
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpFetcherTest {
    private fun png(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="
    )

    @Test fun captchaImageBinaryIsValidatedAndNeverLogged() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png())))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png())))
            server.start()
            val drafts = mutableListOf<com.mina.legadostudio.network.HttpLogRecorder.Draft>()
            val fetcher = HttpFetcher(unsafeSyncRecorder = { drafts += it })
            val url = server.url("/captcha.png").toString()
            val normal = fetcher.fetch(HttpFetcher.FetchRequest(url))
            assertTrue(normal.rawBytes == null)
            assertTrue(normal.bodyNote.contains("二进制"))
            val image = fetcher.fetch(HttpFetcher.FetchRequest(url, allowImageBinary = true))
            assertEquals("", image.body)
            assertEquals("image/png", image.headers["Content-Type"])
            assertTrue(image.rawBytes!!.contentEquals(png()))
            assertTrue(drafts.last().responseBody.contains("验证码图片已验证"))
            assertTrue(!drafts.last().responseBody.contains("base64"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun captchaRejectsEmptyHtmlAndSpoofedImage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(""))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("<html>Cloudflare challenge</html>"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(Buffer().write(png())))
            server.start()
            val fetcher = HttpFetcher()
            val url = server.url("/captcha.png").toString()
            for (reason in listOf("CAPTCHA_EMPTY", "CAPTCHA_NOT_IMAGE", "CAPTCHA_NOT_IMAGE")) {
                val error = runCatching { fetcher.fetch(HttpFetcher.FetchRequest(url, allowImageBinary = true)) }.exceptionOrNull()
                assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains(reason))
            }
        }
    }

    @Test fun captchaRejectsHttpErrorAndOversizedBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("challenge"))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(ByteArray(HttpFetcher.MAX_IMAGE_BYTES.toInt() + 1))))
            server.start()
            val url = server.url("/captcha.png").toString()
            val fetcher = HttpFetcher()
            val forbidden = runCatching { fetcher.fetch(HttpFetcher.FetchRequest(url, allowImageBinary = true)) }.exceptionOrNull()
            assertTrue(forbidden?.message.orEmpty().contains("CAPTCHA_HTTP_403"))
            val tooLarge = runCatching { fetcher.fetch(HttpFetcher.FetchRequest(url, allowImageBinary = true)) }.exceptionOrNull()
            assertTrue(tooLarge?.message.orEmpty().contains("CAPTCHA_TOO_LARGE"))
        }
    }

    @Test fun captchaRedirectGuardBlocksPrivateTargetWithoutSecondRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1/secret"))
            server.start()
            val fetcher = HttpFetcher(unsafeRedirectGuard = { target ->
                if (LogFilterUtils.isLoopbackOrPrivate(target)) "私网地址" else null
            })
            val error = runCatching { fetcher.fetch(HttpFetcher.FetchRequest(
                server.url("/captcha.png").toString(), allowImageBinary = true)) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("重定向被拦截"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun stripsCallerAcceptEncodingSoOkHttpReturnsDecodedHtml() {
        MockWebServer().use { server ->
            val raw = "<!DOCTYPE html><meta property='og:novel:book_name' content='测试书'>"
            val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw.toByteArray()) } }.toByteArray()
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(bytes)))
            server.start()
            val result = HttpFetcher().fetch(HttpFetcher.FetchRequest(server.url("/book").toString(), headers = mapOf("Accept-Encoding" to "gzip, deflate")))
            assertEquals(raw, result.body)
            val request = server.takeRequest()
            assertTrue(request.getHeader("Accept-Encoding").orEmpty() != "gzip, deflate")
        }
    }

    @Test fun recordsRedirectChainAndFinalBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
            server.enqueue(MockResponse().setBody("done"))
            server.start()
            val start = server.url("/start").toString()
            val result = HttpFetcher().fetch(HttpFetcher.FetchRequest(start))
            assertEquals("done", result.body)
            assertEquals(server.url("/final").toString(), result.finalUrl)
            assertTrue(result.redirectChain.first().contains("/start"))
            assertTrue(result.redirectChain.last().contains("/final"))
        }
    }

    @Test fun flagsHighConfidenceJsChallengePage() {
        // 阿里书屋实测 200 挑战页：内容正在载入 + var c2= + /nnxswnn/*.js 三特征同时命中
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """<div id="x">内容正在载入……</div><script>var c2="abc123";</script><script src="/nnxswnn/def.js"></script>"""
            ))
            server.start()
            val fetcher = HttpFetcher()
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/search").toString()))
            assertEquals(200, result.code)
            assertTrue("bodyNote should flag challenge: ${result.bodyNote}", result.bodyNote.contains("JS_CHALLENGE"))
            assertTrue(fetcher.jsChallengeMarker(200, result.body) != null)
            val error = runCatching { fetcher.requireNoJsChallenge(result) }.exceptionOrNull()
            assertTrue(error is com.mina.legadostudio.network.JsChallengeException)
            assertTrue(error!!.message.orEmpty().contains("人机挑战") || error.message.orEmpty().contains("JS_CHALLENGE"))
        }
    }

    @Test fun doesNotFlagNormalContentPage() {
        // 普通业务页：只有「加载中」文案没有挑战变量+脚本路径，不能误报
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html><body><h1>书名</h1><p>正文内容，加载中请稍候的页面不是挑战</p></body></html>"))
            server.start()
            val fetcher = HttpFetcher()
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/book/1").toString()))
            assertEquals("", result.bodyNote)
            assertTrue(fetcher.jsChallengeMarker(200, result.body) == null)
            assertTrue(fetcher.requireNoJsChallenge(result) === result)
        }
    }

    @Test fun doesNotFlagBusinessErrorPageAsChallenge() {
        // 爱书网 200「文件不存在」业务错误页：不是人机验证，不能误报挑战
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html><body><div class='error'>文件不存在或已删除</div></body></html>"))
            server.start()
            val fetcher = HttpFetcher()
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/down.php").toString()))
            assertEquals("", result.bodyNote)
            assertTrue(fetcher.jsChallengeMarker(200, result.body) == null)
            assertTrue(fetcher.requireNoJsChallenge(result) === result)
        }
    }

    @Test fun doesNotFlagPartialChallengeSignals() {
        // 只命中一两个特征（有 c2 变量但没有挑战脚本路径和载入文案）不算挑战页
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<script>var c2='x';var n=1;</script><div>正常内容</div>"))
            server.start()
            val fetcher = HttpFetcher()
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/p").toString()))
            assertTrue(fetcher.jsChallengeMarker(200, result.body) == null)
        }
    }

    @Test fun perHopOnlyFiresOnRedirect() {
        // 200 响应不触发 perHop；3xx 带 Location 时逐跳收到（fetch_page/debug_source 不传该参数则完全不变）。
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/two"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("final"))
            server.start()
            val hops = mutableListOf<HttpFetcher.RedirectHop>()
            val fetcher = HttpFetcher(unsafePerHopRecorder = { hops += it })
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/one").toString()))
            assertEquals(200, result.code)
            assertEquals(1, hops.size)
            assertTrue(hops[0].requestUrl.endsWith("/one"))
            assertTrue(hops[0].nextUrl.orEmpty().endsWith("/two"))
            assertEquals(302, hops[0].statusCode)
        }
    }

    @Test fun perHopAbsentLeavesFetchUnchanged() {
        // 不传 unsafePerHopRecorder（fetch_page/debug_source 走的生产 HttpFetcher）：重定向照走、无额外行为。
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/done"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            server.start()
            val result = HttpFetcher().fetch(HttpFetcher.FetchRequest(server.url("/a").toString()))
            assertEquals(200, result.code)
            assertEquals("ok", result.body)
            assertTrue(result.finalUrl.endsWith("/done"))
        }
    }
}
