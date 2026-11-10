package com.mina.legadostudio.verification

import android.content.ContextWrapper
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.web.WebBridgePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.reflect.Proxy

@RunWith(Parameterized::class)
class VerificationCanonicalHostTest(private val url: String, private val allowed: Boolean) {
    @Test fun creationPolicyUsesCanonicalHost() {
        val result = runCatching { VerificationRequestPolicy.requireUrl(url) }
        if (allowed) assertEquals(url, result.getOrThrow())
        else assertTrue("must reject $url", result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun canOpenAndCanCompleteUseCanonicalHost() {
        assertEquals(url, allowed, WebBridgePolicy.isVerificationUrlAllowed(url))
        assertEquals(url, allowed, WebBridgePolicy.canCompleteVerification("webview", url))
        val payload = WebBridgePolicy.verificationSessionPayload(WebBridgePolicy.VerificationRow(
            "id", "domain", url, "purpose", "WAITING", "webview", "", "", "", 1, 2, "mcp",
        ))
        assertEquals(url, allowed, payload.get("canOpen").asBoolean)
    }

    @Test fun createAndRepairRejectBeforeStorage() = runBlocking {
        if (allowed) return@runBlocking
        var calls = 0
        val dao = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StudioDao::class.java)) { _, method, _ ->
            calls++
            error("Unexpected DAO access: ${method.name}")
        } as StudioDao
        val coordinator = VerificationCoordinator(allocate(ContextWrapper::class.java), dao,
            allocate(RuntimeCookieStore::class.java), allocate(VerificationWebViewStateStore::class.java))
        assertTrue("create $url", runCatching { coordinator.create("mcp", url, "verify") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue("repair $url", runCatching { coordinator.repairAddress("old", url) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, calls)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} allowed={1}") fun cases() = listOf(
            arrayOf<Any>("http://%31%32%37.0.0.1/", false),
            arrayOf<Any>("http://%31%30.0.0.1/", false),
            arrayOf<Any>("http://[0:0:0:0:0:0:0:1]/", false),
            arrayOf<Any>("http://[::ffff:7f00:1]/", false),
            arrayOf<Any>("http://[::ffff:a00:1]/", false),
            arrayOf<Any>("http://[::]/", false),
            arrayOf<Any>("http://[fe90::1]/", false),
            arrayOf<Any>("http://[fd00::1]/", false),
            arrayOf<Any>("http://127.1/", false),
            arrayOf<Any>("http://0x7f000001/", false),
            arrayOf<Any>("http://[ff02::1]/", false),
            arrayOf<Any>("https://example.org/verify", true),
            arrayOf<Any>("https://例子.中国/验证", true),
            arrayOf<Any>("https://[2606:4700:4700::1111]/", true),
            arrayOf<Any>("https://[::ffff:808:808]/", true),
            arrayOf<Any>("https://8.8.8.8/", true),
            arrayOf<Any>("https://fc-public.example/", true),
            arrayOf<Any>("https://fd-public.example/", true),
        )
        private fun <T> allocate(type: Class<T>): T {
            val klass = Class.forName("sun.misc.Unsafe")
            val unsafe = klass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return type.cast(klass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
        }
    }
}
