package com.mina.legadostudio.verification

import android.content.ContextWrapper
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.data.db.VerificationSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Real create() with an in-memory DAO boundary; no Android notifications or Cookie operations. */
class VerificationCoordinatorCreationTest {
    @Test fun blankUrlIsRejectedBeforeAnyDaoAccess() = runBlocking {
        for (url in listOf("", " ", "\t\r\n")) {
            val fixture = Fixture()
            val error = runCatching { fixture.coordinator.create("mcp", url, "MCP 请求验证") }.exceptionOrNull()
            assertTrue("expected URL validation, got $error; DAO calls=${fixture.calls}", error is IllegalArgumentException)
            assertEquals(emptyList<String>(), fixture.calls)
            assertTrue(fixture.saved.isEmpty())
        }
    }

    @Test fun malformedOrDisallowedUrlIsRejectedBeforeAnyDaoAccess() = runBlocking {
        for (url in listOf(
            "not a URL", "example.org", "/verify", "//example.org/v", "https://", "https:example.org",
            "https:///example.org", "https://example.org:99999/v", "https://example.org/%xx",
            " https://example.org/", "https://example.org/\n", "https://example.org/a b",
            "https://example.org/\\path", "file:///sdcard/a", "javascript:alert(1)", "data:text/html,hello",
            "https://user:secret@example.org/", "https://@example.org/",
            "http://localhost/", "http://127.0.0.1/", "http://10.0.0.1/", "http://192.168.1.1/",
            "http://172.16.0.1/", "http://169.254.1.1/", "http://100.64.0.1/", "http://127.1/",
            "http://2130706433/", "http://0x7f000001/", "http://[::1]/", "http://[fd00::1]/",
            "https://appassets.androidplatform.net/assets/web/index.html",
        )) {
            val fixture = Fixture()
            val error = runCatching { fixture.coordinator.create("mcp", url, "MCP 请求验证") }.exceptionOrNull()
            assertTrue("must reject $url before storage; got $error", error is IllegalArgumentException)
            assertEquals("URL: $url", emptyList<String>(), fixture.calls)
            assertTrue(fixture.saved.isEmpty())
        }
    }

    @Test fun acceptedUrlPersistsExactInputWithNonemptyDomain() = runBlocking {
        for (url in listOf("https://example.org/verify?q=a%20b#step", "http://sub.example.org:8080/v", "https://例子.中国/验证")) {
            val fixture = Fixture()
            val error = runCatching { fixture.coordinator.create("mcp", url, "MCP 请求验证") }.exceptionOrNull()
            assertTrue("expected write boundary, got $error", error is StopAfterSave)
            assertEquals(url, fixture.saved.single().url)
            assertTrue(fixture.saved.single().domain.isNotBlank())
            assertEquals("WAITING", fixture.saved.single().status)
        }
    }

    private class Fixture {
        val calls = mutableListOf<String>()
        val saved = mutableListOf<VerificationSessionEntity>()
        private val dao = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StudioDao::class.java)) { _, method, args ->
            calls += method.name
            when (method.name) {
                "waitingVerification" -> null
                "saveVerificationSession" -> {
                    saved += args!![0] as VerificationSessionEntity
                    // Stop AFTER recording the real attempted write, before Android notification code.
                    throw StopAfterSave()
                }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as StudioDao
        val coordinator = VerificationCoordinator(
            allocate(ContextWrapper::class.java), dao,
            allocate(RuntimeCookieStore::class.java), allocate(VerificationWebViewStateStore::class.java),
        )
    }

    private class StopAfterSave : RuntimeException("test stopped after persistence")

    companion object {
        private fun <T> allocate(type: Class<T>): T {
            val klass = Class.forName("sun.misc.Unsafe")
            val unsafe = klass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return type.cast(klass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
        }
    }
}
