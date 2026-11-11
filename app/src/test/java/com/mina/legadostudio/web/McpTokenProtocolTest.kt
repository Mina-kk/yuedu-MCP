package com.mina.legadostudio.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class McpTokenProtocolTest {
    @Test fun replacementPreservesExactValueAndEmptyMeansNoOp() {
        listOf("{}", "{\"mcpToken\":null}", "{\"mcpToken\":\"\"}").forEach {
            val parsed = WebBridgeProtocol.parseSettings(JsonParser.parseString(it).asJsonObject) as WebBridgeProtocol.SettingsParse.Ok
            assertNull(parsed.settings.mcpToken)
        }
        listOf("a", "Az09._~+/-===", "x".repeat(4096)).forEach { value ->
            val parsed = WebBridgeProtocol.parseSettings(JsonObject().apply { addProperty("mcpToken", value) }) as WebBridgeProtocol.SettingsParse.Ok
            assertTrue(parsed.settings.mcpToken == value)
            assertNull(parsed.settings.tokenRequired)
        }
    }

    @Test fun invalidReplacementIsRejectedWithoutEcho() {
        val invalid = listOf(" ", " x", "x ", "x\ny", "x\ry", "x\ty", "中", "x=y", "=", "x".repeat(4097))
        invalid.forEach { value ->
            val parsed = WebBridgeProtocol.parseSettings(JsonObject().apply { addProperty("mcpToken", value) })
            assertTrue(parsed is WebBridgeProtocol.SettingsParse.Bad)
            assertTrue((parsed as WebBridgeProtocol.SettingsParse.Bad).message.startsWith("mcpToken"))
        }
        listOf("true", "123", "[]", "{}").forEach { value ->
            assertTrue(WebBridgeProtocol.parseSettings(JsonParser.parseString("{\"mcpToken\":$value}").asJsonObject) is WebBridgeProtocol.SettingsParse.Bad)
        }
    }
}
