package com.mina.legadostudio.web

import com.google.gson.JsonObject
import com.mina.legadostudio.skills.SkillRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local management only: never expose disabled content through MCP get_skill. */
internal class SkillManagementModule(
    private val repository: SkillRepository,
    private val documents: SkillManagementDocuments,
) {
    private val mutex = Mutex()

    suspend fun handle(method: String, params: JsonObject): JsonObject = mutex.withLock {
        when (method) {
            "skills.read" -> repository.withSnapshot {
                val id = id(params)
                info(id).apply { addProperty("markdown", repository.read(id)) }
            }
            "skills.setEnabled" -> repository.withSnapshot {
                val id = id(params)
                info(id) // Reject unknown IDs before changing preferences.
                val enabled = params.get("enabled")
                require(enabled?.isJsonPrimitive == true && enabled.asJsonPrimitive.isBoolean) { "enabled 必须是布尔值" }
                repository.setEnabled(id, enabled.asBoolean)
                info(id).also { check(it.get("enabled").asBoolean == enabled.asBoolean) { "启用状态回读失败" } }
            }
            "skills.import" -> {
                val imported = documents.importPackage(repository)
                if (imported == null) JsonObject().apply { addProperty("cancelled", true) }
                else repository.withSnapshot {
                    JsonObject().apply {
                        // Read content and metadata in one repository snapshot.
                        val readback = info(imported.id)
                        repository.read(imported.id)
                        addProperty("imported", true)
                        add("skill", readback)
                    }
                }
            }
            "skills.export" -> {
                val id = id(params)
                info(id)
                val saved = documents.exportPackage(repository, id)
                JsonObject().apply { addProperty("saved", saved); addProperty("cancelled", !saved) }
            }
            else -> error("未知技能管理方法")
        }
    }

    private fun id(params: JsonObject): String {
        val value = params.get("id")
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) { "id 必须是字符串" }
        return value.asString.also { require(SkillRepository.validId(it)) { "非法 Skill ID" } }
    }

    private fun info(id: String): JsonObject {
        val value = repository.list().singleOrNull { it.id == id } ?: error("Skill 不存在")
        return JsonObject().apply {
            addProperty("id", value.id)
            addProperty("name", value.name)
            addProperty("builtIn", value.builtIn)
            addProperty("enabled", value.enabled)
        }
    }

    companion object {
        val METHODS = setOf("skills.read", "skills.setEnabled", "skills.import", "skills.export")
    }
}

internal interface SkillManagementDocuments {
    /** null = picker cancellation, with no repository mutation. */
    suspend fun importPackage(repository: SkillRepository): SkillRepository.SkillInfo?
    /** false = picker cancellation; true only after binary output closes successfully. */
    suspend fun exportPackage(repository: SkillRepository, id: String): Boolean
}
