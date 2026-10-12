package com.mina.legadostudio.domain

import androidx.annotation.Keep
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mina.legadostudio.data.db.ProjectEntity
import java.net.URI

/**
 * 书源身份提取 + 保守去重计划（纯函数，无 Android 依赖，JVM 可测）。
 *
 * 设计红线（替代旧的“同注册域 >5 条自动删最旧”逻辑）：
 * - **绝不删除任何项目**：只产出报告与建议；unparseable / 信息不全的一律原样保留。
 * - **身份 = 规范化 bookSourceUrl 全文**，保留 host、显式端口、路径（逐字节，不动尾部 /）、查询串
 *   以及 Legado `#标签` 段；bookSourceType / bookSourceName 记录在身份里但不参与分桶，
 *   同 URL 不同内容一律报冲突而不是悄悄合并。不按注册域名、不按响应速度淘汰任何项。
 * - **“完全相同”才建议导出保留一条**，且三条同时满足：桶键是 `u:` 规范化安全身份
 *   （显式 http/https、无 userinfo，非 `r:`原文/`s:`siteUrl 兜底桶）、成员 JSON 结构全一致
 *   （gson JsonElement.equals，键序无关）、全体成员 siteUrl↔bookSourceUrl 零冲突；
 *   任一不满足 → action=none，全部保留待人工确认。身份不确定宁可不报折叠。
 * - siteUrl（实体字段）与 sourceJson 内 bookSourceUrl 规范化后不一致 → URL_MISMATCH 冲突。
 * - 输出完全确定：不读时钟、不看输入顺序（同一项目集合任意排列产出同一报告）。
 *
 * 保存路径接法（供父代理稍后集成）：
 * - 保存前先查冲突：`urlMismatchBetween(siteUrl, bookSourceUrl)` 非空 → 提示人工确认，不自动改写；
 * - 拿当前条目的身份/桶键：`identify(id, siteUrl, sourceJson)`；
 * - 入库后查重复：`analyze(repository.list())` 或直接用 `SourceDedupeAnalyzer` 对内存列表跑。
 */
object SourceDedupeAnalyzer {

    /** 簇建议：成员内容完全一致，导出/保留 1 条即可（仅建议，永不删除）。 */
    const val ACTION_EXPORT_ONE = "export_one"
    /** 簇建议：存在冲突或信息不足，不给保留建议，全部保留。 */
    const val ACTION_NONE = "none"

    /** 同一 URL 桶下出现多份不同 sourceJson。 */
    const val CONFLICT_SAME_URL_DIFF_JSON = "same_url_diff_json"
    /** 实体 siteUrl 与 sourceJson.bookSourceUrl 规范化后不一致。 */
    const val CONFLICT_URL_MISMATCH = "siteurl_vs_source_url_mismatch"

    /** 一个项目的书源身份（仅从 sourceJson + 实体字段推导，不触网）。 */
    @Keep
    data class SourceIdentity(
        /** 原始 bookSourceUrl 文本（trim 后）。 */
        val rawUrl: String,
        /** 规范化身份 URL：scheme/host 小写，保留路径（含尾部 /）、查询、显式端口、#标签。空串=无法规范化。 */
        val canonicalUrl: String,
        val scheme: String,
        val host: String,
        /** 显式端口；-1=未写端口（与 :443/:80 默认端口不视为同一身份，宁可分开也不可误并）。 */
        val port: Int,
        /** 路径+查询部分（含 ?），展示/排障用。 */
        val pathQuery: String,
        /** bookSourceUrl `#` 之后的 Legado 标签（按 #/;/, 拆分，有序）。 */
        val tags: List<String>,
        /** sourceJson.bookSourceName（缺失为空串）。 */
        val name: String,
        /** sourceJson.bookSourceType（缺失/非数字为 null，不参与分桶，仅供冲突详情）。 */
        val type: Int?,
        /** sourceJson 是否为可解析的 JSON 对象。false 的项绝不参与去重判定。 */
        val validJson: Boolean,
    )

    /** 单个项目的去重视图：身份 + 归属桶 + 候选理由。 */
    @Keep
    data class SourceItem(
        val projectId: String,
        /** 去重分桶键；null = 独立项（JSON 无法解析），永不进入簇。 */
        val clusterKey: String?,
        val identity: SourceIdentity?,
        /** 候选理由（中文，人读）。 */
        val reason: String,
    )

