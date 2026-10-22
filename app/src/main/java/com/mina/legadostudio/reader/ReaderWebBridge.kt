package com.mina.legadostudio.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * `reader_web_status`：只读能力探测（不回显令牌或任何书源内容）。
 *
 * **判定主轴是「用户选的阅读 Web 页面能否操作」**，不是「官方 ReturnData 接口是否匹配」：
 * - `webPageReachable`：固定 `GET` 已保存 origin 的根路径（不带令牌、有界读取、不跟随越域重定向）。
 *   这是「网页在线」的唯一依据；HTML 200 **不等于**结构化检验通过。
 * - `apiProfile`：官方接口能力画像（`structured` / `legacy_read` / `mismatch` / `unknown`），
 *   是可选的增强能力，**不影响**网页能否操作。
 * - 旧版/分支接口不匹配时 status 为 `web_page_available`，`allowedNextActions` 优先
 *   `reader_web_page`，不断言「不是阅读」，也不要求升级。
 *
 * 其它约束：
 * - `reachable`：网络/HTTP 层有响应（401/403 也算，不判离线）；
 * - 只有 root 网络层不可达且接口探测也没拿到响应时，才如实报 `unreachable`；
 * - root 有 HTTP 响应但不是可用页面（404/5xx/被拒的越域重定向）与 root 网络层失败
 *   （超时/连不上）是两件事：前者由 [`Status.webPageStatusCode`] 说明，后者由
 *   [`Status.webPageProbeErrorCode`] 说明，不合并成一句「网页不可用」；
 * - 接口确有正面证据、但 root 没有可用页面时，status 报 `api_only_available`：
 *   只声明「官方接口可达」，不声明网页入口可用，也不声明书源/结构化检验成功；
 *   若 root 是网络层失败（而非 HTTP 状态错误）而接口只有响应，则报 `web_page_unreachable`；
 *   若 root 已回状态码但正文没读完、页面是否可用未确认，则报 `web_page_probe_incomplete`；
 *   两者都不笼统称为「网页不可用」；
 * - `authRequired`/`authReady`：401 Bearer 仍保留，但网页登录由用户在页面里完成，不阻塞页面操作；
 * - `originEcho`：回环回显实际端口，私网主机掩码但保留实际端口，令牌永不回显；
 * - `configRevision`：与配置 store 的 revision 同步，让模型知道当前保存的是哪一次配置；
 * - `webUiSessionSupported`：纯功能标志，表示本构建是否装了网页会话工具（`reader_web_page`），
 *   不含端口、日期或任何个人信息，客户端据此判断能否走网页会话。
 */
class ReaderWebBridge(private val client: ReaderWebClient) {

    /** 单项能力：`supported` / `unsupported` / `unauthorized` / `unknown`。 */
    data class Capability(val state: String, val detail: String) {
        companion object {
            const val SUPPORTED = "supported"
            const val UNSUPPORTED = "unsupported"

            /** 路由存在，但当前凭据无法通过鉴权：不能称为可用，也不能称为不存在。 */
            const val UNAUTHORIZED = "unauthorized"
            const val UNKNOWN = "unknown"
        }
    }

    /** 官方接口画像；不影响网页是否可用。 */
    object ApiProfile {
        /** 新版结构化接口（令牌开关/书源管理/检验状态）可用。 */
        const val STRUCTURED = "structured"

        /** 只有旧版公开读书源接口。 */
        const val LEGACY_READ = "legacy_read"

        /** 网页在线，但没识别到任何官方接口（分支/版本不同）。 */
        const val MISMATCH = "mismatch"

        /** 探测结果不足以分类（网关故障、鉴权未过、离线）。 */
        const val UNKNOWN = "unknown"

        const val NOT_PROBED = "not_probed"
    }

