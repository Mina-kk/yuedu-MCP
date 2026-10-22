package com.mina.legadostudio.reader

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 阅读 App（Legado）Web HTTP 服务的专用客户端。
 *
 * 安全边界（刻意收得很窄）：
 * - 只向用户保存的 origin 发请求，路径来自固定 allowlist，本类不提供「任意 URL/任意路径/任意请求头」入口，
 *   MCP 模型无法借它探测其它主机；也不扫描 WebSocket 端口、不访问任何未列出的路径；
 *   端口只来自已保存配置——不写死默认端口，也不在失败后自动回落/扫描别的端口；
 * - 完全禁止自动重定向（3xx 直接报错），避免被 302 引到别的域；
 * - 独立 OkHttpClient：无 CookieJar、无 HTTP 日志、无代理注入（`Proxy.NO_PROXY`），不复用书源会话；
 * - 响应体上限 + 超时；错误文本统一脱敏，不包含访问令牌或用户的私网地址。
 *
 * 传输层（针对「同秒其它通道成功、本通道偶发 UNREACHABLE」的现场）：
 * - 用项目已有的浏览器 UA / `Accept-Language` 与 Brotli 解压组件（与书源抓取通道一致），
 *   因为阅读 Web 服务前置的反代常对非浏览器 UA 直接断连；
 * - 零 idle 连接池 + `Connection: close`：每条请求用完即关，绝不复用可能已被对端关闭的陈旧连接
 *   （复用陈旧连接是 `SocketException`/`EOF` 偶发的最常见来源）；
 * - `retryOnConnectionFailure(false)`，只由本类在**可恢复**的 Socket/EOF/协议错误上为 GET/HEAD
 *   补一次新连接重试；POST 永不因 IO 异常重放（写入语义不能靠重试猜）；
 * - 一次逻辑请求共用同一个调用预算（deadline）：重试只消耗剩余预算，不把总超时翻倍；
 * - 401 `WWW-Authenticate: Bearer` 的同一 origin 认证重发保留，但计入 `attempts` 并标 `authRetry`。
 *
 * 版本差异（不能一刀切成「不像阅读」）：
 * - 官方 Legado 很早就提供公开读接口 `/getBookSources`、`/getBookSource?url=`；
 *   `/getJsSourceApiTokenRequired`、`/getBookSourcesForManagement`、`/startBookSourceCheck`、
 *   `/getBookSourceCheckStates` 是较新版本才有的。
 * - 旧版/叉版对未知路径可能回 HTML 前端页、404 或 500。探测时先用新版公开接口
 *   （该 GET 不带 `X-Legado-Token`），失败后再用**固定旧版公开读路由**核实：
 *   是 → `LEGACY_READER`（网页在线但没有结构化检验接口）；不是 → `NOT_READER`
 *   （网页在线但官方接口不匹配）。两者都**不断言网页不是阅读，也不要求升级**。
 * - 新版探测正常、但书源管理/检验路由缺失时，报 `CHECK_UNSUPPORTED`，而不是「不像阅读」。
 * - 网页本身是否在线由 [probeWebPage] 单独判定：只 GET 已保存 origin 的根路径，不带令牌、
 *   不跟随跨域重定向；它才是「用户能否操作其选择的阅读 Web 页面」的依据。
 * - HTTP 200 + ReturnData `isSuccess=false` 是业务/令牌错误，不是服务不可达。
 * - 只有受保护路由（书源管理、检验、写入）才发送 `X-Legado-Token`；公开读路由与新接口探测都不带。
 *
 * 诊断：失败一律带 [ReaderWebRequestDiagnostic]（方法/固定路径/阶段/失败相位/异常类别/尝试次数/连接复用），
 * 不含 host、port、查询串、令牌、书源正文或原始异常 URL。
 *
 * 协议依据：LegadoTeam/legado 的 `HttpServer.kt`、`BookSourceCheckController.kt`、`ReturnData.kt`。
 */
