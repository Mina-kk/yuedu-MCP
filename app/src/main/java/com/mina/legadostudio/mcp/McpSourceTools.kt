package com.mina.legadostudio.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.mina.legadostudio.data.db.ProjectEntity
import com.mina.legadostudio.domain.SourceDedupeAnalyzer
import com.mina.legadostudio.domain.SourceFilterPolicy

/**
 * analyze_sources / export_checked_sources / save_source / list_sources 共用的纯函数层：
 * 不依赖 Android Context、不触网、不执行 JS。所有「身份判定/冲突折叠/导出组装/可信证据登记」规则
 * 集中在这里并直接 JVM 单测，StudioMcpServer 只做参数解析与结果包装。
 *
 * 导出保真口径：buildExport 直接拼接每条 sourceJson 原文（不是解析后 toString 重建），
 * 完整字段/类型/#标签/键序逐字节保留。
 *
 * 可信判定口径：verified/failed 只认 CheckEvidenceRegistry（本实例 check_source 跑完登记）；
 * 客户端自传 checkResults 是外部自述（untrusted），绝不进 verified/failed 判定。
 */
object McpSourceTools {

    // ---- analyze_sources 输入上限 ----
    const val MAX_IDS = 200
    const val MAX_ITEM_CHARS = 400_000
    const val MAX_TOTAL_CHARS = 2_000_000
    const val MAX_PROJECTS = 5000

    // ---- export_checked_sources 输入上限（同 analyze；导出原文按条数+总字节把关） ----
    const val MAX_EXPORT_IDS = 200

    // ---- list_sources 分页 ----
    const val DEFAULT_LIST_LIMIT = 200
    const val MAX_LIST_LIMIT = 1000
    const val SNIPPET_LIMIT = 80

    // ---- 工具结果内联保护：导出的对象/数组 JSON 超过该长度时必须报「过大用 read_result」，不得硬截断 ----
    const val MAX_INLINE_RESULT_CHARS = 11_500

    // ---- check_source 证据注册表（见 CheckEvidenceRegistry）----
    /** 单个源最多保留几次 check 快照（同 sourceJson 反复跑时只留最近 N 次）。 */
    const val CHECK_RECORDS_PER_SOURCE = 4
    /** 注册表容量上限（按 sourceJson hash 桶数）；超限淘汰最久未命中桶。 */
    const val CHECK_REGISTRY_MAX_SOURCES = 256
    /** 证据有效期：30 分钟。超过后需重跑 check_source——旧快照不算「当前已验」。 */
    const val CHECK_EVIDENCE_TTL_MS = 30L * 60 * 1000

    private val STAGE_NAMES = setOf("搜索", "详情", "目录", "正文", "发现")

    /**
     * check_source 结果注册表：**verified/failed 判定只认这里**。
     *
     * 为什么需要它：`analyze_sources`/`export_checked_sources` 接收客户端自传的 checkResults
     * （`{"0":{...}}` 按下标），既不与具体 sourceJson 绑定（换个顺序/数组就错位），
     * 经 Gson Map roundtrip 后 DebugReport 成功态对象也还原不出来——凭外部自述判定「已验证」
     * 等于放行伪造。因此严格契约改成：
     * - `check_source` 本实例跑完后，把**当次的原始阶段结果**（DebugReport/errorMap 原文，
     *   不是 roundtrip 后的 Map）按 `digest(sourceJson)` 登记进来；
     * - `analyze`/`export` 用 `sha256(sourceJson)` 命中注册表才算 verified；
     * - 客户端传的 checkResults 只做「外部自述」回显，绝不进 verified/failed 判定；
     * - 不传 checkResults 完全不影响只读静态分析与默认导出（无证据时正常导出，
     *   只有 excludeUnverified 才把无证据源排除）。
     *
     * 存原文而非裁剪 Map 的原因：`SourceFilterPolicy.filter` 要拿阶段原文再跑
     * `SourceCheckEvidence.of`（DebugReport.data 形态决定 ok/failed 判定），存「已判定 Map」
     * 会丢掉结构、变成不可信的二次自述。这里存的是 runner 内部产出的对象，可信。
     */
    object CheckEvidenceRegistry {
        /** 一次 check_source 的登记记录：阶段原文 + 元信息。 */
        data class Record(
            val checkedAt: Long,
            val refresh: Boolean,
            /** 阶段名 → CheckSourceRunner 的原始结果（DebugReport 或 error Map，可信原文）。 */
            val stageResults: Map<String, Any>,
        )

