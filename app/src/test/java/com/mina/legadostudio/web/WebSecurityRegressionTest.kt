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

    @Test fun serverLoadsUsersAuthenticationChoiceBeforeOpeningListener() {
        val text = source("com/mina/legadostudio/service/McpService.kt")
        val start = text.substringAfter("private fun startServer()")
        val guard = start.indexOf("store.load()")
        assertTrue("persisted configuration must precede socket bind", guard >= 0 && guard < start.indexOf("embeddedServer("))
        assertFalse(start.contains("requireAuthentication()"))
        assertTrue(start.contains("configureStudioMcp(store::load, hosts, origins)"))
    }

    @Test fun trustedBridgeAdvertisesEditableAuthenticationAndCopiesActualConfig() {
        val text = source("com/mina/legadostudio/web/StudioWebBridge.kt")
        assertTrue(text.contains("addProperty(\"tokenRequiredLocked\", false)"))
        assertTrue(text.contains("addProperty(\"tokenRequiredEditable\", true)"))
        assertTrue(text.contains("McpAccess.clientConfigJson(url, config.token, tokenRequired = config.tokenRequired)"))
        val copy = text.substringAfter("private suspend fun connectionCopy").substringBefore("// --- tools.list")
        assertTrue(copy.contains("clipboard.setPrimaryClip(clip)"))
        assertTrue(copy.contains("return WebBridgePolicy.copiedAck()"))
        val state = text.substringAfter("private suspend fun serviceState").substringBefore("private suspend fun runtimeState")
        assertFalse(state.contains("config.token)"))
        assertTrue(state.contains("includeToken = false"))
    }

    @Test fun bridgeUsesAtomicPatchAndReadbackInsideSharedSaveLock() {
        val text = source("com/mina/legadostudio/web/StudioWebBridge.kt")
        val save = text.substringAfter("private suspend fun settingsSave").substringBefore("private fun applyAppearanceToHost")
        assertTrue(save.contains("mcp.updateConnection(draft.port, draft.tokenRequired)"))
        assertFalse(save.contains("tokenRequired = true"))
        assertTrue(save.contains("if (draft.tokenRequired != null) failed += \"tokenRequired\""))
        assertTrue("readback belongs to save transaction", save.indexOf("val result = bootstrap()") < save.lastIndexOf("return@withLock result"))
        assertFalse("save lock must cover all bridge instances", text.contains("private val settingsLock = Mutex()\n\n    /** 本地工具"))
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
