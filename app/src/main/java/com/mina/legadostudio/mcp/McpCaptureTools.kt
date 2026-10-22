package com.mina.legadostudio.mcp

import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpLogCaps
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun StudioMcpServer.registerCaptureTools(server: Server) {
    server.tool("capture_once", "逐次抓包：手动发一次真实请求，按独立 cap:contextId 取回本次事务日志。重定向中间每跳另记一条 originKind=capture_hop 的独立日志（本跳请求 URL/方法、3xx 状态码、响应头与 Location 目标，同 contextId/sourceAnchor），被 SSRF 拦截的 3xx 也先落库再中断；最终响应维持 originKind=capture_once 一条，redirectChain 字段不变。返回轻量摘要与 logIds/hopLogIds/finalLogIds（详情用 get_http_log 或整链用 get_capture），不回传响应正文。GET/POST/HEAD，支持自定义请求头与 POST 正文。仅允许公网 HTTP/HTTPS：私网/回环/保留地址、内嵌凭据、非 HTTP 重定向、公网域名解析到私网（DNS 重绑定）都会被拒绝；要求 HTTP 事务记录已开启（关闭时拒绝发起并提示手动开启）。站点处于 WebView 模式的域会被拒绝（WebView 内部跳转无法逐跳校验），请改 HTTP 模式或用 fetch_page。注意：URL/query 可能带令牌等敏感参数，分享前自行检查。", captureOnceSchema(), toolAnnotations = openWrite) { req ->
        runCatching {
            val params = com.mina.legadostudio.network.CaptureOnce.Params(
                url = req.arguments.str("url") ?: error("url 不能为空"),
                method = req.arguments.str("method") ?: "GET",
                headers = req.arguments.str("headers")?.let { raw ->
                    runCatching { JsonParser.parseString(raw).asJsonObject.entrySet().associate { it.key to it.value.asString } }
                        .getOrNull() ?: error("headers 必须是 JSON 对象字符串，如 {\"Referer\":\"https://x\"}")
                }.orEmpty(),
                body = req.arguments.str("body"),
                charset = req.arguments.str("charset"),
                timeoutSec = (req.arguments.int("timeoutSec") ?: 30).coerceIn(5, 120),
            )
            val result = app.captureOnce.run(params)
            ok(app.gson.toJson(mapOf(
                "contextId" to result.contextId,
                "sourceAnchor" to result.sourceAnchor,
                "viaWebView" to result.viaWebView,
                "code" to result.code,
                "finalUrl" to result.finalUrl,
                "elapsedMs" to result.elapsedMs,
                "redirectChain" to result.redirectChain,
                "bodyNote" to result.bodyNote.take(400),
                "logCount" to result.logCount,
                "logIds" to result.logIds,
                "hopLogIds" to result.hopLogIds,
                "finalLogIds" to result.finalLogIds,
                "logs" to result.logs.map {
                    mapOf("id" to it.id, "method" to it.method, "url" to it.url, "finalUrl" to it.finalUrl,
                        "statusCode" to it.statusCode, "durationMs" to it.durationMs,
                        "error" to it.error.take(300), "originKind" to it.originKind)
                },
                "recordingDisabled" to result.recordingDisabled,
                "workflow" to result.workflow,
                "hint" to (result.hint + "；日志含完整 URL/query（可能带令牌），分享前请检查；正文与请求/响应头用 get_http_log(id) 取回，本工具不回传正文"),
            )))
        }.getOrElse { e ->
            if (e is com.mina.legadostudio.network.CaptureOnce.CaptureFailedException) {
                err(e.message.orEmpty().take(300) + "；本次日志 contextId=${e.partial.contextId}，logIds=${app.gson.toJson(e.partial.logIds)}，hopLogIds=${app.gson.toJson(e.partial.hopLogIds)}；用 get_capture(contextId) 回看")
            } else verificationAware(e)
        }
    }
    server.tool("webview_capture", "WebView 抓包（OkHttp 供给/观察，非原生 WebView 网络栈抓包）：一次性临时 WebView 加载入口页，跑完即销毁、不可交互（要点击章节/翻页请改用应用内「日志→抓包→浏览器抓包」可见页）。机制：对 GET/HEAD，引擎用独立 OkHttpClient 对同一 URL 发自己的供给请求并如实记录这次响应（这是 OkHttp 的独立请求，**不是 WebView 内部网络栈的原生字节流**），再把已记录的这批字节喂回 WebView——页面拿到的是供给的字节，但若平台忽略供给改走自己的网络栈，本日志仍是供给侧证据而非 WebView 实际流量。3xx/204/304 不供给：供给侧拿到 3xx 记 OBSERVED_ONLY（WebView 会对同一 URL 重新发起并自行跳转，不宣称逐跳抓全链）；POST/PUT 拿不到请求体只记 OBSERVED_ONLY 请求行；私网/回环/DNS 解析到私网（含连接级钉住地址防重绑定）的请求被空响应阻断记 BLOCKED；超大响应（>4MB）与会话容量（32MB/2000 条）耗尽只留请求行；字体/二进制原始字节落盘（行内记文件路径，保留 7 天过期清理）。返回 contextId/finalUrl/captured/observed/blocked/logIds（详情用 get_http_log 或整链 get_capture，不回传正文，htmlPreview 仅预览）。仅允许公网 HTTP/HTTPS 入口；要求 HTTP 事务记录已开启。注意：URL/query 可能带令牌等敏感参数，分享前自行检查。", webViewCaptureSchema(), toolAnnotations = openWrite) { req ->
        runCatching {
            val result = app.webViewCapture.run(
                url = req.arguments.str("url") ?: error("url 不能为空"),
                delayMs = req.arguments.int("delayMs")?.toLong() ?: 800,
                timeoutSec = (req.arguments.int("timeoutSec") ?: 120).coerceIn(10, 300),
                webJs = req.arguments.str("webJs"),
            )
            ok(app.gson.toJson(mapOf(
                "contextId" to result.result.contextId,
                "sourceAnchor" to result.result.sourceAnchor,
                "entryUrl" to result.result.entryUrl,
                "finalUrl" to result.result.finalUrl,
                "elapsedMs" to result.result.elapsedMs,
                "capturedCount" to result.result.capturedCount,
                "observedCount" to result.result.observedCount,
                "blockedCount" to result.result.blockedCount,
                "exhausted" to result.result.exhausted,
                "logCount" to result.logCount,
                "logIds" to result.logIds,
                "htmlPreview" to result.result.html.take(2000),
                "workflow" to result.workflow,
                "hint" to (result.hint + "；日志含完整 URL/query（可能带令牌），分享前请检查；正文与请求/响应头用 get_http_log(id) 取回，本工具不回传正文"),
            )))
        }.getOrElse { e -> verificationAware(e) }
    }
    server.tool("webview_render", "用真实 WebView 渲染一个 URL 并取回最终 DOM/HTML，存入本任务上下文成 pageId（可继续 read_page/inspect_rule/analyze_html/eval_js 引用）。两种用法：① 只传 url → 走 loadUrl 渲染在线页面；② 传 url + pageId（已存快照）→ 把快照正文经 loadDataWithBaseURL(url, 已存HTML) 渲染——用于把已抓上下文在真实浏览器里还原成最终正文（执行页面内 JS、相对资源按 baseURL 解析），等价书源 java.webView(html,url,js) 三参交接。webJs 可选：主文档就绪后注入的取值脚本（返回 null 重试轮询至超时、__STUDIO_ERROR__ 抛错），缺省返回 documentElement.outerHTML。url 仅公网 http/https；渲染后的 finalUrl 也经同样校验（拒绝重定向到私网/非法 scheme）。渲染结果是本任务快照，不是实时证据。", webViewRenderSchema(), toolAnnotations = openWrite) { req ->
        runCatching {
            val url = req.arguments.str("url") ?: error("url 不能为空")
            val pageId = req.arguments.str("pageId")
            val srcEntry = pageId?.let { savedEntry(req.arguments, it) }
            require(srcEntry == null || srcEntry.kind == "page") { "pageId 必须是已存网页快照（page），收到 ${srcEntry?.kind}" }
            val embedHtml = srcEntry?.text
            val ctxId = contextId(req.arguments)
            val result = app.webViewLoader.load(
                url = url,
                webJs = req.arguments.str("webJs"),
                delayMs = req.arguments.int("delayMs")?.toLong() ?: 800,
                timeoutMs = (req.arguments.int("timeoutSec") ?: 60).coerceIn(10, 300) * 1000L,
                html = embedHtml,
                resourceRecorder = { m, reqUrl, headers ->
                    app.httpLogs.recordAsync(com.mina.legadostudio.network.HttpLogRecorder.Draft(
                        method = m, url = reqUrl, statusCode = 0, requestHeaders = headers,
                        contextId = ctxId, originKind = "webview_render_resource",
                    ))
                },
            )
            // 渲染结果存为 page 条目：key 用渲染通道专属 digest，避免与 fetch_page 普通快照混串
            val fingerprint = contextFingerprint()
            val hit = contexts.fetch(ctxId, TaskContextStore.digest("webrender\n${result.finalUrl}"), fingerprint, reusable = false, refresh = true) {
                HttpFetcher.FetchResult(200, result.finalUrl, emptyMap(), result.html, result.elapsedMs)
            }
            val e = hit.entry
            ok(app.gson.toJson(contexts.metadata(e) + mapOf(
                "contextId" to ctxId, "pageId" to e.id,
                "url" to url, "finalUrl" to result.finalUrl, "elapsedMs" to result.elapsedMs,
                "htmlChars" to result.html.length,
                "htmlPreview" to result.html.take(2000),
                "embedded" to (embedHtml != null),
                "sourcePageId" to pageId,
                "hint" to "渲染结果已存为 pageId，用 read_page(pageId) 分段、inspect_rule/analyze_html/eval_js(pageId) 直接测规则；快照非实时证据。",
            )))
        }.getOrElse { verificationAware(it, req.arguments) }
    }
    server.tool("webview_eval", "用真实 WebView 打开 URL 并执行一段 JS，直接返回脚本值（不落快照、不分页）。script 两种形态都行：表达式（如 document.title、document.querySelectorAll('a').length）或函数体（末尾 return ...）；返回 null/undefined 会按 300ms 间隔轮询直到超时——异步等待页面就绪时返回 null 即可。脚本抛错回 __STUDIO_ERROR__ 失败。与 webview_render 的区别：render 拿整页 DOM 存 pageId 供后续规则调试，eval 只取一个值适合探测/断言。url 仅公网 http/https，重定向落点同校验（SSRF 与 webview_render 同级）。", ToolSchema(properties = buildJsonObject {
        put("url", stringProp("WebView 加载的 HTTP/HTTPS URL（仅公网地址）"))
        put("script", stringProp("要在页面上下文执行的 JS：纯表达式或函数体；返回 null 表示未就绪继续轮询。兼容别名 webJs（与 webview_render 同名参数）"))
        put("webJs", stringProp("可选：script 的兼容别名（与 webview_render 的参数名对齐）；script 优先，二者都传时取 script"))
        putJsonObject("delayMs") { put("type", "integer"); put("description", "onPageFinished 后等待页面稳定的毫秒数，默认 800，最大 10000") }
        putJsonObject("timeoutSec") { put("type", "integer"); put("description", "加载+取值总超时，10..300 秒，默认 60") }
    }, required = listOf("url")), toolAnnotations = openWrite) { req ->
        runCatching {
            val url = req.arguments.str("url") ?: error("url 不能为空")
            val script = req.arguments.str("script") ?: req.arguments.str("webJs")
                ?: error("script 不能为空（也可用兼容别名 webJs 传入）")
            val ctxId = contextId(req.arguments)
            val result = app.webViewLoader.load(
                url = url,
                webJs = script,
                delayMs = req.arguments.int("delayMs")?.toLong() ?: 800,
                timeoutMs = (req.arguments.int("timeoutSec") ?: 60).coerceIn(10, 300) * 1000L,
                html = null,
                resourceRecorder = { m, reqUrl, headers ->
                    app.httpLogs.recordAsync(com.mina.legadostudio.network.HttpLogRecorder.Draft(
                        method = m, url = reqUrl, statusCode = 0, requestHeaders = headers,
                        contextId = ctxId, originKind = "webview_eval_resource",
                    ))
                },
            )
            val value = result.html
            ok(app.gson.toJson(mapOf(
                "contextId" to ctxId,
                "url" to url, "finalUrl" to result.finalUrl, "elapsedMs" to result.elapsedMs,
                "valueChars" to value.length,
                "value" to value.take(12000),
                "truncated" to (value.length > 12000),
                "hint" to "value 是脚本返回值转字符串（对象请先 JSON.stringify）；超 12000 字符截断，要完整 DOM 请用 webview_render 存 pageId 后 read_page。",
            )))
        }.getOrElse { verificationAware(it, req.arguments) }
    }
    server.tool("list_captures", "列出抓包会话历史（cap:contextId 分组摘要，新→旧分页，不回传正文）。同一前缀混存三类会话：逐次抓包 capture_once（kind=capture_once）、可见浏览器会话（kind=webview_browser_visible，用户在 App「日志→抓包→浏览器抓包」交互产生）、MCP 无头 webview_capture（kind=webview_capture_headless）。kind=webview_capture 是旧会话（结算行缺 mode 标记，只能确定是浏览器抓包）；kind=unknown 是更老的记录（无任何 originKind 证据，不猜来源）。拿 contextId 后用 get_capture 分页回看事务、poll_capture 增量拉新事务、get_http_log 看单条详情。", ToolSchema(properties = buildJsonObject {
        putJsonObject("limit") { put("type", "integer"); put("description", "默认 20，最大 100") }
        putJsonObject("offset") { put("type", "integer"); put("description", "可选，分页偏移") }
    }, required = emptyList()), toolAnnotations = readOnly) { req ->
        val limit = (req.arguments.int("limit") ?: 20).coerceIn(1, 100)
        val offset = (req.arguments.int("offset") ?: 0).coerceAtLeast(0)
        val dao = app.database.dao()
        val rows = dao.captureSessionSummaries(limit, offset)
        val total = dao.countCaptureSessions()
        val items = rows.map { s ->
            val kind = com.mina.legadostudio.domain.CaptureSessionKinds.classify(s)
            mutableMapOf<String, Any?>(
                "contextId" to s.contextId,
                "firstLogId" to s.firstLogId,
                "latestLogId" to s.latestLogId,
                "totalCount" to s.totalCount,
                "hopCount" to s.hopCount,
                "kind" to com.mina.legadostudio.domain.CaptureSessionKinds.mcpLabel(kind),
                "firstAt" to s.firstAt, "lastAt" to s.lastAt,
            ).also { m ->
                // lastStatus 仅对逐次抓包有「最终落点」语义；浏览器/未知会话也如实给（是末行状态码），字段名不冒称 final。
                m["lastStatus"] = s.lastStatus
                if (kind == com.mina.legadostudio.domain.CaptureSessionKinds.Kind.ONCE) m["finalStatus"] = s.lastStatus
            }
        }
        ok(app.gson.toJson(mapOf(
            "count" to items.size, "total" to total, "offset" to offset, "limit" to limit,
            "items" to items,
            "hint" to "kind=capture_once 的会话 hopCount>0 时有重定向中间跳（originKind=capture_hop）；latestLogId 是增量游标——对它调 poll_capture(contextId, afterLogId=latestLogId) 可持续拉活会话新事务，不要按 firstLogId 猜。详情按 id 调 get_http_log。",
        )))
    }
    server.tool("get_capture", "回看一个抓包会话（按 cap:contextId 精确，三类会话通用）：返回该上下文轻量日志分页+total，按入库序 ASC。逐次抓包含中间跳 originKind=capture_hop；浏览器会话含 webview_capture_resource/error/blocked/webview_capture（结算行）行。不受最近500条窗口限制。活会话增量用 poll_capture；需要完整请求/响应头与正文时按行 id 调 get_http_log。", ToolSchema(properties = buildJsonObject {
        put("contextId", stringProp("cap:xxx 抓包会话 ID"))
        putJsonObject("offset") { put("type", "integer"); put("minimum", 0) }
        putJsonObject("limit") { put("type", "integer"); put("description", "默认 50，最大 200") }
    }, required = listOf("contextId")), toolAnnotations = readOnly) { req ->
        val ctx = req.arguments.str("contextId") ?: return@tool err("contextId 不能为空")
        require(ctx.startsWith("cap:")) { "contextId 必须是 cap: 前缀的抓包会话" }
        val limit = (req.arguments.int("limit") ?: 50).coerceIn(1, 200)
        val offset = (req.arguments.int("offset") ?: 0).coerceAtLeast(0)
        val dao = app.database.dao()
        val page = dao.httpLogSummariesByContextIdPage(ctx, limit, offset)
        val total = dao.countHttpLogsByContextId(ctx)
        if (total == 0) return@tool err("未找到该抓包会话（contextId=$ctx）")
        ok(app.gson.toJson(mapOf(
            "contextId" to ctx, "total" to total, "offset" to offset, "limit" to limit,
            "items" to page.map { s ->
                mapOf(
                    "id" to s.id, "method" to s.method, "url" to s.url, "finalUrl" to s.finalUrl,
                    "statusCode" to s.statusCode, "durationMs" to s.durationMs,
                    "error" to s.error.take(300), "originKind" to s.originKind,
                )
            },
            "hint" to "originKind=capture_hop 的行是重定向中间跳（3xx+Location 证据）；webview_capture_* 行是浏览器抓包的供给/观察/阻断/结算证据。详情按 id 调 get_http_log。",
        )))
    }
    server.tool("get_http_log", "按 ID 读取单条 HTTP 事务详情（含请求/响应头与正文）。被截断的正文尾部带「…[正文已截断，原长 N 字符]」标记，并另附 requestBodyTruncatedFrom/responseBodyTruncatedFrom（截断前原长，字符数）；旧日志的旧标记（无原长）则不出该字段。", ToolSchema(properties = buildJsonObject { putJsonObject("id") { put("type", "integer") } }, required = listOf("id")), toolAnnotations = readOnly) { req ->
        val log = app.database.dao().httpLog((req.arguments.int("id") ?: return@tool err("id 不能为空")).toLong())
            ?: return@tool err("日志不存在")
        // 原长编码在正文尾部的截断标记里，纯文本解析即可，不依赖新增数据库列；
        // 旧行标记无原长 → null，字段不出。未截断行输出与旧实现完全一致。
        val reqFrom = HttpLogCaps.truncatedFrom(log.requestBody)
        val respFrom = HttpLogCaps.truncatedFrom(log.responseBody)
        if (reqFrom == null && respFrom == null) {
            ok(app.gson.toJson(log))
        } else {
            ok(app.gson.toJson(buildMap<String, Any?> {
                put("id", log.id); put("method", log.method); put("url", log.url)
                put("finalUrl", log.finalUrl); put("statusCode", log.statusCode)
                put("durationMs", log.durationMs); put("requestHeaders", log.requestHeaders)
                put("responseHeaders", log.responseHeaders); put("requestBody", log.requestBody)
                put("responseBody", log.responseBody); put("error", log.error)
                put("redirectChain", log.redirectChain); put("sourceAnchor", log.sourceAnchor)
                put("contextId", log.contextId); put("originKind", log.originKind)
                put("createdAt", log.createdAt)
                reqFrom?.let { put("requestBodyTruncatedFrom", it) }
                respFrom?.let { put("responseBodyTruncatedFrom", it) }
            }))
        }
    }
    server.tool("poll_capture", "增量拉取抓包会话新事务（cap:contextId 专用）：只返回 id > afterLogId 的行（ASC 入库序，上限 limit），附 nextAfterLogId/hasMore/sessionState。空页不推进游标——重复用同一 afterLogId 轮询是安全的，活会话逐条追加不会漏。sessionState=active|finished：可见浏览器会话要等用户「结束并保存」写出结算行（originKind=webview_capture）才转 finished；逐次抓包与无头 webview_capture 一次写入即 finished（它们不写结算行，故不以结算行判定）。建议轮询间隔≥1秒、limit≤200；contextId 不存在/游标越过最新行（记录被删）会明确报错。普通 HTTP 日志不会混入。", ToolSchema(properties = buildJsonObject {
        put("contextId", stringProp("cap:xxx 抓包会话 ID"))
        putJsonObject("afterLogId") { put("type", "integer"); put("description", "上次返回的 nextAfterLogId；首次调用传 list_captures 的 latestLogId 或 0 从头拉") }
        putJsonObject("limit") { put("type", "integer"); put("description", "默认 100，最大 200") }
    }, required = listOf("contextId")), toolAnnotations = readOnly) { req ->
        val ctx = req.arguments.str("contextId") ?: return@tool err("contextId 不能为空")
        require(ctx.startsWith("cap:")) { "contextId 必须是 cap: 前缀的抓包会话" }
        val after = (req.arguments.int("afterLogId") ?: 0).toLong().coerceAtLeast(0)
        val limit = (req.arguments.int("limit") ?: 100).coerceIn(1, 200)
        val dao = app.database.dao()
        val total = dao.countHttpLogsByContextId(ctx)
        if (total == 0) return@tool err("未找到该抓包会话（contextId=$ctx）")
        val latest = dao.latestHttpLogIdByContextId(ctx)
        if (com.mina.legadostudio.verification.WebViewCapture.isStaleCursor(after, latest)) {
            return@tool err("afterLogId=$after 越过会话当前末行 id=$latest（记录可能被清理或 contextId 有误）；请改用最新 latestLogId 或 0 重拉")
        }
        val page = dao.httpLogSummariesByContextIdAfterId(ctx, after, limit)
        // 三类会话「结束」信号不同：逐次/无头一次写完即结束，可见浏览器要等结算行——
        // 不能用「有无结算行」一刀切（逐次抓包从不写结算行，会永远误报 active）。
        val endState = dao.captureSessionEndState(ctx)
        val next = page.lastOrNull()?.id ?: after
        ok(app.gson.toJson(mapOf(
            "contextId" to ctx,
            "total" to total,
            "afterLogId" to after,
            "nextAfterLogId" to next,
            "hasMore" to (page.size == limit),
            "latestLogId" to latest,
            "sessionState" to if (com.mina.legadostudio.domain.CaptureSessionKinds.isFinished(endState)) "finished" else "active",
            "items" to page.map { s ->
                mapOf(
                    "id" to s.id, "method" to s.method, "url" to s.url, "finalUrl" to s.finalUrl,
                    "statusCode" to s.statusCode, "durationMs" to s.durationMs,
                    "error" to s.error.take(300), "originKind" to s.originKind,
                    "createdAt" to s.createdAt,
                )
            },
            "hint" to "只回了增量行：空页（items 空）说明暂无新事务，游标停在 afterLogId 不动；hasMore=true 时用 nextAfterLogId 继续拉。结算行（originKind=webview_capture）出现即会话已结束，此后不再有新行。正文与请求/响应头按 id 调 get_http_log。",
        )))
    }
    server.tool("get_capture_resource", "按 logId 读取抓包会话落盘资源的一段字节（只读、分页、受限）：仅 WebView 抓包会话的二进制行（responseBody 含 [bodyFile=captures/<ctx>/<file>] 标记）可用——先校验该日志确属 cap: 会话且声明了资源、文件确实在该会话目录内，再按 offset/limit 返回 base64 片段（单页上限 256KB）。contextId 必须与日志行一致；路径越出会话目录、行无资源标记、文件已被 7 天过期清理或会话删除均明确报错。字体/图片/媒体原始字节用它取；文本正文请用 get_http_log。", ToolSchema(properties = buildJsonObject {
        putJsonObject("logId") { put("type", "integer"); put("description", "http_logs 行 id（该行 responseBody 须含 [bodyFile=…] 标记）") }
        putJsonObject("offset") { put("type", "integer"); put("description", "字节偏移，默认 0") }
        putJsonObject("limit") { put("type", "integer"); put("description", "本次最多返回的字节数，默认 65536，最大 262144") }
    }, required = listOf("logId")), toolAnnotations = readOnly) { req ->
        val logId = (req.arguments.int("logId") ?: return@tool err("logId 不能为空")).toLong()
        val offset = (req.arguments.int("offset") ?: 0).coerceAtLeast(0)
        val limit = (req.arguments.int("limit") ?: 65536).coerceIn(1, 262144)
        val log = app.database.dao().httpLog(logId) ?: return@tool err("日志不存在（id=$logId）")
        val ctx = log.contextId ?: return@tool err("该日志不属于抓包会话（contextId 为空）")
        if (!ctx.startsWith("cap:")) return@tool err("该日志不属于 cap: 抓包会话（contextId=$ctx）")
        val resource = com.mina.legadostudio.verification.WebViewCapture.bodyFileRefFrom(log.responseBody)
            ?: return@tool err("该日志没有落盘资源（responseBody 不含 [bodyFile=…] 标记；文本正文用 get_http_log 读取）")
        // 显式防线：资源路径必须解析在该 cap: 会话自己的目录内，越界/穿会话/绝对路径一律拒绝。
        com.mina.legadostudio.verification.WebViewCapture.captureResourcePath(app.cacheDir, ctx, resource)
            ?: return@tool err("资源路径越出该会话目录，拒绝读取（resource=$resource）")
        val chunk = app.webViewCapture.readCaptureResource(ctx, resource, offset, limit)
            ?: return@tool err("资源不可读：文件不在该会话目录内或已被 7 天过期清理/手动删除（resource=$resource）")
        ok(app.gson.toJson(mapOf(
            "logId" to logId, "contextId" to ctx, "resource" to chunk.name,
            "totalBytes" to chunk.totalBytes, "offset" to chunk.offset,
            "bytes" to chunk.bytes.size,
            "base64" to java.util.Base64.getEncoder().encodeToString(chunk.bytes),
            "eof" to chunk.eof,
            "mimeHint" to Regex("""\[binary \d+B ([^\]]+)\]""").find(log.responseBody)?.groupValues?.get(1),
            "truncated" to !chunk.eof,
            "hint" to if (chunk.eof) "已到文件尾" else "还有后续字节：用 offset=${chunk.offset + chunk.bytes.size} 继续读；单次响应≤256KB，文件保留期 7 天，过期即不可读",
        )))
    }
    server.tool("get_http_log_recording", "只读查询当前 HTTP 事务记录开关，不更改设置", ToolSchema(properties = buildJsonObject {}, required = emptyList()), toolAnnotations = readOnly) {
        ok(app.gson.toJson(mapOf("enabled" to app.httpLogs.enabled)))
    }
    server.tool("set_http_log_recording", "启用或停用 HTTP 事务记录", ToolSchema(properties = buildJsonObject { putJsonObject("enabled") { put("type", "boolean") } }, required = listOf("enabled")), toolAnnotations = write) { req ->
        val enabled = req.arguments?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: return@tool err("enabled 必须为布尔值")
        app.httpLogs.enabled = enabled; ok("HTTP 事务记录已${if (enabled) "启用" else "停用"}")
    }
}