    /**
     * @property reachable 保存的 origin 是否可连（HTTP 层有响应，含 401/403）
     * @property webPageReachable 保存 origin 的根路径是否是可用页面入口（同源 3xx 跳转算入口）
     * @property readerDetected 是否拿到官方接口的正面证据（401 挑战不算）
     * @property apiProfile 官方接口画像，见 [ApiProfile]
     * @property authRequired 是否检测到鉴权要求（令牌开关、401/403、Bearer 挑战）
     * @property authReady 是否已没有待解决的鉴权阻塞
     * @property bearerAuth 是否要求 Bearer（收到 401 挑战后才发送令牌）
     * @property webPageStatusCode 根路径收到的 HTTP 状态；连响应头都没有拿到时为 null
     * @property webPageProbeErrorCode 根路径探测没有正常完成的失败码
     *   （`TIMEOUT`/`UNREACHABLE`/`REDIRECT_BLOCKED`）；成功拿到可用页面时为 null。
     *   若状态码已到、只是正文没读完，两者会同时出现（见 [webPageProbeFailureKind]）。
     *   不含 host、路径或页面内容。
     * @property webPageProbeDiagnostic 根路径请求的脱敏诊断（方法/固定路径/阶段/失败相位/
     *   异常类别/尝试次数/连接复用）。成功时也有，用来解释「网页这次为什么慢/重置」；
     *   只描述根路径这一条 GET，**不**证明官方接口或书源检验通过。
     */
    data class Status(
        val configured: Boolean,
        val reachable: Boolean,
        val readerDetected: Boolean,
        val tokenSaved: Boolean,
        val tokenRequired: Boolean?,
        val bearerAuth: Boolean,
        val capabilities: Map<String, Capability>,
        val allowedNextActions: List<String>,
        val message: String,
        val authRequired: Boolean = false,
        val authReady: Boolean = false,
        val webPageReachable: Boolean = false,
        val webPageStatusCode: Int? = null,
        val webPageContentKind: String? = null,
        val webPageProbeErrorCode: String? = null,
        val webPageProbeDiagnostic: ReaderWebRequestDiagnostic? = null,
        val apiProfile: String = ApiProfile.NOT_PROBED,
        val apiProfileDetail: String = "",
        val originEcho: String? = null,
        val configRevision: Long = 0L,
        val webUiSessionSupported: Boolean = WEB_UI_SESSION_SUPPORTED,
    ) {
        /**
         * 根路径失败的性质：
         * `none` / `http_status` / `auth_wall` / `body_unreadable`（拿到状态但正文读不动）/
         * `network`（没有任何 HTTP 响应）/ `redirect_refused` / `not_probed`。
         */
        val webPageProbeFailureKind: String
            get() = pageFailureKind(
                configured = configured,
                reachable = webPageReachable,
                statusCode = webPageStatusCode,
                contentKind = webPageContentKind,
                errorCode = webPageProbeErrorCode,
            )

        fun toPayload(): Map<String, Any?> = linkedMapOf(
            "status" to when {
                !configured -> "not_configured"
                !reachable && !webPageReachable -> "unreachable"
                // 有响应但过不了鉴权：既不是离线，也不能断言不是阅读或页面不可用
                authRequired && !authReady -> "auth_required"
                webPageReachable && readerDetected -> "ok"
                webPageReachable -> "web_page_available"
                // 接口有正面证据、根路径却没有可用页面：只声明接口可达，不声明网页/书源可用
                readerDetected -> "api_only_available"
                // 根路径网络层失败（没有拿到 HTTP 响应），但服务在别的路径上有响应：
                // 这与「根路径返回 HTTP 错误」是两件事，不能笼统报网页不可用
                webPageProbeFailureKind == "network" -> "web_page_unreachable"
                // 状态码已到、正文没读完：页面是否可用未确认，同样不能报网页不可用
                webPageProbeFailureKind == "body_unreadable" -> "web_page_probe_incomplete"
                reachable -> "web_page_unavailable"
                else -> "unreachable"
            },
            "configured" to configured,
            "reachable" to reachable,
            "webPageReachable" to webPageReachable,
            "webPageStatusCode" to webPageStatusCode,
            "webPageProbeErrorCode" to webPageProbeErrorCode,
            "webPageProbeFailureKind" to webPageProbeFailureKind,
            "webPageContentKind" to webPageContentKind,
            // 根路径这一条 GET 的脱敏诊断：只含方法/固定路径/阶段/相位/异常类别/次数/连接复用，
            // 不含 host、port、查询串或令牌；它只解释「这一次根路径请求发生了什么」
            "webPageProbeDiagnostic" to webPageProbeDiagnostic?.toPayload(),
            "webUiSessionSupported" to webUiSessionSupported,
            "readerDetected" to readerDetected,
            "apiProfile" to apiProfile,
            "apiProfileDetail" to apiProfileDetail,
            "authRequired" to authRequired,
            "authReady" to authReady,
            "tokenSaved" to tokenSaved,
            "tokenRequired" to tokenRequired,
            "bearerAuth" to bearerAuth,
            "originEcho" to originEcho,
            "configRevision" to configRevision,
            "capabilities" to capabilities.mapValues { (_, value) -> value.state },
            "capabilityDetail" to capabilities.mapValues { (_, value) -> value.detail },
            "allowedNextActions" to allowedNextActions,
            "message" to message,
            "evidenceScope" to "reader_web_probe",
            "evidenceNote" to "根路径 GET 只证明网页入口在线，不等于结构化接口检验通过；根路径 200 的任意第三方 HTML 模板不代表阅读身份，也不据此猜模板；api_only_available 只说明官方接口可达，网页入口/书源未证实；webPageProbeFailureKind 区分 http_status（有 HTTP 状态）与 network（无响应）；webPageProbeDiagnostic 只描述根路径这一条 GET 的方法/固定路径/阶段/失败相位/异常类别/尝试次数/连接复用，不含 host/端口/查询串/令牌，也不能用来证明其它接口或书源检验通过；官方接口画像只作可选能力提示，不决定网页能否操作；unknown 表示该能力无法在不产生副作用的情况下判定，不代表不支持",
        )
    }

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val config = client.configSnapshot()
            ?: return@withContext Status(
                configured = false,
                reachable = false,
                readerDetected = false,
                tokenSaved = false,
                tokenRequired = null,
                bearerAuth = false,
                capabilities = unknownAll(),
                allowedNextActions = emptyList(),
                message = "尚未配置阅读 Web 服务地址：请在 MCP 页「设置」→「阅读 Web 服务」填写阅读的 Web 服务地址并保存",
                apiProfile = ApiProfile.NOT_PROBED,
                apiProfileDetail = "未配置",
                originEcho = null,
                configRevision = 0L,
            )
        val pinned = client.pinConfig()
        val tokenSaved = pinned.hasStoredToken()
        val originEcho = originEcho(config.baseUrl)

