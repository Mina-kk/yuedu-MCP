package com.mina.legadostudio.domain

/**
 * 抽取 `{{expression}}`，不用 `java.util.regex`。
 *
 * 已证实：Release 上相关类初始化失败会变成 [ExceptionInInitializerError]，
 * 之后同进程对该类是 [NoClassDefFoundError]。
 * 未证实（无 Android 首次异常 cause / 真机栈）：根因是 Android ICU 把未转义 `}` 当量词、
 * 静态编译 `{{...}}` 抛 [java.util.regex.PatternSyntaxException]。
 * 防护：生产路径不用静态花括号正则，改为字符串扫描；校验器把正则移出 `<clinit>`。
 */
object JsTemplates {
    data class Hit(val start: Int, val endExclusive: Int, val expression: String) {
        val range: IntRange get() = start until endExclusive
    }

    fun findAll(text: String): List<Hit> {
        if (text.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("{{", i)
            if (start < 0) break
            val end = text.indexOf("}}", start + 2)
            if (end < 0) break
            hits += Hit(start, end + 2, text.substring(start + 2, end))
            i = end + 2
        }
        return hits
    }
}
