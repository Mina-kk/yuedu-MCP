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

    private fun fixture(b: Bench): Server = Server(
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
                val url = args?.get("url")?.jsonPrimitive?.content ?: "https://bench.local/small"
                val mode = args?.get("mode")?.jsonPrimitive?.content ?: "auto"
                require(mode in setOf("auto", "preview", "reference")) { "responseMode 必须为 auto/preview/reference" }
                val maxChars = args?.get("maxChars")?.jsonPrimitive?.content?.toIntOrNull() ?: 6000
                require(maxChars in 0..12000) { "maxChars 必须为 0..12000" }
                val ctx = args?.get("contextId")?.jsonPrimitive?.content ?: b.store.create("bench")
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
                val ctx = req.arguments?.get("contextId")?.jsonPrimitive?.content ?: b.store.create("bench")
                val size = req.arguments?.get("size")?.jsonPrimitive?.content?.toIntOrNull() ?: 15000
                val raw = CallToolResult(listOf(TextContent(sizedResultText(size))))
                boundedFixture(b, ctx, "bench_result", raw)
            }
        }

        // bench_read：按条目 id 分页读回正文（page/result 通用，走生产 contexts.read 路径）。
        server.addTool(Tool(name = "bench_read", description = "paged read of stored entry", inputSchema = schema)) { req ->
            tracked("bench_read") {
                val ctx = req.arguments?.get("contextId")?.jsonPrimitive?.content ?: error("contextId 不能为空")
                val entryId = req.arguments?.get("id")?.jsonPrimitive?.content ?: error("id 不能为空")
                val offset = req.arguments?.get("offset")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val e = b.store.get(ctx, entryId, FINGERPRINT)
                CallToolResult(listOf(TextContent(appGson.toJson(b.store.read(e, offset, 12000)))))
            }
        }

        // bench_err：在受限名单内但 isError=true —— 生产判定必须豁免（不得转引用/不得裁切）。
        server.addTool(Tool(name = "bench_err", description = "isError passthrough", inputSchema = schema)) { req ->
            tracked("bench_err") {
                val size = req.arguments?.get("size")?.jsonPrimitive?.content?.toIntOrNull() ?: 12500
                val errText = "失败明细:" + "x".repeat(size - 5)
                val raw = CallToolResult(listOf(TextContent(errText)), isError = true)
                boundedFixture(b, "unused-ctx", "bench_err", raw)
            }
        }

        // bench_raw：不在受限名单内 —— 即便超长也原样返回（identity 策略，不裁切）。
        server.addTool(Tool(name = "bench_raw", description = "non-bounded long passthrough", inputSchema = schema)) { req ->
            tracked("bench_raw") {
                val size = req.arguments?.get("size")?.jsonPrimitive?.content?.toIntOrNull() ?: 15000
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
    ) {
        var session: String? = null

        suspend fun post(body: String): Pair<Int, String> {
            val response = client.post("http://127.0.0.1:$port/mcp") {
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
            val response = client.post("http://127.0.0.1:$port/mcp") {
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

    private fun httpBench(block: suspend Harness.(Bench) -> Unit) = runBlocking {
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
            ) { fixture(b).also { servers.add(it) } }
        }
        try {
            withTimeout(60_000) {
                engine.start(wait = false)
                val port = engine.engine.resolvedConnectors().single().port
                Harness(client, port, servers, json).block(b)
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

    private companion object {
        const val FINGERPRINT = "fp-fixed"
    }
}
