package com.mina.legadostudio.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `import_reader` 的回执契约（源码级，与 McpToolCatalogTest/ReaderWebToolContractTest 同风格）：
 *
 * 设备现场出现过阅读勾选页的回环端点断连，但旧回执照样 `imported=true`，
 * 调用方误以为导入完成而跳过核实。这里锁定：唤起成功只能算 `launched=true`、
 * 确认状态必须是 `pending_user`/`confirmed=false`，兼容字段 `imported` 永不回 true；
 * 缺 packageName 时不发起唤起，回 `launched=false` + `needsPackageName=true`；
 * 描述首句与精简模式都必须先交代「未确认导入」。
 */
class McpLibraryToolsContractTest {

    private fun librarySource(): String = listOf(
        "src/main/java/com/mina/legadostudio/mcp/McpLibraryTools.kt",
        "app/src/main/java/com/mina/legadostudio/mcp/McpLibraryTools.kt",
    ).map(::File).firstOrNull { it.isFile }?.readText()
        ?: error("找不到 McpLibraryTools.kt")

    /** `import_reader` 的注册段（直到下一条 `server.tool(`）。 */
    private fun importReaderBlock(): String {
        val src = librarySource()
        val start = src.indexOf("server.tool(\"import_reader\"")
        assertTrue("找不到 import_reader 注册", start >= 0)
        val next = src.indexOf("server.tool(", start + 1)
        return src.substring(start, if (next > 0) next else src.length)
    }

    private fun importReaderDescription(): String {
        val block = importReaderBlock()
        val nameEnd = block.indexOf("\"import_reader\"") + "\"import_reader\"".length
        val open = block.indexOf('"', nameEnd)
        val close = block.indexOf('"', open + 1)
        assertTrue("找不到 import_reader 描述", open >= 0 && close > open)
        return block.substring(open + 1, close)
    }

    @Test
    fun descriptionLeadExplainsUnconfirmedAndPrefersWebSync() {
        val head = importReaderDescription().take(100)
        assertTrue("描述前 100 字符必须先交代边界：$head", head.contains("仅唤起勾选页，未确认导入；优先Web同步"))
    }

    @Test
    fun receiptNeverClaimsImportedTrue() {
        val block = importReaderBlock()
        assertFalse("startActivity 成功不等于导入成功，绝不能回 imported=true", block.contains("\"imported\" to true"))
        // 唤起成功口径
        assertTrue(block.contains("\"launched\" to true"))
        assertTrue(block.contains("\"confirmation\" to \"pending_user\""))
        assertTrue(block.contains("\"confirmed\" to false"))
        // 兼容字段保留但恒为 false，并标注 deprecated
        assertTrue(block.contains("\"imported\" to false"))
        assertTrue(block.contains("\"deprecated\""))
        // 必须提示下一步核实/同步，而不是就此收工
        assertTrue(block.contains("reader_web_status"))
        assertTrue(block.contains("reader_web_verify"))
        assertTrue(block.contains("reader_web_sync_source"))
    }

    @Test
    fun missingPackageNameDoesNotLaunch() {
        val block = importReaderBlock()
        assertTrue(block.contains("\"needsPackageName\" to true"))
        assertTrue("未指定 packageName 时不得声称已唤起", block.contains("\"launched\" to false"))
        // 未指定包名直接 return，保证不会执行到 app.importToReader
        assertTrue(block.contains("?: return@tool"))
    }

    @Test
    fun annotationsAreOpenWriteAndNotIdempotent() {
        val block = importReaderBlock()
        assertTrue("唤起阅读界面是对外副作用，与同仓库 reader_web_verify 一致用 openWrite", block.contains("toolAnnotations = openWrite"))
        assertFalse("不得复用幂等的 write 标注", block.contains("toolAnnotations = write"))
    }

    @Test
    fun compactDescriptionKeepsUnconfirmedCaveat() {
        val compact = McpToolCompactor.truncate(importReaderDescription()).orEmpty()
        assertTrue("精简摘要仍须保留「未确认导入」限制：$compact", compact.contains("未确认导入"))
        assertFalse("精简摘要不得出现成功口径", compact.contains("imported=true") || compact.contains("已导入"))
    }

    @Test
    fun checkSourceResponseAndCompactDescriptionDoNotClaimReaderVerification() {
        val src = librarySource()
        val block = src.substring(src.indexOf("server.tool(\"check_source\""))
        val compact = McpToolCompactor.truncate(block.substringAfter("server.tool(\"check_source\", \"").substringBefore('"')).orEmpty()
        assertTrue(compact.contains("沙箱初筛"))
        assertTrue(compact.contains("不代表阅读端通过"))
        assertTrue(block.contains("\"readerWebVerification\" to \"not_performed\""))
        assertTrue(block.contains("reader_web_page"))
        assertTrue(block.contains("reader_web_verify"))
        assertTrue(block.contains("阅读端未覆盖"))
    }