        private data class Bucket(val deque: ArrayDeque<Record> = ArrayDeque(), var lastHitAt: Long)

        private val lock = Any()
        private val buckets = java.util.LinkedHashMap<String, Bucket>()

        private fun evictIfNeeded(now: Long) {
            // 先按 TTL 清整桶里的过期记录；仍超容量再淘汰最久未命中桶
            val it = buckets.entries.iterator()
            while (it.hasNext()) {
                val b = it.next().value
                b.deque.removeAll { now - it.checkedAt > CHECK_EVIDENCE_TTL_MS }
                if (b.deque.isEmpty()) it.remove()
            }
            while (buckets.size > CHECK_REGISTRY_MAX_SOURCES) {
                val oldest = buckets.minByOrNull { it.value.lastHitAt }?.key ?: break
                buckets.remove(oldest)
            }
        }

        /**
         * 登记一次 check_source 结果。stageResults 只取阶段键（搜索/详情/目录/正文），
         * 过滤掉 validation/warnings/cache/timingsMs/evidence/verdict 等非阶段键。
         * 返回登记后该 sourceJson 命中的最新记录（供调用方直接复用），无阶段则返回 null。
         */
        fun register(sourceJson: String, stageResults: Map<String, Any>, refresh: Boolean, now: Long): Record? {
            val stages = stageResults.filterKeys { it in STAGE_NAMES }
            if (stages.isEmpty()) return null
            val hash = TaskContextStore.digest(sourceJson)
            synchronized(lock) {
                evictIfNeeded(now)
                val bucket = buckets.getOrPut(hash) { Bucket(lastHitAt = now) }
                bucket.deque.addLast(Record(now, refresh, stages))
                while (bucket.deque.size > CHECK_RECORDS_PER_SOURCE) bucket.deque.removeFirst()
                bucket.lastHitAt = now
                return bucket.deque.last()
            }
        }

        /** 取某 sourceJson 最近一次（TTL 内）登记的记录；无命中返回 null。 */
        fun latest(sourceJson: String, now: Long): Record? {
            val hash = TaskContextStore.digest(sourceJson)
            synchronized(lock) {
                val bucket = buckets[hash] ?: return null
                bucket.lastHitAt = now
                return bucket.deque.lastOrNull { now - it.checkedAt <= CHECK_EVIDENCE_TTL_MS }
            }
        }

        /** 该 sourceJson 在 TTL 内是否有可信 check 证据。 */
        fun hasTrustedEvidence(sourceJson: String, now: Long): Boolean = latest(sourceJson, now) != null

        /** 供诊断/测试：当前登记了几个 sourceJson 桶。 */
        fun size(): Int = synchronized(lock) { buckets.size }

        /** 测试用：清空注册表。 */
        fun clear() = synchronized(lock) { buckets.clear() }
    }


    /**
     * 书源 JSON 骨架预览：只回显高信息量字段（名字/URL/规则键清单/header 键名），
     * header 只列键名不回显值——值里常有 Cookie/Authorization 凭据，按会话凭据口径不外发。
     * bookSourceComment（源注释）也可能夹带 token/凭据，预览不回显其文本——只报「有没有」。
     */
    private val PREVIEW_FIELDS = listOf("bookSourceName", "bookSourceUrl", "bookSourceType")
    private val RULE_KEY_FIELDS = listOf("searchUrl", "exploreUrl", "ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent", "loginUrl", "loginUi")

    /** 过滤开关名 → 枚举；未知名在 parseExcludes 明确报错。 */
    fun excludeArgNames(): List<String> = SourceFilterPolicy.Exclude.values().map { it.arg }

