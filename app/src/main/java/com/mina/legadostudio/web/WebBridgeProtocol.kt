package com.mina.legadostudio.web

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Web 宿主 ↔ Vue 前端的纯协议层（无 Android 依赖，JVM 可测）。
 *
 * 契约：`docs/WEB_UI_CONTRACT.md`。这里只做**判定与编码**，不做 IO：
 * - 来源保护（origin + main frame）；
 * - 方法 allowlist（显式枚举，绝不前缀匹配）；
 * - 请求大小/结构校验；
 * - 回包信封与错误脱敏；
 * - 各方法的参数校验（设置整体校验、工具调用、验证完成、导出名）。
 *
 * 任何新增方法都必须同时改 [METHODS]，否则前端调用会被 [parseRequest] 直接拒绝。
 */
internal object WebBridgeProtocol {

    /** 唯一可信来源：WebViewAssetLoader 的虚拟域名。 */
    const val TRUSTED_ORIGIN = "https://appassets.androidplatform.net"

    /** 主页面资源路径（AssetPathHandler 只服务 assets/web/）。 */
    const val INDEX_URL = "$TRUSTED_ORIGIN/assets/web/index.html"

    /**
     * 单次请求上限：契约规定 2MB，按 **UTF-8 字节**计（与 Rust 侧一致）。
     * 不能只看 `String.length`：中文 1 字符 3 字节，按字符放行等于允许 6MB 流量。
     */
    const val MAX_REQUEST_BYTES = 2 * 1024 * 1024

    /** 字节上限的字符数上界（每字符最多 3 字节的常见情形），仅用于快速预筛。 */
    const val MAX_REQUEST_CHARS = MAX_REQUEST_BYTES

    /** 回包错误文本上限，避免把整段异常栈塞进 UI。 */
    const val MAX_ERROR_CHARS = 600

    /** 请求 id 上限（前端生成，只做长度与字符集约束）。 */
    const val MAX_ID_CHARS = 128

    // --- 方法 allowlist ---------------------------------------------------

    const val M_BOOTSTRAP = "app.bootstrap"
    const val M_SERVICE_SET = "service.set"
    const val M_SETTINGS_SAVE = "settings.save"
    const val M_CONNECTION_COPY = "connection.copy"
    const val M_TOOLS_LIST = "tools.list"
    const val M_TOOLS_CALL = "tools.call"
    const val M_LOGS_DELETE = "logs.delete"
    const val M_VERIFICATION_LIST = "verification.list"
    const val M_VERIFICATION_OPEN = "verification.open"
    const val M_VERIFICATION_REPAIR = "verification.repairAddress"
    const val M_VERIFICATION_COMPLETE = "verification.complete"
    const val M_VERIFICATION_CLEAR = "verification.clear"
    const val M_VERIFICATION_CLOSE = "verification.close"
    const val M_VERIFICATION_BROWSER_CLOSE = "verification.browser.close"
    const val M_FILE_EXPORT = "file.export"
    const val M_FILE_IMPORT = "file.import"
    const val M_THEME = "app.theme"
    const val M_EXIT = "app.exit"

    val METHODS: Set<String> = linkedSetOf(
        M_BOOTSTRAP, M_SERVICE_SET, M_SETTINGS_SAVE, M_CONNECTION_COPY,
        M_TOOLS_LIST, M_TOOLS_CALL, M_LOGS_DELETE,
        M_VERIFICATION_LIST, M_VERIFICATION_OPEN, M_VERIFICATION_REPAIR, M_VERIFICATION_COMPLETE,
        M_VERIFICATION_CLEAR, M_VERIFICATION_CLOSE, M_VERIFICATION_BROWSER_CLOSE,
        M_FILE_EXPORT, M_FILE_IMPORT, M_THEME, M_EXIT,
    )

    /** 宿主 → 前端事件名（同样显式枚举，避免宿主自己造出未约定事件）。 */
    const val EVENT_BACK = "studio:back"
    const val EVENT_VERIFICATION_BROWSER = "studio:verification-browser"
    const val EVENT_INSETS = "studio:insets"
    const val EVENT_SERVICE = "studio:service"

