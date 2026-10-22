package com.mina.legadostudio.runtime

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.mina.legadostudio.domain.BookSourceValidator
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpOrigin
import io.legado.app.model.analyzeRule.LegadoRuleEngine

interface LegadoRuntime {
    data class InspectRequest(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val charset: String? = null,
        val rule: String = "",
        val kind: LegadoRuleEngine.Kind? = null,
        /** 逐事务来源：contextId/sourceAnchor 由 MCP 调用方显式传入 */
        val contextId: String? = null,
        val sourceAnchor: String? = null,
    )
    @Keep
    data class InspectReport(
        @SerializedName("response") val response: HttpFetcher.FetchResult,
        @SerializedName("output") val output: LegadoRuleEngine.Output?,
        @SerializedName("elements") val elements: List<String>,
        @SerializedName("elementWarning") val elementWarning: String? = null,
        /** 当前解析引擎画像：标识沙箱用的是移植的官方 Legado 引擎，便于判断沙箱/真机差异来源 */
        @SerializedName("engineProfile") val engineProfile: String? = "legado-official-jsoup+rhino",
    )
    @Keep
    data class DebugReport(
        @SerializedName("type") val type: String,
        @SerializedName("entry") val entry: String,
        @SerializedName("lines") val lines: List<String>,
        @SerializedName("data") val data: Any?,
        /** 执行期间书源 java.log/log 系列输出（最多最近 200 条，每条截断 500 字符；无输出为 null） */
        @SerializedName("jsLogs") val jsLogs: List<String>? = null,
        /** 执行结束时 cache.putMemory 键值快照（值截断 300 字符，最多 100 键；无写入为 null） */
        @SerializedName("cacheSnapshot") val cacheSnapshot: Map<String, String>? = null,
        /** 最近 50 次 cache put/delete 调用：`时间戳ms kind key`，按发生顺序；无写入为 null */
        @SerializedName("recentCachePuts") val recentCachePuts: List<String>? = null,
        /** 正文阶段：合并清洗后正文字数；非正文阶段为 null */
        @SerializedName("contentLength") val contentLength: Int? = null,
        /** 正文阶段：正文前 50 字预览；非正文阶段为 null */
        @SerializedName("contentPreview") val contentPreview: String? = null,
        /** 字段规则解析失败明细：`ruleSearch.bookUrl 规则解析失败（JSONPath）：$.id → 原因`；无失败为 null */
        @SerializedName("fieldErrors") val fieldErrors: List<String>? = null,
    )

    suspend fun inspect(request: InspectRequest): InspectReport
    suspend fun debug(sourceJson: String, entry: String): DebugReport
    /** cacheFetch 按调用传入；null = 实时请求。默认参数保持旧调用点兼容。 */
    suspend fun debug(
        sourceJson: String,
        entry: String,
        cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?,
    ): DebugReport = debug(sourceJson, entry)
    /**
     * 带逐事务来源的调试入口：contextId/sourceAnchor 会透传给每个 HTTP 事务日志。
     * 默认实现回退到旧签名，保证自定义 LegadoRuntime 不破编译。
     */
    suspend fun debug(
        sourceJson: String,
        entry: String,
        cacheFetch: (suspend (HttpFetcher.FetchRequest) -> HttpFetcher.FetchResult)?,
        origin: HttpOrigin?,
    ): DebugReport = debug(sourceJson, entry, cacheFetch)
    suspend fun evaluate(js: String, baseUrl: String = "", previous: Any? = null): RhinoEvaluator.Result
    /** 带逐事务来源的 JS 求值（JS 内 ajax/connect/webView 请求会带上该来源）。 */
    suspend fun evaluate(js: String, baseUrl: String, previous: Any?, origin: HttpOrigin?): RhinoEvaluator.Result =
        evaluate(js, baseUrl, previous)
    fun validate(sourceJson: String): BookSourceValidator.Report
}