    /**
     * 解析排除开关：ids 之外第二个参数位置（JSON 顶层布尔字段）。未传/全部 false → 空集 = 不排除任何源。
     * 收到非布尔值或未知维度名时返回错误串（调用方直接回 err）。
     */
    fun parseExcludes(args: kotlinx.serialization.json.JsonObject?): Pair<Set<SourceFilterPolicy.Exclude>, String?> {
        val value = args?.get("exclude") ?: return emptySet<SourceFilterPolicy.Exclude>() to null
        if (value !is kotlinx.serialization.json.JsonObject) return emptySet<SourceFilterPolicy.Exclude>() to
            "exclude 需要 JSON 对象（如 {\"excludeLogin\":true}），收到 ${jsonTypeOf(value)}"
        val known = SourceFilterPolicy.Exclude.values().associateBy { it.arg }
        val enabled = mutableSetOf<SourceFilterPolicy.Exclude>()
        for ((key, v) in value) {
            val dim = known[key] ?: return emptySet<SourceFilterPolicy.Exclude>() to
                "exclude 不支持维度 \"$key\"（可选：${known.keys.sorted().joinToString()}）"
            if (v !is kotlinx.serialization.json.JsonPrimitive || v.booleanOrNull == null) {
                return emptySet<SourceFilterPolicy.Exclude>() to "exclude.$key 需要布尔值，收到 ${jsonTypeOf(v)}"
            }
            if (v.booleanOrNull == true) enabled += dim
        }
        return enabled to null
    }

    /**
     * 解析客户端自传的 checkResults JSON 字符串为 Map<Int, JsonObject>（index→check_source 结果）。
     *
     * **安全口径**：该参数是「外部自述」——无法与本次输入的 sourceJson 建立绑定（键只是数组下标），
     * 且经 Gson Map roundtrip 后 DebugReport 已成功态对象不可还原，SourceCheckEvidence.of 无法判定。
     * 因此它**绝不参与** verified/failed 判定（那是注册表内证据的职责）；这里只做类型校验后原样回显，
     * 让调用方知道自己附了哪些声明。解析失败仍给明确错误（不把坏输入静默吞掉）。
     */
    fun parseCheckResults(raw: String?): Pair<Map<Int, com.google.gson.JsonObject>?, String?> {
        if (raw.isNullOrBlank()) return null to null
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return null to "checkResults 需要 JSON 对象字符串（{\"0\":{...check_source 结果...}}），无法解析为 JSON 对象"
        val out = mutableMapOf<Int, com.google.gson.JsonObject>()
        for ((k, v) in obj.entrySet()) {
            val idx = k.toIntOrNull() ?: return null to "checkResults 的键必须是输入数组下标（数字），收到 \"$k\""
            if (!v.isJsonObject) return null to "checkResults[$k] 需要 JSON 对象（check_source 的返回），收到 ${v.javaClass.simpleName}"
            out[idx] = v.asJsonObject
        }
        return out to null
    }

    /**
     * 把每条 sourceJson 去注册表查一次可信证据，组装成 `SourceFilterPolicy.filter` 要的
     * `checkResults`（index→阶段原文 Map）。只放 TTL 内命中注册表的；未命中该 index 不进 map
     * （对应「该源本实例没跑过 check」→ UNVERIFIED）。**这是 verified 判定的唯一来源。**
     */
    fun trustedCheckResults(sources: List<String>, now: Long): Map<Int, Map<String, Any>> {
        val out = mutableMapOf<Int, Map<String, Any>>()
        sources.forEachIndexed { i, json ->
            CheckEvidenceRegistry.latest(json, now)?.let { out[i] = it.stageResults }
        }
        return out
    }

    /**
     * 外部自述 checkResults 的回显摘要：只报「哪个下标附了什么阶段名/有没有 verdict」，
     * 不回显阶段正文——它不可信，回显原文会让调用方误以为已采信。用于回包 `externalClaims`。
     */
    fun externalClaimsSummary(claims: Map<Int, com.google.gson.JsonObject>?): List<Map<String, Any?>> =
        claims?.entries?.sortedBy { it.key }?.map { (i, obj) ->
            linkedMapOf<String, Any?>(
                "index" to i,
                "declaredStages" to obj.keySet().filter { it in STAGE_NAMES }.sorted(),
                "hasVerdict" to obj.has("verdict"),
            )
        }.orEmpty()

