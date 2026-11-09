package com.mina.legadostudio.web

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PublicWebProxyTest {
    private fun request(port: Int, text: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 3000
        socket.getOutputStream().write(text.toByteArray())
        socket.getInputStream().bufferedReader().readText()
    }

    @Test fun privateHttpAndConnectNeverReachTheTargetSocket() {
        var calls = 0
        val connector = PinnedPublicConnection(resolve = { listOf(InetAddress.getByName("127.0.0.1")) }, dial = { calls++; Socket() })
        PublicWebProxy(connector).use { proxy ->
            for (head in listOf("GET http://public.example/ HTTP/1.1\r\nHost: public.example\r\n\r\n", "CONNECT public.example:443 HTTP/1.1\r\nHost: public.example:443\r\n\r\n")) {
                assertTrue(request(proxy.port, head).startsWith("HTTP/1.1 403"))
            }
            assertEquals(0, calls)
        }
    }

    @Test fun publicHttpPreservesBodyAndDoesNotForwardProxyCredentials() {
        val observed = LinkedBlockingQueue<String>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { remote ->
                    remote.soTimeout = 3000
                    val input = remote.getInputStream().bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) { val line = input.readLine() ?: break; lines += line; if (line.isEmpty()) break }
                    val body = CharArray(4); var pos = 0
                    while (pos < 4) { val n = input.read(body, pos, 4-pos); if (n < 0) break; pos += n }
                    observed.offer(lines.joinToString("\n") + body.concatToString())
                    remote.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                }
            }
            val connector = PinnedPublicConnection(resolve = { listOf(InetAddress.getByName("8.8.8.8")) }, dial = { address ->
                assertEquals("8.8.8.8", address.address.hostAddress)
                Socket("127.0.0.1", server.localPort) // transport fixture, production dial remains IP-pinned
            })
            PublicWebProxy(connector).use { proxy ->
                val response = request(proxy.port, "POST http://public.example/path?q=1 HTTP/1.1\r\nHost: public.example\r\nContent-Length: 4\r\nProxy-Authorization: test-only\r\n\r\nDATA")
                assertTrue(response.endsWith("OK"))
                val sent = observed.poll(3, TimeUnit.SECONDS)!!
                assertTrue(sent.startsWith("POST /path?q=1 HTTP/1.1"))
                assertTrue(sent.endsWith("DATA"))
                assertFalse(sent.contains("test-only"))
            }
            worker.join(3000)
        }
    }
}
