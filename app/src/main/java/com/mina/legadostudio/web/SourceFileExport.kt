package com.mina.legadostudio.web

import com.google.gson.JsonObject
import java.io.File

/** Internal source-delivery callback, never a bridge method or a caller-supplied file path. */
internal class SourceFileExport(private val cacheDir: File, private val host: SafHost) {
    suspend fun export(params: JsonObject): JsonObject {
        fun string(key: String): String? = params.get(key)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val name = WebBridgeProtocol.sanitizeFileName(string("name"))
            ?: throw IllegalArgumentException("name 不是合法文件名")
        val text = string("text") ?: throw IllegalArgumentException("text 不能为空")
        val file = File.createTempFile("source-export-", ".json", cacheDir)
        try {
            file.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            // SafHost completes only after the existing SAF stream copy and digest readback.
            host.exportFile(file, name, "application/json")
            return JsonObject().apply { addProperty("saved", true) }
        } catch (_: SafLauncher.Cancelled) {
            return JsonObject().apply {
                addProperty("saved", false)
                addProperty("cancelled", true)
            }
        } finally {
            file.delete()
        }
    }
}
