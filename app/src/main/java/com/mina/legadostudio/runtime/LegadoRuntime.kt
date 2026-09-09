package com.mina.legadostudio.runtime

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.mina.legadostudio.domain.BookSourceValidator
import com.mina.legadostudio.network.HttpFetcher
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
    )
    @Keep
    data class InspectReport(
        @SerializedName("response") val response: HttpFetcher.FetchResult,
        @SerializedName("output") val output: LegadoRuleEngine.Output?,
        @SerializedName("elements") val elements: List<String>,
        @SerializedName("elementWarning") val elementWarning: String? = null,
    )
    @Keep
    data class DebugReport(
        @SerializedName("type") val type: String,
        @SerializedName("entry") val entry: String,
        @SerializedName("lines") val lines: List<String>,
        @SerializedName("data") val data: Any?,
    )

    suspend fun inspect(request: InspectRequest): InspectReport
    suspend fun debug(sourceJson: String, entry: String): DebugReport
    suspend fun evaluate(js: String, baseUrl: String = "", previous: Any? = null): RhinoEvaluator.Result
    fun validate(sourceJson: String): BookSourceValidator.Report
}
