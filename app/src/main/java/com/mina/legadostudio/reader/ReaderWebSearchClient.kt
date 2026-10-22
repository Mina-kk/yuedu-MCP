package com.mina.legadostudio.reader

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 阅读端全局搜索：固定 /searchBook WS，结果只来自服务端 JSON 数组帧。 */
class ReaderWebSearchClient(
    private val configProvider: () -> ReaderWebConfigStore.Config?,
    private val clientFactory: () -> OkHttpClient = { defaultClient() },
    private val endpointResolver: (String) -> ReaderWebEndpoint.Target = ReaderWebEndpoint::derive,
) {
    companion object {
        private const val MAX_FRAME_BYTES = 128 * 1024
        private const val MAX_TOTAL_BYTES = 512 * 1024
        private const val MAX_FRAMES = 256
        private const val MAX_KEYWORD_CHARS = 512
        private val FIELDS = listOf("name", "author", "bookUrl", "origin", "originName", "latestChapterTitle")

        private fun defaultClient() = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    class SearchException(val code: String, val executed: Boolean, message: String) : Exception(message) {
        fun toPayload(): Map<String, Any?> = linkedMapOf(
            "status" to "error", "mode" to "reader_web_search", "errorCode" to code,
            "executed" to executed, "results" to emptyList<Any>(), "message" to message,
        )
    }

    data class Outcome(
        val status: String,
        val executed: Boolean,
        val results: List<Map<String, String>>,
        val frameCount: Int,
        val truncated: Boolean,
        val closeCode: Int?,
    ) {
        fun toPayload(): Map<String, Any?> = linkedMapOf(
            "status" to status, "mode" to "reader_web_search", "executed" to executed,
            "scope" to "阅读 App 当前已启用书源及其搜索范围（不是单个目标书源）",
            "results" to results, "resultCount" to results.size,
            "byOrigin" to results.mapNotNull { it["origin"]?.trim()?.takeIf(String::isNotEmpty) }
                .groupingBy { it }.eachCount(),
            "frameCount" to frameCount, "truncated" to truncated, "closeCode" to closeCode,
            "evidenceNote" to "仅收录阅读 /searchBook 返回的 JSON 数组帧；byOrigin 按 origin 完整字符串精确计数，#probeUA 等后缀不同于正式源，不可借同域/同名当成正式源命中；全局搜索不等于单源检验通过", 
        )
    }

    private sealed class End {
        data class Closed(val code: Int, val reason: String) : End()
        data class Failed(val code: String, val executed: Boolean, val message: String) : End()
        data object Limit : End()
    }

    private val httpClient by lazy { clientFactory() }

    suspend fun searchAt(
        config: ReaderWebConfigStore.Config?,
        keyword: String,
        totalTimeoutMs: Long = 120_000,
        maxResults: Int = 30,
    ): Outcome = withContext(Dispatchers.IO) {
        val key = keyword.trim()
        if (key.isEmpty() || key.length > MAX_KEYWORD_CHARS || key.any { Character.isISOControl(it) }) {
            throw SearchException("INVALID_ARGUMENT", false, "关键词不能为空、过长或含控制字符")
        }
        val snapshot = config ?: configProvider()
            ?: throw SearchException("NOT_CONFIGURED", false, "尚未配置阅读 Web 服务地址")
        val target = try {
            endpointResolver(snapshot.baseUrl).copy(path = ReaderWebEndpoint.SEARCH_PATH)
        } catch (error: ReaderWebEndpoint.InvalidEndpointException) {
            throw SearchException(error.code, false, error.message.orEmpty())
        }
        val token = snapshot.token.trim()
        val offered = ReaderWebEndpoint.subprotocols(token).toSet()
        val limit = maxResults.coerceIn(1, 100)
        val budget = totalTimeoutMs.coerceIn(200, 300_000)
        val start = System.nanoTime()
        val lastFrame = AtomicLong(start)
        val opened = AtomicBoolean(false)
        val sent = AtomicBoolean(false)
        val end = CompletableDeferred<End>()
        val results = LinkedHashMap<String, Map<String, String>>()
        var frames = 0
        var totalBytes = 0
        var truncated = false
        var sawArray = false
        val secrets = (listOf(
            snapshot.baseUrl.trimEnd('/'), target.url.toString(), target.display,
            "${target.host}:${target.port}", "${target.base.host}:${target.base.port}",
        ) + if (token.isNotEmpty()) listOf(token, ReaderWebEndpoint.encodeToken(token),
            ReaderWebEndpoint.headerValue(token)) else emptyList())
            .filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
        fun sanitize(value: String): String {
            var safe = value.take(512)
            secrets.forEach { safe = safe.replace(it, "[已隐藏]") }
            return safe
        }
        fun snapshotResults(): List<Map<String, String>> = synchronized(results) { results.values.toList() }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                lastFrame.set(System.nanoTime())
                val selected = response.header("Sec-WebSocket-Protocol")?.trim().orEmpty()
                if (selected.isNotEmpty() && selected.split(',').map { it.trim() }.any { it !in offered }) {
                    end.complete(End.Failed("PROTOCOL_ERROR", false, "服务端选定了未提供的子协议"))
                    webSocket.close(1002, "")
                    return
                }
                val request = JsonObject().apply { addProperty("key", key) }.toString()
                if (webSocket.send(request)) sent.set(true)
                else end.complete(End.Failed("CONNECTION_LOST", false, "搜索请求未能发送"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (end.isCompleted) return
                lastFrame.set(System.nanoTime())
                val size = text.toByteArray(StandardCharsets.UTF_8).size
                synchronized(results) {
                    if (end.isCompleted) return
                    frames++
                    if (frames > MAX_FRAMES || size > MAX_FRAME_BYTES || totalBytes + size > MAX_TOTAL_BYTES) {
                        truncated = true
                        end.complete(End.Limit)
                        return
                    }
                    totalBytes += size
                    val array = try { JsonParser.parseString(text) } catch (_: Exception) { null }
                    if (array == null || !array.isJsonArray || array.asJsonArray.any { !it.isJsonObject }) {
                        end.complete(End.Failed("INCOMPATIBLE_FRAME", sent.get(), "搜索服务返回非书籍 JSON 数组帧；已拒绝解析为书籍"))
                        return
                    }
                    sawArray = true
                    for (item in array.asJsonArray) {
                        val obj = item.asJsonObject
                        val book = linkedMapOf<String, String>()
                        for (field in FIELDS) {
                            val value = obj.get(field)
                            if (value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) {
                                book[field] = sanitize(value.asString)
                            }
                        }
                        if (book.isEmpty()) continue
                        val identity = book["origin"].orEmpty() + "\u0000" +
                            (book["bookUrl"] ?: (book["name"].orEmpty() + "\u0000" + book["author"].orEmpty()))
                        if (identity !in results && results.size >= limit) {
                            truncated = true
                            end.complete(End.Limit)
                            return
                        }
                        results[identity] = book
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (!end.isCompleted) end.complete(End.Failed("INCOMPATIBLE_FRAME", sent.get(), "搜索服务返回非文本帧"))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                end.complete(End.Closed(code, reason))
                webSocket.close(code, "")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                end.complete(End.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                val code = when {
                    status != null && status in 300..399 -> "REDIRECT_BLOCKED"
                    status in setOf(401, 403) -> if (token.isEmpty()) "TOKEN_REQUIRED" else "TOKEN_INVALID"
                    status in setOf(404, 405, 410, 426, 501) -> "ENDPOINT_MISSING"
                    status in setOf(429, 503) -> "SERVICE_BUSY"
                    status != null -> "HTTP_ERROR"
                    opened.get() -> "CONNECTION_LOST"
                    t is java.net.SocketTimeoutException -> "TIMEOUT"
                    else -> "UNREACHABLE"
                }
                end.complete(End.Failed(code, sent.get(), "阅读搜索通道失败${status?.let { "（HTTP $it）" }.orEmpty()}；请确认服务、令牌与该分支的 /searchBook 支持情况"))
            }
        }
        val socket = try {
            httpClient.newWebSocket(Request.Builder().url(target.url)
                .header("Sec-WebSocket-Protocol", ReaderWebEndpoint.headerValue(token)).build(), listener)
        } catch (_: Exception) {
            throw SearchException("UNREACHABLE", false, "无法建立阅读搜索连接")
        }
        try {
            val settled = coroutineScope {
                val watchdog = launch {
                    while (isActive && !end.isCompleted) {
                        val now = System.nanoTime()
                        if (!opened.get() && TimeUnit.NANOSECONDS.toMillis(now - start) >= minOf(5_000, budget)) {
                            end.complete(End.Failed("HANDSHAKE_TIMEOUT", false, "阅读搜索通道握手超时"))
                        } else if (opened.get() && TimeUnit.NANOSECONDS.toMillis(now - lastFrame.get()) >= budget) {
                            end.complete(End.Failed("TIMEOUT", sent.get(), "本工具等待阅读搜索结果超过 ${budget}ms（这是 MCP 端等待上限，不是阅读端超时）；书源可能在等待/限流，请加大 timeoutSec 重试"))
                        }
                        delay(100)
                    }
                }
                try { withTimeout(budget) { end.await() } } finally { watchdog.cancel() }
            }
            when (settled) {
                is End.Failed -> throw SearchException(settled.code, settled.executed, settled.message)
                End.Limit -> Outcome("partial", sent.get(), snapshotResults(), frames, true, null)
                is End.Closed -> {
                    val code = settled.code
                    when (code) {
                        1008, 4401, 4403 -> throw SearchException(if (token.isEmpty()) "TOKEN_REQUIRED" else "TOKEN_INVALID", sent.get(), "阅读搜索通道拒绝了访问令牌")
                        1012, 1013 -> throw SearchException("SERVICE_BUSY", sent.get(), "阅读搜索服务暂不可用")
                        1000 -> Unit
                        else -> throw SearchException("PROTOCOL_ERROR", sent.get(), "阅读搜索通道异常关闭（close $code）")
                    }
                    val status = when {
                        settled.reason != "Search finish" -> "incomplete"
                        !sawArray -> "no_data"
                        results.isEmpty() -> "empty"
                        else -> "completed"
                    }
                    Outcome(status, sent.get(), snapshotResults(), frames, truncated, code)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw SearchException("TIMEOUT", sent.get(), "阅读搜索通道超过总等待时间；不能声称搜索完成")
        } finally {
            runCatching { socket.close(1000, "") }
            runCatching { socket.cancel() }
        }
    }
}
