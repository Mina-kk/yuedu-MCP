package com.mina.legadostudio.runtime

/**
 * Official AnalyzeRule string-rule subset:
 * - split `<js>` / `@js:` first
 * - then `rule##regex##replacement` / trailing `##` or `###` for replaceFirst
 * - empty rule + replaceRegex means "do not extract, replace current result"
 *
 * Ported from LegadoTeam/legado AnalyzeRule.SourceRule.makeUpRule + replaceRegex.
 */
object LegadoStringRule {
    private val jsPattern = Regex("<js>([\\s\\S]*?)</js>|@js:([\\s\\S]*)", RegexOption.IGNORE_CASE)

    enum class Mode { Default, Js }

    data class Part(
        val mode: Mode,
        val rule: String,
        val replaceRegex: String = "",
        val replacement: String = "",
        val replaceFirst: Boolean = false,
    )

    fun split(ruleStr: String): List<Part> {
        if (ruleStr.isEmpty()) return emptyList()
        val parts = mutableListOf<Part>()
        var start = 0
        for (match in jsPattern.findAll(ruleStr)) {
            if (match.range.first > start) {
                val prefix = ruleStr.substring(start, match.range.first).trim()
                if (prefix.isNotEmpty()) parts += parsePart(prefix, Mode.Default)
            }
            val js = match.groupValues[1].ifEmpty { match.groupValues[2] }
            parts += parsePart(js, Mode.Js)
            start = match.range.last + 1
        }
        if (start < ruleStr.length) {
            val suffix = ruleStr.substring(start).trim()
            if (suffix.isNotEmpty()) parts += parsePart(suffix, Mode.Default)
        }
        return parts
    }

    fun parsePart(raw: String, mode: Mode): Part {
        val pieces = raw.split("##")
        var replacement = pieces.getOrNull(2).orEmpty()
        var replaceFirst = pieces.size > 3
        if (replacement.endsWith("###")) {
            replaceFirst = true
            replacement = replacement.removeSuffix("###")
        }
        return Part(
            mode = mode,
            rule = pieces.firstOrNull()?.trim().orEmpty(),
            replaceRegex = pieces.getOrNull(1).orEmpty(),
            replacement = replacement,
            replaceFirst = replaceFirst,
        )
    }

    /** 元素预览用规则：取第一段（非 JS），并去掉尾部 lastRule 后缀。 */
    fun elementRule(ruleStr: String): String {
        val first = split(ruleStr).firstOrNull() ?: return ""
        if (first.mode == Mode.Js) return ""
        val cut = first.rule.lastIndexOf("@")
        return if (cut > 0) first.rule.take(cut) else first.rule
    }

    fun replace(result: String, part: Part): String {
        if (part.replaceRegex.isEmpty()) return result
        val regex = runCatching { Regex(part.replaceRegex) }.getOrNull()
        return if (part.replaceFirst) {
            if (regex == null) return part.replacement
            val match = regex.find(result) ?: return ""
            match.value.replaceFirst(regex, part.replacement)
        } else if (regex != null) {
            result.replace(regex, part.replacement)
        } else {
            result.replace(part.replaceRegex, part.replacement)
        }
    }

    fun unescapeHtml(value: String): String =
        if (value.contains('&')) org.jsoup.parser.Parser.unescapeEntities(value, false) else value
}