        // 1) 网页入口：只 GET 已保存 origin 根路径，不带令牌、不跟随越域重定向。
        var webPageReachable = false
        var webPageStatusCode: Int? = null
        var webPageContentKind: String? = null
        // 根路径没有拿到 HTTP 响应（或重定向被拒）时的失败码：与「HTTP 状态错误」分开报告
        var webPageProbeErrorCode: String? = null
        // 根路径这一条 GET 的脱敏诊断（成功/失败都有），只解释该请求本身
        var webPageProbeDiagnostic: ReaderWebRequestDiagnostic? = null
        // 根路径拿到过 HTTP 响应（含 404/5xx/3xx 被拒）：说明服务在线，只是页面入口不可用
        var pageResponded = false
        var pageAuthWall = false
        try {
            val page = pinned.probeWebPage()
            pageResponded = true
            webPageStatusCode = page.statusCode
            webPageContentKind = page.contentKind
            webPageReachable = page.reachable
            webPageProbeDiagnostic = page.diagnostic
            pageAuthWall = page.contentKind == "auth"
        } catch (error: ReaderWebClient.ReaderWebException) {
            webPageStatusCode = error.statusCode
            webPageProbeErrorCode = error.code
            webPageProbeDiagnostic = error.diagnostic
            // 越域/异常重定向：页面入口不算可达，但服务确实响应了
            pageResponded = error.code == "REDIRECT_BLOCKED" || error.statusCode != null
        }

