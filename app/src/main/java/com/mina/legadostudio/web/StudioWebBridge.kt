package com.mina.legadostudio.web

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mina.legadostudio.BuildConfig
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.data.db.VerificationSessionEntity
import com.mina.legadostudio.device.DeviceReadiness
import com.mina.legadostudio.mcp.McpAccess
import com.mina.legadostudio.mcp.McpConfigStore
import com.mina.legadostudio.mcp.StudioLog
import com.mina.legadostudio.mcp.StudioMcpServer
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.RuntimeConfigStore
import com.mina.legadostudio.reader.ReaderWebConfigStore
import com.mina.legadostudio.reader.ReaderWebNetworkState
import com.mina.legadostudio.service.McpService
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject as KJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * App 内 Web UI 的宿主桥。
 *
 * 边界（`docs/WEB_UI_CONTRACT.md` + `.tasks/host-review.md`）：
 * - 只做数据与系统能力，不弹任何原生业务 UI；
 * - WebMessageListener 只在 [WebBridgeProtocol.TRUSTED_ORIGIN] + 主框架工作，本类再校验一次；
 * - 异步 id 回包：每个请求登记进 [WebBridgePending]，完成/失败/销毁只结算一次；
 * - 秘密（MCP 令牌、阅读令牌）只写本机剪贴板或安全存储，绝不回传 JS；
 * - 请求大小按 UTF-8 字节判定（[WebBridgeProtocol.MAX_REQUEST_BYTES]）。
 *
 * 线程：方法体跑在 [scope]（IO）；回包与事件切主线程再 `postMessage`。
 */
