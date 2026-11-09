/*
 * Copyright (C) 2018-2026 LegadoTeam / gedoor
 *
 * This file is part of yuedu-MCP and is a Kotlin/JVM adaptation of
 * LegadoTeam/legado (GPL-3.0) source. It is distributed under the terms of
 * the GNU General Public License v3.0 (see LICENSE).
 *
 * Upstream origin (fixed commit):
 *   repo:   https://github.com/LegadoTeam/legado
 *   commit: 330e606c4d57e08adc056ff8ab7a4132c784da9a
 *   file:   app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt
 *   lines:  initUrl 175-183, analyzeJs 188-211, replaceKeyPageJs 216-243,
 *           analyzeUrl 248-317, encodeParams/appendEncoded 329-389,
 *           paramPattern/pagePattern/queryEncoder 817-820
 *   file:   app/src/main/java/io/legado/app/constant/AppPattern.kt (JS_PATTERN)
 *   file:   app/src/main/java/io/legado/app/utils/EncoderUtils.kt (escape)
 *   file:   app/src/main/java/io/legado/app/data/entities/BaseSource.kt (getHeaderMap 197-222)
 *   file:   app/src/main/java/io/legado/app/model/SharedJsScope.kt (getScope / parseJsLibMap)
 *
 * Deviations are marked "ADAPT:" and documented in docs/research/runtime-implementation.md.
 */
package com.mina.legadostudio.runtime

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

/**
 * 请求模板与 URL 选项解析，按上游 `AnalyzeUrl` 的顺序执行：
 * `analyzeJs`（`@js:`/`<js>` 段与 `@result` 拼接）→ `replaceKeyPageJs`（先 `{{}}` 后 `<a,b,c>`）
 * → `analyzeUrl`（切 `,{` 前的 URL、绝对化、选项字段、`option.js`、query/form 编码）。
 *
 * 编码语义（上游 329-389）：
 * - charset 为空 → UTF-8，且**已编码**片段原样保留（不二次编码）；
 * - charset 为 `escape` → [EncoderUtils.escape] 的 `%uXXXX` 形式；
 * - GET query 整体已编码时不动；否则按 RFC3986 保留 `=&` 等分隔符编码（空格 `%20`，不是 `+`）。
 */
object ReaderRequestTemplate {

    /** 上游 AppPattern.JS_PATTERN：大小写不敏感，`<js></js>` 或 `@js:` 到串尾 */
    val JS_PATTERN: Pattern =
        Pattern.compile("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", Pattern.CASE_INSENSITIVE)

    /** 上游 AnalyzeUrl.paramPattern：允许逗号与 `{` 之间有空白 */
    private val PARAM_PATTERN = Pattern.compile("\\s*,\\s*(?=\\{)")

    /** 上游 AnalyzeUrl.pagePattern */
    private val PAGE_PATTERN = Pattern.compile("<(.*?)>")

    /** 上游 queryEncoder：RFC3986 UNRESERVED + 保留 `!$%&()*+,/:;=?@[]^`{|}` */
    private val QUERY_SAFE = "!$%&()*+,/:;=?@[]^`{|}~-._".toSet()

    // ------------------------------------------------------------------ step 1

    /**
     * 上游 `AnalyzeUrl.analyzeJs`（188-211）。
     *
     * 逐段扫描 `@js:` / `<js></js>`；JS 之前的字面量段把其中的 `@result` 换成上一段结果后拼上，
     * JS 段用当前结果求值并成为新的结果。没有 JS 段时原样返回。
     */
    fun analyzeJs(ruleUrl: String, evalJs: (String, Any?) -> Any?): String {
        var start = 0
        val matcher = JS_PATTERN.matcher(ruleUrl)
        var result: Any = ruleUrl
        var matched = false
        while (matcher.find()) {
            matched = true
            if (matcher.start() > start) {
                val literal = ruleUrl.substring(start, matcher.start()).trim()
                if (literal.isNotEmpty()) {
                    result = literal.replace("@result", result.toString())
                }
            }
            val script = matcher.group(2) ?: matcher.group(1) ?: ""
            result = evalJs(script, result)?.toString().orEmpty()
            start = matcher.end()
        }
        if (!matched) return ruleUrl
        if (ruleUrl.length > start) {
            val tail = ruleUrl.substring(start).trim()
            if (tail.isNotEmpty()) {
                result = tail.replace("@result", result.toString())
            }
        }
        return result.toString()
    }