        // 2) 新版公开接口：只读、不带令牌。它同时给出「是否令牌保护」与「是否 Bearer 挑战」。
        var apiResponded = false
        var tokenRequired: Boolean? = null
        var bearerAuth = false
        var tokenProbeOk = false
        var tokenProbeDetail = ""
        var authRequired = false
        var authReady = true
        var authDetail = ""
        // 新版接口被明确判定「不存在」时，结构检验可以直接判不支持（不必再探测管理与状态）
        var newApiMissing = false
        try {
            val probe = pinned.probe()
            apiResponded = true
            tokenProbeOk = true
            tokenRequired = probe.tokenRequired
            bearerAuth = probe.bearerAuth
            tokenProbeDetail = if (probe.bearerAuth) "已按同一 origin 的 Bearer 挑战完成鉴权" else "公开接口可读"
            authRequired = probe.tokenRequired
            if (probe.tokenRequired && !tokenSaved) {
                // 公开接口能读，但受保护路由一定过不了：不要让模型先去写入/检验
                authReady = false
                authDetail = "阅读已开启访问令牌保护，但本 App 未保存令牌"
            }
        } catch (error: ReaderWebClient.ReaderWebException) {
            bearerAuth = error.bearerChallenge
            when (error.code) {
                "UNREACHABLE", "TIMEOUT" -> Unit
                "TOKEN_REQUIRED", "TOKEN_INVALID" -> {
                    // 401/403 是「服务已响应」：不能判离线，也不能据此断言不是阅读
                    apiResponded = true
                    tokenRequired = true
                    authRequired = true
                    authReady = false
                    authDetail = if (tokenSaved) "令牌无效或已变更" else "需要访问令牌但尚未保存"
                    tokenProbeDetail = authDetail
                }
                "LEGACY_READER" -> {
                    apiResponded = true
                    newApiMissing = true
                    tokenProbeDetail = "新接口不存在（旧版阅读）"
                }
                "NOT_READER" -> {
                    apiResponded = true
                    tokenProbeDetail = "未识别到官方接口（不影响网页操作）"
                }
                else -> {
                    // 未声明 Bearer 的 401/403 仍然是鉴权响应，不能当成「不是阅读」
                    if (error.statusCode in setOf(401, 403)) {
                        apiResponded = true
                        tokenRequired = true
                        authRequired = true
                        authReady = false
                        authDetail = if (tokenSaved) "令牌无效或已变更" else "需要访问令牌但尚未保存"
                        tokenProbeDetail = authDetail
                    } else {
                        apiResponded = true
                        tokenProbeDetail = "探测失败：${error.code}"
                    }
                }
            }
        }

        // 3) 旧版公开读路由（有界前缀）：只回答「有没有旧接口」，不读全库
        var legacyReadOk = false
        if (apiResponded) {
            legacyReadOk = runCatching { pinned.legacyReadAvailable() }.getOrDefault(false)
        }

        // 4) 新版书源管理 + 检验：401/403 表示路由存在但当前凭据过不了鉴权
        var managementState: Capability = Capability(Capability.UNKNOWN, "未探测")
        var checkState: Capability = Capability(Capability.UNKNOWN, "未探测")
        if (newApiMissing) {
            checkState = Capability(Capability.UNSUPPORTED, "该版本没有新版结构化检验接口")
        }
        if (apiResponded && tokenProbeOk) {
            try {
                pinned.fetchSources(emptyList())
                managementState = Capability(Capability.SUPPORTED, "书源管理可读")
            } catch (error: ReaderWebClient.ReaderWebException) {
                managementState = classify(error, "书源管理")
            }
            when (managementState.state) {
                Capability.SUPPORTED -> {
                    // startBookSourceCheck 需要真实书源且会启动检验：有副作用，不能空跑。
                    checkState = try {
                        pinned.checkStates()
                        Capability(Capability.SUPPORTED, "结构化检验状态接口可读")
                    } catch (error: ReaderWebClient.ReaderWebException) {
                        classify(error, "检验状态")
                    }
                }
                Capability.UNSUPPORTED -> checkState = Capability(Capability.UNSUPPORTED, "书源管理缺失，结构化检验随之为不可用")
                else -> checkState = managementState
            }
            if (managementState.state == Capability.UNAUTHORIZED || checkState.state == Capability.UNAUTHORIZED) {
                authRequired = true
                authReady = false
                if (authDetail.isBlank()) {
                    authDetail = if (tokenSaved) "已保存的令牌被阅读拒绝（无效或已变更）" else "阅读要求访问令牌，但本 App 尚未保存"
                }
            }
        }

