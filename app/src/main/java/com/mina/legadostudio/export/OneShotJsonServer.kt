package com.mina.legadostudio.export

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 只绑 127.0.0.1 的一次性 JSON 端点。
 *
 * 阅读的确认页会对同一个 `src` 拉取多次（预览一次、用户确认后再读一次），因此同一路径在 TTL 内
 * 最多成功 GET [MAX_GETS] 次；次数用尽或 TTL 到期后关闭。HEAD 只回元数据、不消耗次数，
 * 错误路径与非 GET/HEAD 方法也不消耗。JSON 只留在内存，不落盘、不监听非回环地址。
 */
class OneShotJsonServer private constructor(json: String, ttlMs: Long) : Closeable {
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
    }
    private val route = "/import/" + UUID.randomUUID().toString().replace("-", "") + ".json"
    val url: String = "http://127.0.0.1:${serverSocket.localPort}$route"
    val port: Int get() = serverSocket.localPort
    private val expiresAtMs = System.currentTimeMillis() + ttlMs
    private val closed = AtomicBoolean(false)
    private val getLock = Any()
    private val servedGets = AtomicInteger(0)
    @Volatile private var payload: ByteArray? = json.toByteArray(StandardCharsets.UTF_8)

    init {
        Thread(this::serve, "reader-import-loopback").apply { isDaemon = true }.start()
    }

    fun isClosed(): Boolean = closed.get()

    /** 本端点已成功交付的 GET 次数（HEAD 与错误路径不计）。 */
    fun servedGets(): Int = servedGets.get()

    private fun serve() {
        try {
            while (!closed.get()) {
                val remaining = expiresAtMs - System.currentTimeMillis()
                if (remaining <= 0) break
                serverSocket.soTimeout = remaining.coerceAtMost(1_000L).toInt()
                try {
                    serverSocket.accept().use { client -> handle(client) }
                    if (servedGets.get() >= MAX_GETS) break
                } catch (_: SocketTimeoutException) {
                } catch (_: SocketException) {
                    if (!closed.get()) break
                } catch (_: java.io.IOException) {
                }
            }
        } catch (_: SocketException) {
        } finally {
            close()
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = CLIENT_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
        val requestLine = reader.readLine()
        var headerChars = requestLine?.length ?: 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headerChars += line.length
            if (headerChars > MAX_HEADER_CHARS) {
                writeResponse(client, "431 Request Header Fields Too Large", null, false)
                return
            }
        }
        if (requestLine == null) return
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            writeResponse(client, "400 Bad Request", null, false)
            return
        }
        val method = parts[0]
        var target = parts[1]
        val query = target.indexOf('?')
        if (query >= 0) target = target.substring(0, query)
        if (target != route) {
            writeResponse(client, "404 Not Found", null, false)
            return
        }
        // HEAD 只回元数据：不消耗 GET 配额，也不影响后续 GET。
        if (method == "HEAD") {
            writeResponse(client, "200 OK", payload, false)
            return
        }
        if (method != "GET") {
            writeResponse(client, "405 Method Not Allowed", null, false)
            return
        }
        val body = payload
        if (body == null || !consumeGet()) {
            writeResponse(client, "410 Gone", null, false)
            return
        }
        writeResponse(client, "200 OK", body, true)
    }

    private fun consumeGet(): Boolean = synchronized(getLock) {
        if (payload == null || servedGets.get() >= MAX_GETS) return@synchronized false
        servedGets.incrementAndGet()
        true
    }

    private fun writeResponse(client: Socket, status: String, body: ByteArray?, includeBody: Boolean) {
        val length = body?.size ?: 0
        val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII))
        writer.write("HTTP/1.1 $status\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: $length\r\n")
        writer.write("Cache-Control: no-store\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        if (includeBody && length > 0 && body != null) {
            client.getOutputStream().write(body)
            client.getOutputStream().flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(getLock) { payload = null }
        runCatching { serverSocket.close() }
    }

    companion object {
        /** 同一路径在 TTL 内允许的成功 GET 次数：覆盖阅读确认页的重复拉取，仍保持一次性语义。 */
        const val MAX_GETS = 3
        private const val MAX_HEADER_CHARS = 16 * 1024
        private const val CLIENT_TIMEOUT_MS = 10_000
        fun start(json: String, ttlMs: Long): OneShotJsonServer {
            require(json.isNotBlank()) { "missing json" }
            require(ttlMs > 0) { "ttl must be positive" }
            return OneShotJsonServer(json, ttlMs)
        }
    }
}