    /** 同桶 ≥2 条的重复候选簇。 */
    @Keep
    data class DuplicateCluster(
        val clusterKey: String,
        /** [ACTION_EXPORT_ONE] 或 [ACTION_NONE]。 */
        val action: String,
        /** action=export_one 时建议保留的项目 ID（确定性选取：updatedAt 最新，并列取 id 最小）；否则空串。 */
        val keepProjectId: String,
        val memberIds: List<String>,
        /** 成员 sourceJson 是否结构完全一致。 */
        val identical: Boolean,
        val reason: String,
    )

    /** 需要人工确认的冲突项（不自动处理）。 */
    @Keep
    data class Conflict(
        /** [CONFLICT_SAME_URL_DIFF_JSON] / [CONFLICT_URL_MISMATCH]。 */
        val kind: String,
        val projectIds: List<String>,
        val detail: String,
    )

    @Keep
    data class Stats(
        val totalProjects: Int,
        val parseableJson: Int,
        val unparseableJson: Int,
        /** JSON 可解析但缺 bookSourceUrl 的项目数（按 siteUrl 兜底归桶）。 */
        val missingSourceUrl: Int,
        /** ≥2 条成员的重复候选簇数。 */
        val clusterCount: Int,
        /** 成员内容完全一致的簇数（不论是否达到可折叠门槛）。 */
        val identicalClusters: Int,
        val conflictCount: Int,
        /** 可安全折叠的冗余副本总数 = 仅 action=export_one 簇的 sum(size-1)；不确定簇不计入。 */
        val redundantCopies: Int,
    )

    @Keep
    data class DedupeReport(
        val items: List<SourceItem>,
        val clusters: List<DuplicateCluster>,
        val conflicts: List<Conflict>,
        val stats: Stats,
    )

    /** 内部解析产物。contentKey 用 JsonElement 做结构相等判定（键序无关）。 */
    private class Parsed(
        val projectId: String,
        val updatedAt: Long,
        val siteUrl: String,
        val identity: SourceIdentity?,
        val content: JsonElement?,
        val clusterKey: String?,
        val urlMismatchDetail: String?,
    )

    /**
     * 全库保守去重扫描（主入口）。
     * @param projects 全部项目（顺序任意，结果与输入顺序无关）
     */
    fun analyze(projects: List<ProjectEntity>): DedupeReport =
        analyzeParsed(projects.map { parse(it.id, it.siteUrl, it.sourceJson, it.updatedAt) })

    /**
     * 轻量行版（保存路径/集成用）：不依赖 ProjectEntity，直接传字段四元组。
     * 供 Repository/MCP 在不想构造实体时复用同一套判定。
     */
    fun analyzeRows(rows: List<Row>): DedupeReport =
        analyzeParsed(rows.map { parse(it.projectId, it.siteUrl, it.sourceJson, it.updatedAt) })

    /** 轻量入参：projectId/siteUrl/sourceJson/updatedAt。 */
    @Keep
    data class Row(
        val projectId: String,
        val siteUrl: String,
        val sourceJson: String,
        val updatedAt: Long = 0L,
    )

