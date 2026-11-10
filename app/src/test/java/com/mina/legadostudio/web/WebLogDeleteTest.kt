package com.mina.legadostudio.web

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebLogDeleteTest {
    private fun parse(json: String) = WebBridgeProtocol.parseLogDelete(JsonParser.parseString(json).asJsonObject)

    @Test fun `strict IDs and bounded batches without coercion`() {
        assertEquals(listOf("1", "9223372036854775807"), parse("""{"kind":"http","ids":["1","9223372036854775807"]}""")!!.ids)
        for (ids in listOf("[]", "[1]", "[true]", "[null]", "[\"1.0\"]", "[\"1e2\"]", "[\"01\"]", "[\"-1\"]", "[\" 1\"]", "[\"9223372036854775808\"]", "[\"1\",\"1\"]")) {
            assertNull(ids, parse("""{"kind":"operation","ids":$ids}"""))
        }
        val allowed = (1..100).joinToString { "\"$it\"" }
        assertEquals(100, parse("""{"kind":"http","ids":[$allowed]}""")!!.ids.size)
        val ids = (1..101).joinToString { "\"$it\"" }
        assertNull(parse("""{"kind":"http","ids":[$ids]}"""))
        assertNull(parse("""{"kind":"http","ids":["1"],"all":true}"""))
    }

    @Test fun `file identifiers cannot name arbitrary paths or unrelated files`() {
        for (kind in listOf("crash", "snapshot", "capture")) {
            for (id in listOf("../secret", "/secret", "cap:../secret", "settings.json", "", "*")) {
                assertNull(parse("""{"kind":"$kind","ids":["$id"]}"""))
            }
        }
        assertNotNull(parse("""{"kind":"crash","ids":["crash-123.txt","fault-456.txt"]}"""))
        val uuid = "12345678-1234-1234-1234-123456789abc"
        assertNotNull(parse("""{"kind":"snapshot","ids":["$uuid"]}"""))
        assertNotNull(parse("""{"kind":"capture","ids":["cap:$uuid"]}"""))
        assertTrue(WebBridgeProtocol.isAllowedMethod("logs.delete"))
        assertFalse(WebBridgeProtocol.isAllowedMethod("logs.clear"))
    }

    @Test fun `delete confirms absence and preserves failed selections`() = runBlocking {
        val store = FakeStore()
        val result = WebBridgeProtocol.deleteLogs(WebBridgeProtocol.LogDelete("http", listOf("1", "2", "3", "4")), store)
        assertEquals(listOf("1"), result.deletedIds)
        assertEquals(listOf("3"), result.missingIds)
        assertEquals(listOf("2", "4"), result.failed.map { it.id })
        assertEquals(listOf("1", "2"), store.attempts)
    }

    @Test fun `unsafe or active target is never deleted`() = runBlocking {
        val store = FakeStore()
        val result = WebBridgeProtocol.deleteLogs(WebBridgeProtocol.LogDelete("capture", listOf("4")), store)
        assertTrue(store.attempts.isEmpty())
        assertEquals("active_capture", result.failed.single().code)
    }

    private class FakeStore : WebBridgeProtocol.LogDeleteStore {
        val existing = mutableSetOf("1", "2", "4")
        val attempts = mutableListOf<String>()
        override suspend fun exists(kind: String, id: String) = id in existing
        override suspend fun blockedReason(kind: String, id: String) = if (id == "4") "active_capture" else null
        override suspend fun delete(kind: String, id: String) {
            attempts += id
            if (id == "1") existing.remove(id)
        }
    }
}
