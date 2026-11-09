package com.mina.legadostudio.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/** Loopback-only forward proxy. HTTPS stays end-to-end TLS; no certificate interception. */
internal class PublicWebProxy(private val connector: PinnedPublicConnection = PinnedPublicConnection()) : Closeable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val slots = Semaphore(16)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false
    val port: Int get() = server.localPort
    init {
        thread(name = "public-web-accept", isDaemon = true) {
            while (!closed) {
                val client = try { server.accept() } catch (_: IOException) { break }
                if (!slots.tryAcquire()) { client.close(); continue }
                sockets.add(client)
                thread(name = "public-web-request", isDaemon = true) {
                    try { client.use { handle(it) } }
                    catch (_: Exception) { /* Never log URLs, headers, or credentials. */ }
                    finally { sockets.remove(client); slots.release() }
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        var established = false
        try {
            val input = client.getInputStream()
            val header = readHeader(input)
            val lines = header.split("\r\n")
            val start = lines.first().split(' ')
            require(start.size == 3 && start[2] in setOf("HTTP/1.0", "HTTP/1.1"))
            val method = start[0]
            require(method.matches(Regex("[A-Z]+")))
            val isConnect = method == "CONNECT"
            val url = (if (isConnect) "https://${start[1]}/" else start[1]).toHttpUrlOrNull()
                ?: throw IOException("Invalid target")
            require(url.username.isEmpty() && url.password.isEmpty())
            if (isConnect) require(start[1] == url.host.let { if (':' in it) "[$it]:${url.port}" else "$it:${url.port}" })
            else require(url.scheme == "http")
            val headers = lines.drop(1).filter { it.isNotEmpty() }.map { line ->
                require(line.isNotEmpty() && !line.first().isWhitespace() && ':' in line)
                val key = line.substringBefore(':'); val value = line.substringAfter(':').trim()
                require(key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.none { it == '\r' || it == '\n' || it == '\u0000' })
                key to value
            }
            val lengths = headers.filter { it.first.equals("Content-Length", true) }.map { it.second.toLongOrNull() ?: -1 }
            require(lengths.size <= 1 && lengths.all { it in 0..(8L * 1024 * 1024) })
            // Reject ambiguous framing instead of interpreting a smuggled second request.
            require(headers.none { it.first.equals("Transfer-Encoding", true) })
            if (isConnect) require(lengths.isEmpty() || lengths.single() == 0L)
            val upstream = connector.connect(url.host, url.port)
            sockets.add(upstream)
            try { upstream.use { remote ->
                remote.soTimeout = 60_000
                val out = client.getOutputStream()
                val upstreamOut = remote.getOutputStream()
                if (isConnect) {
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); out.flush()
                    established = true
                    val relay = thread(name = "public-web-tunnel", isDaemon = true) {
                        try { input.copyTo(upstreamOut); upstreamOut.flush(); remote.shutdownOutput() }
                        catch (_: Exception) { runCatching { remote.close() } }
                    }
                    try { remote.getInputStream().copyTo(out); out.flush() }
                    finally { runCatching { client.close() }; runCatching { remote.close() }; relay.join(1000) }
                } else {
                    val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
                    val host = if (':' in url.host) "[${url.host}]" else url.host
                    val hostHeader = host + if (url.port != 80) ":${url.port}" else ""
                    val prohibited = setOf("host", "connection", "proxy-connection", "proxy-authorization", "proxy-authenticate", "keep-alive", "upgrade", "expect") +
                        headers.filter { it.first.equals("Connection", true) }.flatMap { it.second.lowercase().split(',').map(String::trim) }
                    val rebuilt = buildString {
                        append("$method $path HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\n")
                        headers.filter { it.first.lowercase() !in prohibited }.forEach { (key, value) -> append("$key: $value\r\n") }
                        append("\r\n")
                    }
                    upstreamOut.write(rebuilt.toByteArray(Charsets.ISO_8859_1))
                    var remaining = lengths.firstOrNull() ?: 0L
                    val buffer = ByteArray(16384)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw IOException("Incomplete body")
                        upstreamOut.write(buffer, 0, count); remaining -= count
                    }
                    upstreamOut.flush()
                    established = true
                    // One HTTP request per socket: redirects create a fresh checked connection.
                    remote.getInputStream().copyTo(out); out.flush()
                }
            } } finally { sockets.remove(upstream) }
        } catch (_: Exception) {
            if (!established) runCatching {
                client.getOutputStream().write("HTTP/1.1 403 Blocked\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
        }
    }

    private fun readHeader(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var marker = 0
        while (bytes.size() < 32768) {
            val value = input.read(); if (value < 0) throw IOException("Incomplete header")
            bytes.write(value)
            marker = (marker shl 8) or value
            if (marker == 0x0d0a0d0a) return bytes.toString("ISO-8859-1").dropLast(4)
        }
        throw IOException("Header too large")
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }
}
