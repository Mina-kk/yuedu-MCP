package com.mina.legadostudio.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mina.legadostudio.reader.ReaderWebClient
import com.mina.legadostudio.reader.ReaderWebConfigStore
import com.mina.legadostudio.reader.ReaderWebDirectActions
import com.mina.legadostudio.reader.ReaderWebLegacyDebugClient
import com.mina.legadostudio.reader.ReaderWebSearchClient
import com.mina.legadostudio.reader.ReaderWebSourceSync
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 组合回归：真实 MockWebServer HTTP（lookup/save/readback）+ WS（/bookSourceDebug）→
 * sync 结果 → 自动 debug → 存 TaskContextStore → mergeSyncDeliveryPayload →
 * Gson 序列化最终 payload → read_result 分页取回。
 *
 * 覆盖 written 与 already_present 两条 sync 路径；并断言「取出存好的全量日志」
 * 不产生任何新网络请求（http.requestCount 与 ws.requestCount 在读回前后不变）——
 * read_result 读的是本地存储，重跑 reader_web_debug 才会再打站点。
 *
 * WS 端口按生产规则固定为 HTTP 端口 +1，沿用 ReaderWebDirectActionsTest 的相邻端口对。
 */
class ReaderWebSyncDeliveryPipelineTest {

    private val gson = Gson()
    private val url = "https://book.test/source"

    private fun source(url: String = this.url, name: String = "测试源", search: String = "https://book.test/search?q={%s}") =
        JsonParser.parseString(
            """{"bookSourceName":"$name","bookSourceUrl":"$url","bookSourceType":0,"searchUrl":"$search"}"""
        ).asJsonObject

