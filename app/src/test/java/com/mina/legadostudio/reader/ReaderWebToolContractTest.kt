package com.mina.legadostudio.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `reader_web_verify` 的注册契约（源码级，与 ReadmeToolTableContractTest/McpToolCatalogTest 同风格）：
 * 工具必须注册、标注为 openWrite，且不把 host/路径/请求头暴露成参数；
 * MCP 设置页必须有阅读 Web 服务卡片；配置与 MCP 配置分离、不推进 ContextEpoch。
 */
class ReaderWebToolContractTest {

    private fun firstExisting(vararg paths: String): File =
        paths.map(::File).firstOrNull { it.isFile } ?: error("not found: ${paths.toList()}")

    private fun mcpSources(): String {
        val dir = listOf(
            File("src/main/java/com/mina/legadostudio/mcp"),
            File("app/src/main/java/com/mina/legadostudio/mcp"),
        ).first { it.isDirectory }
        return dir.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }
            .joinToString("\n") { it.readText() }
    }

    private fun readerSources(): String {
        val dir = listOf(
            File("src/main/java/com/mina/legadostudio/reader"),
            File("app/src/main/java/com/mina/legadostudio/reader"),
        ).first { it.isDirectory }
        return dir.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }
            .joinToString("\n") { it.readText() }
    }

    private fun readerToolSource(): String = firstExisting(
        "src/main/java/com/mina/legadostudio/mcp/McpReaderWebTools.kt",
        "app/src/main/java/com/mina/legadostudio/mcp/McpReaderWebTools.kt",
    ).readText()

    @Test fun readerToolIsRegisteredAndOpenWrite() {
        val tools = mcpSources()
        assertTrue(tools.contains("\"reader_web_verify\""))
        val tool = readerToolSource()
        assertTrue("必须以 openWrite 标注（会触发阅读端检验）", tool.contains("toolAnnotations = openWrite"))
        // 只看 reader_web_verify 的注册块：同文件的页面工具会回包同源 path，但同样不接受地址参数
        val verify = tool.substring(tool.indexOf("server.tool(\"reader_web_verify\""))
            .substringBefore("/** `reader_web_page` 的完整描述")
        assertTrue(verify.contains("\"bookSourceUrl\""))
        assertTrue(verify.contains("\"keyword\""))
        assertTrue(verify.contains("\"timeoutSec\""))
        assertFalse(verify.contains("\"url\" to"))
        assertFalse(verify.contains("\"header"))
        assertFalse(verify.contains("\"host\""))
        assertFalse("不得提供任意路径参数", verify.contains("\"path\""))
    }

    @Test fun toolDescriptionsLeadWithBoundaryAndAnnotationsAreCorrect() {
        val tool = readerToolSource()
        // 三个工具都必须注册
        listOf("reader_web_status", "reader_web_sync_source", "reader_web_verify")
            .forEach { assertTrue("缺少工具 $it", tool.contains("\"$it\"")) }
        // 描述前 100 字符先交代边界（只检验已保存/不自动导入/旧版仅日志）
        fun descriptionAt(name: String): String {
            val idx = tool.indexOf("server.tool(\"$name\"")
            assertTrue("找不到 $name 注册", idx >= 0)
            val nameEnd = idx + "server.tool(\"$name\"".length
            val open = tool.indexOf('"', nameEnd)
            val close = tool.indexOf('"', open + 1)
            return tool.substring(open + 1, close)
        }
        val statusHead = descriptionAt("reader_web_status").take(100)
        assertTrue("status 首句必须说明只检验已保存配置", statusHead.contains("只读") && statusHead.contains("已保存"))
        val syncHead = descriptionAt("reader_web_sync_source").take(100)
        assertTrue("sync 首句必须说明只写已保存成品", syncHead.contains("已保存项目"))
        assertTrue("sync 必须说明可以新增书源", syncHead.contains("新增"))
        assertTrue("sync 必须说明覆盖要显式 overwrite", syncHead.contains("overwrite=true"))
        assertFalse("不得声称 sync 只操作已保存书源", tool.contains("三个工具都只操作"))
        val verifyHead = descriptionAt("reader_web_verify").take(100)
        assertTrue("verify 首句必须说明只检验已保存", verifyHead.contains("已保存"))
        assertTrue("verify 首句必须说明不自动导入", verifyHead.contains("不自动导入") || verifyHead.contains("不导入"))
        assertTrue("verify 首句必须说明旧版仅日志不判 PASS", verifyHead.contains("不判 PASSED"))

        // 读/写 annotation 必须与副作用一致
        fun registration(name: String): String {
            val idx = tool.indexOf("server.tool(\"$name\"")
            val next = tool.indexOf("server.tool(", idx + 1)
            return tool.substring(idx, if (next > 0) next else tool.length)
        }
        assertTrue(registration("reader_web_status").contains("toolAnnotations = readOnly"))
        assertTrue(
            "sync 可能在 overwrite=true 时覆盖阅读里的现有书源：必须标 destructiveHint 的开放世界写入",
            registration("reader_web_sync_source").contains("toolAnnotations = openDestructive"),
        )
        assertFalse(
            "不得把 sync 标成幂等 write",
            registration("reader_web_sync_source").contains("toolAnnotations = write"),
        )
        assertFalse(registration("reader_web_sync_source").contains("toolAnnotations = readOnly"))
    }

    @Test fun readerWebUiSessionIsASingleProcessWideInstance() {
        val app = firstExisting(
            "src/main/java/com/mina/legadostudio/StudioApplication.kt",
            "app/src/main/java/com/mina/legadostudio/StudioApplication.kt",
        ).readText()
        val sessionBlock = app.substring(app.indexOf("val readerWebUiSession"))
            .substringBefore("val taskContexts")
        assertTrue("必须是 Application 上的单例（跨 MCP 调用保留 SPA DOM/JS）", sessionBlock.contains("by lazy"))
        assertTrue("目标只来自已保存配置", sessionBlock.contains("readerWebConfig.load()"))
        assertFalse("会话不得自行读 MCP 配置", sessionBlock.contains("McpConfigStore"))
        // 工具层必须用这个单例，而不是每次调用新建会话
        val tool = readerToolSource()
        assertTrue(tool.contains("app.readerWebUiSession"))
        assertFalse("不得 new 会话（会丢失页面状态）", tool.contains("ReaderWebUiSession("))
        assertTrue("click/fill 的副作用必须如实标注", tool.contains("openWrite"))
    }

    @Test fun syncToolTakesProjectIdNotAFreeFormSource() {        val tool = readerToolSource()
        val sync = tool.substring(tool.indexOf("server.tool(\"reader_web_sync_source\""))
            .substringBefore("server.tool(\"reader_web_verify\"")
        assertTrue(sync.contains("\"projectId\""))
        assertTrue(sync.contains("\"overwrite\""))
        assertFalse("同步不得接受模型另传书源", sync.contains("\"source\""))
        assertFalse(sync.contains("\"url\""))
        assertFalse(sync.contains("\"header"))
    }

    @Test fun readerClientUsesOnlyFixedAllowlistRoutes() {
        val client = readerSources()
        listOf(
            "/getJsSourceApiTokenRequired",
            "/getBookSourcesForManagement",
            "/startBookSourceCheck",
            "/getBookSourceCheckStates",
            // 旧版公开读路由：只用于核实「是不是阅读」，不得扩展成任意路径
            "/getBookSource\"",
            "/getBookSources\"",
        ).forEach { assertTrue("缺少固定路由 $it", client.contains(it)) }
        // 禁重定向、无 cookie jar、无 HTTP 日志/书源 UA
        assertTrue(client.contains("followRedirects(false)"))
        assertTrue(client.contains("followSslRedirects(false)"))
        assertFalse("不得复用书源 Cookie", client.contains("cookieJar("))
        assertFalse("不得复用 HTTP 日志", client.contains("HttpLogRecorder"))
        assertFalse("不得复用书源 UA", client.contains("DEFAULT_UA"))
        assertTrue("必须检查 isSuccess", client.contains("isSuccess"))
        assertTrue("必须限制响应体", client.contains("MAX_BODY_BYTES"))
        assertTrue("局域网直连不得走系统代理", client.contains("Proxy.NO_PROXY"))
    }

    @Test fun readerResultNeverEntersCheckEvidenceRegistry() {
        val tool = readerToolSource()
        assertFalse("阅读端结果不得登记为本实例证据", tool.contains("CheckEvidenceRegistry.register"))
        // 回包必须标注来源（payload 构造在 verifier，工具只负责序列化）
        assertTrue(tool.contains("toPayload()"))
        assertTrue(readerSources().contains("\"evidenceScope\" to \"reader_web\""))
    }

    @Test fun readerConfigIsSeparateFromMcpConfigAndUiHasCard() {
        val app = firstExisting(
            "src/main/java/com/mina/legadostudio/StudioApplication.kt",
            "app/src/main/java/com/mina/legadostudio/StudioApplication.kt",
        ).readText()
        assertTrue(app.contains("readerWebConfig"))
        val appReaderLines = app.lines().filter { it.contains("readerWeb") }.joinToString("\n")
        assertFalse("阅读 Web 配置不得触发 ContextEpoch", appReaderLines.contains("contextEpoch"))
        val store = readerSources()
        assertTrue("令牌必须加密保存", store.contains("SecureStringStore"))
        assertTrue("与 MCP 配置分离的 prefs", store.contains("reader_web_config"))
        assertFalse("不得在保存时推进 epoch", store.contains("contextEpoch"))
        val ui = firstExisting(
            "src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt",
            "app/src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt",
        ).readText()
        assertTrue(ui.contains("阅读 Web 服务"))
        assertTrue(ui.contains("测试连接"))
        assertTrue("测试连接必须离开主线程", ui.contains("withContext(Dispatchers.IO)"))
        assertFalse("不得预填用户私网地址", ui.contains("192.168.1."))
        assertTrue("已存令牌不回显", ui.contains("已保存，留空则不修改"))
        assertTrue("令牌输入框必须遮罩", ui.contains("PasswordVisualTransformation"))
        assertTrue("保存当前输入失败时不得回退测旧地址", ui.contains("return@OutlinedButton"))
        // 版本差异必须分开提示，不能再压成一句「不像阅读」
        assertFalse("不得把版本差异压成「不像阅读」", ui.contains("该地址不像阅读 Web 服务"))
        assertTrue("必须按错误阶段展示", ui.contains("stageLabel"))
        assertTrue("必须提供一键回环但由用户点选", ui.contains("改用同机回环"))
        val verifier = readerSources()
        assertTrue("必须用旧 revision 区分本次新状态", verifier.contains("oldRevision"))
        assertTrue("读到旧状态只能报 stale_state", verifier.contains("\"stale_state\""))
        assertTrue("拿不到本次版本必须报错", verifier.contains("SOURCE_REVISION_UNKNOWN"))
        assertTrue("必须核对会话令牌", verifier.contains("\"session_replaced\""))
        assertTrue("无法证明归属时只能报 session_unconfirmed", verifier.contains("\"session_unconfirmed\""))
        assertTrue("结果不得宣称四阶段全部通过", !verifier.contains("四阶段全部通过"))
        // 令牌与 origin 绑定：保存必须走策略判定，换地址时清除旧令牌
        assertTrue("缺少配置策略", store.contains("ReaderWebConfigPolicy.resolve"))
        assertTrue("策略必须在换地址时清令牌", store.contains("previousOrigin != origin"))
        assertTrue("UI 必须提示改地址需重填令牌", ui.contains("修改地址后需重新填写"))
        // 一次检验使用固定快照，中途改配置不影响进行中的调用
        assertTrue("检验必须固定配置快照", verifier.contains("pinConfig()"))
        assertTrue("配置快照必须由客户端提供", readerSources().contains("fun pinConfig()"))
    }

    /**
     * 改阅读 Web 地址**不重启 MCP、不推进 ContextEpoch**（现场误判「改地址要重启 MCP」）。
     *
     * 只看源码契约：配置存储不碰 McpService/ContextEpoch，「阅读 Web 服务」卡片里的保存/测试/清除
     * 也不调用 restart/bump。MCP 会话逻辑本身不动（报告只与时间相关，无法定因）。
     */
    @Test fun readerWebAddressChangesDoNotRestartMcpOrBumpContextEpoch() {
        val store = firstExisting(
            "src/main/java/com/mina/legadostudio/reader/ReaderWebConfigStore.kt",
            "app/src/main/java/com/mina/legadostudio/reader/ReaderWebConfigStore.kt",
        ).readText()
        // 只看代码行：KDoc 里说明「不推进 ContextEpoch」的注释不算引用
        val storeCode = store.lines()
            .filterNot { line -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
            .joinToString("\n")
        listOf("McpService", "ContextEpoch", "contextEpoch", "bump(").forEach {
            assertFalse("阅读 Web 配置存储不得引用 $it", storeCode.contains(it))
        }
        // 配置是独立 prefs，不读 MCP 配置
        assertTrue(store.contains("\"reader_web_config\""))

        val ui = firstExisting(
            "src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt",
            "app/src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt",
        ).readText()
        val cardStart = ui.indexOf("Text(\"阅读 Web 服务\"")
        assertTrue("缺少「阅读 Web 服务」卡片", cardStart >= 0)
        // 卡片到下一个列表项之间：保存/测试连接/清除都在这里
        val nextItem = ui.indexOf("\n                        item {", cardStart)
        val card = ui.substring(cardStart, if (nextItem > cardStart) nextItem else ui.length)
        listOf("McpService.restart", "contextEpoch", "bump(").forEach {
            assertFalse("阅读 Web 服务卡片不得调用 $it", card.contains(it))
        }
        assertTrue("卡片仍有保存入口", card.contains("readerWebConfig.save"))
        assertTrue("卡片仍有测试连接入口", card.contains("readerWebBridge.status()"))
        // MCP 自身配置区仍保留重启逻辑：不能为了这条约束把既有行为删掉
        assertTrue("MCP 配置保存仍会重启服务", ui.contains("McpService.restart(context)"))
    }

    /** 同步工具回包必须带实际请求顺序与写入结局字段，避免模型再猜「GET /saveBookSource」。 */
    @Test fun syncResultPayloadCarriesRequestsAndHonestWriteOutcome() {
        val sync = readerSources()
        listOf(
            "\"requests\" to requests",
            "\"trace\" to traceOf(requests)",
            "\"writeAttempted\" to writeAttempted",
            "\"writeOutcome\" to writeOutcome",
        ).forEach { assertTrue("同步回包缺少 $it", sync.contains(it)) }
        // POST 永不自动重发：网络异常时只能报 unknown，不能声称没写
        assertTrue(sync.contains("WriteOutcome.UNKNOWN"))
        assertTrue(sync.contains("WriteOutcome.NOT_SENT"))
        assertTrue(sync.contains("WriteOutcome.ACCEPTED"))
        // 回读明确传 READBACK 阶段（不是默认 LOOKUP）
        assertTrue("回读必须显式用 READBACK 阶段", sync.contains("ReaderWebClient.Stage.READBACK"))
        assertTrue("预检必须显式用 LOOKUP 阶段", sync.contains("ReaderWebClient.Stage.LOOKUP"))
        // 工具层不得自己拼一份错误模板（两处字段会漂移）
        val tool = readerToolSource()
        assertTrue(tool.contains("ReaderWebSourceSync.failurePayload"))
        // 描述里明确写入是 POST、并提示不会自动重发
        val syncBlock = tool.substring(tool.indexOf("server.tool(\"reader_web_sync_source\""))
            .substringBefore("server.tool(\"reader_web_verify\"")
        assertTrue("描述必须点明 post 写入", syncBlock.contains("POST /saveBookSource"))
        assertTrue("描述必须点明不自动重发", syncBlock.contains("绝不自动重发"))
    }
}