class ReaderWebClient private constructor(
    private val configProvider: () -> ReaderWebConfigStore.Config?,
    private val clientFactory: () -> OkHttpClient,
    private val pinnedConfig: ReaderWebConfigStore.Config?,
) {
    constructor(
        configProvider: () -> ReaderWebConfigStore.Config?,
        clientFactory: () -> OkHttpClient = { defaultClient() },
    ) : this(configProvider, clientFactory, null)

    /**
     * 固定当前配置快照，返回一个此后不再读配置的客户端。
     *
     * 一次检验要连发多个请求；如果中途用户在界面上改了地址，继续 `load()` 会让同一份
     * `sessionToken` 跨主机继续、并把旧令牌发到新地址。检验开始时固定快照，改动只影响下一次调用。
     */
    fun pinConfig(): ReaderWebClient {
        val snapshot = pinnedConfig ?: configProvider()
        return ReaderWebClient({ snapshot }, { httpClient }, snapshot)
    }

    /**
     * @param stage 出错阶段（`token_probe` / `legacy_probe` / `lookup` / `readback` / `save` /
     *   `sources` / `start` / `states`），供界面/工具显示「卡在哪一步」，不影响处理逻辑。
     * @param statusCode 已收到的 HTTP 状态码（网络层失败为 null）。
     * @param bearerChallenge 该 401/403 明确声明了 `WWW-Authenticate: Bearer`：只有这种响应才允许
     *   在同一 origin 上改发 `Authorization: Bearer`，公开探测不会预先携带令牌。
     * @param diagnostic 脱敏诊断（方法/固定路径/阶段/相位/异常类别/尝试次数/连接复用）；
     *   不含 host、port、查询串、令牌与原始 URL。默认 null 保持既有调用兼容。
     */
    class ReaderWebException(
        val code: String,
        message: String,
        val stage: String = "",
        val statusCode: Int? = null,
        val bearerChallenge: Boolean = false,
        val diagnostic: ReaderWebRequestDiagnostic? = null,
    ) : Exception(message) {
        /** 严格意义上的「该路由不存在」：404/405/410，或读取阶段的 5xx。 */
        val endpointMissing: Boolean get() = code == "ENDPOINT_MISSING"

        /** 响应形状不符（HTML/缺 isSuccess/data 不是 Legend ReturnData）：**不是**路由缺失。 */
        val shapeMismatch: Boolean get() = code == "INCOMPATIBLE"

        /**
         * 该 origin 没有可用的阅读接口（路由缺失**或**响应形状不符）。
         * 历史含义：调用方据此降级到旧版公开读/旧版调试；需要严格区分时用
         * [endpointMissing] / [shapeMismatch]。
         */
        val routeMissing: Boolean get() = endpointMissing || shapeMismatch

        /** 给已有异常补诊断（不覆盖已有诊断），供调用方在自己的分支上透出。 */
        fun withDiagnostic(value: ReaderWebRequestDiagnostic?): ReaderWebException =
            if (value == null || diagnostic != null) this
            else ReaderWebException(code, message.orEmpty(), stage, statusCode, bearerChallenge, value)
    }

    /**
     * @property diagnostic 令牌开关那次 GET 的脱敏诊断（成功也有）：
     *   下一环节可据此判断「接口可读」是新建连接还是复用、试了几次。
     */
    data class Probe(
        val tokenRequired: Boolean,
        val bearerAuth: Boolean = false,
        val diagnostic: ReaderWebRequestDiagnostic? = null,
    )

    /**
     * 网页根路径探测结果（用户能否操作其选择的阅读 Web 页面）。
     *
     * @property reachable 已保存 origin 的根路径有正常 HTTP 响应（200/204，或同源 3xx 跳转）
     * @property statusCode 实际收到的 HTTP 状态码
     * @property contentKind `html` / `json` / `empty`：只作分类，**不是**结构化接口检验结论
     * @property viaRedirect 根路径是否经同源 3xx 跳到入口（越域重定向一律不跟随，报 `REDIRECT_BLOCKED`）
     * @property diagnostic 最终一次根路径请求的脱敏诊断（成功时也有，供 `reader_web_status` 透出）
     */
    data class WebPageProbe(
        val reachable: Boolean,
        val statusCode: Int,
        val contentKind: String,
        val viaRedirect: Boolean = false,
        val diagnostic: ReaderWebRequestDiagnostic? = null,
    )

    data class SourceEntry(val source: JsonObject, val state: JsonObject?)
    data class StartResult(val sessionToken: String, val sourceRevisions: Map<String, String>)
    data class CheckStates(val sessionToken: String?, val states: List<JsonObject>)

    /**
     * `GET /getBookSource?url=` 的结果。
     *
     * @property present false = 阅读明确回答「没有这个源」；true = data 里是完整 BookSource。
     * @property bearerAuth 阅读对该路由要求 `Authorization: Bearer`（收到 401 挑战后才发送）。
     * @property diagnostic 本次读取的脱敏诊断（`stage` 会是 `lookup` 或 `readback`，成功也有）：
     *   供同步/回读环节区分「预检失败」与「写入成功但回读失败」，也供下一任务消费。
     */
    data class SourceLookup(
        val present: Boolean,
        val source: JsonObject?,
        val bearerAuth: Boolean = false,
        val diagnostic: ReaderWebRequestDiagnostic? = null,
    )

    /**
     * 写入结果：`POST /saveBookSource` 只发一次，`diagnostic` 如实记录方法/路径/阶段/尝试次数。
     * 成功返回诊断而不只是 Unit，下一环节（回读失败归因）才有据可依。
     */
    data class SaveResult(val diagnostic: ReaderWebRequestDiagnostic? = null)

    /**
     * 书源读取的**固定阶段枚举**：`fetchSource` 只接受这两个值。
     *
     * 同步流程用 `lookup`（写入前预检），写入后的核对用 `readback`；
     * 诊断里的 `stage` 因此能让下一环节直接区分「预检失败」与「写入成功、回读读不到」。
     */
    object Stage {
        const val LOOKUP = "lookup"
        const val READBACK = "readback"
        val READ_STAGES: Set<String> = setOf(LOOKUP, READBACK)
    }

    // ---- 固定 allowlist 路由 ----
    private companion object {
        const val TOKEN_HEADER = "X-Legado-Token"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        const val PATH_TOKEN_REQUIRED = "/getJsSourceApiTokenRequired"
        const val PATH_SOURCES_FOR_MANAGEMENT = "/getBookSourcesForManagement"
        const val PATH_START_CHECK = "/startBookSourceCheck"
        const val PATH_CHECK_STATES = "/getBookSourceCheckStates"
        // 旧版公开读路由：只用于「到底是不是阅读」的核实与同步前的精确预检
        const val PATH_LEGACY_SOURCES = "/getBookSources"
        const val PATH_LEGACY_SOURCE = "/getBookSource"
        // 旧版/叉版通用的书源写入路由：只在同步工具里、且预检确认不存在或被显式允许覆盖后调用
        const val PATH_SAVE_SOURCE = "/saveBookSource"

        const val ACCEPT_JSON = "application/json"
        const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"

        /**
         * 与书源抓取通道（`HttpFetcher`）同一个浏览器 UA 字符串：阅读 Web 服务前面常挂着反代/WAF，
         * 非浏览器 UA 会被直接断连（表现为零星的 UNREACHABLE）。
         *
         * 这里刻意**复制**字面量，而不是引用抓取器里的 UA 常量：阅读通道不得与书源会话
         * （cookie jar / 日志 / 可配置 UA）产生任何耦合，`ReaderWebToolContractTest` 据此锁定。
         * 两份字符串一旦漂移，`ReaderWebTransportTest` 会立刻失败（断言与抓取器常量相等）。
         */
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/150 Mobile Safari/537.36"

        /** 与书源抓取通道一致的 `Accept-Language`。 */
        const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9"

        const val MAX_BODY_BYTES = 4L * 1024 * 1024
        /** 旧版核实只看响应开头：老版本几千条书源会远超 4MB，不能整包读进内存。 */
        const val LEGACY_PREFIX_BYTES = 16L * 1024
        /** 网页根路径只需要确认「有页面」：有界读取，不把整站首页读进内存。 */
        const val WEB_PAGE_PREFIX_BYTES = 64L * 1024
        const val MAX_PAGE_REDIRECTS = 3
        const val MAX_SOURCE_URL_CHARS = 4_096
        const val CONNECT_TIMEOUT_SEC = 5L
        const val READ_TIMEOUT_SEC = 15L
        const val CALL_TIMEOUT_SEC = 20L
        const val CALL_TIMEOUT_MS = CALL_TIMEOUT_SEC * 1_000L

        /** `fetchSource` 允许的读取阶段：只允许这两种固定取值，调用方不能自定义诊断标签。 */
        val READ_STAGES = Stage.READ_STAGES

        /**
         * 官方 `getBookSource` 在书源不存在时的错误文本。只认这几种明确说法；
         * 「搜索无结果」等正常业务文本不在此列，避免把有效书源误判成不存在。
         */
        val SOURCE_MISSING_MESSAGES = listOf(
            "未找到源", "未找到对应书源", "未找到书源", "书源不存在", "没有找到书源",
            "source not found", "book source not found", "no such source",
        )

        /**
         * 旧版/叉版对未知路径常见的「路由不存在」状态。
         * 502/503 是网关/服务暂时不可用，说明请求可能到了阅读（或中途代理），不能当成「没有这个接口」，
         * 否则会把临时故障误报成「版本不支持」。
         */
        val ROUTE_MISSING_CODES = setOf(404, 405, 410, 500, 501)

        /**
         * 生产传输。
         *
         * - `Proxy.NO_PROXY`：局域网直连，绝不让访问令牌经过系统代理；
         * - `ConnectionPool(0, ...)`：零 idle 连接，每条请求用完即断；
         * - `retryOnConnectionFailure(false)`：不在传输层盲重试，重试只由本类按幂等性决定；
         * - `BrotliInterceptor`：与书源抓取通道一致，`br`/`gzip` 由 OkHttp 透明解压；
         * - `eventListenerFactory`：测量本次 call 是否新建了 TCP 连接（诊断用，不影响传输）。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .addInterceptor(BrotliInterceptor)
            .eventListenerFactory(ReaderWebConnectionObserver.factory)
            .build()
    }

    private data class Ctx(val baseUrl: HttpUrl, val token: String, val origin: String)

    /** 复用同一客户端（连接池为空、超时统一）；测试可经构造参数替换工厂。 */
    private val httpClient by lazy { clientFactory() }

    /**
     * 一次逻辑请求的总预算：重试只消耗剩余时间，不把总超时翻倍。
     * 预算取「客户端 callTimeout 与生产上限的较小值」，测试注入的短超时仍然生效。
     */
    private class Budget(deadlineNanos: Long) {
        private val deadline = deadlineNanos

        fun remainingMillis(): Long = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
    }

    private fun newBudget(): Budget {
        val configured = httpClient.callTimeoutMillis
        val budgetMs = if (configured in 1..CALL_TIMEOUT_MS.toInt()) configured.toLong() else CALL_TIMEOUT_MS
        return Budget(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs))
    }

    /**
     * 一条固定路由的请求描述。方法由有没有请求体决定，调用方不能传任意方法/请求头。
     */
    private class HttpSpec(
        val method: String,
        val path: String,
        val stage: String,
        val query: Map<String, String> = emptyMap(),
        val jsonBody: String? = null,
        val protected: Boolean = false,
        val maxBytes: Long = MAX_BODY_BYTES,
        val truncate: Boolean = false,
        val accept: String = ACCEPT_JSON,
        val allowRedirect: Boolean = false,
    )

    private fun contextOrThrow(): Ctx {
        val config = configProvider() ?: throw ReaderWebException(
            "NOT_CONFIGURED",
            "尚未配置阅读 Web 服务地址：请在 MCP 页「设置」→「阅读 Web 服务」填写阅读 App 的 Web 服务地址并保存",
        )
        val baseUrl = runCatching { config.baseUrl.toHttpUrl() }.getOrElse {
            throw ReaderWebException("INVALID_CONFIG", "已保存的阅读 Web 服务地址无效，请重新填写")
        }
        return Ctx(baseUrl, config.token, config.baseUrl.trimEnd('/'))
    }

    private fun urlOf(ctx: Ctx, path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = ctx.baseUrl.newBuilder().encodedPath(path)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val built = builder.build()
        // 双保险：固定路径 + 同一 origin 才允许发请求
        require(built.host == ctx.baseUrl.host && built.port == ctx.baseUrl.port && built.scheme == ctx.baseUrl.scheme) {
            "内部错误：请求目标与已保存的 origin 不一致"
        }
        return built
    }

    // ---- 对外操作 ----

    /**
     * 网页入口探测：只 `GET` 已保存 origin 的**根路径**，用于回答「用户能不能操作其选择的阅读 Web 页面」。
     *
     * 与官方接口能力（[probe]）完全独立：接口不匹配不等于网页不能操作。
     * - 不带任何令牌（`X-Legado-Token` / `Authorization` / Cookie 都不发）；
     * - 只向已保存 origin 发请求，不扫描默认端口、不接受调用方传入 URL；
     * - 同源 3xx 视为「页面入口」并最多跟随 [MAX_PAGE_REDIRECTS] 次；跨域/改端口/改协议一律拒绝；
     * - 正文有界读取（[WEB_PAGE_PREFIX_BYTES]），只用来区分 HTML/JSON/空，不返回正文；
     * - 401/403 表示页面需要登录：`reachable=false`、`contentKind="auth"`，由调用方按鉴权处理。
     */
    suspend fun probeWebPage(): WebPageProbe = withContext(Dispatchers.IO) {
        val ctx = contextOrThrow()
        var target = ctx.baseUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()
        var redirected = false
        repeat(MAX_PAGE_REDIRECTS + 1) {
            val reply = execute(
                ctx = ctx,
                spec = HttpSpec(
                    method = "GET",
                    path = "/",
                    stage = "web_page",
                    // 永不携带令牌：网页根路径只用于确认页面在线
                    protected = false,
                    maxBytes = WEB_PAGE_PREFIX_BYTES,
                    truncate = true,
                    accept = ACCEPT_HTML,
                    allowRedirect = true,
                ),
                urlOverride = target,
            )
            if (reply.status in setOf(301, 302, 303, 307, 308)) {
                val location = reply.location
                val next = location?.let { runCatching { target.resolve(it) }.getOrNull() }
                    ?: throw ReaderWebException(
                        "REDIRECT_BLOCKED",
                        "阅读 Web 根路径返回了重定向（HTTP ${reply.status}）但没有可用的同源地址，已拒绝跟随",
                        "web_page", reply.status,
                        diagnostic = reply.diagnostic,
                    )
                if (!sameOrigin(ctx.baseUrl, next)) {
                    throw ReaderWebException(
                        "REDIRECT_BLOCKED",
                        "阅读 Web 根路径被重定向到另一个地址（HTTP ${reply.status}），已按安全策略拒绝跟随；请把地址直接改为最终的 Web 服务 origin",
                        "web_page", reply.status,
                        diagnostic = reply.diagnostic,
                    )
                }
                target = next
                redirected = true
                return@repeat
            }
            return@withContext when {
                reply.status in 200..299 -> WebPageProbe(
                    reachable = true,
                    statusCode = reply.status,
                    contentKind = contentKind(reply),
                    viaRedirect = redirected,
                    diagnostic = reply.diagnostic,
                )
                reply.status in setOf(401, 403) -> WebPageProbe(
                    reachable = false,
                    statusCode = reply.status,
                    contentKind = "auth",
                    viaRedirect = redirected,
                    diagnostic = reply.diagnostic,
                )
                else -> WebPageProbe(
                    reachable = false,
                    statusCode = reply.status,
                    contentKind = "http_error",
                    viaRedirect = redirected,
                    diagnostic = reply.diagnostic,
                )
            }
        }
        throw ReaderWebException(
            "REDIRECT_BLOCKED",
            "阅读 Web 根路径连续重定向超过 ${MAX_PAGE_REDIRECTS} 次，已停止跟随",
            "web_page",
        )
    }

    private fun sameOrigin(base: HttpUrl, other: HttpUrl): Boolean =
        base.scheme == other.scheme && base.host == other.host && base.port == other.port

    private fun contentKind(reply: HttpReply): String {
        val text = bodyHead(reply)
        if (text.isEmpty()) return "empty"
        return when {
            text.startsWith("{") || text.startsWith("[") -> "json"
            text.startsWith("<") || "html" in reply.contentType -> "html"
            "json" in reply.contentType -> "json"
            else -> "other"
        }
    }

    /**
     * 探测阅读 Web 服务并读取「访问令牌保护」开关。
     *
     * 先打新版公开接口（不带令牌）；该路由不存在/回 HTML 时，用固定旧版公开读路由核实：
     * - 旧路由返回 Legado ReturnData → `LEGACY_READER`（连通阅读，但没有检验接口）；
     * - 旧路由也未识别 → `NOT_READER`（官方接口不匹配，可能是阅读分支差异，不断言网页身份）；
     * - 新版接口正常 → 返回令牌开关（业务失败照实报，不降级成「不可达」）。
     *
     * @param knownSourceUrl 已知的 bookSourceUrl：旧版核实用体积更小的 `/getBookSource?url=`；
     *   null 时退化为有界读取 `/getBookSources` 前缀。
     */
    @JvmOverloads
    suspend fun probe(knownSourceUrl: String? = null): Probe = withContext(Dispatchers.IO) {
        val ctx = contextOrThrow()
        try {
            val parsed = executeRequest(
                ctx = ctx,
                spec = HttpSpec(
                    method = "GET",
                    path = PATH_TOKEN_REQUIRED,
                    stage = "token_probe",
                    protected = false,
                    maxBytes = MAX_BODY_BYTES,
                ),
            )
            val data = parsed.data
            require(data != null && data.isJsonPrimitive) {
                throw incompatible("访问令牌状态", "token_probe", parsed.diagnostic)
            }
            Probe(
                tokenRequired = runCatching { data.asBoolean }.getOrDefault(false),
                bearerAuth = parsed.bearerAuth,
                diagnostic = parsed.diagnostic,
            )
        } catch (error: ReaderWebException) {
            if (!error.routeMissing) throw error
            if (legacyReadLikely(ctx, knownSourceUrl)) {
                throw ReaderWebException(
                    "LEGACY_READER",
                    "网页在线，但该版本没有结构化检验接口（新接口不存在，旧版书源接口可用）。" +
                        "这不影响操作阅读 Web 页面；reader_web_verify 需要新版接口，当前可继续用阅读 Web 页面手动操作与检验",
                    stage = "legacy_probe",
                    diagnostic = error.diagnostic,
                )
            }
            throw ReaderWebException(
                "NOT_READER",
                "该地址有响应，但官方阅读 Web 接口不匹配（可能是阅读分支/版本不同）。" +
                    "这不代表网页不是阅读：请继续用阅读 Web 页面操作，不需要升级",
                stage = "legacy_probe",
                diagnostic = error.diagnostic,
            )
        }
    }

    /**
     * 读取已保存书源。urls 为 null 时（不传参数）返回阅读里的全部书源——
     * 本轮调用一律显式传 URL 列表，避免无谓拉全库；传空列表用于连接自检。
     */
    suspend fun fetchSources(urls: List<String>?): List<SourceEntry> = withContext(Dispatchers.IO) {
        val ctx = contextOrThrow()
        val query = if (urls == null) emptyMap() else mapOf("urls" to JsonArray().apply { urls.forEach { add(it) } }.toString())
        val parsed = request(
            ctx,
            HttpSpec(
                method = "GET",
                path = PATH_SOURCES_FOR_MANAGEMENT,
                stage = "sources",
                query = query,
                protected = true,
            ),
        )
        val data = parsed.data
        require(data != null && data.isJsonObject) {
            throw incompatible("书源列表", "sources", parsed.diagnostic(ReaderWebRequestDiagnostic.Phase.PARSE))
        }
        val obj = data.asJsonObject
        val sources = obj.arrayOrEmpty("sources")
        val states = obj.arrayOrEmpty("states")
        val byUrl = states.mapNotNull { it as? JsonObject }
            .associateBy { it.stringOrNull("bookSourceUrl").orEmpty() }
        sources.map { source ->
            val url = (source as? JsonObject)?.stringOrNull("bookSourceUrl").orEmpty()
            SourceEntry(
                source as? JsonObject ?: throw incompatible(
                    "书源列表",
                    "sources",
                    parsed.diagnostic(ReaderWebRequestDiagnostic.Phase.PARSE),
                ),
                byUrl[url],
            )
        }
    }

    /** 启动阅读端书源检验。sources 必须是阅读里已保存的完整书源对象（与阅读版本一致）。 */
    suspend fun startCheck(sources: List<JsonObject>, keyword: String?): StartResult = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty()) { "没有要检验的书源" }
        val ctx = contextOrThrow()
        val body = JsonObject().apply {
            add("sources", JsonArray().apply { sources.forEach { add(it) } })
            keyword?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("keyword", it) }
        }
        val parsed = request(
            ctx,
            HttpSpec(
                method = "POST",
                path = PATH_START_CHECK,
                stage = "start",
                jsonBody = body.toString(),
                protected = true,
            ),
        )
        val data = parsed.data
        require(data != null && data.isJsonObject) {
            throw incompatible("检验会话", "start", parsed.diagnostic(ReaderWebRequestDiagnostic.Phase.PARSE))
        }
        val obj = data.asJsonObject
        val sessionToken = obj.stringOrNull("sessionToken")?.takeIf { it.isNotBlank() }
            ?: throw ReaderWebException(
                "BUSINESS_ERROR", "阅读端未返回检验会话标识，可能版本不兼容", "start",
                diagnostic = parsed.diagnostic(ReaderWebRequestDiagnostic.Phase.PARSE),
            )
        val revisions = obj.get("sourceRevisions")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.entrySet().orEmpty()
            .associate { entry ->
                entry.key to runCatching { entry.value.asString }.getOrDefault("")
            }
        StartResult(sessionToken, revisions)
    }

    /** 读取阅读端检验状态快照（protected）。 */
    suspend fun checkStates(): CheckStates = withContext(Dispatchers.IO) {
        val ctx = contextOrThrow()
        val parsed = request(
            ctx,
            HttpSpec(method = "GET", path = PATH_CHECK_STATES, stage = "states", protected = true),
        )
        val data = parsed.data
        require(data != null && data.isJsonObject) {
            throw incompatible("检验状态", "states", parsed.diagnostic(ReaderWebRequestDiagnostic.Phase.PARSE))
        }
        val obj = data.asJsonObject
        CheckStates(
            sessionToken = obj.stringOrNull("sessionToken")?.takeIf { it.isNotBlank() },
            states = obj.arrayOrEmpty("states").mapNotNull { it as? JsonObject },
        )
    }

    /**
     * 按逐字符 bookSourceUrl 精确读取阅读里的书源（固定公开读路由）。
     *
     * 只有**明确**的「书源不存在」才返回 `present=false`：即 `isSuccess=false` 且错误文本是官方
     * 未找到措辞。其余形状（`data=null`、空对象、缺少/不符 `bookSourceUrl`、非对象）都无法证明
     * 「阅读里没有这个源」，一律抛 `INCOMPATIBLE`/`SOURCE_MISMATCH`，让同步工具零写入停下，
     * 绝不能让「读不懂」被当成「没有」而触发覆盖写。
     *
     * @param stage 只允许 [Stage.LOOKUP]（同步前预检）或 [Stage.READBACK]（写入后回读）；
     *   它进入诊断，供下一环节区分「预检失败」与「写入成功但回读失败」。
     */
    @JvmOverloads
    suspend fun fetchSource(url: String, stage: String = Stage.LOOKUP): SourceLookup = withContext(Dispatchers.IO) {
        require(stage in READ_STAGES) { "fetchSource 只支持 lookup/readback 阶段" }
        val ctx = contextOrThrow()
        val target = url.trim()
        require(target.isNotEmpty()) { "bookSourceUrl 不能为空" }
        require(target.length <= MAX_SOURCE_URL_CHARS) { "bookSourceUrl 过长" }
        val reply = execute(
            ctx = ctx,
            spec = HttpSpec(
                method = "GET",
                path = PATH_LEGACY_SOURCE,
                stage = stage,
                query = mapOf("url" to target),
                protected = false,
                maxBytes = MAX_BODY_BYTES,
            ),
        )
        val parsed = parseReturnData(reply, ctx, stage, allowSourceMissing = true)
        // root=null 只可能来自「isSuccess=false + 官方未找到措辞」
        parsed.root ?: return@withContext SourceLookup(
            present = false,
            source = null,
            bearerAuth = parsed.bearerAuth,
            diagnostic = parsed.diagnostic,
        )
        val data = parsed.data
        if (data == null || data.isJsonNull) {
            throw ReaderWebException(
                "INCOMPATIBLE",
                "阅读对该书源查询返回了空内容，无法确认书源是否存在，已停止同步（不会写入）",
                stage,
                reply.status,
                diagnostic = reply.diagnostic.copy(phase = ReaderWebRequestDiagnostic.Phase.PARSE),
            )
        }
        if (!data.isJsonObject) throw incompatible("书源详情", stage, reply.diagnostic)
        val source = data.asJsonObject
        val foundUrl = source.stringOrNull("bookSourceUrl")
        if (foundUrl.isNullOrBlank()) {
            // data={} 或缺少 bookSourceUrl：不能当作「找到了」，更不能当作「没有」
            throw ReaderWebException(
                "INCOMPATIBLE",
                "阅读返回的书源缺少 bookSourceUrl，无法确认是否为目标书源，已停止同步（不会写入）",
                stage,
                reply.status,
                diagnostic = reply.diagnostic.copy(phase = ReaderWebRequestDiagnostic.Phase.PARSE),
            )
        }
        if (foundUrl != target) {
            throw ReaderWebException(
                "SOURCE_MISMATCH",
                "阅读返回的书源地址与请求的书源不一致，已停止同步（阅读端实现不兼容）",
                stage,
                reply.status,
                diagnostic = reply.diagnostic.copy(phase = ReaderWebRequestDiagnostic.Phase.PARSE),
            )
        }
        SourceLookup(
            present = true,
            source = source,
            bearerAuth = parsed.bearerAuth,
            diagnostic = parsed.diagnostic,
        )
    }

    /**
     * 写入一份完整 BookSource（固定 `POST /saveBookSource`，正文只来自调用方传入的本地成品）。
     *
     * 该路由在老版/叉版里普遍存在，但**不是只读**：只有预检确认阅读里没有该源、或调用方显式允许
     * 覆盖时才可调用（判定在 [ReaderWebSourceSync]）。
     *
     * POST 绝不因传输异常重放：一次逻辑写入只发一次，失败按诊断如实报告。
     *
     * @return 写入被阅读接受后的脱敏诊断（`method=POST`、`path=/saveBookSource`、`stage=save`），
     *   供调用方在回读失败时区分「写入没发成功」与「写入被接受但回读读不到」。
     *   既有只关心成功/失败的调用方可以直接忽略返回值。
     */
    suspend fun saveSource(source: JsonObject): SaveResult = withContext(Dispatchers.IO) {
        val ctx = contextOrThrow()
        val body = source.toString()
        require(body.toByteArray().size <= MAX_BODY_BYTES) { "书源 JSON 超过 4MB，拒绝写入" }
        // 写入路由可能受 X-Legado-Token 保护；调用方只在同源预检已确认是阅读后才走到这里
        val parsed = request(
            ctx,
            HttpSpec(
                method = "POST",
                path = PATH_SAVE_SOURCE,
                stage = "save",
                jsonBody = body,
                protected = true,
            ),
        )
        SaveResult(parsed.diagnostic)
    }

    /**
     * 连接自检：能拿到令牌开关 + 书源列表（空查询）即视为可用。
     *
     * 两步都固定在同一个配置快照上：中途在界面上改地址，不会让第一步打旧主机、第二步打新主机。
     */
    suspend fun testConnection(): Probe {
        val client = pinConfig()
        val probe = client.probe()
        try {
            client.fetchSources(emptyList())
        } catch (error: ReaderWebException) {
            throw unsupportedCheckEndpoints(error, "书源管理")
        }
        return probe
    }

    /** 是否已保存访问令牌（只回答有无，不返回值本体）。 */
    fun hasStoredToken(): Boolean = configProvider()?.token?.isNotBlank() == true

    /**
     * 当前地址+令牌快照。一次工具调用里 HTTP 与旧版 WebSocket 通道共用它，
     * 中途在界面改配置只影响下一次调用。
     */
    fun configSnapshot(): ReaderWebConfigStore.Config? = pinnedConfig ?: configProvider()

    /**
     * 用固定旧版公开读路由做一次**有界只读**探测：只读 `/getBookSources` 开头 16 KB，
     * 确认返回是否是 Legado ReturnData。不回传任何书源内容。
     */
    suspend fun legacyReadAvailable(): Boolean = withContext(Dispatchers.IO) {
        legacyReadLikely(contextOrThrow(), null)
    }

    // ---- HTTP + ReturnData ----

    /** 成功响应 + 本次交换的脱敏诊断；诊断随 INCOMPATIBLE/业务错误一起透出。 */
    private class HttpReply(
        val status: Int,
        val contentType: String,
        val text: String,
        val truncated: Boolean,
        val diagnostic: ReaderWebRequestDiagnostic,
        val bearerChallenge: Boolean = false,
        val bearerUsed: Boolean = false,
        val location: String? = null,
    )

    private class ReturnData(
        val root: JsonObject?,
        val data: JsonElement?,
        val bearerAuth: Boolean,
        val diagnostic: ReaderWebRequestDiagnostic? = null,
    ) {
        fun diagnostic(phase: String): ReaderWebRequestDiagnostic? =
            diagnostic?.copy(phase = phase)
    }

    private fun request(ctx: Ctx, spec: HttpSpec): ReturnData =
        executeRequest(ctx, spec)

    private fun executeRequest(ctx: Ctx, spec: HttpSpec): ReturnData {
        val reply = execute(ctx, spec)
        return parseReturnData(reply, ctx, spec.stage)
    }

    /**
     * 解析 Legado `ReturnData`：
     * - `isSuccess=true` → `root` + `data`；
     * - `isSuccess=false` 且错误文本表示「没有这个源」→ `root=null`，交给调用方按「不存在」处理；
     * - 其它失败（令牌/业务错误/形状不符）照实抛异常，异常带 `parse` 相位诊断。
     *
     * @param allowSourceMissing 只有精确书源查询（`/getBookSource?url=`）才把「未找到源」当作「不存在」；
     *   其它路由遇到该错误文本仍然是业务错误，不能被静默吞掉。
     */
    private fun parseReturnData(
        reply: HttpReply,
        ctx: Ctx,
        stage: String,
        allowSourceMissing: Boolean = false,
    ): ReturnData {
        // 只有真的解析失败/业务失败才把相位改成 parse；成功的交换保持 none
        val parseDiagnostic = {
            reply.diagnostic.copy(phase = ReaderWebRequestDiagnostic.Phase.PARSE)
        }
        if (isHtml(reply)) throw incompatible("返回的是网页 HTML", stage, parseDiagnostic())
        val root = runCatching { JsonParser.parseString(bodyHead(reply)) }.getOrNull() as? JsonObject
            ?: throw incompatible("返回内容不是 Legado ReturnData JSON", stage, parseDiagnostic())
        val isSuccess = root.get("isSuccess")?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asBoolean }.getOrDefault(false)
        } ?: throw incompatible("缺少 isSuccess 字段", stage, parseDiagnostic())
        if (!isSuccess) {
            val message = redact(root.stringOrNull("errorMsg").orEmpty().ifBlank { "未知错误" }, ctx)
            if (allowSourceMissing && isSourceMissingMessage(message)) {
                return ReturnData(root = null, data = null, bearerAuth = reply.bearerUsed, diagnostic = reply.diagnostic)
            }
            val code = if (message.contains("令牌")) {
                if (ctx.token.isBlank()) "TOKEN_REQUIRED" else "TOKEN_INVALID"
            } else {
                "BUSINESS_ERROR"
            }
            throw ReaderWebException(
                code, "阅读端返回失败：$message", stage, reply.status,
                // 业务失败：HTTP 与 JSON 形状都正常，传输相位仍是 none（不是网络/解析错误）
                diagnostic = reply.diagnostic,
            )
        }
        return ReturnData(
            root = root,
            data = root.get("data"),
            bearerAuth = reply.bearerUsed,
            // 交换本身与形状解析都成功：相位保持 none
            diagnostic = reply.diagnostic,
        )
    }

    /** 官方「书源不存在」的几种文本：只用于把预检判成「没有」，不含「搜索无结果」这类正常调试日志。 */
    private fun isSourceMissingMessage(message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        return SOURCE_MISSING_MESSAGES.any { text.contains(it, ignoreCase = true) }
    }

    /**
     * 一次逻辑请求：先发不带凭据的请求；只有**同一 origin** 明确返回
     * `401 + WWW-Authenticate: Bearer` 时才改发一次 Bearer。
     *
     * 两条路径共用同一个 [Budget]，认证重发也计入 `attempts` 并标 `authRetry`。
     */
    private fun execute(ctx: Ctx, spec: HttpSpec, urlOverride: HttpUrl? = null): HttpReply {
        val budget = newBudget()
        val first = exchange(ctx, spec, bearer = false, budget = budget, priorAttempts = 0, urlOverride = urlOverride)
        if (first.status in setOf(401, 403) && first.bearerChallenge) {
            if (ctx.token.isBlank()) {
                throw ReaderWebException(
                    "TOKEN_REQUIRED",
                    "阅读 Web 服务对该接口要求 Bearer 访问令牌（HTTP ${first.status}）：请在设置里填写与阅读相同的令牌",
                    spec.stage, first.status, bearerChallenge = true,
                    diagnostic = first.diagnostic,
                )
            }
            // MAX 等分支可能对 Web 路由统一要求 Bearer：只在**同一 origin** 收到明确挑战后重发一次，
            // 公开探测绝不预先携带 Authorization，也不会被 302/别的地址骗走令牌。
            return exchange(
                ctx, spec, bearer = true, budget = budget,
                priorAttempts = first.diagnostic.attempts, urlOverride = urlOverride,
            )
        }
        return first
    }

    /**
     * 发一次请求；GET/HEAD 在可恢复的传输异常（RST/EOF/协议错误）上**最多**补一次新连接。
     *
     * POST 永不重放：写请求的语义不能被「可能已到达服务端」的 IO 异常重复执行。
     * 重试与首发共用 [budget]，因此总超时预算不增长。
     */
    private fun exchange(
        ctx: Ctx,
        spec: HttpSpec,
        bearer: Boolean,
        budget: Budget,
        priorAttempts: Int,
        urlOverride: HttpUrl?,
    ): HttpReply {
        var localAttempt = 0
        while (true) {
            localAttempt++
            try {
                return executeOnce(
                    ctx = ctx,
                    spec = spec,
                    bearer = bearer,
                    budget = budget,
                    attempts = priorAttempts + localAttempt,
                    urlOverride = urlOverride,
                )
            } catch (error: ReaderWebException) {
                val diagnostic = error.diagnostic
                val retryable = spec.method != "POST" &&
                    localAttempt == 1 &&
                    diagnostic?.exceptionCategory in ReaderWebRequestDiagnostic.RECOVERABLE_CATEGORIES &&
                    diagnostic?.phase in setOf(
                        ReaderWebRequestDiagnostic.Phase.CONNECT,
                        ReaderWebRequestDiagnostic.Phase.HEADERS,
                        ReaderWebRequestDiagnostic.Phase.BODY,
                    ) &&
                    budget.remainingMillis() > 0
                if (!retryable) throw error
                // GET/HEAD 幂等：换一条**新连接**再试一次（零 idle 池，重试必然重新建连）
            }
        }
    }

    private fun executeOnce(
        ctx: Ctx,
        spec: HttpSpec,
        bearer: Boolean,
        budget: Budget,
        attempts: Int,
        urlOverride: HttpUrl?,
    ): HttpReply {
        val url = urlOverride ?: urlOf(ctx, spec.path, spec.query)
        val builder = Request.Builder().url(url)
            .header("Accept", spec.accept)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            // 与书源抓取通道同一个浏览器 UA：前置反代/WAF 对非浏览器 UA 会直接断连
            .header("User-Agent", BROWSER_UA)
            // 一次性连接：响应结束即关闭，绝不复用可能已被对端关闭的陈旧连接
            .header("Connection", "close")
        // 不发送 Cookie、不发送书源 UA；只有受保护路由才带 Legado 规定的令牌头
        val token = ctx.token.trim()
        if (bearer) {
            builder.header("Authorization", "Bearer $token")
        } else if (spec.protected && token.isNotEmpty()) {
            builder.header(TOKEN_HEADER, token)
        }
        if (spec.jsonBody != null) builder.post(OneShotBody(spec.jsonBody.toByteArray(), JSON_MEDIA))
        val request = builder.build()

        val remaining = budget.remainingMillis()
        if (remaining <= 0) {
            throw ReaderWebException(
                "TIMEOUT", timeoutMessage(), spec.stage,
                diagnostic = diagnostic(spec, ReaderWebRequestDiagnostic.Phase.HEADERS, null, null, attempts, null, bearer),
            )
        }
        val call = httpClient.newCall(request)
        // 只收紧不放宽：总预算由 deadline 决定，重试不会把超时翻倍
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
        val response = try {
            call.execute()
        } catch (error: IOException) {
            throw transportFailure(error, spec, attempts, ReaderWebConnectionObserver.takeNewConnection(call), bearer)
        }
        val newConnection = ReaderWebConnectionObserver.takeNewConnection(call)

        response.use {
            val diagnosticNone = diagnostic(spec, ReaderWebRequestDiagnostic.Phase.NONE, response.code, null, attempts, newConnection, bearer)
            // HTTP 状态已到 = 传输本身完成了。相位只描述传输在哪一步失败，
            // 因此 4xx/5xx 这类「服务端明确回答」的失败相位是 none，而不是 headers。
            val diagnosticStatus = { status: Int ->
                diagnostic(spec, ReaderWebRequestDiagnostic.Phase.NONE, status, null, attempts, newConnection, bearer)
            }
            if (response.isRedirect && !spec.allowRedirect) {
                throw ReaderWebException(
                    "REDIRECT_BLOCKED",
                    "阅读 Web 服务返回了重定向，已按安全策略拒绝跟随；请检查地址是否指向阅读本身",
                    spec.stage, response.code,
                    diagnostic = diagnosticStatus(response.code),
                )
            }
            val contentType = response.body.contentType()?.toString().orEmpty().lowercase()
            if (spec.allowRedirect) {
                // 网页根路径：3xx 交回调用方做同源判定，错误页/鉴权页不当作「页面在线」
                if (response.isRedirect) {
                    return HttpReply(
                        response.code, contentType, "", truncated = false,
                        diagnostic = diagnosticStatus(response.code),
                        location = response.header("Location"),
                    )
                }
                if (!response.isSuccessful) {
                    return HttpReply(
                        response.code, contentType, "", truncated = false,
                        diagnostic = diagnosticStatus(response.code),
                    )
                }
                val body = try {
                    readBody(response, spec)
                } catch (tooLarge: ReaderWebException) {
                    throw tooLarge.withDiagnostic(
                        diagnostic(spec, ReaderWebRequestDiagnostic.Phase.BODY, response.code, null, attempts, newConnection, bearer),
                    )
                } catch (error: IOException) {
                    // 响应头已经到了（HTTP 状态已知），只是正文没读完：调用方要能区分「无响应」与「有状态但正文读不动」
                    throw bodyFailure(error, spec, response.code, attempts, newConnection, bearer)
                }
                return HttpReply(response.code, contentType, body.first, body.second, diagnosticNone)
            }
            if (!response.isSuccessful) {
                val challenge = response.header("WWW-Authenticate").orEmpty()
                val bearerChallenge = response.code == 401 && challenge.contains("Bearer", ignoreCase = true)
                if (bearerChallenge && !bearer) {
                    // 把「需要 Bearer 重试」交给 execute()：这里不能自行发第二次请求
                    return HttpReply(
                        response.code, contentType, "", truncated = false,
                        diagnostic = diagnosticStatus(response.code),
                        bearerChallenge = true,
                    )
                }
                // 401/403 只可能来自令牌保护的受保护路由：按有无令牌区分「需要令牌」与「令牌不对」
                if ((spec.protected || bearer) && response.code in setOf(401, 403)) {
                    val token = ctx.token.trim()
                    if (token.isEmpty()) {
                        throw ReaderWebException(
                            "TOKEN_REQUIRED",
                            "阅读 Web 服务返回 HTTP ${response.code}：该接口需要访问令牌，请在设置里填写与阅读相同的令牌",
                            spec.stage, response.code, bearerChallenge,
                            diagnostic = diagnosticStatus(response.code),
                        )
                    }
                    throw ReaderWebException(
                        "TOKEN_INVALID",
                        "阅读 Web 服务返回 HTTP ${response.code}：访问令牌无效或已变更，请重新填写与阅读相同的令牌",
                        spec.stage, response.code, bearerChallenge,
                        diagnostic = diagnosticStatus(response.code),
                    )
                }
                // 其余状态：路由缺失只认 404/405/410（读取阶段的 5xx 沿用旧语义）；
                // 写入/回读的 5xx 是服务端错误，**不能**一刀切成「路由不存在」
                val routeMissing = response.code in ROUTE_MISSING_CODES && !(
                    response.code >= 500 && spec.stage in setOf("save", Stage.READBACK)
                )
                val code = if (routeMissing) "ENDPOINT_MISSING" else "HTTP_ERROR"
                val detail = if (code == "ENDPOINT_MISSING") {
                    "阅读 Web 服务返回 HTTP ${response.code}；请确认地址指向阅读 Web 服务而不是其它网页"
                } else {
                    "阅读 Web 服务返回 HTTP ${response.code}（服务端错误）：这不代表该写入/回读路由不存在，请稍后重试并核对阅读端日志"
                }
                throw ReaderWebException(
                    code, detail, spec.stage, response.code, bearerChallenge,
                    diagnostic = diagnosticStatus(response.code),
                )
            }
            val declaredLength = response.body.contentLength()
            if (!spec.truncate && declaredLength > spec.maxBytes) {
                throw ReaderWebException(
                    "RESPONSE_TOO_LARGE",
                    "阅读 Web 服务响应过大（超过 ${spec.maxBytes / 1024 / 1024} MB），已拒绝读取",
                    spec.stage,
                    diagnostic = diagnostic(spec, ReaderWebRequestDiagnostic.Phase.BODY, response.code, null, attempts, newConnection, bearer),
                )
            }
            val body = try {
                readBody(response, spec)
            } catch (tooLarge: ReaderWebException) {
                throw tooLarge.withDiagnostic(
                    diagnostic(spec, ReaderWebRequestDiagnostic.Phase.BODY, response.code, null, attempts, newConnection, bearer),
                )
            } catch (error: IOException) {
                throw bodyFailure(error, spec, response.code, attempts, newConnection, bearer)
            }
            return HttpReply(response.code, contentType, body.first, body.second, diagnosticNone, bearerUsed = bearer)
        }
    }

    /** @return 正文与是否因上限被截断 */
    private fun readBody(response: Response, spec: HttpSpec): Pair<String, Boolean> {
        val source = response.body.source()
        source.request(spec.maxBytes + 1)
        val available = source.buffer.size
        val truncated = available > spec.maxBytes
        if (truncated && !spec.truncate) {
            throw ReaderWebException(
                "RESPONSE_TOO_LARGE",
                "阅读 Web 服务响应过大（超过 ${spec.maxBytes / 1024 / 1024} MB），已拒绝读取",
                spec.stage,
            )
        }
        val take = minOf(available, spec.maxBytes)
        return source.buffer.readUtf8(take) to truncated
    }

    private fun diagnostic(
        spec: HttpSpec,
        phase: String,
        httpStatus: Int?,
        exceptionCategory: String?,
        attempts: Int,
        newConnection: Boolean?,
        bearer: Boolean,
    ): ReaderWebRequestDiagnostic = ReaderWebRequestDiagnostic(
        method = spec.method,
        path = spec.path,
        stage = spec.stage,
        phase = phase,
        httpStatus = httpStatus,
        exceptionCategory = exceptionCategory,
        attempts = attempts,
        // newConnection == null 表示无法测量（注入的测试 client 没有监听器），不伪造 false
        reusedConnection = newConnection?.not(),
        authRetry = bearer,
    )

    /** 连接/等响应头阶段的传输失败：按异常类别给出可区分的原因，并带 connect/headers 相位。 */
    private fun transportFailure(
        error: IOException,
        spec: HttpSpec,
        attempts: Int,
        newConnection: Boolean?,
        bearer: Boolean,
    ): ReaderWebException {
        val category = ReaderWebRequestDiagnostic.categoryOf(error)
        val phase = when (category) {
            ReaderWebRequestDiagnostic.Category.DNS,
            ReaderWebRequestDiagnostic.Category.CONNECT,
            ReaderWebRequestDiagnostic.Category.TLS,
            -> ReaderWebRequestDiagnostic.Phase.CONNECT
            else -> ReaderWebRequestDiagnostic.Phase.HEADERS
        }
        val code = if (category == ReaderWebRequestDiagnostic.Category.TIMEOUT) "TIMEOUT" else "UNREACHABLE"
        return ReaderWebException(
            code = code,
            message = transportMessage(category),
            stage = spec.stage,
            statusCode = null,
            diagnostic = diagnostic(spec, phase, null, category, attempts, newConnection, bearer),
        )
    }

    /** 已收到响应头、读正文失败：状态码照实带上，相位是 body。 */
    private fun bodyFailure(
        error: IOException,
        spec: HttpSpec,
        status: Int,
        attempts: Int,
        newConnection: Boolean?,
        bearer: Boolean,
    ): ReaderWebException {
        val category = ReaderWebRequestDiagnostic.categoryOf(error)
        val code = if (category == ReaderWebRequestDiagnostic.Category.TIMEOUT) "TIMEOUT" else "UNREACHABLE"
        val message = when (category) {
            ReaderWebRequestDiagnostic.Category.TIMEOUT ->
                "读取阅读 Web 服务响应超时：请确认阅读未卡在其它检验、网络稳定"
            ReaderWebRequestDiagnostic.Category.EOF ->
                "已收到 HTTP $status，但正文在读完前被对端关闭（EOF）：服务端可能异常关闭或前置代理超时"
            ReaderWebRequestDiagnostic.Category.RESET ->
                "已收到 HTTP $status，但正文传输被重置（连接被对端关闭）：服务端可能异常关闭或前置代理断连"
            ReaderWebRequestDiagnostic.Category.PROTOCOL ->
                "已收到 HTTP $status，但响应体不符合 HTTP 协议（chunked/长度不符）"
            else -> "读取阅读 Web 服务响应失败（$category），请确认服务端与网络稳定"
        }
        return ReaderWebException(
            code = code,
            message = message,
            stage = spec.stage,
            statusCode = status,
            diagnostic = diagnostic(spec, ReaderWebRequestDiagnostic.Phase.BODY, status, category, attempts, newConnection, bearer),
        )
    }

    private fun transportMessage(category: String): String = when (category) {
        ReaderWebRequestDiagnostic.Category.DNS ->
            "无法解析阅读 Web 服务地址：请确认地址填写正确、与阅读在同一网络"
        ReaderWebRequestDiagnostic.Category.CONNECT ->
            "无法连接阅读 Web 服务（连接被拒绝或网络不可达）：请确认阅读已开启 Web 服务且地址端口正确"
        ReaderWebRequestDiagnostic.Category.RESET ->
            "连接阅读 Web 服务时被重置（对端主动断开）：请确认阅读 Web 服务仍在运行、前置代理没有掐断长连接"
        ReaderWebRequestDiagnostic.Category.EOF ->
            "阅读 Web 服务在返回响应前断开了连接（EOF）：可能是服务端异常关闭或前置代理超时"
        ReaderWebRequestDiagnostic.Category.PROTOCOL ->
            "阅读 Web 服务返回了无法解析的 HTTP 响应（协议错误）：请确认地址指向阅读 Web 服务"
        ReaderWebRequestDiagnostic.Category.TLS ->
            "与阅读 Web 服务的 TLS 握手失败：请确认地址、协议与阅读端证书设置一致"
        ReaderWebRequestDiagnostic.Category.TIMEOUT -> timeoutMessage()
        else -> "连接阅读 Web 服务失败（网络错误）：请确认阅读已开启 Web 服务且地址端口正确"
    }

    /**
     * 用固定旧版公开读路由核实「这个地址是不是阅读」。
     * 已知书源 URL 时用小体积的 `/getBookSource?url=`；否则只读 `/getBookSources` 的开头若干 KB。
     * 只认 Legado ReturnData 的形状；任何 HTTP 错误、HTML、JSON 解析失败都返回 false。
     */
    private fun legacyReadLikely(ctx: Ctx, knownSourceUrl: String?): Boolean {
        val url = knownSourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        val path = if (url == null) PATH_LEGACY_SOURCES else PATH_LEGACY_SOURCE
        val query = if (url == null) emptyMap() else mapOf("url" to url)
        return try {
            val reply = execute(
                ctx,
                HttpSpec(
                    method = "GET",
                    path = path,
                    stage = "legacy_probe",
                    query = query,
                    // 旧版公开读路由不是令牌保护路由：绝不把令牌发给可能不是阅读的地址
                    protected = false,
                    maxBytes = LEGACY_PREFIX_BYTES,
                    truncate = true,
                ),
            )
            isReturnDataShape(reply)
        } catch (error: ReaderWebException) {
            false
        }
    }

    private fun isReturnDataShape(reply: HttpReply): Boolean {
        if (isHtml(reply)) return false
        val text = bodyHead(reply)
        if (!text.startsWith("{")) return false
        if (!reply.truncated) {
            val root = runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonObject ?: return false
            return root.get("isSuccess")?.isJsonPrimitive == true && (root.has("data") || root.has("errorMsg"))
        }
        // 截断时只核对开头是否具备 ReturnData 的关键字段，不把整包读进内存
        return text.contains("\"isSuccess\"") && (text.contains("\"data\"") || text.contains("\"errorMsg\""))
    }

    /**
     * 按正文判定是否是网页，`Content-Type` 只在正文无法判定时参考。
     *
     * NanoHTTPD 的 `newFixedLengthResponse(String)` 默认 `text/html`：官方旧版与大量分支版
     * 的 ReturnData JSON 都带着这个头返回。若以头为准，会把真实的阅读接口误判成「返回网页」，
     * 进而让探测报 `NOT_READER`、同步报 `INCOMPATIBLE`，模型只好退回本机沙箱。
     */
    private fun isHtml(reply: HttpReply): Boolean {
        val text = bodyHead(reply)
        if (text.startsWith("{") || text.startsWith("[")) return false
        return text.startsWith("<") || "html" in reply.contentType
    }

    private fun bodyHead(reply: HttpReply): String = reply.text.removePrefix("\uFEFF").trimStart()

    /**
     * 新版探测已确认是阅读、但书源管理/检验路由缺失（HTML 或 404/500）：
     * 说明网页在线，只是没有这些接口——不能再说「不像阅读」，也不要求升级。
     */
    private fun unsupportedCheckEndpoints(error: ReaderWebException, what: String): ReaderWebException =
        if (error.routeMissing) {
            ReaderWebException(
                "CHECK_UNSUPPORTED",
                "阅读 Web 页面在线（令牌开关可读），但该版本没有提供$what/检验接口（返回网页或 404/500）：" +
                    "这不影响操作阅读 Web 页面；可改在阅读的 Web 页面手动操作，或继续用旧版调试通道",
                error.stage,
                error.statusCode,
                error.bearerChallenge,
                error.diagnostic,
            )
        } else {
            error
        }

    private fun incompatible(
        what: String,
        stage: String,
        diagnostic: ReaderWebRequestDiagnostic? = null,
    ) = ReaderWebException(
        "INCOMPATIBLE",
        "该地址没有返回阅读 Web 服务的 $what（可能版本/分支不同）；这不代表网页不能操作，请以阅读 Web 页面的实际操作结果为准",
        stage,
        null,
        diagnostic = diagnostic?.copy(phase = ReaderWebRequestDiagnostic.Phase.PARSE),
    )

    private fun timeoutMessage(): String =
        "连接阅读 Web 服务超时（${CALL_TIMEOUT_SEC} 秒）：请确认阅读已开启 Web 服务、手机与阅读在同一网络"

    /** 错误文本脱敏：去掉令牌与任何 http(s) 地址，避免私网地址随错误回到模型。 */
    private fun redact(text: String, ctx: Ctx): String {
        var out = text
        if (ctx.token.isNotBlank()) out = out.replace(ctx.token, "[令牌已隐藏]")
        out = out.replace(ctx.origin, "[地址已隐藏]")
        return out.replace(Regex("(?i)https?://\\S+"), "[地址已隐藏]").take(300)
    }

    private fun JsonObject.arrayOrEmpty(key: String): List<JsonElement> =
        (get(key) as? JsonArray)?.toList().orEmpty()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
}

