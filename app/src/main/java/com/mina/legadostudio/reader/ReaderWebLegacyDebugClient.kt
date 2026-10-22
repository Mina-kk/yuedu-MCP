package com.mina.legadostudio.reader

import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 旧版阅读 WebSocket 调试通道（`/bookSourceDebug`）。
 *
 * 用途：只跑一次「阅读端调试」并回收纯文本日志，给模型提供**只读的诊断线索**。
 * 各分支阅读（原版 / MD3 / C / T）广泛支持该通道，但它没有结构化结果：
 * 服务只在结束时 `close 1000`，部分旧版连明确的成功信号都没有。因此：
 * - 输出恒为 `status=legacy_debug_only`、`machineVerdict=unverifiable`、
 *   `evidenceScope=reader_web_legacy_debug`，**永远不会**输出 `PASSED`；
 * - 日志只代表阅读端调试输出，不能作为本 App `check_source` 的四阶段证据。
 *
 * 安全边界（与 [ReaderWebClient] 同口径，且更窄）：
 * - 目标只由**已保存的** Web 服务 origin 派生（[ReaderWebEndpoint]）：主机不变、端口 +1、路径固定；
 *   本类没有任何接受模型传入 host/port/URL 的入口，`sourceUrl` 只进 JSON 正文；
 * - 不跟随重定向（3xx 直接判失败，不会碰到 Location 指向的另一台主机）；
 * - 独立 OkHttpClient：`Proxy.NO_PROXY`、默认 `CookieJar.NO_COOKIES`（不保存也不回放 Cookie）、
 *   无日志拦截器、不重试；
 * - 严格预算：握手超时、单帧间隔超时、总超时、文本帧数量上限、日志总字节上限、单行长度上限；
 *   任一超限即停止接收并关闭连接；`finally` 里无条件关闭/取消 WebSocket；
 * - 脱敏：日志与错误文本里不出现访问令牌、令牌子协议串、用户 Web origin / 调试目标地址；
 * - 不写任何系统日志（无 Log/println），日志只经返回值交给调用方。
 *
 * 令牌握手（LegadoTeam 新版）：请求头
 * `Sec-WebSocket-Protocol: legado, legado.token.<UTF-8 令牌的 base64url 无填充>`；
 * 无令牌时只发 `legado`。旧版无鉴权分支忽略该头。
 *
 * 构造参数中的 [clientFactory] 与 [endpointResolver] 仅供 MockWebServer 测试替换；
 * 生产代码使用默认值（严格客户端 + 固定派生）。
 */
