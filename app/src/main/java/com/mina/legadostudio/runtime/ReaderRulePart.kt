/*
 * Copyright (C) 2018-2026 LegadoTeam / gedoor
 *
 * This file is part of yuedu-MCP and is a Kotlin/JVM adaptation of
 * LegadoTeam/legado (GPL-3.0) source. It is distributed under the terms of
 * the GNU General Public License v3.0 (see LICENSE).
 *
 * Upstream origin (fixed commit):
 *   repo:   https://github.com/LegadoTeam/legado
 *   commit: 330e606c4d57e08adc056ff8ab7a4132c784da9a
 *   file:   app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt
 *   lines:  splitSourceRule 593-636, SourceRule 647-847, replaceRegex 544-568,
 *           putRule 507-511, splitPutRule 516-539, companion patterns 1027-1030
 *
 * Deviations from upstream are marked with "ADAPT:" and documented in
 * docs/research/rule-engine-implementation.md.
 */
package com.mina.legadostudio.runtime

import com.google.gson.Gson
import java.util.Locale
import java.util.regex.Pattern

/**
 * 上游 `AnalyzeRule.SourceRule`：一条规则串（`@put:` / `@get:` / `{{}}` / `$n` / `##替换` /
 * `@js:` / `@webjs:` / 模式前缀）的解析结果。
 *
 * 规则串被解析一次，之后每个内容阶段调用 [makeUpRule] 把 `{{}}`、`@get:`、`$n` 按当阶段
 * result 重新展开（上游 AnalyzeRule.kt:776-835）。
 */
