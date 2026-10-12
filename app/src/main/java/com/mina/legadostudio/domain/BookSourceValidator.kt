package com.mina.legadostudio.domain

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BookSourceValidator {
    /**
     * 剥离 Legado 的 `#identity` 锚后缀（书源导出/交换格式允许 `baseUrl#任意标识`），
     * 返回真正可用于请求的 URL 主体。identity 可能是空串（`#` 结尾也算合法后缀）。
     * 只在字段值确实以 http(s) 开头时才剥——非 URL 字段原样返回。
     */
    private fun stripIdentity(url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        val idx = url.indexOf('#')
        return if (idx < 0) url else url.substring(0, idx)
    }

    /**
     * `bookSourceUrl` / `searchUrl` / `exploreUrl` / `loginUrl` 的 HTTP(S) 合法性校验。
     * searchUrl/exploreUrl 允许相对路径（运行时按 bookSourceUrl 解析）与 @js: 前缀，因此只查 http(s) 形态；
     * bookSourceUrl/loginUrl 是真实跳转基址，必须是可解析的绝对 http(s) URL。
     */
    private fun checkHttpUrl(path: String, raw: String, allowRelative: Boolean, issues: MutableList<Issue>) {
        if (raw.isBlank()) return
        if (raw.startsWith("@js:")) return // JS 模板由运行时求值，不在这条静态校验里展开
        val withoutIdentity = stripIdentity(raw)
        if (withoutIdentity.startsWith("http://") || withoutIdentity.startsWith("https://")) {
            if (withoutIdentity.toHttpUrlOrNull() == null) {
                issues += Issue(path, "不是合法的 HTTP(S) URL：$raw")
            }
            return
        }
        if (allowRelative) return // 相对路径留给真机 resolve 处理，不拦
        issues += Issue(path, "仅支持 HTTP/HTTPS URL（可带 #identity 后缀）：$raw")
    }

    /**
     * 字段类型检查：Legado 官方 JSON 里这些字段必须是字符串（或字符串化 JSON）。
     * 给成对象/数组/数字时真机解析会静默失败或崩溃，提前报 issue。
     */
    private fun checkStringField(obj: JsonObject, path: String, issues: MutableList<Issue>) {
        val el = obj.get(path) ?: return
        if (el.isJsonNull) return // null 等同未配置，不拦
        if (!el.isJsonPrimitive) issues += Issue(path, "字段类型必须是字符串（当前是 ${if (el.isJsonObject) "对象" else "数组"}）")
    }
    @Keep data class Issue(@SerializedName("path") val path: String, @SerializedName("message") val message: String)
    @Keep data class Report(
        @SerializedName("issues") val issues: List<Issue>,
        /** 非阻断提示：不影响 isValid。例如非文本源缺 ruleContent.content 只是「按资源取证」的说明。 */
        @SerializedName("warnings") val warnings: List<Issue> = emptyList(),
    ) {
        val isValid: Boolean get() = issues.isEmpty()
    }

    fun validate(json: String): Report {
        val root = try {
            JsonParser.parseString(json)
        } catch (error: Exception) {
            return Report(listOf(Issue("$", "JSON 无法解析：${error.message.orEmpty()}${malformedSnippet(json, error.message.orEmpty())}")))
        }
        if (!root.isJsonObject) return Report(listOf(Issue("$", "书源必须是 JSON 对象")))
        val obj = root.asJsonObject
        val issues = mutableListOf<Issue>()
        val warnings = mutableListOf<Issue>()
        requireText(obj, "bookSourceName", issues, "源名称不能为空")
        requireText(obj, "bookSourceUrl", issues, "源 URL 不能为空")
        // bookSourceType 字符串数字也容忍（真机按 int 读，给字符串会 ClassCastException）；范围 -1..4 之外才报
        val typeEl = obj.get("bookSourceType")
        var bookSourceType: Int? = null
        if (typeEl != null && !typeEl.isJsonNull && !typeEl.isJsonPrimitive) {
            issues += Issue("bookSourceType", "类型字段必须是数字（当前是 ${if (typeEl.isJsonObject) "对象" else "数组"}）")
        } else {
            bookSourceType = typeEl?.takeIf { it.isJsonPrimitive }?.let { el ->
                runCatching { el.asInt }.getOrElse {
                    issues += Issue("bookSourceType", "类型字段必须是数字（当前是字符串 \"$el\"）")
                    null
                }
            }
            if (bookSourceType != null && bookSourceType !in 0..4) issues += Issue("bookSourceType", "类型必须为 0..4")
        }

        // 关键字符串字段类型检查：配成对象/数组时真机按字符串读会崩或静默失效
        for (key in listOf("bookSourceUrl", "bookSourceName", "bookSourceGroup", "searchUrl", "exploreUrl", "loginUrl", "loginUi", "loginCheckJs", "header", "jsLib", "variableComment")) {
            checkStringField(obj, key, issues)
        }

        // URL 合法性：bookSourceUrl / loginUrl 必须是绝对 http(s)（可带 #identity）；
        // searchUrl / exploreUrl 允许相对路径（运行时 resolve）与 @js: 前缀。
        text(obj, "bookSourceUrl")?.let { checkHttpUrl("bookSourceUrl", it, allowRelative = false, issues) }
        text(obj, "loginUrl")?.let { checkHttpUrl("loginUrl", it, allowRelative = false, issues) }
        val searchUrl = text(obj, "searchUrl")
        searchUrl?.let { checkHttpUrl("searchUrl", it, allowRelative = true, issues) }
        val exploreUrl = text(obj, "exploreUrl")
        exploreUrl?.let { checkHttpUrl("exploreUrl", it, allowRelative = true, issues) }

        val ruleSearch = objectOrNull(obj, "ruleSearch")
        if (!searchUrl.isNullOrBlank()) {
            if (!searchUrl.contains("{{key}}") && !searchUrl.contains("key") && !searchUrl.startsWith("@js:")) {
                issues += Issue("searchUrl", "搜索 URL 应包含 {{key}} 或 JavaScript key")
            }
            if (ruleSearch == null || text(ruleSearch, "bookList").isNullOrBlank()) {
                issues += Issue("ruleSearch.bookList", "启用搜索时必须配置书籍列表规则")
            }
        }
        if (!exploreUrl.isNullOrBlank()) {
            val ruleExplore = objectOrNull(obj, "ruleExplore")
            if (ruleExplore == null || text(ruleExplore, "bookList").isNullOrBlank()) {
                issues += Issue("ruleExplore.bookList", "启用发现时必须配置书籍列表规则")
            }
        }

        val content = objectOrNull(obj, "ruleContent")
        val contentRuleMissing = content == null || text(content, "content").isNullOrBlank()
        if (contentRuleMissing) {
            // 非文本源（1..4 音频/图片/文件/视频）：正文常是资源 URL 或二进制，文字规则缺失不算硬错——
            // 只给非阻断 warning（isValid 不受影响），check_source 按资源 URL/二进制记录取证。
            if (bookSourceType != null && bookSourceType in 1..4) {
                warnings += Issue("ruleContent.content", "非文本源未配正文规则：正文将以资源 URL/二进制取证，文字字数不参与可用性判定")
            } else {
                issues += Issue("ruleContent.content", "正文规则不能为空")
            }
        }
        val toc = objectOrNull(obj, "ruleToc")
        if (toc == null || text(toc, "chapterList").isNullOrBlank()) {
            issues += Issue("ruleToc.chapterList", "目录列表规则不能为空")
        }
        return Report(issues, warnings)
    }

    private fun requireText(obj: JsonObject, key: String, issues: MutableList<Issue>, message: String) {
        if (text(obj, key).isNullOrBlank()) issues += Issue(key, message)
    }

    private fun text(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun objectOrNull(obj: JsonObject, key: String): JsonObject? =
        obj.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    /**
     * gson 报 "Unterminated object at line L column C" 时，按行号+列号定位到多行 JSON 的实际出错位置，
     * 回传错误行前后各一行并打 `^` 标出列位，让 save_source 的调用方能直接看见漏引号/漏反斜杠的位置，
     * 不用整包肉眼扫。旧实现只把 column 当全串偏移，多行 JSON 会指到错误的文字上。
     */
    private fun malformedSnippet(json: String, message: String): String {
        val lineNo = Regex("line (\\d+)").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val column = Regex("column (\\d+)").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return ""
        if (lineNo != null && lineNo > 0) {
            val lines = json.split('\n')
            if (lineNo <= lines.size) {
                val sb = StringBuilder("\n错误位置（第 $lineNo 行第 $column 列）：")
                val from = (lineNo - 1).coerceAtLeast(1)
                val to = (lineNo + 1).coerceAtMost(lines.size)
                for (i in from..to) {
                    // 出错行很长时以列为轴心截 ±140 字符，保证列附近内容一定被带回；普通行整行展示（160 上限）
                    val raw = lines[i - 1]
                    val (content, base) = if (i == lineNo && raw.length > 320) {
                        val s = (column - 1 - 140).coerceIn(0, raw.length)
                        val e = (column - 1 + 140).coerceAtMost(raw.length)
                        raw.substring(s, e) to s
                    } else {
                        val c = if (raw.length > 160) raw.take(160) + "…" else raw
                        c to 0
                    }
                    sb.append('\n').append(if (i == lineNo) ">>> " else "    ")
                        .append("第 ").append(i).append(" 行：").append(if (base > 0) "…$content…" else content)
                    if (i == lineNo && column > 0 && column - base <= content.length + 1) {
                        val caretPad = ">>> 第 $i 行：".length + (if (base > 0) 1 else 0) + (column - 1 - base).coerceAtLeast(0)
                        sb.append('\n').append(" ".repeat(caretPad)).append('^')
                    }
                }
                return sb.toString()
            }
        }
        // 没有行号信息时退回旧行为：把 column 当全串偏移，截取前后 140 字符
        val offset = (column - 1).coerceIn(0, json.length)
        val start = (offset - 140).coerceAtLeast(0)
        val end = (offset + 140).coerceAtMost(json.length)
        if (start >= end) return ""
        return "\n错误位置附近源码（第 $column 列前后 140 字符）：\n…${json.substring(start, end).replace("\n", "\\n")}…"
    }
}
