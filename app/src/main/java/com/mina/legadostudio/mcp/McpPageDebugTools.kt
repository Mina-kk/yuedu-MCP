package com.mina.legadostudio.mcp

import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpOrigin
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put

internal fun StudioMcpServer.registerPageDebugTools(server: Server) {
    server.tool("fetch_page", "抓取并保存网页上下文。首次默认返回6000字符预览+pageId；同任务相同URL(含POST)五分钟内复用，仅返回引用。用read_page分段/搜索、analyze_html/inspect_rule(pageId)测试，勿重复传HTML。refresh=true强制联网。", fetchSchema(), toolAnnotations = openWrite) { req ->
        runCatching { fetchPageOk(req.arguments) }.getOrElse { verificationAware(it, req.arguments) }
    }
    server.tool("analyze_html", "分析HTML或测试纯 jsoup CSS 选择器（selector 不支持 Legado 的 @css:/@@/XPath/JS 组合规则；完整规则请用 inspect_rule）。优先传pageId分析完整快照，无需联网；html与pageId二选一。", schema(mapOf("html" to "HTML（与pageId二选一）", "pageId" to "已存网页ID", "baseUrl" to "基础URL", "selector" to "仅纯 jsoup CSS 选择器；完整 Legado 规则用 inspect_rule"), emptyList()), toolAnnotations = readOnly) { req ->
        runCatching {
            require(!(req.arguments.str("html") != null && req.arguments.str("pageId") != null)) { "html/pageId不能同时传入" }
            val e = req.arguments.str("pageId")?.let { savedEntry(req.arguments, it) }
            require(e == null || e.kind == "page") { "需要pageId" }
            val html = e?.text ?: req.arguments.str("html") ?: error("需要html或pageId")
            val base = req.arguments.str("baseUrl") ?: e?.page?.finalUrl.orEmpty()
            val selector = req.arguments.str("selector").orEmpty()
            val output = withContext(Dispatchers.Default) { if (selector.isBlank()) app.analyzer.analyze(html, base) else app.analyzer.testSelector(html, base, selector) }
            // 统一带上下文句柄：即使直传 html 不落引用，也回 contextId，AI 后续可用同一上下文续查
            val ctxId = contextId(req.arguments)
            ok(app.gson.toJson(if (e == null) mapOf("contextId" to ctxId, "output" to output)
                else mapOf("contextId" to ctxId, "pageId" to e.id, "snapshot" to contexts.metadata(e), "output" to output)))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("inspect_rule", "用Legado解析器运行规则。output 为规则解析后的单值拼接字符串（内部 values 仅含该结果），不是逐元素列表；elements 仅作前100项预览，不对应逐项 output。传pageId使用完整快照、不重新抓取、不回传网页正文；只传url也复用快照；refresh=true强制联网；WebView验证模式保持实时。规则内JS仍可主动联网。", schema(mapOf("url" to "实时请求URL（与pageId二选一）", "pageId" to "已存网页ID", "rule" to "Legado规则", "method" to "GET/POST/HEAD", "charset" to "可选编码"), listOf("rule")), toolAnnotations = openWrite) { req ->
        runCatching {
            val pageId = req.arguments.str("pageId")
            require(!(pageId != null && req.arguments.str("url") != null)) { "url/pageId不能同时传入" }
            require(!(pageId != null && req.arguments.bool("refresh") == true)) { "refresh需要url，不能刷新pageId" }
            val webViewLive = pageId == null && req.arguments.str("url")?.let { app.domainModes.requiresWebView(it) } == true
            val hit = if (pageId == null && !webViewLive) contextFetch(req.arguments).second else null
            val e = pageId?.let { savedEntry(req.arguments, it) } ?: hit?.entry
            require(e == null || e.kind == "page") { "需要pageId" }
            val inspectCtxId = contextId(req.arguments)
            val input = com.mina.legadostudio.runtime.LegadoRuntime.InspectRequest(
                e?.page?.finalUrl ?: req.arguments.str("url") ?: error("需要url或pageId"),
                req.arguments.str("method") ?: "GET", charset = req.arguments.str("charset"), rule = req.arguments.str("rule") ?: error("rule不能为空"),
                contextId = inspectCtxId, sourceAnchor = inheritedAnchor(req.arguments, inspectCtxId),
            )
            val report = if (e == null) app.runtime.inspect(input) else app.runtime.inspectSnapshot(input, e.page!!)
            val response = app.gson.toJsonTree(report.response.copy(body = "", headers = emptyMap())).asJsonObject
            response.remove("rawBytes")
            ok(app.gson.toJson(mapOf("response" to response, "output" to report.output,
                "outputHint" to "output 是整条规则解析后拼接的单值字符串；values 只含该单值，不代表逐元素结果。elements 是最多100项的独立预览，不与 output 逐项对应。",
                "elements" to report.elements, "elementWarning" to report.elementWarning, "contextId" to contextId(req.arguments), "pageId" to e?.id, "cacheHit" to (hit?.reused ?: (pageId != null)), "snapshot" to (e?.let { contexts.metadata(it) } ?: mapOf("live" to true)))))
        }.getOrElse { verificationAware(it) }
    }
    server.tool("debug_source", "使用 App 内运行时调试 BookSource JSON；entry 支持关键词、详情 URL、++目录、--正文、分类::URL。默认复用本任务快照，refresh=true 全程实时。注意 searchUrl 的 @js: 最后一行必须以纯表达式返回 http(s) URL 或 \"url,\"+JSON.stringify({method,body,headers})，带 return 或返回裸文本会报「返回的值无效」。", schema(mapOf("source" to "BookSource JSON", "entry" to "调试入口", "refresh" to "可选，true=不使用缓存"), listOf("source", "entry")), toolAnnotations = openWrite) { req ->
        runCatching {
            val source = req.arguments.str("source") ?: return@tool err("source 不能为空")
            // 与 save_source 同一套 JSON 错误提示：语法错直接回行列定位片段，不进后续 debug
            runCatching { JsonParser.parseString(source).asJsonObject }.getOrNull()
                ?: return@tool err(app.validator.validate(source).issues.firstOrNull()?.let { "source 不是合法 JSON 对象：${it.message}" }
                    ?: "source 不是合法 JSON 对象")
            val entry = req.arguments.str("entry") ?: return@tool err("entry 不能为空")
            val cacheTrace = StudioMcpServer.RuntimeCacheTrace(req.arguments.bool("refresh") == true)
            val cache = runtimeCache(req.arguments, cacheTrace)
            val anchor = sourceAnchorOf(source)
            val ctxId = contextId(req.arguments)
            if (anchor != null && ctxId.isNotBlank()) {
                com.mina.legadostudio.domain.ContextAnchorRegistry.put(ctxId, anchor)
            }
            val report = app.runtime.debug(source, entry, cache, HttpOrigin(sourceAnchor = anchor, contextId = ctxId, originKind = "debug_source"))
            ok(app.gson.toJsonTree(report).asJsonObject.apply { add("cache", app.gson.toJsonTree(cacheTrace.summary())) }.toString())
        }.getOrElse { e ->
            val hint = if (e.message?.contains("返回的值无效") == true) {
                "；searchUrl 的 @js: 最后一行必须纯表达式返回 http(s) URL 或 \"url,\"+JSON.stringify({method,body,headers})，不能带 return、不能返回裸文本"
            } else ""
            if (hint.isEmpty()) verificationAware(e, req.arguments) else err(e.message.orEmpty() + hint)
        }
    }
    server.tool("eval_js", "Legado Rhino执行JS；可传pageId，将完整已存正文注入result/src，免去复制HTML。已注入官方同名对象：java（ajax/connect/加解密）、cookie（getCookie/getKey/setCookie=合并/replaceCookie=替换/removeCookie）、cache（put/get/delete/putMemory/getFromMemory，进程内有效）、source（put/get/getVariable/setVariable，debug_source时与书源变量互通）。JS内ajax/connect仍会联网。每次调用都是全新作用域：JS 里 var/函数声明不会带到下一次调用，跨调用要共享数据请显式用 cache.put/get 或 source.put/get；报「Cannot find function / 方法不存在」类错误时对照沙箱可用 API 清单（get_skill_reference(id=legado-book-source, path=references/js-api.md)）。", schema(mapOf("js" to "JavaScript", "baseUrl" to "基础URL", "pageId" to "可选网页快照ID"), listOf("js")), toolAnnotations = openWrite) { req ->
        runCatching {
            val e = req.arguments.str("pageId")?.let { savedEntry(req.arguments, it) }
            require(e == null || e.kind == "page") { "需要pageId" }
            val js = req.arguments.str("js") ?: return@tool err("js 不能为空")
            val evalCtxId = contextId(req.arguments)
            val output = app.runtime.evaluate(js, req.arguments.str("baseUrl") ?: e?.page?.finalUrl.orEmpty(), e?.text,
                HttpOrigin(sourceAnchor = inheritedAnchor(req.arguments, evalCtxId), contextId = evalCtxId, originKind = "eval_js"))
            // 统一带上下文句柄：e==null（未传 pageId）时也要回 contextId，AI 拿得到任务上下文
            ok(app.gson.toJson(if (e == null) mapOf("contextId" to evalCtxId, "output" to output)
                else mapOf("contextId" to evalCtxId, "pageId" to e.id, "snapshot" to contexts.metadata(e), "output" to output)))
        }.getOrElse { e ->
            val message = e.message.orEmpty()
            if (message.contains("【API 提示】")) err(message)
            else verificationAware(e)
        }
    }
}
