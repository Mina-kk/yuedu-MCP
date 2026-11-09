package com.mina.legadostudio.ui.screens

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mina.legadostudio.data.db.ProjectEntity
import com.mina.legadostudio.domain.SourceDedupeAnalyzer
import com.mina.legadostudio.mcp.McpSourceTools

/**
 * 书源「分析/过滤」页的纯决策逻辑（无 Compose / 无 Android 依赖），从旧 Compose SourcesScreen 原样提取。
 *
 * 这些函数决定「哪些源进导出」「哪份检测证据还算数」，属业务安全语义：
 * 前端（Rust/Vue）必须按同一规则实现，本文件保留为语义基准，由 SourcesScreenEvidenceTest 锁住。
 */

/**
 * 本地导出组装（纯函数，JVM 可测；无 MCP 单次回包上限）：
 * - 保持 kept 顺序、每条 sourceJson **逐字节原文**——按已校验的原文直接拼成 JSON 数组，
 *   不重序列化（重序列化会丢键序/空白、长串可能被截断改形）；
 * - 任一成员 JSON 无法解析为对象立即报错（不产伪可用空对象）；
 * - collapse=true 时只折叠 SourceDedupeAnalyzer 判定为 action=export_one 的簇
 *   （安全桶+结构全等+零冲突），action=none 冲突/不确定簇一律原样保留；
 * - 恒返回 JSON 数组（阅读导入只认数组），无大小上限。
 */
internal fun exportArrayJson(kept: List<McpSourceTools.ExportRow>, collapseIdentical: Boolean): Pair<String?, String?> {
    if (kept.isEmpty()) return null to "没有可导出的书源（过滤后为空）"
    // 逐条校验是合法 JSON 对象后才允许进结果；校验用解析树，但输出用原文。
    val parsed = mutableListOf<Pair<McpSourceTools.ExportRow, JsonObject>>()
    kept.forEachIndexed { i, row ->
        val el = runCatching { JsonParser.parseString(row.sourceJson) }.getOrNull()
        if (el == null || !el.isJsonObject) {
            return null to "导出中止：kept[$i]（projectId=${row.projectId}）的 sourceJson 不是合法 JSON 对象，不能伪装成可用书源导出"
        }
        parsed += row to el.asJsonObject
    }
    var rows: List<Pair<McpSourceTools.ExportRow, JsonObject>> = parsed
    if (collapseIdentical) {
        val report = SourceDedupeAnalyzer.analyzeRows(parsed.map { (r, _) ->
            SourceDedupeAnalyzer.Row(r.projectId, r.siteUrl, r.sourceJson, r.updatedAt)
        })
        val keepIds = report.clusters.filter { it.action == SourceDedupeAnalyzer.ACTION_EXPORT_ONE }
            .associate { it.clusterKey to it.keepProjectId }
        val clusterKeyOf = report.items.associate { it.projectId to it.clusterKey }
        rows = parsed.filter { (r, _) ->
            val key = clusterKeyOf[r.projectId]
            val keep = key?.let { keepIds[it] }
            keep == null || keep == r.projectId
        }
    }
    // 逐字节原文拼接：按 kept 顺序用每条 sourceJson 原文拼数组，导入端拿到入库原文（不改键序/不截断）。
    return rows.joinToString(prefix = "[", postfix = "]", separator = ",") { it.first.sourceJson } to null
}

/**
 * 证据隔离解析（纯函数，JVM 可测）：把「projectId → (sourceJson 指纹, check 结果)」
 * 映射到当前 projects 列表的下标 Map。
 * - 键按 projectId 比对，不按位置：列表排序/新增/删除不会让 A 的结果串到 B；
 * - 指纹按 sourceJson 原文逐字节比对：同 ID 保存了新版本（内容变了）→ 旧证据 stale，不进 byIndex；
 * - projectId 已不在库里（被删）→ 计入 dropped 但不算 stale。
 * 返回 (byIndex 当前有效证据, staleCount 内容已变的条数)。
 */
internal fun resolveCheckEvidence(
    projects: List<ProjectEntity>,
    evidence: Map<String, Pair<String, Map<String, Any>>>,
): ResolvedEvidence {
    val byIndex = mutableMapOf<Int, Map<String, Any>>()
    var stale = 0
    val byId = projects.withIndex().associate { it.value.id to it.index }
    for ((projectId, entry) in evidence) {
        val (fingerprint, result) = entry
        val index = byId[projectId] ?: continue // 已删除：静默丢
        if (projects[index].sourceJson == fingerprint) byIndex[index] = result else stale++
    }
    return ResolvedEvidence(byIndex, stale)
}

internal class ResolvedEvidence(
    val byIndex: Map<Int, Map<String, Any>>,
    val staleCount: Int,
)
