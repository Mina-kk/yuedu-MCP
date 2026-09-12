package com.mina.legadostudio.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mina.legadostudio.domain.BookSourceValidator
import com.mina.legadostudio.domain.ContentPagination
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpLogRecorder
import com.mina.legadostudio.verification.DomainModeStore
import com.mina.legadostudio.verification.WebViewPageLoader
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class EmbeddedLegadoRuntime(
    private val fetcher: HttpFetcher,
    private val validator: BookSourceValidator,
    private val engine: LegadoRuleEngine = LegadoRuleEngine(),
    private val rhino: RhinoEvaluator,
    private val webViewLoader: WebViewPageLoader? = null,
    private val domainModes: DomainModeStore? = null,
    private val httpLogs: HttpLogRecorder? = null,
) : LegadoRuntime {
    override suspend fun inspect(request: LegadoRuntime.InspectRequest) = withContext(Dispatchers.IO) {
        val response = fetchAbsolute(request.url, request.method, request.headers, request.body, request.charset)
        inspectSnapshot(request, response)
    }
    suspend fun inspectSnapshot(request: LegadoRuntime.InspectRequest, response: HttpFetcher.FetchResult) = withContext(Dispatchers.Default) {
        val output = request.rule.takeIf { it.isNotBlank() }?.let { extract(response.body, it, response.finalUrl) }
            ?.let { LegadoRuleEngine.Output(listOf(it), it, 1) }
        val elementRule = request.rule.takeIf { it.isNotBlank() && !hasJs(it) }
            ?.let { LegadoStringRule.elementRule(it) }.orEmpty()
        val elementAttempt = runCatching { elementRule.takeIf { it.isNotBlank() }
            ?.let { engine.elements(response.body, it, request.kind ?: LegadoRuleEngine.detect(it)).take(100) }.orEmpty() }
        LegadoRuntime.InspectReport(response.copy(body = response.body.take(300_000)), output,
            elementAttempt.getOrDefault(emptyList()), elementAttempt.exceptionOrNull()?.let { "元素预览不可用：${it.message}" })
    }

    override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport = debug(sourceJson, entry, null)

    /**
     * cacheFetch 按调用传入（每个 MCP 任务一份闭包，指向各自的任务上下文缓存），
     * 并行调试多个书源时互不干扰，也不再需要全局开关与跨任务互斥。
     */
    suspend fun debug(
        sourceJson: String,
        entry: String,
        cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?,
    ): LegadoRuntime.DebugReport = withContext(Dispatchers.IO) {
        val root = JsonParser.parseString(sourceJson).asJsonObject
        val state = hashMapOf<String, String>()
        when {
            entry.startsWith("--") -> debugContent(root, entry.removePrefix("--"), state, cacheFetch)
            entry.startsWith("++") -> debugToc(root, entry.removePrefix("++"), state, cacheFetch)
            entry.contains("::") -> {
                val parts = entry.split("::", limit = 2)
                debugList(root, root.getAsJsonObject("ruleExplore"), requestValue(parts[1], root, mapOf("page" to 1, "source" to state)), "发现", parts[1], state, cacheFetch)
            }
            entry.startsWith("http://") || entry.startsWith("https://") -> debugInfo(root, entry, state, cacheFetch)
            else -> {
                val search = root.text("searchUrl").orEmpty()
                require(search.isNotBlank()) { "书源没有 searchUrl" }
                val value = requestValue(search, root, mapOf("key" to entry, "page" to 1, "source" to state))
                debugList(root, root.getAsJsonObject("ruleSearch"), value, "搜索", entry, state, cacheFetch)
            }
        }
    }

    override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = withContext(Dispatchers.IO) {
        rhino.evaluate(js, baseUrl, previous)
    }

    override fun validate(sourceJson: String) = validator.validate(sourceJson)

    private suspend fun debugInfo(root: JsonObject, value: String, state: MutableMap<String, String>, cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?): LegadoRuntime.DebugReport {
        val response = fetchValue(value, root.text("bookSourceUrl").orEmpty(), sourceHeaders(root), cacheFetch)
        val rules = root.getAsJsonObject("ruleBookInfo") ?: JsonObject()
        val bindings = mapOf("source" to state, "url" to response.finalUrl, "isFromBookInfo" to true)
        val fields = linkedMapOf<String, String?>()
        listOf("name", "author", "kind", "wordCount", "lastChapter", "intro", "coverUrl", "tocUrl").forEach { field ->
            fields[field] = extract(response.body, rules.text(field), response.finalUrl, bindings)
                ?.let { resolveIfUrl(response.finalUrl, field, it) }
        }
        return LegadoRuntime.DebugReport("详情", value, listOf("HTTP ${response.code} ${response.finalUrl}", "页面 ${response.body.length} 字符") + fields.map { "${it.key}: ${it.value.orEmpty()}" }, fields)
    }

    private suspend fun debugToc(root: JsonObject, value: String, state: MutableMap<String, String>, cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?): LegadoRuntime.DebugReport {
        val rules = root.getAsJsonObject("ruleToc") ?: error("缺少 ruleToc")
        val listRule = rules.text("chapterList").orEmpty()
        val chapters = mutableListOf<Map<String, Any>>()
        val lines = mutableListOf<String>()
        val visited = hashSetOf<String>()
        var current: String? = value
        for (page in 0 until 100) {
            val target = current ?: break
            if (!visited.add(target)) break
            val response = fetchValue(target, root.text("bookSourceUrl").orEmpty(), sourceHeaders(root), cacheFetch)
            val pageBindings = mapOf("source" to state, "url" to response.finalUrl, "page" to page + 1)
            val elements = extractElements(response.body, listRule, response.finalUrl, pageBindings)
            elements.forEach { html ->
                val index = chapters.size
                val bindings = pageBindings + ("chapter" to mapOf("index" to index))
                val name = extract(html, rules.text("chapterName"), response.finalUrl, bindings).orEmpty()
                val chapterUrl = extract(html, rules.text("chapterUrl"), response.finalUrl, bindings).orEmpty()
                chapters += mapOf("index" to index, "name" to name, "url" to resolve(response.finalUrl, chapterUrl))
            }
            lines += "目录第${page + 1}页 HTTP ${response.code}，${elements.size} 条"
            current = rules.text("nextTocUrl")?.takeIf { it.isNotBlank() }
                ?.let { extract(response.body, it, response.finalUrl, pageBindings) }
                ?.takeIf { it.isNotBlank() }?.let { resolve(response.finalUrl, it) }
        }
        // 输出裁剪：章节数据只保留前 20 条（调试足够定位），全量以 totalChapters 标注
        return LegadoRuntime.DebugReport("目录", value, lines + "目录总数 ${chapters.size}" + chapters.take(3).map { "${it["name"]} -> ${it["url"]}" },
            mapOf("totalChapters" to chapters.size, "chapters" to chapters.take(20)))
    }

    private suspend fun debugContent(root: JsonObject, value: String, state: MutableMap<String, String>, cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?): LegadoRuntime.DebugReport {
        val rules = root.getAsJsonObject("ruleContent") ?: error("缺少 ruleContent")
        val contentRule = rules.text("content").orEmpty()
        val pages = mutableListOf<String>()
        val lines = mutableListOf<String>()
        var current: String? = value
        val visited = hashSetOf<String>()
        for (page in 0 until 10) {
            val target = current ?: break
            if (!visited.add(target)) break
            val response = fetchValue(target, root.text("bookSourceUrl").orEmpty(), sourceHeaders(root), cacheFetch)
            val bindings = mapOf("source" to state, "url" to response.finalUrl, "title" to "", "nextChapterUrl" to "")
            val rawContent = extract(response.body, contentRule, response.finalUrl, bindings, unescape = false).orEmpty()
            pages += rawContent
            lines += "第${page + 1}页 HTTP ${response.code}，正文 ${rawContent.length} 字符"
            val nextRule = rules.text("nextContentUrl")
            val nextUrl = nextRule?.takeIf { it.isNotBlank() }?.let { extract(response.body, it, response.finalUrl, bindings) }
                ?.takeIf { it.isNotBlank() }?.let { resolve(response.finalUrl, it) }
            current = if (nextUrl != null && ContentPagination.isNextChapter(target, nextUrl, nextRule.orEmpty())) {
                lines += "已忽略 nextContentUrl：指向下一章而不是下一页"
                null
            } else nextUrl
        }
        val merged = pages.joinToString("\n")
        val replaceRule = rules.text("replaceRegex")
        val cleaned = if (replaceRule.isNullOrBlank()) merged else extract(merged, replaceRule, value, mapOf("source" to state), unescape = true) ?: ""
        if (!replaceRule.isNullOrBlank()) {
            lines += "合并后 ${merged.length} 字符，全文 replaceRegex 后 ${cleaned.length} 字符（官方在合并后清洗，不是逐页）"
        } else {
            lines += "合并正文 ${merged.length} 字符"
        }
        // 输出裁剪：正文只保留前 4000 字符供核对，replaceRegex 前样本保留 800 字符；全量以 contentTotalChars/mergedTotalChars 标注
        return LegadoRuntime.DebugReport("正文", value, lines + "合并正文 ${cleaned.length} 字符", mapOf(
            "pages" to pages.size, "content" to cleaned.take(4000), "contentTotalChars" to cleaned.length,
            "mergedBeforeReplace" to merged.take(800), "mergedTotalChars" to merged.length,
        ))
    }

    private suspend fun debugList(root: JsonObject, rules: JsonObject?, requestValue: String, type: String, entry: String, state: MutableMap<String, String>, cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?): LegadoRuntime.DebugReport {
        requireNotNull(rules) { "缺少 ${if (type == "搜索") "ruleSearch" else "ruleExplore"}" }
        val response = fetchValue(requestValue, root.text("bookSourceUrl").orEmpty(), sourceHeaders(root), cacheFetch)
        val listRule = rules.text("bookList").orEmpty()
        val elements = extractElements(response.body, listRule, response.finalUrl, mapOf("source" to state, "url" to response.finalUrl))
        val books = elements.take(200).map { html ->
            val bindings = mapOf("source" to state, "url" to response.finalUrl)
            linkedMapOf(
                "name" to extract(html, rules.text("name"), response.finalUrl, bindings).orEmpty(),
                "author" to extract(html, rules.text("author"), response.finalUrl, bindings).orEmpty(),
                "bookUrl" to resolve(response.finalUrl, extract(html, rules.text("bookUrl"), response.finalUrl, bindings).orEmpty()),
                "coverUrl" to resolve(response.finalUrl, extract(html, rules.text("coverUrl"), response.finalUrl, bindings).orEmpty()),
                "intro" to extract(html, rules.text("intro"), response.finalUrl, bindings).orEmpty().take(120),
                "kind" to extract(html, rules.text("kind"), response.finalUrl, bindings).orEmpty(),
                "lastChapter" to extract(html, rules.text("lastChapter"), response.finalUrl, bindings).orEmpty(),
            )
        }
        // 输出裁剪：列表只内联前 10 条（总数放 lines），避免大响应挤爆上下文
        val trimmed = books.take(10)
        return LegadoRuntime.DebugReport(type, entry,
            listOf("HTTP ${response.code} ${response.finalUrl}", "列表 ${books.size} 条（内联前 ${trimmed.size} 条）") + trimmed.take(3).map { "${it["name"]} / ${it["author"]}" },
            trimmed)
    }

    private suspend fun fetchValue(
        value: String,
        base: String,
        sourceHeaders: Map<String, String> = emptyMap(),
        cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)? = null,
    ): HttpFetcher.FetchResult {
        val parsed = LegadoUrlOptions.parse(value)
        val absolute = resolve(base, parsed.url)
        var response = if (parsed.webView || domainModes?.requiresWebView(absolute) == true) {
            val loader = webViewLoader ?: error("当前测试环境没有 WebView Loader")
            val loaded = loader.load(absolute, parsed.webJs, parsed.webViewDelayTime)
            httpLogs?.record(HttpLogRecorder.Draft(method = "WEBVIEW", url = absolute, finalUrl = loaded.finalUrl, statusCode = 200, durationMs = loaded.elapsedMs, requestHeaders = sourceHeaders + parsed.headers, responseBody = loaded.html))
            if (fetcher.looksLikeVerification(403, loaded.finalUrl, loaded.html)) {
                throw com.mina.legadostudio.verification.VerificationRequiredException(
                    loaded.finalUrl, loaded.finalUrl.toHttpUrlOrNull()?.host.orEmpty(), viaWebView = true,
                    marker = fetcher.verificationMarker(403, loaded.finalUrl, loaded.html) ?: "webview", code = 200,
                )
            }
            HttpFetcher.FetchResult(200, loaded.finalUrl, emptyMap(), loaded.html, loaded.elapsedMs)
        } else {
            val cached = cacheFetch?.invoke(HttpFetcher.FetchRequest(absolute, parsed.method, sourceHeaders + parsed.headers, parsed.body, parsed.charset))
            cached ?: fetchAbsolute(absolute, parsed.method, sourceHeaders + parsed.headers, parsed.body, parsed.charset)
        }
        parsed.bodyJs?.takeIf { it.isNotBlank() }?.let { js ->
            val transformed = rhino.evaluate(js, response.finalUrl, response.body, mapOf("url" to response.finalUrl)).value
            if (transformed != null) response = response.copy(body = transformed)
        }
        return response
    }

    private fun fetchAbsolute(url: String, method: String, headers: Map<String, String>, body: String?, charset: String?): HttpFetcher.FetchResult =
        fetcher.fetch(HttpFetcher.FetchRequest(url, method, headers, body, charset))

    private fun requestValue(template: String, root: JsonObject, bindings: Map<String, Any?>): String {
        if (template.startsWith("@js:")) {
            return rhino.evaluate(template.removePrefix("@js:"), root.text("bookSourceUrl").orEmpty(), null, bindings).value.orEmpty()
        }
        var result = template
            .replace("{{key}}", java.net.URLEncoder.encode(bindings["key"]?.toString().orEmpty(), "UTF-8"))
            .replace("{{page}}", bindings["page"]?.toString() ?: "1")
        JS_TEMPLATE.findAll(result).toList().asReversed().forEach { match ->
            val value = rhino.evaluate(match.groupValues[1], root.text("bookSourceUrl").orEmpty(), null, bindings).value.orEmpty()
            result = result.replaceRange(match.range, value)
        }
        val parsed = LegadoUrlOptions.parse(result)
        val absolute = resolve(root.text("bookSourceUrl").orEmpty(), parsed.url)
        if (parsed.url == result) return absolute
        val optionText = result.substring(result.lastIndexOf(",{") + 1)
        return "$absolute,$optionText"
    }

    private fun extractElements(content: String, rule: String, baseUrl: String, bindings: Map<String, Any?>): List<String> {
        val parts = LegadoStringRule.split(rule)
        if (parts.isEmpty()) return emptyList()
        if (parts.size == 1 && parts[0].mode == LegadoStringRule.Mode.Default) {
            return if (parts[0].rule.isBlank()) emptyList() else engine.elements(content, parts[0].rule)
        }
        var current: Any? = content
        for (part in parts) {
            current = when (part.mode) {
                LegadoStringRule.Mode.Js -> rhino.evaluateRaw(part.rule, baseUrl, current, bindings).value
                LegadoStringRule.Mode.Default -> {
                    val html = when (current) {
                        is String -> current
                        else -> toElementHtml(current).joinToString("")
                    }
                    if (part.rule.isBlank()) current else engine.elements(html, part.rule)
                }
            }
        }
        return toElementHtml(current)
    }

    private fun toElementHtml(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is org.jsoup.nodes.Element -> listOf(value.outerHtml())
        is org.jsoup.select.Elements -> value.map { it.outerHtml() }
        is org.htmlunit.corejs.javascript.NativeArray -> (0 until value.length.toInt()).flatMap { index ->
            toElementHtml(com.script.rhino.RhinoScriptEngine.unwrapReturnValue(value.get(index, value)))
        }
        is Iterable<*> -> value.mapNotNull { item -> when (item) { is org.jsoup.nodes.Element -> item.outerHtml(); null -> null; else -> item.toString() } }
        is Array<*> -> value.mapNotNull { item -> when (item) { is org.jsoup.nodes.Element -> item.outerHtml(); null -> null; else -> item.toString() } }
        is String -> runCatching {
            val parsed = JsonParser.parseString(value)
            if (parsed.isJsonArray) parsed.asJsonArray.map { it.asString } else listOf(value)
        }.getOrElse { listOf(value) }
        else -> listOf(value.toString())
    }

    private fun extract(content: String, rule: String?, baseUrl: String, bindings: Map<String, Any?> = emptyMap(), unescape: Boolean = true): String? {
        if (rule.isNullOrBlank()) return null
        var result: String? = content
        for (part in LegadoStringRule.split(rule)) {
            if (result == null) continue
            if (part.rule.isNotBlank() || part.replaceRegex.isEmpty()) {
                result = when (part.mode) {
                    LegadoStringRule.Mode.Js -> rhino.evaluate(part.rule, baseUrl, result, bindings).value
                    LegadoStringRule.Mode.Default -> if (part.rule.isBlank()) result else engine.extract(result, part.rule).first
                }
            }
            if (result != null && part.replaceRegex.isNotEmpty()) {
                result = LegadoStringRule.replace(result, part)
            }
        }
        if (result == null) return null
        return if (unescape) LegadoStringRule.unescapeHtml(result) else result
    }

    private fun sourceHeaders(root: JsonObject): Map<String, String> {
        val value = root.get("header") ?: return emptyMap()
        val obj = when {
            value.isJsonObject -> value.asJsonObject
            value.isJsonPrimitive -> runCatching { JsonParser.parseString(value.asString).asJsonObject }.getOrNull()
            else -> null
        } ?: return emptyMap()
        return obj.entrySet().associate { it.key to it.value.asString }
    }

    private fun hasJs(rule: String) = rule.contains("<js>") || rule.startsWith("@js:") || rule.contains("{{")
    private fun resolve(base: String, value: String): String = if (value.isBlank()) "" else if (value.startsWith("http://") || value.startsWith("https://")) value else base.toHttpUrlOrNull()?.resolve(value)?.toString() ?: value
    private fun resolveIfUrl(base: String, field: String, value: String): String = if (field in setOf("coverUrl", "tocUrl")) resolve(base, value) else value
    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString

    companion object {
        // Android ICU treats unescaped `}` as a quantifier; both braces must be escaped.
        internal val JS_TEMPLATE = Regex("\\{\\{([^{}]+)\\}\\}")
    }
}
