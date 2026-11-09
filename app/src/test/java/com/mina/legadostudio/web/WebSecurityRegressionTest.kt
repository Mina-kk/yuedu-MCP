package com.mina.legadostudio.web

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WebSecurityRegressionTest {
    private fun source(relative: String): String = listOf(File("src/main/java/$relative"), File("app/src/main/java/$relative"))
        .first { it.isFile }.readText()

    @Test fun verificationResourcesRejectPrivateTargetsAndCredentials() {
        for (url in listOf("http://127.0.0.1:58823/mcp", "http://10.0.0.1/", "http://192.168.1.5/", "http://localhost/", "http://[::1]/", "http://[::ffff:192.168.1.5]/", "http://user:pw@example.com/")) {
            assertEquals(url, WebBridgePolicy.LocalResource.Deny, WebBridgePolicy.verificationResourceDecision(url))
        }
        assertEquals(WebBridgePolicy.LocalResource.Serve, WebBridgePolicy.verificationResourceDecision("https://cdn.example.com/challenge.js"))
    }

    @Test fun uaMustBeAValidHttpHeaderBeforeAnySettingWrite() {
        for (ua in listOf("Mozilla/5.0\nX-Test: 1", "Mozilla/5.0\r", "Mozilla/5.0\u0000", "Mozilla/5.0 中文")) {
            val params = JsonObject().apply { addProperty("port", 58824); addProperty("userAgent", ua) }
            assertTrue("UA must be rejected", WebBridgeProtocol.parseSettings(params) is WebBridgeProtocol.SettingsParse.Bad)
        }
        assertTrue(WebBridgeProtocol.parseSettings(JsonObject().apply { addProperty("userAgent", "Mozilla/5.0 Test") }) is WebBridgeProtocol.SettingsParse.Ok)
    }

    @Test fun serverSecuresLegacyConfigBeforeOpeningListener() {
        val text = source("com/mina/legadostudio/service/McpService.kt")
        val start = text.substringAfter("private fun startServer()")
        val guard = start.indexOf("store.requireAuthentication()")
        assertTrue("guard must precede socket bind", guard >= 0 && guard < start.indexOf("embeddedServer("))
    }

    @Test fun safReservesOneOperationAcrossBothPickers() {
        val text = source("com/mina/legadostudio/web/SafLauncher.kt")
        assertTrue("export and import must share atomic gate", text.contains("private val operations = SingleOperationGate()"))
        assertEquals(2, Regex("operations\\.reserve\\(").findAll(text).count())
    }

    @Test fun cookieToolOutputUsesWebOnlySecretBoundary() {
        val text = source("com/mina/legadostudio/web/StudioWebBridge.kt")
        assertTrue(text.contains("WebToolResultPolicy.forWeb(call.name,"))
    }

    @Test fun verificationUsesConnectionPinnedProxyWithNoDirectFallback() {
        val activity = source("com/mina/legadostudio/MainActivity.kt")
        val setup = activity.substringAfter("private suspend fun showVerificationBrowser")
        assertTrue(setup.indexOf("WebProxyBoundary.acquire") in 0 until setup.indexOf("VerificationBrowserView("))
        val proxy = source("com/mina/legadostudio/web/WebProxyBoundary.kt")
        assertTrue(proxy.contains("removeImplicitRules()"))
        assertFalse(proxy.contains(".addDirect("))
        assertTrue(proxy.contains("setProxyOverride"))
        val reader = source("com/mina/legadostudio/reader/ReaderWebUiSession.kt")
        assertTrue(reader.contains("WebProxyBoundary.active"))
    }
}
