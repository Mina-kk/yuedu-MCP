package com.mina.legadostudio.web

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LogDeleteReceiptContractTest {
    @Test fun `wire receipt retains exact keys and string IDs for every log kind`() {
        for (kind in listOf("operation", "http", "capture", "crash", "snapshot")) {
            val result = WebBridgeProtocol.LogDeleteResult(
                kind, listOf("9223372036854775807", "2"), listOf("3"),
                listOf(WebBridgeProtocol.LogDeleteFailure("4", "active_capture")),
            )
            val wire = logDeleteReceiptJson(result)
            assertEquals(JsonParser.parseString("""{"kind":"$kind","deletedIds":["9223372036854775807","2"],"missingIds":["3"],"failed":[{"id":"4","code":"active_capture"}]}"""), wire)
            assertEquals(setOf("kind", "deletedIds", "missingIds", "failed"), wire.keySet())
            assertEquals(setOf("id", "code"), wire.getAsJsonArray("failed")[0].asJsonObject.keySet())
        }
    }

    @Test fun `empty receipt includes all arrays rather than null or omitted fields`() {
        assertEquals(
            JsonParser.parseString("""{"kind":"http","deletedIds":[],"missingIds":[],"failed":[]}"""),
            logDeleteReceiptJson(WebBridgeProtocol.LogDeleteResult("http", emptyList(), emptyList(), emptyList())),
        )
    }

    @Test fun `actual deletion outcomes survive serialization including every failure code`() = runBlocking {
        val remaining = mutableSetOf("1", "2", "4", "5")
        val store = object : WebBridgeProtocol.LogDeleteStore {
            override suspend fun exists(kind: String, id: String) = id in remaining
            override suspend fun blockedReason(kind: String, id: String) = if (id == "4") "active_capture" else null
            override suspend fun delete(kind: String, id: String) {
                if (id == "5") error("storage unavailable")
                if (id == "1") remaining.remove(id)
            }
        }
        val result = WebBridgeProtocol.deleteLogs(WebBridgeProtocol.LogDelete("http", listOf("1", "2", "3", "4", "5")), store)
        assertEquals(
            JsonParser.parseString("""{"kind":"http","deletedIds":["1"],"missingIds":["3"],"failed":[{"id":"2","code":"delete_unconfirmed"},{"id":"4","code":"active_capture"},{"id":"5","code":"delete_failed"}]}"""),
            logDeleteReceiptJson(result),
        )
    }
}
