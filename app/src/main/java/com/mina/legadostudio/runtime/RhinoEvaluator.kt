package com.mina.legadostudio.runtime

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.verification.RuntimeCookieStore
import com.mina.legadostudio.verification.WebViewPageLoader
import com.script.rhino.RhinoScriptEngine
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

class RhinoEvaluator(
    private val fetcher: HttpFetcher,
    private val gson: Gson,
    private val webViewLoader: WebViewPageLoader? = null,
    private val cookies: RuntimeCookieStore? = null,
    private val userAgentProvider: () -> String = { HttpFetcher.DEFAULT_UA },
) {
    @Keep data class Result(
        @SerializedName("value") val value: String?,
        @SerializedName("logs") val logs: List<String>,
        @SerializedName("elapsedMs") val elapsedMs: Long,
    )
    data class RawResult(val value: Any?, val logs: List<String>, val elapsedMs: Long)

    fun evaluate(js: String, baseUrl: String = "", previous: Any? = null, bindings: Map<String, Any?> = emptyMap()): Result {
        val raw = evaluateRaw(js, baseUrl, previous, bindings)
        return Result(normalize(raw.value), raw.logs, raw.elapsedMs)
    }

    fun evaluateRaw(js: String, baseUrl: String = "", previous: Any? = null, bindings: Map<String, Any?> = emptyMap()): RawResult {
        require(js.isNotBlank()) { "JavaScript 不能为空" }
        val logs = mutableListOf<String>()
        val api = StudioJsApi(fetcher, logs::add, webViewLoader, cookies, userAgentProvider)
        val started = System.currentTimeMillis()
        val value = RhinoScriptEngine.eval(js) {
            put("java", api)
            put("result", previous)
            put("baseUrl", baseUrl)
            put("src", previous)
            // 官方同名对象：cookie/cache/source。bindings 传入的 source 状态图包成 SourceJsApi，JS 写入回传调试流程
            put("cookie", CookieJsApi(cookies))
            put("cache", CacheJsApi())
            bindings.forEach { (key, item) -> if (key != "source") put(key, item) }
            @Suppress("UNCHECKED_CAST")
            put("source", when (val bound = bindings["source"]) {
                is SourceJsApi -> bound
                is MutableMap<*, *> -> SourceJsApi(bound as MutableMap<String, String>)
                else -> SourceJsApi()
            })
        }
        return RawResult(value, logs, System.currentTimeMillis() - started)
    }

    private fun normalize(value: Any?): String? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value.toString()
        else -> runCatching { gson.toJson(value) }.getOrElse { value.toString() }
    }
}

