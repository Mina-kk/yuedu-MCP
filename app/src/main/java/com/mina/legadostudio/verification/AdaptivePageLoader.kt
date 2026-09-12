package com.mina.legadostudio.verification

import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpLogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AdaptivePageLoader(
    private val http: HttpFetcher,
    private val webView: WebViewPageLoader,
    private val modes: DomainModeStore,
    private val logs: HttpLogRecorder? = null,
) {
    suspend fun load(request: HttpFetcher.FetchRequest): HttpFetcher.FetchResult {
        val url = request.url?.trim().orEmpty()
        val method = request.method?.uppercase() ?: "GET"
        if (url.isNotBlank() && method == "GET" && modes.requiresWebView(url)) {
            val result = webView.load(url)
            logs?.record(HttpLogRecorder.Draft(method = "WEBVIEW", url = url, finalUrl = result.finalUrl, statusCode = 200, durationMs = result.elapsedMs, responseBody = result.html))
            if (http.looksLikeVerification(403, result.finalUrl, result.html)) {
                throw VerificationRequiredException(
                    result.finalUrl, DomainKey.fromUrl(result.finalUrl), viaWebView = true,
                    marker = http.verificationMarker(403, result.finalUrl, result.html) ?: "webview", code = 200,
                )
            }
            return HttpFetcher.FetchResult(200, result.finalUrl, emptyMap(), result.html, result.elapsedMs)
        }
        return withContext(Dispatchers.IO) { http.fetch(request) }
    }
}