        val readerDetected = tokenProbeOk || legacyReadOk ||
            managementState.state == Capability.SUPPORTED || checkState.state == Capability.SUPPORTED
        val authBlocked = authRequired && !authReady
        // 网络层：根路径或任一接口拿到过 HTTP 响应，就算「服务在线」；两者都连不上才是 unreachable
        val online = pageResponded || apiResponded
        val pageFailureKind = pageFailureKind(
            configured = true,
            reachable = webPageReachable,
            statusCode = webPageStatusCode,
            contentKind = webPageContentKind,
            errorCode = webPageProbeErrorCode,
        )

        val apiProfile = when {
            !online -> ApiProfile.NOT_PROBED
            tokenProbeOk || managementState.state == Capability.SUPPORTED || checkState.state == Capability.SUPPORTED ->
                ApiProfile.STRUCTURED
            legacyReadOk -> ApiProfile.LEGACY_READ
            authBlocked -> ApiProfile.UNKNOWN
            // 拿到过响应、但没有正面接口证据：网页在线而官方接口不匹配
            else -> ApiProfile.MISMATCH
        }
        val apiProfileDetail = when (apiProfile) {
            ApiProfile.STRUCTURED -> if (managementState.state == Capability.SUPPORTED) {
                "结构化接口可读"
            } else {
                "新版接口可读；书源管理/检验另有状态"
            }
            ApiProfile.LEGACY_READ -> "只有旧版公开读书源接口，没有新版结构化检验"
            ApiProfile.MISMATCH -> "未识别到官方阅读 Web 接口（可能与阅读分支/版本不同）"
            ApiProfile.UNKNOWN -> if (authBlocked) "鉴权未通过，无法判定接口画像" else tokenProbeDetail.ifBlank { "接口探测未完成" }
            else -> "服务不可达，未探测接口"
        }

        val capabilities = linkedMapOf(
            "webPage" to when {
                webPageReachable -> Capability(Capability.SUPPORTED, "根路径可打开，可直接操作阅读 Web 页面")
                pageAuthWall -> Capability(
                    Capability.UNAUTHORIZED,
                    "根路径要求登录：可在 Web 页面自行登录后继续操作",
                )
                webPageProbeErrorCode == "REDIRECT_BLOCKED" -> Capability(
                    Capability.UNSUPPORTED,
                    "根路径被重定向到其它地址，已按安全策略拒绝跟随；这不说明接口是否可达，也不据此猜页面模板",
                )
                webPageStatusCode != null && webPageProbeErrorCode != null -> Capability(
                    Capability.UNKNOWN,
                    "根路径返回 HTTP ${webPageStatusCode}，但正文没有读完（${webPageProbeErrorCode}）：页面是否可用未确认",
                )
                webPageStatusCode != null -> Capability(
                    Capability.UNSUPPORTED,
                    "根路径返回 HTTP ${webPageStatusCode}，没有可用页面（接口是否可达另见 readerDetected/apiProfile）",
                )
                webPageProbeErrorCode != null -> Capability(
                    Capability.UNKNOWN,
                    "根路径网络层失败（${webPageProbeErrorCode}），无法判定页面是否可用；不代表接口不可达",
                )
                else -> Capability(Capability.UNKNOWN, "服务不可达，未探测")
            },
            "read" to when {
                legacyReadOk -> Capability(Capability.SUPPORTED, "旧版公开读接口可用")
                // 鉴权未过时不能断言旧版公开读「不是阅读数据」
                authBlocked && !authReady -> Capability(
                    Capability.UNKNOWN,
                    "鉴权未通过，未确认旧版公开读接口；可先在 Web 页面登录或补令牌",
                )
                online && tokenProbeOk -> Capability(Capability.UNSUPPORTED, "旧版公开读接口返回的不是阅读数据（不影响新版接口与网页操作）")
                online -> Capability(Capability.UNKNOWN, "旧版公开读接口未确认")
                else -> Capability(Capability.UNKNOWN, "服务不可达，未探测")
            },
            "check" to checkState,
            "write" to Capability(
                Capability.UNKNOWN,
                "写入路由 /saveBookSource 只能在真正提交书源时确认；reader_web_sync_source 会实际尝试并如实回报结果",
            ),
            "legacyDebug" to Capability(
                Capability.UNKNOWN,
                "旧版 WebSocket 调试通道（Web 端口 +1）只有 reader_web_debug 实际执行后才能确认",
            ),
            "search" to Capability(
                Capability.UNKNOWN,
                "根路径 200 和书源读接口均不证明 /searchBook 可用；需用 reader_web_search 实际握手与收取数组帧",
            ),
        )