    /** 上游 `BaseSource.extractInlineJs` 判定：整串恰好是一个 `@js:`/`<js></js>` 段。 */
    fun isPureJsRule(text: String?): Boolean {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return JS_PATTERN.matcher(trimmed).matches()
    }

    /** 取纯 JS 规则里的脚本体（上游 `group(1) ?: group(2)`） */
    fun inlineJsBody(text: String): String? {
        val matcher = JS_PATTERN.matcher(text.trim())
        if (!matcher.matches()) return null
        return (matcher.group(1) ?: matcher.group(2))?.trim()
    }

    // ------------------------------------------------------------------ step 2

    /**
     * 上游 `AnalyzeUrl.replaceKeyPageJs`（216-243）：**先**替换 `{{}}`，**再**替换 `<a,b,c>` 分页段。
     * 顺序不能反：`{{page < 3 ? 1 : 2}}` 里的 `<` 会被分页正则误当成段首。
     *
     * `{{}}` 用花括号平衡扫描（[RuleAnalyzerChomp]），JS 结果是整型 Double 时按 `%.0f` 输出。
     */
    fun replaceTemplates(template: String, page: Int?, evalJs: (String, Any?) -> Any?): String {
        var ruleUrl = template
        if (ruleUrl.contains("{{") && ruleUrl.contains("}}")) {
            ruleUrl = replaceBalancedTemplates(ruleUrl, evalJs)
        }
        if (page != null) {
            val matcher = PAGE_PATTERN.matcher(ruleUrl)
            while (matcher.find()) {
                val pages = matcher.group(1)?.split(",") ?: continue
                if (pages.isEmpty()) continue
                val replacement = if (page < pages.size) {
                    pages[(page - 1).coerceAtLeast(0)].trim()
                } else {
                    pages.last().trim()
                }
                ruleUrl = ruleUrl.replace(matcher.group(), replacement)
            }
        }
        return ruleUrl
    }

