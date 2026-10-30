package com.mina.legadostudio.diagnostic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExceptionDigestTest {
    @Test fun chainRedactsUrlAndToken() {
        val error = RuntimeException("GET https://secret.example/path?token=abc123 failed")
        val text = ExceptionDigest.chain(error)
        assertFalse(text.contains("secret.example"))
        assertFalse(text.contains("abc123"))
        assertTrue(text.contains("RuntimeException"))
        assertTrue(text.contains("[URL已隐藏]") || text.contains("***"))
    }

    @Test fun firstLineKeepsClassName() {
        val line = ExceptionDigest.firstLine(java.util.regex.PatternSyntaxException("bad", "{{", 0))
        assertTrue(line.contains("PatternSyntaxException"))
        assertFalse(line.contains("http://"))
    }

    @Test fun chainRedactsPasswordSecretAndSpacedAuthorization() {
        val error = RuntimeException(
            "login failed password=hunter2 secret=s3cr3t Authorization Bearer tok_live_abc https://evil.example/x",
        )
        val text = ExceptionDigest.chain(error)
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("s3cr3t"))
        assertFalse(text.contains("tok_live_abc"))
        assertFalse(text.contains("evil.example"))
        assertTrue(text.contains("RuntimeException"))
        assertTrue(text.contains("at "))
        assertTrue(text.contains("password=***"))
        assertTrue(text.contains("secret=***"))
        assertTrue(text.contains("authorization=***"))
        assertTrue(text.contains("[URL已隐藏]"))
    }

    @Test fun firstLineRedactsBasicAuthorizationAndPasswd() {
        val line = ExceptionDigest.firstLine(
            IllegalStateException("Authorization: Basic dXNlcjpwYXNz passwd=letmein"),
        )
        assertTrue(line.contains("IllegalStateException"))
        assertFalse(line.contains("dXNlcjpwYXNz"))
        assertFalse(line.contains("letmein"))
        assertTrue(line.contains("authorization=***"))
        assertTrue(line.contains("passwd=***"))
    }

    @Test fun chainKeepsErrorClassAndDoesNotRedactAuthorizationFailed() {
        val error = RuntimeException("authorization failed; retry later")
        val text = ExceptionDigest.chain(error)
        assertTrue(text.contains("RuntimeException"))
        assertTrue(text.contains("authorization failed"))
        assertFalse(text.contains("authorization=***"))
        assertTrue(text.contains("at "))
    }
}
