package com.mina.legadostudio.web

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class PinnedPublicConnectionTest {
    private val publicIp = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
    private val privateIp = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test fun actualDialUsesTheCheckedAddressWithoutSecondDnsLookup() {
        var lookups = 0
        var dialled: InetSocketAddress? = null
        val connector = PinnedPublicConnection(
            resolve = { if (++lookups == 1) listOf(publicIp) else listOf(privateIp) },
            dial = { endpoint -> dialled = endpoint; Socket() },
        )
        connector.connect("public.example", 443).close()
        assertEquals(1, lookups)
        assertEquals(publicIp, dialled!!.address)
        assertFalse(dialled!!.isUnresolved)
        assertEquals(443, dialled!!.port)
    }

    @Test fun mixedAnswersNeverDialEvenThePublicAnswer() {
        var calls = 0
        val connector = PinnedPublicConnection(resolve = { listOf(publicIp, privateIp) }, dial = { calls++; Socket() })
        assertTrue(runCatching { connector.connect("public.example", 443) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun subsequentPrivateAnswerIsRejectedBeforeConnect() {
        var lookups = 0
        var calls = 0
        val connector = PinnedPublicConnection(
            resolve = { if (++lookups == 1) listOf(publicIp) else listOf(privateIp) },
            dial = { calls++; Socket() },
        )
        connector.connect("public.example", 443).close()
        assertTrue(runCatching { connector.connect("public.example", 443) }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun noAddressFailureAndReservedRangesAreFailClosed() {
        for (ip in listOf("0.0.0.0", "10.0.0.1", "169.254.169.254", "100.64.0.1", "224.0.0.1", "::1", "fc00::1", "fe80::1")) {
            var calls = 0
            val connector = PinnedPublicConnection(resolve = { listOf(InetAddress.getByName(ip)) }, dial = { calls++; Socket() })
            assertTrue(ip, runCatching { connector.connect("public.example", 443) }.isFailure)
            assertEquals(ip, 0, calls)
        }
        assertTrue(runCatching { PinnedPublicConnection(resolve = { emptyList() }).connect("public.example", 443) }.isFailure)
    }
}
