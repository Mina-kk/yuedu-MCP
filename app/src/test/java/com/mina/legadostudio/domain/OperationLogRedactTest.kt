package com.mina.legadostudio.domain

import com.mina.legadostudio.diagnostic.LogRedactor
import com.mina.legadostudio.mcp.StudioLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationLogRedactTest {
    @Test
    fun redactsTokenAuthorizationAndApiKey() {
        val raw = "authorization=Bearer secret token: abc123 api-key=sk-live header"
        val safe = LogRedactor.redact(raw)
        assertFalse(safe.contains("Bearer secret"))
        assertFalse(safe.contains("abc123"))
        assertFalse(safe.contains("sk-live"))
        assertTrue(safe.contains("authorization=***"))
        assertTrue(safe.contains("token=***"))
        assertTrue(safe.contains("api-key=***"))
    }

    @Test
    fun studioLogAddUsesRedactor() {
        StudioLog.add("connect token: super-secret-value")
        val last = StudioLog.get(5).last()
        assertFalse(last.contains("super-secret-value"))
        assertTrue(last.contains("token=***"))
    }

    @Test
    fun redactsPasswordSecretSpacedAuthorizationAndUrl() {
        val raw = "password=hunter2 secret=s3cr3t Authorization Bearer tok_live_abc https://evil.example/login"
        val safe = LogRedactor.redact(raw)
        assertFalse(safe.contains("hunter2"))
        assertFalse(safe.contains("s3cr3t"))
        assertFalse(safe.contains("tok_live_abc"))
        assertFalse(safe.contains("evil.example"))
        assertTrue(safe.contains("password=***"))
        assertTrue(safe.contains("secret=***"))
        assertTrue(safe.contains("authorization=***"))
        assertTrue(safe.contains("[URL已隐藏]"))
        assertFalse(LogRedactor.redact("authorization failed").contains("authorization=***"))
        assertFalse(LogRedactor.redact("Authorization: Basic dXNlcjpwYXNz").contains("dXNlcjpwYXNz"))
    }
}