    @Test
    fun sandboxVerdictIsAlwaysSandboxOnlyAndDemandsReaderStepsWhenConfigured() {
        val off = sandboxVerdictFields(readerConfigured = false, readerSideExecuted = false)
        assertEquals("sandbox_only", off["verdictScope"])
        assertFalse(off.containsKey("requiredNextActions"))
        val on = sandboxVerdictFields(readerConfigured = true, readerSideExecuted = false)
        assertEquals("sandbox_only", on["verdictScope"])
        assertEquals(false, on["readerSideExecuted"])
        assertEquals(false, on["readerSidePassed"])
        assertFalse(on.containsKey("readerSideVerified"))
        val next = on["requiredNextActions"] as List<*>
        assertTrue(next.contains("reader_web_sync_source"))
        assertTrue(next.contains("reader_web_debug"))
        assertFalse(next.contains("reader_web_search"))
        assertTrue((on["forbiddenClaims"] as List<*>).contains("4/4 通过即可用"))
        assertTrue(on["deliveryRule"].toString().contains("沙箱 4/4 不能作为交付结论"))
        assertTrue(librarySource().contains("ReaderExecutionRegistry.hasExecuted(sourceUrl)"))
    }

    @Test
    fun readerSidePassedRequiresAllStagesAndNoFailures() {
        val all = listOf("search", "detail", "toc", "content")
        assertFalse(readerSidePassed(null))
        assertTrue(readerSidePassed(mapOf("executed" to true, "failureSignals" to emptyList<String>(), "stagesReached" to all)))
        assertFalse(readerSidePassed(mapOf("executed" to true, "failureSignals" to listOf("x"), "stagesReached" to all)))
        assertFalse(readerSidePassed(mapOf("executed" to true, "failureSignals" to emptyList<String>(), "stagesReached" to listOf("search"))))
        val passed = sandboxVerdictFields(readerConfigured = true, readerSideExecuted = true, readerSidePassed = true)
        assertEquals(true, passed["readerSidePassed"])
    }

    @Test
    fun exportDeliveryStatusFollowsReaderExecutions() {
        val ran = mapOf<String, Any?>("executed" to true, "kinds" to listOf("debug"), "failureSignals" to emptyList<String>())
        val bad = mapOf<String, Any?>("executed" to true, "kinds" to listOf("debug"), "failureSignals" to listOf("≡未获取到书籍"))
        val zeroHits = mapOf<String, Any?>("executed" to true, "kinds" to listOf("search"), "searchHits" to 0)
        fun row(e: Map<String, Any?>?) = ReaderDeliveryRow("p", "https://a.example", e)
        assertEquals("reader_web_not_configured", exportDeliveryFields(false, listOf(row(ran)))["deliveryStatus"])
        assertEquals("sandbox_only_not_reader_verified", exportDeliveryFields(true, listOf(row(null)))["deliveryStatus"])
        assertEquals("sandbox_only_not_reader_verified", exportDeliveryFields(true, listOf(row(ran), row(null)))["deliveryStatus"])
        assertEquals("reader_failure_signals", exportDeliveryFields(true, listOf(row(bad)))["deliveryStatus"])
        assertEquals("reader_failure_signals", exportDeliveryFields(true, listOf(row(zeroHits)))["deliveryStatus"])
        assertEquals("reader_debug_incomplete", exportDeliveryFields(true, listOf(row(ran + ("latestDebugStatus" to "TIMEOUT"))))["deliveryStatus"])
        assertEquals("reader_executed", exportDeliveryFields(true, listOf(row(ran +
            ("historicalFailureSignals" to listOf("旧失败")))))["deliveryStatus"])
        val ok = exportDeliveryFields(true, listOf(row(ran)))
        assertEquals("reader_executed", ok["deliveryStatus"])
        assertFalse(ok.containsKey("blockingReason"))
        val first = (exportDeliveryFields(true, listOf(row(null)))["readerExecutions"] as List<*>).single() as Map<*, *>
        assertEquals(mapOf("executed" to false), first["readerExecution"])
    }