    private fun analyzeParsed(parsed: List<Parsed>): DedupeReport {
        val clusterSizes = parsed.mapNotNull { it.clusterKey }.groupingBy { it }.eachCount()
        val items = parsed.map { p ->
            val size = p.clusterKey?.let { clusterSizes[it] } ?: 0
            SourceItem(
                projectId = p.projectId,
                clusterKey = p.clusterKey,
                identity = p.identity,
                reason = itemReason(p, size),
            )
        }.sortedWith(compareBy({ it.clusterKey ?: "￿" }, { it.identity?.canonicalUrl ?: "" }, { it.projectId }))

        val conflicts = mutableListOf<Conflict>()
        val clusters = parsed.filter { it.clusterKey != null }
            .groupBy { it.clusterKey!! }
            .filter { it.value.size > 1 }
            .map { (key, members) ->
                val memberIds = members.map { it.projectId }.sorted()
                val identical = members.all { it.content != null } &&
                    members.mapNotNull { it.content }.distinct().size == 1
                // 折叠建议的三道闸（零假阳性）：①桶键必须是 u: 规范化安全身份（r:/s: 兜底桶身份不确定，不给建议）；
                // ②成员内容结构全一致；③全体成员 siteUrl↔bookSourceUrl 零冲突。任一不过 → none，全保留。
                val urlBucket = key.startsWith(BUCKET_URL_PREFIX)
                val fallbackBucket = !urlBucket
                val conflictedMembers = members.filter { it.urlMismatchDetail != null }.map { it.projectId }.sorted()
                val foldable = identical && urlBucket && conflictedMembers.isEmpty()
                val cluster = if (foldable) {
                    val keep = members.sortedWith(compareByDescending<Parsed> { it.updatedAt }.thenBy { it.projectId }).first().projectId
                    DuplicateCluster(
                        clusterKey = key,
                        action = ACTION_EXPORT_ONE,
                        keepProjectId = keep,
                        memberIds = memberIds,
                        identical = true,
                        reason = "成员 sourceJson 完全一致 ×${members.size}、身份确定且与 siteUrl 一致，导出/保留一条即可（建议保留 $keep；仅建议，不自动删除）",
                    )
                } else {
                    val blocker = when {
                        !identical -> "内容不一致"
                        fallbackBucket && key.startsWith(BUCKET_RAW_PREFIX) -> "bookSourceUrl 不是安全的 http(s) URL，按原文归桶，身份不确定"
                        fallbackBucket -> "成员缺 bookSourceUrl，按 siteUrl 兜底归桶，身份不确定"
                        else -> "成员 siteUrl↔bookSourceUrl 存在冲突"
                    }
                    DuplicateCluster(
                        clusterKey = key,
                        action = ACTION_NONE,
                        keepProjectId = "",
                        memberIds = memberIds,
                        identical = identical,
                        reason = "同桶 ${members.size} 条但$blocker，全部保留待人工确认" +
                            if (conflictedMembers.isNotEmpty()) "（冲突成员：${conflictedMembers.joinToString()}）" else "",
                    )
                }
                if (!identical) {
                    conflicts += Conflict(
                        kind = CONFLICT_SAME_URL_DIFF_JSON,
                        projectIds = memberIds,
                        detail = "同一身份桶 '$key' 下存在 ${members.size} 份不同 sourceJson：" + describeDiff(members),
                    )
                }
                cluster
            }
            .sortedBy { it.clusterKey }

        parsed.forEach { p ->
            p.urlMismatchDetail?.let { detail ->
                conflicts += Conflict(
                    kind = CONFLICT_URL_MISMATCH,
                    projectIds = listOf(p.projectId),
                    detail = detail,
                )
            }
        }
        conflicts.sortWith(compareBy({ it.kind }, { it.projectIds.firstOrNull().orEmpty() }))

        val stats = Stats(
            totalProjects = parsed.size,
            parseableJson = parsed.count { it.identity != null },
            unparseableJson = parsed.count { it.identity == null },
            missingSourceUrl = parsed.count { it.identity != null && it.identity.rawUrl.isBlank() },
            clusterCount = clusters.size,
            identicalClusters = clusters.count { it.identical },
            conflictCount = conflicts.size,
            redundantCopies = clusters.filter { it.action == ACTION_EXPORT_ONE }.sumOf { it.memberIds.size - 1 },
        )
        return DedupeReport(items = items, clusters = clusters, conflicts = conflicts, stats = stats)
    }

    /**
     * 单项目身份提取（保存路径接法）：save 前后调用即可拿到身份与分桶键，
     * 与既有项目的 [SourceItem.clusterKey] 比对即可知道是否落入重复候选桶。
     */
    fun identify(projectId: String, siteUrl: String, sourceJson: String): SourceItem {
        val p = parse(projectId, siteUrl, sourceJson, updatedAt = 0L)
        return SourceItem(
            projectId = p.projectId,
            clusterKey = p.clusterKey,
            identity = p.identity,
            reason = itemReason(p, 0),
        )
    }

    /**
     * 保存前置冲突检查（接法）：siteUrl 与 bookSourceUrl 规范化后不一致 → 返回人读详情，
     * 否则 null。保存方收到非空时应提示人工确认，不自动改写任一字段。
     */
    fun urlMismatchBetween(siteUrl: String, bookSourceUrl: String): String? =
        urlMismatch(siteUrl, bookSourceUrl, canonicalUrlOf(bookSourceUrl).orEmpty())

