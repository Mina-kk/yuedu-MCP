package com.mina.legadostudio.network

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.brotli.BrotliInterceptor
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class HttpFetcher(
    private val cookieHeaderProvider: (String) -> String? = { null },
    private val logRecorder: HttpLogRecorder? = null,
    private val userAgentProvider: () -> String = { DEFAULT_UA },
    private val sourceTypeProvider: () -> Int = { 0 },
) {
    data class FetchRequest(
        val url: String? = null,
        val method: String? = "GET",
        val headers: Map<String, String>? = emptyMap(),
        val body: String? = null,
        val charset: String? = null,
        val timeoutSec: Int? = 30,
        val maxBodyBytes: Long? = null,
    )
    @Keep
    data class FetchResult(
        @SerializedName("code") val code: Int,
        @SerializedName("finalUrl") val finalUrl: String,
        @SerializedName("headers") val headers: Map<String, String>,
        @SerializedName("body") val body: String,
        @SerializedName("elapsedMs") val elapsedMs: Long,
        @SerializedName("redirectChain") val redirectChain: List<String> = emptyList(),
        @SerializedName("bodyNote") val bodyNote: String = "",
        @SerializedName("binaryBytes") val binaryBytes: Long = 0,
    )

    fun fetch(input: FetchRequest): FetchResult {
        val url = input.url?.trim().orEmpty()
        require(url.isNotEmpty()) { "请先填写要抓取的网址" }
        require(url.startsWith("http://") || url.startsWith("https://")) { "仅支持 HTTP/HTTPS" }
        val method = input.method?.uppercase()?.takeIf { it in setOf("GET", "POST", "HEAD") } ?: "GET"
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .callTimeout((input.timeoutSec ?: 30).coerceIn(5, 120).toLong(), TimeUnit.SECONDS)
            .addInterceptor(BrotliInterceptor)
            .build()
        val headers = Headers.Builder().apply {
            add("User-Agent", userAgentProvider())
            add("Accept-Language", "zh-CN,zh;q=0.9")
            cookieHeaderProvider(url)?.takeIf { it.isNotBlank() }?.let { add("Cookie", it) }
            input.headers.orEmpty().forEach { (key, value) ->
                // OkHttp 只有在自己协商 Content-Encoding 时才会透明解压；调用方手工设置会把 gzip/br 原始字节泄漏给 HTML 解析器。
                if (key.isNotBlank() && !key.equals("Accept-Encoding", ignoreCase = true)) set(key, value)
            }
        }.build()
        val body = when (method) {
            "GET", "HEAD" -> null
            else -> (input.body.orEmpty()).toRequestBody(
                (headers["Content-Type"] ?: "application/x-www-form-urlencoded; charset=utf-8").toMediaType()
            )
        }
        val request = Request.Builder().url(url).headers(headers).method(method, body).build()
        val started = System.currentTimeMillis()
        try {
            return client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val responseHeaders = response.headers.toMultimap().mapValues { it.value.joinToString("; ") }
                val redirectChain = generateSequence(response.priorResponse) { it.priorResponse }.toList().asReversed().map { it.request.url.toString() } + finalUrl
                val elapsed = System.currentTimeMillis() - started
                val requestHeaders = headers.toMultimap().mapValues { it.value.joinToString("; ") }
                // 按当前书源类型处理二进制响应:不下载正文,避免图片/音视频等不相干内容进入解析与日志
                if (method != "HEAD" && isBinaryContent(response.body.contentType()?.toString(), finalUrl)) {
                    val contentLength = response.body.contentLength().coerceAtLeast(0)
                    response.body.close()
                    val sourceType = sourceTypeProvider().coerceIn(0, 4)
                    val sizeText = if (contentLength > 0) "，大小约 ${contentLength / 1024} KB" else ""
                    val note = if (sourceType == 0) {
                        "已按当前书源类型（文本）跳过二进制资源，未下载正文$sizeText；制作音频/图片/文件/视频书源请到 MCP 页切换书源类型"
                    } else {
                        "二进制资源（当前书源类型：${RuntimeConfigStore.typeName(sourceType)}）$sizeText，未下载正文；书源规则只需引用该 URL"
                    }
                    logRecorder?.record(HttpLogRecorder.Draft(method, url, finalUrl, response.code, elapsed, requestHeaders, responseHeaders, input.body.orEmpty(), note, redirectChain = redirectChain))
                    return FetchResult(response.code, finalUrl, responseHeaders, "", elapsed, redirectChain, note, contentLength)
                }
                val bytes = input.maxBodyBytes?.let { max ->
                    require(max in 1..8_000_000) { "maxBodyBytes 超出范围" }
                    val source = response.body.source()
                    source.request(max + 1)
                    require(source.buffer.size <= max) { "CONTENT_TOO_LARGE：网页超出上下文下载容量，未截断保存" }
                    source.readByteArray(source.buffer.size)
                } ?: response.body.bytes()
                val charset = input.charset?.let { runCatching { Charset.forName(it) }.getOrNull() }
                    ?: response.body.contentType()?.charset()
                    ?: detectCharset(bytes)
                val text = bytes.toString(charset)
                logRecorder?.record(HttpLogRecorder.Draft(method, url, finalUrl, response.code, elapsed, requestHeaders, responseHeaders, input.body.orEmpty(), text, redirectChain = redirectChain))
                val verifyMarker = verificationMarker(response.code, finalUrl, text)
                if (verifyMarker != null) {
                    throw com.mina.legadostudio.verification.VerificationRequiredException(
                        finalUrl, response.request.url.host, viaWebView = false, marker = verifyMarker, code = response.code,
                    )
                }
                FetchResult(response.code, finalUrl, responseHeaders, text, elapsed, redirectChain)
            }
        } catch (error: Throwable) {
            if (error !is com.mina.legadostudio.verification.VerificationRequiredException) {
                logRecorder?.record(HttpLogRecorder.Draft(method, url, durationMs = System.currentTimeMillis() - started, requestHeaders = headers.toMultimap().mapValues { it.value.joinToString("; ") }, requestBody = input.body.orEmpty(), error = error.stackTraceToString()))
            }
            throw error
        }
    }

    fun looksLikeVerification(code: Int, url: String, body: String): Boolean = verificationMarker(code, url, body) != null

    /** 仅按页面标记判定（不看状态码/URL 门限），验证中心轮询「挑战是否已放行」用。 */
    fun verificationMarkerLoose(body: String): String? =
        Regex("Verify Yourself|WAF/VERIFY/CAPTCHA|cf-chl-|challenges\\.cloudflare\\.com|turnstile|altcha-widget|aegis_altcha|人机验证|安全验证", RegexOption.IGNORE_CASE)
            .find(body.take(20_000))?.value

    /** 返回命中的验证页标记名（如 "cf-chl"、"turnstile"、"人机验证"），非验证页返回 null。 */
    fun verificationMarker(code: Int, url: String, body: String): String? {
        val hit = verificationMarkerLoose(body) ?: return null
        return if (code >= 403 || url.contains("verify", true) || url.contains("captcha", true)) hit else null
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        val head = bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1)
        val name = Regex("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)").find(head)?.groupValues?.get(1)
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/150 Mobile Safari/537.36"

        /** 可按文本读取的 Content-Type(m3u8 清单是文本,视频源制作需要读取)。 */
        private val TEXTUAL_TYPES = setOf(
            "application/json", "application/xml", "application/javascript", "application/ecmascript",
            "application/x-javascript", "application/x-www-form-urlencoded", "application/rss+xml",
            "application/atom+xml", "application/ld+json", "application/manifest+json",
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl",
            "image/svg+xml",
        )

        /** 无明确 Content-Type 时按 URL 扩展名识别二进制资源。 */
        private val BINARY_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "ico", "avif",
            "mp3", "m4a", "aac", "flac", "ogg", "wav",
            "mp4", "ts", "mov", "mkv", "avi", "webm", "flv", "m4s",
            "zip", "rar", "7z", "tar", "gz", "apk", "epub", "pdf", "mobi", "azw3",
            "woff", "woff2", "ttf", "otf", "exe", "dmg",
        )

        /** 判断响应是否为二进制内容(图片/音视频/文件等),文本页面与数据接口返回 false。 */
        fun isBinaryContent(contentType: String?, url: String): Boolean {
            val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (ct.isNotEmpty() && ct != "application/octet-stream" && ct != "binary/octet-stream") {
                if (ct.startsWith("text/")) return false
                if (ct in TEXTUAL_TYPES) return false
                if (ct.endsWith("+json") || ct.endsWith("+xml")) return false
                return true
            }
            val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext in BINARY_EXTENSIONS
        }
    }
}
