package com.mina.legadostudio.domain

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class BookSourceValidator {
    @Keep data class Issue(@SerializedName("path") val path: String, @SerializedName("message") val message: String)
    @Keep data class Report(@SerializedName("issues") val issues: List<Issue>) {
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
        requireText(obj, "bookSourceName", issues, "源名称不能为空")
        requireText(obj, "bookSourceUrl", issues, "源 URL 不能为空")
        val type = obj.get("bookSourceType")?.takeIf { it.isJsonPrimitive }?.asInt
        if (type != null && type !in 0..4) issues += Issue("bookSourceType", "类型必须为 0..4")

        val searchUrl = text(obj, "searchUrl")
        val ruleSearch = objectOrNull(obj, "ruleSearch")
        if (!searchUrl.isNullOrBlank()) {
            if (!searchUrl.contains("{{key}}") && !searchUrl.contains("key")) {
                issues += Issue("searchUrl", "搜索 URL 应包含 {{key}} 或 JavaScript key")
            }
            if (ruleSearch == null || text(ruleSearch, "bookList").isNullOrBlank()) {
                issues += Issue("ruleSearch.bookList", "启用搜索时必须配置书籍列表规则")
            }
        }
        val exploreUrl = text(obj, "exploreUrl")
        if (!exploreUrl.isNullOrBlank()) {
            val ruleExplore = objectOrNull(obj, "ruleExplore")
            if (ruleExplore == null || text(ruleExplore, "bookList").isNullOrBlank()) {
                issues += Issue("ruleExplore.bookList", "启用发现时必须配置书籍列表规则")
            }
        }

        val content = objectOrNull(obj, "ruleContent")
        if (content == null || text(content, "content").isNullOrBlank()) {
            issues += Issue("ruleContent.content", "正文规则不能为空")
        }
        val toc = objectOrNull(obj, "ruleToc")
        if (toc == null || text(toc, "chapterList").isNullOrBlank()) {
            issues += Issue("ruleToc.chapterList", "目录列表规则不能为空")
        }
        return Report(issues)
    }

    private fun requireText(obj: JsonObject, key: String, issues: MutableList<Issue>, message: String) {
        if (text(obj, key).isNullOrBlank()) issues += Issue(key, message)
    }

    private fun text(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun objectOrNull(obj: JsonObject, key: String): JsonObject? =
        obj.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    /**
     * gson 报 "Unterminated object at line L column C" 时，回传错误列前后各约 140 字符的源码片段，
     * 让 save_source 的调用方能直接看见漏引号/漏反斜杠的位置，不用整包肉眼扫。
     */
    private fun malformedSnippet(json: String, message: String): String {
        val column = Regex("column (\\d+)").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return ""
        val start = (column - 1 - 140).coerceAtLeast(0)
        val end = (column - 1 + 140).coerceAtMost(json.length)
        if (start >= end) return ""
        return "\n错误位置附近源码（第 ${column} 列前后 140 字符）：\n…${json.substring(start, end).replace("\n", "\\n")}…"
    }
}
