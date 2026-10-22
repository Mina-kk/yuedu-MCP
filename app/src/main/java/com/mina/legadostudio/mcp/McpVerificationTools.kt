package com.mina.legadostudio.mcp

import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpOrigin
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun StudioMcpServer.registerVerificationTools(server: Server) {
    server.tool("browser_verify", "创建站点验证会话（验证码/登录/WAF/CF）。默认 webview 会话（真实页面）；传 imageUrl 时创建 image_code 图文验证码会话（拉图给用户看，等用户回输入）。waitSec=0 立即返回；1..120 阻塞等待用户在验证中心完成；webview 完成后自动取证返回 evidence，image_code 完成后返回用户输入的 answer", schema(mapOf("url" to "验证 URL（image_code 时可为验证码图所在页 URL，用于定域）", "purpose" to "用途说明", "waitSec" to "0..120，阻塞等待完成的最长秒数", "imageUrl" to "可选：验证码图片 URL，传入后创建 image_code 图文验证码会话"), listOf("url")), toolAnnotations = openWrite) { req ->
        runCatching {
            val url = req.arguments.str("url") ?: return@tool err("url 不能为空")
            val imageUrl = req.arguments.str("imageUrl").orEmpty().trim()
            val isImageCode = imageUrl.isNotEmpty()
            val imageData = if (isImageCode) runCatching {
                app.fetchCaptchaImage(imageUrl, HttpOrigin(contextId = contextId(req.arguments), originKind = "browser_verify_captcha"))
            }.getOrElse { return@tool err("验证码图拉取失败：${it.message?.take(200) ?: "未知错误"}；若遇 CF/网络挑战，请先在本手机验证中心过验证后重试；未创建空图会话") } else ""
            val session = app.verification.create("mcp", url, req.arguments.str("purpose") ?: "MCP 请求验证",
                kind = if (isImageCode) "image_code" else "webview", imageData = imageData)
            val waitSec = (req.arguments.int("waitSec") ?: 0).coerceIn(0, 120)
            var row = app.database.dao().verificationSession(session.id)
            var done = row?.status == "COMPLETED"
            var waited = 0
            while (!done && waited < waitSec) {
                kotlinx.coroutines.delay(1_000); waited++
                row = app.database.dao().verificationSession(session.id)
                done = row?.status == "COMPLETED"
            }
            if (!done) {
                val payload = app.gson.toJsonTree(session).asJsonObject
                payload.addProperty("status", if (waited > 0) "wait_timeout" else "WAITING")
                payload.addProperty("waitedSec", waited)
                payload.addProperty("message", if (isImageCode) "验证码图已推送到「验证中心」，等用户看图输入后可用 get_verification_status 轮询取 answer" else StudioMcpServer.VERIFY_MESSAGE + "；稍后可用 get_verification_status 轮询，完成后重试原工具")
                return@tool ok(payload.toString())
            }
            if (isImageCode) {
                return@tool ok(app.gson.toJson(mapOf(
                    "status" to "COMPLETED", "sessionId" to session.id, "kind" to "image_code",
                    "waitedSec" to waited, "answer" to (row?.answer ?: ""),
                    "message" to "用户已输入验证码，answer 即识别结果，可直接拼进请求参数重试",
                )))
            }
            // 完成后用 WebView 通道取证一次，AI 直接拿到验证是否真正生效
            val evidence = runCatching {
                val r = app.pageLoader.load(HttpFetcher.FetchRequest(url = url, method = "GET", timeoutSec = 45, maxBodyBytes = 4_000_000,
                    origin = HttpOrigin(contextId = contextId(req.arguments), originKind = "browser_verify_evidence")))
                mapOf("code" to r.code, "finalUrl" to r.finalUrl, "marker" to app.fetcher.verificationMarker(200, r.finalUrl, r.body))
            }.getOrElse { mapOf("code" to 0, "finalUrl" to url, "marker" to "load_error:" + (it.message?.take(80) ?: "")) }
            ok(app.gson.toJson(mapOf(
                "status" to "COMPLETED", "sessionId" to session.id, "domain" to session.domain,
                "waitedSec" to waited, "evidence" to evidence, "message" to "验证完成并已取证，请重试原工具；若 evidence.marker 非空说明该站每次访问都要求验证，建议 set_domain_mode(domain, ALWAYS)",
            )))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("get_verification_status", "读取 App 内验证会话状态", schema(mapOf("sessionId" to "验证会话 ID"), listOf("sessionId")), toolAnnotations = readOnly) { req ->
        app.database.dao().verificationSession(req.arguments.str("sessionId") ?: return@tool err("sessionId 不能为空"))?.let { ok(app.gson.toJson(it)) } ?: err("验证会话不存在")
    }
    server.tool("get_cookies", "读取指定 URL 所属域的 Runtime Cookie", schema(mapOf("url" to "URL"), listOf("url")), toolAnnotations = readOnly) { req ->
        ok(app.cookieStore.headerFor(req.arguments.str("url") ?: return@tool err("url 不能为空")) ?: "（空）")
    }
    server.tool("set_cookie", "写入指定 URL 所属域的 Runtime/WebView Cookie。默认 merge=true 按 Cookie 名合并，该域其他 Cookie（如登录态）保留；merge=false 整串替换", schema(mapOf("url" to "URL", "cookie" to "name=value; ...", "merge" to "默认 true：按名合并；false=整串替换"), listOf("url", "cookie")), toolAnnotations = write) { req ->
        runCatching {
            val url = req.arguments.str("url") ?: return@tool err("url 不能为空")
            val cookie = req.arguments.str("cookie") ?: return@tool err("cookie 不能为空")
            if (req.arguments.bool("merge") != false) app.cookieStore.merge(url, cookie) else app.cookieStore.set(url, cookie)
            ok("Cookie 已写入")
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("clear_cookies", "清除指定 URL 所属域的 Runtime/WebView Cookie", schema(mapOf("url" to "URL"), listOf("url")), toolAnnotations = destructive) { req ->
        app.cookieStore.clear(req.arguments.str("url") ?: return@tool err("url 不能为空")); ok("Cookie 已清除")
    }
    // 人过 CF/JS 挑战后的一键回填：采当前 WebView CookieManager 的整串（同步落 prefs 桶），
    // 与书源 header 做按名合并（同名覆盖、未点名保留——同 cookieStore.merge 语义）后写回项目。
    // 返回值只回 cookie 名列表与长度，不回显 cookie 串与 header 明文（与日志脱敏口径一致）。
    server.tool("apply_webview_cookies", "人工过完 CF/JS 挑战后，把 WebView 采到的 Cookie（与可选 User-Agent）按名合并进书源 header 并保存。url 用挑战页 URL；projectId 或 bookSourceUrl 至少传一个定位目标书源；includeUa 默认 true", ToolSchema(properties = buildJsonObject {
        put("url", stringProp("挑战页 URL（采 cookie 与定域都用它）"))
        put("projectId", stringProp("目标书源项目 ID（与 bookSourceUrl 至少传一个）"))
        put("bookSourceUrl", stringProp("目标书源 bookSourceUrl（未传 projectId 时按它定位最近一次保存的项目）"))
        putJsonObject("includeUa") { put("type", "boolean"); put("description", "true=同时把 WebView 实际 UA 合并进 header 的 User-Agent，默认 true") }
    }, required = listOf("url")), toolAnnotations = write) { req ->
        runCatching {
            val url = req.arguments.str("url") ?: return@tool err("url 不能为空")
            val cookie = app.cookieStore.captureFromWebView(url)
            if (cookie.isBlank()) {
                return@tool err("未采到 cookie：该 URL 的 WebView Cookie 桶为空，请先用 browser_verify(url, waitSec=90) 让用户在验证中心过挑战，或在「日志→抓包→浏览器抓包」页手动过完挑战后结束会话，再调用本工具")
            }
            val ua = app.runtimeConfig.userAgent
            val projectIdArg = req.arguments.str("projectId").orEmpty().trim()
            val bookSourceUrlArg = req.arguments.str("bookSourceUrl").orEmpty().trim()
            val project = when {
                projectIdArg.isNotEmpty() -> app.projects.get(projectIdArg)
                bookSourceUrlArg.isNotEmpty() -> app.database.dao().projectBySiteUrl(bookSourceUrlArg)
                else -> null
            } ?: return@tool err(
                if (projectIdArg.isEmpty() && bookSourceUrlArg.isEmpty()) "必须传 projectId 或 bookSourceUrl 之一来定位目标书源"
                else "未找到对应书源项目（projectId/bookSourceUrl 无匹配，先用 list_sources 确认）"
            )
            val root = runCatching { JsonParser.parseString(project.sourceJson).asJsonObject }
                .getOrNull() ?: return@tool err("项目内书源不是合法 JSON 对象，无法用工具回填；请先用 save_source 修正后再试")
            // 与 sourceHeaders/sanitizeTransportHeaders 同款形态兼容：header 可能是 JsonObject 或 JSON 字符串
            val headerRaw = root.get("header")
            val headerObj = when {
                headerRaw?.isJsonObject == true -> headerRaw.asJsonObject.deepCopy()
                headerRaw?.isJsonPrimitive == true -> runCatching { JsonParser.parseString(headerRaw.asString).asJsonObject }.getOrNull()
                else -> null
            } ?: com.google.gson.JsonObject()
            // Cookie 按名合并：已有 header 的同名 cookie 被覆盖，未点名的保留（对齐 cookieStore.merge）
            val pairs = linkedMapOf<String, String>()
            fun absorbCookie(raw: String) = raw.split(';').map { it.trim() }.filter { it.contains('=') }
                .forEach { pairs[it.substringBefore('=').trim()] = it.substringAfter('=').trim() }
            headerObj.get("Cookie")?.takeIf { it.isJsonPrimitive }?.asString?.let(::absorbCookie)
            absorbCookie(cookie)
            headerObj.addProperty("Cookie", pairs.entries.joinToString("; ") { "${it.key}=${it.value}" })
            val includeUa = req.arguments.bool("includeUa") ?: true
            if (includeUa) headerObj.addProperty("User-Agent", ua)
            if (headerRaw?.isJsonPrimitive == true) root.addProperty("header", headerObj.toString()) else root.add("header", headerObj)
            app.projects.save(project.copy(sourceJson = app.gson.toJson(root), updatedAt = System.currentTimeMillis()))
            val domain = com.mina.legadostudio.verification.DomainKey.fromUrl(url)
            val lastVerifiedAt = app.database.dao().latestVerification("mcp", domain)?.updatedAt
            ok(app.gson.toJson(mapOf(
                "applied" to true,
                "domain" to domain,
                "cookieNames" to pairs.keys.toList(),
                "cookieLen" to cookie.length,
                "ua" to ua,
                "uaApplied" to includeUa,
                "projectId" to project.id,
                "bookSourceUrl" to project.siteUrl,
                "lastVerifiedAt" to lastVerifiedAt,
            )))
        }.getOrElse { err(it.message.orEmpty()) }
    }
}