    @Test
    fun readerDebugDeliveryStatusIsDecidedByServer() {
        assertEquals("reader_not_executed", readerDeliveryFields(mapOf("executed" to false, "errorCode" to "UNREACHABLE"))["deliveryStatus"])
        assertEquals(
            "reader_failure_signals",
            readerDeliveryFields(mapOf("executed" to true, "closeCode" to 1000, "debugLogs" to listOf("◇书籍总数:0"), "logSignals" to mapOf("failureSignals" to listOf("列表大小:0"))))["deliveryStatus"],
        )
        assertEquals(
            "reader_debug_ran",
            readerDeliveryFields(mapOf("executed" to true, "closeCode" to 1000, "completionSignal" to true, "debugLogs" to listOf("︽搜索页解析完成"), "logSignals" to mapOf("failureSignals" to emptyList<String>())))["deliveryStatus"],
        )
        assertEquals("reader_not_executed", readerDeliveryFields(mapOf("executed" to "unknown"))["deliveryStatus"])
        val incomplete = readerDeliveryFields(mapOf(
            "executed" to "unknown", "errorCode" to "TIMEOUT", "debugLogs" to listOf("︾开始搜索", "获取书籍列表"),
            "logSignals" to mapOf("failureSignals" to emptyList<String>()),
        ))
        assertEquals("reader_debug_incomplete", incomplete["deliveryStatus"])
        assertTrue(incomplete["deliveryRule"].toString().contains("不得改用沙箱"))
        assertTrue(incomplete["nextAction"].toString().contains("timeoutSec"))
        assertEquals("reader_debug_incomplete", readerDeliveryFields(mapOf(
            "executed" to true, "truncated" to true, "closeCode" to 1000,
            "logSignals" to mapOf("failureSignals" to emptyList<String>()),
        ))["deliveryStatus"])
        assertEquals("reader_debug_incomplete", readerDeliveryFields(mapOf(
            "executed" to true, "closeCode" to null, "completionSignal" to false,
            "logSignals" to mapOf("failureSignals" to listOf("书籍总数:0")),
        ))["deliveryStatus"])
        assertEquals("reader_debug_incomplete", readerDeliveryFields(mapOf(
            "executed" to true, "closeCode" to 1000, "debugLogs" to listOf("︾开始解析搜索页", "┌获取书籍列表"),
            "logSignals" to mapOf("failureSignals" to emptyList<String>()),
        ))["deliveryStatus"])
        val limited = readerDeliveryFields(mapOf(
            "executed" to true, "closeCode" to 1000,
            "debugLogs" to listOf("搜索间隔【15】秒", "︽未获取到书籍"),
            "logSignals" to mapOf("failureSignals" to listOf("︽未获取到书籍")),
        ))
        assertEquals("source_rate_limited", limited["failureCause"])
        assertFalse(limited["nextAction"].toString().contains("修规则"))
    }

    @Test
    fun syncToolForcesReaderDebugWithoutOptOut() {
        val reader = listOf(
            "src/main/java/com/mina/legadostudio/mcp/McpReaderWebTools.kt",
            "app/src/main/java/com/mina/legadostudio/mcp/McpReaderWebTools.kt",
        ).map(::File).first { it.isFile }.readText()
        val sync = reader.substring(reader.indexOf("server.tool(\"reader_web_sync_source\""))
            .substringBefore("server.tool(\"reader_web_debug\"")
        assertTrue(sync.contains("\"autoReaderDebug\" to true"))
        assertTrue(sync.contains("readerWebDirectActions.debug("))
        assertTrue(sync.contains("readerDeliveryFields("))
        assertFalse("不得提供关闭自动调试的开关", sync.contains("\"autoDebug\"") || sync.contains("\"skipDebug\""))
        val actions = listOf(
            "src/main/java/com/mina/legadostudio/reader/ReaderWebDirectActions.kt",
            "app/src/main/java/com/mina/legadostudio/reader/ReaderWebDirectActions.kt",
        ).map(::File).first { it.isFile }.readText()
        assertTrue(actions.contains("DEFAULT_DEBUG_KEYWORD = \"我的\""))
    }

    @Test
    fun syncDebugLogsAreTruncatedToTail() {
        val small = mapOf<String, Any?>("debugLogs" to listOf("a", "b"))
        assertEquals(small, truncateDebugLogs(small))
        val logs = (1..100).map { "line-$it-" + "x".repeat(50) }
        val out = truncateDebugLogs(mapOf("executed" to true, "debugLogs" to logs), maxChars = 500)
        val kept = out["debugLogs"] as List<*>
        assertTrue(kept.isNotEmpty() && kept.size < logs.size)
        assertEquals(logs.last(), kept.last())
        assertTrue(kept.sumOf { it.toString().length } <= 500)
        assertEquals(true, out["debugLogsTruncated"])
        assertEquals(100, out["debugLogsTotalLines"])
        assertEquals(true, out["executed"])
    }
}
