package com.mina.legadostudio.export

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 回环导入端点的行为契约：只绑 127.0.0.1、随机 128bit 路径、TTL 到期关闭、
 * HEAD 不消耗配额、同一路径在 TTL 内最多成功 GET [OneShotJsonServer.MAX_GETS] 次
 * （兼容阅读确认页多次拉取），错误路径不受影响，端点之间相互隔离。
 */
class OneShotJsonServerTest {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): Pair<Int, String> =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.code to response.body.string()
        }

    private fun head(url: String): Int =
        client.newCall(Request.Builder().url(url).head().build()).execute().use { it.code }

    private fun awaitClosed(server: OneShotJsonServer, timeoutMs: Long = 2_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (server.isClosed()) return true
            Thread.sleep(20)
        }
        return server.isClosed()
    }

    @Test
    fun bindsLoopbackWithRandomPathAndReturnsJson() {
        val json = "[{\"bookSourceName\":\"测试🎃\",\"bookSourceUrl\":\"https://example.com/#简体\"}]"
        val server = OneShotJsonServer.start(json, 5_000)
        try {
            assertTrue(server.url.startsWith("http://127.0.0.1:"))
            assertFalse(server.url.contains(json))
            val path = server.url.substringAfter("http://127.0.0.1:${server.port}")
            assertTrue("path must carry a random 128bit token: $path", Regex("^/import/[0-9a-f]{32}\\.json$").matches(path))

            val (code, body) = get(server.url)
            assertEquals(200, code)
            assertEquals(json, body)
            assertFalse(server.isClosed())
        } finally {
            server.close()
        }
    }

    @Test
    fun allowsRepeatedGetsUpToLimitThenStops() {
        val json = "[]"
        val server = OneShotJsonServer.start(json, 10_000)
        try {
            repeat(OneShotJsonServer.MAX_GETS) { index ->
                val (code, body) = get(server.url)
                assertEquals("GET #${index + 1}", 200, code)
                assertEquals(json, body)
            }
            assertEquals(OneShotJsonServer.MAX_GETS, server.servedGets())
            // 次数用尽后监听线程收尾关闭，后续请求被拒绝。
            assertTrue(awaitClosed(server))
        } finally {
            server.close()
        }
    }

    @Test
    fun headDoesNotConsumeGetBudget() {
        val server = OneShotJsonServer.start("[]", 10_000)
        try {
            repeat(OneShotJsonServer.MAX_GETS + 2) { assertEquals(200, head(server.url)) }
            assertEquals(0, server.servedGets())

            val (code, body) = get(server.url)
            assertEquals(200, code)
            assertEquals("[]", body)
            assertEquals(1, server.servedGets())
        } finally {
            server.close()
        }
    }

    @Test
    fun wrongPathAndWrongMethodDoNotConsumeBudget() {
        val server = OneShotJsonServer.start("[]", 10_000)
        try {
            val wrong = "http://127.0.0.1:${server.port}/wrong.json"
            assertEquals(404, get(wrong).first)
            assertEquals(404, get("http://127.0.0.1:${server.port}/import/ffff.json").first)
            assertEquals(0, server.servedGets())
            assertFalse(server.isClosed())

            val (code, body) = get(server.url)
            assertEquals(200, code)
            assertEquals("[]", body)
        } finally {
            server.close()
        }
    }

    @Test
    fun queryStringAndMethodErrorsBehaveAsBefore() {
        val server = OneShotJsonServer.start("[]", 10_000)
        try {
            assertEquals(200, get("${server.url}?from=reader").first)
            assertEquals(
                405,
                client.newCall(
                    Request.Builder().url(server.url).post("".toRequestBody()).build(),
                ).execute().use { it.code },
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun expiresAfterTtlWithoutRequests() {
        val server = OneShotJsonServer.start("[]", 300)
        assertFalse(server.isClosed())
        assertTrue("server must close at TTL", awaitClosed(server, 3_000))
        val failed = runCatching { get(server.url) }.exceptionOrNull()
        assertTrue("expired endpoint must reject connections, got $failed", failed != null)
    }

    @Test
    fun closeRevokesEndpoint() {
        val server = OneShotJsonServer.start("[]", 30_000)
        server.close()
        assertTrue(server.isClosed())
        val failed = try {
            get(server.url)
            null
        } catch (error: Exception) {
            error
        }
        assertTrue("closed loopback endpoint must reject connections", failed != null)
    }

    @Test
    fun endpointsAreIsolated() {
        val first = OneShotJsonServer.start("[\"a\"]", 10_000)
        val second = OneShotJsonServer.start("[\"b\"]", 10_000)
        try {
            assertNotEquals(first.url, second.url)
            first.close()

            val (code, body) = get(second.url)
            assertEquals(200, code)
            assertEquals("[\"b\"]", body)
            assertFalse(second.isClosed())
            val firstFailed: IOException? = try {
                get(first.url).let { null }
            } catch (error: IOException) {
                error
            }
            assertTrue("closed endpoint must not affect the surviving one", firstFailed != null)
        } finally {
            second.close()
        }
    }

    @Test
    fun concurrentGetsNeverExceedBudget() {
        val server = OneShotJsonServer.start("[]", 10_000)
        try {
            val codes = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val threads = (1..8).map {
                Thread {
                    runCatching { codes.add(get(server.url).first) }
                }.apply { start() }
            }
            threads.forEach { it.join(5_000) }
            assertEquals(
                "only MAX_GETS requests may succeed",
                OneShotJsonServer.MAX_GETS,
                codes.count { it == 200 },
            )
            assertTrue(server.servedGets() <= OneShotJsonServer.MAX_GETS)
        } finally {
            server.close()
        }
    }
}