    /** 解析 sources 参数（JSON 数组字符串，每项是完整书源对象）。返回 (jsonList, error)。 */
    fun parseSourceArray(raw: String): Pair<List<String>?, String?> {
        if (raw.isBlank()) return null to "sources 不能为空"
        if (raw.length > MAX_TOTAL_CHARS) return null to "sources 过大（${raw.length} 字符，上限 $MAX_TOTAL_CHARS）"
        val el = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?: return null to "sources 不是合法 JSON：请传 JSON 数组字符串，如 [{...},{...}]"
        if (!el.isJsonArray) return null to "sources 需要 JSON 数组，收到 ${if (el.isJsonObject) "object（请用 [ ] 包裹后传数组）" else "非标量"}"
        val arr = el.asJsonArray
        if (arr.size() == 0) return null to "sources 数组为空"
        if (arr.size() > MAX_IDS) return null to "sources 一次最多 $MAX_IDS 条（收到 ${arr.size()}）"
        val out = mutableListOf<String>()
        arr.forEachIndexed { i, e ->
            if (!e.isJsonObject) return null to "sources[$i] 需要 JSON 对象书源，收到 ${e.javaClass.simpleName}"
            val text = e.toString()
            if (text.length > MAX_ITEM_CHARS) return null to "sources[$i] 过大（${text.length} 字符，单条上限 $MAX_ITEM_CHARS）"
            out += text
        }
        return out to null
    }

    /**
     * 按 projectId 集合过滤项目；返回 (命中项目（保持 ids 顺序去重）, 未命中的 id 列表, 错误)。
     * 未知 id 不静默丢弃——逐个点名，调用方决定报错或提示。
     */
    fun selectProjects(projects: List<ProjectEntity>, ids: List<String>): Triple<List<ProjectEntity>, List<String>, String?> {
        if (ids.size > MAX_IDS) return Triple(emptyList(), ids, "ids 一次最多 $MAX_IDS 个（收到 ${ids.size}）")
        val byId = projects.associateBy { it.id }
        val distinct = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val missing = distinct.filter { it !in byId }
        val hit = distinct.mapNotNull { byId[it] }
        return Triple(hit, missing, null)
    }

    /** 项目 → analyze/export 输入行（身份判定不改写 siteUrl——真实数据原样参与）。 */
    fun rowOf(p: ProjectEntity) = SourceDedupeAnalyzer.Row(p.id, p.siteUrl, p.sourceJson, p.updatedAt)

    // ---- list_sources latestOnly / archive_source_versions（纯函数，可测） ----

    /** 同 URL 版本数：按 siteUrl.trim() 分组计数（与 get_source 的精确匹配口径一致）。 */
    fun versionCounts(projects: List<ProjectEntity>): Map<String, Int> =
        projects.groupingBy { it.siteUrl.trim() }.eachCount()

    /**
     * latestOnly 折叠（纯函数）：每个 siteUrl 只留 updatedAt 最大的一条代表；
     * 代表顺序仍按各自 updatedAt 倒序。折叠不判断内容——同 URL 不同 JSON 也算同组，
     * 查全量历史请去掉 latestOnly。
     */
    fun latestOnly(projects: List<ProjectEntity>): List<ProjectEntity> =
        projects.groupBy { it.siteUrl.trim() }.values.map { group -> group.maxByOrNull { it.updatedAt }!! }
            .sortedByDescending { it.updatedAt }

    /**
     * archive_source_versions 的删除决策（纯函数，可测）：
     * 输入 = 全库项目 + 精确 bookSourceUrl + keepLatest（≥1）。
     * 输出 = 该 URL 组内按 updatedAt 倒序保留前 keepLatest 条，其余为待删。
     * URL 逐字符精确匹配（trim 后比较）：尾部 / 与 #标签不同即不同版本组，与 get_source 口径一致。
     * 返回 Triple(命中组, 保留, 待删)；命中为空时三者皆空——调用方据此报错。
     */
    fun decideArchive(projects: List<ProjectEntity>, bookSourceUrl: String, keepLatest: Int): Triple<List<ProjectEntity>, List<ProjectEntity>, List<ProjectEntity>> {
        val key = bookSourceUrl.trim()
        val group = projects.filter { it.siteUrl.trim() == key }.sortedByDescending { it.updatedAt }
        if (group.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())
        val keep = group.take(keepLatest.coerceAtLeast(1))
        return Triple(group, keep, group.drop(keep.size))
    }

