package com.mina.legadostudio.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiSurfaceContractTest {
    private fun mcpServerSources(): String {
        val dir = listOf(
            File("src/main/java/com/mina/legadostudio/mcp"),
            File("app/src/main/java/com/mina/legadostudio/mcp"),
        ).first { it.isDirectory }
        return dir.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }.joinToString("\n") { it.readText() }
    }

    private fun appKt(): String {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/StudioApp.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/StudioApp.kt"),
        )
        return candidates.first { it.isFile }.readText()
    }

    private fun walkAppSources(): Sequence<File> {
        val roots = listOf(File("src/main"), File("app/src/main")).filter { it.isDirectory }
        return roots.asSequence().flatMap { it.walkTopDown() }.filter { it.isFile && it.extension == "kt" }
    }

    @Test
    fun bottomBarHasMcpSourcesSkillsVerificationAndLogs() {
        val source = appKt()
        assertTrue(source.contains("StudioTab(\"mcp\""))
        assertTrue(source.contains("StudioTab(\"sources\""))
        assertTrue(source.contains("StudioTab(\"skills\""))
        assertTrue(source.contains("StudioTab(\"logs\""))
        assertFalse(source.contains("StudioTab(\"projects\""))
        assertTrue(source.contains("StudioTab(\"verification\", \"验证中心\""))
        assertFalse(source.contains("StudioTab(\"settings\""))
        assertFalse(source.contains("composable(\"projects\")"))
        assertFalse(source.contains("composable(\"settings\")"))
        assertFalse(source.contains("composable(\"webWorkbench\")"))
        assertFalse(source.contains("composable(\"advanced\")"))
        assertTrue(source.contains("composable(\"skills\")"))
        assertTrue(source.contains("composable(\"sources\")"))
        assertTrue(source.contains("composable(\"verification\")"))
        assertTrue(source.contains("composable(\"logs\")"))
    }

    /** 功能介绍页合同：guide 是 MCP 页内二级路由——可压栈、不在底栏、不进深链白名单 */
    @Test
    fun guideRouteIsInternalOnly() {
        val source = appKt()
        assertTrue(source.contains("composable(\"guide\")"))
        assertFalse(source.contains("StudioTab(\"guide\""))
        val deepLink = Regex("allowedDeepLinkRoutes\\s*=\\s*setOf\\(([^)]*)\\)").find(source)?.groupValues?.get(1).orEmpty()
        assertFalse("guide must not be a deep link target", deepLink.contains("\"guide\""))
        val mcp = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt"),
        ).first { it.isFile }.readText()
        assertTrue(mcp.contains("onOpenGuide"))
        assertTrue(mcp.contains("功能介绍"))
        val guide = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/FeatureGuideScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/FeatureGuideScreen.kt"),
        ).first { it.isFile }.readText()
        assertTrue(guide.contains("BuildConfig.VERSION_NAME"))
        assertTrue(guide.contains("capture_once"))
        assertTrue(guide.contains("get_http_log"))
        assertTrue(guide.contains("debug_source"))
        assertTrue(guide.contains("check_source"))
        assertTrue(guide.contains("cap:contextId"))
        assertTrue(guide.contains("redirectChain"))
        // 抓包工具链介绍与实际注册的 MCP 工具保持一致
        assertTrue(guide.contains("webview_capture"))
        assertTrue(guide.contains("poll_capture"))
        assertTrue(guide.contains("get_capture_resource"))
        assertTrue(guide.contains("list_captures"))
    }

    /** 技能包引用路径防回归锁：SKILL.md / index.md 里公开声明的 references/... 路径
     *  必须真实存在于 assets（get_skill_reference 走同一相对路径读 assets）；
     *  抓包取证能力选择表与单站案例门禁文案不得被删。 */
    @Test
    fun skillReferencesResolveAndKeepGuards() {
        val skillDir = listOf(
            File("src/main/assets/skills/legado-book-source"),
            File("app/src/main/assets/skills/legado-book-source"),
        ).first { it.isDirectory }
        val skillMd = File(skillDir, "SKILL.md").readText()
        val indexMd = File(skillDir, "references/index.md").readText()
        // xxx.md 是 index.md 示例命令里的占位符，不是真实声明路径
        val declared = Regex("""references/[A-Za-z0-9._-]+\.(?:md|yaml)""")
            .findAll(skillMd + indexMd).map { it.value }.toSet() - "references/xxx.md"
        assertTrue(declared.isNotEmpty())
        declared.forEach { rel ->
            assertTrue("声明的引用路径不存在: $rel", File(skillDir, rel).isFile)
        }
        // index.md 必须索引生成合同与字段模板
        assertTrue(indexMd.contains("references/generation-contract.md"))
        assertTrue(indexMd.contains("references/template.yaml"))
        // 抓包取证能力选择表：三类方式 + 读取链
        assertTrue(skillMd.contains("webview_capture"))
        assertTrue(skillMd.contains("capture_once"))
        assertTrue(skillMd.contains("poll_capture"))
        assertTrue(skillMd.contains("get_capture_resource"))
        assertTrue(skillMd.contains("list_captures"))
        // 单站案例门禁保持
        assertTrue(skillMd.contains("单站取证案例边界"))
        assertTrue(indexMd.contains("非通用模板"))
    }

    @Test
    fun appSourcesDoNotNameClientBrands() {
        val hits = walkAppSources()
            .filter { file ->
                val text = file.readText()
                Regex("(?i)rikkahub|operit").containsMatchIn(text)
            }
            .map { it.path }
            .toList()
        assertTrue("client brand mentions: $hits", hits.isEmpty())
    }

    @Test
    fun mcpScreenCopiesAuthMaterialWithoutSourceList() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("复制客户端配置"))
        assertTrue(source.contains("MCP 宿主"))
        assertTrue(source.contains("clientConfigJson"))
        assertTrue(source.contains("mcpServers"))
        assertTrue(source.contains("lanEndpoints"))
        assertTrue(source.contains("connectionUrl"))
        assertTrue(source.contains("saveCompactMode"))
        assertTrue(source.contains("loadCompactMode"))
        assertTrue(source.contains("连接模式"))
        assertTrue(source.contains("精简（省 token）"))
        assertTrue(source.contains("仅复制鉴权头"))
        assertTrue(source.contains("tokenHeaderLine"))
        assertTrue(source.contains("前置条件"))
        assertTrue(source.contains("悬浮窗"))
        assertTrue(source.contains("悬浮球"))
        assertTrue(source.contains("OverlayPrefs"))
        assertTrue(source.contains("VerificationOverlayManager"))
        assertTrue(source.contains("接入信息"))
        assertTrue(source.contains("服务器链接"))
        assertTrue(source.contains("请求头名称"))
        assertTrue(source.contains("请求头值"))
        assertTrue(source.contains("bearerTokenValue"))
        assertTrue(source.contains("AUTH_HEADER"))
        assertTrue(source.contains("配置文件客户端（JSON）"))
        assertFalse(source.contains("X-Studio-Token"))
        assertFalse(source.contains("已保存书源"))
        assertFalse(source.contains("导入至阅读"))
        assertFalse(source.contains("ReaderImport"))
        assertFalse(source.contains("复制 JSON"))
        assertFalse(source.contains("复制 Token"))
        assertFalse(source.contains("复制访问令牌"))
        assertFalse(source.contains("复制局域网 MCP"))
        assertFalse(source.contains("全局请求头"))
        assertFalse(source.contains("MCP 条目"))
        assertFalse(source.contains("启动后显示本机回环链接"))
    }

    /** 通知栏两个客户端 URL 出口（复制 Endpoint / 常驻通知文本）必须走同一个
     *  clientEndpoint helper，且 helper 读 compactMode 偏好经 connectionUrl 转换，
     *  不得绕过 App 内所选标准/精简模式直出原始 endpoint。 */
    @Test
    fun mcpServiceNotificationUrlsGoThroughClientEndpointHelper() {
        val source = listOf(
            File("src/main/java/com/mina/legadostudio/service/McpService.kt"),
            File("app/src/main/java/com/mina/legadostudio/service/McpService.kt"),
        ).first { it.isFile }.readText()
        fun bodyOf(signature: String): String {
            val start = source.indexOf(signature)
            if (start < 0) return ""
            val end = source.indexOf("\n    private fun ", start + signature.length)
            return source.substring(start, if (end > start) end else source.length)
        }
        val helper = bodyOf("private fun clientEndpoint()")
        assertTrue("clientEndpoint helper 须存在", helper.isNotEmpty())
        assertTrue("helper 须读模式偏好", helper.contains("loadCompactMode"))
        assertTrue("helper 须经 connectionUrl 转换", helper.contains("McpAccess.connectionUrl"))
        val copyBody = bodyOf("private fun copyEndpoint()")
        assertTrue("复制 Endpoint 须走 helper", copyBody.contains("clientEndpoint()"))
        val notifyBody = bodyOf("private fun updateNotification()")
        assertTrue("通知文本须走 helper", notifyBody.contains("clientEndpoint()"))
        assertFalse("复制不得直出原始 endpoint", copyBody.contains("endpoints.firstOrNull"))
        assertFalse("通知不得直出原始 endpoint", notifyBody.contains("endpoints.firstOrNull"))
    }

    @Test
    fun sourcesScreenImportsAndDeletes() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/SourcesScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/SourcesScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("导入至阅读"))
        assertTrue(source.contains("projects.delete"))
        assertTrue(source.contains("launchReaderImport"))
        assertTrue(source.contains("删除书源"))
        assertTrue(source.contains("SourceCatalog.groupByDomain"))
        assertTrue(source.contains("展开"))
        assertTrue(source.contains("分享JSON"))
        assertTrue(source.contains("Intent.ACTION_SEND"))
        assertTrue(source.contains("FileProvider.getUriForFile"))
        // 分享可靠性锁（Android 8+ 偶发唤不出/对方读不到流）：
        // ClipData.newUri 覆盖 Direct Share 目标授权；MIME 用 text/plain（application/json 在主流
        // 社交 App 的 ACTION_SEND 目标里支持差）；startActivity 回主线程（IO 线程调起会丢面板）
        assertTrue("分享须带 ClipData 授权", source.contains("ClipData.newUri"))
        assertTrue("书源分享 MIME 须 text/plain", source.contains("\"text/plain\""))
        assertTrue("startActivity 须在主线程", source.contains("Dispatchers.Main"))
        assertFalse(source.contains("复制 JSON"))
        assertFalse(source.contains("CreateDocument("))
    }

    /** 日志页导出范围防回归锁：HTTP 导出有三范围（当日/全部日期/仅已勾选），
     *  勾选导出与删除共用「勾选 ∩ 可见」解析，导出取数走 keyset 投影扫描 + 分批实体，禁 OFFSET 漂移 */
    @Test
    fun logsScreenExportScopes() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertFalse(source.contains("DiagnosticExporter"))
        assertTrue(source.contains("删除"))
        assertTrue(source.contains("LocalStudioFullscreen"))
        assertTrue(source.contains("HttpLogDetail"))
        // 三范围出口：范围 radio + 勾选快照 + 勾选∩可见解析（范围字面量与 SCOPE_* key 在 Plan 侧定义）
        assertTrue("导出须有范围选项", source.contains("exportScopes"))
        assertTrue("HTTP 导出须组装范围选项", source.contains("HttpLogExportPlan.scopeOptions"))
        val plan = listOf(
            File("src/main/java/com/mina/legadostudio/domain/HttpLogExportPlan.kt"),
            File("app/src/main/java/com/mina/legadostudio/domain/HttpLogExportPlan.kt"),
        ).first { it.isFile }.readText()
        assertTrue("须支持仅导出已勾选", plan.contains("仅导出已勾选") && plan.contains("SCOPE_SELECTED"))
        assertTrue("勾选须点击即快照", source.contains("exportSelectedSnapshot"))
        assertTrue("勾选导出须走「勾选∩可见」解析", source.contains("LogDeletePlan.resolveLogIds"))
        assertTrue("勾选快照须在点击时解析成 id", source.contains("exportSelectedSnapshot = LogDeletePlan.resolveLogIds"))
        // 取数通道：keyset 投影扫描（无 OFFSET）+ 分批实体回取 + 流式写出
        assertTrue("导出须走 HttpLogExportPlan", source.contains("HttpLogExportPlan.scan"))
        assertTrue("全库导出须用 keyset 投影页", source.contains("httpLogSummariesAllBeforeIdNoCapture"))
        assertTrue("实体须按 id 分批取", source.contains("httpLogsByIds"))
        assertTrue("导出须流式写出", source.contains("HttpLogExportWriter"))
        // 半成品文件：先写 .tmp 再原子改名，失败清 tmp
        assertTrue("导出须写 tmp 后原子改名", source.contains(".tmp") && source.contains("renameTo"))
        // 分桶交批直接用扫描的 idsByBucket，禁 O(N×K) 逐桶反查
        assertTrue("分桶须用 idsByBucket", source.contains("scan.idsByBucket"))
        assertFalse("分桶不得再逐桶反查 bucketOfId", source.contains("bucketOfId"))
        assertFalse("导出不得再用 OFFSET 实体页", source.contains("httpLogsByDayPageNoCapture("))
        // startActivity 须在主线程（IO 线程调起分享面板会丢授权/唤不出）
        assertTrue("导出分享须回主线程", source.contains("withContext(Dispatchers.Main) { context.startActivity(chooser) }"))
        // 锚点一致性：scan 与 writer 必须共用导出开始时的一次快照，不得各自取 registry
        assertTrue("导出须一次快照贯穿 scan+writer", source.contains("contextAnchors = ctxAnchors"))
        // contextId 多锚点歧义：哨兵值透出给归属器判 CONFLICT，不做「最近一次 wins」
        val attributor = listOf(
            File("src/main/java/com/mina/legadostudio/domain/HttpLogAttributor.kt"),
            File("app/src/main/java/com/mina/legadostudio/domain/HttpLogAttributor.kt"),
        ).first { it.isFile }.readText()
        assertTrue("ContextAnchorRegistry 须有歧义哨兵", attributor.contains("AMBIGUOUS_ANCHOR"))
        // 默认范围锁：弹窗必须走 defaultScopeKey（有勾选默认仅已勾选，防一键分享误发当日全库）；
        // remember 挂 exportScopes，切 Tab/日期/锚点重组后遵守当前快照、不留陈旧选择
        val dialog = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/LogExportDialog.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/LogExportDialog.kt"),
        ).first { it.isFile }.readText()
        assertTrue("弹窗默认范围须走 defaultScopeKey", dialog.contains("HttpLogExportPlan.defaultScopeKey(exportScopes)"))
        assertTrue("scopeKey 须随选项集重组", dialog.contains("remember(exportScopes)"))
        assertTrue("有勾选须默认仅已勾选", plan.contains("defaultScopeKey") && plan.contains("SCOPE_SELECTED"))
    }

    /** 日志页删除流防回归锁：删除目标必须走 LogDeletePlan 解析（仅删可见项、数字解析、切批），禁止再对 selected 直接 toLong */
    @Test
    fun logsScreenDeleteGoesThroughPlanResolver() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("LogDeletePlan.resolveLogIds"))
        assertTrue(source.contains("LogDeletePlan.resolveNames"))
        assertFalse(source.contains("selected.map { it.toLong() }"))
        assertFalse(source.contains("selected.map{it.toLong()}"))
    }

    /** 抓包页签防回归锁：CAPTURE 是紧凑入口页（三张入口卡进各自二级页 + 记录开关），
     *  表单/历史不再堆在页签内；普通 HTTP 隔离与按天 keyset 分页契约不变 */
    @Test
    fun logsScreenHasCaptureOnceForm() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        // 三个入口卡：逐次抓包 / 浏览器抓包 / 抓包会话历史，全部在主滚动容器（item{}）内
        assertTrue(source.contains("逐次抓包"))
        assertTrue(source.contains("浏览器抓包"))
        assertTrue(source.contains("抓包会话历史"))
        assertTrue("入口须跳到逐次抓包二级页", source.contains("onOpenCaptureOnce"))
        assertTrue("入口须跳到浏览器抓包二级页", source.contains("onOpenBrowserCapture"))
        assertTrue("入口须跳到会话历史二级页", source.contains("onOpenCaptureHistory"))
        // 入口卡须挂在 LazyColumn item 里（同一主滚动容器），不再是固定控制区+空列表
        assertTrue("抓包内容须在主滚动容器内", source.contains("LogsTab.CAPTURE -> {"))
        // 旧的内嵌形态不得复活：表单卡/历史卡/内嵌滚动区已迁出到独立页
        assertFalse("逐次抓包表单须迁到 CaptureOnceScreen", source.contains("CaptureOnceCard"))
        assertFalse("历史卡须迁到 CaptureHistoryScreen", source.contains("CaptureHistoryCard"))
        // 独立页签锁：CAPTURE 是 LogsTab 第五个内页签（不改底栏）
        assertTrue("抓包须为日志内独立页签", source.contains("LogsTab { OPERATION, HTTP, CAPTURE, CRASH, SNAPSHOT }"))
        assertTrue("抓包页签须在标签行露出", source.contains("LogsTab.CAPTURE to \"抓包\""))
        // HTTP 普通列表不得再锁定最近 500 条：已改为「全库日期导航 + 按天 keyset 分页」
        assertFalse("HTTP 列表不得锁死 observeHttpLogSummariesNoCapture(500)", source.contains("observeHttpLogSummariesNoCapture(500)"))
        assertTrue("HTTP 须按天 keyset 分页", source.contains("httpLogSummariesByDayBeforeIdNoCapture"))
        assertTrue("HTTP 日期导航须走全库 DISTINCT", source.contains("observeHttpLogDaysNoCapture"))
        assertTrue("新日志增量须走 afterId 通道", source.contains("httpLogSummariesAfterIdNoCapture"))
        assertTrue("窗口未穷尽须明示已加载部分", source.contains("已加载部分"))
        assertTrue("按天窗口须有手动加载更多", source.contains("加载更多"))
        // 状态一致性锁：翻页请求换身份（epoch+实例），回填只认发请求时那个 loading 页；
        // 飞行期新行并入最新 cur 而非被 next 覆盖；删除后窗口 removeIds 收敛
        assertTrue("翻页须 beginLoadMore 换身份", source.contains("beginLoadMore"))
        assertTrue("回填须同实例守卫", source.contains("cur === request"))
        assertTrue("翻页结果须 fold 到当前 cur", source.contains("foldNextPage"))
        assertTrue("删除后窗口须 removeIds 收敛", source.contains("removeIds"))
        assertTrue("HTTP 窗口须有 epoch", source.contains("epoch"))
        // HTTP 导出同样排除抓包记录（keyset 投影页 SQL 层 cap:% 排除），且文案用日全量总数
        assertTrue("HTTP 导出须走 NoCapture keyset 投影页", source.contains("httpLogSummariesByDayBeforeIdNoCapture"))
        assertTrue("导出计数须用日全量口径", source.contains("dayTotal"))
        assertTrue("锚点导出须提示筛选口径", source.contains("scopeHint"))
    }

    /** 「选择日期」弹窗整删防回归锁：两侧都提供按真实数据库日期的一键删除（不受已加载窗口/500 条上限），
     *  HTTP 侧 SQL 排除 cap:% 抓包、操作日志按天全删；入口显示条数且必须二次确认，删除后窗口重开防幽灵回填。 */
    @Test
    fun logsScreenHasDayLevelDelete() {
        val source = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
        ).first { it.isFile }.readText()
        // 删除目标：按天 DAO 直删（HTTP 排除 cap:%，操作日志全库口径），不得复用 deleteHttpLogsInRange（含抓包行）
        assertTrue("HTTP 整删须走 NoCapture 按天删除", source.contains("deleteHttpLogsByDayNoCapture"))
        assertTrue("操作日志整删须走按天全删", source.contains("deleteOperationLogsByDay"))
        assertFalse("整删不得复用 deleteHttpLogsInRange（会误删 cap:% 抓包）", source.contains("deleteHttpLogsInRange("))
        // 入口须二次确认且快照条数/日期/页签（防弹窗期间换日/翻页串数据）
        assertTrue("整删须有确认态快照", source.contains("pendingDayDelete"))
        assertTrue("确认文案须带删除字样的按钮", source.contains("删除 ${'$'}deleteDate 全部 ${'$'}deleteCount 条"))
        // 抓包隔离文案明示（避免用户误以为整删会清掉抓包会话）
        assertTrue("须明示 cap: 会话不受影响", source.contains("cap:") && source.contains("不受影响"))
        // 删除当前查看日后窗口须整页重开（飞行中的加载更多不得回填已删行）
        assertTrue("整删后须重开 HTTP 窗口", source.contains("httpDayRefreshTick"))

        val dao = listOf(
            File("src/main/java/com/mina/legadostudio/data/db/StudioDao.kt"),
            File("app/src/main/java/com/mina/legadostudio/data/db/StudioDao.kt"),
        ).first { it.isFile }.readText()
        // DAO 合同：按天删 HTTP 必须带 cap:% 排除谓词；操作日志有全库日期导航 + 按天 count + 按天 delete
        assertTrue("HTTP 按天删须排除 cap:%", dao.contains("fun deleteHttpLogsByDayNoCapture("))
        assertTrue("操作日志须有全库日期 DISTINCT", dao.contains("fun observeOperationLogDays()"))
        assertTrue("操作日志须有按天 count", dao.contains("fun countOperationLogsByDay("))
        assertTrue("操作日志须有按天 delete", dao.contains("fun deleteOperationLogsByDay("))
        // UI 须把两侧真实计数都接入弹窗（不用 500 窗口伪称全日）
        assertTrue("UI 须接操作日志全库日期流", source.contains("observeOperationLogDays"))
        assertTrue("UI 须接操作日志按天计数", source.contains("countOperationLogsByDay"))
        assertTrue("UI 须接 HTTP 按天计数（NoCapture）", source.contains("countHttpLogsByDayNoCapture"))
    }

    /** 抓包二级路由防回归锁：capture_once / capture_history 与 browser_capture 都是
     *  日志页内的内部二级路由——可压栈、不在底栏、不进深链白名单 */
    @Test
    fun captureSubRoutesAreInternal() {
        val source = appKt()
        assertTrue(source.contains("composable(\"capture_once\")"))
        assertTrue(source.contains("composable(\"capture_history\")"))
        assertTrue(source.contains("composable(\"browser_capture\")"))
        assertTrue(source.contains("CaptureOnceScreen"))
        assertTrue(source.contains("CaptureHistoryScreen"))
        assertTrue(source.contains("BrowserCaptureScreen"))
        val deepLink = Regex("allowedDeepLinkRoutes\\s*=\\s*setOf\\(([^)]*)\\)").find(source)?.groupValues?.get(1).orEmpty()
        assertFalse("capture_once must not be a deep link target", deepLink.contains("\"capture_once\""))
        assertFalse("capture_history must not be a deep link target", deepLink.contains("\"capture_history\""))
        assertFalse("browser_capture must not be a deep link target", deepLink.contains("\"browser_capture\""))
        assertFalse("capture_once must not be a bottom tab", source.contains("StudioTab(\"capture_once\""))
        assertFalse("capture_history must not be a bottom tab", source.contains("StudioTab(\"capture_history\""))
    }

    /** 逐次抓包独立页防回归锁：表单 + 执行 + 结果预览 + 详情/会话跳转都在单层 LazyColumn，无内嵌滚动 */
    @Test
    fun captureOnceScreenIsSingleScroll() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/CaptureOnceScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/CaptureOnceScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("app.captureOnce.run"))
        assertTrue(source.contains("CaptureOnce.Params"))
        assertTrue(source.contains("observeHttpLogsByContextId"))
        assertTrue(source.contains("recordingDisabled"))
        assertTrue(source.contains("LazyColumn"))
        assertTrue(source.contains("查看本次会话详情"))
        assertTrue("执行中须防重复发起", source.contains("if (captureBusy) return"))
        assertTrue("详情/会话覆盖层须置全屏标记", source.contains("LocalStudioFullscreen"))
        // 返回键顺序锁：页面级 BackHandler 在单条详情加载空窗（viewingFullLog==null、内部 handler 未注册）
        // 也要可靠生效——顺序固定为 单条详情 > 会话详情 > 退出页面，不依赖覆盖层 handler 注册时序
        assertTrue("覆盖层加载空窗须有外层 BackHandler 兜底", source.contains("BackHandler"))
        val httpIdIdx = source.indexOf("viewingHttpId != null -> viewingHttpId = null")
        val sessionIdIdx = source.indexOf("viewingCaptureContextId != null -> viewingCaptureContextId = null")
        assertTrue("返回键须先关单条详情再关会话详情", httpIdIdx in 1 until sessionIdIdx)
        assertFalse("结果区不得再用 300dp 内嵌滚动", source.contains("heightIn(max = 300.dp)"))
    }

    /** 抓包会话历史独立页防回归锁：单层 LazyColumn 承载筛选/行/加载更多，
     *  默认进入即加载、失败可重试、ID 全库精确查找保留，无 320dp 内嵌滚动 */
    @Test
    fun captureHistoryScreenIsSingleScroll() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/CaptureHistoryScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/CaptureHistoryScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("loadCapturePage"))
        assertTrue(source.contains("captureSessionSummaries"))
        assertTrue(source.contains("countCaptureSessions"))
        assertTrue(source.contains("LazyColumn"))
        assertTrue(source.contains("LaunchedEffect(Unit)"))
        assertTrue("ID 全库精确查找须保留", source.contains("countHttpLogsByContextId"))
        assertTrue("须混合两类抓包会话并按来源标注", source.contains("CaptureSessionKinds.uiLabel"))
        // 返回键顺序锁：页面级 BackHandler 在单条详情加载空窗（viewingFullLog==null、内部 handler 未注册）
        // 也要可靠生效——顺序固定为 单条详情 > 会话详情 > 退出页面，不依赖覆盖层 handler 注册时序
        assertTrue("覆盖层加载空窗须有外层 BackHandler 兜底", source.contains("BackHandler"))
        val httpIdIdx = source.indexOf("viewingHttpId != null -> viewingHttpId = null")
        val sessionIdIdx = source.indexOf("viewingCaptureContextId != null -> viewingCaptureContextId = null")
        assertTrue("返回键须先关单条详情再关会话详情", httpIdIdx in 1 until sessionIdIdx)
        assertFalse("不得再用 320dp 内嵌滚动区", source.contains("heightIn(max = 320.dp)"))
        // 「逐次抓包历史」误导文案已改为统称（含浏览器会话）
        assertFalse("不得再称「逐次抓包历史」", source.contains("逐次抓包历史按"))
        assertTrue("finalStatus 不得对非逐次会话冒称最终状态", source.contains("kind == \"逐次抓包\""))
    }

    /** 浏览器抓包页布局+安全防回归锁：控件可收起、面板默认折叠按实测可用高动态夹取、
     *  会话不跨进程还原对象（缺 dnsGuard 会降级私网守卫），重建须走 newSession 重开带守卫会话 */
    @Test
    fun browserCaptureScreenIsReaderFriendly() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/BrowserCaptureScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/BrowserCaptureScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        // 顶部常驻只留一条：URL 摘要 + 展开/收起 + 溢出菜单；完整地址栏与导航行进可折叠展开区
        assertTrue("须有可折叠的地址/导航展开区", source.contains("moreExpanded"))
        assertTrue("须有溢出菜单承载低频操作", source.contains("DropdownMenu"))
        assertTrue("溢出菜单须含结束并保存/导出/会话详情", source.contains("结束并保存") && source.contains("会话详情"))
        assertTrue("点 URL 须展开并聚焦地址栏", source.contains("focusAddressField") && source.contains("requestFocus"))
        // 事务面板默认折叠，展开高度 = 实测可用高 × 比例，且上限为 WebView 留最小高度
        assertTrue("事务面板须默认折叠", source.contains("showTxPanel by rememberSaveable { mutableStateOf(false) }"))
        assertTrue("面板高度须可步进调整", source.contains("txPanelFraction"))
        assertTrue("须按实测可用高夹取（键盘/窄屏不压死 WebView）", source.contains("BoxWithConstraints"))
        assertTrue("WebView 须有最小高度保护", source.contains("MinWebViewHeight"))
        assertTrue("须避让键盘", source.contains("imePadding"))
        // 安全策略锁：Session 不得跨进程还原成无 dnsGuard 的对象——
        // 不存在 Session Saver，重建必须走 newSession（带守卫的新会话），旧会话只经 onDispose 结算一次
        assertFalse("禁止还原无 dnsGuard 的 Session 对象", source.contains("CaptureSessionSaver"))
        assertFalse("禁止对 Session 用 rememberSaveable Saver", Regex("Saver<\\s*WebViewCapture\\.Session").containsMatchIn(source))
        assertTrue("重建须 newSession 重开带守卫会话", source.contains("app.webViewCapture.newSession(currentUrl"))
        assertTrue("URL 须 saveable 供重开会话", source.contains("currentUrl by rememberSaveable"))
        // WebView 按 contextId key 常驻，开合面板/地址区不重建
        assertTrue("WebView 须按会话 key 常驻", source.contains("key(activeSession.contextId)"))
        // 新会话先开成功再结算旧会话（失败不丢旧会话）
        assertTrue(source.contains("app.webViewCapture.newSession(target"))
        assertTrue(source.contains("finishSession(old"))
        assertTrue(
            "startSession 须先开新会话再结算",
            source.indexOf("app.webViewCapture.newSession(target") < source.indexOf("finishSession(old"),
        )
        // 覆盖层打开须通知全局隐藏底栏胶囊
        assertTrue("覆盖层须置 LocalStudioFullscreen", source.contains("LocalStudioFullscreen"))
        // 返回键顺序锁：覆盖层存在时外层 BackHandler 直接关最顶层（单条详情 > 会话详情 > WebView 后退），
        // 不依赖各覆盖层 handler 的注册时序——单条详情 DB 加载空窗（viewingFullLog==null）也要可靠返回。
        val httpIdIdx = source.indexOf("viewingHttpId != null -> viewingHttpId = null")
        val sessionIdx = source.indexOf("showSessionDetail -> showSessionDetail = false")
        val webViewIdx = source.indexOf("wv.canGoBack()")
        assertTrue("返回键须先关单条详情", httpIdIdx in 1 until sessionIdx)
        assertTrue("返回键须先关会话详情再退 WebView", sessionIdx in 1 until webViewIdx)
        // 事务面板须用轻量投影 Flow（SQL LIMIT 300、不含正文），不得再整行回读含 request/responseBody 的实体
        assertTrue("事务流须走轻量 DESC 投影", source.contains("observeHttpLogSummariesByContextIdDesc"))
        assertFalse("不得再全量回读实体流", source.contains("observeHttpLogsByContextId"))
    }

    /** DAO 防回归锁：普通 HTTP 通道全部有 cap: 排除版（NULL 保留），且保留无过滤版供 includeCapture=true */
    @Test
    fun studioDaoHasNoCaptureVariants() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/data/db/StudioDao.kt"),
            File("app/src/main/java/com/mina/legadostudio/data/db/StudioDao.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        // 过滤谓词必须 IS NULL OR NOT LIKE——纯 NOT LIKE 会把 NULL contextId 一起滤掉
        assertTrue(source.contains("(contextId IS NULL OR contextId NOT LIKE 'cap:%')"))
        assertTrue(source.contains("observeHttpLogSummariesNoCapture"))
        assertTrue(source.contains("httpLogsByDayPageNoCapture"))
        assertTrue(source.contains("httpLogSummariesByDayPageNoCapture"))
        assertTrue(source.contains("httpLogSummariesPageNoCapture"))
        assertTrue(source.contains("countHttpLogsByDayNoCapture"))
        // 按天滚动窗口三件套：keyset 页（id<beforeId）、全库日期 DISTINCT、MAX(id) 增量通道
        assertTrue(source.contains("fun httpLogSummariesByDayBeforeIdNoCapture("))
        assertTrue(source.contains("id < :beforeId"))
        assertTrue(source.contains("fun observeHttpLogDaysNoCapture()"))
        assertTrue(source.contains("DISTINCT date(createdAt/1000,'unixepoch','localtime')"))
        assertTrue(source.contains("fun httpLogSummariesAfterIdNoCapture("))
        assertTrue(source.contains("fun observeLatestHttpLogIdNoCapture()"))
        // 无过滤版保留（includeCapture=true 与抓包页专用通道仍用它们）
        assertTrue(source.contains("fun observeHttpLogSummaries(limit: Int)"))
        assertTrue(source.contains("fun httpLogSummariesByDayPage("))
        assertTrue(source.contains("fun httpLogSummariesPage("))
        // 导出「全部日期」的全库投影 keyset 页（id<beforeId，无 OFFSET 漂移）
        assertTrue(source.contains("fun httpLogSummariesAllBeforeIdNoCapture("))
        // 浏览器抓包事务面板的轻量投影流：SQL 层 LIMIT + 投影列（不含正文），不得回退到全实体流
        assertTrue(source.contains("fun observeHttpLogSummariesByContextIdDesc("))
        assertTrue(source.contains("ORDER BY id DESC LIMIT :limit"))
    }

    /** MCP get_http_logs 防回归锁：includeCapture 默认 false，默认走 NoCapture 扫描，true 走无过滤版 */
    @Test
    fun mcpGetHttpLogsDefaultsToNoCapture() {
        val source = mcpServerSources()
        assertTrue(source.contains("\"includeCapture\""))
        assertTrue(source.contains("req.arguments.bool(\"includeCapture\") == true"))
        assertTrue(source.contains("httpLogSummariesByDayPageNoCapture"))
        assertTrue(source.contains("httpLogSummariesPageNoCapture"))
        // contextId 精确捷径（cap:xxx 点名抓包会话）不受 includeCapture 影响
        assertTrue(source.contains("httpLogSummariesByContextIdPage(contextIdParam"))
    }

    /** 抓包会话 UI 防回归锁：历史入口 + 分页 DAO + 逐跳详情 + 旧版兼容提示 + 脱敏截断提醒。
     *  重构后符号分布在三个文件：LogsScreen 保入口/导出/覆盖层顺序，CaptureHistoryScreen 保分页+ID 查找，
     *  CaptureDetails 保会话详情逐跳标注与脱敏提醒。 */
    @Test
    fun logsScreenHasCaptureSessionHistory() {
        val logs = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
        ).first { it.isFile }.readText()
        assertTrue(logs.contains("抓包会话"))
        assertTrue(logs.contains("onOpenGuide"))
        assertTrue(logs.contains("功能介绍"))
        // 会话导出留在 LogsScreen（两个抓包二级页跨文件复用，同包 internal 可见）
        assertTrue(logs.contains("exportCaptureSession"))
        assertTrue(logs.contains("countHttpLogsByContextId"))
        // 回归锁：覆盖层顺序（会话层先画、单条详情后画）仍保留在 LogsScreen
        assertTrue(logs.contains("CaptureSessionDetail"))

        // C2 MCP 闭环锁：DAO 摘要改 lastStatus/latestLogId/来源字段（不再用 MAX(statusCode) 冒称最终状态），
        // MCP 新增 poll_capture/get_capture_resource，描述须如实区分三类会话。
        val daoSource = listOf(
            File("src/main/java/com/mina/legadostudio/data/db/StudioDao.kt"),
            File("app/src/main/java/com/mina/legadostudio/data/db/StudioDao.kt"),
        ).first { it.isFile }.readText()
        assertTrue("DAO 须提供 latestLogId", daoSource.contains("latestLogId"))
        assertTrue("DAO 须提供 lastStatus（末行状态码，非 MAX）", daoSource.contains("lastStatus"))
        assertTrue("DAO 须提供增量游标页", daoSource.contains("httpLogSummariesByContextIdAfterId"))
        assertTrue("DAO 须提供会话结束证据聚合（sessionState 判定）", daoSource.contains("captureSessionEndState"))
        assertFalse("旧 MAX(statusCode) 聚合不得保留", daoSource.contains("MAX(CASE WHEN originKind!='capture_hop' THEN statusCode END)"))
        val entities = listOf(
            File("src/main/java/com/mina/legadostudio/data/db/Entities.kt"),
            File("app/src/main/java/com/mina/legadostudio/data/db/Entities.kt"),
        ).first { it.isFile }.readText()
        assertTrue("摘要须含 latestLogId", entities.contains("val latestLogId: Long"))
        assertTrue("摘要须含 lastStatus", entities.contains("val lastStatus: Int?"))
        assertTrue("摘要须含来源证据字段", entities.contains("val hasOnce: Int") && entities.contains("val webviewKinds") && entities.contains("val summaryBody"))
        assertFalse("finalStatus 字段不得保留", entities.contains("val finalStatus"))
        val mcp = mcpServerSources()
        assertTrue("须注册 poll_capture", mcp.contains("\"poll_capture\""))
        assertTrue("须注册 get_capture_resource", mcp.contains("\"get_capture_resource\""))
        assertTrue("list_captures 须输出 latestLogId", mcp.contains("\"latestLogId\""))
        assertTrue("list_captures 须输出 kind", mcp.contains("\"kind\""))
        assertTrue("list_captures 须输出 lastStatus", mcp.contains("\"lastStatus\""))
        assertTrue("poll_capture 须带 afterLogId", mcp.contains("\"afterLogId\""))
        assertTrue("poll_capture 须带 sessionState", mcp.contains("\"sessionState\""))
        assertTrue("get_capture_resource 须按 logId", mcp.contains("\"logId\""))
        assertTrue("资源读取须校验会话目录", mcp.contains("captureResourcePath"))
        assertFalse("list_captures 不得再用旧 finalStatus 文案冒充", mcp.contains("每条是一次 capture_once"))
        // P0 锁：wrapper 不得对 cap: 抓包会话 id 走 TaskContextStore.describe（否则
        // get_capture/poll_capture/get_http_logs(cap:) 全被 CONTEXT_EXPIRED_OR_UNKNOWN 拦死）。
        assertTrue("wrapper 须用 McpToolContextPolicy 分流 cap: 抓包 id", mcp.contains("McpToolContextPolicy.shouldValidateTaskContext"))
        // 行级锚点权威锁：泛用工具不得把 contextId 旧锚点回写进行级 sourceAnchor
        // （裸 fetch/eval 混查其它站会被钉死在先调的书源上，且写入后归属不可纠正）
        assertTrue("inheritedAnchor 不得回填 registry 锚点", mcp.contains("?: return null"))
        val sessionIdx = logs.indexOf("viewingCaptureContextId?.let")
        val detailIdx = logs.indexOf("if (viewingHttpId != null) {")
        assertTrue("会话覆盖层必须先于单条详情绘制", sessionIdx in 1 until detailIdx)
        // 返回键空窗锁：详情 DB 加载期间（viewingFullLog==null、内部 handler 未注册）由页面级
        // BackHandler 关掉最顶层覆盖层，返回不得穿透到 Activity 默认行为
        assertTrue("覆盖层加载空窗须有 BackHandler 兜底", logs.contains("BackHandler(enabled = viewingHttpId != null || viewingCaptureContextId != null)"))

        val history = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/CaptureHistoryScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/CaptureHistoryScreen.kt"),
        ).first { it.isFile }.readText()
        assertTrue(history.contains("captureSessionSummaries"))
        assertTrue(history.contains("countCaptureSessions"))
        assertTrue(history.contains("httpLogSummariesByContextIdPage"))
        // ID 全库精确查找：不依赖已加载页，count>0 直接开会话详情覆盖层
        assertTrue(history.contains("countHttpLogsByContextId(ctx)"))
        assertTrue(history.contains("CaptureSessionDetail"))
        val histSessionIdx = history.indexOf("viewingCaptureContextId?.let")
        val histDetailIdx = history.indexOf("if (viewingHttpId != null) {")
        assertTrue("历史页会话覆盖层必须先于单条详情绘制", histSessionIdx in 1 until histDetailIdx)

        val details = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/CaptureDetails.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/CaptureDetails.kt"),
        ).first { it.isFile }.readText()
        assertTrue(details.contains("capture_hop"))
        assertTrue(details.contains("redirectChain"))
        assertTrue(details.contains("HttpLogCaps.TRUNCATED_MARK"))
        // 来源标签不伪造：逐次 vs 浏览器按真实 originKind 区分，无法识别标「抓包」
        assertTrue(details.contains("webview_capture"))
        assertTrue(details.contains("captureSessionKindLabel"))
        assertTrue(details.contains("captureEntryRole"))
    }

    /** 抓包会话历史删除防回归锁：
     *  - DAO：删除谓词必须「精确等值 + cap:% 前缀校验」双条件，复用按天删/按 id 删都会
     *    破坏抓包隔离或误伤其它会话；
     *  - UI：行内删除按钮 + 二次确认（快照 ctx/条数），活跃会话（浏览器抓包未结算）拒绝删除，
     *    删除后窗口 epoch 换身份防飞行翻页回填幽灵行、total 回读 DB 校正、详情层联动关闭；
     *  - 页签：logs 被二级路由压栈时 composable 离组，tab 必须 rememberSaveable 才能回到「抓包」。 */
    @Test
    fun captureHistoryDeleteContract() {
        val dao = listOf(
            File("src/main/java/com/mina/legadostudio/data/db/StudioDao.kt"),
            File("app/src/main/java/com/mina/legadostudio/data/db/StudioDao.kt"),
        ).first { it.isFile }.readText()
        assertTrue("须有按 contextId 删会话入口", dao.contains("fun deleteCaptureSessionByContextId("))
        assertTrue(
            "删除 SQL 须精确等值 + cap: 前缀双谓词",
            dao.contains("DELETE FROM http_logs WHERE contextId = :contextId AND contextId LIKE 'cap:%'"),
        )
        // keyset 会话分页：游标 (lastAt, latestLogId) 续页，删除后不漏行不重行（OFFSET 会缺页）
        assertTrue("须有 keyset 会话分页 DAO", dao.contains("fun captureSessionSummariesBefore("))
        assertTrue("keyset 须按聚合键游标", dao.contains("MAX(createdAt) < :beforeLastAt") && dao.contains("MAX(id) < :beforeLatestId"))

        val history = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/CaptureHistoryScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/CaptureHistoryScreen.kt"),
        ).first { it.isFile }.readText()
        assertTrue("历史页须调会话删除 DAO", history.contains("deleteCaptureSessionByContextId"))
        assertTrue("删除须二次确认", history.contains("pendingSessionDelete"))
        assertTrue("须有逐行删除按钮", history.contains("Icons.Outlined.Delete"))
        assertTrue("活跃会话须拒绝删除", history.contains("isCaptureSessionActive"))
        assertTrue("删除须作废飞行翻页（epoch）", history.contains("epoch"))
        assertTrue("删除后须收敛窗口", history.contains("removeSession"))
        assertTrue("删除后须回读会话总数", history.contains("countCaptureSessions"))
        assertTrue("已打开详情须联动关闭", history.contains("viewingCaptureContextId = null"))
        assertTrue("详情页须透传删除入口", history.contains("onDeleteSession"))
        assertTrue("落盘资源目录须随会话清理", history.contains("captureDirName"))
        // keyset 翻页锁：历史页走游标 DAO、游标字段入 state、单条详情归属登记（含加载空窗）
        assertTrue("翻页须走 keyset DAO", history.contains("captureSessionSummariesBefore"))
        assertTrue("游标须入窗口状态", history.contains("cursorLastAt"))
        assertTrue("单条详情归属须登记", history.contains("openLogContextIds") && history.contains("withOpenLogContext"))
        assertFalse("不得再回退 OFFSET 会话分页", history.contains("captureSessionSummaries("))

        val details = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/CaptureDetails.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/CaptureDetails.kt"),
        ).first { it.isFile }.readText()
        assertTrue("会话详情须可选删除入口（默认 null）", details.contains("onDeleteSession"))

        val engine = listOf(
            File("src/main/java/com/mina/legadostudio/verification/WebViewCaptureEngine.kt"),
            File("app/src/main/java/com/mina/legadostudio/verification/WebViewCaptureEngine.kt"),
        ).first { it.isFile }.readText()
        assertTrue("引擎须登记活跃 cap: 会话", engine.contains("activeCaptureContextIds"))
        // 收尾竞态双闸：closeWrites 先关写入闸（在途块写完后入队结算行，闸外新写被拒），
        // 注销挂在结算行落库之后（onSettled 回调）——缺任一都会留「删除后写回孤儿会话」窗口
        assertTrue("结算须先关写入闸", engine.contains("session.closeWrites"))
        assertTrue("回调写点须经写入闸", engine.contains("withWriteGate") && engine.contains("beginWrite"))
        assertTrue("结算须注销活跃会话", engine.contains("activeCaptureContextIds.remove"))
        assertTrue("注销须在结算行写库后", engine.contains("onSettled = {"))

        val capture = listOf(
            File("src/main/java/com/mina/legadostudio/verification/WebViewCapture.kt"),
            File("app/src/main/java/com/mina/legadostudio/verification/WebViewCapture.kt"),
        ).first { it.isFile }.readText()
        // Session 写入闸：关闸前已开始的写块排空后才入队结算行；关闸后新写一律拒绝
        assertTrue("Session 须有写入闸", capture.contains("fun beginWrite("))
        assertTrue("Session 须支持关闸结算", capture.contains("fun closeWrites("))
        assertTrue("Session 须支持写块结束", capture.contains("fun endWrite("))
        assertTrue("Session 须有闸内执行包装", capture.contains("fun <T> withWriteGate("))

        val recorder = listOf(
            File("src/main/java/com/mina/legadostudio/network/HttpLogRecorder.kt"),
            File("app/src/main/java/com/mina/legadostudio/network/HttpLogRecorder.kt"),
        ).first { it.isFile }.readText()
        assertTrue("recordAsync 须支持写库结算回调", recorder.contains("onSettled"))

        val logs = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/LogsScreen.kt"),
        ).first { it.isFile }.readText()
        assertTrue(
            "抓包页签须 rememberSaveable（二级页返回保留 CAPTURE 而非回落 OPERATION）",
            logs.contains("var tab by rememberSaveable { mutableStateOf(LogsTab.OPERATION) }"),
        )
        assertFalse("页签不得回退普通 remember", logs.contains("var tab by remember {"))
    }

    /** 连接模式契约锁：所有给客户端看的 URL 出口（本机/局域网展示、复制服务器链接、
     *  JSON 配置、仅复制 URL）必须统一走 McpAccess.connectionUrl，不得让某个出口绕过
     *  当前所选的标准/精简模式。 */
    @Test
    fun mcpScreenAllClientUrlsGoThroughConnectionUrl() {
        val source = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/McpStatusScreen.kt"),
        ).first { it.isFile }.readText()
        assertTrue(source.contains("McpAccess.connectionUrl(endpoint, compactMode)"))
        assertTrue(source.contains("McpAccess.connectionUrl(it, compactMode)"))
        assertTrue("JSON 配置须用转换后的地址", source.contains("clientConfigJson(localClientUrl"))
        assertTrue("仅复制 URL 须用转换后的地址", source.contains("copy(\"MCP URL\", shownUrl)"))
        assertFalse("不得再直接把原始 endpoint 交给客户端 JSON", source.contains("clientConfigJson(endpoint"))
    }

    @Test
    fun mcpServerExposesStructuredLogTools() {
        val source = mcpServerSources()
        assertTrue(source.contains("\"get_logs\""))
        assertTrue(source.contains("\"get_log\""))
        assertTrue(source.contains("\"get_crash_logs\""))
        assertTrue(source.contains("\"get_crash_log\""))
        assertTrue(source.contains("\"get_diagnostic_snapshots\""))
        assertTrue(source.contains("\"get_diagnostic_snapshot\""))
        assertTrue(source.contains("listOf(\"mcp\", \"sources\", \"skills\", \"verification\", \"logs\")"))
    }

    @Test
    fun skillsScreenManagesCustomSkills() {
        val candidates = listOf(
            File("src/main/java/com/mina/legadostudio/ui/screens/SkillsScreen.kt"),
            File("app/src/main/java/com/mina/legadostudio/ui/screens/SkillsScreen.kt"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("新增"))
        assertTrue(source.contains("导入"))
        assertTrue(source.contains("导出"))
        assertTrue(source.contains("删除"))
        assertTrue(source.contains("内置"))
        assertTrue(source.contains("自定义"))
        assertTrue(source.contains("onDelete = null"))
        assertTrue(source.contains("importPackage"))
        assertTrue(source.contains("exportPackage"))
        assertFalse(source.contains("webWorkbench"))
    }

    @Test
    fun manifestRegistersReaderImport() {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(source.contains("android:scheme=\"legado\""))
        assertTrue(source.contains(".export.ReaderImportService"))
        assertTrue(source.contains("android.intent.action.VIEW"))
    }

    @Test
    fun skillAssetsDoNotUseYueDuNames() {
        val roots = listOf(File("src/main/assets/skills"), File("app/src/assets/skills")).filter { it.isDirectory }
        val hits = roots.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "md" }
            .filter { Regex("YueDU|mcp__YueDU").containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()
        assertTrue("YueDU leftovers: $hits", hits.isEmpty())
    }

    /** 书源踩坑防回归锁：官方 API 补齐、分字段保存、signJs 禁缓存、searchUrl 返回契约提示，四道护栏必须都在 */
    @Test
    fun pitfallGuardsStayInPlace() {
        val mcp = mcpServerSources()
        assertTrue(mcp.contains("\"fields\""))
        assertTrue(mcp.contains("action=signJs"))
        assertTrue(mcp.contains("返回的值无效"))

        val rhino = listOf(
            File("src/main/java/com/mina/legadostudio/runtime/RhinoEvaluator.kt"),
            File("app/src/main/java/com/mina/legadostudio/runtime/RhinoEvaluator.kt"),
        ).first { it.isFile }.readText()
        assertTrue(rhino.contains("base64DecodeToByteArray"))
        assertTrue(rhino.contains("fun statusCode()"))
        assertTrue(rhino.contains("java.lang.Thread.sleep"))
    }
}
