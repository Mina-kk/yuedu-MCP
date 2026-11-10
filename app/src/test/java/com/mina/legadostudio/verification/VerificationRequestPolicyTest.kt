package com.mina.legadostudio.verification

import com.mina.legadostudio.web.WebBridgePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationRequestPolicyTest {
    @Test fun acceptedUrlIsPreservedAndAlsoAcceptedByOpenPolicy() {
        for (url in listOf("https://example.org/verify?q=a%20b#step", "http://sub.example.org:8080/v", "https://例子.中国/验证")) {
            assertEquals(url, VerificationRequestPolicy.requireUrl(url))
            assertTrue(WebBridgePolicy.isVerificationUrlAllowed(url))
            assertTrue(DomainKey.fromUrl(url).isNotBlank())
        }
    }

    @Test fun missingAndMalformedUrlsProduceActionableErrorsWithoutEchoingSecrets() {
        for (url in listOf(null, "", "\n", "https:example.org", "https://user:secret@example.org/?token=secret")) {
            val error = runCatching { VerificationRequestPolicy.requireUrl(url) }.exceptionOrNull()
            assertTrue("got $error", error is IllegalArgumentException)
            assertTrue(error!!.message.orEmpty().contains("url"))
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    @Test fun noWhitespaceOrBackslashRepairOfSourceInput() {
        for (url in listOf(" https://example.org/", "https://example.org/\t", "https://example.org\\x")) {
            val error = runCatching { VerificationRequestPolicy.requireUrl(url) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }
}