        val allowed = when {
            !online -> listOf("reader_web_status")
            authBlocked -> if (webPageReachable || pageAuthWall) listOf("reader_web_page", "reader_web_status")
                else listOf("reader_web_status")
            webPageReachable && !readerDetected -> listOf("reader_web_page", "reader_web_status")
            webPageReachable -> listOf("reader_web_page", "reader_web_sync_source", "reader_web_verify", "reader_web_debug", "reader_web_search")
            readerDetected -> listOf("reader_web_sync_source", "reader_web_verify", "reader_web_debug", "reader_web_search", "reader_web_status")
            else -> listOf("reader_web_status")
        }
        val message = when {
            !online -> "已保存的阅读 Web 服务地址没有响应：请确认阅读已开启 Web 服务、地址端口正确（同机可改用回环地址，端口保持不变）"
            authBlocked -> buildString {
                append("该地址已响应，但鉴权未通过：")
                append(authDetail.ifBlank { "需要访问令牌" })
                if (bearerAuth) {
                    append("；阅读的 Web 路由要求 Bearer 令牌")
                }
                append("。这不代表地址不是阅读，也不代表服务离线：")
                if (webPageReachable || pageAuthWall) {
                    append("你可以直接在阅读 Web 页面里登录后继续操作，")
                }
                append("也可以先在「阅读 Web 服务」里填写与阅读相同的令牌，再重新探测。")
                if (tokenSaved) {
                    append("已保存的令牌被拒绝时，请确认令牌与当前地址绑定且未在阅读端变更。")
                }
            }
            webPageReachable && !readerDetected ->
                "网页在线，官方接口不匹配；可用阅读 Web 页面工具继续，不断言不是阅读，也不强制升级"
            webPageReachable -> "阅读 Web 页面可操作；需要写入/检验时再用 reader_web_sync_source / reader_web_verify"
            readerDetected -> buildString {
                append("接口可达（只说明官方接口有响应，不代表网页入口或书源可用），但根路径")
                if (webPageStatusCode != null && webPageProbeErrorCode != null) {
                    append("返回 HTTP $webPageStatusCode 后正文没有读完（${webPageProbeErrorCode}），页面是否可用未确认")
                } else if (webPageStatusCode != null) {
                    append("返回 HTTP $webPageStatusCode，不是可用页面入口")
                } else {
                    append("网络层失败（${webPageProbeErrorCode.orEmpty().ifBlank { "未知" }}），没有拿到 HTTP 响应")
                }
                append("；这不影响结构化接口，也不代表它不是阅读，可按需核对端口是否另指页面入口")
            }
            else -> buildString {
                append("该地址在接口路径上有响应，但根路径没有返回可用页面")
                when {
                    pageFailureKind == "network" -> append("：根路径网络层失败，没有拿到 HTTP 响应")
                    pageFailureKind == "body_unreadable" -> append("：根路径返回了状态码但正文没读完")
                    pageFailureKind == "redirect_refused" -> append("：根路径重定向到其它地址，已拒绝跟随")
                    webPageStatusCode != null -> append("（HTTP $webPageStatusCode）")
                }
                append("；请核对端口是否指向阅读的 Web 服务入口，这不代表接口不可达")
            }
        }

