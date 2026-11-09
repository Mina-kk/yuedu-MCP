/*
 * Copyright (C) 2018-2026 LegadoTeam / gedoor
 *
 * This file is part of yuedu-MCP. The rule orchestration implemented here is a
 * Kotlin/JVM adaptation of LegadoTeam/legado (GPL-3.0); see LICENSE.
 *
 * Upstream origin (fixed commit):
 *   repo:   https://github.com/LegadoTeam/legado
 *   commit: 330e606c4d57e08adc056ff8ab7a4132c784da9a
 *   file:   app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt
 *   lines:  setContent 104-116, getStringList 205-296, getString 302-382,
 *           getElement 387-417, getElements 453-502, putRule 507-511,
 *           splitSourceRule 593-636, put/get 856-891, evalJS bindings 896-936
 *
 * The per-analyzer pieces live in io/legado/app/model/analyzeRule and are
 * adapted from the same commit. Deviations are marked "ADAPT:" and documented
 * in docs/research/rule-engine-implementation.md.
 */
package com.mina.legadostudio.runtime

import io.legado.app.model.analyzeRule.AnalyzeByJSoup
import io.legado.app.model.analyzeRule.AnalyzeByJSonPath
import io.legado.app.model.analyzeRule.AnalyzeByRegex
import io.legado.app.model.analyzeRule.AnalyzeByXPath
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import org.jsoup.nodes.Element
import java.net.URL

/**
 * 上游 `AnalyzeRule` 的可移植解析核：`@put/@get`、`{{规则}}`/`{{JS}}`、`$n` 捕获组、
 * `##替换`、多段 JS、typed 结果（Element / JXNode / JSON Map / regex capture List）。
 *
 * 不依赖 Android，也不做网络与 JS 引擎实现：
 * - JS 由 [evalJs] / [evalWebJs] 回调注入（由 runtime worker 接到 RhinoEvaluator）；
 * - 变量由 [getVariable] / [putVariable] 回调注入（chapter→book→ruleData→source 层级的实现方）；
 * - 平台缺失能力抛 [UnsupportedOperationException]，绝不静默返回假值。
 *
 * 线程模型与上游一致：实例非线程安全，一次解析链用一个实例。
 */
