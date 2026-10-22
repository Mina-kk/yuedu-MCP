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

    fun extract(content: Any, rawRule: String, kind: Kind = detect(rawRule, content), onError: ((Exception) -> Unit)? = null): Output {
        val reversed = rawRule.trimStart().startsWith("-") && !rawRule.trimStart().startsWith("--")
        val rule = if (reversed) rawRule.trimStart().substring(1) else rawRule
        val normalized = normalize(rule, kind)
        var values = when (kind) {
            // CSS/XPath 直接吃 Element：子规则在元素自身上求值，裸 @attr（如 @href）才能取到当前节点属性；
            // 若先把元素序列化成 HTML 再重解析，上下文会变成文档根节点，裸 @attr 永远取空（与官方行为不一致）
            Kind.CSS -> AnalyzeByJSoup(content).getStringList(normalized)
            Kind.XPATH -> AnalyzeByXPath(content).getStringList(normalized)
            Kind.JSON_PATH -> AnalyzeByJSonPath(jsonContent(content), onError).getStringList(normalized)
            Kind.REGEX -> AnalyzeByRegex.getElements(stringify(content), arrayOf(normalized)).mapNotNull { it.firstOrNull() }
        }
        if (reversed) values = values.asReversed()
        return Output(values, values.firstOrNull(), values.size)
    }

    fun elements(content: String, rawRule: String, kind: Kind = detect(rawRule, content)): List<String> =
        elementList(content, rawRule, kind).map { raw ->
            when (raw) {
                is Element -> raw.outerHtml()
                is Map<*, *>, is List<*> -> toJson(raw)
                else -> raw.toString()
            }
        }

    /**
     * 返回原始节点（CSS→Element，XPath→JXNode，JSON→Map/List 原始对象），供上层把节点继续喂给子规则；
     * JSON 列表元素保留对象形态，字段规则（`$.name` / `name`）在元素自身上求值，与官方一致。
     */
    fun elementList(content: Any, rawRule: String, kind: Kind = detect(rawRule, content), onError: ((Exception) -> Unit)? = null): List<Any> {
        val reversed = rawRule.trimStart().startsWith("-") && !rawRule.trimStart().startsWith("--")
        val rule = if (reversed) rawRule.trimStart().substring(1) else rawRule
        val normalized = normalize(rule, kind)
        var result: List<Any> = when (kind) {
            Kind.CSS -> AnalyzeByJSoup(content).getElements(normalized).toList()
            Kind.XPATH -> AnalyzeByXPath(content).getElements(normalized).orEmpty()
            Kind.JSON_PATH -> AnalyzeByJSonPath(jsonContent(content), onError).getList(normalized).orEmpty().filterNotNull()
            Kind.REGEX -> AnalyzeByRegex.getElements(stringify(content), arrayOf(normalized)).mapNotNull { it.firstOrNull() }
        }
        if (reversed) result = result.asReversed()
        return result
    }

    private fun stringify(content: Any): String = when (content) {
        is String -> content
        is Element -> content.outerHtml()
        is Map<*, *>, is List<*> -> toJson(content)
        else -> content.toString()
    }

    private fun jsonContent(content: Any): Any = when (content) {
        is Map<*, *>, is List<*> -> content
        else -> stringify(content)
    }

    companion object {
        private val gson = com.google.gson.Gson()

        fun toJson(value: Any): String = gson.toJson(value)

        /** 对齐官方 AnalyzeRule.setContent：内容是 JSON 对象/数组（或 {…}/[…] 文本）时整体按 JSON 处理。 */
        fun isJsonContent(content: Any?): Boolean = when (content) {
            null, is Element -> false
            is Map<*, *>, is List<*> -> true
            is String -> content.trim().let { (it.startsWith("{") && it.endsWith("}")) || (it.startsWith("[") && it.endsWith("]")) }
            else -> false
        }

        /**
         * 官方 SourceRule.init：显式前缀优先；内容是 JSON 时无前缀规则走 JsonPath
         * （jayway 对不以 `$` 开头的路径自动补 `$.`，所以 `bookName` 等价 `$.bookName`）。
         */
        fun detect(rule: String, content: Any?): Kind {
            val kind = detect(rule)
            if (kind != Kind.CSS || !isJsonContent(content)) return kind
            val trimmed = rule.trimStart()
            return if (trimmed.startsWith("@CSS:", true) || trimmed.startsWith("@@")) kind else Kind.JSON_PATH
        }

        /**
         * 对齐官方 AnalyzeRule.SourceRule.init 的整串模式判定：模式只看规则串开头一次，
         * &&/||/%% 分支内再出现的前缀不会进到这里（交由各引擎按字面量处理，
         * JSoup 下分支内 @css: 会报 Could not parse query，与真机一致）。
         * 显式 kind（inspect_rule 传入）优先于本判定。
         */
        fun detect(rule: String): Kind {
            val trimmed = rule.trimStart()
            return when {
                // @css: 前缀不在这里剥：AnalyzeByJSoup 内部 SourceRule 负责剥除并置 isCss，
                // 走 selectCss 语义（CSS 用 jsoup select、不支持 legado 索引语法），与真机一致
                trimmed.startsWith("@CSS:", true) -> Kind.CSS
                trimmed.startsWith("@XPath:", true) -> Kind.XPATH
                trimmed.startsWith("@Json:", true) -> Kind.JSON_PATH
                trimmed.startsWith(":") -> Kind.REGEX
                // @@ = 剥前缀走默认（JSoup），normalize 负责剥除
                trimmed.startsWith("@@") -> Kind.CSS
                // 官方 SourceRule：/ 开头（含 //）均判 XPath
                trimmed.startsWith("/") -> Kind.XPATH
                trimmed.startsWith("$.") || trimmed.startsWith("$[") -> Kind.JSON_PATH
                else -> Kind.CSS
            }
        }

        private fun normalize(rule: String, kind: Kind): String = when (kind) {
            Kind.XPATH -> rule.removePrefix("@XPath:").removePrefix("@xpath:")
            Kind.JSON_PATH -> rule.removePrefix("@Json:").removePrefix("@json:")
            Kind.REGEX -> rule.removePrefix(":")
            Kind.CSS -> rule.removePrefix("@@")
        }
    }
}
