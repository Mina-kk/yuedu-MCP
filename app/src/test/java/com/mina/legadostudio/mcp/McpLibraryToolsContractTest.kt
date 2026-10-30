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
        assertTrue(librarySource().contains("ReaderExecutionRegistry.summary("))
    }

    @Test
    fun readerSidePassedRequiresAllStagesAndNoFailures() {
        val executed = mapOf<String, Any?>("executed" to true, "complete" to true,
            "identityMatches" to true, "failureSignals" to emptyList<String>(),
            "currentKind" to "verify", "verifyStatus" to "passed")
        assertFalse(readerSidePassed(null))
        assertTrue(readerSidePassed(executed))
        assertFalse(readerSidePassed(executed + ("identityMatches" to false)))
        assertFalse(readerSidePassed(executed + ("failureSignals" to listOf("x"))))
        assertFalse(readerSidePassed(executed + ("complete" to false)))
        assertFalse(readerSidePassed(executed + ("currentKind" to "debug")))
        val passed = sandboxVerdictFields(readerConfigured = true, readerSideExecuted = true, readerSidePassed = true)
        assertEquals(true, passed["readerSidePassed"])
    }

    @Test
    fun exportDeliveryStatusFollowsReaderExecutions() {
        val ran = mapOf<String, Any?>("executed" to true, "complete" to true,
            "identityMatches" to true, "currentKind" to "debug", "missingStages" to emptyList<String>(),
            "failureSignals" to emptyList<String>())
        val bad = ran + ("failureSignals" to listOf("未获取到书籍"))
        val partial = ran + ("missingStages" to listOf("content"))
        val verified = ran + mapOf("currentKind" to "verify", "verifyStatus" to "passed")
        fun row(e: Map<String, Any?>?) = ReaderDeliveryRow("p", "https://a.example", e)
        assertEquals("reader_web_not_configured", exportDeliveryFields(false, listOf(row(ran)))["deliveryStatus"])
        assertEquals("sandbox_only_not_reader_verified", exportDeliveryFields(true, listOf(row(null)))["deliveryStatus"])
        assertEquals("sandbox_only_not_reader_verified", exportDeliveryFields(true, listOf(row(ran), row(null)))["deliveryStatus"])
        assertEquals("reader_evidence_unmatched", exportDeliveryFields(true, listOf(row(ran + ("identityMatches" to false))))["deliveryStatus"])
        assertEquals("reader_failure_signals", exportDeliveryFields(true, listOf(row(bad)))["deliveryStatus"])
        assertEquals("reader_debug_incomplete", exportDeliveryFields(true, listOf(row(ran + ("complete" to false))))["deliveryStatus"])
        assertEquals("reader_partial", exportDeliveryFields(true, listOf(row(partial)))["deliveryStatus"])
        assertEquals("reader_executed", exportDeliveryFields(true, listOf(row(ran +
            ("historicalFailureSignals" to listOf("旧失败")))))["deliveryStatus"])
        assertEquals("reader_verified", exportDeliveryFields(true, listOf(row(verified)))["deliveryStatus"])
        val ok = exportDeliveryFields(true, listOf(row(ran)))
        assertEquals("reader_executed", ok["deliveryStatus"])
        assertEquals(true, ok["draftExportAllowed"])
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
        // nextAction 是稳定工具名；调用参数挪到 nextActionArguments，自然语言进 nextActionHint
        assertEquals("reader_web_debug", incomplete["nextAction"])
        assertTrue((incomplete["nextActionArguments"] as Map<*, *>).containsKey("timeoutSec"))
        assertTrue(incomplete["nextActionHint"].toString().contains("timeoutSec"))
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
        assertFalse(limited["nextActionHint"].toString().contains("修规则"))
    }

    @Test
    fun syncToolForcesReaderDebugWithoutOptOut() {
        val reader = listOf(
            "src/main/java/com/mina/legadostudio/mcp/McpReaderWebTools.kt",
            "app/src/main/java/com/mina/legadostudio/mcp/McpReaderWebTools.kt",
        ).map(::File).first { it.isFile }.readText()
        val sync = reader.substring(reader.indexOf("server.tool(\"reader_web_sync_source\""))
            .substringBefore("server.tool(\"reader_web_debug\"")
        assertTrue(sync.contains("syncWithAutoDebugDelivery("))
        assertTrue(sync.contains("readerWebDirectActions.debug("))
        assertTrue("sync 必须暴露可选 debugTimeoutSec", sync.contains("\"debugTimeoutSec\""))
        assertTrue(sync.contains("resolveSyncDebugTimeoutSec("))
        assertTrue("不得在工具层再套更短 withTimeout", !sync.contains("withTimeout("))
        val pipeline = reader.substringAfter("internal suspend fun syncWithAutoDebugDelivery(")
            .substringBefore("internal fun truncateDebugLogs(")
        assertTrue(pipeline.contains("val execution = debug("))
        assertTrue(pipeline.contains("mergeSyncDeliveryPayload("))
        val merge = reader.substringAfter("internal fun mergeSyncDeliveryPayload(")
            .substringBefore("internal const val READER_WEB_PAGE_DESCRIPTION")
        assertTrue(merge.contains("\"autoReaderDebug\" to true"))
        assertTrue(merge.contains("readerDeliveryFields(execution)"))
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
        assertTrue("截断时必须带 debugLogsKeyLines", out.containsKey("debugLogsKeyLines"))
    }

    @Test
    fun syncDebugTimeoutSecClampsAndDefaultsToSixty() {
        assertEquals(60, resolveSyncDebugTimeoutSec(null))
        assertEquals(60, resolveSyncDebugTimeoutSec(60))
        assertEquals(15, resolveSyncDebugTimeoutSec(14))
        assertEquals(15, resolveSyncDebugTimeoutSec(1))
        assertEquals(180, resolveSyncDebugTimeoutSec(181))
        assertEquals(180, resolveSyncDebugTimeoutSec(999))
        assertEquals(90, resolveSyncDebugTimeoutSec(90))
    }

    @Test
    fun truncatedLogsKeepTailAndExposeBoundedKeyLinesFromFullLog() {
        val head = listOf(
            "[00:00.001] ⇒开始搜索关键字:玄鉴仙族",
            "[00:00.100] ︾开始解析搜索页",
            "[00:00.200] ┌获取书籍列表",
            "[00:00.300] └列表大小:3",
            "[00:00.400] ┌获取名称",
            "[00:00.500] └玄鉴仙族",
            "[00:00.800] ◇书籍总数:3",
            "[00:01.000] ︽搜索页解析完成",
            "[00:01.100] ︾开始解析详情页",
            "[00:01.200] ︽详情页解析完成",
            "[00:02.000] ︾开始解析目录页",
            "[00:02.100] ┌获取目录列表",
            "[00:02.200] └列表大小:88",
            "[00:02.250] ◇目录总数:88",
            "[00:02.300] ┌获取章节名称",
            "[00:02.400] └第一章 开端",
            "[00:02.500] ︽目录页解析完成",
            "[00:03.000] ︾开始解析正文页",
            "[00:03.100] ┌获取正文",
            "[00:03.200] └这是正文内容预览abcdefgh",
            "[00:03.300] ︽正文页解析完成",
            "[00:03.400] Exception: boom",
        )
        val filler = (1..40).map { "填充-$it-" + "y".repeat(80) }
        val logs = head + filler
        val original = mapOf<String, Any?>("executed" to true, "truncated" to false, "debugLogs" to logs)
        val out = truncateDebugLogs(original, maxChars = 500)
        val kept = (out["debugLogs"] as List<*>).map { it.toString() }
        assertEquals(true, out["debugLogsTruncated"])
        assertEquals(false, out["truncated"])
        assertEquals(logs, original["debugLogs"])
        assertTrue("内联尾部应丢掉搜索列表行", kept.none { it.contains("列表大小:3") })
        assertTrue("内联尾部应丢掉书名", kept.none { it.contains("玄鉴仙族") })
        val keys = (out["debugLogsKeyLines"] as List<*>).map { it.toString() }
        assertTrue(keys.size <= SYNC_DEBUG_KEY_LINES_MAX)
        assertTrue(keys.all { it.length <= SYNC_DEBUG_KEY_LINE_MAX_CHARS })
        assertTrue("关键行须含搜索列表条数", keys.any { it.contains("列表大小:3") || it.contains("书籍总数:3") })
        assertTrue("关键行须含书名", keys.any { it.contains("玄鉴仙族") })
        assertTrue("关键行须含目录总数", keys.any { it.contains("目录总数:88") || it.contains("列表大小:88") })
        assertTrue("关键行须含首章", keys.any { it.contains("第一章") })
        assertTrue("关键行须含正文", keys.any { it.contains("正文") })
        assertTrue("关键行须含错误", keys.any { it.contains("Exception") })
        assertEquals("旧 debugLogs 仍是尾部列表", kept, out["debugLogs"])
    }

    @Test
    fun untruncatedLogsDoNotAddKeyLinesField() {
        val small = mapOf<String, Any?>("debugLogs" to listOf("a", "b"), "executed" to true)
        val out = truncateDebugLogs(small)
        assertEquals(small, out)
        assertFalse(out.containsKey("debugLogsKeyLines"))
        assertFalse(out.containsKey("debugLogsTruncated"))
    }

    @Test
    fun mergeEchoesResolvedDebugTimeoutSec() {
        val payload = mergeSyncDeliveryPayload(
            syncBase("written", confirmed = true), "written", true,
            debugExecution(fourStageLogs), ReaderExecutionRef("ctx", "r"),
            debugTimeoutSec = 180,
        )
        assertEquals(180, payload["debugTimeoutSec"])
        val clamped = mergeSyncDeliveryPayload(
            syncBase("error", confirmed = false) - "presentInReader" + ("presentInReader" to false),
            "error", false, null, null, debugTimeoutSec = 9,
        )
        assertEquals(15, clamped["debugTimeoutSec"])
        val defaulted = mergeSyncDeliveryPayload(
            syncBase("written", confirmed = true), "written", true,
            debugExecution(fourStageLogs), ReaderExecutionRef("ctx", "r"),
        )
        assertEquals(60, defaulted["debugTimeoutSec"])
    }

    // ---------------- R2 收尾：sync 合并回包契约（mergeSyncDeliveryPayload 纯函数） ----------------

    private fun syncBase(status: String, confirmed: Boolean): Map<String, Any?> = linkedMapOf(
        "status" to status,
        "projectId" to "p1",
        "bookSourceUrl" to "https://a.example/source.json",
        "written" to (status == "written"),
        "confirmed" to confirmed,
        "presentInReader" to true,
        // 底层 sync.toPayload 的 nextAction 是未消费的底层建议，merge 必须剥掉
        "nextAction" to if (confirmed) "reader_web_debug" else null,
        "message" to "x",
    ).filterValues { it != null }

    private fun debugExecution(
        logs: List<String>,
        closeCode: Int? = 1000,
        failures: List<String> = emptyList(),
        executed: Any? = true,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "executed" to executed,
        "lookupConfirmed" to true,
        "sourceUrl" to "https://a.example/source.json",
        "keyword" to "我的",
        "debugLogs" to logs,
        "closeCode" to closeCode,
        "completionSignal" to false,
        "truncated" to false,
        "logSignals" to mapOf("failureSignals" to failures),
    ).filterValues { it != null }

    private val fourStageLogs = listOf(
        "︾开始搜索关键字 我的", "︽搜索页解析完成",
        "︾开始解析详情页", "︽详情页解析完成",
        "︾开始解析目录页", "︽目录页解析完成",
        "︾开始解析正文页", "︽正文页解析完成",
        "调试完成",
    )

    @Test fun syncMergeWrittenConfirmedFullDebugNeedsNoRerun() {
        val payload = mergeSyncDeliveryPayload(
            syncBase("written", confirmed = true), "written", true,
            debugExecution(fourStageLogs), ReaderExecutionRef("ctx-1", "res-1"),
        )
        assertEquals("written", payload["status"])
        assertEquals(true, payload["autoReaderDebug"])
        assertEquals("reader_debug_ran", payload["deliveryStatus"])
        assertEquals("all_stages_reached_no_failure", payload["logVerdict"])
        // 完整成功：本次自动调试已是阅读端证据，不强迫重复调试
        assertEquals(null, payload["nextAction"])
        assertTrue((payload["optionalActions"] as List<*>).contains("reader_web_page"))
        // 底层 nextAction=reader_web_debug 不得穿透到最终回包
        assertFalse(payload["nextAction"] == "reader_web_debug")
        // 完整结果引用：read_result 取回，不是重跑提示
        assertEquals("res-1", payload["readerExecutionResultId"])
        assertEquals("ctx-1", payload["readerExecutionContextId"])
        assertTrue(payload["readerExecutionNote"].toString().contains("read_result"))
        assertFalse(payload.toString().contains("请调用 reader_web_debug"))
    }

    @Test fun syncMergeAlreadyPresentRunsAutoDebug() {
        val payload = mergeSyncDeliveryPayload(
            syncBase("already_present", confirmed = true), "already_present", true,
            debugExecution(fourStageLogs), ReaderExecutionRef("ctx", "r"),
        )
        assertEquals("already_present", payload["status"])
        assertEquals(true, payload["autoReaderDebug"])
        assertEquals("reader_debug_ran", payload["deliveryStatus"])
        assertEquals(null, payload["nextAction"])
    }

    @Test fun syncMergeFailureKeepsSignalAndStableNextAction() {
        val payload = mergeSyncDeliveryPayload(
            syncBase("written", confirmed = true), "written", true,
            debugExecution(
                listOf("︾开始搜索关键字 我的", "︽未获取到书籍", "Exception: timeout"),
                failures = listOf("未获取到书籍"),
            ),
            ReaderExecutionRef("ctx", "r"),
        )
        assertEquals("reader_failure_signals", payload["deliveryStatus"])
        // nextAction 是稳定工具名，不是自然语言
        assertEquals("reader_web_debug", payload["nextAction"])
        val args = payload["nextActionArguments"] as Map<*, *>
        assertEquals("https://a.example/source.json", args["bookSourceUrl"])
        assertTrue(payload["nextActionHint"].toString().isNotBlank())
        // 失败信号不得被合并吞掉：内联日志与 logSignals 都保留
        val exec = payload["readerExecution"] as Map<*, *>
        assertEquals(listOf("未获取到书籍"),
            (exec["logSignals"] as Map<*, *>)["failureSignals"])
        assertTrue((exec["debugLogs"] as List<*>).any { it.toString().contains("未获取到书籍") })
    }

    @Test fun syncMergeIncompleteDebugGivesStructuredRerun() {
        val payload = mergeSyncDeliveryPayload(
            syncBase("written", confirmed = true), "written", true,
            debugExecution(listOf("︾开始搜索关键字"), closeCode = null),
            null,
        )
        assertEquals("reader_debug_incomplete", payload["deliveryStatus"])
        assertEquals("reader_web_debug", payload["nextAction"])
        assertTrue((payload["nextActionArguments"] as Map<*, *>).containsKey("timeoutSec"))
        // 没存上结果时不丢调试信息，也不谎称有 resultId
        assertFalse(payload.containsKey("readerExecutionResultId"))
    }

    @Test fun syncMergeNotExecutedPointsAtStatusNotDebug() {
        val payload = mergeSyncDeliveryPayload(
            syncBase("written", confirmed = true), "written", true,
            mapOf("executed" to false, "errorCode" to "UNREACHABLE", "debugLogs" to emptyList<String>()),
            null,
        )
        assertEquals("reader_not_executed", payload["deliveryStatus"])
        assertEquals("reader_web_status", payload["nextAction"])
    }

    @Test fun syncMergeUnconfirmedRetryArgsGiveProjectIdNotOverwrite() {
        // 非冲突的未确认（written_unconfirmed 等）：重试参数只给 projectId，绝不能顺手塞 overwrite
        val payload = mergeSyncDeliveryPayload(
            syncBase("written_unconfirmed", confirmed = false), "written_unconfirmed", false,
            debugExecution(fourStageLogs), ReaderExecutionRef("ctx", "r"),
        )
        assertEquals("reader_fields_unconfirmed", payload["deliveryStatus"])
        val args = payload["nextActionArguments"] as Map<*, *>
        assertEquals("p1", args["projectId"])
        assertFalse(args.containsKey("overwrite"))
        assertEquals(false, payload["requiresUserConfirmation"])
        assertTrue(payload["nextActionHint"].toString().contains("mismatchedFields"))
    }

    @Test fun syncMergeStoreFailureKeepsFullLogsAndFakesNoResultId() {
        // 存 TaskContextStore 失败：不丢全量、不伪造 read_result 可用路径
        val longLogs = (1..80).map { "︾行-$it-" + "z".repeat(80) } + fourStageLogs
        val payload = mergeSyncDeliveryPayload(
            syncBase("written", confirmed = true), "written", true,
            debugExecution(longLogs), null, storeFailed = true,
        )
        assertFalse(payload.containsKey("readerExecutionResultId"))
        assertFalse(payload.containsKey("readerExecutionContextId"))
        assertEquals(false, payload["readerExecutionStored"])
        assertTrue(payload["readerExecutionNote"].toString().contains("没有可用的 read_result"))
        val exec = payload["readerExecution"] as Map<*, *>
        assertEquals("存不上时内联必须是全量日志", longLogs.size, (exec["debugLogs"] as List<*>).size)
        assertFalse(exec["debugLogsTruncated"] == true)
        assertEquals("all_stages_reached_no_failure", payload["logVerdict"])
    }

    @Test fun debugRetryArgsKeepEffectiveKeywordAndOmitBlankSourceUrl() {
        // 用户没传 keyword 时 execution.keyword 是实际生效值（DEFAULT_DEBUG_KEYWORD），不是「默认我的」话术；
        // sourceUrl 缺失时 debugArgs 不伪造 bookSourceUrl——带上空调用是无效参数
        val withKeyword = readerDeliveryFields(debugExecution(
            listOf("︾开始搜索关键字"), closeCode = null,
        ) + ("keyword" to "站内书名"))
        assertEquals("站内书名", (withKeyword["nextActionArguments"] as Map<*, *>)["keyword"])

        val defaulted = readerDeliveryFields(debugExecution(
            listOf("︾开始搜索关键字"), closeCode = null,
        ).filterKeys { it != "keyword" })
        assertEquals("我的", (defaulted["nextActionArguments"] as Map<*, *>)["keyword"])

        val noSourceUrl = readerDeliveryFields(debugExecution(
            listOf("︾开始搜索关键字"), closeCode = null,
        ).filterKeys { it != "sourceUrl" })
        val args = noSourceUrl["nextActionArguments"] as Map<*, *>
        assertFalse("sourceUrl 缺失时不得给可调用但无效的 bookSourceUrl", args.containsKey("bookSourceUrl"))
        assertTrue(args.containsKey("timeoutSec"))
    }

    @Test fun rateLimitedNeedsHttpWordingNotBare429() {
        // 书号/章节号里的裸 429 不能被当成 HTTP 429 限流
        fun signals(logs: List<String>) = readerDeliveryFields(mapOf(
            "executed" to true, "closeCode" to 1000, "completionSignal" to true,
            "debugLogs" to logs, "logSignals" to mapOf("failureSignals" to listOf("未获取到书籍")),
        ))
        val bare = signals(listOf("︾开始搜索关键字", "共429章", "︽未获取到书籍"))
        assertEquals("undetermined", bare["failureCause"])
        val http = signals(listOf("︾开始搜索关键字", "HTTP 429 Too Many Requests", "︽未获取到书籍"))
        assertEquals("source_rate_limited", http["failureCause"])
        val zh = signals(listOf("︾开始搜索关键字", "访问过于频繁，请稍后再试", "︽未获取到书籍"))
        assertEquals("source_rate_limited", zh["failureCause"])
    }

    @Test fun syncMergeUnconfirmedOverridesDebugStatusAndKeepsItsSignal() {
        // 字段未确认：deliveryStatus 覆盖成 reader_fields_unconfirmed，但调试结局保留在 readerDebugDeliveryStatus
        val payload = mergeSyncDeliveryPayload(
            syncBase("written", confirmed = false), "written_unconfirmed", false,
            debugExecution(fourStageLogs), ReaderExecutionRef("ctx", "r"),
        )
        assertEquals("reader_fields_unconfirmed", payload["deliveryStatus"])
        assertEquals("reader_debug_ran", payload["readerDebugDeliveryStatus"])
        // 下一步是核对字段后重试同步，不是重跑调试
        assertEquals("reader_web_sync_source", payload["nextAction"])
        assertTrue(payload["nextActionHint"].toString().contains("mismatchedFields"))
    }

    @Test fun syncMergeConflictMarksRequiresConfirmationWithoutCallableOverwrite() {
        val payload = mergeSyncDeliveryPayload(
            syncBase("conflict", confirmed = false), "conflict", false,
            debugExecution(fourStageLogs), null,
        )
        assertEquals("reader_fields_unconfirmed", payload["deliveryStatus"])
        assertEquals("reader_web_sync_source", payload["nextAction"])
        // 冲突分支绝不预填可直接自动执行的 overwrite=true：覆盖必须用户明确授权
        val args = payload["nextActionArguments"] as Map<*, *>
        assertEquals("p1", args["projectId"])
        assertFalse(args.containsKey("overwrite"))
        assertEquals(true, payload["requiresUserConfirmation"])
        assertTrue(payload["nextActionHint"].toString().contains("differentTopLevelFields"))
    }

    @Test fun syncMergeNotInReaderGivesNoDebugAndStableNextAction() {
        val base = syncBase("error", confirmed = false) - "presentInReader" +
            ("presentInReader" to false)
        val payload = mergeSyncDeliveryPayload(base, "error", false, null, null)
        assertEquals(false, payload["autoReaderDebug"])
        assertEquals("not_in_reader", payload["deliveryStatus"])
        assertEquals("reader_web_sync_source", payload["nextAction"])
        assertFalse(payload.containsKey("readerExecution"))
        assertFalse(payload.containsKey("readerExecutionResultId"))
    }

    @Test fun syncTruncatedLogsPointAtStoredResultNotRerun() {
        // 裁剪后的回包日志只给尾部；全量走 resultId 分页取回，绝不提示重跑调试
        val logs = (1..60).map { "︾阶段日志-$it-" + "y".repeat(60) } + fourStageLogs
        val execution = debugExecution(logs)
        val out = truncateDebugLogs(execution)
        assertEquals(true, out["debugLogsTruncated"])
        assertTrue(out["debugLogsNote"].toString().contains("read_result"))
        assertTrue(out["debugLogsNote"].toString().contains("readerExecutionResultId"))
        assertFalse(out["debugLogsNote"].toString().contains("请调用 reader_web_debug"))
        // delivery 判定用的是未裁剪的完整日志：尾部裁剪不影响 structuredResult/失败信号判断
        assertEquals("reader_debug_ran", readerDeliveryFields(execution)["deliveryStatus"])
        val merged = mergeSyncDeliveryPayload(
            syncBase("written", true), "written", true, execution, ReaderExecutionRef("c", "r"),
        )
        assertEquals("all_stages_reached_no_failure", merged["logVerdict"])
    }

    @Test fun readerExecutionResultRoundTripsWithoutSecondNetwork() {
        // read_result 按 offset 精确分页取回与存入文本一致：全量日志不需要第二次站点请求
        val store = TaskContextStore()
        kotlinx.coroutines.runBlocking {
            val ctx = store.create("reader-sync")
            val fullLogs = (1..80).map { "︾完整日志行-$it-" + "z".repeat(80) }
            val execution = debugExecution(fullLogs)
            val text = com.google.gson.Gson().toJson(execution)
            val e = store.saveResult(ctx, text, "fp")
            val entry = store.get(ctx, e.id, "fp")
            // 分页精确拼接回原文
            val sb = StringBuilder()
            var offset = 0
            while (true) {
                val page = store.read(entry, offset, 500)
                sb.append(page["body"] as String)
                if (page["hasMore"] != true) break
                offset = page["nextOffset"] as Int
            }
            assertEquals(text, sb.toString())
            // 解析回 Map：完整 debugLogs 全部在，未截断 structuredResult 判定用得上
            @Suppress("UNCHECKED_CAST")
            val restored = com.google.gson.Gson().fromJson(sb.toString(), Map::class.java) as Map<String, Any?>
            assertEquals(fullLogs.size, (restored["debugLogs"] as List<*>).size)
            assertEquals(fullLogs.first(), (restored["debugLogs"] as List<*>).first())
        }
    }
}
