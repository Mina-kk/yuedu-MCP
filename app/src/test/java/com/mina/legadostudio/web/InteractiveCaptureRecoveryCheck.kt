package com.mina.legadostudio.web

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.network.HttpLogRecorder
import com.mina.legadostudio.network.CaptureActivity
import com.mina.legadostudio.verification.WebViewCaptureEngine
import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.util.concurrent.*
import kotlin.coroutines.*

/** Real host/engine/recorder; fake only Android construction and Room IO. */
fun main() = runBlocking {
    // Android's compile API does not expose sun.misc.Unsafe. Resolve it only on
    // the host JVM to bypass Android stub constructors, not production behavior.
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").let { it.isAccessible = true; it.get(null) }
    val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
    fun <T> allocate(type: Class<T>): T = type.cast(allocateInstance.invoke(unsafe, type))
    fun field(target: Any, name: String, value: Any?) { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val rows = java.util.Collections.synchronizedList(mutableListOf<com.mina.legadostudio.data.db.HttpLogEntity>())
    val prefs = Proxy.newProxyInstance(StudioDao::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, m, _ -> if (m.name == "getBoolean") true else null }
    val dao = Proxy.newProxyInstance(StudioDao::class.java.classLoader, arrayOf(StudioDao::class.java)) { _, m, args ->
        when(m.name) {
            "addHttpLog" -> {
                val row = args!![0] as com.mina.legadostudio.data.db.HttpLogEntity
                if (row.originKind == WebViewCaptureEngine.ORIGIN_WEBVIEW_SESSION) error("injected summary disk failure")
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); rows.add(row); 1L
            }
            "countHttpLogsByContextId" -> rows.size
            "httpLogsByContextIdPage" -> rows.toList()
            else -> null
        }
    } as StudioDao
    val writer = Executors.newSingleThreadExecutor()
    val recorder = allocate(HttpLogRecorder::class.java)
    for ((k,v) in mapOf("prefs" to prefs, "dao" to dao, "gson" to Gson(), "asyncWriter" to writer)) field(recorder,k,v)
    val context = allocate(ContextWrapper::class.java)
    val engine = WebViewCaptureEngine(context, recorder, { emptyList() }, { true })
    val session = engine.newSession("https://example.com/", interactive = true)
    val host = InteractiveCaptureHost(context, engine, dao, {}, {}, {}, { JsonObject() })
    field(host,"session",session); field(host,"phase","active"); field(host,"currentUrl",session.entryUrl)
    suspend fun invoke(name: String): Any? = suspendCoroutine { continuation ->
        val method = host.javaClass.declaredMethods.single { it.name == name }
        method.isAccessible = true
        try {
            val result = method.invoke(host, continuation)
            if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resume(result)
        } catch (e: java.lang.reflect.InvocationTargetException) { continuation.resumeWithException(e.targetException) }
    }
    try {
        recorder.recordAsync(HttpLogRecorder.Draft("GET", session.entryUrl, contextId = session.contextId))
        check(entered.await(5,TimeUnit.SECONDS))
        val finish = async { runCatching { invoke("finish") } }
        yield(); delay(100)
        check(engine.isCaptureSessionActive(session.contextId))
        check(!CaptureActivity.deleteIfInactive(session.contextId) { error("deleted during FIFO") })
        release.countDown()
        check(finish.await().isFailure) { "failed summary must not be called saved" }
        check(!engine.isCaptureSessionActive(session.contextId))
        check(rows.size == 1) { "captured transaction lost" }
        // Exercise the production close branch without Android's Main dispatcher.
        val close = invoke("close") as JsonObject
        check(close.get("closed").asBoolean)
        check(!close.get("saved").asBoolean)
        check(close.get("contextId").asString == session.contextId)
        check(close.get("diagnostic").asString.isNotBlank())
        check(rows.size == 1)
        check(host.javaClass.getDeclaredField("phase").let { it.isAccessible=true; it.get(host) } == "failed")
        check(CaptureActivity.deleteIfInactive(session.contextId) {})
        println("Recovery: real summary DAO failure, FIFO protection, retained row, unsaved close and reopenable phase passed")
    } finally { release.countDown(); writer.shutdown(); check(writer.awaitTermination(5,TimeUnit.SECONDS)) }
}
