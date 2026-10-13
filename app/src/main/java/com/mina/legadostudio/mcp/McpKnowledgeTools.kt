package com.mina.legadostudio.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject

internal fun StudioMcpServer.registerKnowledgeTools(server: Server) {
    server.tool("search_knowledge", "搜索内置 Legado 书源知识库并返回命中片段", schema(mapOf("query" to "搜索词"), listOf("query")), toolAnnotations = readOnly) { req ->
        ok(app.gson.toJson(app.knowledge.search(req.arguments.str("query").orEmpty(), 50)))
    }
    server.tool("list_skills", "列出内置和自定义 Skills（含 enabled；禁用的不要加载）", ToolSchema(properties = buildJsonObject {}, required = emptyList()), toolAnnotations = readOnly) {
        ok(app.gson.toJson(app.skills.list()))
    }
    server.tool("get_skill", "读取已启用 Skill 的 Markdown；禁用技能会报错", schema(mapOf("id" to "Skill ID"), listOf("id")), toolAnnotations = readOnly) { req ->
        runCatching {
            val id = req.arguments.str("id") ?: error("id 不能为空")
            require(app.skills.isEnabled(id)) { "Skill 已禁用" }
            ok(app.skills.read(id))
        }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("save_skill", "保存自定义 Skill；内置 Skill 不可覆盖", schema(mapOf("id" to "Skill ID", "markdown" to "SKILL.md 内容"), listOf("id", "markdown")), toolAnnotations = write) { req ->
        runCatching { app.skills.save(req.arguments.str("id")!!, req.arguments.str("markdown")!!); StudioLog.add("Skill saved: ${req.arguments.str("id")}"); ok("Skill 已保存") }.getOrElse { err(it.message.orEmpty()) }
    }
    server.tool("delete_skill", "删除自定义 Skill；内置 Skill 不可删除", schema(mapOf("id" to "Skill ID"), listOf("id")), toolAnnotations = write) { req ->
        runCatching {
            val id = req.arguments.str("id") ?: error("id 不能为空")
            require(app.skills.delete(id)) { "Skill 不存在或不是自定义技能" }
            StudioLog.add("Skill deleted: $id")
            ok("Skill 已删除")
        }.getOrElse { err(it.message.orEmpty()) }
    }
}