class StudioJsApi(
    private val fetcher: HttpFetcher,
    private val logger: (String) -> Unit,
    private val webViewLoader: WebViewPageLoader? = null,
    private val cookies: RuntimeCookieStore? = null,
    private val userAgentProvider: () -> String = { HttpFetcher.DEFAULT_UA },
) {
    /** 官方兼容：java.lang.Thread.sleep(ms)（沙箱仅放行这一个 java.lang 用法，其余包路径不可用） */
    @JvmField
    val lang: StudioLangApi = StudioLangApi()

    fun log(message: Any?) { logger(message?.toString().orEmpty()) }
    fun toast(message: Any?) { logger("toast: ${message?.toString().orEmpty()}") }
    fun ajax(url: String): String = fetcher.fetch(HttpFetcher.FetchRequest(url)).body
    @JvmOverloads
    fun connect(url: String, header: Any? = null, timeout: Any? = null): StudioJsResponse {
        val parsed = LegadoUrlOptions.parse(url)
        val extra = when (header) {
            is Map<*, *> -> header.entries.associate { it.key.toString() to it.value.toString() }
            is String -> runCatching { com.google.gson.JsonParser.parseString(header).asJsonObject.entrySet().associate { it.key to it.value.asString } }.getOrDefault(emptyMap())
            else -> emptyMap()
        }
        val absolute = parsed.url.ifBlank { url }
        val result = fetcher.fetch(HttpFetcher.FetchRequest(absolute, parsed.method, parsed.headers + extra, parsed.body, parsed.charset))
        return StudioJsResponse(result.code, result.finalUrl, result.body, result.headers, result.elapsedMs)
    }
    @JvmOverloads
    fun startBrowserAwait(url: String, title: Any? = null, refetch: Any? = null): String {
        throw com.mina.legadostudio.verification.VerificationRequiredException(url, com.mina.legadostudio.verification.DomainKey.fromUrl(url))
    }
    fun getString(rule: String, content: Any? = null): String {
        val element = when (content) {
            is org.jsoup.nodes.Element -> content
            else -> org.jsoup.Jsoup.parse(content?.toString().orEmpty()).body().children().firstOrNull()
                ?: org.jsoup.Jsoup.parse(content?.toString().orEmpty()).body()
        }
        return when (rule) {
            "text", "textNodes" -> element.text()
            "html" -> element.html()
            "all" -> element.outerHtml()
            else -> element.attr(rule)
        }
    }
    fun get(url: String, headers: Any? = null): StudioJsResponse = request(url, "GET", null, headers)
    fun post(url: String, body: String, headers: Any? = null): StudioJsResponse = request(url, "POST", body, headers)
    @JvmOverloads
    fun encodeURI(value: String, charset: String = "UTF-8"): String = URLEncoder.encode(value, charset).replace("+", "%20")
    fun base64Encode(value: String): String = Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray())
    @JvmOverloads
    fun base64Decode(value: String, charset: String = "UTF-8"): String = String(Base64.getDecoder().decode(value), java.nio.charset.Charset.forName(charset))
    fun base64DecodeToByteArray(value: String): ByteArray = Base64.getDecoder().decode(value)
    fun md5Encode(value: String): String = digest(value, "MD5")
    fun md5Encode16(value: String): String = md5Encode(value).substring(8, 24)
    @JvmOverloads
    fun timeFormat(value: Long, format: String = "yyyy-MM-dd HH:mm:ss"): String = java.text.SimpleDateFormat(format, java.util.Locale.getDefault()).format(java.util.Date(value))
    fun randomUUID(): String = java.util.UUID.randomUUID().toString()
    fun getWebViewUA(): String = userAgentProvider()
    fun getCookie(url: String): String = cookies?.headerFor(url).orEmpty()
    fun getCookie(url: String, key: String?): String {
        val all = getCookie(url)
        if (key.isNullOrBlank()) return all
        return all.split(';').map { it.trim() }.firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "").orEmpty()
    }
    fun setCookie(url: String, cookie: String) { cookies?.set(url, cookie) ?: error("CookieStore 不可用") }
    fun hexEncodeToString(value: String): String = value.toByteArray().joinToString("") { "%02x".format(it) }
    fun hexDecodeToString(value: String): String = String(value.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    fun sha1Encode(value: String): String = digest(value, "SHA-1")
    fun sha256Encode(value: String): String = digest(value, "SHA-256")
    fun strToBytes(value: String): ByteArray = value.toByteArray()
    fun strToBytes(value: String, charset: String): ByteArray = value.toByteArray(Charsets.UTF_8.takeIf { charset.equals("UTF-8", true) } ?: java.nio.charset.Charset.forName(charset))
    fun bytesToStr(value: ByteArray): String = String(value)
    fun bytesToStr(value: ByteArray, charset: String): String = String(value, java.nio.charset.Charset.forName(charset))
    fun htmlFormat(value: String): String = org.jsoup.Jsoup.parseBodyFragment(value).body().html()
    fun ajaxAll(urls: Array<String>): Array<String> = urls.map(::ajax).toTypedArray()
    fun webView(html: String?, url: String?, js: String?): String? {
        val target = url?.takeIf { it.isNotBlank() } ?: error("webView url 不能为空")
        val loader = webViewLoader ?: error("WebView Loader 不可用")
        return kotlinx.coroutines.runBlocking { loader.load(target, js).html }
    }
    fun webView(url: String): String? = webView(null, url, null)

    private fun request(url: String, method: String, body: String?, headers: Any?): StudioJsResponse {
        val map = when (headers) {
            is Map<*, *> -> headers.entries.associate { it.key.toString() to it.value.toString() }
            is String -> runCatching { com.google.gson.JsonParser.parseString(headers).asJsonObject.entrySet().associate { it.key to it.value.asString } }.getOrDefault(emptyMap())
            else -> emptyMap()
        }
        val result = fetcher.fetch(HttpFetcher.FetchRequest(url, method, map, body))
        return StudioJsResponse(result.code, result.finalUrl, result.body, result.headers, result.elapsedMs)
    }
    private fun digest(value: String, algorithm: String): String = MessageDigest.getInstance(algorithm).digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @JvmOverloads
    fun createSymmetricCrypto(transformation: String, key: Any, iv: Any? = null): StudioSymmetricCrypto =
        StudioSymmetricCrypto(transformation, key, iv)
}

