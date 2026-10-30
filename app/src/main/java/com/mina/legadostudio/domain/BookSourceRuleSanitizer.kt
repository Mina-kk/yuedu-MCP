package com.mina.legadostudio.domain

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object BookSourceRuleSanitizer {
    private val CSS_OR_PREFIX = Regex("\\|\\|\\s*@css:", RegexOption.IGNORE_CASE)

    fun normalizeCssOr(rule: String): String {
        val trimmed = rule.trim()
        if (!trimmed.startsWith("@css:", true)) return trimmed
        return trimmed.replace(CSS_OR_PREFIX, "||")
    }

    /**
     * 真机严格模式告警：官方 AnalyzeRule.SourceRule.init 只在整条规则开头判一次模式
     * （@css:/@xpath:/@json:/@@），&&/||/%% 分支内的模式前缀不被识别——
     * 会按当前引擎字面量解析（如 JSoup 报 Could not parse query '@css:...'），
     * 即「沙箱能跑、真机报错/结果不同」的高危写法。检出后逐条给 warning，不改写规则。
     */
    fun strictModeWarnings(sourceJson: String): List<String> {
        val root = runCatching { JsonParser.parseString(sourceJson).asJsonObject }.getOrNull() ?: return emptyList()
        val warnings = mutableListOf<String>()
        val ruleFields = mapOf(
            "ruleSearch" to listOf("bookList", "name", "author", "bookUrl", "coverUrl", "intro", "kind", "lastChapter"),
            "ruleExplore" to listOf("bookList", "name", "author", "bookUrl", "coverUrl", "intro", "kind", "lastChapter"),
            "ruleBookInfo" to listOf("name", "author", "intro", "coverUrl", "tocUrl", "kind", "lastChapter", "wordCount"),
            "ruleToc" to listOf("chapterList", "chapterName", "chapterUrl", "nextTocUrl"),
            "ruleContent" to listOf("content", "nextContentUrl", "replaceRegex"),
        )
        // 整串开头的模式前缀（真机 SourceRule.init 的判定口径）
        val headPrefix = Regex("^\\s*(@css:|@xpath:|@json:|@@)", RegexOption.IGNORE_CASE)
        // 分支内的模式前缀：跟在 && / || / %% 之后
        val branchPrefix = Regex("(?:&&|\\|\\||%%)\\s*(@css:|@xpath:|@json:|@@)", RegexOption.IGNORE_CASE)
        for ((section, keys) in ruleFields) {
            val obj = root.getAsJsonObject(section) ?: continue
            for (key in keys) {
                val rule = obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                val branchHit = branchPrefix.find(rule)?.groupValues?.get(1)
                val headHit = headPrefix.find(rule)?.groupValues?.get(1)
                when {
                    branchHit != null && headHit == null ->
                        warnings += "$section.$key：规则以默认(JSoup)模式开头，但 &&/||/%% 分支内含模式前缀「$branchHit」——真机 SourceRule 只在整串开头判模式，分支内前缀会被当 JSoup 字面量解析并可能报 Could not parse query；请把「$branchHit」移到规则开头（整段统一该模式）或删除前缀；此为启发式告警，若阅读端已能正常解析则无需修改"
                    branchHit != null && headHit != null && !branchHit.equals(headHit, true) ->
                        warnings += "$section.$key：规则开头声明「$headHit」但分支内出现「$branchHit」——真机不会识别分支内前缀，请统一为开头声明的一种模式；此为启发式告警，若阅读端已能正常解析则无需修改"
                }
            }
        }
        return warnings
    }

    private fun sanitizeTransportHeaders(root: JsonObject) {
        val header = root.get("header") ?: return
        val obj = when {
            header.isJsonObject -> header.asJsonObject.deepCopy()
            header.isJsonPrimitive -> runCatching { JsonParser.parseString(header.asString).asJsonObject }.getOrNull()
            else -> null
        } ?: return
        obj.entrySet().map { it.key }.filter { it.equals("Accept-Encoding", ignoreCase = true) }.forEach(obj::remove)
        if (header.isJsonPrimitive) root.addProperty("header", obj.toString()) else root.add("header", obj)
    }

    fun sanitizeJson(json: String): String {
        val parsed = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return json
        if (!parsed.isJsonObject) return json
        val root = parsed.asJsonObject
        sanitizeTransportHeaders(root)
        fun fix(obj: JsonObject?, keys: List<String>) {
            if (obj == null) return
            for (key in keys) {
                val value = obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                val normalized = normalizeCssOr(value)
                if (normalized != value) obj.addProperty(key, normalized)
            }
        }
        fix(root.getAsJsonObject("ruleSearch"), listOf("bookList", "name", "author", "bookUrl", "coverUrl", "intro", "kind", "lastChapter"))
        fix(root.getAsJsonObject("ruleExplore"), listOf("bookList", "name", "author", "bookUrl", "coverUrl", "intro", "kind", "lastChapter"))
        fix(root.getAsJsonObject("ruleBookInfo"), listOf("name", "author", "intro", "coverUrl", "tocUrl", "kind", "lastChapter"))
        fix(root.getAsJsonObject("ruleToc"), listOf("chapterList", "chapterName", "chapterUrl", "nextTocUrl"))
        fix(root.getAsJsonObject("ruleContent"), listOf("content", "nextContentUrl"))
        val content = root.getAsJsonObject("ruleContent")
        val next = content?.get("nextContentUrl")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (next.contains("下一章") || next.contains("下一回")) {
            content.remove("nextContentUrl")
        }
        return root.toString()
    }
}