    /**
     * URL 规范化（公开给保存路径/测试）：只认**显式 http/https** 身份——scheme/host 小写，
     * 路径逐字节保留（不动尾部 /），保留显式端口、查询串与 #标签段。
     * 返回 null 的情况（= 身份不确定，调用方必须走兜底桶、不给折叠建议）：
     * 无法解析、缺 scheme、scheme 非 http/https、含 userinfo（user:pass@ 嵌入凭证）、取不到 host。
     * 空串原样返回空串。
     */
    fun canonicalUrlOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "$scheme://$host${if (port >= 0) ":$port" else ""}$path$query$fragment"
    }

    private const val BUCKET_URL_PREFIX = "u:"
    private const val BUCKET_RAW_PREFIX = "r:"
    private const val BUCKET_SITE_PREFIX = "s:"

    private fun parse(projectId: String, siteUrl: String, sourceJson: String, updatedAt: Long): Parsed {
        val element = runCatching { JsonParser.parseString(sourceJson) }.getOrNull()
        if (element == null || !element.isJsonObject) {
            // 无法解析（含 JSON 数组/基元）：一律独立项，不进任何桶 → 永远不会被建议折叠
            return Parsed(projectId, updatedAt, siteUrl, null, null, null, null)
        }
        val obj = element.asJsonObject
        val rawUrl = obj.get("bookSourceUrl")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val name = obj.get("bookSourceName")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val type = obj.get("bookSourceType")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
        val canonical = canonicalUrlOf(rawUrl).orEmpty()
        // 只有拿到安全规范化身份才解析 URI 取 tag/path；canonical 为空 = 身份不确定，uri 置 null 不给可疑片段
        val uri = canonical.takeIf { it.isNotBlank() }?.let { runCatching { URI(rawUrl) }.getOrNull() }
        val tags = uri?.rawFragment?.split('#', ';', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val identity = SourceIdentity(
            rawUrl = rawUrl,
            canonicalUrl = canonical,
            scheme = uri?.scheme?.lowercase().orEmpty(),
            host = uri?.host?.lowercase().orEmpty(),
            port = uri?.port ?: -1,
            pathQuery = (uri?.rawPath.orEmpty() + (uri?.rawQuery?.let { "?$it" }.orEmpty())),
            tags = tags,
            name = name,
            type = type,
            validJson = true,
        )
        val clusterKey = when {
            canonical.isNotBlank() -> BUCKET_URL_PREFIX + canonical
            rawUrl.isNotBlank() -> BUCKET_RAW_PREFIX + rawUrl
            // 缺 bookSourceUrl：siteUrl 原文归桶（不做规范化拼接，最多只 trim），身份不确定 → 永不给折叠建议
            else -> BUCKET_SITE_PREFIX + siteUrl.trim()
        }
        val mismatch = urlMismatch(siteUrl, rawUrl, canonical)
        return Parsed(projectId, updatedAt, siteUrl, identity, element, clusterKey, mismatch)
    }

    /**
     * siteUrl 与 bookSourceUrl 一致性检查：两边都非空且规范化后不同 → 冲突详情；否则 null。
     * 任一侧拿不到安全规范化身份时退化为原文比较（trim），不等即冲突——不确定宁可报也不放过。
     */
    private fun urlMismatch(siteUrl: String, rawUrl: String, canonicalUrl: String): String? {
        val site = siteUrl.trim()
        if (site.isEmpty() || rawUrl.isBlank()) return null
        val siteCanon = canonicalUrlOf(site)
        val same = if (siteCanon != null && canonicalUrl.isNotEmpty()) {
            siteCanon == canonicalUrl
        } else {
            site == rawUrl.trim()
        }
        return if (same) null
        else "项目 siteUrl='$site' 与 sourceJson.bookSourceUrl='$rawUrl' 规范化后不一致（site→${siteCanon ?: "无法解析"}，source→${canonicalUrl.ifEmpty { "无法解析" }}）"
    }

    private fun itemReason(p: Parsed, bucketSize: Int): String = when {
        p.identity == null -> "sourceJson 无法解析为 JSON 对象，原样保留，不参与去重判定"
        bucketSize > 1 -> "重复候选簇 ${p.clusterKey}：$bucketSize 条成员（见 clusters）"
        p.identity.rawUrl.isBlank() -> "缺 bookSourceUrl，按 siteUrl 归桶（${p.clusterKey}），当前桶内唯一"
        p.identity.canonicalUrl.isBlank() -> "bookSourceUrl 无法规范化为 URL，按原文归桶（${p.clusterKey}），当前桶内唯一"
        else -> "独立身份 ${p.identity.canonicalUrl}"
    }

    /** 冲突详情里的人读差异提示：列出各成员 name/type，便于一眼看出是否同名不同类型。 */
    private fun describeDiff(members: List<Parsed>): String =
        members.sortedBy { it.projectId }.joinToString("；") { m ->
            val id = m.identity
            "${m.projectId}(name=${id?.name ?: "?"},type=${id?.type?.toString() ?: "?"})"
        }
}
