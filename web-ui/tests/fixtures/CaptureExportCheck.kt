package com.mina.legadostudio.web

import android.content.ContextWrapper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.verification.WebViewCapture
import com.mina.legadostudio.verification.WebViewCaptureEngine
import kotlinx.coroutines.*
import java.io.File
import java.lang.reflect.Proxy
import kotlin.coroutines.*

private class ExportContext : ContextWrapper(null) {
    lateinit var directory: File
    override fun getCacheDir(): File = directory
}
fun main(args: Array<String>) = runBlocking {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").let { it.isAccessible = true; it.get(null) }
    fun <T> allocate(type: Class<T>): T = type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    fun field(target: Any, name: String, value: Any?) { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }
    val context = allocate(ExportContext::class.java).apply { directory = File(args[0]) }
    val rows = listOf(HttpLogEntity(id=1, method="GET", url="https://example.com", finalUrl="https://example.com", statusCode=200, durationMs=1, requestHeaders="", responseHeaders="", requestBody="", error="", redirectChain="[]", responseBody="[binary image/png] [bodyFile=captures/missing.bin]"))
    val dao = Proxy.newProxyInstance(StudioDao::class.java.classLoader, arrayOf(StudioDao::class.java)) { _, m, _ ->
        when(m.name) { "countHttpLogsByContextId" -> rows.size; "httpLogsByContextIdPage" -> rows; else -> null }
    } as StudioDao
    var mode = "saved"
    var exported: JsonObject? = null
    var temp: File? = null
    val host = InteractiveCaptureHost(context, allocate(WebViewCaptureEngine::class.java), dao, {}, {}, {}, { file ->
        temp = file
        exported = JsonParser.parseString(file.readText()).asJsonObject
        if (mode == "error") error("injected provider failure")
        JsonObject().apply { addProperty("saved", mode == "saved"); addProperty("cancelled", mode == "cancelled") }
    })
    field(host,"session",WebViewCapture.Session("cap:export", "https://example.com", null))
    field(host,"phase","saved")
    suspend fun export(): JsonObject = suspendCoroutine { continuation ->
        val method = host.javaClass.declaredMethods.single { it.name == "export" }.apply { isAccessible = true }
        try { val result = method.invoke(host, continuation); if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resume(result as JsonObject) }
        catch (e: java.lang.reflect.InvocationTargetException) { continuation.resumeWithException(e.targetException) }
    }
    val result = export()
    for (key in listOf("count", "total", "partial", "missingResources", "omittedResources")) {
        check(result.get(key) == exported!!.get(key)) { "Host discarded exporter metadata: $key" }
    }
    check(result.get("partial").asBoolean && result.get("missingResources").asInt == 1)
    check(result.get("saved").asBoolean)
    File(args[0], "receipt.json").writeText(result.toString())
    check(temp?.exists() == false)
    mode = "cancelled"
    val cancelled = export(); check(!cancelled.get("saved").asBoolean && cancelled.get("cancelled").asBoolean)
    mode = "error"; check(runCatching { export() }.isFailure); check(temp?.exists() == false)
    println("PASS actual Host + exporter: missing resource receipt equals file metadata; cancelled/error not saved; temp cleanup")
}