    /**
     * 花括号平衡扫描替换 `{{expr}}`（上游 `RuleAnalyzer.innerRule("{{","}}")`）。
     * 支持嵌套 `{}`、引号内的括号；不平衡的 `{{` 当普通文本跳过。
     */
    private fun replaceBalancedTemplates(text: String, evalJs: (String, Any?) -> Any?): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("{{", i)
            if (start < 0) {
                out.append(text, i, text.length)
                break
            }
            out.append(text, i, start)
            val end = findBalancedEnd(text, start + 2)
            if (end < 0) {
                // 不平衡：`{{` 只是普通文本，继续往后找
                out.append(text, start, start + 2)
                i = start + 2
                continue
            }
            val expression = text.substring(start + 2, end)
            out.append(formatJsResult(evalJs(expression, null)))
            i = end + 2
        }
        return out.toString()
    }

    /** 从 [from] 起找与 `{{` 配对的 `}}`，返回 `}}` 的首字符下标；不平衡返回 -1。 */
    private fun findBalancedEnd(text: String, from: Int): Int {
        var depth = 1
        var inSingle = false
        var inDouble = false
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '\'' && !inDouble) inSingle = !inSingle
            else if (c == '"' && !inSingle) inDouble = !inDouble
            if (!inSingle && !inDouble) {
                if (c == '{' && i + 1 < text.length && text[i + 1] == '{') {
                    depth++
                    i += 2
                    continue
                }
                if (c == '}' && i + 1 < text.length && text[i + 1] == '}') {
                    depth--
                    if (depth == 0) return i
                    i += 2
                    continue
                }
            }
            i++
        }
        return -1
    }

    /** 上游 `replaceKeyPageJs` 的 JS 结果格式化：整型 Double 输出 `%.0f`，其余 `toString()`。 */
    fun formatJsResult(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Double -> if (value % 1.0 == 0.0) String.format(Locale.ROOT, "%.0f", value) else value.toString()
        else -> value.toString()
    }

    // ------------------------------------------------------------------ step 3

    /** 上游 `analyzeUrl` 的切分结果：`urlNoOption` + 选项 JSON 原文 */
    data class SplitUrl(val urlPart: String, val optionText: String?)

    /**
     * 上游 `analyzeUrl` 248-259：`\s*,\s*(?=\{` 切出 URL 与选项。
     * 选项 JSON 解析失败时整串当 URL（与上游 `urlOption == null` 时行为一致）。
     */
    fun splitUrlOption(ruleUrl: String): SplitUrl {
        val matcher = PARAM_PATTERN.matcher(ruleUrl)
        if (!matcher.find()) return SplitUrl(ruleUrl, null)
        val urlPart = ruleUrl.substring(0, matcher.start())
        val optionText = ruleUrl.substring(matcher.end())
        val parsed = runCatching { JsonParser.parseString(optionText) }.getOrNull()
        if (parsed == null || !parsed.isJsonObject) return SplitUrl(ruleUrl, null)
        return SplitUrl(urlPart, optionText)
    }

    /**
     * 完整 URL 解析：`analyzeJs` → `replaceTemplates` → 切选项 → 绝对化 → `option.js` → 编码。
     *
     * @param sourceUrl 书源 `bookSourceUrl`（相对路径基准）
     * @param page 分页号（`<a,b,c>` 段与 `{{page}}`）
     */
    fun resolveUrl(
        template: String,
        sourceUrl: String,
        page: Int?,
        evalJs: (String, Any?) -> Any?,
    ): String {
        val afterJs = analyzeJs(template, evalJs)
        val afterTemplates = replaceTemplates(afterJs, page, evalJs)
        val split = splitUrlOption(afterTemplates)
        var absolute = absoluteUrl(sourceUrl, split.urlPart)
        val option = split.optionText?.let { parseOptionObject(it) }
        // 上游 292-296：option.js 在 URL 绝对化之后执行，result 是完整 url；
        // 其 JS 作用域里的 baseUrl 已归一为站点根（NetworkUtils.getBaseUrl(url)），
        // 由调用方在 evalJs 闭包里用 siteBaseUrl() 提供（见 ReaderRuntimeContext）。
        option?.get("js")?.takeIf { it.isJsonPrimitive }?.asString?.let { js ->
            evalJs(js, absolute)?.toString()?.takeIf { it.isNotBlank() }?.let { absolute = it }
        }
        return encodeRequestUrl(absolute, option?.let { UrlOption.from(it) } ?: UrlOption())
    }

    /** 解析选项 JSON 成 [UrlOption]（字段名兼容上游 UrlOption） */
    fun parseOption(optionText: String?): UrlOption =
        optionText?.let { parseOptionObject(it) }?.let { UrlOption.from(it) } ?: UrlOption()

    private fun parseOptionObject(optionText: String): JsonObject? {
        val parsed = runCatching { JsonParser.parseString(optionText) }.getOrNull() ?: return null
        return parsed.takeIf { it.isJsonObject }?.asJsonObject
    }

    /**
     * 上游 `NetworkUtils.getAbsoluteURL(baseUrl, url)`：绝对地址原样，`data:` 原样，
     * `javascript` 开头返回空串，其余按 baseUrl 解析；baseUrl 不是合法 URL 时原样返回。
     */
    fun absoluteUrl(baseUrl: String, url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        if (trimmed.startsWith("javascript")) return ""
        if (trimmed.startsWith("data:")) return trimmed
        val base = baseUrl.toHttpUrlOrNull() ?: return trimmed
        return base.resolve(trimmed)?.toString() ?: trimmed
    }

    /** 上游 `NetworkUtils.getBaseUrl(url)`：`scheme://host[:port]/`；非法 URL 返回空串。 */
    fun siteBaseUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return ""
        return "${parsed.scheme}://${parsed.host}${if (parsed.port != parsed.defaultPort) ":${parsed.port}" else ""}/"
    }

    // ------------------------------------------------------------------ 编码

    /**
     * 上游 `analyzeQuery` / `analyzeFields`（329-389）：只对 GET query 与表单 body 编码。
     *
     * - GET：整条 query 已编码（含 `%xx`）时原样保留；否则逐 `k=v` 用 [encodeComponent]。
     * - POST 表单（body 不含 JSON/XML 且没有 Content-Type）：同样逐段编码。
     * - `charset=escape`：`%uXXXX`；其它 charset：按 UTF-8 字节百分号编码。
     */
    fun encodeRequestUrl(url: String, option: UrlOption): String {
        val method = option.method.uppercase(Locale.ROOT)
        if (method != "GET" && method != "HEAD") return url
        val q = url.indexOf('?')
        if (q < 0) return url
        val head = url.substring(0, q)
        val query = url.substring(q + 1)
        if (query.isEmpty()) return url
        val charset = option.charset?.takeIf { it.isNotBlank() }
        if (charset == null && looksEncoded(query)) return url
        val encoded = encodeParams(query, charset, isQuery = true)
        return "$head?$encoded"
    }

    /** 上游 `encodedQuery`：query 里出现 `%xx` 即认为调用方已自行编码。 */
    fun looksEncoded(query: String): Boolean = PERCENT_ESCAPE.containsMatchIn(query)

    private val PERCENT_ESCAPE = Regex("%[0-9A-Fa-f]{2}")

    /**
     * 上游 `encodeParams`：按 `&`/`=` 切分，逐段编码；`charset` 为空时已编码片段原样保留。
     */
    fun encodeParams(params: String, charset: String?, isQuery: Boolean): String {
        val checkEncoded = charset.isNullOrEmpty()
        val escape = charset == "escape"
        val sb = StringBuilder(params.length + 16)
        var pos = 0
        val len = params.length
        while (pos <= len) {
            if (sb.isNotEmpty()) sb.append('&')
            var ampOffset = params.indexOf('&', pos)
            if (ampOffset == -1) ampOffset = len
            val eqOffset = params.indexOf('=', pos)
            val key: String
            val value: String?
            if (eqOffset == -1 || eqOffset > ampOffset) {
                key = params.substring(pos, ampOffset)
                value = null
            } else {
                key = params.substring(pos, eqOffset)
                value = params.substring(eqOffset + 1, ampOffset)
            }
            sb.append(encodeComponent(key, checkEncoded, escape))
            if (value != null) {
                sb.append('=')
                sb.append(encodeComponent(value, checkEncoded, escape))
            }
            pos = ampOffset + 1
        }
        return sb.toString()
    }

    /** 上游 `StringBuilder.appendEncoded`（`charset == null` 分支即 UTF-8 + 已编码保留）。 */
    private fun encodeComponent(value: String, checkEncoded: Boolean, escape: Boolean): String {
        if (checkEncoded && looksEncoded(value)) return value
        if (escape) return EncoderUtils.escape(value)
        return rfc3986Encode(value)
    }

    /**
     * 上游 `queryEncoder`（RFC3986.UNRESERVED.orNew(PercentCodec.of("!$%&()*+,/:;=?@[\\]^`{|}")))：
     * 保留 `=&` 等分隔符与子分隔符，空格编码为 `%20`（不是 `+`）。
     */
    fun rfc3986Encode(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = (byte.toInt() and 0xFF).toChar()
            if (ch in QUERY_SAFE && byte >= 0) {
                sb.append(ch)
            } else {
                sb.append('%').append(String.format(Locale.ROOT, "%02X", byte.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    /** 上游 EncoderUtils.escape：字母数字原样，其余 `%xx`/`%uXXXX`（小写十六进制）。 */
    object EncoderUtils {
        fun escape(src: String): String {
            val sb = StringBuilder(src.length + 16)
            for (ch in src) {
                val code = ch.code
                if (code in 48..57 || code in 65..90 || code in 97..122) {
                    sb.append(ch)
                    continue
                }
                val prefix = when {
                    code < 16 -> "%0"
                    code < 256 -> "%"
                    else -> "%u"
                }
                sb.append(prefix).append(code.toString(16))
            }
            return sb.toString()
        }
    }

    // ------------------------------------------------------------------ headers

    /**
     * 上游 `BaseSource.getHeaderMap`（197-222）：`header` 是纯 JS（[isPureJsRule]）时先求值，
     * 结果按 JSON 对象解析；否则直接当 JSON 解析。解析失败不抛（上游吞异常只记日志），
     * 通过 [onError] 留下诊断，绝不静默当成空 headers 而让作者以为配置生效。
     */
    fun resolveHeaders(
        rawHeader: String?,
        sourceUrl: String,
        evalJs: (String, Any?) -> Any?,
        onError: ((String) -> Unit)? = null,
    ): Map<String, String> {
        val raw = rawHeader?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val json = if (isPureJsRule(raw)) {
            val body = inlineJsBody(raw) ?: return emptyMap()
            // 上游 BaseSource.evalJS 的 baseUrl 是书源 URL（getKey()），不是页面 URL
            val evaluated = runCatching { evalJs(body, null) }
                .getOrElse { error ->
                    onError?.invoke("header JS 执行失败：${error.message.orEmpty().take(200)}")
                    return emptyMap()
                }
            when (val value = evaluated) {
                null -> return emptyMap()
                is String -> value
                else -> LegadoJson.toJsonString(value)
            }
        } else {
            raw
        }
        val parsed = runCatching { JsonParser.parseString(json) }.getOrNull()
        if (parsed == null || !parsed.isJsonObject) {
            onError?.invoke("header 不是合法 JSON 对象（${json.take(120)}），本次请求未使用该 header")
            return emptyMap()
        }
        return parsed.asJsonObject.entrySet().associate { (key, value) ->
            key to when {
                value.isJsonNull -> ""
                value.isJsonPrimitive -> value.asString
                else -> value.toString()
            }
        }
    }

    // ------------------------------------------------------------------ jsLib

    /** 上游 `SharedJsScope.parseJsLibMap` 的判定结果：内联片段 + 远程 URL 列表 */
    data class JsLibSpec(
        val inlineParts: List<String>,
        val remoteUrls: List<String>,
    )

    /**
     * 上游 `SharedJsScope.evaluateJsLib`：`jsLib` 是 JSON 对象时按值逐个处理，
     * 值是绝对 URL 的下载后执行，否则当内联 JS；不是 JSON 对象时整串当内联 JS。
     */
    fun parseJsLibSpec(jsLib: String): JsLibSpec {
        val text = jsLib.trim()
        if (text.isEmpty()) return JsLibSpec(emptyList(), emptyList())
        val asJson = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (asJson == null || !asJson.isJsonObject) return JsLibSpec(listOf(text), emptyList())
        val inline = ArrayList<String>()
        val remote = ArrayList<String>()
        for ((_, value) in asJson.asJsonObject.entrySet()) {
            if (!value.isJsonPrimitive) continue
            val part = value.asString
            if (part.isBlank()) continue
            if (isAbsoluteUrl(part)) remote += part else inline += part
        }
        return JsLibSpec(inline, remote)
    }

    fun isAbsoluteUrl(value: String): Boolean =
        value.startsWith("http://", true) || value.startsWith("https://", true)

    /** 远程 jsLib 装载结果：要 eval 进共享作用域的源码（内联在前、远程按声明顺序在后）+ 脱敏诊断行 */
    data class JsLibLoadResult(
        val inlineSources: List<String>,
        val diagnostics: List<String>,
    )

    /**
     * 装载 jsLib（上游 `SharedJsScope.getScope` + `evaluateJsLib`）。
     *
     * - 内联片段原样返回；
     * - 远程 URL 用 [fetcher] 下载，按**内容 sha256** 进 [cache]（上游按 URL md5 缓存文件，
     *   这里按内容哈希，源更新即自然失效）；
     * - [fetcher] 为 null（宿主未提供受限下载能力）时抛 [ReaderRuntimeEnvironmentException]，
     *   绝不静默跳过——跳过会让依赖该库的规则报 ReferenceError 并被误判成书源错误。
     *
     * 诊断行只含 URL 的 scheme+host+path 截断，不含 query（可能带 token）。
     */
    fun loadJsLib(
        raw: String,
        sourceUrl: String,
        fetcher: ((String) -> String)?,
        cache: ReaderRuntimeJsLibCache = ReaderRuntimeJsLibCache.shared,
        maxBytes: Int = MAX_JS_LIB_BYTES,
    ): JsLibLoadResult {
        val spec = parseJsLibSpec(raw)
        if (spec.remoteUrls.isEmpty()) return JsLibLoadResult(spec.inlineParts, emptyList())
        val loader = fetcher ?: throw ReaderRuntimeEnvironmentException(
            "书源 jsLib 声明了远程库（${spec.remoteUrls.joinToString { redactUrl(it) }}），" +
                "当前运行环境未启用受限 jsLib 下载，规则依赖的库函数不会存在；" +
                "这是验收环境能力限制，不是书源规则错误。请保留原写法并以阅读端结果为准。",
        ).withCapability("remote_js_lib")
        val sources = ArrayList<String>(spec.inlineParts)
        val diagnostics = ArrayList<String>()
        for (url in spec.remoteUrls) {
            val cached = cache.get(url)
            if (cached != null) {
                sources += cached
                diagnostics += "jsLib 远程库命中缓存：${redactUrl(url)}（内容 sha256 ${cache.hashOf(url)?.take(12)}）"
                continue
            }
            val body = try {
                loader(url)
            } catch (error: ReaderRuntimeEnvironmentException) {
                throw error
            } catch (error: Exception) {
                throw ReaderRuntimeEnvironmentException(
                    "jsLib 远程库下载失败：${redactUrl(url)} → ${error.message.orEmpty().take(200)}；" +
                        "该库函数不可用，依赖它的报错属环境问题，不是书源规则错误。",
                    error,
                ).withCapability("remote_js_lib")
            }
            if (body.isBlank()) {
                throw ReaderRuntimeEnvironmentException(
                    "jsLib 远程库内容为空：${redactUrl(url)}；不按空库继续执行，避免把后续 ReferenceError 误判成规则错误。",
                ).withCapability("remote_js_lib")
            }
            require(body.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                "jsLib 远程库超过 ${maxBytes / 1024}KB 上限：${redactUrl(url)}"
            }
            cache.put(url, body)
            sources += body
            diagnostics += "jsLib 远程库已加载：${redactUrl(url)}（${body.length} 字符，内容 sha256 ${cache.hashOf(url)?.take(12)}）"
        }
        return JsLibLoadResult(sources, diagnostics)
    }

    /** 脱敏：只留 scheme://host[:port]/path（截断），丢 query/fragment 与 userinfo。 */
    fun redactUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.take(80)
        val path = parsed.encodedPath.take(60)
        return "${parsed.scheme}://${parsed.host}${if (parsed.port != parsed.defaultPort) ":${parsed.port}" else ""}$path"
    }

    /** 远程 jsLib 单文件上限（与 HttpFetcher 文本上限同量级，防大文件撑爆内存） */
    const val MAX_JS_LIB_BYTES = 2_000_000

    // ------------------------------------------------------------- 其它小工具

    /** 上游 `NetworkUtils.encodedForm` 同义：出现 `%xx` 即视为已编码 */
    fun looksFormEncoded(value: String): Boolean = looksEncoded(value)

    /** 把 `application/x-www-form-urlencoded` 的 body 按上游 encodeParams 编码 */
    fun encodeFormBody(body: String, charset: String?): String = encodeParams(body, charset, isQuery = false)

    /** 解码 query 片段（java.toURL 的 searchParams 用） */
    fun decodeQueryValue(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /** 上游 `URLEncoder.encode(value, charset)` 的 `+`→`%20` 变体（java.encodeURI） */
    fun encodeUriComponent(value: String, charset: String = "UTF-8"): String =
        URLEncoder.encode(value, charset).replace("+", "%20")
}

/** 上游 `UrlOption` 的沙箱子集（只保留运行时会用到的字段） */
data class UrlOption(
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val charset: String? = null,
    val bodyJs: String? = null,
    val webView: Boolean = false,
    val webJs: String? = null,
    val webViewDelayTime: Long = 0,
    val js: String? = null,
    val type: String? = null,
    val retry: Int = 0,
    val timeoutMs: Long? = null,
    val followRedirects: Boolean? = null,
    val raw: JsonObject? = null,
) {
    companion object {
        fun from(obj: JsonObject): UrlOption {
            fun str(key: String): String? = obj.get(key)?.takeUnless { it.isJsonNull }?.let {
                if (it.isJsonPrimitive) it.asString else it.toString()
            }
            val headers = when (val h = obj.get("header") ?: obj.get("headers")) {
                null -> emptyMap()
                else -> parseHeaders(h)
            }
            return UrlOption(
                method = str("method") ?: "GET",
                headers = headers,
                body = obj.get("body")?.takeUnless { it.isJsonNull }?.let {
                    if (it.isJsonPrimitive) it.asString else it.toString()
                },
                charset = str("charset"),
                bodyJs = str("bodyJs"),
                webView = obj.get("webView")?.takeUnless { it.isJsonNull }?.let {
                    when {
                        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean -> it.asBoolean
                        it.isJsonPrimitive -> it.asString.isNotEmpty() && !it.asString.equals("false", true)
                        else -> true
                    }
                } ?: false,
                webJs = str("webJs"),
                webViewDelayTime = obj.get("webViewDelayTime")?.takeUnless { it.isJsonNull }?.let {
                    runCatching { it.asLong }.getOrDefault(0L)
                } ?: 0L,
                js = str("js"),
                type = str("type"),
                retry = obj.get("retry")?.takeUnless { it.isJsonNull }?.let {
                    runCatching { it.asInt }.getOrNull() ?: runCatching { it.asString.toInt() }.getOrNull()
                } ?: 0,
                timeoutMs = obj.get("timeout")?.takeUnless { it.isJsonNull }?.let {
                    runCatching { it.asLong }.getOrNull() ?: runCatching { it.asString.toLong() }.getOrNull()
                },
                followRedirects = obj.get("followRedirects")?.takeUnless { it.isJsonNull }?.let {
                    runCatching { it.asBoolean }.getOrNull()
                },
                raw = obj,
            )
        }

        /** 上游 UrlOption.headerMap：值是 JSON 字符串时再解析一层 */
        private fun parseHeaders(element: JsonElement): Map<String, String> {
            val obj = when {
                element.isJsonObject -> element.asJsonObject
                element.isJsonPrimitive -> runCatching {
                    JsonParser.parseString(element.asString).asJsonObject
                }.getOrNull()
                else -> null
            } ?: return emptyMap()
            return obj.entrySet().associate { (key, value) ->
                key to when {
                    value.isJsonNull -> ""
                    value.isJsonPrimitive -> value.asString
                    else -> value.toString()
                }
            }
        }
    }
}

/** 远程 jsLib 内容缓存：按源 URL 记内容 sha256，命中直接复用源码 */
class ReaderRuntimeJsLibCache(private val maxEntries: Int = 16) {
    private data class Entry(val content: String, val sha256: String)
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(url: String): String? = entries[url]?.content

    @Synchronized
    fun hashOf(url: String): String? = entries[url]?.sha256

    @Synchronized
    fun put(url: String, content: String) {
        entries[url] = Entry(content, sha256(content))
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        /** 进程级共享缓存：同一书源跨阶段/跨次 debug 不重复下载（上游 ACache 同义） */
        val shared = ReaderRuntimeJsLibCache()

        fun sha256(text: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** Gson 小工具：把任意 JS 结果转成 JSON 字符串（header JS 返回值用） */
internal object LegadoJson {
    private val gson = com.google.gson.Gson()

    fun toJsonString(value: Any): String = when (value) {
        is String -> value
        is Map<*, *>, is Collection<*>, is Array<*> -> gson.toJson(value)
        else -> gson.toJson(value)
    }
}
