package com.mina.legadostudio.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mina.legadostudio.domain.BookSourceValidator
import com.mina.legadostudio.domain.ContentPagination
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpLogRecorder
import com.mina.legadostudio.network.HttpOrigin
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
        val origin = HttpOrigin(sourceAnchor = request.sourceAnchor, contextId = request.contextId, originKind = "inspect_rule")
        val response = fetchAbsolute(request.url, request.method, request.headers, request.body, request.charset, origin)
        inspectSnapshot(request, response)
    }
    suspend fun inspectSnapshot(request: LegadoRuntime.InspectRequest, response: HttpFetcher.FetchResult) = withContext(Dispatchers.Default) {
        val origin = HttpOrigin(sourceAnchor = request.sourceAnchor, contextId = request.contextId, originKind = "inspect_rule")
        val output = request.rule.takeIf { it.isNotBlank() }?.let { extract(response.body, it, response.finalUrl, origin = origin) }
            ?.let { LegadoRuleEngine.Output(listOf(it), it, 1) }
        val elementRule = request.rule.takeIf { it.isNotBlank() && !hasJs(it) }
            ?.let { LegadoStringRule.elementRule(it) }.orEmpty()
        val elementAttempt = runCatching { elementRule.takeIf { it.isNotBlank() }
            ?.let { engine.elements(response.body, it, request.kind ?: LegadoRuleEngine.detect(it)).take(100) }.orEmpty() }
        val pseudoSelectorWarning = pseudoSelectorWarning(request.rule)
        val warning = listOfNotNull(
            elementAttempt.exceptionOrNull()?.let { "元素预览不可用：${it.message}" },
            pseudoSelectorWarning,
        ).takeIf { it.isNotEmpty() }?.joinToString("；")
        LegadoRuntime.InspectReport(response.copy(body = response.body.take(300_000)), output,
            elementAttempt.getOrDefault(emptyList()), warning)
    }

    override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport = debug(sourceJson, entry, null)

    /**
     * cacheFetch 按调用传入（每个 MCP 任务一份闭包，指向各自的任务上下文缓存），
     * 并行调试多个书源时互不干扰，也不再需要全局开关与跨任务互斥。
     */
    override suspend fun debug(
        sourceJson: String,
        entry: String,
        cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?,
    ): LegadoRuntime.DebugReport = debug(sourceJson, entry, cacheFetch, null)

    override suspend fun debug(
        sourceJson: String,
        entry: String,
        cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?,
        origin: HttpOrigin?,
    ): LegadoRuntime.DebugReport = withContext(Dispatchers.IO) {
        val root = JsonParser.parseString(sourceJson).asJsonObject
        val state = hashMapOf<String, String>()
        // 官方 jsLib：纯 JS 片段才装载进 Rhino 作用域；http(s) URL 形态是外部库引用，
        // 沙箱不做网络加载（安全与可复现性），记一条诊断行让书源作者知情。
        val rawJsLib = root.text("jsLib")?.takeIf { it.isNotBlank() }
        var jsLibNote: String? = null
        val jsLib: String? = when {
            rawJsLib == null -> null
            rawJsLib.trim().startsWith("http://") || rawJsLib.trim().startsWith("https://") -> {
                jsLibNote = "jsLib 为外部 URL（${rawJsLib.trim().take(120)}），沙箱不加载网络库，已按内联 JS 之外的声明跳过；规则中依赖的函数请内联或改写"
                null
            }
            else -> rawJsLib
        }
        // 书源级 enabledCookieJar（官方默认 true；本字段为 false 时该源全部请求不走自动 Cookie jar——
        // 不回带也不吸收 Set-Cookie；用户显式 Cookie 头/验证中心注入不受此开关影响）。
        // 经 bindings 透传给 RhinoEvaluator，JS 内 java.ajax/connect/get/post 的二次请求同口径隔离。
        val sourceCookieJar = root.get("enabledCookieJar")?.takeUnless { it.isJsonNull }?.asBoolean ?: true
        // 官方内置变量 book：一次 debug 里各阶段共享同一实例——
        // origin/originName/type 取书源头字段；detail/list 阶段抽取的字段写回（book.name 等）；
        // book.putVariable 的自定义变量池与 source 变量一样跨规则求值共享。
        val book = BookJsApi().apply {
            this.origin = root.text("bookSourceUrl").orEmpty()
            this.originName = root.text("bookSourceName").orEmpty()
            this.type = root.get("bookSourceType")?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrDefault(0) } ?: 0
        }
        // 观测 cache + JS 日志汇出：整个 debug 生命周期共享一份，
        // 报告里的 jsLogs / cacheSnapshot / recentCachePuts 从这里取（见 enrichReport）。
        val cacheProbe = SnapshottingCache()
        val jsLogLines = mutableListOf<String>()
        val bindings = mutableMapOf<String, Any?>(
            RhinoEvaluator.BINDING_USE_COOKIE_JAR to sourceCookieJar,
            RhinoEvaluator.BINDING_SOURCE_ANCHOR to root.text("bookSourceUrl").orEmpty(),
            "cache" to cacheProbe,
            RhinoEvaluator.BINDING_JS_LOG to { line: String ->
                while (jsLogLines.size >= 200) jsLogLines.removeAt(0)
                jsLogLines += line.take(500)
            },
        )
        if (jsLib != null) bindings[RhinoEvaluator.BINDING_JSLIB] = { jsLib }
        val report = when {
            entry.startsWith("--") -> debugContent(root, entry.removePrefix("--"), state, cacheFetch, origin, bindings, book)
            entry.startsWith("++") -> debugToc(root, entry.removePrefix("++"), state, cacheFetch, origin, bindings, book)
            entry.contains("::") -> {
                val parts = entry.split("::", limit = 2)
                debugList(root, root.getAsJsonObject("ruleExplore"), requestValue(parts[1], root, mapOf("page" to 1, "source" to state, "book" to book) + bindings, origin), "发现", parts[1], state, cacheFetch, origin, bindings, book, jsLibNote)
            }
            entry.startsWith("http://") || entry.startsWith("https://") -> debugInfo(root, entry, state, cacheFetch, origin, bindings, book, jsLibNote)
            else -> {
                val search = root.text("searchUrl").orEmpty()
                require(search.isNotBlank()) { "书源没有 searchUrl" }
                val value = requestValue(search, root, mapOf("key" to entry, "page" to 1, "source" to state, "book" to book) + bindings, origin)
                debugList(root, root.getAsJsonObject("ruleSearch"), value, "搜索", entry, state, cacheFetch, origin, bindings, book, jsLibNote)
            }
        }
        return@withContext enrichReport(report, jsLogLines, cacheProbe)
    }

    /** 把 jsLogs/cacheSnapshot/recentCachePuts 附到报告上（null 字段 = 无记录，JSON 不输出旧字段之外的噪音）。 */
    private fun enrichReport(report: LegadoRuntime.DebugReport, jsLogLines: List<String>, probe: SnapshottingCache): LegadoRuntime.DebugReport {
        val puts = probe.recentPuts()
        return report.copy(
            jsLogs = jsLogLines.takeIf { it.isNotEmpty() }?.toList(),
            cacheSnapshot = probe.memorySnapshot().takeIf { it.isNotEmpty() },
            recentCachePuts = puts.takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = withContext(Dispatchers.IO) {
        rhino.evaluate(js, baseUrl, previous)
    }

    override suspend fun evaluate(js: String, baseUrl: String, previous: Any?, origin: HttpOrigin?) = withContext(Dispatchers.IO) {
        rhino.evaluate(js, baseUrl, previous, origin = origin)
    }

    override fun validate(sourceJson: String) = validator.validate(sourceJson)

    private suspend fun debugInfo(root: JsonObject, value: String, state: MutableMap<String, String>, cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?, origin: HttpOrigin?, jsLibBindings: Map<String, Any?> = emptyMap(), book: BookJsApi = BookJsApi(), jsLibNote: String? = null): LegadoRuntime.DebugReport {
        val jsNotes = mutableListOf<String>()
        val response = fetchValue(value, root.text("bookSourceUrl").orEmpty(), sourceHeaders(root), cacheFetch, origin, jsLibBindings, cookieJar(jsLibBindings), root.text("loginCheckJs"), jsNotes::add)
        val rules = root.getAsJsonObject("ruleBookInfo") ?: JsonObject()
        val fieldErrors = mutableListOf<String>()
        // 官方详情阶段：book.bookUrl 指向详情页地址；逐字段抽取后写回 book，
        // 后续字段的 JS 规则可以直接用 book.name/book.coverUrl 等已抽出的值。
        book.bookUrl = response.finalUrl
        book.origin = root.text("bookSourceUrl").orEmpty().ifBlank { book.origin }
        book.originName = root.text("bookSourceName").orEmpty().ifBlank { book.originName }
        val bindings = mapOf("source" to state, "book" to book, "url" to response.finalUrl, "isFromBookInfo" to true, "fromBookInfo" to true) + jsLibBindings
        val fields = linkedMapOf<String, String?>()
        listOf("name", "author", "kind", "wordCount", "lastChapter", "intro", "coverUrl", "tocUrl").forEach { field ->
            fields[field] = extract(response.body, rules.text(field), response.finalUrl, bindings, origin = origin, field = "ruleBookInfo.$field", fieldErrors = fieldErrors)
                ?.let { resolveIfUrl(response.finalUrl, field, it) }
            when (field) {
                "name" -> book.name = fields[field].orEmpty()
                "author" -> book.author = fields[field].orEmpty()
                "kind" -> book.kind = fields[field].orEmpty()
                "intro" -> book.intro = fields[field].orEmpty()
                "coverUrl" -> book.coverUrl = fields[field].orEmpty()
                "tocUrl" -> book.tocUrl = fields[field].orEmpty()
                "lastChapter" -> book.latestChapterTitle = fields[field].orEmpty()
                else -> {}
            }
        }
        return LegadoRuntime.DebugReport("详情", value, listOf("HTTP ${response.code} ${response.finalUrl}", "页面 ${response.body.length} 字符") + listOfNotNull(jsLibNote) + jsNotes + fieldErrors + fields.map { "${it.key}: ${it.value.orEmpty()}" }, fields, fieldErrors = fieldErrors.takeIf { it.isNotEmpty() })
    }

    private suspend fun debugToc(root: JsonObject, value: String, state: MutableMap<String, String>, cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?, origin: HttpOrigin?, jsLibBindings: Map<String, Any?> = emptyMap(), book: BookJsApi = BookJsApi(), jsLibNote: String? = null): LegadoRuntime.DebugReport {
        val rules = root.getAsJsonObject("ruleToc") ?: error("缺少 ruleToc")
        val fieldErrors = mutableListOf<String>()
        val listRule = rules.text("chapterList").orEmpty()
        val chapters = mutableListOf<Map<String, Any>>()
        val lines = mutableListOf<String>()
        val jsNotes = mutableListOf<String>()
        val visited = hashSetOf<String>()
        var current: String? = value
        for (page in 0 until 100) {
            val target = current ?: break
            if (!visited.add(target)) break
            val response = fetchValue(target, root.text("bookSourceUrl").orEmpty(), sourceHeaders(root), cacheFetch, origin, jsLibBindings, cookieJar(jsLibBindings), root.text("loginCheckJs"), jsNotes::add)
            // 官方目录阶段：book.tocUrl 指向当前目录页地址（首个目录页即 tocUrl）
            if (page == 0) book.tocUrl = response.finalUrl
            val pageBindings = mapOf("source" to state, "book" to book, "url" to response.finalUrl, "page" to page + 1) + jsLibBindings
            val elements = extractElements(response.body, listRule, response.finalUrl, pageBindings, origin)
            elements.forEach { html ->
                val index = chapters.size
                // 官方 chapter 对象：url/title 由 chapterUrl/chapterName 规则填充，index/bookUrl/baseUrl 由调试流程给
                val chapter = ChapterJsApi().apply {
                    this.index = index
                    this.bookUrl = book.bookUrl
                    this.baseUrl = response.finalUrl
                }
                val bindings = pageBindings + ("chapter" to chapter)
                val name = extract(html, rules.text("chapterName"), response.finalUrl, bindings, origin = origin, field = "ruleToc.chapterName", fieldErrors = fieldErrors).orEmpty()
                val chapterUrl = extract(html, rules.text("chapterUrl"), response.finalUrl, bindings, origin = origin, field = "ruleToc.chapterUrl", fieldErrors = fieldErrors).orEmpty()
                chapter.title = name
                chapter.url = resolve(response.finalUrl, chapterUrl)
                chapters += mapOf("index" to index, "name" to name, "url" to chapter.url)
            }
            lines += "目录第${page + 1}页 HTTP ${response.code}，${elements.size} 条"
            current = rules.text("nextTocUrl")?.takeIf { it.isNotBlank() }
                ?.let { extract(response.body, it, response.finalUrl, pageBindings, origin = origin) }
                ?.takeIf { it.isNotBlank() }?.let { resolve(response.finalUrl, it) }
        }
        // 输出裁剪：章节数据只保留前 20 条（调试足够定位），全量以 totalChapters 标注
        return LegadoRuntime.DebugReport("目录", value, lines + "目录总数 ${chapters.size}" + jsNotes + fieldErrors + chapters.take(3).map { "${it["name"]} -> ${it["url"]}" },
            mapOf("totalChapters" to chapters.size, "chapters" to chapters.take(20)), fieldErrors = fieldErrors.takeIf { it.isNotEmpty() })
    }

    private suspend fun debugContent(root: JsonObject, value: String, state: MutableMap<String, String>, cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?, origin: HttpOrigin?, jsLibBindings: Map<String, Any?> = emptyMap(), book: BookJsApi = BookJsApi(), jsLibNote: String? = null): LegadoRuntime.DebugReport {
        val rules = root.getAsJsonObject("ruleContent") ?: error("缺少 ruleContent")
        val fieldErrors = mutableListOf<String>()
        val contentRule = rules.text("content").orEmpty()
        val pages = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val jsNotes = mutableListOf<String>()
        var current: String? = value
        val visited = hashSetOf<String>()
        var endReason = "complete"
        val pageDiags = mutableListOf<Map<String, Any?>>()
        // 官方 chapter 对象：--章节目录入口时 chapter.url 即目标章节地址；bookUrl 共享本次 debug 的 book
        val chapter = ChapterJsApi().apply {
            url = value
            bookUrl = book.bookUrl
            baseUrl = value
        }
        for (page in 0 until 10) {
            val target = current ?: break
            if (!visited.add(target)) { endReason = "cycle"; break }
            val response = fetchValue(target, root.text("bookSourceUrl").orEmpty(), sourceHeaders(root), cacheFetch, origin, jsLibBindings, cookieJar(jsLibBindings), root.text("loginCheckJs"), jsNotes::add) { rawCode, reportedCode, transport, cacheUsed ->
                pageDiags += mapOf("page" to page + 1, "httpStatus" to rawCode, "reportedStatus" to reportedCode, "transport" to transport, "cacheUsed" to cacheUsed)
            }
            val bindings = mapOf("source" to state, "book" to book, "chapter" to chapter, "url" to response.finalUrl, "title" to "", "nextChapterUrl" to "") + jsLibBindings
            val rawContent = extract(response.body, contentRule, response.finalUrl, bindings, unescape = false, origin = origin, field = "ruleContent.content", fieldErrors = fieldErrors).orEmpty()
            pages += rawContent
            lines += "第${page + 1}页 HTTP ${response.code}，正文 ${rawContent.length} 字符"
            val nextRule = rules.text("nextContentUrl")
            val nextUrl = nextRule?.takeIf { it.isNotBlank() }?.let { extract(response.body, it, response.finalUrl, bindings, origin = origin) }
                ?.takeIf { it.isNotBlank() }?.let { resolve(response.finalUrl, it) }
            current = if (nextUrl != null && ContentPagination.isNextChapter(target, nextUrl, nextRule.orEmpty())) {
                lines += "已忽略 nextContentUrl：指向下一章而不是下一页"
                endReason = "next_chapter"
                null
            } else nextUrl
        }
        if (pages.size == 10 && current != null) endReason = "page_limit"
        val merged = pages.joinToString("\n")
        val replaceRule = rules.text("replaceRegex")
        val cleaned = if (replaceRule.isNullOrBlank()) merged else extract(merged, replaceRule, value, mapOf("source" to state, "book" to book, "chapter" to chapter) + jsLibBindings, unescape = true, origin = origin) ?: ""
        if (!replaceRule.isNullOrBlank()) {
            lines += "合并后 ${merged.length} 字符，全文 replaceRegex 后 ${cleaned.length} 字符（官方在合并后清洗，不是逐页）"
        } else {
            lines += "合并正文 ${merged.length} 字符"
        }
        // 正文一眼可核对的一帧：字数 + 前 50 字预览（报告 contentLength/contentPreview 同值给 MCP 端读）
        val preview = cleaned.take(50)
        lines += "正文字数: ${cleaned.length}，预览: ${preview.replace("\n", "\\n")}${if (cleaned.length > 50) "…" else ""}"
        // 输出裁剪：正文只保留前 4000 字符供核对，replaceRegex 前样本保留 800 字符；全量以 contentTotalChars/mergedTotalChars 标注
        return LegadoRuntime.DebugReport("正文", value, listOfNotNull(jsLibNote) + lines + "合并正文 ${cleaned.length} 字符" + jsNotes + fieldErrors, mapOf(
            "pages" to pages.size, "content" to cleaned.take(4000), "contentTotalChars" to cleaned.length,
            "mergedBeforeReplace" to merged.take(800), "mergedTotalChars" to merged.length,
            "contentDiagnostics" to mapOf("endReason" to endReason, "pages" to pageDiags.toList()),
        ), contentLength = cleaned.length, contentPreview = preview, fieldErrors = fieldErrors.takeIf { it.isNotEmpty() })
    }

    private suspend fun debugList(root: JsonObject, rules: JsonObject?, requestValue: String, type: String, entry: String, state: MutableMap<String, String>, cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?, origin: HttpOrigin?, jsLibBindings: Map<String, Any?> = emptyMap(), book: BookJsApi = BookJsApi(), jsLibNote: String? = null): LegadoRuntime.DebugReport {
        requireNotNull(rules) { "缺少 ${if (type == "搜索") "ruleSearch" else "ruleExplore"}" }
        val ruleKey = if (type == "搜索") "ruleSearch" else "ruleExplore"
        val fieldErrors = mutableListOf<String>()
        val jsNotes = mutableListOf<String>()
        val response = fetchValue(requestValue, root.text("bookSourceUrl").orEmpty(), sourceHeaders(root), cacheFetch, origin, jsLibBindings, cookieJar(jsLibBindings), root.text("loginCheckJs"), jsNotes::add)
        val listRule = rules.text("bookList").orEmpty()
        val elements = extractElements(response.body, listRule, response.finalUrl, mapOf("source" to state, "book" to book, "url" to response.finalUrl) + jsLibBindings, origin)
        val books = elements.take(200).map { html ->
            // 官方搜索/列表阶段：每条结果是独立 book（setRuleData 把当前条目挂到规则上下文），
            // 逐字段抽取后写回该条目 book，后续字段规则可见已抽出的 name 等值
            val itemBook = BookJsApi().apply {
                this.origin = book.origin
                this.originName = book.originName
                this.type = book.type
            }
            val bindings = mapOf("source" to state, "book" to itemBook, "url" to response.finalUrl) + jsLibBindings
            linkedMapOf(
                "name" to extract(html, rules.text("name"), response.finalUrl, bindings, origin = origin, field = "$ruleKey.name", fieldErrors = fieldErrors).orEmpty().also { itemBook.name = it },
                "author" to extract(html, rules.text("author"), response.finalUrl, bindings, origin = origin, field = "$ruleKey.author", fieldErrors = fieldErrors).orEmpty().also { itemBook.author = it },
                "bookUrl" to resolve(response.finalUrl, extract(html, rules.text("bookUrl"), response.finalUrl, bindings, origin = origin, field = "$ruleKey.bookUrl", fieldErrors = fieldErrors).orEmpty()).also { itemBook.bookUrl = it },
                "coverUrl" to resolve(response.finalUrl, extract(html, rules.text("coverUrl"), response.finalUrl, bindings, origin = origin, field = "$ruleKey.coverUrl", fieldErrors = fieldErrors).orEmpty()).also { itemBook.coverUrl = it },
                "intro" to extract(html, rules.text("intro"), response.finalUrl, bindings, origin = origin, field = "$ruleKey.intro", fieldErrors = fieldErrors).orEmpty().take(120).also { itemBook.intro = it },
                "kind" to extract(html, rules.text("kind"), response.finalUrl, bindings, origin = origin, field = "$ruleKey.kind", fieldErrors = fieldErrors).orEmpty().also { itemBook.kind = it },
                "lastChapter" to extract(html, rules.text("lastChapter"), response.finalUrl, bindings, origin = origin, field = "$ruleKey.lastChapter", fieldErrors = fieldErrors).orEmpty().also { itemBook.latestChapterTitle = it },
            )
        }
        // 输出裁剪：列表只内联前 10 条（总数放 lines），避免大响应挤爆上下文
        val trimmed = books.take(10)
        return LegadoRuntime.DebugReport(type, entry,
            listOf("HTTP ${response.code} ${response.finalUrl}", "列表 ${books.size} 条（内联前 ${trimmed.size} 条）") + listOfNotNull(jsLibNote) + jsNotes + fieldErrors + trimmed.take(3).map { "${it["name"]} / ${it["author"]}" },
            trimmed, fieldErrors = fieldErrors.takeIf { it.isNotEmpty() })
    }

    private suspend fun fetchValue(
        value: String,
        base: String,
        sourceHeaders: Map<String, String> = emptyMap(),
        cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)? = null,
        origin: HttpOrigin? = null,
        jsLibBindings: Map<String, Any?> = emptyMap(),
        useCookieJar: Boolean = true,
        /** 书源级 loginCheckJs：与真机 AnalyzeUrl 一致，每个响应拿到后、bodyJs 之前执行。纯 JS 不带 @js: 前缀。 */
        loginCheckJs: String? = null,
        /** JS 内日志（java.log/toast/桩提示）汇出处；null = 丢弃。 */
        jsLog: ((String) -> Unit)? = null,
        observer: ((rawCode: Int?, reportedCode: Int?, transport: String, cacheUsed: Boolean?) -> Unit)? = null,
    ): HttpFetcher.FetchResult {
        val parsed = LegadoUrlOptions.parse(value)
        val absolute = resolve(base, parsed.url)
        // 规则内逐事务来源：锚点优先取书源自身 bookSourceUrl，其次显式 origin
        val requestOrigin = (origin ?: HttpOrigin())
            .let { o -> o.copy(sourceAnchor = o.sourceAnchor ?: base.takeIf { it.isNotBlank() }) }
        var response = if (parsed.webView || domainModes?.requiresWebView(absolute) == true) {
            val loader = webViewLoader ?: error("当前测试环境没有 WebView Loader")
            val loaded = loader.load(absolute, parsed.webJs, parsed.webViewDelayTime) { m, reqUrl, headers ->
                // WebView 子资源请求（shouldInterceptRequest 观测，statusCode=0）；异步写不阻塞拦截线程
                httpLogs?.recordAsync(HttpLogRecorder.Draft(method = m, url = reqUrl, statusCode = 0, requestHeaders = headers,
                    sourceAnchor = requestOrigin.sourceAnchor, contextId = requestOrigin.contextId, originKind = "webview_resource"))
            }
            observer?.invoke(null, null, "webview", false)
            httpLogs?.record(HttpLogRecorder.Draft(method = "WEBVIEW", url = absolute, finalUrl = loaded.finalUrl, statusCode = 200, durationMs = loaded.elapsedMs, requestHeaders = sourceHeaders + parsed.headers, responseBody = loaded.html, sourceAnchor = requestOrigin.sourceAnchor, contextId = requestOrigin.contextId, originKind = requestOrigin.originKind ?: "webview"))
            if (fetcher.looksLikeVerification(403, loaded.finalUrl, loaded.html)) {
                throw com.mina.legadostudio.verification.VerificationRequiredException(
                    loaded.finalUrl, loaded.finalUrl.toHttpUrlOrNull()?.host.orEmpty(), viaWebView = true,
                    marker = fetcher.verificationMarker(403, loaded.finalUrl, loaded.html) ?: "webview", code = 200,
                )
            }
            // WebView 渲染后仍是挑战页 = 挑战未放行，给出结构化提示而不是把挑战页交给规则解析
            fetcher.requireNoJsChallenge(HttpFetcher.FetchResult(200, loaded.finalUrl, emptyMap(), loaded.html, loaded.elapsedMs))
        } else {
            val request = HttpFetcher.FetchRequest(absolute, parsed.method, sourceHeaders + parsed.headers, parsed.body, parsed.charset, maxBodyBytes = MAX_BODY_BYTES, origin = requestOrigin, useCookieJar = useCookieJar)
            // 200 JS 挑战页必须有结构化提示：cacheFetch 或直接抓取都不许把挑战页当业务数据交给规则解析
            val result = cacheFetch?.invoke(request)
                ?: fetchAbsolute(absolute, parsed.method, sourceHeaders + parsed.headers, parsed.body, parsed.charset, requestOrigin, useCookieJar)
            // cacheFetch 返回值不透明（可能内部 miss 后走 HTTP/WebView）：只记调用方可见的 reportedCode，不猜 raw/命中
            if (cacheFetch != null) observer?.invoke(null, result.code, "cache_fetch", null)
            else observer?.invoke(result.code, result.code, "http", false)
            fetcher.requireNoJsChallenge(result)
        }
        // 对齐真机 AnalyzeUrl：每次拿到响应后先执行书源 loginCheckJs（纯 JS，result 为响应对象），
        // JS 可改写/接管响应；返回值按 StrResponse 语义接管（字符串替换 body，对象取 body()/headers()）。
        loginCheckJs?.takeIf { it.isNotBlank() }?.let { js ->
            response = applyLoginCheckJs(js, response, jsLibBindings, requestOrigin, jsLog)
        }
        parsed.bodyJs?.takeIf { it.isNotBlank() }?.let { js ->
            val transformed = rhino.evaluate(js, response.finalUrl, response.body, mapOf("url" to response.finalUrl) + jsLibBindings, origin = requestOrigin)
            transformed.value?.let { response = response.copy(body = it) }
            transformed.logs.forEach { jsLog?.invoke("bodyJs: $it") }
        }
        return response
    }

    /**
     * 执行 loginCheckJs 并把返回值落到 FetchResult：
     * - 返回 null/undefined/原响应本身 → 响应不变（真机不改动时同样原样返回）
     * - 返回字符串 → 替换 body
     * - 返回响应对象（StudioJsResponse 或 {body, headers} 形 Map/JS 对象）→ 取 body()/headers() 替换
     * JS 抛错直接上浮（与真机 evalJS 失败中断该请求一致）。
     */
    private fun applyLoginCheckJs(
        js: String,
        response: HttpFetcher.FetchResult,
        jsLibBindings: Map<String, Any?>,
        origin: HttpOrigin?,
        jsLog: ((String) -> Unit)?,
    ): HttpFetcher.FetchResult {
        // result 绑定为 StudioJsResponse（body()/headers()/code()/url() 与真机 StrResponse 同名可调用）
        val jsResponse = StudioJsResponse(response.code, response.finalUrl, response.body, response.headers, response.elapsedMs, response.rawBytes)
        val raw = rhino.evaluateRaw(js, response.finalUrl, jsResponse, mapOf("url" to response.finalUrl) + jsLibBindings, origin = origin)
        raw.logs.forEach { jsLog?.invoke("loginCheckJs: $it") }
        return when (val v = com.script.rhino.RhinoScriptEngine.unwrapReturnValue(raw.value)) {
            null -> response
            is String -> response.copy(body = v)
            is StudioJsResponse -> response.copy(body = v.body(), headers = v.headers())
            is Map<*, *> -> {
                val body = (v["body"] ?: v["content"])?.toString()
                @Suppress("UNCHECKED_CAST")
                val headers = (v["headers"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }
                response.copy(body = body ?: response.body, headers = headers ?: response.headers)
            }
            is org.htmlunit.corejs.javascript.NativeObject -> {
                val body = org.htmlunit.corejs.javascript.ScriptableObject.getProperty(v, "body")
                    .takeIf { it != org.htmlunit.corejs.javascript.Scriptable.NOT_FOUND }?.toString()
                response.copy(body = body ?: response.body)
            }
            else -> response
        }
    }

    private fun fetchAbsolute(url: String, method: String, headers: Map<String, String>, body: String?, charset: String?, origin: HttpOrigin? = null, useCookieJar: Boolean = true): HttpFetcher.FetchResult =
        fetcher.fetch(HttpFetcher.FetchRequest(url, method, headers, body, charset, maxBodyBytes = MAX_BODY_BYTES, origin = origin, useCookieJar = useCookieJar))

    /** bindings 里的书源级 Cookie jar 开关（缺省 true）；false = 仅对自动 jar 禁用，显式用户 cookie 语义不动。 */
    private fun cookieJar(bindings: Map<String, Any?>): Boolean = bindings[RhinoEvaluator.BINDING_USE_COOKIE_JAR] as? Boolean ?: true

    private fun requestValue(template: String, root: JsonObject, bindings: Map<String, Any?>, origin: HttpOrigin? = null): String {
        if (template.startsWith("@js:")) {
            return rhino.evaluate(template.removePrefix("@js:"), root.text("bookSourceUrl").orEmpty(), null, bindings, origin = origin).value.orEmpty()
        }
        var result = template
            .replace("{{key}}", java.net.URLEncoder.encode(bindings["key"]?.toString().orEmpty(), "UTF-8"))
            .replace("{{page}}", bindings["page"]?.toString() ?: "1")
        JS_TEMPLATE.findAll(result).toList().asReversed().forEach { match ->
            val value = rhino.evaluate(match.groupValues[1], root.text("bookSourceUrl").orEmpty(), null, bindings, origin = origin).value.orEmpty()
            result = result.replaceRange(match.range, value)
        }
        val parsed = LegadoUrlOptions.parse(result)
        val absolute = resolve(root.text("bookSourceUrl").orEmpty(), parsed.url)
        if (parsed.url == result) return absolute
        val optionText = result.substring(result.lastIndexOf(",{") + 1)
        return "$absolute,$optionText"
    }

    private fun extractElements(content: Any, rule: String, baseUrl: String, bindings: Map<String, Any?>, origin: HttpOrigin? = null): List<Any> {
        val parts = LegadoStringRule.split(rule)
        if (parts.isEmpty()) return emptyList()
        if (parts.size == 1 && parts[0].mode == LegadoStringRule.Mode.Default) {
            // 保留原始节点：子规则（如裸 @href）必须在元素自身上求值，序列化成 HTML 重解析会把上下文换成文档根节点
            return if (parts[0].rule.isBlank()) emptyList() else engine.elementList(content, parts[0].rule)
        }
        var current: Any? = content
        for (part in parts) {
            current = when (part.mode) {
                LegadoStringRule.Mode.Js -> rhino.evaluateRaw(part.rule, baseUrl, current, bindings, origin = origin).value
                LegadoStringRule.Mode.Default -> {
                    val html = when (current) {
                        is String -> current
                        else -> toElementHtml(current).joinToString("")
                    }
                    if (part.rule.isBlank()) current else engine.elementList(html, part.rule)
                }
            }
        }
        return toElementList(current)
    }

    private fun toElementHtml(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is org.jsoup.nodes.Element -> listOf(value.outerHtml())
        is org.jsoup.select.Elements -> value.map { it.outerHtml() }
        is org.htmlunit.corejs.javascript.NativeArray -> (0 until value.length.toInt()).flatMap { index ->
            toElementHtml(com.script.rhino.RhinoScriptEngine.unwrapReturnValue(value.get(index, value)))
        }
        is Iterable<*> -> value.mapNotNull { item -> when (item) { is org.jsoup.nodes.Element -> item.outerHtml(); is Map<*, *>, is List<*> -> LegadoRuleEngine.toJson(item); null -> null; else -> item.toString() } }
        is Array<*> -> value.mapNotNull { item -> when (item) { is org.jsoup.nodes.Element -> item.outerHtml(); null -> null; else -> item.toString() } }
        is String -> runCatching {
            val parsed = JsonParser.parseString(value)
            if (parsed.isJsonArray) parsed.asJsonArray.map { it.asString } else listOf(value)
        }.getOrElse { listOf(value) }
        else -> listOf(value.toString())
    }

    private fun toElementList(value: Any?): List<Any> = when (value) {
        null -> emptyList()
        is org.jsoup.nodes.Element -> listOf(value)
        is org.jsoup.select.Elements -> value.toList()
        is org.htmlunit.corejs.javascript.NativeArray -> (0 until value.length.toInt()).flatMap { index ->
            toElementList(com.script.rhino.RhinoScriptEngine.unwrapReturnValue(value.get(index, value)))
        }
        is Iterable<*> -> value.filterNotNull()
        is Array<*> -> value.filterNotNull()
        else -> listOf(value)
    }

    private fun asText(value: Any): String = when (value) {
        is String -> value
        is org.jsoup.nodes.Element -> value.outerHtml()
        is Map<*, *>, is List<*> -> LegadoRuleEngine.toJson(value)
        else -> value.toString()
    }

    /**
     * 字段级取值。`field` 非空时（如 `ruleSearch.bookUrl`），规则解析异常带上字段名与规则原文
     * 写入 `fieldErrors`，不再只抛出无上下文的底层异常文本。
     */
    private fun extract(content: Any, rule: String?, baseUrl: String, bindings: Map<String, Any?> = emptyMap(), unescape: Boolean = true, origin: HttpOrigin? = null, field: String? = null, fieldErrors: MutableList<String>? = null): String? {
        if (rule.isNullOrBlank()) return null
        var result: Any? = content
        val onError: ((Exception) -> Unit)? = if (field != null && fieldErrors != null) { e -> recordFieldError(fieldErrors, field, rule, e) } else null
        for (part in LegadoStringRule.split(rule)) {
            if (result == null) continue
            if (part.rule.isNotBlank() || part.replaceRegex.isEmpty()) {
                result = try {
                    when (part.mode) {
                        LegadoStringRule.Mode.Js -> rhino.evaluate(part.rule, baseUrl, asText(result), bindings, origin = origin).value
                        // 与官方 getString 一致：多命中按 \n 拼接，不能只取首个（多段正文 @text 会丢后续段落）
                        LegadoStringRule.Mode.Default -> if (part.rule.isBlank()) result else engine.extract(result, part.rule, onError = onError).values.takeIf { it.isNotEmpty() }?.joinToString("\n")
                    }
                } catch (e: Exception) {
                    if (field == null || e is kotlinx.coroutines.CancellationException || e is FieldRuleException) throw e
                    throw FieldRuleException(fieldErrorText(field, part.rule, part.mode, e), e)
                }
            }
            if (result != null && part.replaceRegex.isNotEmpty()) {
                result = LegadoStringRule.replace(asText(result), part)
            }
        }
        if (result == null) return null
        val text = asText(result)
        return if (unescape) LegadoStringRule.unescapeHtml(text) else text
    }

    /** 字段规则执行失败：message 带字段名、规则类型与规则原文，原始异常放 cause。 */
    class FieldRuleException(message: String, cause: Throwable) : IllegalStateException(message, cause)

    private fun ruleKindLabel(rule: String, mode: LegadoStringRule.Mode): String = when (mode) {
        LegadoStringRule.Mode.Js -> "JS"
        LegadoStringRule.Mode.Default -> when (LegadoRuleEngine.detect(rule)) {
            LegadoRuleEngine.Kind.JSON_PATH -> "JSONPath"
            LegadoRuleEngine.Kind.XPATH -> "XPath"
            LegadoRuleEngine.Kind.REGEX -> "正则"
            LegadoRuleEngine.Kind.CSS -> "JSoup/CSS"
        }
    }

    private fun fieldErrorText(field: String, rule: String, mode: LegadoStringRule.Mode, e: Throwable): String {
        val cause = generateSequence(e) { it.cause }.last()
        val reason = (cause.message ?: e.message ?: cause.javaClass.simpleName).take(200)
        return "$field 规则解析失败（${ruleKindLabel(rule, mode)}）：${rule.take(120)} → ${cause.javaClass.simpleName}: $reason"
    }

    private fun recordFieldError(sink: MutableList<String>, field: String, rule: String, e: Exception) {
        if (sink.size >= 8) return
        // 键不存在＝字段取空（官方同样静默），不算规则错误
        if (e is com.jayway.jsonpath.PathNotFoundException) return
        val text = fieldErrorText(field, rule, LegadoStringRule.Mode.Default, e)
        if (text !in sink) sink += text
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

    /**
     * 启发式告警：规则疑似把 CSS 选择器的 `#id`/`.class` 思路误写成 Legado 的
     * `id.`/`class.`/`tag.` 前缀语法。Legado 前缀后应紧跟**一个**属性值
     * （如 `id.content`、`class.book-list`、`tag.div`），若第二段本身再含 `.`/`#`/`-` 链、
     * 或整段写成 `id.xx.yy`/`class.xx.yy` 的多节点路径，多半是想写 CSS
     * （应为 `#xx.yy` / `.xx.yy` 或 `tag.xx .yy`）。只告警不拦截——合法多段前缀也存在，
     * 沙箱与真机引擎一致时按官方行为走，提示仅帮作者定位「规则能跑但取空」。
     */
    private fun pseudoSelectorWarning(rule: String?): String? {
        if (rule.isNullOrBlank()) return null
        val firstSeg = rule.trimStart().substringBefore('@').substringBefore("<js>")
        // 命中 `id.xxx.yyy` / `class.xxx.yyy` / `id.xxx#yyy` 这类「前缀值里再带点/井」形态
        val m = Regex("^(id|class|tag)\\.([A-Za-z0-9_-]+)[.#](\\S+)").find(firstSeg) ?: return null
        return "选择器疑似 CSS 写法误用 Legado 前缀：`${m.value}` 里的 `${m.groupValues[2]}` 后再接 `${m.groupValues[0].substring(m.groupValues[1].length + m.groupValues[2].length + 1)}`；" +
            "Legado `id.`/`class.`/`tag.` 只接受单个属性值，想写 CSS 请用 `@css:` 前缀（如 `@css:#${m.groupValues[2]} ${m.groupValues[3]}` 或 `@css:.${m.groupValues[2]} .${m.groupValues[3]}`），或直接写 CSS 选择器"
    }

    private fun resolve(base: String, value: String): String = if (value.isBlank()) "" else if (value.startsWith("http://") || value.startsWith("https://")) value else base.toHttpUrlOrNull()?.resolve(value)?.toString() ?: value
    private fun resolveIfUrl(base: String, field: String, value: String): String = if (field in setOf("coverUrl", "tocUrl")) resolve(base, value) else value
    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString

    companion object {
        /** debug/check 抓取上限，与 StudioMcpServer.contextFetch 一致：超限报 CONTENT_TOO_LARGE，避免大页 OOM。 */
        internal const val MAX_BODY_BYTES = 4_000_000L
        // Android ICU treats unescaped `}` as a quantifier; both braces must be escaped.
        internal val JS_TEMPLATE = Regex("\\{\\{([^{}]+)\\}\\}")
    }
}
