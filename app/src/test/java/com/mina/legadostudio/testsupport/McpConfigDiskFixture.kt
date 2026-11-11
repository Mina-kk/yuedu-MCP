package com.mina.legadostudio.testsupport

import android.content.SharedPreferences
import com.mina.legadostudio.mcp.McpConfigStore
import java.io.File
import java.lang.reflect.Proxy
import java.util.Properties

/** Only Android preferences/Keystore I/O is replaced; every config decision runs in McpConfigStore. */
class McpConfigDiskFixture(private val root: File) {
    private val prefsFile = File(root, "mcp.properties")
    private val tokenFile = File(root, "token.fixture")
    var failCommit = false
    var beforeCommit: (() -> Unit)? = null
    var afterRead: ((String) -> Unit)? = null
    var tokenReadOverride: (() -> String?)? = null
    var beforeTokenWrite: (() -> Unit)? = null
    var afterTokenWrite: (() -> Unit)? = null
    var beforeEpoch: (() -> Unit)? = null
    var tokenWrites = 0
    var tokenChanges = 0
    var applyCalls = 0

    init { root.mkdirs(); tokenFile.writeText("fixture-existing-token") }

    private fun read(): Properties = Properties().apply {
        if (prefsFile.exists()) prefsFile.inputStream().use { load(it) }
    }

    fun seed(vararg values: Pair<String, Any>) {
        val data = read()
        values.forEach { (key, value) -> data.setProperty(key, value.toString()) }
        prefsFile.outputStream().use { data.store(it, null) }
    }

    fun stored(key: String): String? = read().getProperty(key)
    fun clearToken() { tokenFile.delete() }

    fun store(): McpConfigStore {
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            val key = args?.firstOrNull() as? String
            when (method.name) {
                "contains" -> read().containsKey(key)
                "getBoolean" -> (read().getProperty(key)?.toBoolean() ?: args!![1]).also { afterRead?.invoke(key!!) }
                "getInt" -> (read().getProperty(key)?.toInt() ?: args!![1]).also { afterRead?.invoke(key!!) }
                "getString" -> (read().getProperty(key) ?: args!![1]).also { afterRead?.invoke(key!!) }
                "edit" -> editor()
                else -> error("Unexpected preferences method ${method.name}")
            }
        } as SharedPreferences
        return McpConfigStore(prefs,
            { tokenReadOverride?.invoke() ?: tokenFile.takeIf { it.exists() }?.readText() },
            { token: String -> beforeTokenWrite?.invoke(); tokenWrites++; tokenFile.writeText(token); afterTokenWrite?.invoke() },
            { beforeEpoch?.invoke(); tokenChanges++ })
    }

    private fun editor(): SharedPreferences.Editor {
        val changes = mutableMapOf<String, String?>()
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putBoolean", "putInt", "putString" -> { changes[args!![0] as String] = args[1]?.toString(); proxy }
                "remove" -> { changes[args!![0] as String] = null; proxy }
                "commit", "apply" -> {
                    if (method.name == "apply") applyCalls++
                    beforeCommit?.invoke()
                    if (failCommit) false else {
                        val data = read()
                        changes.forEach { (key, value) -> if (value == null) data.remove(key) else data.setProperty(key, value) }
                        prefsFile.outputStream().use { data.store(it, null) }
                        true
                    }
                }
                else -> error("Unexpected preferences editor method ${method.name}")
            }
        } as SharedPreferences.Editor
    }
}
