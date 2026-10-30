package com.mina.legadostudio.mcp

import com.google.gson.Gson
import com.mina.legadostudio.mcp.TaskContextStore.Entry
import com.mina.legadostudio.network.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TaskContextSnapshotTest {
    private val gson = Gson()

    @Test fun saveLoadPreservesRedirectChainFromPage() {
        val dir = Files.createTempDirectory("ctx-snap-chain").toFile()
        try {
            val chain = listOf("https://old.example.test/start", "https://new.example.test/final")
            val snap = TaskContextSnapshot.TaskSnapshot(
                id = "c1", label = "t", touched = 10L, notes = "",
                entries = listOf(
                    TaskContextSnapshot.EntrySnapshot(
                        id = "e1", kind = "page", text = "body", created = 10L, fingerprint = "fp",
                        code = 200, finalUrl = "https://new.example.test/final", elapsedMs = 5L,
                        redirectChain = chain,
                    ),
                ),
            )
            TaskContextSnapshot.save(dir, listOf(snap))
            val loaded = TaskContextSnapshot.load(dir).single()
            assertEquals(chain, loaded.entries.single().redirectChain)
            assertEquals(200, loaded.entries.single().code)
            assertEquals("https://new.example.test/final", loaded.entries.single().finalUrl)
            val json = java.io.File(dir, "c1.json").readText()
            assertTrue(json.contains("redirectChain"))
            assertFalse("Entry 没有 requestedUrl，快照不得编造", json.contains("requestedUrl"))
            assertFalse("Entry 没有 crossHostRedirect，快照不得编造", json.contains("crossHostRedirect"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun oldSnapshotMissingRedirectChainLoadsAsEmpty() {
        val dir = Files.createTempDirectory("ctx-snap-legacy").toFile()
        try {
            java.io.File(dir, "old.json").writeText(
                """{"id":"old","label":"t","touched":1,"notes":"n","entries":[{"id":"e1","kind":"page","text":"body","created":1,"fingerprint":"fp","code":200,"finalUrl":"https://example.org/"}]}""",
            )
            val loaded = TaskContextSnapshot.load(dir).single()
            assertEquals("old", loaded.id)
            assertEquals(emptyList<String>(), loaded.entries.single().redirectChain)
            assertEquals(200, loaded.entries.single().code)
            assertEquals("https://example.org/", loaded.entries.single().finalUrl)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun gsonIgnoresUnknownRequestedUrlAndCrossHostRedirect() {
        val json = """
            {"id":"e1","kind":"page","text":"body","created":1,"fingerprint":"fp",
             "code":302,"finalUrl":"https://b.example/","elapsedMs":3,
             "redirectChain":["https://a.example/","https://b.example/"],
             "requestedUrl":"https://a.example/","crossHostRedirect":true}
        """.trimIndent()
        val parsed = gson.fromJson(json, TaskContextSnapshot.EntrySnapshot::class.java)
        val normalized = TaskContextSnapshot.normalize(
            TaskContextSnapshot.TaskSnapshot("c", "", 1, "", listOf(parsed)),
        ).entries.single()
        assertEquals(listOf("https://a.example/", "https://b.example/"), normalized.redirectChain)
        val roundtrip = gson.toJson(normalized)
        assertFalse(roundtrip.contains("requestedUrl"))
        assertFalse(roundtrip.contains("crossHostRedirect"))
    }

    @Test fun entryPageHasRedirectChainButNotRequestedUrlFields() {
        val fields = Entry::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("Entry 通过 page 持有 FetchResult", "page" in fields)
        assertFalse(fields.contains("redirectChain"))
        assertFalse(fields.contains("requestedUrl"))
        assertFalse(fields.contains("crossHostRedirect"))
        val pageFields = HttpFetcher.FetchResult::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(pageFields.contains("redirectChain"))
        assertFalse(pageFields.contains("requestedUrl"))
        assertFalse(pageFields.contains("crossHostRedirect"))
        val snapFields = TaskContextSnapshot.EntrySnapshot::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(snapFields.contains("redirectChain"))
        assertFalse(snapFields.contains("requestedUrl"))
        assertFalse(snapFields.contains("crossHostRedirect"))
    }
}