        Status(
            configured = true,
            reachable = online,
            readerDetected = readerDetected,
            tokenSaved = tokenSaved,
            tokenRequired = tokenRequired,
            bearerAuth = bearerAuth,
            capabilities = capabilities,
            allowedNextActions = allowed,
            message = message,
            authRequired = authRequired,
            authReady = authReady,
            webPageReachable = webPageReachable,
            webPageStatusCode = webPageStatusCode,
            webPageContentKind = webPageContentKind,
            webPageProbeErrorCode = webPageProbeErrorCode,
            webPageProbeDiagnostic = webPageProbeDiagnostic,
            apiProfile = apiProfile,
            apiProfileDetail = apiProfileDetail,
            originEcho = originEcho,
            configRevision = config.revision,
        )
    }

    private fun classify(error: ReaderWebClient.ReaderWebException, what: String): Capability = when {
        error.routeMissing -> Capability(Capability.UNSUPPORTED, "该版本没有提供${what}接口")
        // 路由存在但鉴权未过：既不能称为可用，也不能称为不存在
        error.code == "TOKEN_REQUIRED" || error.code == "TOKEN_INVALID" || error.statusCode in setOf(401, 403) ->
            Capability(Capability.UNAUTHORIZED, "${what}接口存在，但当前凭据无法通过鉴权（令牌缺失或无效）")
        // 502/503/超时等临时故障不能判成「不支持」，否则会误导模型去降级
        else -> Capability(Capability.UNKNOWN, "${what}探测未完成：${error.message.orEmpty()}")
    }

    private fun unknownAll(): Map<String, Capability> = linkedMapOf(
        "webPage" to Capability(Capability.UNKNOWN, "未配置"),
        "read" to Capability(Capability.UNKNOWN, "未配置"),
        "check" to Capability(Capability.UNKNOWN, "未配置"),
        "write" to Capability(Capability.UNKNOWN, "未配置"),
        "legacyDebug" to Capability(Capability.UNKNOWN, "未配置"),
        "search" to Capability(Capability.UNKNOWN, "未配置"),
    )

    companion object {
        /**
         * 本构建是否装了网页会话工具（`reader_web_page`）：纯功能标志，
         * 不含端口、日期或任何个人信息；客户端据此判断能否走网页会话，
         * 而不是按 root200/HTML 模板去猜服务身份。
         */
        const val WEB_UI_SESSION_SUPPORTED = true

        /**
         * 根路径失败归类（纯函数，供 [Status.webPageProbeFailureKind] 与回包信息复用）。
         *
         * 关键区分：拿到 HTTP 状态 = HTTP 层错误（401/403 是登录墙）；完全没有状态 =
         * 网络层失败；状态已到但正文读不动 = `body_unreadable`。
         */
        fun pageFailureKind(
            configured: Boolean,
            reachable: Boolean,
            statusCode: Int?,
            contentKind: String?,
            errorCode: String?,
        ): String = when {
            !configured -> "not_probed"
            reachable -> "none"
            errorCode == "REDIRECT_BLOCKED" -> "redirect_refused"
            statusCode != null && errorCode != null -> "body_unreadable"
            statusCode != null -> if (contentKind == "auth") "auth_wall" else "http_status"
            errorCode != null -> "network"
            else -> "unknown"
        }

        /** `reader_web_status` 的回包只用这套键；测试据此锁定不泄漏地址/令牌。 */
        val SENSITIVE_KEY_PATTERN = Regex("(?i)(token|authorization|host|address|url)$")

        /**
         * 回显「当前保存的是哪个地址」但不泄漏私网主机或令牌：
         * - 回环：`http://127.0.0.1:<实际端口>`；
         * - 其它：保留协议与实际端口，主机掩码为 `<已隐藏>`。
         */
        fun originEcho(origin: String): String? {
            // 先规范化：localhost/127.0.01 等严格等价别名归一为 127.0.0.1；其它回环主机保持原样
            val normalized = runCatching { ReaderWebOrigin.normalize(origin) }.getOrNull() ?: return null
            val url = runCatching { normalized.toHttpUrlOrNull() }.getOrNull() ?: return null
            val scheme = url.scheme
            // 回环：回显实际主机与实际端口（127.0.0.2 / [::1] 是不同回环主机，不能一律改写成 .1）
            return if (ReaderWebGuidance.isLoopback(normalized)) {
                val host = if (url.host.contains(':')) "[${url.host}]" else url.host
                "$scheme://$host:${url.port}"
            } else {
                "$scheme://<已隐藏>:${url.port}"
            }
        }
    }
}
