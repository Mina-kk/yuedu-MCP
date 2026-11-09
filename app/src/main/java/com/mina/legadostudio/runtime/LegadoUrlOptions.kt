package com.mina.legadostudio.runtime

import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpFetcher

object LegadoUrlOptions {
    data class Parsed(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val charset: String? = null,
        val bodyJs: String? = null,
        val webView: Boolean = false,
        val webJs: String? = null,
        val webViewDelayTime: Long = 800,
    )

    fun parse(value: String): Parsed {
        val split = ReaderRequestTemplate.splitUrlOption(value)
        if (split.optionText == null) return Parsed(value.trim())
        val option = ReaderRequestTemplate.parseOption(split.optionText)
        return Parsed(
            url = split.urlPart.trim(), method = option.method, headers = option.headers,
            body = option.body, charset = option.charset, bodyJs = option.bodyJs,
            webView = option.webView, webJs = option.webJs,
            webViewDelayTime = option.raw?.get("webViewDelayTime")?.asLong ?: 800,
        )
    }

    fun toFetchRequest(value: String): HttpFetcher.FetchRequest {
        val parsed = parse(value)
        return HttpFetcher.FetchRequest(parsed.url, parsed.method, parsed.headers, parsed.body, parsed.charset)
    }
}
