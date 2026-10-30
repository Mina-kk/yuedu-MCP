package com.mina.legadostudio.domain

/**
 * 对齐阅读 [HtmlFormatter.formatKeepImg] + 逐行 trim 的正文预处理：
 * `replaceRegex` 作用在这段纯文本上，不是 `@html` 返回的 HTML。
 */
object ContentPlainText {
    private val IMG = Regex("(?i)<img\\b[^>]*>")
    private val BLOCK = Regex("(?i)</?(?:p|div|br|h[1-6]|tr|li|blockquote|section|article)\\b[^>]*>")
    private val TAG = Regex("<[^>]+>")

    fun formatKeepImg(html: String): String {
        if (html.isEmpty()) return html
        val images = ArrayList<String>()
        var text = IMG.replace(html) { match ->
            images += match.value
            "\uE000${images.lastIndex}\uE001"
        }
        text = BLOCK.replace(text, "\n")
        text = TAG.replace(text, "")
        text = org.jsoup.parser.Parser.unescapeEntities(text, false)
        images.forEachIndexed { index, img ->
            text = text.replace("\uE000$index\uE001", img)
        }
        return text.lineSequence().joinToString("\n") { it.trim() }
    }
}