    /** 项目 → 书源骨架预览 Map（sourceJson 解析失败时如实体 stub 返回，不回显不可解析原文）。 */
    fun previewOf(p: ProjectEntity, index: Int): Map<String, Any?> {
        val obj = runCatching { JsonParser.parseString(p.sourceJson).asJsonObject }.getOrNull()
        return linkedMapOf<String, Any?>(
            "index" to index,
            "projectId" to p.id,
            "name" to p.name,
            "siteUrl" to p.siteUrl,
            "stage" to p.stage,
            "updatedAt" to p.updatedAt,
            "parseableJson" to (obj != null),
        ).also { m ->
            if (obj != null) {
                PREVIEW_FIELDS.forEach { k -> obj.get(k)?.takeUnless { it.isJsonNull }?.let { m[k] = it.asString.take(120) } }
                // bookSourceComment 可能夹带 token/凭据：只回显「有没有」，不回显文本
                m["hasComment"] = obj.get("bookSourceComment")?.takeUnless { it.isJsonNull }?.asString?.isNotBlank() == true
                m["ruleKeys"] = RULE_KEY_FIELDS.filter { obj.has(it) && !obj.get(it).isJsonNull }
                m["headerKeys"] = obj.get("header")?.let { h ->
                    when {
                        h.isJsonObject -> h.asJsonObject.keySet().sorted()
                        h.isJsonPrimitive -> runCatching {
                            JsonParser.parseString(h.asString).asJsonObject.keySet().sorted()
                        }.getOrElse { listOf("<unparseable header>") }
                        else -> emptyList()
                    }
                }.orEmpty()
            } else {
                m["parseError"] = "sourceJson 不是合法 JSON 对象"
            }
        }
    }

    /** DedupeReport → MCP 回包 Map（冲突原样带出，不折叠、不排序改写）。 */
    fun reportPayload(report: SourceDedupeAnalyzer.DedupeReport, previewById: Map<String, Map<String, Any?>>): Map<String, Any?> =
        linkedMapOf(
            "stats" to report.stats,
            "clusters" to report.clusters.map { c ->
                linkedMapOf(
                    "clusterKey" to c.clusterKey, "action" to c.action,
                    "keepProjectId" to c.keepProjectId.ifBlank { null },
                    "memberIds" to c.memberIds, "identical" to c.identical, "reason" to c.reason,
                )
            },
            // 冲突逐条原样列出：绝不把冲突折叠成「可导出」
            "conflicts" to report.conflicts.map { linkedMapOf("kind" to it.kind, "projectIds" to it.projectIds, "detail" to it.detail) },
            "items" to report.items.map { item ->
                linkedMapOf(
                    "projectId" to item.projectId, "clusterKey" to item.clusterKey, "reason" to item.reason,
                    "identity" to item.identity?.let { id ->
                        linkedMapOf(
                            "rawUrl" to id.rawUrl, "canonicalUrl" to id.canonicalUrl, "host" to id.host,
                            "port" to id.port, "pathQuery" to id.pathQuery, "tags" to id.tags,
                            "name" to id.name, "type" to id.type, "validJson" to id.validJson,
                        )
                    },
                    "source" to previewById[item.projectId],
                )
            },
        )

    /** Filter Report → MCP 回包 Map（保留/排除清单各带原因；不会把「未验证」伪装成「可用」）。 */
    fun filterPayload(report: SourceFilterPolicy.Report): Map<String, Any?> = linkedMapOf(
        "total" to report.total,
        "keptCount" to report.keptCount,
        "excludedCount" to report.excludedCount,
        "kept" to report.kept.map { decisionPayload(it) },
        "excluded" to report.excluded.map { decisionPayload(it) },
    )

    private fun decisionPayload(d: SourceFilterPolicy.Decision): Map<String, Any?> = linkedMapOf(
        "index" to d.index, "keep" to d.keep, "reasons" to d.reasons,
        "bookSourceName" to d.source.get("bookSourceName")?.takeIf { it.isJsonPrimitive }?.asString,
        "bookSourceUrl" to d.source.get("bookSourceUrl")?.takeIf { it.isJsonPrimitive }?.asString,
        // 这里的 evidence 只可能来自本实例 check_source 注册表（客户端自述不进 filter），故如实标 trusted
        "trustedEvidence" to d.evidence?.mapValues { (_, ev) ->
            linkedMapOf("ran" to ev.ran, "ok" to ev.ok, "reason" to ev.reason, "category" to ev.category)
        },
    )

