package com.mina.legadostudio.mcp

import com.google.gson.GsonBuilder
import com.mina.legadostudio.reader.ReaderWebConfigStore
import com.mina.legadostudio.reader.ReaderWebGuidance
import com.mina.legadostudio.reader.ReaderWebUiPolicy
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `reader_web_page` 的契约：模型不能重定向、动作只对应固定 DOM 操作、回包有分段预算，
 * 且通用 fetch_page 命中所保存的阅读 origin 时只做引导、不抓页。
 *
 * 这些用例覆盖参数裁剪与回包成形的纯逻辑，以及注册/描述/instructions 的源码契约；
 * **不证明真机 WebView 上页面交互可用**（需要 Android 运行时，JVM 单测无法覆盖）。
 */
class ReaderWebPageToolContractTest {

    // 与 StudioApplication.gson 同配置：生产回包是 pretty-printing，预算必须按同一序列化结果算
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private fun mcpSources(): String {
        val dir = listOf(
            File("src/main/java/com/mina/legadostudio/mcp"),
            File("app/src/main/java/com/mina/legadostudio/mcp"),
        ).first { it.isDirectory }
        return dir.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }
            .joinToString("\n") { it.readText() }
    }

    private fun toolSource(): String = listOf(
        File("src/main/java/com/mina/legadostudio/mcp/McpReaderWebTools.kt"),
        File("app/src/main/java/com/mina/legadostudio/mcp/McpReaderWebTools.kt"),
    ).first { it.isFile }.readText()

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    // ---------------- 注册与描述 ----------------

    @Test fun toolCatalogHasExactlySeventyThreeToolsIncludingReaderWebPage() {
        val names = Regex("""(?:addTool|tool)\("([^"]+)"""")
            .findAll(mcpSources()).map { it.groupValues[1] }.toList()
        assertEquals("重复注册会把 tools/list 变成 73 以外的数量", names.size, names.toSet().size)
        assertEquals("注册工具总数应为 73", 73, names.size)
        assertTrue("缺少 reader_web_page", "reader_web_page" in names)
    }

    @Test fun readerWebPageIsOpenWriteAndHasNoRedirectParameters() {
        val source = toolSource()
        val start = source.indexOf("server.tool(\"reader_web_page\"")
        assertTrue("未注册 reader_web_page", start >= 0)
        val registration = source.substring(start).substringBefore("server.tool(\"reader_web_status\"")
        assertTrue("click/fill 有副作用，必须 openWrite", registration.contains("toolAnnotations = openWrite"))
        assertFalse("不得标成只读", registration.contains("toolAnnotations = readOnly"))
        listOf("url", "host", "headers", "js", "baseUrl", "origin").forEach { key ->
            assertFalse("reader_web_page 不得暴露 $key 参数", registration.contains("put(\"$key\""))
        }
        assertTrue(registration.contains("putJsonObject(\"action\")"))
        // 动作枚举固定，无其它动作入口
        assertEquals(
            listOf("open", "snapshot", "click", "press_enter", "fill", "scroll", "back", "close"),
            ReaderWebPageRequest.ACTIONS,
        )
        assertTrue("schema 动作描述必须列出 press_enter", registration.contains("click/press_enter/fill"))
    }

    @Test fun compactDescriptionCarriesBoundaryInsideFirstHundredChars() {
        val compact = McpToolCompactor.truncate(READER_WEB_PAGE_DESCRIPTION)!!
        assertTrue("compact 描述应截断到首句", compact.length <= McpToolCompactor.MAX_DESCRIPTION_CHARS)
        listOf("无URL/host/headers/JS参数", "不能重定向换站", "reader_web_status", "不猜默认端口")
            .forEach { assertTrue("compact 描述前 100 字符缺少「$it」：$compact", compact.contains(it)) }
        // 完整描述仍保留用途与副作用说明
        assertTrue(READER_WEB_PAGE_DESCRIPTION.contains("click/press_enter/fill 有副作用"))
        assertTrue("必须点名 press_enter 只对可编辑表单控件回车",
            READER_WEB_PAGE_DESCRIPTION.contains("press_enter 只对可编辑表单控件回车"))
        assertTrue(READER_WEB_PAGE_DESCRIPTION.contains("textOffset"))
    }

    @Test fun initializeInstructionsRouteThroughDedicatedTools() {
        val source = mcpSources()
        assertTrue("create() 必须传 instructions", source.contains("instructions = MCP_INSTRUCTIONS"))
        val instructions = StudioMcpServer.MCP_INSTRUCTIONS
        assertTrue(instructions.contains("不要猜默认端口"))
        assertTrue(instructions.contains("reader_web_status"))
        assertTrue(instructions.contains("reader_web_page"))
        assertTrue("必须说明 fetch_page 不用于阅读网页操作", instructions.contains("fetch_page"))
        assertTrue("必须说明官方 API 只是可选", instructions.contains("可选"))
    }

    @Test fun readerWebPageNeverProbesOfficialApiFirst() {
        val source = toolSource()
        val handler = source.substring(source.indexOf("private suspend fun StudioMcpServer.handleReaderWebPage"))
            .substringBefore("/** 会话错误码对应的下一步提示")
        assertFalse("页面工具不得先探官方接口", handler.contains("readerWebBridge"))
        assertFalse("页面工具不得调用官方客户端", handler.contains("readerWebClient"))
        assertFalse("页面工具不得先跑 status", handler.contains(".status()"))
        assertTrue("必须直接调会话的 suspend 方法", handler.contains("app.readerWebUiSession"))
        // close 走独立分支：只销毁会话，不做网络请求，也不读配置
        val closeBranch = handler.substringBefore("// 先执行动作")
        assertTrue(closeBranch.contains("app.readerWebUiSession.close()"))
        assertFalse("close 不得读配置或联网", closeBranch.contains("readerWebConfig"))
        // 成功回包必须带 mode/originEcho/configRevision
        val payloadSource = source.substring(source.indexOf("internal object ReaderWebPagePayload"))
        listOf("\"mode\" to \"web_ui\"", "\"originEcho\"", "\"configRevision\"").forEach {
            assertTrue("成功回包缺少 $it", payloadSource.contains(it))
        }
        // originEcho 只由本地身份计算，不联网
        val echo = source.substring(source.indexOf("internal object ReaderWebPageAttribution"))
            .substringBefore("/** `reader_web_page` 的 handler")
        assertTrue(echo.contains("ReaderWebBridge.originEcho"))
        assertFalse("originEcho 不得联网探测", echo.contains("status()") || echo.contains("readerWebClient"))
    }

    // ---------------- 页面归属：先动作，再按会话身份标注 ----------------

    private fun identity(baseUrl: String, revision: Long) =
        ReaderWebUiPolicy.SessionIdentity.of(baseUrl, revision, "")!!

    @Test fun attributionRequiresPinnedAndCurrentRevisionToMatch() {
        // 动作前预读的是旧配置（1122/旧 revision），动作按新配置（1162/新 revision）打开：
        // pinned 与 current 不一致，必须丢弃结果，绝不能挂旧端口成功返回
        val pinned = identity("http://127.0.0.1:1162", 8L)
        val stale = config("http://127.0.0.1:1122", 7L)
        val changed = ReaderWebPageAttribution.resolve(pinned, stale)
        assertTrue("pinned 与当前配置不同必须判为 Changed", changed is ReaderWebPageAttribution.Result.Changed)
        changed as ReaderWebPageAttribution.Result.Changed
        assertEquals(8L, changed.pinnedRevision)
        assertEquals(7L, changed.currentRevision)
        // 失败回包不能带 originEcho/configRevision，否则模型会把旧值当成页面归属
        val failure = ReaderWebPagePayload.error("CONFIG_CHANGED", "配置已变更，已丢弃本次结果", "reader_web_page(action=open)")
        assertFalse("失败回包不得带 originEcho", failure.containsKey("originEcho"))
        assertFalse("失败回包不得带 configRevision", failure.containsKey("configRevision"))

        // revision 相等才允许成功，且成功回包用 pinned 的 revision 与掩码 origin
        val ok = ReaderWebPageAttribution.resolve(pinned, config("http://127.0.0.1:1162", 8L))
        assertTrue("同一身份应成功", ok is ReaderWebPageAttribution.Result.Ok)
        ok as ReaderWebPageAttribution.Result.Ok
        assertEquals(8L, ok.revision)
        assertEquals("http://127.0.0.1:1162", ok.originEcho)
    }

    @Test fun attributionUsesPinnedOriginNotPreReadConfig() {
        // 同一 revision 但 origin 变了：也不能用预读配置的 1122 冒充成功
        val pinned = identity("http://127.0.0.1:1162", 9L)
        val other = ReaderWebPageAttribution.resolve(pinned, config("http://127.0.0.1:1122", 9L))
        assertTrue("origin 不同必须失败", other is ReaderWebPageAttribution.Result.Changed)
        assertEquals(9L, (other as ReaderWebPageAttribution.Result.Changed).pinnedRevision)

        // 令牌变更同样是一次新配置（身份含令牌指纹），不能只看 revision
        val tokenChanged = ReaderWebPageAttribution.resolve(
            identity("http://127.0.0.1:1162", 9L),
            ReaderWebConfigStore.Config("http://127.0.0.1:1162", "secret", 9L),
        )
        assertTrue("令牌指纹不同必须失败", tokenChanged is ReaderWebPageAttribution.Result.Changed)

        // 非回环主机仍按掩码回显，端口保留
        val masked = ReaderWebPageAttribution.resolve(
            identity("http://192.168.1.8:1162", 4L),
            config("http://192.168.1.8:1162", 4L),
        ) as ReaderWebPageAttribution.Result.Ok
        assertEquals("http://<已隐藏>:1162", masked.originEcho)
        assertFalse("不得回显私网主机", masked.originEcho!!.contains("192.168"))
    }

    @Test fun pinnedMissingMeansChanged() {
        // 会话已销毁/关闭：没有 pinned 身份，动作结果不能归属到任何配置
        val missing = ReaderWebPageAttribution.resolve(null, config("http://127.0.0.1:1162", 5L))
        assertTrue(missing is ReaderWebPageAttribution.Result.Changed)
        assertEquals(5L, (missing as ReaderWebPageAttribution.Result.Changed).currentRevision)
        // 配置被删除（current == null）同样失败
        assertTrue(
            ReaderWebPageAttribution.resolve(identity("http://127.0.0.1:1162", 5L), null)
                is ReaderWebPageAttribution.Result.Changed,
        )
    }

    @Test fun handlerReadsConfigOnlyAfterTheActionReturns() {
        val source = toolSource()
        val handler = source.substring(source.indexOf("private suspend fun StudioMcpServer.handleReaderWebPage"))
            .substringBefore("/** 会话错误码对应的下一步提示")
        val actionCall = handler.indexOf("session.open()")
        val loadCall = handler.indexOf("app.readerWebConfig.load()")
        val pinnedRead = handler.indexOf("app.readerWebUiSession.pinnedIdentity")
        assertTrue("必须执行动作", actionCall >= 0)
        assertTrue("动作成功后必须读会话 pinned 身份", pinnedRead >= 0)
        assertTrue("配置只能在动作之后读（否则回包会挂旧端口/旧 revision）", loadCall > actionCall)
        assertTrue("pinned 身份必须在读配置之前取出", pinnedRead < loadCall)
        // close 早退分支仍在读配置之前
        assertTrue(handler.indexOf("app.readerWebUiSession.close()") < loadCall)
        // 归属不一致时用 CONFIG_CHANGED 失败，不得把 pinned 或 current 之一当成功值
        assertTrue(handler.contains("\"CONFIG_CHANGED\""))
        assertTrue(
            "成功回包只从归属结果取 revision",
            handler.contains("attribution.revision") && !handler.contains("ReaderWebConfigEcho"),
        )
    }

    @Test fun sessionExposesPinnedIdentityForAttribution() {
        val session = listOf(
            File("src/main/java/com/mina/legadostudio/reader/ReaderWebUiSession.kt"),
            File("app/src/main/java/com/mina/legadostudio/reader/ReaderWebUiSession.kt"),
        ).first { it.isFile }.readText()
        assertTrue(
            "会话必须提供只读 pinnedIdentity（含 origin 与 revision）",
            Regex("""val pinnedIdentity:\s*ReaderWebUiPolicy\.SessionIdentity\?""").containsMatchIn(session),
        )
        val getter = session.substringAfter("val pinnedIdentity")
        assertTrue("pinnedIdentity 必须是只读 getter", getter.contains("get() = live?.let"))
        assertTrue("必须过滤已销毁的会话", getter.substringBefore("\n\n").contains("state.destroyed"))
    }

    // ---------------- 动作分发 ----------------

    @Test fun pressEnterRequiresEditableTargetSelectorAndDispatchesToSession() {
        // press_enter 是独立动作：只带 selector/index，不写文本（先 fill 再回车），也不接受 deltaY
        val enter = ReaderWebPageRequest.parse(args("action" to "press_enter", "selector" to "input[name=q]", "index" to "1"))
        assertEquals("press_enter", enter.action)
        assertEquals("input[name=q]", enter.selector)
        assertEquals(1, enter.index)
        assertEquals("press_enter 不带 text", null, enter.text)
        assertNull("press_enter 不带 deltaY", enter.deltaY)

        fun fails(vararg pairs: Pair<String, String>): Boolean = runCatching {
            ReaderWebPageRequest.parse(args(*pairs))
        }.isFailure
        assertTrue("press_enter 必须给 selector", fails("action" to "press_enter"))
        assertTrue(
            "press_enter 不接受 text（否则会绕过 fill 直接提交）",
            fails("action" to "press_enter", "selector" to "input", "text" to "x"),
        )
        assertTrue("press_enter 不接受 deltaY", fails("action" to "press_enter", "selector" to "input", "deltaY" to "100"))
        assertTrue("press_enter 不接受空 selector", fails("action" to "press_enter", "selector" to "  "))

        // handler 必须真的把动作转给会话的 pressEnter（不是复用 click / 只回快照）
        val handler = toolSource()
            .substring(toolSource().indexOf("private suspend fun StudioMcpServer.handleReaderWebPage"))
            .substringBefore("/** 会话错误码对应的下一步提示")
        assertTrue(
            "press_enter 必须分派到 session.pressEnter",
            Regex("""\"press_enter\"\s*->\s*session\.pressEnter\(request\.selector!!,\s*request\.index\)""")
                .containsMatchIn(handler),
        )
        assertEquals(
            "press_enter 必须排在 click 之后、fill 之前",
            listOf("open", "snapshot", "click", "press_enter", "fill", "scroll", "back", "close"),
            ReaderWebPageRequest.ACTIONS,
        )
    }

    @Test fun clickAndFillRequireSelectorAndOnlyCarryDomFields() {
        val click = ReaderWebPageRequest.parse(args("action" to "click", "selector" to "#search", "index" to "2"))
        assertEquals("click", click.action)
        assertEquals("#search", click.selector)
        assertEquals(2, click.index)

        val fill = ReaderWebPageRequest.parse(
            args("action" to "fill", "selector" to "input[name=q]", "text" to "诡秘之主"),
        )
        assertEquals("诡秘之主", fill.text)
        assertEquals(0, fill.index)
        assertEquals("空串表示清空，不能被当成缺省", "", ReaderWebPageRequest.parse(
            args("action" to "fill", "selector" to "input[name=q]", "text" to ""),
        ).text)
    }

    @Test fun openSnapshotScrollBackAndCloseRejectForeignFields() {
        assertEquals("open", ReaderWebPageRequest.parse(args("action" to "open")).action)
        assertEquals("snapshot", ReaderWebPageRequest.parse(args("action" to "snapshot")).action)
        assertEquals("back", ReaderWebPageRequest.parse(args("action" to "back")).action)
        assertEquals("close", ReaderWebPageRequest.parse(args("action" to "close")).action)
        assertEquals(-600, ReaderWebPageRequest.parse(args("action" to "scroll", "deltaY" to "-600")).deltaY)

        fun fails(vararg pairs: Pair<String, String>): Boolean = runCatching {
            ReaderWebPageRequest.parse(args(*pairs))
        }.isFailure
        assertTrue("open 带 selector 应拒绝", fails("action" to "open", "selector" to "#a"))
        assertTrue("snapshot 带 index 应拒绝", fails("action" to "snapshot", "index" to "1"))
        assertTrue("scroll 必须给 deltaY", fails("action" to "scroll"))
        assertTrue("scroll 不接受 selector", fails("action" to "scroll", "deltaY" to "100", "selector" to "#a"))
        assertTrue("click 必须给 selector", fails("action" to "click"))
        assertTrue("click 不接受 text", fails("action" to "click", "selector" to "#a", "text" to "x"))
        assertTrue("fill 必须给 text", fails("action" to "fill", "selector" to "input"))
        assertTrue("未知动作应拒绝", fails("action" to "navigate"))
        assertTrue("URL 不是合法参数", fails("action" to "open", "url" to "http://evil.example/"))
    }

    @Test fun pagingOffsetsAndLimitAreClamped() {
        val parsed = ReaderWebPageRequest.parse(
            args("action" to "snapshot", "textOffset" to "120", "elementOffset" to "30", "limit" to "500"),
        )
        assertEquals(120, parsed.textOffset)
        assertEquals(30, parsed.elementOffset)
        assertEquals("limit 夹到上限", ReaderWebPageRequest.MAX_ELEMENT_LIMIT, parsed.limit)
        val low = ReaderWebPageRequest.parse(args("action" to "snapshot", "limit" to "0"))
        assertEquals("limit 至少 1", 1, low.limit)
        assertTrue(
            "负 offset 应拒绝",
            runCatching { ReaderWebPageRequest.parse(args("action" to "snapshot", "textOffset" to "-1")) }.isFailure,
        )
        assertEquals(
            "默认元素条数应为 30",
            ReaderWebPageRequest.DEFAULT_ELEMENT_LIMIT,
            ReaderWebPageRequest.parse(args("action" to "snapshot")).limit,
        )
    }

    // ---------------- 回包裁剪/分段 ----------------

    private fun element(order: Int, text: String) = ReaderWebUiPolicy.Element(
        order = order,
        index = order % 3,
        tag = "a",
        text = text,
        selector = "#item-$order",
    )

    private fun snapshot(
        text: String = "正文".repeat(2_000),
        elements: List<ReaderWebUiPolicy.Element> = (0 until 60).map { element(it, "第${it}项") },
        textTruncated: Boolean = false,
    ) = ReaderWebUiPolicy.Snapshot(
        title = "阅读 - 书架",
        path = "/#/bookshelf?page=1",
        text = text,
        textTruncated = textTruncated,
        elements = elements,
        elementLimitReached = elements.size >= 80,
    )

    @Test fun snapshotPayloadDefaultsToPrefixedTextAndThirtyElements() {
        val snap = snapshot()
        val payload = ReaderWebPagePayload.build(
            gson, "open", snap, "http://127.0.0.1:1162", 3L,
            ReaderWebPageRequest.parse(args("action" to "open")),
        )
        assertEquals("web_ui", payload["mode"])
        assertEquals("ok", payload["status"])
        assertEquals("open", payload["action"])
        assertEquals("http://127.0.0.1:1162", payload["originEcho"])
        assertEquals(3L, payload["configRevision"])
        assertEquals("正文默认预览 ≤3000 字符", 3_000, (payload["text"] as String).length)
        assertEquals(30, (payload["elements"] as List<*>).size)
        assertEquals(30, payload["nextElementOffset"])
        assertEquals(true, payload["hasMoreElements"])
        assertEquals(3_000, payload["nextTextOffset"])
        assertEquals(true, payload["hasMoreText"])
        assertEquals("正文总长以原文计", snap.text.length, payload["textTotalChars"])
        assertFalse(payload.containsKey("url"))
        assertFalse(payload.containsKey("href") && payload["href"] != null)
    }

    @Test fun pagingOffsetsSliceTextAndElements() {
        val snap = snapshot(text = "正文".repeat(5_000))
        val page2 = ReaderWebPagePayload.build(
            gson, "snapshot", snap, null, 0L,
            ReaderWebPageRequest.parse(
                args("action" to "snapshot", "textOffset" to "6000", "elementOffset" to "30", "limit" to "10"),
            ),
        )
        assertEquals(6_000, page2["textOffset"])
        assertEquals(snap.text.substring(6_000, 9_000), page2["text"])
        assertEquals(9_000, page2["nextTextOffset"])
        assertEquals(30, page2["elementOffset"])
        assertEquals(40, page2["nextElementOffset"])
        val elements = page2["elements"] as List<*>
        assertEquals(10, elements.size)
        assertEquals("#item-30", (elements.first() as Map<*, *>)["selector"])
        assertEquals(30, (elements.first() as Map<*, *>)["order"])

        // offset 超过长度时给空片段而不是越界
        val beyond = ReaderWebPagePayload.build(
            gson, "snapshot", snap, null, 0L,
            ReaderWebPageRequest.parse(args("action" to "snapshot", "textOffset" to "999999", "elementOffset" to "999")),
        )
        assertEquals("", beyond["text"])
        assertEquals(0, (beyond["elements"] as List<*>).size)
    }

    @Test fun payloadStaysUnderCharBudgetEvenForHostilePages() {
        // 最坏情况：80 条元素，每条 selector 240、text 120 都写满，正文 12000 字
        val hostile = snapshot(
            text = "长".repeat(12_000),
            elements = (0 until 80).map {
                ReaderWebUiPolicy.Element(
                    order = it,
                    index = it,
                    tag = "button",
                    text = "很长的元素文本".repeat(20),
                    selector = "#s$it" + ".class-name".repeat(20),
                    placeholder = "占位文本".repeat(20),
                    name = "field-name-$it",
                )
            },
            textTruncated = true,
        )
        val payload = ReaderWebPagePayload.build(
            gson, "snapshot", hostile, "http://<已隐藏>:1162", 7L,
            ReaderWebPageRequest.parse(args("action" to "snapshot", "limit" to "80")),
        )
        val json = gson.toJson(payload)
        assertTrue("单次结果必须 < 11.5k，实际 ${json.length}", json.length < 11_500)
        assertTrue(json.length <= ReaderWebPagePayload.MAX_RESULT_CHARS)
        // 收缩后仍是完整 JSON：可被解析且分段字段自洽
        val parsed = com.google.gson.JsonParser.parseString(json).asJsonObject
        val returned = parsed.getAsJsonArray("elements").size()
        assertTrue("必须真的收缩（否则 80 条会溢出）", returned in 1 until 80)
        assertEquals(returned, parsed.get("nextElementOffset").asInt - parsed.get("elementOffset").asInt)
        assertTrue("收缩后仍要提示还有更多", parsed.get("hasMoreElements").asBoolean)
        // 正文预算在元素收缩之后仍保留可用长度
        assertTrue(
            "正文不能只剩几个字",
            parsed.get("text").asString.length >= ReaderWebPagePayload.MIN_TEXT_PREVIEW_CHARS,
        )
    }

    @Test fun sensitiveValuesAndCredentialsAreNotEchoedByTheToolLayer() {
        // 工具层只做裁剪：策略层已剔除敏感值，这里锁定不额外补回 host/绝对地址
        val payload = ReaderWebPagePayload.build(
            gson, "snapshot",
            ReaderWebUiPolicy.Snapshot(
                title = "阅读",
                path = "/#/book/1",
                text = "书架",
                textTruncated = false,
                elements = listOf(
                    ReaderWebUiPolicy.Element(0, 0, "input", "", "#pwd", type = "password", name = "password"),
                ),
                elementLimitReached = false,
            ),
            "http://127.0.0.1:1162", 1L,
            ReaderWebPageRequest.parse(args("action" to "snapshot")),
        )
        val element = (payload["elements"] as List<*>).single() as Map<*, *>
        assertFalse("密码框不回显值", element.containsKey("value"))
        val json = gson.toJson(payload)
        assertFalse("不得回显令牌类字段", json.contains("Authorization"))
        assertFalse("不得回显保存地址的私网主机", json.contains("192.168"))
    }

    @Test fun closePayloadIsOfflineAndErrorPayloadCarriesCode() {
        val closed = ReaderWebPagePayload.closed()
        assertEquals("closed", closed["status"])
        assertEquals(true, closed["closed"])
        assertEquals("close", closed["action"])
        assertEquals("web_ui", closed["mode"])
        assertFalse("close 不访问网络，不回地址", closed.containsKey("originEcho"))

        val error = ReaderWebPagePayload.error("NOT_CONFIGURED", "尚未配置阅读 Web 服务地址", "configure_reader_web_address")
        assertEquals("error", error["status"])
        assertEquals("NOT_CONFIGURED", error["errorCode"])
        assertEquals("configure_reader_web_address", error["nextAction"])
        assertEquals(8, ReaderWebPageRequest.ACTIONS.size)
    }

    // ---------------- fetch_page 同 origin 引导 ----------------

    private fun config(baseUrl: String, revision: Long = 5L) =
        ReaderWebConfigStore.Config(baseUrl, "", revision)

    @Test fun fetchPageGuidesWhenOriginMatchesSavedReader() {
        val saved = config("http://127.0.0.1:1162")
        // 同 scheme+host+port 的任意路径/参数/片段都算同一 origin
        listOf(
            "http://127.0.0.1:1162",
            "http://127.0.0.1:1162/",
            "http://127.0.0.1:1162/#/bookshelf",
            "http://127.0.0.1:1162/book/1?q=%E6%B5%8B",
        ).forEach { assertTrue("应命中：$it", ReaderWebOriginHint.matches(it, saved)) }

        val payload = ReaderWebOriginHint.payload(saved)
        assertEquals("reader_web_origin", payload["status"])
        assertEquals("reader_web_page", payload["nextAction"])
        assertEquals("web_ui", payload["mode"])
        assertEquals(false, payload["networkRequest"])
        assertEquals("http://127.0.0.1:1162", payload["originEcho"])
        assertEquals(5L, payload["configRevision"])
        assertTrue((payload["hint"] as String).contains("reader_web_status"))
        val json = gson.toJson(payload)
        assertFalse("引导回包不得回显令牌", json.contains("token"))
    }

    /**
     * 引导回包本身必须是「本地引导」而不是服务端返回：读它不能当成执行过 GET /getBookSource，
     * 更不能当成写入成功。
     */
    @Test fun fetchPageHintDeclaresItIssuedNoRequestAndIsNotAServerEcho() {
        val payload = ReaderWebOriginHint.payload(config("http://127.0.0.1:1162"))
        assertEquals("没有向服务端发 HTTP", false, payload["requestIssued"])
        assertEquals("触发引导的工具就是 fetch_page", "fetch_page", payload["requestedTool"])
        val note = payload["requestNote"] as String
        assertTrue("必须明说这是 MCP 工具引导", note.contains("MCP 工具引导"))
        assertTrue("必须明说没有发送 HTTP", note.contains("没有向服务端发送 HTTP"))
        assertTrue("必须明说不是服务端 echo", note.contains("不是服务端 echo"))
        assertTrue("必须明说不是接口返回", note.contains("不是接口返回"))
        assertTrue("必须点名 GET /getBookSource 不能被当成执行过", note.contains("GET /getBookSource"))
        assertTrue("必须点名 POST /saveBookSource", note.contains("POST /saveBookSource"))
        assertTrue("必须指向真正会读写书源的工具", note.contains("reader_web_sync_source"))
        assertFalse("引导不得暗示已经写成功", payload.toString().contains("written=true"))
    }

    /** 同步失败阶段必须有人类可读标签（lookup / save / readback），不能都落成 null。 */
    @Test fun syncStageLabelsCoverLookupSaveReadback() {
        val lookup = ReaderWebGuidance.describe("INCOMPATIBLE", "lookup", "返回内容不是 ReturnData")
        assertTrue(lookup.stageLabel!!.contains("预检"))
        assertTrue("必须点明查重用的是 GET /getBookSource", lookup.stageLabel.contains("GET /getBookSource"))
        val save = ReaderWebGuidance.describe("HTTP_ERROR", "save", "HTTP 500")
        assertTrue(save.stageLabel!!.contains("写入"))
        assertTrue("必须点明写入是 POST /saveBookSource", save.stageLabel.contains("POST /saveBookSource"))
        val readback = ReaderWebGuidance.describe("READBACK_FAILED", "readback", "回读失败")
        assertTrue(readback.stageLabel!!.contains("回读"))
        // 既有阶段标签不得回退
        assertEquals("读取检验状态", ReaderWebGuidance.describe("HTTP_ERROR", "states", "x").stageLabel)
        assertNull("未知阶段不给误导标签", ReaderWebGuidance.describe("HTTP_ERROR", "unknown_stage", "x").stageLabel)
    }

    @Test fun fetchPageDoesNotInterceptOtherOrigins() {
        val saved = config("http://127.0.0.1:1162")
        assertEquals("localhost 归一后与 127.0.0.1 同 origin", true,
            ReaderWebOriginHint.matches("http://localhost:1162/", saved))
        listOf(
            "http://127.0.0.1:1122" to "别的端口不拦（模型自己写错的端口仍按通用抓取走，由抓取结果如实反馈）",
            "http://127.0.0.1" to "默认端口不同",
            "https://127.0.0.1:1162" to "scheme 不同",
            "http://192.168.1.8:1162" to "host 不同",
            "http://127.0.0.1:1162.evil.example" to "伪装域名",
            "https://example.com/reader" to "公网地址不受影响",
            "http://192.168.1.8:8080/api" to "其它内网地址不受影响",
        ).forEach { (url, why) ->
            assertFalse(why, ReaderWebOriginHint.matches(url, saved))
        }
        assertFalse("未配置时不拦截", ReaderWebOriginHint.matches("http://127.0.0.1:1162", null))
        assertFalse("非法 URL 不拦截", ReaderWebOriginHint.matches("not a url", saved))
        assertFalse("非 http(s) 不拦截", ReaderWebOriginHint.matches("file:///sdcard/x", saved))
    }

    @Test fun nonLoopbackOriginEchoMasksHostButKeepsPort() {
        val payload = ReaderWebOriginHint.payload(config("http://192.168.1.8:1162"))
        assertEquals("http://<已隐藏>:1162", payload["originEcho"])
        assertFalse("不得回显私网主机", gson.toJson(payload).contains("192.168"))
    }

    @Test fun errorCodesMapToSafeNextActions() {
        assertEquals("configure_reader_web_address", readerWebPageNextAction("NOT_CONFIGURED"))
        assertEquals("reader_web_page(action=open)", readerWebPageNextAction("NOT_OPENED"))
        assertEquals("reader_web_page(action=open)", readerWebPageNextAction("TIMEOUT"))
        assertEquals("reader_web_page(action=open)", readerWebPageNextAction("OFF_ORIGIN"))
        assertEquals("reader_web_page(action=snapshot)", readerWebPageNextAction("TARGET_NOT_FOUND"))
        // click/press_enter 派发成功但未观察到生效：下一步是读快照看实况，不能当成已提交
        assertEquals("reader_web_page(action=snapshot)", readerWebPageNextAction("NO_EFFECT"))
        assertEquals("reader_web_page(action=snapshot)", readerWebPageNextAction("ACTION_UNVERIFIED"))
        assertEquals("reader_web_page(action=snapshot)", readerWebPageNextAction("INVALID_SELECTOR"))
        assertEquals("未知码不给误导性建议", null, readerWebPageNextAction("SCRIPT_ERROR"))
        // off-origin 必须如实返回，不能被吞成成功
        val offOrigin = ReaderWebPagePayload.error("OFF_ORIGIN", "页面已离开已保存的阅读 Web 服务地址", readerWebPageNextAction("OFF_ORIGIN"))
        assertEquals("error", offOrigin["status"])
        assertEquals("OFF_ORIGIN", offOrigin["errorCode"])
    }

    @Test fun requestRejectsEveryNonDomParameterName() {
        // 地址类/脚本类参数名一律按未知键拒绝（含大小写变体）
        listOf(
            "url", "host", "headers", "js", "script", "baseUrl", "origin", "port", "cookie",
            "Url", "Headers", "JS",
        ).forEach { key ->
            val failure = runCatching {
                ReaderWebPageRequest.parse(args("action" to "open", key to "x"))
            }.exceptionOrNull()
            assertTrue("$key 应被拒绝", failure is IllegalArgumentException)
            assertTrue("拒绝信息要说明只做固定 DOM 操作", failure!!.message!!.contains("固定 DOM 操作"))
        }
        // contextId 由客户端注入，不算越权参数
        assertEquals("snapshot", ReaderWebPageRequest.parse(args("action" to "snapshot", "contextId" to "ctx-1")).action)
    }

    @Test fun configEchoIsComputedWithoutNetwork() {
        // 回显来自已保存配置的掩码 origin，不联网、不读密钥以外的字段
        val payload = ReaderWebOriginHint.payload(config("http://127.0.0.1:1162", 9L))
        assertEquals("http://127.0.0.1:1162", payload["originEcho"])
        assertEquals(9L, payload["configRevision"])
    }

    // ---------------- initialize instructions 走线 ----------------

    @Test fun initializeHandshakeCarriesInstructions() = testApplication {
        application {
            configureStudioMcp(
                configProvider = { McpConfigStore.Config(1237, false, "") },
                allowedHosts = listOf("localhost"),
                allowedOrigins = listOf("http://localhost"),
            ) {
                // 走真实 SDK：instructions 必须出现在 initialize 结果里，而不是只存在于源码常量
                io.modelcontextprotocol.kotlin.sdk.server.Server(
                    serverInfo = io.modelcontextprotocol.kotlin.sdk.types.Implementation("t", "1"),
                    options = io.modelcontextprotocol.kotlin.sdk.server.ServerOptions(
                        capabilities = io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities(),
                    ),
                    instructions = StudioMcpServer.MCP_INSTRUCTIONS,
                )
            }
        }
        val response = client.post("/mcp") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, io.ktor.http.ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",""" +
                    """"capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
            )
        }
        val body = response.bodyAsText()
        assertTrue("initialize 未回 instructions：$body", body.contains("reader_web_page"))
        assertTrue("instructions 必须提醒不要猜默认端口：$body", body.contains("不要猜默认端口"))
        assertTrue("instructions 必须指向 originEcho 读端口：$body", body.contains("originEcho"))
        assertTrue("instructions 必须说明官方 API 只是可选：$body", body.contains("可选"))
    }
}