internal class StudioWebBridge(
    private val activity: Activity,
    private val onExit: () -> Unit,
    private val onRequestCloseVerificationBrowser: () -> Unit,
    private val onThemeApplied: (dark: Boolean, palette: String) -> Unit,
) {
    private val app: StudioApplication = activity.applicationContext as StudioApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val pending = WebBridgePending()

    /** settings.save 串行化：两个并发保存不允许交错写各自的字段。 */
    private val settingsLock = Mutex()

    /** 本地工具实例：每 Activity 一个，销毁释放；不是网络客户端（registerSession=false）。 */
    private val localServer: StudioMcpServer by lazy {
        StudioMcpServer(activity.applicationContext, compact = false).also { it.create(registerSession = false) }
    }

    /** 本地工具目录快照：只在首次取用时建一次，保证顺序与注册顺序一致。 */
    @Volatile private var localToolsSnapshot: List<Tool>? = null

    /** SDK 自身序列化器：Tool / CallToolResult 原形（inputSchema、structuredContent 都保留）。 */
    private val sdkJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Volatile private var released = false

    /**
     * 事件用的回包代理。**只在 origin + main frame 校验通过后**写入：
     * 被拒的子 frame 拿到的 proxy 不能污染之后所有宿主事件。
     */
    @Volatile private var eventProxy: JavaScriptReplyProxy? = null

    // --- 安装 / 释放 ------------------------------------------------------

    /** 安装 WebMessageListener。返回 false 表示当前 WebView 不支持（契约要求明确提示升级）。 */
    fun install(webView: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "WEB_MESSAGE_LISTENER unsupported")
            return false
        }
        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_OBJECT,
            setOf(WebBridgeProtocol.TRUSTED_ORIGIN),
        ) { _, message, sourceOrigin, isMainFrame, proxy ->
            handle(message.data, sourceOrigin.toString(), isMainFrame, proxy)
        }
        return true
    }

    /**
     * 释放：先停止入站（[released]），再取消在途请求并回 host_gone，最后取消作用域。
     * 幂等——重复调用不会二次回包，也不会去触碰未初始化过的 [localServer]。
     */
    fun release() {
        if (released) return
        released = true
        val outstanding = pending.drain()
        val proxy = eventProxy
        eventProxy = null
        if (outstanding.isNotEmpty() && proxy != null) {
            main.post {
                outstanding.forEach { id ->
                    runCatching {
                        proxy.postMessage(
                            WebBridgeProtocol.failure(id, WebBridgeProtocol.ERR_HOST_GONE, "宿主已关闭，请求已取消")
                        )
                    }
                }
            }
        }
        scope.cancel()
        // 只有真正创建过才释放：避免触发未初始化的 lazy 实例（release 时再建一个 Server 是纯浪费）。
        localToolsSnapshot?.let { runCatching { localServer.releaseLocal() } }
        localToolsSnapshot = null
    }

    // --- 入站 -------------------------------------------------------------

    private fun handle(raw: String?, origin: String, isMainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        if (released) return
        when (val verdict = WebBridgeProtocol.authorizeOrigin(origin, isMainFrame)) {
            is WebBridgeProtocol.OriginVerdict.Reject -> {
                // 来源不符：不解析、不回包、不写事件代理，只记一条脱敏日志。
                Log.w(TAG, "rejected bridge message: ${verdict.code}")
                return
            }
            WebBridgeProtocol.OriginVerdict.Accept -> Unit
        }
        // 校验通过后才认这个代理作为事件出口。
        eventProxy = proxy
        when (val parsed = WebBridgeProtocol.parseRequest(raw)) {
            is WebBridgeProtocol.Parsed.Bad -> {
                parsed.id?.let { id ->
                    post(proxy, WebBridgeProtocol.failure(id, parsed.error.code, parsed.error.message))
                } ?: Log.w(TAG, "dropped malformed message: ${parsed.error.code}")
            }
            is WebBridgeProtocol.Parsed.Ok -> {
                if (pending.add(parsed.id) == null) {
                    post(proxy, WebBridgeProtocol.failure(parsed.id, WebBridgeProtocol.ERR_BAD_REQUEST, "重复或超限的请求 id"))
                    return
                }
                scope.launch {
                    val envelope = try {
                        val result = dispatch(parsed.method, parsed.params)
                        WebBridgeProtocol.success(parsed.id, result)
                    } catch (cancelled: CancellationException) {
                        WebBridgeProtocol.failure(parsed.id, WebBridgeProtocol.ERR_CANCELLED, "请求已取消")
                    } catch (failure: BridgeFailure) {
                        WebBridgeProtocol.failure(parsed.id, failure.code, failure.message.orEmpty())
                    } catch (error: Throwable) {
                        Log.w(TAG, "bridge dispatch failed: ${error.javaClass.simpleName}")
                        WebBridgeProtocol.failure(
                            parsed.id,
                            WebBridgeProtocol.ERR_INTERNAL,
                            "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                        )
                    }
                    // 只结算一次：Activity 销毁已 drain 的 id 不再重复回包。
                    if (pending.remove(parsed.id)) post(proxy, envelope)
                }
            }
        }
    }

    private suspend fun dispatch(method: String, params: JsonObject): JsonElement = when (method) {
        WebBridgeProtocol.M_BOOTSTRAP -> bootstrap()
        WebBridgeProtocol.M_SERVICE_SET -> serviceSet(params)
        WebBridgeProtocol.M_SETTINGS_SAVE -> settingsSave(params)
        WebBridgeProtocol.M_CONNECTION_COPY -> connectionCopy(params)
        WebBridgeProtocol.M_TOOLS_LIST -> toolsList()
        WebBridgeProtocol.M_TOOLS_CALL -> toolsCall(params)
        WebBridgeProtocol.M_LOGS_DELETE -> logsDelete(params)
        WebBridgeProtocol.M_VERIFICATION_LIST -> verificationList()
        WebBridgeProtocol.M_VERIFICATION_OPEN -> verificationOpen(params)
        WebBridgeProtocol.M_VERIFICATION_COMPLETE -> verificationComplete(params)
        WebBridgeProtocol.M_VERIFICATION_CLEAR -> verificationClear(params)
        WebBridgeProtocol.M_VERIFICATION_CLOSE -> verificationClose(params)
        WebBridgeProtocol.M_VERIFICATION_BROWSER_CLOSE -> {
            main.post { onRequestCloseVerificationBrowser() }
            JsonObject().apply { addProperty("closed", true) }
        }
        WebBridgeProtocol.M_FILE_EXPORT -> fileExport(params)
        WebBridgeProtocol.M_FILE_IMPORT -> fileImport()
        WebBridgeProtocol.M_THEME -> theme(params)
        WebBridgeProtocol.M_EXIT -> {
            main.post { onExit() }
            JsonObject().apply { addProperty("closing", true) }
        }
        else -> throw BridgeFailure(WebBridgeProtocol.ERR_UNKNOWN_METHOD, "未登记的方法")
    }

    // --- app.bootstrap ----------------------------------------------------

    private suspend fun bootstrap(): JsonObject {
        val root = JsonObject()
        root.addProperty("versionName", BuildConfig.VERSION_NAME)
        root.addProperty("versionCode", BuildConfig.VERSION_CODE)
        root.addProperty("buildRevision", BuildConfig.BUILD_REVISION)
        root.addProperty("ui", "rust-vue")
        root.add("service", serviceState())
        root.add("runtime", runtimeState())
        root.add("reader", readerState())
        root.add("appearance", appearanceState())
        root.add("capabilities", capabilities())
        return root
    }

    private suspend fun serviceState(): JsonObject {
        val context = activity.applicationContext
        val store = McpConfigStore(context)
        val config = withContext(Dispatchers.IO) { store.load() }
        val compact = withContext(Dispatchers.IO) { store.loadCompactMode() }
        val running = McpService.running
        val starting = McpService.starting
        val endpoints = McpService.endpoints
        val stats = withContext(Dispatchers.IO) { McpService.status(context, includeToken = false) }
        val obj = JsonObject()
        obj.addProperty("running", running)
        obj.addProperty("starting", starting)
        obj.addProperty("port", config.port)
        // 回真实值：启动/保存时已强制 tokenRequired=true，这里不靠硬编码掩盖旧设置。
        obj.addProperty("tokenRequired", config.tokenRequired)
        val endpointArray = JsonArray()
        endpoints.forEach { endpointArray.add(McpAccess.connectionUrl(it, compact)) }
        obj.add("endpoints", endpointArray)
        obj.addProperty("compact", compact)
        obj.addProperty("clientCount", (stats["clientCount"] as? Number)?.toLong() ?: 0L)
        obj.addProperty("lastAccessAt", (stats["lastAccessAt"] as? Number)?.toLong() ?: 0L)
        return obj
    }

    private suspend fun runtimeState(): JsonObject {
        val runtimeConfig = RuntimeConfigStore(activity.applicationContext)
        val obj = JsonObject()
        obj.addProperty("userAgent", runtimeConfig.userAgent)
        obj.addProperty("bookSourceType", runtimeConfig.bookSourceType)
        obj.addProperty("bookSourceTypeName", RuntimeConfigStore.typeName(runtimeConfig.bookSourceType))
        obj.addProperty("defaultUserAgent", HttpFetcher.DEFAULT_UA)
        return obj
    }

    private suspend fun readerState(): JsonObject {
        val store = ReaderWebConfigStore(activity.applicationContext)
        val config = withContext(Dispatchers.IO) { store.load() }
        val hasToken = withContext(Dispatchers.IO) { store.hasToken() }
        val obj = JsonObject()
        obj.addProperty("baseUrl", config?.baseUrl.orEmpty())
        obj.addProperty("tokenSaved", hasToken)
        obj.addProperty("revision", config?.revision ?: 0L)
        obj.addProperty("network", ReaderWebNetworkState.currentKind(activity.applicationContext).name.lowercase())
        return obj
    }

    private suspend fun appearanceState(): JsonObject {
        val state = withContext(Dispatchers.IO) { WebAppearance.load(activity.applicationContext) }
        val systemDark = isSystemDark()
        val obj = JsonObject()
        // 宿主模式（light/system/dark）与 Vue 模式（light/auto/dark）同时给出，前端不必猜映射。
        obj.addProperty("theme", state.mode)
        obj.addProperty("mode", state.vueMode)
        obj.addProperty("dark", WebAppearance.isDark(state.mode, systemDark))
        obj.addProperty("palette", state.palette)
        obj.addProperty("paletteIndex", state.vuePaletteIndex)
        val palettes = JsonArray()
        WebAppearance.PALETTE_KEYS.forEach { palettes.add(it) }
        obj.add("palettes", palettes)
        return obj
    }

    private fun isSystemDark(): Boolean =
        (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private suspend fun capabilities(): JsonObject {
        val readiness = withContext(Dispatchers.IO) {
            runCatching {
                val port = McpConfigStore(activity.applicationContext).load().port
                DeviceReadiness(activity.applicationContext).inspect(port, McpService.running)
            }.getOrNull()
        }
        val obj = JsonObject()
        obj.addProperty("webMessageListener", true)
        obj.addProperty("saf", safHost != null)
        obj.addProperty("verificationBrowser", true)
        obj.addProperty("toolCount", withContext(Dispatchers.IO) { localTools().size })
        obj.addProperty("tokenRequiredLocked", true)
        obj.addProperty("notificationPermission", readiness?.notificationPermission ?: false)
        obj.addProperty("notificationsEnabled", readiness?.notificationsEnabled ?: false)
        obj.addProperty("batteryUnrestricted", readiness?.batteryUnrestricted ?: false)
        obj.addProperty("overlayPermission", readiness?.overlayPermission ?: false)
        obj.addProperty("portAvailable", readiness?.portAvailable ?: false)
        obj.addProperty("manufacturer", readiness?.manufacturer.orEmpty())
        val addresses = JsonArray()
        readiness?.localAddresses.orEmpty().forEach { addresses.add(it) }
        obj.add("localAddresses", addresses)
        obj.addProperty("sdkInt", Build.VERSION.SDK_INT)
        return obj
    }

    // --- service.set / settings.save --------------------------------------

    private suspend fun serviceSet(params: JsonObject): JsonElement {
        val action = WebBridgeProtocol.parseServiceAction(stringParam(params, "action"))
            ?: throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, "action 必须是 start/stop/restart")
        val context = activity.applicationContext
        withContext(Dispatchers.Main) {
            when (action) {
                ServiceAction.START -> McpService.start(context)
                ServiceAction.STOP -> McpService.stop(context)
                ServiceAction.RESTART -> McpService.restart(context)
            }
        }
        // 启动是异步的：如实回 starting=true，不假装已就绪。
        return serviceState()
    }

    /**
     * 设置保存：
     * 1) [WebBridgeProtocol.parseSettings] 已把**全部**字段校验完，任何错误都在写之前返回；
     * 2) 令牌校验（readerToken 单独保存需要已存在 origin）也在写之前做完；
     * 3) 写入在 [settingsLock] 内串行，避免两次保存交错；
     * 4) 回读真实落盘值；IO 失败如实回 `partial` + `failed`，不谎称原子事务。
     */
    private suspend fun settingsSave(params: JsonObject): JsonElement {
        val draft = when (val parsed = WebBridgeProtocol.parseSettings(params)) {
            is WebBridgeProtocol.SettingsParse.Ok -> parsed.settings
            is WebBridgeProtocol.SettingsParse.Bad -> throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, parsed.message)
        }
        val context = activity.applicationContext
        val failed = mutableListOf<String>()
        // 写前校验必须在任何写之前（含端口/UA）：令牌单独保存需要已有 origin。
        if (draft.readerBaseUrl == null && draft.readerToken != null) {
            val existing = withContext(Dispatchers.IO) { ReaderWebConfigStore(context).load() }
            if (existing == null) {
                throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, "尚未配置阅读地址，无法单独保存令牌")
            }
        }
        settingsLock.withLock {
            withContext(Dispatchers.IO) {
                val mcp = McpConfigStore(context)
                val runtime = RuntimeConfigStore(context)
                val reader = ReaderWebConfigStore(context)

                fun step(name: String, block: () -> Unit) {
                    runCatching(block).onFailure {
                        failed += name
                        StudioLog.add("settings.save $name failed", "W", "web", it.javaClass.simpleName)
                    }
                }

                // 端口：tokenRequired 恒 true，绝不写 false。
                draft.port?.let { port ->
                    step("port") {
                        val current = mcp.load()
                        if (port != current.port || !current.tokenRequired) {
                            mcp.save(McpConfigStore.Config(port = port, tokenRequired = true, token = current.token))
                        }
                    }
                }
                draft.compact?.let { compact -> step("compact") { mcp.saveCompactMode(compact) } }
                draft.userAgent?.let { ua -> step("userAgent") { runtime.userAgent = ua } }
                draft.bookSourceType?.let { type -> step("bookSourceType") { runtime.bookSourceType = type } }
                if (draft.readerBaseUrl != null) {
                    // readerToken 缺省（null）= 保留同 origin 令牌；改 origin 由 store 策略清除旧令牌。
                    step("reader") { reader.save(draft.readerBaseUrl, draft.readerToken) }
                } else if (draft.readerToken != null) {
                    val existing = reader.load()
                    if (existing == null) {
                        throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, "尚未配置阅读地址，无法单独保存令牌")
                    }
                    step("reader") { reader.save(existing.baseUrl, draft.readerToken) }
                }
                draft.appearanceTheme?.let { mode ->
                    step("appearanceTheme") {
                        val hostMode = WebAppearance.fromVueMode(mode)
                        WebAppearance.save(context, palette = null, mode = hostMode)
                        WebAppearance.mirrorLegacyMode(context, hostMode)
                    }
                }
                // palette 单独保存：不重置 mode（save 只写传入字段）。
                draft.appearancePalette?.let { palette ->
                    step("appearancePalette") { WebAppearance.save(context, palette = palette, mode = null) }
                }
            }
        }
        // 主题立即生效（系统栏），再回读 bootstrap 让前端确认落盘值。
        withContext(Dispatchers.Main) { applyAppearanceToHost() }
        val result = bootstrap()
        if (failed.isNotEmpty()) {
            result.addProperty("partial", true)
            val array = JsonArray()
            failed.forEach { array.add(it) }
            result.add("failed", array)
        }
        return result
    }

    private fun applyAppearanceToHost() {
        val state = WebAppearance.load(activity.applicationContext)
        onThemeApplied(WebAppearance.isDark(state.mode, isSystemDark()), state.palette)
    }

    // --- connection.copy ---------------------------------------------------

    private suspend fun connectionCopy(params: JsonObject): JsonElement {
        val kind = WebBridgeProtocol.parseConnectionKind(stringParam(params, "kind"))
            ?: throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, "kind 必须是 url/token/json")
        val lan = params.get("lan")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        val context = activity.applicationContext
        val store = McpConfigStore(context)
        val config = withContext(Dispatchers.IO) { store.load() }
        val compact = withContext(Dispatchers.IO) { store.loadCompactMode() }
        val endpoint = if (lan) {
            // 局域网地址取当前真实网卡地址，不用缓存的旧 IP。
            McpAccess.lanEndpoints(config.port).firstOrNull()
                ?: throw BridgeFailure(WebBridgeProtocol.ERR_NOT_FOUND, "当前没有可用的局域网地址")
        } else {
            McpService.endpoints.firstOrNull() ?: McpAccess.endpoints(config.port).first()
        }
        val url = McpAccess.connectionUrl(endpoint, compact)
        val text = when (kind) {
            ConnectionKind.URL -> url
            // 「复制令牌」= 纯 token（Vue 文案如此），不是 Authorization 头。
            ConnectionKind.TOKEN -> config.token
            ConnectionKind.JSON -> McpAccess.clientConfigJson(url, config.token, tokenRequired = true)
        }
        val label = when (kind) {
            ConnectionKind.URL -> "MCP Endpoint"
            ConnectionKind.TOKEN -> "MCP 令牌"
            ConnectionKind.JSON -> "MCP 客户端配置"
        }
        withContext(Dispatchers.Main) {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            // API 33+：标记为敏感内容，系统剪贴板预览不显示令牌；不回传 JS。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(clip)
        }
        // 只回 {copied:true}：被复制的令牌绝不回传前端。
        return WebBridgePolicy.copiedAck()
    }

    // --- tools.list / tools.call -------------------------------------------

    /**
     * 本地工具目录（注册顺序）。
     *
     * 不能直接遍历 `ConcurrentHashMap.values`：哈希顺序与注册顺序无关，前端按顺序渲染会乱。
     * [StudioMcpServer.localTools] 已按注册顺序返回快照，这里再缓存一份，保证同一 Activity 内顺序稳定。
     */
    private fun localTools(): List<Tool> {
        localToolsSnapshot?.let { return it }
        val snapshot = localServer.localTools()
        localToolsSnapshot = snapshot
        return snapshot
    }

    private suspend fun toolsList(): JsonElement {
        val tools = withContext(Dispatchers.IO) { localTools() }
        val array = JsonArray()
        tools.forEach { tool ->
            // 用 SDK 自身序列化整个 Tool：inputSchema 是 ToolSchema（JSON Schema 对象），
            // 不是 KJsonObject，手拼会丢字段或类型不符。
            val encoded = sdkJson.encodeToString(Tool.serializer(), tool)
            array.add(JsonParser.parseString(encoded))
        }
        return JsonObject().apply { add("tools", array) }
    }

    private suspend fun toolsCall(params: JsonObject): JsonElement {
        val call = when (val parsed = WebBridgeProtocol.parseToolCall(params)) {
            is WebBridgeProtocol.ToolCallParse.Ok -> parsed
            is WebBridgeProtocol.ToolCallParse.Bad -> throw BridgeFailure(parsed.code, parsed.message)
        }
        if (!localServer.hasLocalTool(call.name)) {
            throw BridgeFailure(WebBridgeProtocol.ERR_NOT_FOUND, "未知工具：${call.name}")
        }
        val arguments: KJsonObject = Json.parseToJsonElement(call.arguments.toString()).jsonObject
        val cookiesBefore = app.cookieStore.sensitiveValues()
        val result: CallToolResult = withContext(Dispatchers.IO) { localServer.callLocalTool(call.name, arguments) }
        // MCP CallToolResult 原形：整个对象用 SDK serializer 序列化，structuredContent 一并保留。
        val encoded = sdkJson.encodeToString(CallToolResult.serializer(), result)
        val context = activity.applicationContext
        val knownSecrets = cookiesBefore + app.cookieStore.sensitiveValues() + listOfNotNull(
            McpConfigStore(context).load().token,
            ReaderWebConfigStore(context).load()?.token,
        )
        return WebToolResultPolicy.forWeb(call.name, JsonParser.parseString(encoded), knownSecrets)
    }

    // --- logs.delete (trusted local bridge only; never an MCP tool) --------

    private val logDeleteLock = Mutex()

    private suspend fun logsDelete(params: JsonObject): JsonElement {
        val request = WebBridgeProtocol.parseLogDelete(params)
            ?: throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, "需要 kind 和 1..100 个不重复的精确字符串 ids")
        return logDeleteLock.withLock {
            val dao = app.database.dao()
            val store = object : WebBridgeProtocol.LogDeleteStore {
                fun file(kind: String, id: String): java.io.File = when (kind) {
                    "crash" -> java.io.File(app.filesDir, "crash-logs/$id")
                    "snapshot" -> java.io.File(app.filesDir, "diagnostic-snapshots/$id.json")
                    "capture" -> java.io.File(app.cacheDir, "captures/${id.removePrefix("cap:")}")
                    else -> error("Not a file log")
                }

                // No links, alternate roots, or arbitrary persisted paths, even in a corrupted row.
                fun safeFile(kind: String, id: String): Boolean {
                    val target = file(kind, id)
                    val root = if (kind == "capture") app.cacheDir else app.filesDir
                    val expectedParent = java.io.File(root.canonicalFile, target.parentFile!!.name)
                    val expectedTarget = java.io.File(expectedParent, target.name)
                    if (target.parentFile!!.canonicalFile != expectedParent) return false
                    if (target.canonicalFile != expectedTarget) return false
                    return !target.exists() || expectedTarget.walkTopDown().all { it.canonicalFile == it.absoluteFile }
                }

                override suspend fun exists(kind: String, id: String): Boolean = when (kind) {
                    "operation" -> dao.operationLog(id.toLong()) != null
                    "http" -> dao.httpLog(id.toLong()) != null
                    "capture" -> dao.countHttpLogsByContextId(id) > 0 || file(kind, id).exists()
                    "crash" -> app.crashLogs.list().any { it.name == id } || file(kind, id).exists()
                    "snapshot" -> dao.diagnosticSnapshot(id) != null || file(kind, id).exists()
                    else -> error("Unknown log kind")
                }

                override suspend fun blockedReason(kind: String, id: String): String? {
                    if (kind == "http" && dao.httpLog(id.toLong())?.contextId?.startsWith("cap:", ignoreCase = true) == true) {
                        return "wrong_log_kind"
                    }
                    if (kind == "capture" && app.webViewCapture.isCaptureSessionActive(id)) return "active_capture"
                    if (kind in setOf("capture", "crash", "snapshot") && !safeFile(kind, id)) return "unsafe_file"
                    if (kind == "snapshot") {
                        val row = dao.diagnosticSnapshot(id)
                        if (row != null && java.io.File(row.path).absoluteFile != file(kind, id).absoluteFile) return "unsafe_file"
                    }
                    return null
                }

                override suspend fun delete(kind: String, id: String) {
                    // Recheck immediately before touching data, not just at batch start.
                    check(blockedReason(kind, id) == null)
                    when (kind) {
                        "operation" -> dao.deleteOperationLogs(listOf(id.toLong()))
                        "http" -> dao.deleteHttpLogs(listOf(id.toLong()))
                        "capture" -> {
                            check(com.mina.legadostudio.network.CaptureActivity.deleteIfInactive(id) {
                                dao.deleteCaptureSessionByContextId(id)
                                val target = file(kind, id)
                                if (target.exists()) check(target.deleteRecursively())
                            }) { "active_capture" }
                        }
                        "crash" -> app.crashLogs.delete(listOf(id))
                        "snapshot" -> {
                            // Store.delete trusts row.path and ignores failed file deletion. Use the
                            // fixed whitelist path and retain the DB row on file failure instead.
                            val target = file(kind, id)
                            if (target.exists()) check(target.isFile && target.delete())
                            dao.deleteDiagnosticSnapshots(listOf(id))
                        }
                    }
                }
            }
            WebBridgeProtocol.toJson(WebBridgeProtocol.deleteLogs(request, store))
        }
    }

    // --- verification.* ----------------------------------------------------

    private suspend fun verificationList(): JsonElement {
        val rows = withContext(Dispatchers.IO) {
            // 一次性快照：不长期订阅 Flow（Activity 销毁即释放）。
            app.database.dao().observeVerificationSessions().first()
        }.map { it.toRow() }
        return WebBridgePolicy.verificationListPayload(rows)
    }

    private suspend fun verificationOpen(params: JsonObject): JsonElement {
        val id = WebBridgeProtocol.parseVerificationId(params)
            ?: throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, "verification.open 需要字符串 id")
        val session = withContext(Dispatchers.IO) { app.database.dao().verificationSession(id) }
            ?: throw BridgeFailure(WebBridgeProtocol.ERR_NOT_FOUND, "验证任务不存在")
        if (!WebBridgePolicy.isVerificationUrlAllowed(session.url)) {
            throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, "验证任务地址不允许在隔离浏览器中打开")
        }
        val opener = verificationOpener
            ?: throw BridgeFailure(WebBridgeProtocol.ERR_UNSUPPORTED, "宿主未就绪，无法打开隔离验证页")
        withContext(Dispatchers.Main) { opener(session) }
        return JsonObject().apply { addProperty("opened", true) }
    }

    @Volatile private var verificationOpener: (suspend (VerificationSessionEntity) -> Unit)? = null

    fun setVerificationBrowserOpener(block: suspend (VerificationSessionEntity) -> Unit) {
        verificationOpener = block
    }

    /**
     * 完成会话：finalUrl **只**取宿主观察值。
     * 优先用隔离验证页当前 WebView 的 lastObservedUrl（由 MainActivity 注入），
     * 其次用宿主此前保存的 finalUrl（WAITING 期间由 onUrlObserved 回写），最后才是任务 URL。
     * 前端传的 finalUrl/url 一律丢弃。
     */
    private suspend fun verificationComplete(params: JsonObject): JsonElement {
        val parsed = when (val value = WebBridgeProtocol.parseVerificationComplete(params)) {
            is WebBridgeProtocol.CompleteParse.Ok -> value
            is WebBridgeProtocol.CompleteParse.Bad -> throw BridgeFailure(value.code, value.message)
        }
        val session = withContext(Dispatchers.IO) { app.database.dao().verificationSession(parsed.id) }
            ?: throw BridgeFailure(WebBridgeProtocol.ERR_NOT_FOUND, "验证任务不存在")
        val live = observedUrlProvider?.invoke(session.id)
        val observed = pickFinalUrl(live, session.finalUrl, session.url)
        val completed = withContext(Dispatchers.IO) {
            app.verification.complete(session.id, observed, parsed.answer)
        }
        main.post {
            onRequestCloseVerificationBrowser()
            emitVerificationBrowser("completed", completed.id)
        }
        return JsonParser.parseString(app.gson.toJson(completed.toRow()))
    }

    /** 宿主注入：返回隔离验证页当前真实观察地址（没有打开中的页面时返回 null）。 */
    @Volatile private var observedUrlProvider: ((String) -> String?)? = null

    fun setObservedUrlProvider(block: (String) -> String?) {
        observedUrlProvider = block
    }

    private suspend fun verificationClear(params: JsonObject): JsonElement {
        val id = WebBridgeProtocol.parseVerificationId(params)
            ?: throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, "verification.clear 需要字符串 id")
        withContext(Dispatchers.IO) { app.verification.clear(id) }
        return JsonObject().apply { addProperty("cleared", true) }
    }

    private suspend fun verificationClose(params: JsonObject): JsonElement {
        val id = WebBridgeProtocol.parseVerificationId(params)
            ?: throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, "verification.close 需要字符串 id")
        withContext(Dispatchers.IO) { app.verification.close(id) }
        return JsonObject().apply { addProperty("closed", true) }
    }

    private fun VerificationSessionEntity.toRow() = WebBridgePolicy.VerificationRow(
        id = id, domain = domain, url = url, purpose = purpose, status = status, kind = kind,
        imageData = imageData, finalUrl = finalUrl, answer = answer,
        createdAt = createdAt, updatedAt = updatedAt, jobId = jobId,
    )

    // --- file.export / file.import（SAF） ----------------------------------

    private suspend fun fileExport(params: JsonObject): JsonElement {
        val host = safHost ?: throw BridgeFailure(WebBridgeProtocol.ERR_UNSUPPORTED, "当前系统不支持文件保存")
        val name = WebBridgeProtocol.sanitizeFileName(stringParam(params, "name"))
            ?: throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, "name 不是合法文件名")
        val text = stringParam(params, "text")
        WebBridgeProtocol.checkExportText(text)?.let { throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, it) }
        val mime = WebBridgeProtocol.sanitizeMime(stringParam(params, "mime"))
        val saved = try {
            host.export(SafRequest.Export(name, text.orEmpty(), mime))
            true
        } catch (cancelled: SafLauncher.Cancelled) {
            false
        }
        return JsonObject().apply { addProperty("saved", saved) }
    }

    private suspend fun fileImport(): JsonElement {
        val host = safHost ?: throw BridgeFailure(WebBridgeProtocol.ERR_UNSUPPORTED, "当前系统不支持文件选择")
        val result = try {
            host.import()
        } catch (cancelled: SafLauncher.Cancelled) {
            return JsonObject().apply { addProperty("cancelled", true) }
        }
        val obj = JsonObject()
        obj.addProperty("name", result.name)
        obj.addProperty("text", result.text)
        return obj
    }

    /** null = 宿主未接 SAF（明确回 unsupported，不假装成功）。 */
    @Volatile private var safHost: SafHost? = null

    fun attachSaf(host: SafHost) {
        safHost = host
    }

    // --- app.theme ---------------------------------------------------------

    /**
     * 只同步系统栏图标/背景；palette 可选（前端切换配色时一并下发，宿主持久化）。
     * 前端传 `dark` 布尔值 —— 这是「当前生效的深色态」，宿主不反推模式。
     */
    private suspend fun theme(params: JsonObject): JsonElement {
        val darkElement = params.get("dark")
        if (darkElement == null || !darkElement.isJsonPrimitive || !darkElement.asJsonPrimitive.isBoolean) {
            throw BridgeFailure(WebBridgeProtocol.ERR_BAD_PARAMS, "dark 必须是布尔值")
        }
        val dark = darkElement.asBoolean
        val palette = stringParam(params, "palette")?.let { raw ->
            val normalized = WebAppearance.normalizePalette(raw)
            if (normalized != raw.trim().lowercase()) {
                throw BridgeFailure(
                    WebBridgeProtocol.ERR_BAD_PARAMS,
                    "palette 必须是 ${WebAppearance.PALETTE_KEYS.joinToString("/")}",
                )
            }
            withContext(Dispatchers.IO) { WebAppearance.save(activity.applicationContext, palette = normalized, mode = null) }
            normalized
        } ?: withContext(Dispatchers.IO) { WebAppearance.load(activity.applicationContext).palette }
        withContext(Dispatchers.Main) { onThemeApplied(dark, palette) }
        return JsonObject().apply { addProperty("applied", true) }
    }

    // --- 出站事件 ----------------------------------------------------------

    /** 宿主 → 前端事件 `{event, payload}`；未登记事件名直接丢弃。 */
    fun emit(event: String, payload: JsonElement?) {
        if (released) return
        val envelope = WebBridgeProtocol.event(event, payload) ?: return
        val proxy = eventProxy ?: return
        post(proxy, envelope)
    }

    fun emitVerificationBrowser(action: String, sessionId: String?) {
        val payload = JsonObject()
        payload.addProperty("action", action)
        sessionId?.let { payload.addProperty("id", it) }
        emit(WebBridgeProtocol.EVENT_VERIFICATION_BROWSER, payload)
    }

    /**
     * Insets 事件。
     *
     * [top]/[bottom]/[ime] 是**留给 Web 的** insets（CSS px）。MainActivity 用原生 padding
     * 让出系统栏/键盘后传 0，避免前端再算一次（双算会让内容被挤两次）。
     * [rawTop]/[rawBottom]/[rawIme]（px）与 [density] 只作信息字段，前端不得用于布局。
     */
    fun emitInsets(
        top: Int,
        bottom: Int,
        ime: Int,
        rawTop: Int = 0,
        rawBottom: Int = 0,
        rawIme: Int = 0,
        density: Float = 0f,
    ) {
        val payload = JsonObject()
        payload.addProperty("top", top)
        payload.addProperty("bottom", bottom)
        payload.addProperty("ime", ime)
        payload.addProperty("rawTop", rawTop)
        payload.addProperty("rawBottom", rawBottom)
        payload.addProperty("rawIme", rawIme)
        payload.addProperty("density", density)
        emit(WebBridgeProtocol.EVENT_INSETS, payload)
    }

    fun emitBack() {
        emit(WebBridgeProtocol.EVENT_BACK, JsonObject())
    }

    private fun post(proxy: JavaScriptReplyProxy, envelope: String) {
        main.post { runCatching { proxy.postMessage(envelope) } }
    }

    private fun stringParam(params: JsonObject, key: String): String? =
        params.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    internal class BridgeFailure(val code: String, message: String?) : RuntimeException(message)

    companion object {
        const val BRIDGE_OBJECT = "StudioBridge"
        private const val TAG = "StudioWebBridge"

        /**
         * 完成会话时的 finalUrl 选择（纯函数，JVM 可测）：
         * 1) 隔离页当前观察值（最真实）；
         * 2) 宿主此前保存的 finalUrl（WAITING 期间回写过）；
         * 3) 任务原始 URL。
         * 空串/空白一律视为缺失。
         */
        fun pickFinalUrl(liveObserved: String?, savedFinalUrl: String?, sessionUrl: String?): String {
            listOf(liveObserved, savedFinalUrl, sessionUrl).forEach { candidate ->
                if (!candidate.isNullOrBlank()) return candidate
            }
            return ""
        }
    }
}

/** SAF 请求/结果（Activity 侧实现，bridge 只描述意图）。 */
internal sealed interface SafRequest {
    data class Export(val name: String, val text: String, val mime: String) : SafRequest
}

internal data class SafImportResult(val name: String, val text: String)

/** SAF 宿主抽象：Activity 用 ActivityResultContracts 实现；bridge 不直接持有 launcher。 */
internal interface SafHost {
    suspend fun export(request: SafRequest.Export)
    suspend fun import(): SafImportResult
}