    /**
     * 导出组装（纯函数）：
     * - 保持调用方传入顺序与每条 sourceJson 原文**逐字节**——直接用原始字符串拼数组，
     *   不经过「解析→重序列化」，键序/空白/#标签全部原样（这就是注释承诺的「保留原文」）；
     * - 任一成员 JSON 无法解析为对象 → error，不产出「伪可用空对象」；
     * - collapseIdentical=true 时只折叠「u: 安全桶 + 成员 JSON 结构全等 + 零冲突」的簇，
     *   冲突簇/action=none 一律原样保留——折叠报告如实回传。
     *
     * 「保留完整字段/类型/#标签」是承诺；「逐字节」由原文拼接天然保证，而不是靠 toString 重建。
     */
    fun buildExport(
        kept: List<ExportRow>,
        collapseIdentical: Boolean,
        asArray: Boolean,
    ): Triple<String?, Map<String, Any?>, String?> {
        if (kept.isEmpty()) {
            return Triple(null, emptyMap(), "没有可导出的书源（过滤后为空）；不是格式错误，请放宽 exclude 条件或先取消过滤")
        }
        // 每条必须先能解析为 JSON 对象——否则导出的会是「伪可用空对象」，不能用。
        // 解析只用做合法性校验；真正写出的文本是原始 sourceJson 字符串（逐字节保留）。
        kept.forEachIndexed { i, row ->
            val el = runCatching { JsonParser.parseString(row.sourceJson) }.getOrNull()
            if (el == null || !el.isJsonObject) {
                return Triple(null, emptyMap(),
                    "导出中止：kept[${i}]（projectId=${row.projectId}）的 sourceJson 不是合法 JSON 对象，不能伪装成可用书源导出；" +
                        "请先用 analyze_sources 排查该行，修正后再导出")
            }
        }
        val collapsedClusters = mutableListOf<Map<String, Any?>>()
        var rows: List<ExportRow> = kept
        if (collapseIdentical) {
            // 用同一套 DedupeAnalyzer 重新归桶（行 = 导出候选），只对 action=export_one 的簇留 keep 代表
            val report = SourceDedupeAnalyzer.analyzeRows(kept.map {
                SourceDedupeAnalyzer.Row(it.projectId, it.siteUrl, it.sourceJson, it.updatedAt)
            })
            val keepIds = report.clusters.filter { it.action == SourceDedupeAnalyzer.ACTION_EXPORT_ONE }
                .associate { it.clusterKey to it.keepProjectId }
            val clusterKeyOf = report.items.associate { it.projectId to it.clusterKey }
            rows = kept.filter { r ->
                val key = clusterKeyOf[r.projectId]
                val keep = key?.let { keepIds[it] }
                keep == null || keep == r.projectId // 非折叠簇成员/null 全保留；折叠簇只留 keep
            }
            report.clusters.filter { it.action == SourceDedupeAnalyzer.ACTION_EXPORT_ONE }.forEach { c ->
                collapsedClusters += linkedMapOf(
                    "clusterKey" to c.clusterKey, "kept" to c.keepProjectId,
                    "dropped" to (c.memberIds - c.keepProjectId).sorted(),
                )
            }
        }
        // 直接拼原始 sourceJson 文本：数组= `[` + 原文逗号连接 + `]`；单对象默认仍是原文。
        // 这样 键序/字段类型/bookSourceUrl 的 #标签 与写入时完全一致（不被 toString 归一化）。
        val text = if (!asArray && rows.size == 1) rows.single().sourceJson
        else rows.joinToString(separator = ",", prefix = "[", postfix = "]") { it.sourceJson }
        if (text.length > MAX_INLINE_RESULT_CHARS) {
            return Triple(null, emptyMap(),
                "导出结果 ${text.length} 字符超过单次返回上限 $MAX_INLINE_RESULT_CHARS；请缩小 ids/加 exclude 过滤后重试，或分批导出")
        }
        val summary = linkedMapOf<String, Any?>(
            "requested" to kept.size,
            "exported" to rows.size,
            "collapsed" to collapsedClusters,
            "asArray" to (asArray || rows.size > 1),
        )
        return Triple(text, summary, null)
    }

