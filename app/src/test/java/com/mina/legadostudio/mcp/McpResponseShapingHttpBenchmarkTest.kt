package com.mina.legadostudio.mcp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mina.legadostudio.network.HttpFetcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响应成形的可复现基准（本步只测量，不优化）。
 *
 * 传输：本机 CIO HTTP 真实 loopback + 生产 configureStudioMcp；fixture 是内存 Server，
 * 工具名 bench_*（合成 —— 不代表 Android 端 67 个真实工具逐一实测）。正文数据经真实
 * TaskContextStore（固定 clock/fingerprint、无 snapshotDir）+ 计数 loader 注入合成
 * FetchResult；成形走生产同款 McpResponsePayloads + McpToolUsage.track。
 *
 * 序列化与生产完全一致：GsonBuilder().setPrettyPrinting().disableHtmlEscaping()，
 * 否则 HTML 转义/缩进差异会让字节数失真。
 *
 * 每次 tools/call 都核对：envelope 的 jsonrpc/id/result-or-error；返回 text 的 UTF-16
 * 字符数与 UTF-8 字节数与 usage 中该工具名增量的精确差分（不是混合用例的总量核对）。
 * 输出仅为 println 的 BASELINE 单行（进 Gradle XML system-out），是既有机制的基线，
 * 不是本步新优化收益。
 */
class McpResponseShapingHttpBenchmarkTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val schema = ToolSchema(properties = buildJsonObject {}, required = emptyList())

    /** 与 StudioApplication.kt:50 完全相同的 Gson 配置（未 serializeNulls）。 */
    private val appGson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** fixture 内模拟生产 boundedResult 判定用的受限名单；不改主 BOUNDED_TOOLS。 */
    private val benchBounded = setOf("bench_result", "bench_err")

    private val smallHtml = "<html><body><h1>样例</h1><p>短页面。</p></body></html>"
    private val longCjkHtml = "<html><body>" +
        "<p>第壹章 长中文段落，用于测量 CJK 正文的首屏预览与缓存命中差异。</p>".repeat(300) +
        "</body></html>"
    private val bigJson = buildString {
        append("{\"items\":[")
        repeat(400) { append("{\"id\":$it,\"name\":\"元素$it\",\"desc\":\"合成测试数据\"},") }
        append("{}]}")
    }
    private val resultSuffix = "FIN-DU-FLUX"

    init {
        // 合成正文的确定性前置条件：大 CJK/JSON 必须真的超过 12000 阈值
        assertTrue("longCjkHtml must exceed limit", longCjkHtml.length > 12000)
        assertTrue("bigJson must exceed limit", bigJson.length > 12000)
    }

    /** 按准确请求长度生成结果正文：重复前缀 + 定长尾缀，总长 == size。 */
    private fun sizedResultText(size: Int): String {
        require(size >= resultSuffix.length) { "size 必须不小于尾缀长度" }
        return "r".repeat(size - resultSuffix.length) + resultSuffix
    }

    private data class Bench(
        val store: TaskContextStore,
        val usage: McpToolUsage,
        val loaderCalls: AtomicInteger,
        val bodies: Map<String, String>,
    )

    private fun newBench(): Bench = Bench(
        store = TaskContextStore(clock = { 60_000L }),
        usage = McpToolUsage(),
        loaderCalls = AtomicInteger(),
        bodies = mapOf(
            "https://bench.local/small" to smallHtml,
            "https://bench.local/long" to longCjkHtml,
            "https://bench.local/bigjson" to bigJson,
        ),
    )

    /** 复刻生产 fetchPageOk 的成形部分：metadata + preview 选择 + flags，经同一 helper。 */
    private fun pageResult(b: Bench, ctx: String, hit: TaskContextStore.Hit,
        mode: String, maxChars: Int): CallToolResult {
        val e = hit.entry
        val r = e.page!!
        val payload = McpResponsePayloads.fetchPagePayload(
            b.store.metadata(e), ctx, e.id, hit.reused,
            r.code, r.finalUrl, r.elapsedMs, r.bodyNote, r.binaryBytes,
            McpResponsePayloads.pagePreview(e.text, mode, hit.reused, maxChars), e.text.length,
            false, null, null, emptyMap(),
        )
        return CallToolResult(listOf(TextContent(appGson.toJson(payload))))
    }

    /** 复刻生产 boundedResult 的成形部分：同 needsResultReference 判定 → saveResult + 引用 payload。 */
    private suspend fun boundedFixture(b: Bench, ctx: String, name: String, result: CallToolResult): CallToolResult {
        if (!McpResponsePayloads.needsResultReference(name, result, benchBounded)) return result
        val text = (result.content.single() as TextContent).text
        val e = b.store.saveResult(ctx, text, FINGERPRINT)
        val payload = McpResponsePayloads.resultReferencePayload(b.store.metadata(e), ctx, e.id, text)
        return CallToolResult(listOf(TextContent(appGson.toJson(payload))))
    }

    /**
     * compact 即生产 StudioMcpServer(compact=...) 的连接标记：由 initialize URL 的 ?compact=1
     * 经 serverFactory 读入（见 httpBench 中 queryParameters 取值，同 McpService），fixture 内
     * bench_page 的缺省预览长度走生产同款 McpResponsePayloads.pagePreviewLimit。
     */
    private fun fixture(b: Bench, compact: Boolean = false): Server = Server(
        serverInfo = Implementation("bench-fixture", "1"),
        options = ServerOptions(capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false),
        )),
    ).also { server ->
        suspend fun tracked(name: String, block: suspend () -> CallToolResult) =
            b.usage.track(name) { block() }

        // bench_page：store.fetch（缓存键）→ 生产同款预览/payload。校验与生产一致。
        server.addTool(Tool(name = "bench_page", description = "fetch+shape page", inputSchema = schema)) { req ->
            tracked("bench_page") {
                val args = req.arguments
                val url = McpArgs.str(args, "url") ?: "https://bench.local/small"
                val mode = McpArgs.str(args, "mode") ?: "auto"
                require(mode in setOf("auto", "preview", "reference")) { "responseMode 必须为 auto/preview/reference" }
                val maxChars = McpResponsePayloads.pagePreviewLimit(compact, mode, McpArgs.int(args, "maxChars"))
                require(maxChars in 0..12000) { "maxChars 必须为 0..12000" }
                val ctx = McpArgs.str(args, "contextId") ?: b.store.create("bench")
                val body = b.bodies.getValue(url)
                val hit = b.store.fetch(ctx, "GET\n$url\n", FINGERPRINT, reusable = true, refresh = false) {
                    b.loaderCalls.incrementAndGet()
                    HttpFetcher.FetchResult(200, url, mapOf("content-type" to "text/html"), body, 42L)
                }
                pageResult(b, ctx, hit, mode, maxChars)
            }
        }

        // bench_result：按准确长度生成结果正文，走生产同款 needsResultReference 判定。
        server.addTool(Tool(name = "bench_result", description = "sized result→maybe reference", inputSchema = schema)) { req ->
            tracked("bench_result") {
                val ctx = McpArgs.str(req.arguments, "contextId") ?: b.store.create("bench")
                val size = McpArgs.int(req.arguments, "size") ?: 15000
                val raw = CallToolResult(listOf(TextContent(sizedResultText(size))))
                boundedFixture(b, ctx, "bench_result", raw)
            }
        }

        // bench_read：按条目 id 分页读回正文（page/result 通用，走生产 contexts.read 路径）。
        server.addTool(Tool(name = "bench_read", description = "paged read of stored entry", inputSchema = schema)) { req ->
            tracked("bench_read") {
                val ctx = McpArgs.str(req.arguments, "contextId") ?: error("contextId 不能为空")
                val entryId = McpArgs.str(req.arguments, "id") ?: error("id 不能为空")
                val offset = McpArgs.int(req.arguments, "offset") ?: 0
                val e = b.store.get(ctx, entryId, FINGERPRINT)
                CallToolResult(listOf(TextContent(appGson.toJson(b.store.read(e, offset, 12000)))))
            }
        }

        // bench_err：在受限名单内但 isError=true —— 生产判定必须豁免（不得转引用/不得裁切）。
        server.addTool(Tool(name = "bench_err", description = "isError passthrough", inputSchema = schema)) { req ->
            tracked("bench_err") {
                val size = McpArgs.int(req.arguments, "size") ?: 12500
                val errText = "失败明细:" + "x".repeat(size - 5)
                val raw = CallToolResult(listOf(TextContent(errText)), isError = true)
                boundedFixture(b, "unused-ctx", "bench_err", raw)
            }
        }

        // bench_raw：不在受限名单内 —— 即便超长也原样返回（identity 策略，不裁切）。
        server.addTool(Tool(name = "bench_raw", description = "non-bounded long passthrough", inputSchema = schema)) { req ->
            tracked("bench_raw") {
                val size = McpArgs.int(req.arguments, "size") ?: 15000
                val raw = CallToolResult(listOf(TextContent(sizedResultText(size))))
                boundedFixture(b, "unused-ctx", "bench_raw", raw)
            }
        }
    }

    private data class Harness(
        val client: HttpClient,
        val port: Int,
        val servers: CopyOnWriteArrayList<Server>,
        val json: Json,
        val basePath: String = "/mcp",
    ) {
        var session: String? = null

        suspend fun post(body: String): Pair<Int, String> {
            val response = client.post("http://127.0.0.1:$port$basePath") {
                header(HttpHeaders.Host, "127.0.0.1:$port")
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                header(HttpHeaders.ContentType, "application/json")
                header("MCP-Protocol-Version", "2025-11-25")
                session?.let { header("Mcp-Session-Id", it) }
                setBody(body)
            }
            return response.status.value to response.bodyAsText()
        }

        suspend fun startSession() {
            val response = client.post("http://127.0.0.1:$port$basePath") {
                header(HttpHeaders.Host, "127.0.0.1:$port")
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"bench","version":"1"}}}""")
            }
            assertEquals(200, response.status.value)
            session = response.headers["Mcp-Session-Id"] ?: error("no session header")
            val (status, _) = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            assertEquals(202, status)
        }

        /** 严格 envelope：恰好一个同 id 的 JSON-RPC 2.0 回包，且 result/error 二选一。 */
        fun envelope(body: String, id: Int): JsonObject {
            val trimmed = body.trim()
            val payloads = if (trimmed.startsWith("{")) listOf(trimmed) else {
                val events = mutableListOf<String>(); val data = mutableListOf<String>()
                for (line in (body.replace("\r\n", "\n") + "\n\n").lineSequence()) {
                    if (line.isEmpty()) { if (data.isNotEmpty()) events.add(data.joinToString("\n")); data.clear() }
                    else if (line.startsWith("data:")) data.add(line.removePrefix("data:").removePrefix(" "))
                }
                events
            }
            val matches = payloads.map { json.parseToJsonElement(it).jsonObject }
                .filter { it["id"] == JsonPrimitive(id) && (it.containsKey("result") || it.containsKey("error")) }
            assertEquals("expected one envelope for id=$id: $body", 1, matches.size)
            val env = matches.single()
            assertEquals("jsonrpc must be 2.0", JsonPrimitive("2.0"), env["jsonrpc"])
            assertEquals("id must echo", JsonPrimitive(id), env["id"])
            assertEquals("exactly one of result/error",
                1, listOf(env.containsKey("result"), env.containsKey("error")).count { it })
            return env
        }

        /** tools/call → result 对象（调用方自行取 text/isError）。 */
        suspend fun callResult(id: Int, name: String, args: String = "{}"): JsonObject {
            val (status, body) = post(
                """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}""")
            assertEquals("id=$id body=$body", 200, status)
            return envelope(body, id).getValue("result").jsonObject
        }
    }

    /** 某工具在 usage 中已记录的 (textChars, textBytesUtf8) 快照；未记录视为 0。 */
    private fun usageStat(b: Bench, name: String): Pair<Long, Long> =
        b.usage.topByTextBytes(100).toMap()[name]
            ?.let { it["textChars"] as Long to it["textBytesUtf8"] as Long } ?: (0L to 0L)

    private fun usageCalls(b: Bench): Long {
        @Suppress("UNCHECKED_CAST")
        return (b.usage.snapshot()["summary"] as Map<String, Any>)["calls"] as Long
    }

    /** 单条基线输出（进 stdout/XML；只量既有机制的文本体积，不宣称本轮收益）。 */
    private fun report(caseName: String, text: String, b: Bench, extra: String = "") {
        println("BASELINE $caseName | chars=${text.length} | bytesUtf8=${text.toByteArray(Charsets.UTF_8).size} | usageCalls=${usageCalls(b)} | loaderCalls=${b.loaderCalls.get()}${if (extra.isNotEmpty()) " | $extra" else ""}")
    }

    /**
     * 发起一次 tools/call 并做逐调用核对：返回 text 的 UTF-16/UTF-8 尺寸必须精确等于
     * usage 中该工具名的增量；envelope 已由 callResult/envelope 严格校验。
     */
    private suspend fun Harness.callChecked(b: Bench, id: Int, name: String, args: String = "{}"): JsonObject {
        val (c0, b0) = usageStat(b, name)
        val result = callResult(id, name, args)
        val text = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        val (c1, b1) = usageStat(b, name)
        assertEquals("usage textChars delta for $name", text.length.toLong(), c1 - c0)
        assertEquals("usage textBytesUtf8 delta for $name",
            text.toByteArray(Charsets.UTF_8).size.toLong(), b1 - b0)
        return result
    }

    private fun JsonObject.text() =
        getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content

    private fun JsonObject.field(name: String) =
        json.parseToJsonElement(text()).jsonObject.getValue(name)

    private fun JsonObject.str(name: String) = field(name).jsonPrimitive.content
    private fun JsonObject.bool(name: String) = field(name).jsonPrimitive.content == "true"

    private fun httpBench(basePath: String = "/mcp", block: suspend Harness.(Bench) -> Unit) = runBlocking {
        val servers = CopyOnWriteArrayList<Server>()
        val b = newBench()
        val client = HttpClient(ClientCIO) {
            expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = 5_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 5_000 }
        }
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            configureStudioMcp(
                configProvider = { McpConfigStore.Config(1237, false, "") },
                allowedHosts = listOf("127.0.0.1", "localhost"),
                allowedOrigins = listOf("http://127.0.0.1:1237", "http://localhost:1237"),
                // 与 McpService 同规则：initialize URL 的 ?compact=1 决定本连接 fixture 的 compact 标记
            ) { fixture(b, compact = call.request.queryParameters[McpToolCompactor.QUERY_PARAM] == McpToolCompactor.QUERY_VALUE).also { servers.add(it) } }
        }
        try {
            withTimeout(60_000) {
                engine.start(wait = false)
                val port = engine.engine.resolvedConnectors().single().port
                Harness(client, port, servers, json, basePath).block(b)
            }
        } finally {
            client.close()
            try { withContext(NonCancellable) { withTimeout(5_000) { servers.forEach { it.close() } } } }
            finally { engine.stop(100, 500) }
        }
    }

    @Test(timeout = 90_000) fun smallPageFirstAndCacheHitBaseline() = httpBench { b ->
        startSession()
        val ctx = b.store.create("bench-ctx")
        // 首次 auto：miss → loader+1，body=6000 内预览
        val r1 = callChecked(b, 2, "bench_page", """{"url":"https://bench.local/small","contextId":"$ctx","mode":"auto"}""")
        assertEquals(1, b.loaderCalls.get())
        assertFalse(r1.bool("cacheHit"))
        assertTrue(r1.bool("networkRequest"))
        assertEquals(smallHtml, r1.str("body"))
        assertEquals(smallHtml.length, r1.str("nextOffset").toInt())
        assertEquals(false, r1.bool("truncated"))
        val pageId1 = r1.str("pageId")
        report("page-small-first-auto", r1.text(), b)

        // 同键二次 auto：缓存命中 → loader 不变、pageId 相同、body 空
        val r2 = callChecked(b, 3, "bench_page", """{"url":"https://bench.local/small","contextId":"$ctx","mode":"auto"}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals(pageId1, r2.str("pageId"))
        assertTrue(r2.bool("cacheHit"))
        assertEquals("", r2.str("body"))
        assertTrue(r2.bool("bodyOmitted"))
        assertEquals(0, r2.str("nextOffset").toInt())
        report("page-small-second-auto-cache", r2.text(), b)
    }

    @Test(timeout = 90_000) fun longCjkPageBaseline() = httpBench { b ->
        startSession()
        val ctx = b.store.create("bench-ctx")
        // 长中文 HTML 首次 auto：6000 字符预览、truncated、totalChars 与正文一致
        val r1 = callChecked(b, 2, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"auto"}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals(6000, r1.str("body").length)
        assertEquals(longCjkHtml.take(6000), r1.str("body"))
        assertTrue(r1.bool("truncated"))
        assertEquals(longCjkHtml.length, r1.str("totalChars").toInt())
        val pageId = r1.str("pageId")
        report("page-longcjk-first-auto", r1.text(), b, "totalChars=${longCjkHtml.length}")

        // 二次 auto 命中：body 空、loader 仍为 1、pageId 相同
        val r2 = callChecked(b, 3, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"auto"}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals(pageId, r2.str("pageId"))
        assertEquals("", r2.str("body"))
        assertTrue(r2.bool("bodyOmitted"))
        report("page-longcjk-second-auto-cache", r2.text(), b)

        // reference 模式：body 恒空，但全文仍可由 store.get/read 完整还原
        val r3 = callChecked(b, 4, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"reference"}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals("", r3.str("body"))
        assertTrue(r3.bool("bodyOmitted"))
        report("page-longcjk-reference", r3.text(), b)

        val entry = b.store.get(ctx, pageId, FINGERPRINT)
        assertEquals(longCjkHtml, entry.text)
        val restored = StringBuilder()
        var off = 0
        while (off < entry.text.length) {
            val page = b.store.read(entry, off, 12000)
            restored.append(page["body"] as String)
            off = page["nextOffset"] as Int
        }
        assertEquals(longCjkHtml, restored.toString())
    }

    @Test(timeout = 90_000) fun bigJsonModesBaseline() = httpBench { b ->
        startSession()
        val ctx = b.store.create("bench-ctx")
        // auto 首次：6000 预览
        val r1 = callChecked(b, 2, "bench_page", """{"url":"https://bench.local/bigjson","contextId":"$ctx","mode":"auto"}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals(6000, r1.str("body").length)
        assertTrue(r1.bool("truncated"))
        assertEquals(bigJson.length, r1.str("totalChars").toInt())
        val pageId = r1.str("pageId")
        report("page-bigjson-first-auto", r1.text(), b, "totalChars=${bigJson.length}")

        // preview + maxChars=0：body 空（缓存仍命中，loader 不变）
        val r2 = callChecked(b, 3, "bench_page", """{"url":"https://bench.local/bigjson","contextId":"$ctx","mode":"preview","maxChars":0}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals("", r2.str("body"))
        assertTrue(r2.bool("bodyOmitted"))
        report("page-bigjson-preview0", r2.text(), b)

        // reference：body 空
        val r3 = callChecked(b, 4, "bench_page", """{"url":"https://bench.local/bigjson","contextId":"$ctx","mode":"reference"}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals("", r3.str("body"))
        report("page-bigjson-reference", r3.text(), b)

        // 全文经 store.get/read 还原（分页拼接回原始 JSON 串）
        val entry = b.store.get(ctx, pageId, FINGERPRINT)
        assertEquals(bigJson, entry.text)
        val restored = StringBuilder()
        var off = 0
        while (off < entry.text.length) {
            val page = b.store.read(entry, off, 12000)
            restored.append(page["body"] as String)
            off = page["nextOffset"] as Int
        }
        assertEquals(bigJson, restored.toString())
    }

    @Test(timeout = 90_000) fun resultReferencePagingBaseline() = httpBench { b ->
        startSession()
        val ctx = b.store.create("bench-ctx")
        val size = 25000
        // >12000 → needsResultReference 命中：resultId + 3000 预览 + 原 hint
        val r1 = callChecked(b, 2, "bench_result", """{"contextId":"$ctx","size":$size}""")
        val payload = json.parseToJsonElement(r1.text()).jsonObject
        val resultId = payload.getValue("resultId").jsonPrimitive.content
        assertEquals(3000, payload.getValue("preview").jsonPrimitive.content.length)
        assertEquals(size, payload.getValue("resultChars").jsonPrimitive.content.toInt())
        assertEquals(size, payload.getValue("totalChars").jsonPrimitive.content.toInt())
        assertEquals(3000, payload.getValue("nextOffset").jsonPrimitive.content.toInt())
        assertEquals("true", payload.getValue("hasMore").jsonPrimitive.content)
        report("result-reference-save", r1.text(), b, "resultChars=$size")

        // bench_read 分页读回 → 与生成原文逐字符一致（含尾缀）
        val expected = sizedResultText(size)
        val sb = StringBuilder(); var off = 0; var pages = 0
        while (true) {
            pages++
            val rp = callChecked(b, 2 + pages, "bench_read",
                """{"contextId":"$ctx","id":"$resultId","offset":$off}""")
            val pm = json.parseToJsonElement(rp.text()).jsonObject
            sb.append(pm.getValue("body").jsonPrimitive.content)
            off = pm.getValue("nextOffset").jsonPrimitive.content.toInt()
            if (pm.getValue("hasMore").jsonPrimitive.content != "true") break
            assertTrue("paging loop bound", pages < 10)
        }
        assertEquals(expected, sb.toString())
        report("result-paged-restore", sb.toString(), b, "pages=$pages restoredChars=${sb.length}")

        // ≤12000 的结果：needsResultReference 不命中，原样返回（HTTP 层证明非引用路径）
        val r2 = callChecked(b, 20, "bench_result", """{"contextId":"$ctx","size":12000}""")
        val p2 = r2.text()
        assertEquals(12000, p2.length)
        assertEquals(sizedResultText(12000), p2)
        assertFalse("no resultId on passthrough", p2.contains("resultId"))
        report("result-12000-passthrough", p2, b)
    }

    @Test(timeout = 90_000) fun errorAndNonBoundedPassthroughBaseline() = httpBench { b ->
        startSession()
        // 受限名单内的错误结果：isError 豁免 —— 原样返回，envelope 保 isError=true，不裁切
        val errText = "失败明细:" + "x".repeat(12500 - 5)
        val r1 = callChecked(b, 2, "bench_err", """{"size":12500}""")
        assertEquals(JsonPrimitive(true), r1["isError"])
        assertEquals(errText, r1.text())
        report("error-12500-passthrough", r1.text(), b)

        // 非受限工具：即便超过 12000 也 identity 返回完整原文（不转引用、不裁切）
        val size = 15000
        val r2 = callChecked(b, 3, "bench_raw", """{"size":$size}""")
        assertNotEquals(JsonPrimitive(true), r2["isError"])
        assertEquals(sizedResultText(size), r2.text())
        report("nonbounded-15000-passthrough", r2.text(), b)
    }

    /**
     * compact 会话（真实 /mcp?compact=1 initialize URL，经 factory query 读取）下长中文页
     * 首次 auto 缺省预览 2000：返回 text 的 UTF-16 与 UTF-8 均小于标准会话的 6000；
     * 全文仍经 store.get/read 分页逐字还原；pageId/contextId 只核对非空（UUID 不要求相同）。
     */
    @Test(timeout = 90_000) fun compactAutoPreviewIsSmallerAndStoreRestores() = httpBench("/mcp?compact=1") { b ->
        startSession()
        val ctx = b.store.create("bench-ctx")
        val r1 = callChecked(b, 2, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"auto"}""")
        assertEquals(1, b.loaderCalls.get())
        assertFalse(r1.bool("cacheHit"))
        // compact auto 缺省：2000 预览截断，非 6000
        assertEquals(2000, r1.str("body").length)
        assertEquals(longCjkHtml.take(2000), r1.str("body"))
        assertTrue(r1.bool("truncated"))
        assertEquals(longCjkHtml.length, r1.str("totalChars").toInt())
        assertEquals(2000, r1.str("nextOffset").toInt())
        val pageId = r1.str("pageId")
        assertTrue("pageId 非空", pageId.isNotBlank())
        assertTrue("contextId 非空", r1.str("contextId").isNotBlank())
        val compactText = r1.text()
        report("compact-page-longcjk-first-auto", compactText, b, "totalChars=${longCjkHtml.length}")

        // 缓存命中：compact 下 limit 仍取 2000，但 body 输出空且 loader 不变
        val r2 = callChecked(b, 3, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"auto"}""")
        assertEquals(1, b.loaderCalls.get())
        assertTrue(r2.bool("cacheHit"))
        assertEquals("", r2.str("body"))
        assertTrue(r2.bool("bodyOmitted"))
        report("compact-page-longcjk-second-auto-cache", r2.text(), b)

        // reference：body 恒空
        val r3 = callChecked(b, 4, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"reference"}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals("", r3.str("body"))
        report("compact-page-longcjk-reference", r3.text(), b)

        // 全文逐字还原：经真实 HTTP bench_read 分页读回（共享 contexts.read 路径），
        // 有界循环 + 每页 nextOffset 持续向前，拼回后与原始正文一致
        val entry = b.store.get(ctx, pageId, FINGERPRINT)
        assertEquals(longCjkHtml, entry.text)
        val restored = StringBuilder()
        var off = 0
        var pages = 0
        while (true) {
            pages++
            val rp = callChecked(b, 10 + pages, "bench_read",
                """{"contextId":"$ctx","id":"$pageId","offset":$off}""")
            val pm = json.parseToJsonElement(rp.text()).jsonObject
            restored.append(pm.getValue("body").jsonPrimitive.content)
            val next = pm.getValue("nextOffset").jsonPrimitive.content.toInt()
            assertTrue("nextOffset 必须持续向前", next > off || pm.getValue("hasMore").jsonPrimitive.content != "true")
            off = next
            if (pm.getValue("hasMore").jsonPrimitive.content != "true") break
            assertTrue("paging loop bound", pages < 10)
        }
        assertEquals(longCjkHtml, restored.toString())
        println("COMPACT-VS-DEFAULT compactUtf8=${compactText.toByteArray(Charsets.UTF_8).size} restoredPages=$pages")
    }

    /**
     * 标准 vs compact 双会话对照：同一引擎上 /mcp 与 /mcp?compact=1 各自 initialize，
     * 同一份输入各自首抓（不同 ctx），真实比较返回 text 体积；loader 各 1 次、调用结构一致。
     */
    @Test(timeout = 90_000) fun compactVsDefaultFirstAutoByteSize() = httpBench { b ->
        // loader 只在 store.fetch 缓存 miss 时被调用；initialize/initialized 不触发 fetch。
        // 缓存条目按 task(ctx) 隔离，故「每 ctx+URL 首次调用」恰好 loader+1，用增量断言。
        startSession()
        val ctxStd = b.store.create("std-ctx")
        val beforeStd = b.loaderCalls.get()
        val std = callChecked(b, 2, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctxStd","mode":"auto"}""")
        assertEquals("std ctx+URL 首次 loader+1", beforeStd + 1, b.loaderCalls.get())
        assertEquals(6000, std.str("body").length)
        val stdText = std.text()
        report("vs-default-page-longcjk-first-auto", stdText, b)
        val stdPageId = std.str("pageId")

        // compact 会话首抓（同 bench、不同 ctx → 该 ctx+URL 首次，loader 再 +1）
        val compactHarness = Harness(client, port, servers, json, "/mcp?compact=1")
        compactHarness.startSession()
        val ctxCmp = b.store.create("cmp-ctx")
        val beforeCmp = b.loaderCalls.get()
        val cmp = compactHarness.callChecked(b, 10, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctxCmp","mode":"auto"}""")
        assertEquals("cmp ctx+URL 首次 loader+1", beforeCmp + 1, b.loaderCalls.get())
        assertEquals(2000, cmp.str("body").length)
        val cmpText = cmp.text()
        report("vs-compact-page-longcjk-first-auto", cmpText, b)
        val cmpPageId = cmp.str("pageId")

        // 元数据 pageId/contextId 是独立 UUID，只要求非空且结构字段一致
        assertTrue(stdPageId.isNotBlank()); assertTrue(cmpPageId.isNotBlank())
        assertEquals(std.str("totalChars"), cmp.str("totalChars"))
        assertEquals(std.str("code"), cmp.str("code"))
        // compact 响应 text 的 UTF-16 与 UTF-8 都必须实际更小
        assertTrue("compact chars smaller", cmpText.length < stdText.length)
        val stdUtf8 = stdText.toByteArray(Charsets.UTF_8).size
        val cmpUtf8 = cmpText.toByteArray(Charsets.UTF_8).size
        assertTrue("compact utf8 smaller", cmpUtf8 < stdUtf8)
        println("COMPACT-VS-DEFAULT defaultChars=${stdText.length} compactChars=${cmpText.length} defaultUtf8=$stdUtf8 compactUtf8=$cmpUtf8 shrinkUtf8=${stdUtf8 - cmpUtf8} ratio=%.3f".format(cmpUtf8.toDouble() / stdUtf8))

        // bigjson 同对照：compact 首抓 2000 仍截断且小于标准 6000
        val stdJ = callChecked(b, 20, "bench_page", """{"url":"https://bench.local/bigjson","contextId":"$ctxStd","mode":"auto"}""")
        val cmpJ = compactHarness.callChecked(b, 30, "bench_page", """{"url":"https://bench.local/bigjson","contextId":"$ctxCmp","mode":"auto"}""")
        assertEquals(6000, stdJ.str("body").length)
        assertEquals(2000, cmpJ.str("body").length)
        val stdJUtf8 = stdJ.text().toByteArray(Charsets.UTF_8).size
        val cmpJUtf8 = cmpJ.text().toByteArray(Charsets.UTF_8).size
        assertTrue(cmpJUtf8 < stdJUtf8)
        println("COMPACT-VS-DEFAULT-JSON defaultUtf8=$stdJUtf8 compactUtf8=$cmpJUtf8")

        // 同 ctx+URL 二次调用走缓存，loader 不再增加（每 ctx+URL 仅首次 1 次）
        val beforeCmp2 = b.loaderCalls.get()
        val cmp2 = compactHarness.callChecked(b, 31, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctxCmp","mode":"auto"}""")
        assertTrue(cmp2.bool("cacheHit"))
        assertEquals("", cmp2.str("body"))
        assertEquals(beforeCmp2, b.loaderCalls.get())
    }

    @Test(timeout = 90_000) fun compactAutoExplicit4000RetainsFullSnapshot() = httpBench("/mcp?compact=1") { b ->
        startSession()
        val ctx = b.store.create("compact-4000-ctx")
        val before = b.loaderCalls.get()
        val first = callChecked(b, 2, "bench_page",
            """{"url":"https://bench.local/long","contextId":"$ctx","mode":"auto","maxChars":4000}""")
        assertEquals(before + 1, b.loaderCalls.get())
        assertEquals(longCjkHtml.take(4000), first.str("body"))
        val pageId = first.str("pageId")
        assertTrue("pageId 非空", pageId.isNotBlank())
        assertEquals(longCjkHtml.length, first.str("totalChars").toInt())

        val firstTail = callChecked(b, 3, "bench_read",
            """{"contextId":"$ctx","id":"$pageId","offset":4000}""")
        val firstPage = json.parseToJsonElement(firstTail.text()).jsonObject
        val tailBody = StringBuilder(firstPage.getValue("body").jsonPrimitive.content)
        var offset = firstPage.getValue("nextOffset").jsonPrimitive.content.toInt()
        var hasMore = firstPage.getValue("hasMore").jsonPrimitive.content == "true"
        var requestId = 4
        while (hasMore) {
            assertTrue(offset < longCjkHtml.length)
            val previousOffset = offset
            val next = callChecked(b, requestId++, "bench_read",
                """{"contextId":"$ctx","id":"$pageId","offset":$offset}""")
            val nextPage = json.parseToJsonElement(next.text()).jsonObject
            tailBody.append(nextPage.getValue("body").jsonPrimitive.content)
            offset = nextPage.getValue("nextOffset").jsonPrimitive.content.toInt()
            hasMore = nextPage.getValue("hasMore").jsonPrimitive.content == "true"
            assertTrue("nextOffset 必须持续向前", offset > previousOffset)
            assertTrue(offset <= longCjkHtml.length)
            assertEquals(before + 1, b.loaderCalls.get())
        }
        assertEquals(longCjkHtml.length, offset)
        assertEquals(longCjkHtml.substring(4000), tailBody.toString())
        assertEquals(before + 1, b.loaderCalls.get())
    }

    /**
     * compact 下的显式 maxChars 与 preview/reference 缺省均保持旧输出：
     * 显式 0/6000 优先覆盖 compact 的 2000 缺省；preview/reference 无 maxChars 仍 6000。
     */
    @Test(timeout = 90_000) fun compactExplicitAndNonAutoModesUnchanged() = httpBench("/mcp?compact=1") { b ->
        startSession()
        val ctx = b.store.create("bench-ctx")
        // compact + auto + 显式 maxChars=6000：显式优先，非 2000
        val r1 = callChecked(b, 2, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"auto","maxChars":6000}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals(6000, r1.str("body").length)
        assertEquals(longCjkHtml.take(6000), r1.str("body"))
        report("compact-explicit-6000", r1.text(), b)

        // compact + preview 缺省：仍 6000（非 auto 不走 2000）
        val r2 = callChecked(b, 3, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"preview"}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals(6000, r2.str("body").length)
        report("compact-preview-default", r2.text(), b)

        // compact + auto + maxChars=0：旧行为 —— body 空
        val r3 = callChecked(b, 4, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"auto","maxChars":0}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals("", r3.str("body"))
        assertTrue(r3.bool("bodyOmitted"))
        report("compact-explicit-0", r3.text(), b)

        // compact + reference 缺省：body 恒空（reference 不回正文，非 2000 也非 6000 预览）
        val r4 = callChecked(b, 5, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"reference"}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals("", r4.str("body"))
        assertTrue(r4.bool("bodyOmitted"))

        // 显式 preview 在 compact 下也不回落 2000：preview 缺省 6000 上面已核，这里显式 preview+4000
        val r5 = callChecked(b, 6, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"preview","maxChars":4000}""")
        assertEquals(1, b.loaderCalls.get())
        assertEquals(4000, r5.str("body").length)

        // 反例：非法 maxChars（小数 3.5）经 McpArgs.int 报错 —— isError，不回落 compact 2000 缺省
        val err = callResult(7, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"auto","maxChars":3.5}""")
        assertEquals(JsonPrimitive(true), err["isError"])
        // 越界 maxChars（>12000）：require 报错，同样不回落
        val err2 = callResult(8, "bench_page", """{"url":"https://bench.local/long","contextId":"$ctx","mode":"auto","maxChars":99999}""")
        assertEquals(JsonPrimitive(true), err2["isError"])
    }

    private companion object {
        const val FINGERPRINT = "fp-fixed"
    }
}
