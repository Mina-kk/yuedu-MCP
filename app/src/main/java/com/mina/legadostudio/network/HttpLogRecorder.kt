package com.mina.legadostudio.network

import android.content.Context
import com.google.gson.Gson
import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.domain.LogFilterUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class HttpLogRecorder(context: Context, private val dao: StudioDao, private val gson: Gson) {
    data class Draft(
        val method: String,
        val url: String,
        val finalUrl: String = "",
        val statusCode: Int = 0,
        val durationMs: Long = 0,
        val requestHeaders: Map<String, String> = emptyMap(),
        val responseHeaders: Map<String, String> = emptyMap(),
        val requestBody: String = "",
        val responseBody: String = "",
        val error: String = "",
        val redirectChain: List<String> = emptyList(),
        /** 显式来源锚点：由调用链逐事务传入，不从线程/进程全局推断 */
        val sourceAnchor: String? = null,
        val contextId: String? = null,
        /** 发起链路标注：capture_once=最终落点、capture_hop=重定向中间跳（url=本跳请求、finalUrl=下一跳目标、responseHeaders 含 Location）。 */
        val originKind: String? = null,
        /**
         * 正文落库上限申请（0=默认 [HttpLogCaps.DEFAULT_BODY_CHARS]）。仅 WebView 抓包
         * 供给行显式申请更大上限（钳制在 [HttpLogCaps.MAX_WEBVIEW_BODY_CHARS]），
         * 其余来源不传即沿用旧 8_192 口径不变。
         */
        val bodyMaxChars: Int = 0,
    )
    private val prefs = context.getSharedPreferences("http_log_config", Context.MODE_PRIVATE)
    private val asyncWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "http-log-writer").apply { isDaemon = true }
    }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    /**
     * 非阻塞记录：排队到单线程写库，用在 shouldInterceptRequest 等不能阻塞的高频回调线程。
     * 实体构建（redact/normalize/截断）在调用线程完成，只把 DAO 写入异步化。
     */
    fun recordAsync(draft: Draft) {
        recordAsync(draft, onSettled = null)
    }

    /**
     * 带结算回调的非阻塞记录：[onSettled] 在该行写库尝试结束后（同一写库线程）回调，
     * 参数 true=落库成功、false=被丢弃（记录关闭/私网过滤）或写库失败——调用方不得
     * 把它当「已写」断言。抓包会话结算用它把「活跃表注销」排到结算行入库之后，
     * 消除「先注销后写库」的删除竞态：注销发生在写入完成之后，历史页在窗口内删会话
     * 不会漏掉这条结算行；失败也须照常注销（否则该会话永远活跃删不掉），如实记日志。
     * 回调在写库线程触发，实现里不得再阻塞等待写库线程（WebViewCaptureEngine 只动 synchronizedSet）。
     */
    fun recordAsync(draft: Draft, onSettled: ((Boolean) -> Unit)?) {
        val entity = if (enabled && !shouldDropForPrivateUrl(draft)) buildEntity(draft) else null
        asyncWriter.execute {
            val ok = entity != null && runCatching { runBlocking(Dispatchers.IO) { dao.addHttpLog(entity) } }.isSuccess
            onSettled?.invoke(ok)
        }
    }

    /** FIFO barrier, independent of enabled/filter state. Enqueue after the last producer exits. */
    fun afterPendingWrites(onSettled: () -> Unit) {
        asyncWriter.execute(onSettled)
    }

    fun record(draft: Draft) {
        if (!enabled) return
        // 关键防护：如果请求是本地回环（如 127.0.0.1、localhost）或私网 IP，直接丢弃，绝不污染抓包库
        if (shouldDropForPrivateUrl(draft)) return

        val entity = buildEntity(draft)
        runBlocking(Dispatchers.IO) { dao.addHttpLog(entity) }
    }

    private fun buildEntity(draft: Draft) = HttpLogEntity(
        method = draft.method,
        url = draft.url,
        finalUrl = draft.finalUrl,
        statusCode = draft.statusCode,
        durationMs = draft.durationMs,
        requestHeaders = gson.toJson(redact(draft.requestHeaders)),
        responseHeaders = gson.toJson(redact(draft.responseHeaders)),
        // 先按行上限截断再脱敏：被截掉的尾巴永不落库（也就无需脱敏），redactText 的正则
        // 全量扫描被限制在 ≤cap+mark 字符，避免对 MB 级正文跑正则拖慢拦截线程。
        requestBody = redactText(HttpLogCaps.takeMarked(draft.requestBody, HttpLogCaps.capFor(draft.bodyMaxChars))),
        responseBody = redactText(HttpLogCaps.takeMarked(draft.responseBody, HttpLogCaps.capFor(draft.bodyMaxChars))),
        error = redactText(draft.error).take(2_000),
        redirectChain = gson.toJson(draft.redirectChain),
        sourceAnchor = draft.sourceAnchor?.let { com.mina.legadostudio.domain.HttpLogAttributor.normalizeAnchor(it) ?: it },
        contextId = draft.contextId,
        originKind = draft.originKind,
    )

    private fun redact(headers: Map<String, String>): Map<String, String> = headers.mapValues { (key, value) ->
        if (key.equals("Authorization", true) || key.equals("Cookie", true) || key.equals("Set-Cookie", true) || key.contains("api-key", true)) "***" else value
    }

    private fun redactText(value: String): String {
        return value.replace(Regex("(?i)(authorization|api[-_ ]?key|token)\\s*[:=]\\s*[^,;\\s]+")) { mr ->
            "${mr.groupValues[1]}=***"
        }
    }

    companion object {
        /**
         * 私网/回环 URL 是否应丢弃不落库（record/recordAsync 共用的同一闸）。
         *
         * 默认丢弃——普通请求访问私网地址一律不进抓包库（防 127.0.0.1/localhost/内网污染
         * 站点胶囊条）。唯一受控例外：WebView 抓包会话里被「真正阻断」的私网导航/资源——
         * 引擎已用空响应把它断掉（blockedRows 也递增），若这里再把日志吞掉就会造成
         * 「计数记了阻断、库里却没有该行」的缺失。只对
         * `originKind == "webview_capture_blocked" && contextId 是 cap: 前缀` 的阻断证据放行：
         * - 缩到单一 originKind，不放宽逐次抓包（capture_once/capture_hop）或其它链路的私网请求；
         * - 要求 cap: 会话归属，避免非抓包上下文伪造 originKind 绕过私网过滤；
         * - 该行 statusCode=0、error=BLOCKED:…，是「拦截证据」而非真实私网响应数据。
         */
        fun shouldDropForPrivateUrl(draft: Draft): Boolean {
            if (!LogFilterUtils.isLoopbackOrPrivate(draft.url)) return false
            val isBlockedEvidence = draft.originKind == "webview_capture_blocked" &&
                (draft.contextId?.startsWith("cap:") == true)
            return !isBlockedEvidence
        }
    }
}