    /**
     * save_source 的 fields 合并（纯函数，与工具描述一致）：
     * 顶层键整体覆盖——同名键被替换为 fields 值；值为 JSON null 时**删除**该顶层键
     * （obj.remove，不是写入 null——写入 null 会让 has(key) 仍为 true，与「删除」语义不符）。
     * 不递归合并子键；未点名的键原样保留。
     */
    /**
     * 顶层字段里「值本身就是一段 JSON 字符串」的字段集合：Legado 规范中
     * header/loginUi/loginCheckJs/jsLib 等字段的合法值是序列化后的 JSON 文本。
     * MCP 传输层会把 fields 里 `"header": "{...}"` 的字符串再解析成 object，
     * 导致落库/校验时 type 变成对象而非字符串（BookSourceValidator 报
     * 「字段类型必须是字符串」）。这里对这些字段做归一化：收到 object/array
     * 时重新序列化回 JSON 文本，等价于调用方传入了序列化字符串。
     */
    private val JSON_STRING_VALUED_FIELDS = setOf("header", "loginUi", "loginCheckJs", "jsLib")
    private val GSON = Gson()

    fun mergeFieldsInto(obj: JsonObject, fields: JsonObject) {
        for ((key, value) in fields.entrySet()) {
            val normalized = if (key in JSON_STRING_VALUED_FIELDS && value != null && !value.isJsonNull && !value.isJsonPrimitive) {
                com.google.gson.JsonPrimitive(GSON.toJson(value))
            } else value
            if (normalized == null || normalized.isJsonNull) obj.remove(key) else obj.add(key, normalized)
        }
    }

    /**
     * get_source 的定位决策（纯函数）：
     * 项目 ID 精确命中优先；否则 URL（siteUrl.trim()==key.trim()）**恰好命中一条**才返回；
     * 命中多条 → Ambiguous（回 projectId 列表，不默认选其一——同 URL 多版本时返回哪个是
     * 不确定的，静默挑 firstOrNull 会把别的版本内容当请求版本回给调用方）。
     */
    sealed class SourceLookup {
        data class Found(val project: ProjectEntity) : SourceLookup()
        data class Ambiguous(val projectIds: List<String>) : SourceLookup()
        data object NotFound : SourceLookup()
    }

    fun resolveGetSource(byId: ProjectEntity?, urlMatches: List<ProjectEntity>): SourceLookup = when {
        byId != null -> SourceLookup.Found(byId)
        urlMatches.isEmpty() -> SourceLookup.NotFound
        urlMatches.size == 1 -> SourceLookup.Found(urlMatches.single())
        else -> SourceLookup.Ambiguous(urlMatches.map { it.id })
    }

    /** 导出行输入（projectId 只为回执与折叠判定，不回显进书源 JSON 本身）。 */
    data class ExportRow(val projectId: String, val siteUrl: String, val sourceJson: String, val updatedAt: Long)