    val EVENTS: Set<String> = linkedSetOf(EVENT_BACK, EVENT_VERIFICATION_BROWSER, EVENT_INSETS, EVENT_SERVICE)

    // --- 错误码 -----------------------------------------------------------

    const val ERR_BAD_REQUEST = "bad_request"
    const val ERR_BAD_PARAMS = "bad_params"
    const val ERR_PAYLOAD_TOO_LARGE = "payload_too_large"
    const val ERR_UNKNOWN_METHOD = "unknown_method"
    const val ERR_FORBIDDEN_ORIGIN = "forbidden_origin"
    const val ERR_UNSUPPORTED = "unsupported"
    const val ERR_NOT_FOUND = "not_found"
    const val ERR_TOOL_FAILED = "tool_failed"
    const val ERR_INTERNAL = "internal"
    const val ERR_CANCELLED = "cancelled"
    const val ERR_HOST_GONE = "host_gone"

    const val REJECT_FOREIGN_ORIGIN = "origin_not_allowed"
    const val REJECT_SUB_FRAME = "not_main_frame"

    /** 保留 HTML 安全转义；JSON 解码后内容不变，避免信封被嵌入脚本时提前闭合。 */
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    fun isAllowedMethod(method: String?): Boolean = method != null && method in METHODS

    fun isAllowedEvent(event: String?): Boolean = event != null && event in EVENTS

    // --- 来源保护 ---------------------------------------------------------

    sealed interface OriginVerdict {
        data object Accept : OriginVerdict
        data class Reject(val code: String) : OriginVerdict
    }

    /**
     * 严格 origin 比较：不做大小写折叠、不忽略端口、不做后缀匹配。
     * WebMessageListener 已在原生侧按 origin 过滤，这里是第二道闸（防配置写错）。
     */
    fun authorizeOrigin(origin: String?, isMainFrame: Boolean): OriginVerdict {
        if (origin == null || origin != TRUSTED_ORIGIN) return OriginVerdict.Reject(REJECT_FOREIGN_ORIGIN)
        if (!isMainFrame) return OriginVerdict.Reject(REJECT_SUB_FRAME)
        return OriginVerdict.Accept
    }

    // --- 请求解析 ---------------------------------------------------------

    sealed interface Parsed {
        data class Ok(val id: String, val method: String, val params: JsonObject) : Parsed
        data class Bad(val id: String?, val error: BridgeError) : Parsed
    }

    data class BridgeError(val code: String, val message: String)

    fun parseRequest(raw: String?): Parsed {
        if (raw == null) return Parsed.Bad(null, BridgeError(ERR_BAD_REQUEST, "空消息"))
        // 先按字符数粗筛：UTF-8 每字符最多 4 字节，字符数都超了就没必要再逐码点计数。
        if (raw.length > MAX_REQUEST_CHARS) {
            return Parsed.Bad(null, BridgeError(ERR_PAYLOAD_TOO_LARGE, "请求超过 ${MAX_REQUEST_BYTES / 1024}KB（UTF-8 字节）上限"))
        }
        val bytes = utf8Length(raw)
        if (bytes > MAX_REQUEST_BYTES) {
            return Parsed.Bad(null, BridgeError(ERR_PAYLOAD_TOO_LARGE, "请求超过 ${MAX_REQUEST_BYTES / 1024}KB（UTF-8 字节）上限"))
        }
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?: return Parsed.Bad(null, BridgeError(ERR_BAD_REQUEST, "消息不是合法 JSON"))
        if (!root.isJsonObject) return Parsed.Bad(null, BridgeError(ERR_BAD_REQUEST, "消息必须是 JSON 对象"))
        val obj = root.asJsonObject
        val id = readId(obj)
            ?: return Parsed.Bad(null, BridgeError(ERR_BAD_REQUEST, "缺少合法 id"))
        val method = obj.get("method")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (method.isNullOrBlank()) return Parsed.Bad(id, BridgeError(ERR_BAD_REQUEST, "缺少 method"))
        if (!isAllowedMethod(method)) {
            return Parsed.Bad(id, BridgeError(ERR_UNKNOWN_METHOD, "未登记的方法：${sanitizeMethodForEcho(method)}"))
        }
        val paramsElement = obj.get("params")
        val params = when {
            paramsElement == null || paramsElement is JsonNull -> JsonObject()
            paramsElement.isJsonObject -> paramsElement.asJsonObject
            else -> return Parsed.Bad(id, BridgeError(ERR_BAD_PARAMS, "params 必须是 JSON 对象"))
        }
        return Parsed.Ok(id, method, params)
    }