/** 官方阅读兼容的对称加解密对象：java.createSymmetricCrypto("AES/CBC/PKCS5Padding", key, iv) */
class StudioSymmetricCrypto(
    private val transformation: String,
    key: Any,
    iv: Any?,
) {
    private val keyBytes: ByteArray = key.toBytes()
    private val ivBytes: ByteArray? = iv?.toBytes()

    private fun Any.toBytes(): ByteArray = when (this) {
        is ByteArray -> this
        is String -> this.toByteArray()
        else -> this.toString().toByteArray()
    }

    private fun algorithm(): String = transformation.substringBefore('/').ifBlank { "AES" }

    private fun needsIv(): Boolean = transformation.uppercase().contains("/CBC/") ||
        transformation.uppercase().contains("/CFB/") ||
        transformation.uppercase().contains("/OFB/") ||
        transformation.uppercase().contains("/CTR/")

    private fun newCipher(mode: Int): javax.crypto.Cipher {
        val cipher = runCatching { javax.crypto.Cipher.getInstance(transformation) }
            .getOrElse { javax.crypto.Cipher.getInstance(transformation.replace(Regex("(?i)pkcs7padding"), "PKCS5Padding")) }
        val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, algorithm())
        if (needsIv()) {
            val vector = ivBytes ?: error("$transformation 需要 iv 参数")
            cipher.init(mode, keySpec, javax.crypto.spec.IvParameterSpec(vector))
        } else {
            cipher.init(mode, keySpec)
        }
        return cipher
    }

    fun decrypt(data: ByteArray): ByteArray = newCipher(javax.crypto.Cipher.DECRYPT_MODE).doFinal(data)

    /** 入参为 Base64 字符串，输出 UTF-8 字符串（官方 legado SymmetricCrypto.decryptStr 语义） */
    fun decryptStr(value: String): String = String(decrypt(Base64.getDecoder().decode(value)))

    fun decryptBase64Str(value: String): String = decryptStr(value)

    fun encrypt(data: ByteArray): ByteArray = newCipher(javax.crypto.Cipher.ENCRYPT_MODE).doFinal(data)

    fun encryptStr(value: String): ByteArray = encrypt(value.toByteArray())

    fun encryptBase64Str(value: String): String = Base64.getEncoder().encodeToString(encrypt(value.toByteArray()))
}

data class StudioJsResponse(
    private val status: Int,
    private val finalUrl: String,
    private val content: String,
    private val responseHeaders: Map<String, String>,
    private val duration: Long,
) {
    fun code(): Int = status
    /** 官方阅读兼容别名：部分旧书源写 r.statusCode() */
    fun statusCode(): Int = status
    fun url(): String = finalUrl
    fun body(): String = content
    fun headers(): Map<String, String> = responseHeaders
    fun callTime(): Long = duration
    fun raw(): StudioJsResponse = this
    fun request(): StudioJsResponse = this
    override fun toString(): String = content
}

/** 官方兼容 java.lang 子路径：沙箱仅放行 Thread.sleep，其余 java.lang.* 一律不可用 */
class StudioLangApi {
    @JvmField
    val Thread: ThreadApi = ThreadApi()

    class ThreadApi {
        fun sleep(ms: Long) {
            val wait = if (ms < 0) 0 else ms
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
