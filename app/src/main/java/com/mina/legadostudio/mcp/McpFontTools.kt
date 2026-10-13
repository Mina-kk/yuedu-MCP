package com.mina.legadostudio.mcp

import com.google.gson.JsonParser
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpOrigin
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun StudioMcpServer.registerFontTools(server: Server) {
    server.tool("fetch_font", "抓取字体资源（woff/woff2/ttf/otf）为二进制并解出 cmap。与 capture_once 同级 SSRF 防护：仅公网 http(s)、入口+重定向逐跳+DNS 三层校验、内嵌凭据拒绝；字体 Content-Type 或字体扩展名+魔数(wOF2/wOFF/TTF/OTTO) 才会放行，其余一律拒绝。成功后把字体字节存入本 contextId 的内存 fontId 槽（不写磁盘快照、不经日志正文），返回 fontId+cmap 摘要；完整映射用 get_font_map(fontId, offset/limit) 分页取。响应正文不返回、也不可通过 read_page 读取。注意：cmap 给的是码点→glyphId，glyphId 不等于汉字，gid→汉字的还原要字形比对。", ToolSchema(properties = buildJsonObject {
        put("url", stringProp("字体文件 URL（仅公网 http(s)）"))
        put("headers", stringProp("可选请求头 JSON 对象或字符串，如 {\"Referer\":\"https://site/\"}；防盗链站点用"))
        putJsonObject("timeoutSec") { put("type", "integer"); put("description", "5..120 秒，默认 30") }
        putJsonObject("decode") { put("type", "boolean"); put("description", "默认 true：取回后立即解 cmap；false=只存 fontId 不解析") }
    }, required = listOf("url")), toolAnnotations = openWrite) { req ->
        runCatching {
            val url = req.arguments.str("url") ?: error("url 不能为空")
            require(url.startsWith("http://") || url.startsWith("https://")) { "仅支持 HTTP/HTTPS" }
            require(url.length <= 8192) { "url 过长" }
            require(url.toHttpUrlOrNull()?.let { it.username.isEmpty() && it.password.isEmpty() } == true) { "URL 不允许内嵌用户名/密码" }
            require(!com.mina.legadostudio.domain.LogFilterUtils.isLoopbackOrPrivate(url)) { "目标为回环/私网/保留地址，仅允许公网字体" }
            val ctxId = contextId(req.arguments)
            // 锚点：显式 sourceAnchor > 字体 URL 自身域兜底（不回填 contextId 旧锚点：裸请求不做推测归属）
            val anchor = inheritedAnchor(req.arguments, ctxId)
                ?: runCatching { com.mina.legadostudio.domain.LogFilterUtils.extractPrimaryDomain(url) }.getOrNull()
            val origin = HttpOrigin(sourceAnchor = anchor, contextId = ctxId, originKind = "mcp_fetch_font")
            // 可选请求头（防盗链 Referer 等）：接受 JSON 对象字符串；键名/值限制长度，禁止注入 Cookie
            // （会话 Cookie 由 cookieJar/cookieHeaderProvider 管，避免覆盖逃逸出指纹域）。
            val fontHeaders = req.arguments.str("headers")?.let { raw ->
                val obj = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull()
                    ?: error("headers 必须是 JSON 对象字符串，如 {\"Referer\":\"https://x/\"}")
                obj.entrySet().associate { (k, v) ->
                    require(k.length <= 64 && v.asString.length <= 2048) { "headers 键值过长" }
                    require(!k.equals("Cookie", true) && !k.equals("Authorization", true) && !k.equals("Accept-Encoding", true)) {
                        "headers 不允许覆盖 $k（凭据/编码由沙箱托管）"
                    }
                    k to v.asString
                }
            }.orEmpty()
            val request = HttpFetcher.FetchRequest(
                url = url, method = "GET", headers = fontHeaders,
                timeoutSec = (req.arguments.int("timeoutSec") ?: 30).coerceIn(5, 120),
                maxBodyBytes = HttpFetcher.MAX_FONT_BYTES, allowFontBinary = true, origin = origin,
            )
            val redirectGuard: (String) -> String? = { target ->
                val parsed = target.toHttpUrlOrNull()
                when {
                    parsed == null -> "重定向目标不是有效的 HTTP(S) 地址"
                    parsed.username.isNotEmpty() || parsed.password.isNotEmpty() -> "重定向目标内嵌凭据"
                    com.mina.legadostudio.domain.LogFilterUtils.isLoopbackOrPrivate(target) -> "重定向目标为回环/私网/保留地址"
                    else -> null
                }
            }
            val dnsGuard: (java.net.InetAddress) -> String? = { addr ->
                if (com.mina.legadostudio.domain.LogFilterUtils.isLoopbackOrPrivate(addr.hostAddress.orEmpty()))
                    "解析到私网/回环地址 ${addr.hostAddress}" else null
            }
            val fetcher = HttpFetcher(
                cookieHeaderProvider = { u -> app.cookieStore.headerFor(u) },
                cookieJar = app.sessionCookieJar,
                logRecorder = app.httpLogs,
                userAgentProvider = { app.runtimeConfig.userAgent },
                sourceTypeProvider = { app.runtimeConfig.bookSourceType },
                unsafeRedirectGuard = redirectGuard,
                unsafeDnsGuard = dnsGuard,
            )
            val result = withContext(Dispatchers.IO) { fetcher.fetch(request) }
            // 只认成功响应：3xx/4xx/5xx 即使正文碰巧带字体魔数也拒（防盗链空跳转/错误页）
            require(result.code in 200..299) { "字体请求返回 ${result.code}，非成功响应" }
            // rawBytes 在非字体路径也可能是正文字节：消费端魔数终检，Content-Type/扩展名双撒谎时拒绝
            val fontBytes = result.rawBytes?.takeIf { result.body.isEmpty() && com.mina.legadostudio.network.Woff2Decoder.looksLikeFont(it) }
                ?: error("响应未放行字体二进制（FONT_NOT_FONT）：${result.bodyNote.take(160)}")
            val decode = req.arguments.bool("decode") ?: true
            val decoded = if (decode) runCatching { com.mina.legadostudio.network.Woff2Decoder.decode(fontBytes) }.getOrElse { throw it } else null
            val entry = contexts.saveBinary(ctxId, fontBytes, contextFingerprint(), label = result.finalUrl)
            ok(app.gson.toJson(mapOf(
                "contextId" to ctxId, "fontId" to entry.id,
                "code" to result.code, "finalUrl" to result.finalUrl, "elapsedMs" to result.elapsedMs,
                "bytes" to fontBytes.size,
                "sha256" to TaskContextStore.digest(fontBytes),
                "cmapFormat" to (decoded?.cmapFormat ?: 0),
                "mappings" to (decoded?.mappings?.size ?: 0),
                "note" to ("字体字节仅存内存 fontId 槽（不入 HTTP 日志正文、不入磁盘快照）；cmap 是码点→glyphId，glyphId≠汉字，gid→汉字需要字形比对；完整映射用 get_font_map(fontId=${entry.id}, offset, limit) 分页"),
            )))
        }.getOrElse { verificationAware(it) }
    }
    server.tool("get_font_map", "按 fontId 分页读取字体的 cmap 映射（U+XXXX→glyphId，按码点排序）。glyphId 不等于汉字；gid→汉字需要字形比对。只读不落盘。", ToolSchema(properties = buildJsonObject {
        put("fontId", stringProp("fetch_font 返回的字体引用 ID"))
        putJsonObject("offset") { put("type", "integer"); put("minimum", 0) }
        putJsonObject("limit") { put("type", "integer"); put("description", "1..2000 行，默认 500") }
    }, required = listOf("fontId")), toolAnnotations = readOnly) { req ->
        runCatching {
            val ctxId = contextId(req.arguments)
            val fontId = req.arguments.str("fontId") ?: error("fontId 不能为空")
            // 解码+排序结果在 TaskContextStore 按 entryId 缓存：分页多次取不重复跑 Brotli/全量排序
            val (decoded, lines) = contexts.binaryDecoded(ctxId, fontId, contextFingerprint())
            val offset = (req.arguments.int("offset") ?: 0).coerceIn(0, lines.size)
            val limit = (req.arguments.int("limit") ?: 500).coerceIn(1, 2000)
            val page = lines.drop(offset).take(limit)
            ok(app.gson.toJson(mapOf(
                "fontId" to fontId, "contextId" to ctxId,
                "cmapFormat" to decoded.cmapFormat, "total" to lines.size,
                "offset" to offset, "limit" to limit, "hasMore" to (offset + page.size < lines.size),
                "lines" to page,
                "hint" to "每行 'U+XXXX<TAB>gid'；gid 是字形序号不是汉字，比对字形轮廓才能还原文字",
            )))
        }.getOrElse { err(it.message.orEmpty()) }
    }
}