    private fun readId(obj: JsonObject): String? {
        val element = obj.get("id") ?: return null
        if (!element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        val id = when {
            primitive.isString -> primitive.asString
            primitive.isNumber -> primitive.asString
            else -> return null
        }
        if (id.isBlank() || id.length > MAX_ID_CHARS) return null
        if (id.any { it.isISOControl() }) return null
        return id
    }

    /** 回显未知方法名时去掉控制字符，避免把任意字节回灌到前端。 */
    private fun sanitizeMethodForEcho(method: String): String =
        method.filterNot { it.isISOControl() }.take(64)

    // --- 回包 -------------------------------------------------------------

    fun success(id: String, result: JsonElement?): String {
        val obj = JsonObject()
        obj.addProperty("id", id)
        obj.addProperty("ok", true)
        obj.add("result", result ?: JsonObject())
        return gson.toJson(obj)
    }

    fun failure(id: String?, code: String, message: String): String {
        val obj = JsonObject()
        if (id != null) obj.addProperty("id", id)
        obj.addProperty("ok", false)
        val error = JsonObject()
        error.addProperty("code", code)
        error.addProperty("message", redact(message).take(MAX_ERROR_CHARS))
        obj.add("error", error)
        return gson.toJson(obj)
    }

    /**
     * 宿主 → 前端事件信封：`{event, payload}`。
     * 前端 `StudioBridge.onmessage` 统一按 `{event, payload}` 分发（与 RPC 回包 `{id,ok,...}` 区分）；
     * 只有 MainActivity 用 `window.dispatchEvent(CustomEvent)` 派发的路由事件走 `detail`（DOM 规定字段）。
     */
    fun event(name: String, payload: JsonElement?): String? {
        if (!isAllowedEvent(name)) return null
        val obj = JsonObject()
        obj.addProperty("event", name)
        obj.add("payload", payload ?: JsonObject())
        return gson.toJson(obj)
    }

    /**
     * `tools.call` 的结果：MCP CallToolResult 原形（`content` + `isError` + 可选 `structuredContent`）。
     * [contentJson] 来自 SDK 自身的序列化；无法解析时降级为一条错误文本，
     * 而不是把异常抛给前端（前端永远拿到可渲染结果）。
     */
    fun toolResultJson(isError: Boolean, contentJson: String, structuredJson: String?): String {
        val obj = JsonObject()
        val content = runCatching { JsonParser.parseString(contentJson) }.getOrNull()
        if (content != null && content.isJsonArray) {
            obj.add("content", content)
            obj.addProperty("isError", isError)
        } else {
            val fallback = JsonArray()
            fallback.add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", "工具结果解析失败（content 不是 JSON 数组）")
            })
            obj.add("content", fallback)
            obj.addProperty("isError", true)
        }
        if (structuredJson != null) {
            runCatching { JsonParser.parseString(structuredJson) }
                .getOrNull()?.takeIf { !it.isJsonNull }?.let { obj.add("structuredContent", it) }
        }
        return gson.toJson(obj)
    }

    fun toJson(element: Any?): JsonElement = when (element) {
        null -> JsonNull.INSTANCE
        is JsonElement -> element
        is String -> JsonParser.parseString(gson.toJson(element))
        else -> JsonParser.parseString(gson.toJson(element))
    }

    // --- 脱敏 -------------------------------------------------------------

    private val BEARER = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=\\-]{6,}")
    private val AUTH_HEADER = Regex("(?i)(authorization|cookie|set-cookie|x-api-key|api[-_]?key)\\s*[:=]\\s*[^\\s,;]{4,}")
    private val SECRET_QUERY = Regex("(?i)([?&;](?:token|access_token|refresh_token|id_token|key|apikey|api_key|secret|sig|signature|password|pwd)=)[^&#\\s]+")
    private val URL_CREDENTIALS = Regex("(?i)\\b([a-z][a-z0-9+.\\-]*://)[^/@\\s:]+:[^/@\\s]+@")

    /**
     * 错误文本脱敏：只保留可定位信息（状态码、域名、错误类别），
     * 去掉令牌、Authorization/Cookie 头、URL 内嵌凭据与敏感 query。
     */
    fun redact(text: String): String = text
        .replace(BEARER, "$1 [已隐藏]")
        .replace(AUTH_HEADER, "$1: [已隐藏]")
        .replace(SECRET_QUERY, "$1[已隐藏]")
        .replace(URL_CREDENTIALS, "$1[已隐藏]@")

    // --- 本地日志删除：精确身份，禁止日期/全库/路径删除 ----------------------

    const val MAX_LOG_DELETE_BATCH = 100
    data class LogDelete(val kind: String, val ids: List<String>)
    data class LogDeleteFailure(val id: String, val code: String)
    data class LogDeleteResult(
        val kind: String,
        val deletedIds: List<String>,
        val missingIds: List<String>,
        val failed: List<LogDeleteFailure>,
    )

    private val LOG_UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

    fun parseLogDelete(params: JsonObject): LogDelete? {
        if (params.keySet() != setOf("kind", "ids")) return null
        val kind = params.get("kind")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        if (kind !in setOf("operation", "http", "capture", "crash", "snapshot")) return null
        val array = params.get("ids")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (array.size() !in 1..MAX_LOG_DELETE_BATCH) return null
        val ids = mutableListOf<String>()
        for (element in array) {
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
            val id = element.asString
            val valid = when (kind) {
                "operation", "http" -> id.matches(Regex("[1-9][0-9]{0,18}")) && id.toLongOrNull() != null
                "capture" -> id.matches(Regex("cap:$LOG_UUID"))
                "snapshot" -> id.matches(Regex(LOG_UUID))
                "crash" -> id.matches(Regex("(?:crash|fault)-[0-9]{1,19}\\.txt"))
                else -> false
            }
            if (!valid || id in ids) return null
            ids += id
        }
        return LogDelete(kind, ids)
    }

    /** Store must check category ownership/activity BEFORE mutating, and include files in readback. */
    interface LogDeleteStore {
        suspend fun exists(kind: String, id: String): Boolean
        suspend fun blockedReason(kind: String, id: String): String?
        suspend fun delete(kind: String, id: String)
    }

    suspend fun deleteLogs(request: LogDelete, store: LogDeleteStore): LogDeleteResult {
        val deleted = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val failed = mutableListOf<LogDeleteFailure>()
        for (id in request.ids) {
            try {
                val reason = store.blockedReason(request.kind, id)
                if (reason != null) {
                    failed += LogDeleteFailure(id, reason)
                } else if (!store.exists(request.kind, id)) {
                    missing += id
                } else {
                    store.delete(request.kind, id)
                    if (store.exists(request.kind, id)) failed += LogDeleteFailure(id, "delete_unconfirmed")
                    else deleted += id
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed += LogDeleteFailure(id, "delete_failed")
            }
        }
        return LogDeleteResult(request.kind, deleted, missing, failed)
    }

    // --- 参数校验：service / connection -----------------------------------

    fun parseServiceAction(raw: String?): ServiceAction? = when (raw) {
        "start" -> ServiceAction.START
        "stop" -> ServiceAction.STOP
        "restart" -> ServiceAction.RESTART
        else -> null
    }

    fun parseConnectionKind(raw: String?): ConnectionKind? = when (raw) {
        "url" -> ConnectionKind.URL
        "token" -> ConnectionKind.TOKEN
        "json" -> ConnectionKind.JSON
        else -> null
    }

    // --- 参数校验：文件 ---------------------------------------------------

    private val UNSAFE_FILE_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    /** SAF 只接受文件名（不接受路径）：去掉目录分隔与控制字符，保留扩展名。 */
    fun sanitizeFileName(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val cleaned = text.replace(UNSAFE_FILE_CHARS, "_").trim('.', '_', ' ')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
        if (cleaned.length > 120) return null
        return cleaned
    }

    fun checkExportText(text: String?): String? {
        if (text == null) return "text 不能为空"
        if (utf8Length(text) > MAX_REQUEST_BYTES) {
            return "导出内容超过 ${MAX_REQUEST_BYTES / 1024}KB（UTF-8 字节）上限"
        }
        return null
    }

    /**
     * UTF-8 字节长度。按码点计：BMP 外字符 4 字节，代理对只算一次，
     * 避免把孤立的代理项当成 3 字节的 U+FFFD。
     */
    fun utf8Length(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            bytes += when {
                code <= 0x7F -> 1
                code <= 0x7FF -> 2
                code <= 0xFFFF -> 3
                else -> 4
            }
            i += Character.charCount(code)
        }
        return bytes
    }

    private val MIME = Regex("^[a-z0-9][a-z0-9!#$&^_.+\\-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+\\-]{0,63}$")

    /** MIME 只接受裸 type/subtype，防止把 CRLF 头注入塞进 Intent。 */
    fun sanitizeMime(raw: String?): String {
        val base = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return if (MIME.matches(base)) base else "text/plain"
    }

    // --- 参数校验：工具调用 -----------------------------------------------

    sealed interface ToolCallParse {
        data class Ok(val name: String, val arguments: JsonObject) : ToolCallParse
        data class Bad(val code: String, val message: String) : ToolCallParse
    }

    fun parseToolCall(params: JsonObject): ToolCallParse {
        val nameElement = params.get("name")
        val name = nameElement?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (name.isNullOrBlank()) return ToolCallParse.Bad(ERR_BAD_PARAMS, "tools.call 需要字符串 name")
        if (name.length > 120 || name.any { it.isISOControl() }) {
            return ToolCallParse.Bad(ERR_BAD_PARAMS, "工具名不合法")
        }
        val argsElement = params.get("arguments")
        val arguments = when {
            argsElement == null || argsElement is JsonNull -> JsonObject()
            argsElement.isJsonObject -> argsElement.asJsonObject
            else -> return ToolCallParse.Bad(ERR_BAD_PARAMS, "arguments 必须是 JSON 对象")
        }
        return ToolCallParse.Ok(name, arguments)
    }

    // --- 参数校验：验证完成 -----------------------------------------------

    const val MAX_VERIFICATION_ANSWER_CHARS = 200

    sealed interface CompleteParse {
        data class Ok(val id: String, val answer: String) : CompleteParse
        data class Bad(val code: String, val message: String) : CompleteParse
    }

    /**
     * 只取 id 与 answer。前端即便传了 finalUrl/url，也一律丢弃：
     * 完成态地址必须来自宿主 WebView 的实际观察。
     */
    fun parseVerificationComplete(params: JsonObject): CompleteParse {
        val id = params.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (id.isNullOrBlank() || id.length > MAX_ID_CHARS) {
            return CompleteParse.Bad(ERR_BAD_PARAMS, "verification.complete 需要字符串 id")
        }
        val answerElement = params.get("answer")
        val answer = when {
            answerElement == null || answerElement is JsonNull -> ""
            answerElement.isJsonPrimitive && answerElement.asJsonPrimitive.isString -> answerElement.asString
            else -> return CompleteParse.Bad(ERR_BAD_PARAMS, "answer 必须是字符串")
        }
        if (answer.length > MAX_VERIFICATION_ANSWER_CHARS) {
            return CompleteParse.Bad(ERR_BAD_PARAMS, "answer 超过 $MAX_VERIFICATION_ANSWER_CHARS 字符")
        }
        return CompleteParse.Ok(id, answer)
    }

    data class VerificationRepair(val id: String, val url: String)

    fun parseVerificationRepair(params: JsonObject): VerificationRepair? {
        if (params.keySet() != setOf("id", "url")) return null
        val id = parseVerificationId(params)?.takeUnless { it.any(Char::isISOControl) } ?: return null
        val url = params.get("url")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        if (url.length > 8192 || !WebBridgePolicy.isVerificationUrlAllowed(url)) return null
        return VerificationRepair(id, url)
    }

    fun parseVerificationId(params: JsonObject): String? {
        val id = params.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return id?.takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS }
    }

    // --- 参数校验：设置整体 -------------------------------------------------

    const val MIN_PORT = 1024
    const val MAX_PORT = 65530
    const val MIN_UA = 8
    const val MAX_UA = 500
    const val MAX_READER_TOKEN = 512

    /**
     * 外观：宿主模式 light/system/dark，Vue 模式 light/auto/dark 都接受（映射在 WebAppearance）。
     * palette 只接受 10 套 ID（见 [WebAppearance.PALETTE_KEYS]），不接受颜色 hex。
     */
    val APPEARANCE_MODES = setOf("light", "system", "dark", "auto")

    /**
     * 已校验的设置增量。所有字段可空 = 未提供（保持原值）。
     *
     * [tokenRequiredLocked] 恒为 true：契约禁止任何「关闭令牌校验」的操作，
     * 前端传入 tokenRequired 字段会被忽略而不是报错（避免暴露该开关的存在）。
     */
    data class SettingsDraft(
        val port: Int? = null,
        val compact: Boolean? = null,
        val userAgent: String? = null,
        val bookSourceType: Int? = null,
        val readerBaseUrl: String? = null,
        val readerToken: String? = null,
        val appearanceTheme: String? = null,
        val appearancePalette: String? = null,
        val tokenRequiredLocked: Boolean = true,
    )

    sealed interface SettingsParse {
        data class Ok(val settings: SettingsDraft) : SettingsParse
        data class Bad(val message: String) : SettingsParse
    }

    /**
     * 整体校验：收集**全部**字段错误后一次性返回，绝不「先写一半再报错」。
     * 类型不符直接失败，不做字符串→数字之类的隐式转换。
     */
    fun parseSettings(params: JsonObject): SettingsParse {
        val errors = mutableListOf<String>()

        fun str(key: String): String? {
            val element = params.get(key) ?: return null
            if (element is JsonNull) return null
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                errors += "$key 必须是字符串"
                return null
            }
            return element.asString
        }

        fun int(key: String, range: IntRange): Int? {
            val element = params.get(key) ?: return null
            if (element is JsonNull) return null
            if (!element.isJsonPrimitive || element.asJsonPrimitive.isString) {
                errors += "$key 必须是整数"
                return null
            }
            // 不能用 asInt：Gson 会把 1.5 截成 1、把越界大数截成 Int.MAX_VALUE，等于放行非法输入。
            val value = exactInt(element)
            if (value == null) {
                errors += "$key 必须是整数"
                return null
            }
            if (value !in range) {
                errors += "$key 必须在 ${range.first}..${range.last}"
                return null
            }
            return value
        }

        fun bool(key: String): Boolean? {
            val element = params.get(key) ?: return null
            if (element is JsonNull) return null
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
                errors += "$key 必须是布尔值"
                return null
            }
            return element.asBoolean
        }

        val port = int("port", MIN_PORT..MAX_PORT)
        val compact = bool("compact")
        val userAgent = str("userAgent")?.also { ua ->
            if (ua.trim().length !in MIN_UA..MAX_UA) errors += "userAgent 长度必须在 $MIN_UA..$MAX_UA"
            if (runCatching { okhttp3.Headers.Builder().add("User-Agent", ua) }.isFailure) {
                errors += "userAgent 包含非法 HTTP 头字符"
            }
        }
        val bookSourceType = int("bookSourceType", -1..4)
        val readerBaseUrl = str("readerBaseUrl")?.also { raw ->
            if (raw.isNotBlank()) {
                val normalized = runCatching { com.mina.legadostudio.reader.ReaderWebOrigin.normalize(raw) }.getOrNull()
                if (normalized == null) errors += com.mina.legadostudio.reader.ReaderWebOrigin.INVALID_MESSAGE
            }
        }?.takeIf { it.isNotBlank() }?.let { runCatching { com.mina.legadostudio.reader.ReaderWebOrigin.normalize(it) }.getOrNull() }
        val readerToken = str("readerToken")?.also {
            if (it.length > MAX_READER_TOKEN) errors += "readerToken 超过 $MAX_READER_TOKEN 字符"
        }
        var appearanceTheme: String? = null
        var appearancePalette: String? = null
        run {
            val element = params.get("appearance") ?: return@run
            if (element is JsonNull) return@run
            if (!element.isJsonObject) {
                errors += "appearance 必须是对象"
                return@run
            }
            val appearance = element.asJsonObject
            appearance.get("theme")?.takeIf { it !is JsonNull }?.let { themeElement ->
                if (!themeElement.isJsonPrimitive || !themeElement.asJsonPrimitive.isString) {
                    errors += "appearance.theme 必须是字符串"
                } else {
                    val theme = themeElement.asString.trim().lowercase()
                    if (theme !in APPEARANCE_MODES) {
                        errors += "appearance.theme 必须是 ${APPEARANCE_MODES.joinToString("/")}"
                    } else {
                        appearanceTheme = theme
                    }
                }
            }
            appearance.get("palette")?.takeIf { it !is JsonNull }?.let { paletteElement ->
                if (!paletteElement.isJsonPrimitive || !paletteElement.asJsonPrimitive.isString) {
                    errors += "appearance.palette 必须是字符串"
                } else {
                    val palette = paletteElement.asString.trim().lowercase()
                    if (palette !in WebAppearance.PALETTE_KEYS) {
                        errors += "appearance.palette 必须是 ${WebAppearance.PALETTE_KEYS.joinToString("/")}"
                    } else {
                        appearancePalette = palette
                    }
                }
            }
        }

        if (errors.isNotEmpty()) return SettingsParse.Bad(errors.joinToString("; "))
        return SettingsParse.Ok(
            SettingsDraft(
                port = port,
                compact = compact,
                userAgent = userAgent,
                bookSourceType = bookSourceType,
                readerBaseUrl = readerBaseUrl,
                readerToken = readerToken,
                appearanceTheme = appearanceTheme,
                appearancePalette = appearancePalette,
            )
        )
    }

    // --- 小工具 -----------------------------------------------------------

    /**
     * 严格整数：只接受 JSON 整数字面量且落在 Int 范围内。
     * `1.5`、`1e3`、`1.0` 这类浮点写法一律返回 null（不做截断/四舍五入）。
     */
    fun exactInt(element: JsonElement): Int? {
        if (!element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        if (primitive.isBoolean || primitive.isString) return null
        val raw = runCatching { primitive.asString }.getOrNull() ?: return null
        if (raw.isEmpty()) return null
        val body = raw.removePrefix("-")
        if (body.isEmpty() || body.any { it !in '0'..'9' }) return null
        return raw.toIntOrNull()
    }

    fun jsonArrayOf(block: JsonArray.() -> Unit): JsonArray = JsonArray().apply(block)

    fun stringOrNull(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    fun boolOrFalse(obj: JsonObject, key: String): Boolean =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
}

internal enum class ServiceAction { START, STOP, RESTART }

internal enum class ConnectionKind { URL, TOKEN, JSON }