    private fun okJson(data: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody("""{"isSuccess":true,"errorMsg":"","data":$data}""")

    private fun missingJson(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody("""{"isSuccess":false,"errorMsg":"未找到源","data":null}""")

    /** 阅读侧调度器：`initial` 为已存书源（null 表示没有），POST 才真正替换。 */
    private fun readerDispatcher(initial: JsonObject?): Dispatcher {
        val stored = AtomicReference<JsonObject?>(initial)
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/getBookSource?") ->
                        stored.get()?.let { okJson(it.toString()) } ?: missingJson()
                    path == "/saveBookSource" && request.method == "POST" -> {
                        stored.set(JsonParser.parseString(request.body.readUtf8()).asJsonObject)
                        okJson("\"\"")
                    }
                    path == "/saveBookSource" -> MockResponse().setResponseCode(405)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    /** 调试 WS：回一个关键词回显帧 + 四阶段日志 + 关闭。 */
    private fun debugSocket(logLines: List<String>, keySink: AtomicReference<String?>? = null): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        keySink?.set(
                            JsonParser.parseString(text).asJsonObject.get("key").asString
                        )
                        logLines.forEach { webSocket.send(it) }
                        webSocket.send("调试完成")
                        webSocket.close(1000, "")
                    }
                },
            )
        }

    private val fourStageLogs = listOf(
        "︾开始搜索关键字 我的", "︽搜索页解析完成",
        "︾开始解析详情页", "︽详情页解析完成",
        "︾开始解析目录页", "︽目录页解析完成",
        "︾开始解析正文页", "︽正文页解析完成",
    )

    /** 与 ReaderWebSyncTest 同口径：禁重定向/禁 SSL 重定向，短超时。 */
    private fun strictClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun stack(http: MockWebServer): Triple<ReaderWebSourceSync, ReaderWebDirectActions, ReaderWebConfigStore.Config> {
        val config = ReaderWebConfigStore.Config("http://${http.hostName}:${http.port}", "")
        val client = ReaderWebClient({ config }, clientFactory = { strictClient() })
        val sync = ReaderWebSourceSync(client, sourceProvider = { source().toString() })
        val actions = ReaderWebDirectActions(
            client,
            ReaderWebLegacyDebugClient({ config }, clientFactory = { strictClient() }),
            ReaderWebSearchClient({ config }, clientFactory = { strictClient() }),
        )
        return Triple(sync, actions, config)
    }

    /** 与 ReaderWebDirectActionsTest 相同：HTTP 端口必须是 WS 端口 -1。 */
    private fun pair(block: suspend (MockWebServer, MockWebServer) -> Unit) {
        repeat(20) {
            MockWebServer().use { ws ->
                ws.start()
                if (ws.port <= 1025) return@use
                try {
                    MockWebServer().use { http ->
                        http.start(ws.port - 1)
                        kotlinx.coroutines.runBlocking { block(http, ws) }
                    }
                    return
                } catch (error: java.net.BindException) {
                    // 下一次随机端口；测试仍验证严格 +1，而非替换生产端点规则。
                }
            }
        }
        error("无法取得相邻本机测试端口")
    }

    @Test fun writtenPathPipelinesThroughDebugStoreMergeAndPagedReadback() = runTest {
        pair { http, ws ->
            http.dispatcher = readerDispatcher(initial = null)
            ws.dispatcher = debugSocket(fourStageLogs)
            val (syncService, actions, _) = stack(http)

            val store = TaskContextStore()
            val ctxId = store.create("reader-sync")
            val fingerprint = "fp"

            val result = syncService.sync("p1", overwrite = false)
            assertEquals("written", result.status)
            assertTrue(result.confirmed)

            val payload = syncWithAutoDebugDelivery(
                result = result,
                projectIdArg = "p1",
                debug = { sourceKey, keyword -> actions.debug(sourceKey, keyword, 30) },
                storeExecution = { execution ->
                    val entry = store.saveResult(ctxId, gson.toJson(execution), fingerprint)
                    ReaderExecutionRef(ctxId, entry.id)
                },
                keywordArg = null,
                debugTimeoutSec = 90,
            )

            // 最终回包契约：写入成功 + 自动调试已跑 + 四阶段无失败 → 不强迫重跑
            assertEquals("written", payload["status"])
            assertEquals(true, payload["autoReaderDebug"])
            assertEquals(90, payload["debugTimeoutSec"])
            assertEquals("reader_debug_ran", payload["deliveryStatus"])
            assertEquals("all_stages_reached_no_failure", payload["logVerdict"])
            assertTrue(payload["deliveryRule"].toString().contains("可交付 ≠ 已验证"))
            assertEquals(null, payload["nextAction"])
            val resultId = payload["readerExecutionResultId"] as String
            assertEquals(ctxId, payload["readerExecutionContextId"])

            // 读回前的网络水位：lookup → POST → readback → debug lookup + WS 握手
            val httpBefore = http.requestCount
            val wsBefore = ws.requestCount
            assertEquals(4, httpBefore) // sync lookup + save + readback + debug lookup
            assertEquals(1, wsBefore)   // /bookSourceDebug 握手

            // read_result 分页取回：与存入文本逐字节一致，且不再打任何网络
            val entry = store.get(ctxId, resultId, fingerprint)
            val sb = StringBuilder()
            var offset = 0
            while (true) {
                val page = store.read(entry, offset, 500)
                sb.append(page["body"] as String)
                if (page["hasMore"] != true) break
                offset = page["nextOffset"] as Int
            }
            val restoredText = sb.toString()
            @Suppress("UNCHECKED_CAST")
            val restored = gson.fromJson(restoredText, Map::class.java) as Map<String, Any?>
            // 完整执行结果（未截断 debugLogs）按 resultId 取回，不是回包里的尾部裁剪
            val storedLogs = restored["debugLogs"] as List<*>
            assertTrue(storedLogs.size >= fourStageLogs.size)
            assertTrue(storedLogs.any { it.toString().contains("正文页解析完成") })

            assertEquals("read_result 不得再打 HTTP", httpBefore, http.requestCount)
            assertEquals("read_result 不得再打 WS", wsBefore, ws.requestCount)

            // Gson 最终序列化稳定：payload 本身是合法 JSON 往返
            val roundTrip = JsonParser.parseString(readerDeliveryJson(gson, payload)).asJsonObject
            assertEquals(resultId, roundTrip.get("readerExecutionResultId").asString)
            assertTrue(roundTrip.has("nextAction"))
            assertTrue(roundTrip.get("nextAction").isJsonNull)
        }
    }

    @Test fun alreadyPresentPathRunsAutoDebugWithoutPostAndStoresResult() = runTest {
        pair { http, ws ->
            http.dispatcher = readerDispatcher(initial = source())
            ws.dispatcher = debugSocket(fourStageLogs)
            val (syncService, actions, _) = stack(http)

            val store = TaskContextStore()
            val ctxId = store.create("reader-sync")
            val fingerprint = "fp"

            val result = syncService.sync("p1", overwrite = false)
            assertEquals("already_present", result.status)
            assertFalse(result.written)
            assertTrue(result.confirmed)
            assertTrue(result.presentInReader)

            val payload = syncWithAutoDebugDelivery(
                result = result,
                projectIdArg = "p1",
                debug = { sourceKey, keyword -> actions.debug(sourceKey, keyword, 30) },
                storeExecution = { execution ->
                    val entry = store.saveResult(ctxId, gson.toJson(execution), fingerprint)
                    ReaderExecutionRef(ctxId, entry.id)
                },
                keywordArg = null,
            )

            assertEquals("already_present", payload["status"])
            assertEquals(true, payload["autoReaderDebug"])
            assertNotNull(payload["readerExecutionResultId"])

            // already_present：两条 GET（sync lookup + debug lookup），一条 WS；无 POST
            val methods = generateSequence { http.takeRequest(1, TimeUnit.MILLISECONDS) }
                .map { it.method!! }.toList()
            assertEquals(2, methods.size)
            assertTrue(methods.none { it == "POST" })
            assertEquals(1, ws.requestCount)

            val httpBefore = http.requestCount
            val wsBefore = ws.requestCount
            store.get(ctxId, payload["readerExecutionResultId"] as String, fingerprint)
            assertEquals(httpBefore, http.requestCount)
            assertEquals(wsBefore, ws.requestCount)
        }
    }

    @Test fun conflictGivesProjectIdAndRequiresConfirmationNotCallableOverwrite() = runTest {
        pair { http, ws ->
            http.dispatcher = readerDispatcher(
                initial = source(name = "阅读里的旧名", search = "https://reader.test/s?q={%s}"),
            )
            ws.dispatcher = debugSocket(fourStageLogs)
            val (syncService, actions, _) = stack(http)

            val store = TaskContextStore()
            val ctxId = store.create("reader-sync")

            val result = syncService.sync("p1", overwrite = false)
            assertEquals("conflict", result.status)
            assertFalse(result.confirmed)

            val payload = syncWithAutoDebugDelivery(
                result = result,
                projectIdArg = "p1",
                debug = { sourceKey, keyword -> actions.debug(sourceKey, keyword, 30) },
                storeExecution = { execution ->
                    val entry = store.saveResult(ctxId, gson.toJson(execution), "fp")
                    ReaderExecutionRef(ctxId, entry.id)
                },
                keywordArg = null,
            )

            // 冲突分支：只给 projectId，绝不预填可直接执行的 overwrite=true
            assertEquals("reader_fields_unconfirmed", payload["deliveryStatus"])
            assertEquals("reader_web_sync_source", payload["nextAction"])
            val args = payload["nextActionArguments"] as Map<*, *>
            assertEquals("p1", args["projectId"])
            assertFalse("不得给可自动执行的 overwrite=true", args.containsKey("overwrite"))
            assertEquals(true, payload["requiresUserConfirmation"])
            assertTrue(payload["nextActionHint"].toString().contains("differentTopLevelFields"))
            // 阅读中已有该源 → 自动调试仍跑过（针对阅读现存版本）
            assertEquals("reader_debug_ran", payload["readerDebugDeliveryStatus"])
        }
    }

    @Test fun storeFailureKeepsFullExecutionWithoutFakingResultId() = runTest {
        pair { http, ws ->
            http.dispatcher = readerDispatcher(initial = null)
            // 超长日志：超过内联裁剪上限，验证「存不上时内联完整日志不丢行」
            val longLogs = (1..80).map { "︾阶段日志-$it-" + "z".repeat(80) } + fourStageLogs
            ws.dispatcher = debugSocket(longLogs)
            val (syncService, actions, _) = stack(http)

            val result = syncService.sync("p1", overwrite = false)
            assertEquals("written", result.status)

            val payload = syncWithAutoDebugDelivery(
                result = result,
                projectIdArg = "p1",
                debug = { sourceKey, keyword -> actions.debug(sourceKey, keyword, 30) },
                storeExecution = { throw IllegalStateException("模拟 TaskContextStore 写失败") },
                keywordArg = null,
            )

            // 存失败：不伪造 read_result 路径，内联的是完整未截断日志
            assertFalse(payload.containsKey("readerExecutionResultId"))
            assertFalse(payload.containsKey("readerExecutionContextId"))
            assertEquals(false, payload["readerExecutionStored"])
            assertTrue(payload["readerExecutionNote"].toString().contains("没有可用的 read_result"))
            val exec = payload["readerExecution"] as Map<*, *>
            assertEquals(false, exec["debugLogsTruncated"] ?: false)
            val logs = exec["debugLogs"] as List<*>
            assertEquals("内联必须是全量日志，不能裁掉", longLogs.size + 1, logs.size) // +1 调试完成帧
            // delivery 判定仍在完整日志上做
            assertEquals("all_stages_reached_no_failure", payload["logVerdict"])
        }
    }

    @Test fun inlineTailTruncationDoesNotFlipOriginalTruncatedFlag() {
        val start = "[00:00.001] ⇒开始搜索关键字:玄鉴仙族"
        val later = "[00:01.653] └http://example.test/cover.jpg"
        val cover = "[00:02.424] ┌获取封面链接"
        val logs = listOf(start, "︾开始解析搜索页", "≡获取成功", "┌获取书籍列表") +
            (1..40).map { "详情日志-$it-" + "y".repeat(80) } + listOf(later, cover)
        val execution = mapOf(
            "executed" to true,
            "debugLogs" to logs,
            "truncated" to false,
            "closeCode" to 1000,
            "logSignals" to mapOf("stagesReached" to listOf("search", "info", "toc", "content")),
        )
        val out = truncateDebugLogs(execution, maxChars = later.length + cover.length)
        assertEquals(false, out["truncated"])
        assertEquals(true, out["debugLogsTruncated"])
        assertTrue((out["debugLogsDroppedHeadLines"] as Int) > 0)
        assertEquals(1653L, out["debugLogsStartOffsetMs"])
        val kept = (out["debugLogs"] as List<*>).map { it.toString() }
        assertEquals(listOf(later, cover), kept)
        assertFalse(kept.any { it.contains("开始搜索关键字") })
        assertTrue(out["debugLogsNote"].toString().contains("不是 WS 丢帧"))
        assertTrue(out["debugLogsNote"].toString().contains("read_result"))
        assertTrue(out["debugLogsNote"].toString().contains("readerExecutionResultId"))
        val merged = mergeSyncDeliveryPayload(
            mapOf("status" to "written", "confirmed" to true, "projectId" to "p1", "nextAction" to "reader_web_debug"),
            "written", true, execution, ReaderExecutionRef("ctx", "res"),
        )
        assertEquals(true, merged["readerExecutionInlineTruncated"])
        assertEquals(false, (merged["readerExecution"] as Map<*, *>)["truncated"])
        assertEquals(true, (merged["readerExecution"] as Map<*, *>)["debugLogsTruncated"])
        assertEquals(60, merged["debugTimeoutSec"])
        val exec = merged["readerExecution"] as Map<*, *>
        assertTrue(exec.containsKey("debugLogsKeyLines"))
        val originalLogs = execution["debugLogs"] as List<*>
        assertEquals("完整日志不得被 truncate 改写", logs.size, originalLogs.size)
        assertEquals(start, originalLogs.first())
    }

    @Test fun mergeEchoesClampedDebugTimeoutSecOnNotInReader() {
        val payload = mergeSyncDeliveryPayload(
            mapOf("status" to "error", "confirmed" to false, "presentInReader" to false, "projectId" to "p1"),
            "error", false, null, null, debugTimeoutSec = 200,
        )
        assertEquals(180, payload["debugTimeoutSec"])
        assertEquals(false, payload["autoReaderDebug"])
    }
}
