/* GPL-3.0: Kotlin/JVM adaptation of LegadoTeam HtmlFormatter.kt,
 * fixed commit 330e606c4d57e08adc056ff8ab7a4132c784da9a, lines 9-85.
 * Keep source rules unchanged; only replaceRegex receives per-line-trimmed text.
 */
package com.mina.legadostudio.domain

import com.mina.legadostudio.runtime.ReaderRequestTemplate
import java.util.regex.Pattern

object ContentPlainText {
    private val nbsp = Regex("(&nbsp;)+")
    private val esp = Regex("(&ensp;|&emsp;)")
    private val noPrint = Regex("(&thinsp;|&zwnj;|&zwj;|\u2009|\u200C|\u200D)")
    private val wrap = Regex("</?(?:div|p|br|hr|h\\d|article|dd|dl)[^>]*>")
    private val comment = Regex("<!--[^>]*-->")
    private val notImage = Regex("</?(?!img)[a-zA-Z]+(?=[ >])[^<>]*>")
    private val indent = Regex("\\s*\\n+\\s*")
    private val first = Regex("^[\\n\\s]+")
    private val last = Regex("[\\n\\s]+$")
    private val image = Pattern.compile(
        "<img[^>]*\\ssrc\\s*=\\s*['\"]([^'\"{>]*\\{(?:[^{}]|\\{[^}>]+\\})+\\})['\"][^>]*>|<img[^>]*\\sdata-(?:src|original|srcset)\\s*=\\s*['\"]([^'\">]+)['\"][^>]*>|<img[^>]*\\ssrc\\s*=\\s*\"([^\">]+)\"[^>]*>|<img[^>]*\\s(?:data-[^=>]*|src)=\\s*['\"]([^'\">]*)['\"][^>]*>",
        Pattern.CASE_INSENSITIVE,
    )

    @JvmOverloads
    fun formatKeepImg(html: String, baseUrl: String = "", trimLines: Boolean = true): String {
        val formatted = html.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
            .replace(nbsp, " ").replace(esp, " ").replace(noPrint, "")
            .replace(wrap, "\n").replace(comment, "").replace(notImage, "")
            .replace(indent, "\n　　").replace(first, "　　").replace(last, "")
        val matcher = image.matcher(formatted)
        val out = StringBuilder()
        var position = 0
        while (matcher.find()) {
            val target = matcher.group(1) ?: matcher.group(2) ?: matcher.group(3) ?: matcher.group(4)
            val option = ReaderRequestTemplate.splitUrlOption(target)
            val absolute = if (baseUrl.isBlank()) option.urlPart else ReaderRequestTemplate.absoluteUrl(baseUrl, option.urlPart)
            out.append(formatted, position, matcher.start()).append("<img src=\"")
                .append(absolute).append(option.optionText?.let { ",$it" }.orEmpty()).append("\">")
            position = matcher.end()
        }
        out.append(formatted, position, formatted.length)
        // AnalyzeRule unescapes after formatting; never strip the newly unescaped literal '<...>'.
        val text = org.jsoup.parser.Parser.unescapeEntities(out.toString(), false)
        return if (trimLines) text.lineSequence().joinToString("\n") { it.trim() } else text
    }
}