class ReaderRulePart internal constructor(
    ruleStr: String,
    internal var mode: ReaderRulePart.Mode = Mode.Default,
    /** 内容本身是 JSON 文本时，无前缀规则按 JsonPath 处理（上游 `isJSON`，AnalyzeRule.kt:685） */
    private val isJsonContent: Boolean = false,
) {
    enum class Mode { XPath, Json, Default, Js, Regex, WebJs }

    /** 上游 `rule`：模式前缀与 `@put:` 已剥离，`{{}}`/`@get:`/`$n` 尚未展开 */
    var rule: String
        private set

    var replaceRegex: String = ""
        private set
    var replacement: String = ""
        private set
    var replaceFirst: Boolean = false
        private set

    /** `@put:{"k":"rule"}` 解析出的键值，值本身还是规则（上游 putRule 用 getString 求值） */
    val putMap = HashMap<String, String>()

    private val ruleParam = ArrayList<String>()
    private val ruleType = ArrayList<Int>()

    init {
        rule = when {
            mode == Mode.Js || mode == Mode.Regex -> ruleStr
            // 上游保留 @CSS: 前缀，由 AnalyzeByJSoup.SourceRule 再剥离（AnalyzeRule.kt:665-669）
            ruleStr.startsWith("@CSS:", true) -> {
                mode = Mode.Default
                ruleStr
            }
            ruleStr.startsWith("@@") -> {
                mode = Mode.Default
                ruleStr.substring(2)
            }
            ruleStr.startsWith("@XPath:", true) -> {
                mode = Mode.XPath
                ruleStr.substring(7)
            }
            ruleStr.startsWith("@Json:", true) -> {
                mode = Mode.Json
                ruleStr.substring(6)
            }
            isJsonContent || ruleStr.startsWith("$.") || ruleStr.startsWith("$[") -> {
                mode = Mode.Json
                ruleStr
            }
            ruleStr.startsWith("/") -> {
                mode = Mode.XPath
                ruleStr
            }
            else -> ruleStr
        }
        rule = splitPutRule(rule, putMap)
        // @get、{{ }} 拆分
        var start = 0
        var tmp: String
        val evalMatcher = evalPattern.matcher(rule)
        if (evalMatcher.find()) {
            tmp = rule.substring(start, evalMatcher.start())
            if (mode != Mode.Js && mode != Mode.Regex &&
                (evalMatcher.start() == 0 || !tmp.contains("##"))
            ) {
                mode = Mode.Regex
            }
            do {
                if (evalMatcher.start() > start) {
                    tmp = rule.substring(start, evalMatcher.start())
                    splitRegex(tmp)
                }
                tmp = evalMatcher.group()
                when {
                    tmp.startsWith("@get:", true) -> {
                        ruleType.add(GET_RULE_TYPE)
                        ruleParam.add(tmp.substring(6, tmp.lastIndex))
                    }
                    tmp.startsWith("{{") -> {
                        ruleType.add(JS_RULE_TYPE)
                        ruleParam.add(tmp.substring(2, tmp.length - 2))
                    }
                    else -> splitRegex(tmp)
                }
                start = evalMatcher.end()
            } while (evalMatcher.find())
        }
        if (rule.length > start) {
            tmp = rule.substring(start)
            splitRegex(tmp)
        }
    }

    /** 拆分 `$1`..`$99` 捕获组引用（AnalyzeRule.kt:744-771） */
    private fun splitRegex(ruleStr: String) {
        var start = 0
        var tmp: String
        val ruleStrArray = ruleStr.split("##")
        val regexMatcher = regexPattern.matcher(ruleStrArray[0])
        if (regexMatcher.find()) {
            if (mode != Mode.Js && mode != Mode.Regex) {
                mode = Mode.Regex
            }
            do {
                if (regexMatcher.start() > start) {
                    tmp = ruleStr.substring(start, regexMatcher.start())
                    ruleType.add(DEFAULT_RULE_TYPE)
                    ruleParam.add(tmp)
                }
                tmp = regexMatcher.group()
                ruleType.add(tmp.substring(1).toInt())
                ruleParam.add(tmp)
                start = regexMatcher.end()
            } while (regexMatcher.find())
        }
        if (ruleStr.length > start) {
            tmp = ruleStr.substring(start)
            ruleType.add(DEFAULT_RULE_TYPE)
            ruleParam.add(tmp)
        }
    }

    /**
     * 按当阶段 result 展开 `@get:` / `{{}}` / `$n`，再切出 `##` 替换段。
     *
     * 上游 AnalyzeRule.kt:776-835。`{{}}` 里若是规则（`@`/`$.`/`$[`/`//` 开头）走 getString，
     * 否则当 JS 求值；`$n` 只对 List 生效，非 List 时保留字面量。
     */
    fun makeUpRule(
        result: Any?,
        evalJs: (String, Any?) -> Any?,
        getString: (String) -> String,
        get: (String) -> String,
    ) {
        val infoVal = StringBuilder()
        if (ruleParam.isNotEmpty()) {
            var index = ruleParam.size
            while (index-- > 0) {
                val regType = ruleType[index]
                when {
                    regType > DEFAULT_RULE_TYPE -> {
                        val list = result as? List<*>
                        if (list != null) {
                            if (list.size > regType) {
                                val item = list[regType]
                                if (item != null) infoVal.insert(0, item.toString())
                            }
                        } else {
                            infoVal.insert(0, ruleParam[index])
                        }
                    }
                    regType == JS_RULE_TYPE -> {
                        val param = ruleParam[index]
                        if (isRule(param)) {
                            infoVal.insert(0, getString(param))
                        } else {
                            when (val jsEval: Any? = evalJs(param, result)) {
                                null -> Unit
                                is String -> infoVal.insert(0, jsEval)
                                is Double -> {
                                    if (jsEval % 1.0 == 0.0) {
                                        infoVal.insert(0, String.format(Locale.ROOT, "%.0f", jsEval))
                                    } else {
                                        infoVal.insert(0, jsEval.toString())
                                    }
                                }
                                else -> infoVal.insert(0, jsEval.toString())
                            }
                        }
                    }
                    regType == GET_RULE_TYPE -> infoVal.insert(0, get(ruleParam[index]))
                    else -> infoVal.insert(0, ruleParam[index])
                }
            }
            rule = infoVal.toString()
        }
        val ruleStrS = rule.split("##")
        rule = ruleStrS[0].trim()
        if (ruleStrS.size > 1) {
            replaceRegex = ruleStrS[1]
        }
        if (ruleStrS.size > 2) {
            replacement = ruleStrS[2]
        }
        if (ruleStrS.size > 3) {
            replaceFirst = true
        }
    }

    val paramSize: Int get() = ruleParam.size

    private fun isRule(ruleStr: String): Boolean =
        ruleStr.startsWith('@') ||
            ruleStr.startsWith("$.") ||
            ruleStr.startsWith("$[") ||
            ruleStr.startsWith("//")

    companion object {
        private const val GET_RULE_TYPE = -2
        private const val JS_RULE_TYPE = -1
        private const val DEFAULT_RULE_TYPE = 0

        /** 上游 AppPattern.JS_PATTERN（constant/AppPattern.kt） */
        val JS_PATTERN: Pattern =
            Pattern.compile("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", Pattern.CASE_INSENSITIVE)

        /** 上游 AppPattern.WebJS_PATTERN */
        val WEB_JS_PATTERN: Pattern =
            Pattern.compile("@webjs:([\\w\\W]{5,})", Pattern.CASE_INSENSITIVE)

        private val putPattern =
            Pattern.compile("@put:(\\{[^}]+?\\})", Pattern.CASE_INSENSITIVE)
        private val evalPattern =
            Pattern.compile("@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}", Pattern.CASE_INSENSITIVE)
        private val regexPattern = Pattern.compile("\\$\\d{1,2}")

        private val gson = Gson()

        /**
         * 分离 `@put:{...}`（AnalyzeRule.kt:516-539）。
         *
         * ADAPT: 上游用 GSONStrict 再退 GSON 解析，这里直接用 Gson 解析成 Map<String,String>；
         * 非标准 JSON（单引号等）解析失败时忽略该 put 段，与上游"宽松解析失败即跳过"一致。
         */
        internal fun splitPutRule(ruleStr: String, putMap: HashMap<String, String>): String {
            var vRuleStr = ruleStr
            val putMatcher = putPattern.matcher(vRuleStr)
            while (putMatcher.find()) {
                vRuleStr = vRuleStr.replace(putMatcher.group(), "")
                val putJsonStr = putMatcher.group(1)
                val putJson = try {
                    gson.fromJson(putJsonStr, Map::class.java)
                } catch (_: Exception) {
                    null
                }
                if (putJson != null) {
                    for ((key, value) in putJson) {
                        putMap[key.toString()] = value?.toString().orEmpty()
                    }
                }
            }
            return vRuleStr
        }
    }
}
