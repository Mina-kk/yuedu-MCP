package com.mina.legadostudio.web

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class VerificationDnsPolicyTest {
    @Test fun aPublicNameResolvingToPrivateAddressIsRejected() {
        val result = WebBridgePolicy.verificationNetworkAllowed("https://public.example/test") {
            listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
        assertFalse(result)
    }
    @Test fun mixedPublicAndPrivateAnswersAreRejected() {
        assertFalse(WebBridgePolicy.verificationNetworkAllowed("https://public.example/test") {
            listOf(InetAddress.getByAddress(byteArrayOf(8,8,8,8)), InetAddress.getByAddress(byteArrayOf(10,0,0,1)))
        })
    }
    @Test fun dnsFailuresAreFailClosedAndPublicResourcesStillWork() {
        assertFalse(WebBridgePolicy.verificationNetworkAllowed("https://public.example/test") { error("dns failed") })
        assertTrue(WebBridgePolicy.verificationNetworkAllowed("https://public.example/test") {
            listOf(InetAddress.getByAddress(byteArrayOf(8,8,8,8)))
        })
    }
}