/**
 * 只能写一次的请求体：`reader_web` 的 POST 只允许发一次，第二次 `writeTo` 立即抛错。
 *
 * OkHttp 自身在 `retryOnConnectionFailure(false)` 且本类从不重放 POST 的前提下不会二次写，
 * 这个包装是**结构性保障**：即使以后有人打开传输层重试或复用同一个 body，也不会静默把
 * 「书源写入」这类非幂等请求重复发到阅读端。
 */
private class OneShotBody(
    private val bytes: ByteArray,
    private val contentType: MediaType,
) : RequestBody() {
    private var written = false

    override fun contentType(): MediaType = contentType

    override fun contentLength(): Long = bytes.size.toLong()

    /** 让 OkHttp 也知道这是 one-shot（即使有人打开传输层重试也不会重发）。 */
    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: okio.BufferedSink) {
        check(!written) { "阅读 Web 写入请求体只能发送一次（POST 不重放）" }
        written = true
        sink.write(bytes)
    }
}

/**
 * 记录「本次 call 是否新建了 TCP 连接」，供诊断的 `reusedConnection` 使用。
 * 生产 client 挂了 [factory]：`connectStart` 触发过 = 新建过连接；
 * 只收到 `connectionAcquired` 而没有 `connectStart` = 复用了池里的连接（生产零 idle 池下不应出现）。
 * 注入的测试 client 没有这个监听器 → 返回 null（无法测量），不伪造 false。
 */
private object ReaderWebConnectionObserver {
    private val newConnection = ConcurrentHashMap<okhttp3.Call, Boolean>()

    val factory = EventListener.Factory { call ->
        object : EventListener() {
            override fun connectStart(call: okhttp3.Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                newConnection[call] = true
            }

            override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) {
                newConnection.putIfAbsent(call, false)
            }
        }
    }

    /** true = 该 call 建了新连接；false = 复用了连接；null = 没有观测到连接获取（未测量）。 */
    fun takeNewConnection(call: okhttp3.Call): Boolean? = newConnection.remove(call)
}
