package com.mina.legadostudio.domain

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/**
 * 语料索引匹配结果
 */
@Keep
data class CorpusMatch(
    val i: Int,
    val domain: String,
    val name: String,
    val type: Int,
    val family: String,
    val flags: Int,
)

/**
 * 族元数据
 */
@Keep
data class CorpusFamilyMeta(
    val family: String,
    val type: Int,
    val searchMethod: String,
    val count: Int,
    val shardBytes: Int,
    val exemplars: List<String>,
)

/**
 * 语料纯逻辑索引类（便于离线及单元测试）
 */
class CorpusIndex(
    indexJson: String,
    private val shardReader: suspend (fid: String) -> String?,
) {
    private val records: List<CorpusMatch>
    private val families: Map<String, CorpusFamilyMeta>

    init {
        val root = JsonParser.parseString(indexJson).asJsonObject
        val recArray = root.getAsJsonArray("rec")
        val recList = ArrayList<CorpusMatch>(recArray.size())
        for (elem in recArray) {
            val obj = elem.asJsonObject
            recList.add(
                CorpusMatch(
                    i = obj.get("i")?.asInt ?: 0,
                    domain = obj.get("d")?.asString.orEmpty(),
                    name = obj.get("n")?.asString.orEmpty(),
                    type = obj.get("t")?.asInt ?: 0,
                    family = obj.get("f")?.asString.orEmpty(),
                    flags = obj.get("g")?.asInt ?: 0,
                )
            )
        }
        records = recList

        val famObj = root.getAsJsonObject("fam")
        val famMap = LinkedHashMap<String, CorpusFamilyMeta>()
        if (famObj != null) {
            for ((fid, node) in famObj.entrySet()) {
                val fObj = node.asJsonObject
                val exList = mutableListOf<String>()
                fObj.getAsJsonArray("ex")?.forEach {
                    exList.add(it.asString)
                }
                famMap[fid] = CorpusFamilyMeta(
                    family = fid,
                    type = fObj.get("t")?.asInt ?: 0,
                    searchMethod = fObj.get("m")?.asString.orEmpty(),
                    count = fObj.get("c")?.asInt ?: 0,
                    shardBytes = fObj.get("s")?.asInt ?: 0,
                    exemplars = exList,
                )
            }
        }
        families = famMap
    }

    /**
     * 规范化用户查询，转小写、去空白、去协议前缀和路径
     */
    fun normalizeQuery(query: String): String {
        var q = query.trim().lowercase()
        if (q.isEmpty()) return ""
        // 去除协议头
        if (q.contains("://")) {
            q = q.substringAfter("://")
        }
        // 去除路径和参数
        val slashIdx = q.indexOf('/')
        if (slashIdx != -1) {
            q = q.substring(0, slashIdx)
        }
        val qIdx = q.indexOf('?')
        if (qIdx != -1) {
            q = q.substring(0, qIdx)
        }
        val portIdx = q.indexOf(':')
        if (portIdx != -1) {
            q = q.substring(0, portIdx)
        }
        return q.trim()
    }

    /**
     * 搜索书源语料
     * 排序优先级：
     * 1. 域名精确命中 (domain == query)
     * 2. 域名后缀命中 (query 是 domain 后缀，如 .example.com 或结尾对齐)
     * 3. 域名包含 (domain 包含 query)
     * 4. 名称包含 (name 包含 query, 不区分大小写)
     */
    fun match(query: String, limit: Int = 20): List<CorpusMatch> {
        val cleanQuery = normalizeQuery(query)
        if (cleanQuery.isEmpty()) return emptyList()

        val exactMatches = mutableListOf<CorpusMatch>()
        val suffixMatches = mutableListOf<CorpusMatch>()
        val domainContains = mutableListOf<CorpusMatch>()
        val nameContains = mutableListOf<CorpusMatch>()

        for (rec in records) {
            val d = rec.domain.lowercase()
            val n = rec.name.lowercase()

            when {
                d == cleanQuery -> exactMatches.add(rec)
                d.endsWith(".$cleanQuery") || (cleanQuery.contains('.') && d.endsWith(cleanQuery)) -> suffixMatches.add(rec)
                d.contains(cleanQuery) -> domainContains.add(rec)
                n.contains(cleanQuery) -> nameContains.add(rec)
            }
        }

        val result = ArrayList<CorpusMatch>(limit)
        fun addAllUntilLimit(list: List<CorpusMatch>) {
            for (item in list) {
                if (result.size >= limit) return
                result.add(item)
            }
        }

        addAllUntilLimit(exactMatches)
        addAllUntilLimit(suffixMatches)
        addAllUntilLimit(domainContains)
        addAllUntilLimit(nameContains)

        return result
    }

    /**
     * 获取指定族样例 ID 列表
     */
    fun familyExemplars(fid: String): List<String> {
        return families[fid]?.exemplars.orEmpty()
    }

    /**
     * 获取指定族元数据
     */
    fun familyMeta(fid: String): CorpusFamilyMeta? {
        return families[fid]
    }

    /**
     * 根据书源全局序号 i 获取所属族 ID
     */
    fun sourceFamily(i: Int): String? {
        if (i in records.indices) {
            return records[i].family
        }
        return records.firstOrNull { it.i == i }?.family
    }

    /**
     * 获取指定书源的完整清洗后 JSON 文本
     */
    suspend fun sourceJson(i: Int): String? {
        val targetFid = sourceFamily(i) ?: return null
        val targetSid = "i$i"

        val shardText = shardReader(targetFid) ?: return null
        try {
            val root = JsonParser.parseString(shardText).asJsonObject
            val idsArray = root.getAsJsonArray("ids") ?: return null
            val nodesArray = root.getAsJsonArray("n") ?: return null

            for (idx in 0 until idsArray.size()) {
                if (idsArray.get(idx).asString == targetSid) {
                    return nodesArray.get(idx).toString()
                }
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }
}

/**
 * Android 语料仓库薄壳
 */
class CorpusRepository(private val context: Context) {
    private val shardFileNames: List<String> by lazy {
        try {
            context.assets.list("corpus/shards")?.sorted().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val index: CorpusIndex by lazy {
        val indexText = context.assets.open("corpus/index.json").bufferedReader().use { it.readText() }
        CorpusIndex(indexText) { fid ->
            readShardText(fid)
        }
    }

    /**
     * 读取指定分片文本，处理普通分片与可能拆分的多分片
     */
    private fun readShardText(fid: String): String? {
        val directName = "$fid.json"
        if (directName in shardFileNames) {
            return runCatching {
                context.assets.open("corpus/shards/$directName").bufferedReader().use { it.readText() }
            }.getOrNull()
        }

        // 尝试多分片合并或查找 <fid>-0.json ...
        val partMatches = shardFileNames.filter { it.startsWith("$fid-") && it.endsWith(".json") }
        if (partMatches.isEmpty()) return null

        // 单独一个 part 或依次读取
        for (partName in partMatches) {
            val text = runCatching {
                context.assets.open("corpus/shards/$partName").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue

            // 检查当前 part 是否包含我们可能需要的数据（或者直接返回第一段）
            return text
        }

        return null
    }

    fun match(query: String, limit: Int = 20): List<CorpusMatch> = index.match(query, limit)

    fun familyExemplars(fid: String): List<String> = index.familyExemplars(fid)

    fun familyMeta(fid: String): CorpusFamilyMeta? = index.familyMeta(fid)

    fun sourceFamily(i: Int): String? = index.sourceFamily(i)

    suspend fun sourceJson(i: Int): String? = index.sourceJson(i)
}
