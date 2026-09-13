package io.legado.app.model.analyzeRule

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import org.jsoup.nodes.Element

/**
 * Stable public facade around LegadoTeam/legado rule analyzers.
 * The analyzers in this package are adapted from the GPL-3.0 upstream repository.
 */
@Keep
class LegadoRuleEngine {
    enum class Kind { CSS, XPATH, JSON_PATH, REGEX }

    @Keep
    data class Output(
        @SerializedName("values") val values: List<String>,
        @SerializedName("first") val first: String?,
        @SerializedName("count") val count: Int,
    )

    fun extract(content: Any, rawRule: String, kind: Kind = detect(rawRule)): Output {
        val reversed = rawRule.trimStart().startsWith("-") && !rawRule.trimStart().startsWith("--")
        val rule = if (reversed) rawRule.trimStart().substring(1) else rawRule
        val normalized = normalize(rule, kind)
        var values = when (kind) {
            // CSS/XPath 直接吃 Element：子规则在元素自身上求值，裸 @attr（如 @href）才能取到当前节点属性；
            // 若先把元素序列化成 HTML 再重解析，上下文会变成文档根节点，裸 @attr 永远取空（与官方行为不一致）
            Kind.CSS -> AnalyzeByJSoup(content).getStringList(normalized)
            Kind.XPATH -> AnalyzeByXPath(content).getStringList(normalized)
            Kind.JSON_PATH -> AnalyzeByJSonPath(stringify(content)).getStringList(normalized)
            Kind.REGEX -> AnalyzeByRegex.getElements(stringify(content), arrayOf(normalized)).mapNotNull { it.firstOrNull() }
        }
        if (reversed) values = values.asReversed()
        return Output(values, values.firstOrNull(), values.size)
    }

    fun elements(content: String, rawRule: String, kind: Kind = detect(rawRule)): List<String> =
        elementList(content, rawRule, kind).map { raw ->
            when (raw) {
                is Element -> raw.outerHtml()
                else -> raw.toString()
            }
        }

    /**
     * 返回原始节点（CSS→Element，XPath→JXNode），供上层把 Element 继续喂给子规则；
     * JSON/正则没有节点概念，退化为字符串列表。
     */
    fun elementList(content: Any, rawRule: String, kind: Kind = detect(rawRule)): List<Any> {
        val reversed = rawRule.trimStart().startsWith("-") && !rawRule.trimStart().startsWith("--")
        val rule = if (reversed) rawRule.trimStart().substring(1) else rawRule
        val normalized = normalize(rule, kind)
        var result: List<Any> = when (kind) {
            Kind.CSS -> AnalyzeByJSoup(content).getElements(normalized).toList()
            Kind.XPATH -> AnalyzeByXPath(content).getElements(normalized).orEmpty()
            Kind.JSON_PATH -> AnalyzeByJSonPath(stringify(content)).getStringList(normalized)
            Kind.REGEX -> AnalyzeByRegex.getElements(stringify(content), arrayOf(normalized)).mapNotNull { it.firstOrNull() }
        }
        if (reversed) result = result.asReversed()
        return result
    }

    private fun stringify(content: Any): String = when (content) {
        is String -> content
        is Element -> content.outerHtml()
        else -> content.toString()
    }

    companion object {
        fun detect(rule: String): Kind = when {
            rule.startsWith("@XPath:", true) || rule.trimStart().startsWith("//") -> Kind.XPATH
            rule.startsWith("@Json:", true) || rule.trimStart().startsWith("$.") -> Kind.JSON_PATH
            rule.startsWith(":") -> Kind.REGEX
            else -> Kind.CSS
        }

        private fun normalize(rule: String, kind: Kind): String = when (kind) {
            Kind.XPATH -> rule.removePrefix("@XPath:").removePrefix("@xpath:")
            Kind.JSON_PATH -> rule.removePrefix("@Json:").removePrefix("@json:")
            Kind.REGEX -> rule.removePrefix(":")
            Kind.CSS -> rule.removePrefix("@@")
        }
    }
}