    /**
     * save_source 落库决策（纯函数，可测）：
     * 输入 = 新书源 JSON 对象 + 既有项目列表（全库，调用方查好）+ newVersion 标志 + now。
     * 输出 = 要写的 ProjectEntity + 回包附加信息（冲突/版本号/是否新身份）。
     *
     * 规则（替代旧的 trimEnd('/') 合并）：
     * - 身份键 = SourceDedupeAnalyzer.canonicalUrlOf(bookSourceUrl)：规范化失败 → 原文兜底「r:原文」；
     *   同 URL 不同 JSON 一律 newProject 成为新版本，不静默覆盖；
     * - 完全相同（结构相等）且 newVersion=false → 覆写最新一条（幂等）；newVersion=true 也先查是否已有
     *   同内容版本，没有才追加（重复保存不产生空版本）。
     * - siteUrl 落库值 = 原始 bookSourceUrl（trim，不动尾部 / 与 #标签）；不再 trimEnd('/')。
     */
    fun decideSave(
        obj: JsonObject,
        newVersion: Boolean,
        all: List<ProjectEntity>,
        now: Long,
    ): SaveDecision {
        val finalJson = obj.toString()
        val bookSourceUrl = obj.get("bookSourceUrl")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val name = obj.get("bookSourceName")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().ifBlank { "未命名书源" }
        val siteUrl = bookSourceUrl // 落库 siteUrl = 原 bookSourceUrl（含 #标签/尾部/），不做 trimEnd 归并
        val canonical = SourceDedupeAnalyzer.canonicalUrlOf(bookSourceUrl)
        val bucketKey = when {
            !canonical.isNullOrEmpty() -> "u:$canonical"
            bookSourceUrl.isNotBlank() -> "r:$bookSourceUrl"
            else -> "s:$siteUrl"
        }
        // 同身份成员：clusterKey 一致（u: 规范化同 / r: 原文同 / s: siteUrl 兜底同）
        val sameIdentity = all.filter { existing ->
            val existingKey = runCatching {
                val exObj = JsonParser.parseString(existing.sourceJson).asJsonObject
                val exUrl = exObj.get("bookSourceUrl")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                val exCanon = SourceDedupeAnalyzer.canonicalUrlOf(exUrl)
                when {
                    !exCanon.isNullOrEmpty() -> "u:$exCanon"
                    exUrl.isNotBlank() -> "r:$exUrl"
                    else -> "s:${existing.siteUrl.trim()}"
                }
            }.getOrNull() ?: "s:${existing.siteUrl.trim()}" // 坏 JSON 按 siteUrl 兜底，绝不合并进 u:/r: 桶
            existingKey == bucketKey
        }
        val identical = sameIdentity.filter {
            runCatching { JsonParser.parseString(it.sourceJson) }.getOrNull() == JsonParser.parseString(finalJson)
        }
        val conflicts = sameIdentity.filter { it !in identical }

        return when {
            // 同身份+同内容已存在：幂等覆写最新一条（newVersion=true 也不另造副本——重复保存不产生空版本）
            identical.isNotEmpty() && !newVersion -> {
                val keep = identical.maxByOrNull { it.updatedAt }!!
                SaveDecision.Overwrite(
                    project = keep.copy(name = name, siteUrl = siteUrl, sourceJson = finalJson, stage = "VALIDATED", updatedAt = now),
                    identityKey = bucketKey, bookSourceUrl = bookSourceUrl,
                    note = "内容与现有版本完全一致，覆写保持最新（不新增空版本）",
                    conflicts = conflicts.map { it.id },
                )
            }
            identical.isNotEmpty() && newVersion -> {
                // newVersion=true 但内容没变：仍然不造副本，回执说明已是最新
                val keep = identical.maxByOrNull { it.updatedAt }!!
                SaveDecision.Overwrite(
                    project = keep.copy(name = name, siteUrl = siteUrl, sourceJson = finalJson, stage = "VALIDATED", updatedAt = now),
                    identityKey = bucketKey, bookSourceUrl = bookSourceUrl,
                    note = "请求了 newVersion 但内容与现有版本完全一致，未新增空版本（已覆写最新时间戳）",
                    conflicts = conflicts.map { it.id },
                )
            }
            else -> {
                // 同身份不同内容 → 一律新项目（显式版本）；无同身份也无冲突 → 全新身份新项目
                val reason = when {
                    conflicts.isNotEmpty() -> "同 bookSourceUrl 但 JSON 不同：按新版本保留（不覆盖既有，冲突请 analyze_sources 人工判定）"
                    newVersion -> "newVersion=true：追加新版本"
                    else -> "新身份入库"
                }
                SaveDecision.NewVersion(
                    project = ProjectEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name, siteUrl = siteUrl, sourceJson = finalJson,
                        stage = "VALIDATED", notes = "", createdAt = now, updatedAt = now,
                    ),
                    identityKey = bucketKey, bookSourceUrl = bookSourceUrl,
                    note = reason,
                    conflicts = conflicts.map { it.id },
                )
            }
        }
    }

    sealed class SaveDecision {
        abstract val project: ProjectEntity
        abstract val identityKey: String
        abstract val bookSourceUrl: String
        abstract val note: String
        abstract val conflicts: List<String>
        data class Overwrite(override val project: ProjectEntity, override val identityKey: String,
            override val bookSourceUrl: String, override val note: String, override val conflicts: List<String>) : SaveDecision()
        data class NewVersion(override val project: ProjectEntity, override val identityKey: String,
            override val bookSourceUrl: String, override val note: String, override val conflicts: List<String>) : SaveDecision()
    }

    private fun jsonTypeOf(v: kotlinx.serialization.json.JsonElement): String = when (v) {
        is kotlinx.serialization.json.JsonObject -> "object"
        is kotlinx.serialization.json.JsonArray -> "array"
        is kotlinx.serialization.json.JsonNull -> "null"
        else -> if (v.jsonPrimitive.booleanOrNull != null) "boolean" else "string"
    }
}