class ReaderWebLegacyDebugClient(
    private val configProvider: () -> ReaderWebConfigStore.Config?,
    private val clientFactory: () -> OkHttpClient = { defaultClient() },
    private val endpointResolver: (String) -> ReaderWebEndpoint.Target = ReaderWebEndpoint::derive,
) {

    /** 回包固定字段值；任何结果都不得声称检验通过。 */
    companion object {
        const val STATUS = "legacy_debug_only"
        const val MACHINE_VERDICT = "unverifiable"
        const val EVIDENCE_SCOPE = "reader_web_legacy_debug"

        private const val SUBPROTOCOL_HEADER = "Sec-WebSocket-Protocol"
        private const val CONNECT_TIMEOUT_SEC = 5L
        private const val WRITE_TIMEOUT_SEC = 10L

        private const val MAX_SOURCE_URL_CHARS = 4_096
        private const val MAX_KEYWORD_CHARS = 512

        private const val DEFAULT_TOTAL_TIMEOUT_MS = 30_000L
        private const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val DEFAULT_IDLE_TIMEOUT_MS = 15_000L
        private const val DEFAULT_MAX_FRAMES = 2_000
        private const val DEFAULT_MAX_TOTAL_BYTES = 256L * 1024
        private const val DEFAULT_MAX_LINE_CHARS = 8_192

        /** 未观察到关闭帧的哨兵值（WebSocket 合法关闭码是 1000..4999）。 */
        private const val NO_CLOSE_CODE = -1

        /** 只在「几乎没有日志」时才据此判定拒绝，避免把正常调试日志里的词误判成拒绝。 */
        private const val REFUSAL_LOG_LINE_LIMIT = 3
        private const val REFUSAL_LOG_CHAR_LIMIT = 600

        /**
         * 只保留指向「请求被拒绝」的强特征短语。
         * 「未找到书源 / source not found」这类也出现在搜索无结果的正常日志里，故不采用。
         */
        private val SOURCE_MISSING_PATTERNS = listOf(
            "书源不存在", "未找到对应书源", "book source not found",
        )
        private val BUSY_PATTERNS = listOf(
            "已有调试", "正在调试", "另一个调试", "调试任务已存在", "debug task already",
        )
        /** 仅作提示：部分版本在结束时输出这类文本；不作为通过依据。 */
        private val COMPLETION_PATTERNS = listOf("调试完成", "检验完成", "调试结束", "debug finished")

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            // 局域网直连：绝不把令牌子协议或调试内容经系统代理转发
            .proxy(Proxy.NO_PROXY)
            // 不安装 CookieJar：OkHttp 的默认值就是 CookieJar.NO_COOKIES，不保存也不回放 Cookie
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            // 读超时交给本类的帧间隔/总超时预算，避免 OkHttp 在空闲期直接断开
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    /** @property debugLogs 已接收并脱敏的日志（错误时也可能带有部分日志）。 */
    class LegacyDebugException(
        val code: String,
        message: String,
        val debugLogs: List<String> = emptyList(),
    ) : Exception(message)

    /**
     * 一次调试会话的结果。
     *
     * @property closeCode 服务端关闭码（1000 正常结束）；null 表示没有拿到关闭帧。
     * @property truncated 是否因帧数/字节/单行上限提前停止接收。
     * @property completionSignal 日志里出现「完成」类文本；仅为线索，不影响 verdict。
     */
    data class Outcome(
        val sourceUrl: String,
        val keyword: String,
        val debugLogs: List<String>,
        val frames: Int,
        val bytes: Long,
        val truncated: Boolean,
        val closeCode: Int?,
        val closeReason: String,
        val elapsedMs: Long,
        val completionSignal: Boolean,
    ) {
        fun toPayload(): Map<String, Any?> = linkedMapOf(
            "status" to STATUS,
            "machineVerdict" to MACHINE_VERDICT,
            "evidenceScope" to EVIDENCE_SCOPE,
            "sourceUrl" to sourceUrl,
            "keyword" to keyword,
            "debugLogs" to debugLogs,
            "frameCount" to frames,
            "byteCount" to bytes,
            "truncated" to truncated,
            "closeCode" to closeCode,
            "closeReason" to closeReason,
            "completionSignal" to completionSignal,
            "elapsedMs" to elapsedMs,
            "message" to message(truncated),
            "evidenceNote" to
                "日志来自阅读 App 的旧版 WebSocket 调试通道，仅代表阅读端调试输出；该通道没有结构化结论，" +
                "不登记为本实例 check_source 的证据，也不代表书源检验通过",
        )

        private fun message(truncated: Boolean): String = buildString {
            append("阅读端调试通道已结束（close ${closeCode ?: "无关闭帧"}）")
            if (truncated) append("，日志因达到本地上限被截断，未收到完整输出")
            append("；旧版通道不提供明确的成功信号，这些日志不能作为检验通过的依据")
        }

        companion object {
            /** 失败回包：保留 errorCode，绝不含 PASSED。 */
            fun errorPayload(code: String, message: String, debugLogs: List<String> = emptyList()): Map<String, Any?> =
                linkedMapOf(
                    "status" to STATUS,
                    "machineVerdict" to MACHINE_VERDICT,
                    "evidenceScope" to EVIDENCE_SCOPE,
                    "errorCode" to code,
                    "message" to message,
                    "debugLogs" to debugLogs,
                )
        }
    }

    private sealed class Settle {
        class Done(
            val logs: List<String>,
            val frames: Int,
            val bytes: Long,
            val truncated: Boolean,
            val closeCode: Int?,
            val closeReason: String,
        ) : Settle()

        class Failed(
            val code: String,
            val message: String,
            val logs: List<String>,
        ) : Settle()
    }

    private val httpClient by lazy { clientFactory() }

    /**
     * 连接阅读旧版调试通道，发送一次调试请求并收集日志。
     *
     * @param sourceUrl 必须已在阅读里保存的 bookSourceUrl（只写进 JSON，绝不作为连接目标）
     * @param keyword 搜索关键词（可为空串）
     * @param totalTimeoutMs 总预算（200..120000）
     * @param handshakeTimeoutMs 握手预算（<= 总预算）
     * @param idleTimeoutMs 握手完成后允许的无帧间隔
     * @param maxFrames 最多接收的帧数
     * @param maxTotalBytes 日志总字节上限
     * @param maxLineChars 单条日志字符上限
     *
     * @throws LegacyDebugException 未配置、地址/参数非法、令牌问题、路由缺失、离线、忙碌、书源不存在、超时等
     */
    @JvmOverloads
    suspend fun debug(
        sourceUrl: String,
        keyword: String = "",
        totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
        handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
        maxFrames: Int = DEFAULT_MAX_FRAMES,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
    ): Outcome = execute(
        config = null,
        sourceUrl = sourceUrl,
        keyword = keyword,
        totalTimeoutMs = totalTimeoutMs,
        handshakeTimeoutMs = handshakeTimeoutMs,
        idleTimeoutMs = idleTimeoutMs,
        maxFrames = maxFrames,
        maxTotalBytes = maxTotalBytes,
        maxLineChars = maxLineChars,
    )

    /**
     * 用调用方固定的配置快照调试（一次工具调用里 HTTP 与 WS 共用同一 origin/令牌）。
     * `config = null` 表示按当前配置读取。
     */
    @JvmOverloads
    suspend fun debugAt(
        config: ReaderWebConfigStore.Config?,
        sourceUrl: String,
        keyword: String = "",
        totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    ): Outcome = execute(
        config = config,
        sourceUrl = sourceUrl,
        keyword = keyword,
        totalTimeoutMs = totalTimeoutMs,
        handshakeTimeoutMs = DEFAULT_HANDSHAKE_TIMEOUT_MS,
        idleTimeoutMs = maxOf(DEFAULT_IDLE_TIMEOUT_MS, totalTimeoutMs),
        maxFrames = DEFAULT_MAX_FRAMES,
        maxTotalBytes = DEFAULT_MAX_TOTAL_BYTES,
        maxLineChars = DEFAULT_MAX_LINE_CHARS,
    )

    private suspend fun execute(
        config: ReaderWebConfigStore.Config?,
        sourceUrl: String,
        keyword: String,
        totalTimeoutMs: Long,
        handshakeTimeoutMs: Long,
        idleTimeoutMs: Long,
        maxFrames: Int,
        maxTotalBytes: Long,
        maxLineChars: Int,
    ): Outcome = withContext(Dispatchers.IO) {
        val url = sourceUrl.trim()
        if (url.isEmpty()) throw LegacyDebugException("INVALID_ARGUMENT", "bookSourceUrl 不能为空")
        if (url.length > MAX_SOURCE_URL_CHARS) throw LegacyDebugException("INVALID_ARGUMENT", "bookSourceUrl 过长")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw LegacyDebugException("INVALID_ARGUMENT", "bookSourceUrl 必须是 http/https 书源地址")
        }
        if (url.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            throw LegacyDebugException("INVALID_ARGUMENT", "bookSourceUrl 含非法控制字符")
        }
        val key = keyword.trim()
        if (key.length > MAX_KEYWORD_CHARS) throw LegacyDebugException("INVALID_ARGUMENT", "关键词过长")
        if (key.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            throw LegacyDebugException("INVALID_ARGUMENT", "关键词含非法控制字符")
        }

        val config = config ?: configProvider() ?: throw LegacyDebugException(
            "NOT_CONFIGURED",
            "尚未配置阅读 Web 服务地址：请在 MCP 页「设置」→「阅读 Web 服务」填写并保存后再试",
        )
        val target = try {
            endpointResolver(config.baseUrl)
        } catch (error: ReaderWebEndpoint.InvalidEndpointException) {
            throw LegacyDebugException(error.code, error.message.orEmpty())
        }
        val token = config.token.trim()

        val totalMs = totalTimeoutMs.coerceIn(200L, 120_000L)
        val handshakeMs = handshakeTimeoutMs.coerceIn(50L, totalMs)
        val idleMs = idleTimeoutMs.coerceIn(50L, totalMs)
        val frameLimit = maxFrames.coerceIn(1, 10_000)
        val byteLimit = maxTotalBytes.coerceIn(1_024L, 4L * 1024 * 1024)
        val lineLimit = maxLineChars.coerceIn(256, 64 * 1024)
        val tickMs = (idleMs / 8).coerceIn(20L, 250L)

        val redact = Redactor(config.baseUrl, target, token)
        val payload = JsonObject().apply {
            addProperty("tag", url)
            addProperty("key", key)
        }.toString()

        val settle = CompletableDeferred<Settle>()
        val opened = AtomicBoolean(false)
        val frames = AtomicInteger()
        val bytes = AtomicLong()
        val truncated = AtomicBoolean(false)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val closeCode = AtomicInteger(NO_CLOSE_CODE)
        val closeReason = AtomicReference("")
        val lastActivityNanos = AtomicLong(System.nanoTime())
        val startedNanos = System.nanoTime()

        fun snapshotLogs(): List<String> = synchronized(logs) { logs.toList() }

        fun settleFailed(code: String, message: String) {
            settle.complete(Settle.Failed(code, redact.apply(message), snapshotLogs()))
        }

        fun settleDone(cut: Boolean = truncated.get()) {
            val observed = closeCode.get()
            settle.complete(
                Settle.Done(
                    logs = snapshotLogs(),
                    frames = frames.get(),
                    bytes = bytes.get(),
                    truncated = cut,
                    closeCode = observed.takeIf { it != NO_CLOSE_CODE },
                    closeReason = closeReason.get(),
                )
            )
        }

        fun ingest(text: String) {
            if (settle.isCompleted) return
            val count = frames.incrementAndGet()
            if (count > frameLimit) {
                truncated.set(true)
                settleDone(cut = true)
                return
            }
            val clipped = if (text.length > lineLimit) {
                truncated.set(true)
                text.take(lineLimit) + "…(单条日志过长，已截断)"
            } else {
                text
            }
            val size = clipped.toByteArray(StandardCharsets.UTF_8).size.toLong()
            if (bytes.addAndGet(size) > byteLimit) {
                truncated.set(true)
                settleDone(cut = true)
                return
            }
            // 单条过长只裁剪这一条并标记截断，不因此中断整场调试
            logs.add(redact.apply(clipped))
        }

        val offeredSubprotocols = ReaderWebEndpoint.subprotocols(token).toSet()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!opened.compareAndSet(false, true)) return
                lastActivityNanos.set(System.nanoTime())
                // 服务端选定的子协议必须是我们发过的那个；不接受时立即断开，不把调试数据发过去
                val selected = response.header(SUBPROTOCOL_HEADER)?.trim().orEmpty()
                if (selected.isNotEmpty() && selected.split(',').map { it.trim() }
                        .any { it !in offeredSubprotocols }
                ) {
                    settleFailed(
                        "PROTOCOL_ERROR",
                        "阅读调试服务选定了未提供的子协议，已按安全策略断开；请确认地址指向阅读本身",
                    )
                    runCatching { webSocket.close(1002, "") }
                    return
                }
                if (!webSocket.send(payload)) {
                    settleFailed("CONNECTION_LOST", "调试请求未能发送：连接已关闭")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastActivityNanos.set(System.nanoTime())
                ingest(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // 协议只发文本：二进制帧不解析、不保留，但仍计入帧数上限，避免被刷爆
                lastActivityNanos.set(System.nanoTime())
                if (frames.incrementAndGet() > frameLimit) {
                    truncated.set(true)
                    settleDone(cut = true)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                lastActivityNanos.set(System.nanoTime())
                closeCode.compareAndSet(NO_CLOSE_CODE, code)
                closeReason.set(redact.apply(reason))
                // 收到对端关闭帧即视为会话终止：不等待可能永不到来的 onClosed，
                // 也不把它误判成空闲超时；随后回显关闭帧完成握手。
                settleDone()
                runCatching { webSocket.close(code, "") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closeCode.compareAndSet(NO_CLOSE_CODE, code)
                // onClosing 通常已写入关闭原因；只有还没写时才补上
                if (closeReason.get().isEmpty()) closeReason.set(redact.apply(reason))
                settleDone()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val (code, message) = classifyFailure(t, response, token.isEmpty(), opened.get())
                settleFailed(code, message)
            }
        }

        val request = Request.Builder()
            .url(target.url)
            .header(SUBPROTOCOL_HEADER, ReaderWebEndpoint.headerValue(token))
            .build()

        val socket = try {
            httpClient.newWebSocket(request, listener)
        } catch (error: IOException) {
            throw LegacyDebugException("UNREACHABLE", "无法建立阅读调试连接：请确认阅读已开启 Web 服务且地址正确")
        }

        try {
            val settled = coroutineScope {
                val watchdog = launch {
                    while (isActive) {
                        val now = System.nanoTime()
                        if (!opened.get()) {
                            if ((now - startedNanos) / 1_000_000 >= handshakeMs) {
                                settleFailed(
                                    "HANDSHAKE_TIMEOUT",
                                    "阅读调试通道握手超时（${handshakeMs}ms）：请确认阅读的 WebSocket 调试端口是 Web 端口的下一个端口",
                                )
                                return@launch
                            }
                        } else if ((now - lastActivityNanos.get()) / 1_000_000 >= idleMs) {
                            settleFailed(
                                "TIMEOUT",
                                "本工具等待阅读调试输出超过 ${idleMs}ms（这是 MCP 端等待上限，不是阅读端超时）；书源可能在等待/sleep，请加大 timeoutSec 重跑 reader_web_debug，结论仍以阅读端为准，不得改用沙箱结论",
                            )
                            return@launch
                        }
                        delay(tickMs)
                    }
                }
                try {
                    withTimeout(totalMs) { settle.await() }
                } finally {
                    watchdog.cancel()
                }
            }

            when (settled) {
                is Settle.Failed -> throw LegacyDebugException(settled.code, settled.message, settled.logs)
                is Settle.Done -> {
                    val combined = settled.logs.joinToString("\n")
                    val refusal = if (
                        settled.logs.size <= REFUSAL_LOG_LINE_LIMIT && combined.length <= REFUSAL_LOG_CHAR_LIMIT
                    ) {
                        classifyRefusal(combined)
                    } else {
                        null
                    }
                    if (refusal != null) {
                        val (code, message) = refusal
                        throw LegacyDebugException(code, message, settled.logs)
                    }
                    val normalizedClose = settled.closeCode
                    if (normalizedClose == null && !settled.truncated) {
                        throw LegacyDebugException(
                            "CONNECTION_LOST",
                            "阅读调试连接已断开，但没有收到关闭帧：阅读可能被关闭或调试通道被重置",
                            settled.logs,
                        )
                    }
                    if (normalizedClose != null) {
                        when (normalizedClose) {
                            1000, 1001, 1005 -> Unit
                            1008, 4401, 4403 -> {
                                val code = if (token.isEmpty()) "TOKEN_REQUIRED" else "TOKEN_INVALID"
                                val message = if (token.isEmpty()) {
                                    "阅读拒绝了调试连接（close $normalizedClose）：该版本可能要求访问令牌，请填写与阅读相同的令牌"
                                } else {
                                    "阅读拒绝了调试连接（close $normalizedClose）：访问令牌可能无效或已变更"
                                }
                                throw LegacyDebugException(code, message, settled.logs)
                            }
                            1012, 1013 -> throw LegacyDebugException(
                                "SERVICE_BUSY",
                                "阅读调试通道暂时不可用（close $normalizedClose）：服务正在重启或过载，请稍后重试",
                                settled.logs,
                            )
                            1011 -> throw LegacyDebugException(
                                "SERVICE_ERROR",
                                "阅读调试通道内部错误（close $normalizedClose）：请查看阅读端日志后重试",
                                settled.logs,
                            )
                            else -> throw LegacyDebugException(
                                "PROTOCOL_ERROR",
                                "阅读调试通道异常关闭（close $normalizedClose）：该分支的调试协议可能不兼容",
                                settled.logs,
                            )
                        }
                    }
                    val elapsed = (System.nanoTime() - startedNanos) / 1_000_000
                    Outcome(
                        sourceUrl = url,
                        keyword = key,
                        debugLogs = settled.logs,
                        frames = settled.frames,
                        bytes = settled.bytes,
                        truncated = settled.truncated,
                        closeCode = normalizedClose,
                        closeReason = settled.closeReason,
                        elapsedMs = elapsed,
                        completionSignal = COMPLETION_PATTERNS.any { combined.contains(it, ignoreCase = true) },
                    )
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw LegacyDebugException(
                "TIMEOUT",
                "本工具等待阅读调试达到总上限 ${totalMs}ms（这是 MCP 端等待上限，不是阅读端超时）；阅读可能仍在处理，请加大 timeoutSec 重跑 reader_web_debug，结论仍以阅读端为准，不得改用沙箱结论",
                snapshotLogs(),
            )
        } finally {
            runCatching { socket.close(1000, "") }
            runCatching { socket.cancel() }
        }
    }

    /** 依据 HTTP 响应/异常如实分类；错误文本不含地址与令牌。 */
    private fun classifyFailure(
        error: Throwable,
        response: Response?,
        tokenAbsent: Boolean,
        opened: Boolean,
    ): Pair<String, String> {
        val status = response?.code
        if (status != null) {
            return when {
                status in 300..399 -> "REDIRECT_BLOCKED" to
                    "阅读调试服务返回了重定向，已按安全策略拒绝跟随；请确认地址指向阅读本身"
                status == 401 || status == 403 -> {
                    if (tokenAbsent) {
                        "TOKEN_REQUIRED" to
                            "阅读调试通道要求访问令牌（HTTP $status）：请在设置里填写与阅读相同的令牌"
                    } else {
                        "TOKEN_INVALID" to
                            "阅读调试通道拒绝了访问令牌（HTTP $status）：令牌可能无效或已变更"
                    }
                }
                status in setOf(404, 405, 410, 426, 501) -> "ENDPOINT_MISSING" to
                    "阅读没有提供 WebSocket 调试路由（HTTP $status）：该分支可能不支持调试通道，或调试端口不是 Web 端口的下一个端口"
                status == 429 || status == 503 -> "SERVICE_BUSY" to
                    "阅读调试服务忙碌/暂不可用（HTTP $status）：可能已有其它调试任务，请稍后重试"
                status == 408 || status == 504 -> "TIMEOUT" to
                    "阅读调试服务响应超时（HTTP $status）：请稍后重试"
                else -> "HTTP_ERROR" to
                    "阅读调试服务返回 HTTP $status：请确认地址指向阅读 Web 服务而不是其它网页"
            }
        }
        return when (error) {
            is SocketTimeoutException -> "TIMEOUT" to "连接阅读调试端口超时：请确认阅读已开启 Web 服务且在同一网络"
            is java.net.UnknownHostException -> "UNREACHABLE" to
                "无法解析阅读 Web 服务地址：请确认地址填写正确、与阅读在同一网络"
            is java.net.ConnectException -> "UNREACHABLE" to
                "无法连接阅读调试端口：请确认阅读已开启 Web 服务，且调试端口为 Web 端口的下一个端口"
            is java.net.NoRouteToHostException -> "UNREACHABLE" to
                "无法到达阅读 Web 服务：请确认手机与阅读在同一网络"
            is javax.net.ssl.SSLException -> "UNREACHABLE" to
                "阅读调试端口的 TLS 握手失败：请确认 https 地址与阅读实际配置一致"
            is java.net.ProtocolException -> if (opened) {
                "PROTOCOL_ERROR" to "阅读调试通道协议异常：该分支可能不兼容 WebSocket 调试协议"
            } else {
                "ENDPOINT_MISSING" to "该地址没有按 WebSocket 协议响应：请确认调试端口属于阅读"
            }
            is IOException -> if (opened) {
                "CONNECTION_LOST" to "阅读调试连接中断：阅读可能被关闭或调试通道被重置"
            } else {
                "UNREACHABLE" to "无法连接阅读调试服务：请确认阅读已开启 Web 服务且地址端口正确"
            }
            else -> if (opened) {
                "CONNECTION_LOST" to "阅读调试连接异常结束"
            } else {
                "UNREACHABLE" to "无法建立阅读调试连接"
            }
        }
    }

    /** 只有极少量日志时才据此判断「服务拒绝执行」；正常调试日志里的同名词不在此列。 */
    private fun classifyRefusal(text: String): Pair<String, String>? {
        if (SOURCE_MISSING_PATTERNS.any { text.contains(it, ignoreCase = true) }) {
            return "SOURCE_NOT_FOUND" to
                "阅读中没有该地址的书源（调试通道返回：书源不存在）：请先在阅读里保存该书源后再试"
        }
        if (BUSY_PATTERNS.any { text.contains(it, ignoreCase = true) }) {
            return "SERVICE_BUSY" to
                "阅读调试通道正忙（调试通道返回：已有调试任务）：请等当前调试结束后重试"
        }
        return null
    }

    /**
     * 日志/错误文本脱敏：令牌、令牌子协议串、用户 Web origin 与调试目标地址一律替换掉。
     * 只针对本次连接自己的地址，不改写日志里其它 URL（书源地址是调试内容的一部分）。
     */
    private class Redactor(origin: String, target: ReaderWebEndpoint.Target, token: String) {
        private val replacements: List<Pair<String, String>>

        init {
            val entries = mutableListOf<String>()
            val base = origin.trim().trimEnd('/')
            if (base.isNotEmpty()) entries += base
            if (token.isNotEmpty()) {
                // 令牌本体与子协议串（含 base64 形式）都必须整段隐藏：宁可多替换，
                // 也不能让令牌出现在回给模型的日志里。
                entries += token
                entries += ReaderWebEndpoint.headerValue(token)
                entries += ReaderWebEndpoint.encodeToken(token)
            }
            entries += target.display
            entries += target.url.toString()
            val hostPort = "${target.host}:${target.port}"
            entries += hostPort
            entries += (if (target.secure) "wss://" else "ws://") + hostPort
            entries += (if (target.secure) "https://" else "http://") + hostPort
            if (target.host.contains(':')) {
                // IPv6 在 URL 里带方括号：脱敏也要覆盖 "[::1]:1163" 这种写法
                val bracketed = "[${target.host}]:${target.port}"
                entries += bracketed
                entries += "ws://$bracketed"
                entries += "wss://$bracketed"
                entries += "http://$bracketed"
                entries += "https://$bracketed"
            }
            val originUrl = target.base
            run {
                val bare = "${originUrl.host}:${originUrl.port}"
                entries += bare
                entries += "ws://$bare"
                entries += "http://$bare"
            }
            replacements = entries
                .filter { it.isNotBlank() }
                .distinct()
                .sortedByDescending { it.length }
                .map { it to "[已隐藏]" }
        }

        fun apply(text: String): String {
            var out = text
            for ((needle, mask) in replacements) {
                if (out.contains(needle)) out = out.replace(needle, mask)
            }
            return out
        }
    }
}