class ReaderRuleSession(
    content: Any?,
    baseUrl: String = "",
    private val engine: LegadoRuleEngine = LegadoRuleEngine(),
    private val evalJs: (script: String, result: Any?) -> Any? = { _, _ ->
        throw UnsupportedOperationException("JavaScript evaluator not configured")
    },
    private val evalWebJs: ((script: String, result: Any?) -> Any?)? = null,
    private val getVariable: (String) -> String = { "" },
    private val putVariable: (String, String) -> Unit = { _, _ -> },
    /** 覆盖 `bookName` / `title` 特殊键（上游 AnalyzeRule.get 875-891） */
    private val bookNameProvider: () -> String? = { null },
    private val chapterTitleProvider: () -> String? = { null },
) {
    /** 原始内容（上游 `content`）。JS 里的 `src` 始终是它，不是链中上一段结果。 */
    var content: Any? = null
        private set

    /** 当前页地址（上游 `baseUrl` / `redirectUrl`） */
    var baseUrl: String = ""
        private set

    private var isJSON: Boolean = false
    private var redirectUrl: URL? = null

    private var analyzeByXPath: AnalyzeByXPath? = null
    private var analyzeByJSoup: AnalyzeByJSoup? = null
    private var analyzeByJSonPath: AnalyzeByJSonPath? = null

    private val stringRuleCache = HashMap<String, List<ReaderRulePart>>()
    private val localBindings = HashMap<String, String>()

    /** 解析过程中的错误回调；null 时异常按上游语义吞掉并返回空值 */
    var onError: ((Exception) -> Unit)? = null

    init {
        setContent(content, baseUrl.ifEmpty { null })
    }

    /**
     * 上游 AnalyzeRule.setContent(104-116)：内容不可空，JSON 判定只看内容本身
     * （Element / JXNode 等节点不算 JSON）。
     */
    @JvmOverloads
    fun setContent(content: Any?, baseUrl: String? = null): ReaderRuleSession {
        requireNotNull(content) { "内容不可空（Content cannot be null）" }
        this.content = content
        isJSON = when (content) {
            is Element -> false
            else -> LegadoRuleEngine.isJsonContent(content)
        }
        setBaseUrl(baseUrl)
        analyzeByXPath = null
        analyzeByJSoup = null
        analyzeByJSonPath = null
        return this
    }

    fun setBaseUrl(baseUrl: String?): ReaderRuleSession {
        baseUrl?.let {
            this.baseUrl = it
            redirectUrl = runCatching { URL(it) }.getOrNull()
        }
        return this
    }

    fun setLocal(key: String, value: String): ReaderRuleSession {
        localBindings[key] = value
        return this
    }

    // ---------------------------------------------------------------- getString

    /**
     * 上游 AnalyzeRule.getString(302-382)。
     *
     * @param isUrl 为 true 时只取首个 DOM 结果（getString0），空结果回退 [baseUrl]，否则拼绝对地址。
     * @param unescape 结果含 `&` 时做 HTML4 反转义。
     */
    @JvmOverloads
    fun getString(
        rule: String?,
        content: Any? = null,
        isUrl: Boolean = false,
        unescape: Boolean = true,
    ): String {
        if (rule.isNullOrEmpty()) return ""
        return getString(splitSourceRuleCached(rule), content, isUrl, unescape)
    }

    @JvmOverloads
    fun getString(
        ruleList: List<ReaderRulePart>,
        content: Any? = null,
        isUrl: Boolean = false,
        unescape: Boolean = true,
    ): String {
        var result: Any? = null
        val source = content ?: this.content
        if (source != null && ruleList.isNotEmpty()) {
            result = source
            if (isJsObject(result)) {
                // ADAPT: 上游对 NativeObject（JS 对象）走键值/JS 分派；这里用同一套"首段规则"语义，
                // 但对象本身由调用方保证是 Map（JS 对象不预先 stringify）。
                val part = ruleList.first()
                putRule(part.putMap)
                part.makeUpRule(result, ::evalJsInternal, ::getString, ::get)
                result = when {
                    part.mode == ReaderRulePart.Mode.Js -> evalJsInternal(part.rule, result)
                    part.mode == ReaderRulePart.Mode.Json ->
                        jsonAnalyzer(result).getString(part.rule)
                    part.paramSize > 1 -> part.rule
                    else -> (result as Map<*, *>)[part.rule]
                }?.let { replaceRegex(it.toString(), part) }
            } else {
                for (part in ruleList) {
                    putRule(part.putMap)
                    part.makeUpRule(result, ::evalJsInternal, ::getString, ::get)
                    result ?: continue
                    val current = part.rule
                    if (current.isNotBlank() || part.replaceRegex.isEmpty()) {
                        result = when (part.mode) {
                            ReaderRulePart.Mode.WebJs -> evalWebJsInternal(current, result)
                            ReaderRulePart.Mode.Js -> evalJsInternal(current, result)
                            ReaderRulePart.Mode.Json -> jsonAnalyzer(result).getString(current)
                            ReaderRulePart.Mode.XPath -> xpathAnalyzer(result).getString(current)
                            // 上游 getString 的 when 不含 Regex（AnalyzeRule.kt:347-359）：`:正则` 规则在
                            // getString 路径上原样返回规则字面量，不取首个匹配。见 implementation 文档差异表。
                            ReaderRulePart.Mode.Regex -> current
                            ReaderRulePart.Mode.Default -> if (isUrl) {
                                jsoupAnalyzer(result).getString0(current)
                            } else {
                                jsoupAnalyzer(result).getString(current)
                            }
                        }
                    }
                    if (result != null && part.replaceRegex.isNotEmpty()) {
                        result = replaceRegex(result.toString(), part)
                    }
                }
            }
        }
        if (result == null) result = ""
        val resultStr = result.toString()
        val str = if (unescape && resultStr.indexOf('&') > -1) {
            unescapeHtml4(resultStr)
        } else {
            resultStr
        }
        if (isUrl) {
            return if (str.isBlank()) {
                baseUrl
            } else {
                absoluteUrl(str)
            }
        }
        return str
    }

    // ------------------------------------------------------------ getStringList

    /**
     * 上游 AnalyzeRule.getStringList(205-296)。最终 String 按 `\n` 拆行；isUrl 时解析绝对地址并去重。
     */
    @JvmOverloads
    fun getStringList(
        rule: String?,
        content: Any? = null,
        isUrl: Boolean = false,
    ): List<String>? {
        if (rule.isNullOrEmpty()) return null
        return getStringList(splitSourceRuleCached(rule), content, isUrl)
    }

    @JvmOverloads
    fun getStringList(
        ruleList: List<ReaderRulePart>,
        content: Any? = null,
        isUrl: Boolean = false,
    ): List<String>? {
        var result: Any? = null
        val source = content ?: this.content
        if (source != null && ruleList.isNotEmpty()) {
            result = source
            if (isJsObject(result)) {
                val part = ruleList.first()
                putRule(part.putMap)
                part.makeUpRule(result, ::evalJsInternal, ::getString, ::get)
                result = when {
                    part.mode == ReaderRulePart.Mode.Js -> evalJsInternal(part.rule, result)
                    part.mode == ReaderRulePart.Mode.Json -> jsonAnalyzer(result).getStringList(part.rule)
                    part.paramSize > 1 -> part.rule
                    else -> (result as Map<*, *>)[part.rule]
                }
                result?.let {
                    if (part.replaceRegex.isNotEmpty() && it is List<*>) {
                        result = it.map { item -> replaceRegex(item.toString(), part) }
                    } else if (part.replaceRegex.isNotEmpty()) {
                        result = replaceRegex(result.toString(), part)
                    }
                }
            } else {
                for (part in ruleList) {
                    putRule(part.putMap)
                    part.makeUpRule(result, ::evalJsInternal, ::getString, ::get)
                    result ?: continue
                    val current = part.rule
                    if (current.isNotEmpty()) {
                        result = when (part.mode) {
                            ReaderRulePart.Mode.WebJs -> evalWebJsInternal(current, result)
                            ReaderRulePart.Mode.Js -> evalJsInternal(current, result)
                            ReaderRulePart.Mode.Json -> jsonAnalyzer(result).getStringList(current)
                            ReaderRulePart.Mode.XPath -> xpathAnalyzer(result).getStringList(current)
                            ReaderRulePart.Mode.Regex -> AnalyzeByRegex.getElements(
                                stringify(result),
                                current.split("&&").filter { it.isNotBlank() }.toTypedArray(),
                            )
                            ReaderRulePart.Mode.Default -> jsoupAnalyzer(result).getStringList(current)
                        }
                    }
                    if (part.replaceRegex.isNotEmpty() && result is List<*>) {
                        val newList = ArrayList<String>()
                        for (item in result) {
                            newList.add(replaceRegex(item.toString(), part))
                        }
                        result = newList
                    } else if (part.replaceRegex.isNotEmpty()) {
                        result = replaceRegex(result.toString(), part)
                    }
                }
            }
        }
        if (result == null) return null
        if (result is String) {
            result = result.split("\n")
        }
        if (isUrl) {
            val urlList = ArrayList<String>()
            if (result is List<*>) {
                for (url in result) {
                    val absoluteURL = absoluteUrl(url.toString())
                    if (absoluteURL.isNotEmpty() && !urlList.contains(absoluteURL)) {
                        urlList.add(absoluteURL)
                    }
                }
            }
            return urlList
        }
        @Suppress("UNCHECKED_CAST")
        return result as? List<String>
    }

    // ---------------------------------------------------------------- getElement

    /** 上游 AnalyzeRule.getElement(387-417)。空规则返回 null。 */
    fun getElement(rule: String): Any? {
        if (rule.isEmpty()) return null
        var result: Any? = null
        val source = this.content
        val ruleList = splitSourceRule(rule, allInOne = true)
        if (source != null && ruleList.isNotEmpty()) {
            result = source
            for (part in ruleList) {
                putRule(part.putMap)
                part.makeUpRule(result, ::evalJsInternal, ::getString, ::get)
                result ?: continue
                val current = part.rule
                result = when (part.mode) {
                    ReaderRulePart.Mode.Regex -> AnalyzeByRegex.getElement(
                        stringify(result),
                        current.split("&&").filter { it.isNotBlank() }.toTypedArray(),
                    )
                    ReaderRulePart.Mode.WebJs -> evalWebJsInternal(current, result)
                    ReaderRulePart.Mode.Js -> evalJsInternal(current, result)
                    ReaderRulePart.Mode.Json -> jsonAnalyzer(result).getObject(current)
                    ReaderRulePart.Mode.XPath -> xpathAnalyzer(result).getElements(current)
                    ReaderRulePart.Mode.Default -> jsoupAnalyzer(result).getElements(current)
                }
                if (part.replaceRegex.isNotEmpty()) {
                    result = replaceRegex(result.toString(), part)
                }
            }
        }
        return result
    }

    // --------------------------------------------------------------- getElements

    /**
     * 上游 AnalyzeRule.getElements(453-502)：非列表（标量）结果返回空列表，不包成单元素。
     * 列表元素保留 typed 形态（Element / JXNode / JSON Map / regex capture List），不预先字符串化。
     */
    fun getElements(rule: String): List<Any> {
        var result: Any? = null
        val source = this.content
        val ruleList = splitSourceRule(rule, allInOne = true)
        if (source != null && ruleList.isNotEmpty()) {
            result = source
            for (part in ruleList) {
                putRule(part.putMap)
                result ?: continue
                val current = part.rule
                result = when (part.mode) {
                    ReaderRulePart.Mode.Regex -> AnalyzeByRegex.getElements(
                        stringify(result),
                        current.split("&&").filter { it.isNotBlank() }.toTypedArray(),
                    )
                    ReaderRulePart.Mode.WebJs -> evalWebJsInternal(current, result)
                    ReaderRulePart.Mode.Js -> evalJsInternal(current, result)
                    ReaderRulePart.Mode.Json -> jsonAnalyzer(result).getList(current)
                    ReaderRulePart.Mode.XPath -> xpathAnalyzer(result).getElements(current)
                    ReaderRulePart.Mode.Default -> jsoupAnalyzer(result).getElements(current)
                }
            }
        }
        return when (val value = result) {
            is List<*> -> value.filterNotNull()
            is Array<*> -> value.filterNotNull()
            else -> ArrayList()
        }
    }

    // ------------------------------------------------------------- variables

    /** 上游 AnalyzeRule.put(856-865)：写入当前层级变量，返回 value。 */
    fun put(key: String, value: String): String {
        putVariable(key, value)
        return value
    }

    /** 上游 AnalyzeRule.get(875-891)：本地绑定 → bookName/title → 层级变量，取首个非空。 */
    fun get(key: String): String {
        localBindings[key]?.let { return it }
        when (key) {
            "bookName" -> bookNameProvider()?.let { return it }
            "title" -> chapterTitleProvider()?.let { return it }
        }
        return getVariable(key)
    }

    /** 上游 putRule(507-511)：`@put` 的值本身是规则，用 getString 求值后写入。 */
    private fun putRule(map: Map<String, String>) {
        for ((key, value) in map) {
            put(key, getString(value))
        }
    }

    // --------------------------------------------------------------- rule split

    fun splitSourceRule(ruleStr: String?, allInOne: Boolean = false): List<ReaderRulePart> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        val ruleList = ArrayList<ReaderRulePart>()
        var mMode = ReaderRulePart.Mode.Default
        var start = 0
        // 仅首字符为 : 时为 AllInOne（上游 598-605）
        if (allInOne && ruleStr.startsWith(":")) {
            mMode = ReaderRulePart.Mode.Regex
            start = 1
        }
        var tmp: String
        val jsMatcher = ReaderRulePart.JS_PATTERN.matcher(ruleStr)
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                tmp = ruleStr.substring(start, jsMatcher.start()).trim { it <= ' ' }
                if (tmp.isNotEmpty()) {
                    ruleList.add(ReaderRulePart(tmp, mMode, isJSON))
                }
            }
            ruleList.add(
                ReaderRulePart(
                    jsMatcher.group(2) ?: jsMatcher.group(1) ?: "",
                    ReaderRulePart.Mode.Js,
                    isJSON,
                )
            )
            start = jsMatcher.end()
        }
        val webJsMatcher = ReaderRulePart.WEB_JS_PATTERN.matcher(ruleStr)
        while (webJsMatcher.find()) {
            if (webJsMatcher.start() > start) {
                tmp = ruleStr.substring(start, webJsMatcher.start()).trim { it <= ' ' }
                if (tmp.isNotEmpty()) {
                    ruleList.add(ReaderRulePart(tmp, mMode, isJSON))
                }
            }
            ruleList.add(
                ReaderRulePart(
                    webJsMatcher.group(1) ?: "",
                    ReaderRulePart.Mode.WebJs,
                    isJSON,
                )
            )
            start = webJsMatcher.end()
        }
        if (ruleStr.length > start) {
            tmp = ruleStr.substring(start).trim { it <= ' ' }
            if (tmp.isNotEmpty()) {
                ruleList.add(ReaderRulePart(tmp, mMode, isJSON))
            }
        }
        return ruleList
    }

    private fun splitSourceRuleCached(ruleStr: String): List<ReaderRulePart> =
        stringRuleCache.getOrPut(ruleStr) { splitSourceRule(ruleStr) }

    // ------------------------------------------------------------- analyzers

    private fun jsoupAnalyzer(o: Any): AnalyzeByJSoup =
        if (o != content) {
            AnalyzeByJSoup(o)
        } else {
            analyzeByJSoup ?: AnalyzeByJSoup(content!!).also { analyzeByJSoup = it }
        }

    private fun xpathAnalyzer(o: Any): AnalyzeByXPath =
        if (o != content) {
            AnalyzeByXPath(o)
        } else {
            analyzeByXPath ?: AnalyzeByXPath(content!!).also { analyzeByXPath = it }
        }

    private fun jsonAnalyzer(o: Any): AnalyzeByJSonPath =
        if (o != content) {
            AnalyzeByJSonPath(jsonContent(o), onError)
        } else {
            analyzeByJSonPath
                ?: AnalyzeByJSonPath(jsonContent(content!!), onError).also { analyzeByJSonPath = it }
        }

    // ------------------------------------------------------------------ JS

    private fun evalJsInternal(script: String, result: Any?): Any? = evalJs(script, result)

    private fun evalWebJsInternal(script: String, result: Any?): Any? {
        val evaluator = evalWebJs
            ?: throw UnsupportedOperationException(
                "@webjs 需要 WebView 平台能力，当前运行环境未配置（script=${script.take(40)}）"
            )
        return evaluator(script, result)
    }

    // ------------------------------------------------------------- helpers

    private fun isJsObject(value: Any?): Boolean = value is Map<*, *>

    private fun stringify(value: Any): String = when (value) {
        is String -> value
        is Element -> value.outerHtml()
        is Map<*, *>, is List<*> -> LegadoRuleEngine.toJson(value)
        else -> value.toString()
    }

    private fun jsonContent(value: Any): Any = when (value) {
        is Map<*, *>, is List<*> -> value
        else -> stringify(value)
    }

    /**
     * 上游 AnalyzeRule.replaceRegex(544-568)：`##match##replacement` 全替换，
     * 四段 `##match##replacement##` 时只替换首个匹配（group(0) 内 replaceFirst）。
     */
    private fun replaceRegex(result: String, part: ReaderRulePart): String {
        if (part.replaceRegex.isEmpty()) return result
        val regex = runCatching { Regex(part.replaceRegex) }.getOrNull()
        if (part.replaceFirst) {
            if (regex != null) {
                runCatching {
                    val matcher = regex.toPattern().matcher(result)
                    return if (matcher.find()) {
                        matcher.group(0)!!.replaceFirst(regex, part.replacement)
                    } else {
                        ""
                    }
                }
            }
            return part.replacement
        }
        if (regex != null) {
            runCatching { return result.replace(regex, part.replacement) }
        }
        return result.replace(part.replaceRegex, part.replacement)
    }

    /**
     * 上游 NetworkUtils.getAbsoluteURL(URL?, String)：
     * 绝对地址/data URL 原样返回，`javascript` 开头返回空串，其余按 baseUrl 解析；
     * baseUrl 为空时只 trim。解析失败返回原相对地址。
     */
    fun absoluteUrl(relativePath: String): String {
        val trimmed = relativePath.trim()
        val base = redirectUrl ?: return trimmed
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        if (DATA_URI.matches(trimmed)) return trimmed
        if (trimmed.startsWith("javascript")) return ""
        return runCatching { URL(base, relativePath).toString() }.getOrDefault(trimmed)
    }

    private companion object {
        val DATA_URI = Regex("^data:.*?;base64,(.*)")

        /**
         * ADAPT: 上游用 commons-text StringEscapeUtils.unescapeHtml4（AnalyzeRule.kt:369-373）；
         * 本模块不引入新依赖，这里按 HTML4 命名实体表实现同一语义（仅处理 `&xxx;` 形式）。
         * 未收录的实体原样保留，与 commons-text 的宽松行为一致。
         */
        private val NAMED_ENTITIES: Map<String, String> = buildMap {
            put("amp", "&"); put("lt", "<"); put("gt", ">"); put("quot", "\""); put("apos", "'")
            put("nbsp", "\u00a0"); put("iexcl", "\u00a1"); put("cent", "\u00a2"); put("pound", "\u00a3")
            put("curren", "\u00a4"); put("yen", "\u00a5"); put("brvbar", "\u00a6"); put("sect", "\u00a7")
            put("uml", "\u00a8"); put("copy", "\u00a9"); put("ordf", "\u00aa"); put("laquo", "\u00ab")
            put("not", "\u00ac"); put("shy", "\u00ad"); put("reg", "\u00ae"); put("macr", "\u00af")
            put("deg", "\u00b0"); put("plusmn", "\u00b1"); put("sup2", "\u00b2"); put("sup3", "\u00b3")
            put("acute", "\u00b4"); put("micro", "\u00b5"); put("para", "\u00b6"); put("middot", "\u00b7")
            put("cedil", "\u00b8"); put("sup1", "\u00b9"); put("ordm", "\u00ba"); put("raquo", "\u00bb")
            put("frac14", "\u00bc"); put("frac12", "\u00bd"); put("frac34", "\u00be"); put("iquest", "\u00bf")
            put("times", "\u00d7"); put("divide", "\u00f7")
            put("ndash", "\u2013"); put("mdash", "\u2014"); put("lsquo", "\u2018"); put("rsquo", "\u2019")
            put("sbquo", "\u201a"); put("ldquo", "\u201c"); put("rdquo", "\u201d"); put("bdquo", "\u201e")
            put("dagger", "\u2020"); put("Dagger", "\u2021"); put("bull", "\u2022"); put("hellip", "\u2026")
            put("permil", "\u2030"); put("prime", "\u2032"); put("Prime", "\u2033"); put("lsaquo", "\u2039")
            put("rsaquo", "\u203a"); put("oline", "\u203e"); put("frasl", "\u2044"); put("euro", "\u20ac")
            put("trade", "\u2122"); put("larr", "\u2190"); put("uarr", "\u2191"); put("rarr", "\u2192")
            put("darr", "\u2193"); put("harr", "\u2194"); put("spades", "\u2660"); put("clubs", "\u2663")
            put("hearts", "\u2665"); put("diams", "\u2666"); put("loz", "\u25ca")
            // Latin-1 大写带重音
            val latin1 = "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecir" +
                "c Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml Oslash" +
                " Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring" +
                " aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve" +
                " oacute ocirc otilde ouml oslash ugrave uacute ucirc uuml yacute thorn yuml"
            var code = 0xC0
            for (name in latin1.split(" ")) {
                if (code == 0xD7 || code == 0xF7) code++ // × 与 ÷ 单独列出
                put(name, code.toChar().toString())
                code++
            }
        }

        private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")

        internal fun unescapeHtml4(value: String): String =
            ENTITY.replace(value) { m ->
                val body = m.groupValues[1]
                when {
                    body.startsWith("#x") || body.startsWith("#X") ->
                        body.substring(2).toIntOrNull(16)?.let { codePointToString(it) } ?: m.value
                    body.startsWith("#") ->
                        body.substring(1).toIntOrNull()?.let { codePointToString(it) } ?: m.value
                    else -> NAMED_ENTITIES[body] ?: m.value
                }
            }

        private fun codePointToString(codePoint: Int): String? =
            if (codePoint in 0..0x10FFFF) String(Character.toChars(codePoint)) else null
    }
}
