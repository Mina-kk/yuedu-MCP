package com.mina.legadostudio.network

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.brotli.BrotliInterceptor
import java.net.InetAddress
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/** HTTP 200 但正文是 JS 人机挑战页（如阿里书屋的 内容正在载入 + var c2= + /nnxswnn/ 脚本），不是业务数据。 */
class JsChallengeException(
    val url: String,
    val challengeMarker: String,
    message: String,
) : Exception(message)

class HttpFetcher(
    private val cookieHeaderProvider: (String) -> String? = { null },
    /** 会话 Cookie jar：响应 Set-Cookie 自动落库、后续同域请求自动回带；null = 关闭 jar（保持旧行为，仅显式头）。 */
    private val cookieJar: okhttp3.CookieJar? = null,
    private val logRecorder: HttpLogRecorder? = null,
    private val userAgentProvider: () -> String = { DEFAULT_UA },
    private val sourceTypeProvider: () -> Int = { 0 },
    /** 重定向/请求目标加固钩子：每次 followRedirect 前收到已 resolve 的绝对目标 URL，非空返回值立即中断本次请求。仅逐次抓包注入。 */
    private val unsafeRedirectGuard: ((String) -> String?)? = null,
    /** DNS 解析守卫：解析出的任一地址属于私网/回环/保留段时返回非空原因即拒绝建连。仅逐次抓包注入。 */
    private val unsafeDnsGuard: ((InetAddress) -> String?)? = null,
    /** 中间 3xx 逐跳观察钩子（network 层，先于应用层 interceptor）：仅在响应为 3xx 重定向时收到逐跳元数据，回调抛异常会中断请求。仅逐次抓包注入。 */
    private val unsafePerHopRecorder: ((RedirectHop) -> Unit)? = null,
    /**
     * 测试缝：同步落库钩子，签名与 HttpLogRecorder.record 一致；非空时优先于 logRecorder 生效，
     * 供 JVM 单测把 fetch 的最终落点/失败 error 行接进内存 LogStore（生产不传）。
     */
    private val unsafeSyncRecorder: ((HttpLogRecorder.Draft) -> Unit)? = null,
) {

    /**
     * 重定向中间一跳的脱敏元数据：不含响应正文；请求头仅记录方法+URL，避免把 Cookie/凭据落到别处。
     * 由 network interceptor 在守卫判定前构造，被 redirect guard 阻断的一跳也会先记录（保留 3xx 证据）。
     */
    data class RedirectHop(
        /** 本跳请求 URL（OkHttp 已把上一跳 Location resolve 成绝对地址）。 */
        val requestUrl: String,
        val method: String,
        val statusCode: Int,
        /** 3xx 响应头多值合并后的扁平映射（脱敏由落库方负责）。 */
        val responseHeaders: Map<String, String>,
        /** resolve 后的绝对重定向目标；非 http(s) 或无法 resolve 时为 null。 */
        val nextUrl: String?,
    )
    data class FetchRequest(
        val url: String? = null,
        val method: String? = "GET",
        val headers: Map<String, String>? = emptyMap(),
        val body: String? = null,
        val charset: String? = null,
        val timeoutSec: Int? = 30,
        val maxBodyBytes: Long? = null,
        /** 逐事务传递的来源锚点；不进缓存 key，不影响请求本身 */
        val origin: HttpOrigin? = null,
        /** false = 本次请求禁用会话 Cookie jar（既不回带也不吸收 Set-Cookie）；默认 true。 */
        val useCookieJar: Boolean = true,
        /**
         * true = 放行「明确是字体」的二进制响应（woff/woff2/ttf/otf Content-Type 或 URL 扩展名），
         * 下载原始字节进 rawBytes（body 仍为空串，日志记说明不记二进制）。仅 MCP fetch_font 与
         * JS java.fetchFont 使用；默认 false 完全不影响既有二进制跳过行为。
         */
        val allowFontBinary: Boolean = false,
        /** 验证码专用：只读取经格式校验的图片原始字节，最多 2MB；普通 fetch 不变。 */
        val allowImageBinary: Boolean = false,
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
        val rawBytes: ByteArray? = null,
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
            .apply {
                // 会话 Cookie jar：本跳 Set-Cookie 落库 + 后续同域自动回带；逐请求 useCookieJar=false 可关。
                if (input.useCookieJar && cookieJar != null) cookieJar(cookieJar)
                unsafeDnsGuard?.let { guard ->
                    dns { hostname ->
                        val resolved = okhttp3.Dns.SYSTEM.lookup(hostname)
                        // 公网域名若解析到回环/私网/保留地址（DNS 重绑定/内网劫持），拒绝建连
                        resolved.firstNotNullOfOrNull { guard(it) }?.let { reason ->
                            throw java.net.UnknownHostException("DNS 解析被拒绝：$reason")
                        }
                        resolved
                    }
                }
                if (unsafeRedirectGuard != null || unsafePerHopRecorder != null) {
                    addNetworkInterceptor { chain ->
                        val prior = chain.proceed(chain.request())
                        // 只在 3xx 且带 Location 时校验：把 Location 相对当前请求 URL resolve 成绝对目标再判，
                        // 避免 `//127.0.0.1/`、相对路径、host 解析跳私网等绕行；非 3xx 的 Location 不拦截（不错杀）。
                        if (prior.isRedirect) {
                            val location = prior.header("Location")
                            if (!location.isNullOrBlank()) {
                                val target = runCatching { prior.request.url.resolve(location.trim()) }.getOrNull()
                                // 先落逐跳证据（含即将被 guard 拒绝的一跳），再走守卫判定：
                                // 被拦的 3xx 本身就是要留下的关键证据，不能只记一条失败尾部。
                                unsafePerHopRecorder?.invoke(
                                    RedirectHop(
                                        requestUrl = prior.request.url.toString(),
                                        method = prior.request.method,
                                        statusCode = prior.code,
                                        responseHeaders = prior.headers.toMultimap().mapValues { it.value.joinToString("; ") },
                                        nextUrl = target?.toString(),
                                    )
                                )
                                val guard = unsafeRedirectGuard ?: return@addNetworkInterceptor prior
                                val reason = when {
                                    target == null -> "重定向 Location 无法解析为绝对目标"
                                    else -> guard(target.toString())
                                }
                                if (reason != null) {
                                    prior.close()
                                    throw java.io.IOException("重定向被拦截：$reason")
                                }
                            }
                        }
                        prior
                    }
                }
            }
            .build()
        val headers = Headers.Builder().apply {
            add("User-Agent", userAgentProvider())
            add("Accept-Language", "zh-CN,zh;q=0.9")
            // jar 生效时显式串（RuntimeCookieStore prefs/WebView 采集）由 StudioCookieJar 的
            // loadForRequest overlay 统一回带，不显式加 Cookie 头（避免与 jar 头两行并存）；
            // jar 关闭时退回旧行为：仅显式头、Set-Cookie 不落库。
            val explicitCookie = cookieHeaderProvider(url)?.takeIf { it.isNotBlank() }
            if (!(input.useCookieJar && cookieJar is StudioCookieJar)) {
                explicitCookie?.let { add("Cookie", it) }
            }
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
                if (input.allowImageBinary) {
                    require(method == "GET") { "CAPTCHA_METHOD：验证码图片只支持 GET" }
                    require(response.code in 200..299) {
                        "CAPTCHA_HTTP_${response.code}：验证码图片拉取失败；若遇 403/Cloudflare，请先在手机验证中心打开原站页面过盾，再重试图片请求（Cookie 不保证跨 WebView/HTTP 通道可用）"
                    }
                    val cap = (input.maxBodyBytes ?: MAX_IMAGE_BYTES).coerceIn(1, MAX_IMAGE_BYTES)
                    val source = response.body.source()
                    require(response.body.contentLength() <= cap) { "CAPTCHA_TOO_LARGE：验证码图片超过 2MB" }
                    source.request(cap + 1)
                    require(source.buffer.size <= cap) { "CAPTCHA_TOO_LARGE：验证码图片超过 2MB" }
                    val imageBytes = source.readByteArray(source.buffer.size)
                    require(imageBytes.isNotEmpty()) { "CAPTCHA_EMPTY：验证码图片响应为空" }
                    val length = response.body.contentLength()
                    require(length < 0 || imageBytes.size.toLong() == length) { "CAPTCHA_TRUNCATED：验证码图片未完整下载" }
                    val mime = detectImageMime(imageBytes)
                        ?: throw IllegalArgumentException("CAPTCHA_NOT_IMAGE：验证码响应不是受支持的图片（可能是 HTML/挑战页）")
                    val contentType = response.body.contentType()?.toString()?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    require(contentType.isEmpty() || contentType == "application/octet-stream" || contentType == "binary/octet-stream" || contentType == mime ||
                        (mime == "image/jpeg" && contentType == "image/jpg")) {
                        "CAPTCHA_NOT_IMAGE：响应 Content-Type 与图片格式不符（可能是 HTML/挑战页）"
                    }
                    val note = "验证码图片已验证（$mime，${imageBytes.size} 字节）；正文不记录图片字节"
                    recordDraft(HttpLogRecorder.Draft(method, url, finalUrl, response.code, elapsed, requestHeaders, responseHeaders, input.body.orEmpty(), note, redirectChain = redirectChain, sourceAnchor = input.origin?.sourceAnchor, contextId = input.origin?.contextId, originKind = input.origin?.originKind))
                    val verifiedHeaders = responseHeaders.filterKeys { !it.equals("Content-Type", ignoreCase = true) } + ("Content-Type" to mime)
                    return FetchResult(response.code, finalUrl, verifiedHeaders, "", elapsed, redirectChain, note, imageBytes.size.toLong(), rawBytes = imageBytes)
                }
                // 按当前书源类型处理二进制响应:不下载正文,避免图片/音视频等不相干内容进入解析与日志
                if (method != "HEAD" && isBinaryContent(response.body.contentType()?.toString(), finalUrl)
                    && !(input.allowFontBinary && isFontContent(response.body.contentType()?.toString(), finalUrl))) {
                    val contentLength = response.body.contentLength().coerceAtLeast(0)
                    response.body.close()
                    val sourceType = sourceTypeProvider().coerceIn(-1, 4)
                    val sizeText = if (contentLength > 0) "，大小约 ${contentLength / 1024} KB" else ""
                    val note = when (sourceType) {
                        0 -> "已按当前书源类型（文本）跳过二进制资源，未下载正文$sizeText；制作音频/图片/文件/视频书源请到 MCP 页切换书源类型，图文混合站点可选「自动」"
                        -1 -> "二进制资源（当前书源类型：自动）$sizeText，未下载正文；书源规则只需引用该 URL"
                        else -> "二进制资源（当前书源类型：${RuntimeConfigStore.typeName(sourceType)}）$sizeText，未下载正文；书源规则只需引用该 URL"
                    }
                    recordDraft(HttpLogRecorder.Draft(method, url, finalUrl, response.code, elapsed, requestHeaders, responseHeaders, input.body.orEmpty(), note, redirectChain = redirectChain, sourceAnchor = input.origin?.sourceAnchor, contextId = input.origin?.contextId, originKind = input.origin?.originKind))
                    return FetchResult(response.code, finalUrl, responseHeaders, "", elapsed, redirectChain, note, contentLength)
                }
                // 字体二进制专属通道（allowFontBinary + 字体 Content-Type/扩展名）：
                // body 恒为空串、字节进 rawBytes、日志只记说明（不落二进制、不做 charset/挑战页文本判定）。
                if (method != "HEAD" && input.allowFontBinary
                    && isFontContent(response.body.contentType()?.toString(), finalUrl)) {
                    val cap = (input.maxBodyBytes ?: MAX_FONT_BYTES).coerceIn(1, MAX_FONT_BYTES)
                    val source = response.body.source()
                    source.request(cap + 1)
                    require(source.buffer.size <= cap) { "FONT_TOO_LARGE：字体超过 ${cap / 1024}KB，未截断保存" }
                    val fontBytes = source.readByteArray(source.buffer.size)
                    val contentLength = response.body.contentLength().coerceAtLeast(0)
                    // Content-Length 已知时必须精确匹配：不一致说明没拿到完整字体（对端分块/截断），
                    // 宁可用 noContentLength 让 socket close 触发 source.request EOF 读取；仍不足则拒绝。
                    if (contentLength > 0) {
                        require(fontBytes.size.toLong() == contentLength) {
                            "FONT_TRUNCATED：声明 $contentLength 字节实际只收到 ${fontBytes.size}，拒绝按字体放行"
                        }
                    }
                    require(Woff2Decoder.looksLikeFont(fontBytes)) {
                        "FONT_NOT_FONT：内容不是 wOF2/wOFF/TTF/OTTO 魔数，拒绝按字体放行"
                    }
                    val note = "字体资源已按二进制保留（${fontBytes.size} 字节，rawBytes 内，body 为空）；用 fetchFont/decodeWoff2 解析 cmap"
                    recordDraft(HttpLogRecorder.Draft(method, url, finalUrl, response.code, elapsed, requestHeaders, responseHeaders, input.body.orEmpty(), note, redirectChain = redirectChain, sourceAnchor = input.origin?.sourceAnchor, contextId = input.origin?.contextId, originKind = input.origin?.originKind))
                    return FetchResult(response.code, finalUrl, responseHeaders, "", elapsed, redirectChain, note, fontBytes.size.toLong(), rawBytes = fontBytes)
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
                val challengeMarker = jsChallengeMarker(response.code, text)
                // 200 挑战页不是业务数据：在正文与日志里留下可识别标记，debug/check 不会把它误当 CSS 空结果
                val challengeNote = challengeMarker?.let { JS_CHALLENGE_NOTE }
                recordDraft(HttpLogRecorder.Draft(method, url, finalUrl, response.code, elapsed, requestHeaders, responseHeaders, input.body.orEmpty(), text, redirectChain = redirectChain, sourceAnchor = input.origin?.sourceAnchor, contextId = input.origin?.contextId, originKind = input.origin?.originKind))
                val verifyMarker = verificationMarker(response.code, finalUrl, text)
                if (verifyMarker != null) {
                    throw com.mina.legadostudio.verification.VerificationRequiredException(
                        finalUrl, response.request.url.host, viaWebView = false, marker = verifyMarker, code = response.code,
                    )
                }
                FetchResult(response.code, finalUrl, responseHeaders, text, elapsed, redirectChain, challengeNote.orEmpty(), rawBytes = bytes)
            }
        } catch (error: Throwable) {
            if (error !is com.mina.legadostudio.verification.VerificationRequiredException) {
                recordDraft(HttpLogRecorder.Draft(method, url, durationMs = System.currentTimeMillis() - started, requestHeaders = headers.toMultimap().mapValues { it.value.joinToString("; ") }, requestBody = input.body.orEmpty(), error = error.stackTraceToString(), sourceAnchor = input.origin?.sourceAnchor, contextId = input.origin?.contextId, originKind = input.origin?.originKind))
            }
            throw error
        }
    }

    /** 同步落库单入口：测试缝 unsafeSyncRecorder 优先，否则走 HttpLogRecorder.record（含 enabled/私网过滤）。 */
    private fun recordDraft(draft: HttpLogRecorder.Draft) {
        unsafeSyncRecorder?.invoke(draft) ?: logRecorder?.record(draft)
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

    /**
     * 高置信识别 HTTP 200 的 JS 人机挑战页（不是业务数据，CSS 规则在其上恒为空）。
     * 要求「载入中文案 + 挑战变量 + 挑战脚本路径」三者同时命中（≥3 特征）才判挑战页，
     * 普通业务页（含 200 的错误提示页如「文件不存在」）不会误报；返回命中的特征组合描述或 null。
     */
    fun jsChallengeMarker(code: Int, body: String): String? {
        if (code !in 200..299) return null
        val head = body.take(20_000)
        val hits = mutableListOf<String>()
        if (head.contains("内容正在载入") || head.contains("正在载入中") || head.contains("加载中，请稍候") ||
            head.contains("Checking your browser", true) || head.contains("Just a moment", true)) hits += "载入中文案"
        if (Regex("var\\s+c2\\s*=|var\\s+c1\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(head)) hits += "c2 挑战变量"
        if (head.contains("/nnxswnn/", true)) hits += "/nnxswnn/ 挑战脚本"
        if (head.contains("cf-chl-", true) || head.contains("challenges.cloudflare.com", true)) hits += "cf-chl"
        if (Regex("(?i)<meta[^>]+http-equiv=[\"']?refresh").containsMatchIn(head) &&
            head.contains("script", true)) hits += "meta refresh + script"
        return if (hits.size >= 3) hits.joinToString("+") else null
    }

    /**
     * 检查响应是否是 JS 挑战页：是则抛出 [JsChallengeException]（带可操作诊断），否则原样返回。
     * 只报告特征，不执行页面里的未知挑战 JS。debug_source/check_source 的抓取路径应过此闸门，
     * 避免把挑战页按「CSS 选择器没选到」误判成规则问题。
     */
    fun requireNoJsChallenge(result: FetchResult): FetchResult {
        // bodyNote 只在 fetch 已打上 JS_CHALLENGE 标记时采信（二进制跳过的 note 不算）；否则按正文特征复核
        val marker = if (result.bodyNote.contains("JS_CHALLENGE")) JS_CHALLENGE_NOTE
            else jsChallengeMarker(result.code, result.body) ?: return result
        throw JsChallengeException(
            url = result.finalUrl,
            challengeMarker = marker,
            message = "请求被 JS 人机挑战拦截（HTTP ${result.code}，特征：$marker）。响应是挑战页不是业务数据，" +
                "CSS/XPath 规则在其上选不到内容是正常现象，不要继续改选择器。需要先完成挑战（如 browser_verify 人工验证，" +
                "或按知识库《JS挑战与动态搜索避坑实战》在 searchUrl/bookList 的 @js 里完成挑战并种下通行 Cookie）后重试；" +
                "本工具不会执行挑战页里的未知 JS，也不会自动切 WebView。",
        )
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        val head = bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1)
        val name = Regex("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)").find(head)?.groupValues?.get(1)
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/150 Mobile Safari/537.36"
        const val MAX_IMAGE_BYTES = 2_000_000L

        private fun detectImageMime(bytes: ByteArray): String? {
            fun signature(vararg values: Int): Boolean = bytes.size >= values.size &&
                values.indices.all { bytes[it].toInt() and 0xff == values[it] }
            fun ascii(offset: Int, value: String): Boolean = bytes.size >= offset + value.length &&
                value.indices.all { bytes[offset + it].toInt() == value[it].code }
            return when {
                bytes.size >= 45 && signature(137, 80, 78, 71, 13, 10, 26, 10) &&
                    ascii(12, "IHDR") && ascii(bytes.size - 8, "IEND") -> "image/png"
                bytes.size >= 4 && signature(0xff, 0xd8, 0xff) &&
                    bytes[bytes.size - 2].toInt() and 0xff == 0xff && bytes.last().toInt() and 0xff == 0xd9 -> "image/jpeg"
                bytes.size >= 14 && (ascii(0, "GIF87a") || ascii(0, "GIF89a")) &&
                    bytes.last().toInt() and 0xff == 0x3b -> "image/gif"
                bytes.size >= 20 && ascii(0, "RIFF") && ascii(8, "WEBP") &&
                    (ascii(12, "VP8 ") || ascii(12, "VP8L") || ascii(12, "VP8X")) &&
                    ((0..3).fold(0L) { n, i -> n or ((bytes[4 + i].toLong() and 0xff) shl (8 * i)) } + 8 == bytes.size.toLong()) -> "image/webp"
                else -> null
            }
        }

        /** 命中 JS 挑战页时写入 FetchResult.bodyNote 的诊断文案。 */
        const val JS_CHALLENGE_NOTE = "JS_CHALLENGE_PAGE：响应是 JS 人机挑战页（HTTP 200），不是业务数据"

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

        /** fetchFont 路径的下载上限：4MB（JS fetchFont 另用 2MB 上限，见 StudioJsApi）。 */
        const val MAX_FONT_BYTES = 4L * 1024 * 1024

        /** 明确声明字体的 Content-Type（不含 octet-stream：它靠 URL 扩展名判）。 */
        private val FONT_TYPES = setOf(
            "font/woff", "font/woff2", "font/ttf", "font/otf", "font/sfnt",
            "application/font-woff", "application/x-font-woff", "application/font-woff2",
            "application/x-font-woff2", "application/x-font-ttf", "application/font-sfnt",
            "application/vnd.ms-opentype",
        )

        /** 无扩展名时仍可按路径段命中（如 /fonts/abc 的 CDN 指纹名），但只在 Content-Type 已声明字体时使用。 */
        private val FONT_EXTENSIONS = setOf("woff", "woff2", "ttf", "otf")

        /**
         * 仅当「Content-Type 明确是字体」或「URL 扩展名是字体后缀」时为 true。
         * octet-stream + 无扩展名的含糊响应不算（服务端声明不清就不放行二进制）。
         */
        fun isFontContent(contentType: String?, url: String): Boolean {
            val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (ct in FONT_TYPES || ct.startsWith("font/")) return true
            val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext in FONT_EXTENSIONS) {
                // 扩展名是字体后缀且 Content-Type 是 octet-stream/缺省/字体类 → 放行
                return ct.isEmpty() || ct == "application/octet-stream" || ct == "binary/octet-stream" || ct in FONT_TYPES || ct.startsWith("font/")
            }
            return false
        }
    }
}
