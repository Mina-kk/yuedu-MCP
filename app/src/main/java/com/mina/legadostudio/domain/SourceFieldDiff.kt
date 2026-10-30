package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal object SourceFieldDiff {
    private val gson = Gson()
    private val nestedRuleFields = setOf("ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent", "ruleExplore")

    fun changedFields(old: JsonObject?, new: JsonObject): List<String> {
        if (old == null) return emptyList()
        val changed = mutableListOf<String>()
        (old.keySet() + new.keySet()).toSortedSet().forEach { key ->
            val before = old.get(key)
            val after = new.get(key)
            val beforeObject = before as? JsonObject
            val afterObject = after as? JsonObject
            if (key in nestedRuleFields && (beforeObject != null || afterObject != null)) {
                val oldRule = beforeObject ?: JsonObject()
                val newRule = afterObject ?: JsonObject()
                (oldRule.keySet() + newRule.keySet()).toSortedSet().forEach { child ->
                    if (!sameJson(oldRule.get(child), newRule.get(child))) changed += "$key.$child"
                }
            } else if (!sameJson(before, after)) {
                changed += key
            }
        }
        return changed
    }

    private fun sameJson(first: JsonElement?, second: JsonElement?): Boolean =
        canonical(first) == canonical(second)

    private fun canonical(element: JsonElement?): String = when {
        element == null -> "<missing>"
        element.isJsonObject -> element.asJsonObject.entrySet().sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { "${gson.toJson(it.key)}:${canonical(it.value)}" }
        element.isJsonArray -> element.asJsonArray.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        else -> gson.toJson(element)
    }
}
