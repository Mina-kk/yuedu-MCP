package com.mina.legadostudio.web

import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceFileExportTest {
    private class Host(val save: suspend (File, String, String) -> Unit) : SafHost {
        override suspend fun exportFile(file: File, name: String, mime: String) = save(file, name, mime)
        override suspend fun export(request: SafRequest.Export): Unit = error("Generic text SAF must not be called")
        override suspend fun import(): SafImportResult = error("Not an import")
    }
    private fun request(text: String, name: String = "book-sources.json") = JsonObject().apply {
        addProperty("name", name); addProperty("text", text); addProperty("mime", "application/json")
    }
    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest()
    }
    @Test fun pickerCancellationReturnsExplicitFalseAndCleansOnlyItsFile() = runBlocking {
        val dir = Files.createTempDirectory("source-export-cancel-").toFile()
        try {
            val unrelated = File(dir, "keep.json").apply { writeText("keep") }
            var temporary: File? = null
            val exporter = SourceFileExport(dir, Host { file, _, _ ->
                temporary = file
                assertTrue(file.isFile)
                throw SafLauncher.Cancelled()
            })
            val result = exporter.export(request("[ {\"id\":1} ]"))
            assertFalse(result["saved"].asBoolean)
            assertTrue(result["cancelled"].asBoolean)
            assertEquals(setOf("saved", "cancelled"), result.keySet())
            assertFalse(temporary!!.exists())
            assertEquals("keep", unrelated.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun hostFailureAndCoroutineCancellationPropagateWithoutLeakingTemps() = runBlocking {
        val dir = Files.createTempDirectory("source-export-failure-").toFile()
        try {
            val unrelated = File(dir, "keep.json").apply { writeText("keep") }
            for (failure in listOf(java.io.IOException("copy/readback failed"), kotlinx.coroutines.CancellationException("disposed"))) {
                var temporary: File? = null
                val exporter = SourceFileExport(dir, Host { file, _, _ -> temporary = file; throw failure })
                val result = runCatching { exporter.export(request("[{}]")) }
                assertSame(failure, result.exceptionOrNull())
                assertFalse(temporary!!.exists())
                assertEquals("keep", unrelated.readText())
                assertEquals(setOf("keep.json"), dir.list()!!.toSet())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun overlappingExportsUseUniqueTempsAndDoNotAcceptCallerPaths() = runBlocking {
        val dir = Files.createTempDirectory("source-export-overlap-").toFile()
        try {
            var firstFile: File? = null
            var secondFile: File? = null
            val second = SourceFileExport(dir, Host { file, name, mime ->
                secondFile = file
                assertNotEquals(firstFile, file)
                assertTrue(firstFile!!.isFile)
                assertEquals("[ 2 ]", file.readText())
                assertEquals("application/json", mime)
                assertFalse(name.contains('/') || name.contains('\\') || name.contains('\n'))
                assertEquals(dir.canonicalFile, file.parentFile!!.canonicalFile)
            })
            val first = SourceFileExport(dir, Host { file, _, _ ->
                firstFile = file
                val p = request("[ 2 ]", "../../outside\\path\n.json").apply {
                    addProperty("path", File(dir.parentFile, "do-not-touch.json").path)
                    addProperty("mime", "text/html")
                }
                assertTrue(second.export(p)["saved"].asBoolean)
                assertFalse(secondFile!!.exists())
                assertEquals("[ 1 ]", file.readText())
            })
            assertTrue(first.export(request("[ 1 ]"))["saved"].asBoolean)
            assertFalse(firstFile!!.exists())
            assertTrue(dir.list()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun malformedInternalArgumentsNeverReachHostOrCreateFiles() = runBlocking {
        val dir = Files.createTempDirectory("source-export-invalid-").toFile()
        try {
            val exporter = SourceFileExport(dir, Host { _, _, _ -> fail("invalid arguments reached host") })
            for (p in listOf(JsonObject(), request("[]", ".."), request("[]", "x".repeat(121)), request("[]").apply { remove("text") })) {
                assertTrue(runCatching { exporter.export(p) }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(dir.list()!!.isEmpty())
            }
            assertEquals(2 * 1024 * 1024, WebBridgeProtocol.MAX_REQUEST_BYTES)
            assertNull(WebBridgeProtocol.checkExportText("x".repeat(WebBridgeProtocol.MAX_REQUEST_BYTES)))
            assertNotNull(WebBridgeProtocol.checkExportText("x".repeat(WebBridgeProtocol.MAX_REQUEST_BYTES + 1)))
        } finally { dir.deleteRecursively() }
    }

    @Test fun largeRawUtf8ArrayReachesFileHostUnchangedWithoutSourceInReceipt() = runBlocking {
        val dir = Files.createTempDirectory("source-export-test-").toFile()
        try {
            val raw = "[\n  {\n \"large\":9007199254740993123456789, \"text\":\"" + "中😀".repeat(400_000) + "\"\n } \n]\n"
            val expected = raw.toByteArray(Charsets.UTF_8)
            assertTrue(expected.size > WebBridgeProtocol.MAX_REQUEST_BYTES)
            assertNotNull("JS text export must still reject this same payload", WebBridgeProtocol.checkExportText(raw))
            var temporary: File? = null
            var completed = false
            val host = Host { file, name, mime ->
                temporary = file
                assertEquals(dir.canonicalFile, file.parentFile!!.canonicalFile)
                assertEquals("book-sources.json", name)
                assertEquals("application/json", mime)
                assertArrayEquals(expected, file.readBytes())
                val received = File(dir, "receiver-copy")
                file.inputStream().use { input -> received.outputStream().use { input.copyTo(it) } }
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), digest(received))
                completed = true
            }
            val result = SourceFileExport(dir, host).export(request(raw))
            assertTrue(completed)
            assertTrue(result["saved"].asBoolean)
            assertEquals(setOf("saved"), result.keySet())
            assertFalse(temporary!!.exists())
            assertTrue(File(dir, "receiver-copy").isFile)
        } finally { dir.deleteRecursively() }
    }
}
