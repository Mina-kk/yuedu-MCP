package com.mina.legadostudio.network

import android.content.Context
import com.google.gson.Gson
import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.domain.LogFilterUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class HttpLogRecorder(context: Context, private val dao: StudioDao, private val gson: Gson) {
    data class Draft(
        val method: String,
        val url: String,
        val finalUrl: String = "",
        val statusCode: Int = 0,
        val durationMs: Long = 0,
        val requestHeaders: Map<String, String> = emptyMap(),
        val responseHeaders: Map<String, String> = emptyMap(),
        val requestBody: String = "",
        val responseBody: String = "",
        val error: String = "",
        val redirectChain: List<String> = emptyList(),
    )
    private val prefs = context.getSharedPreferences("http_log_config", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    fun record(draft: Draft) {
        if (!enabled) return
        // 关键防护：如果请求是本地回环（如 127.0.0.1、localhost）或私网 IP，直接丢弃，绝不污染抓包库
        if (LogFilterUtils.isLoopbackOrPrivate(draft.url)) return

        val entity = HttpLogEntity(
            method = draft.method,
            url = draft.url,
            finalUrl = draft.finalUrl,
            statusCode = draft.statusCode,
            durationMs = draft.durationMs,
            requestHeaders = gson.toJson(redact(draft.requestHeaders)),
            responseHeaders = gson.toJson(redact(draft.responseHeaders)),
            requestBody = redactText(draft.requestBody).take(8_192),
            responseBody = redactText(draft.responseBody).take(8_192),
            error = redactText(draft.error).take(2_000),
            redirectChain = gson.toJson(draft.redirectChain),
        )
        runBlocking(Dispatchers.IO) { dao.addHttpLog(entity) }
    }

    private fun redact(headers: Map<String, String>): Map<String, String> = headers.mapValues { (key, value) ->
        if (key.equals("Authorization", true) || key.equals("Cookie", true) || key.equals("Set-Cookie", true) || key.contains("api-key", true)) "***" else value
    }

    private fun redactText(value: String): String {
        return value.replace(Regex("(?i)(authorization|api[-_ ]?key|token)\\s*[:=]\\s*[^,;\\s]+")) { mr ->
            "${mr.groupValues[1]}=***"
        }
    }
}