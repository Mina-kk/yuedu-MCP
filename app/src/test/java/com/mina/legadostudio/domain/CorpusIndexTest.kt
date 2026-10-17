package com.mina.legadostudio.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Optional

class CorpusIndexTest {
    private val indexJson = """
    {
      "v": 1, "n": 3,
      "fam": {
        "f_0001": {"t": 0, "m": "GET", "c": 2, "s": 1024, "ex": ["i1"]},
        "f_0002": {"t": 2, "m": "POST", "c": 1, "s": 512, "ex": ["i2"]}
      },
      "rec": [
        {"i": 0, "d": "aaa.example.com", "n": "甲站文学", "t": 0, "f": "f_0001", "g": 1},
        {"i": 1, "d": "bbb.example.com", "n": "乙阁小说", "t": 0, "f": "f_0001", "g": 20},
        {"i": 2, "d": "ccc.manga.org", "n": "丙漫画", "t": 2, "f": "f_0002", "g": 8}
      ]
    }
    """.trimIndent()

    private val shards = mapOf(
        "f_0001" to """{"f":"f_0001","ids":["i0","i1"],"n":[{"bookSourceName":"甲站文学"},{"bookSourceName":"乙阁小说"}]}""",
        "f_0002" to """{"f":"f_0002","ids":["i2"],"n":[{"bookSourceName":"丙漫画"}]}""",
    )

    private fun build() = CorpusIndex(indexJson) { fid -> shards[fid] }

    @Test fun exactDomainMatchRanksFirst() {
        val hits = build().match("aaa.example.com")
        assertEquals(1, hits.size)
        assertEquals(0, hits[0].i)
        assertEquals("f_0001", hits[0].family)
    }

    @Test fun urlLikeQueryIsNormalizedToHost() {
        val hits = build().match("https://aaa.example.com/list/1.html")
        assertEquals("aaa.example.com", hits.first().domain)
    }

    @Test fun suffixAndContainsThenNameFallback() {
        val hits = build().match("example.com", limit = 10)
        assertEquals(listOf(0, 1), hits.map { it.i })  // 后缀命中两条，按 rec 顺序
        val byName = build().match("漫画")
        assertEquals(2, byName.single().i)
    }

    @Test fun noMatchReturnsEmpty() {
        assertTrue(build().match("nonexistent.xyz").isEmpty())
        assertTrue(build().match("").isEmpty())
    }

    @Test fun sourceJsonReadsThroughShardReader() = runBlocking {
        val index = build()
        assertEquals("""{"bookSourceName":"乙阁小说"}""", index.sourceJson(1))
        assertEquals(null, index.sourceJson(99))
    }

    @Test fun familyMetaAndExemplars() = runBlocking {
        val index = build()
        val meta = index.familyMeta("f_0001")!!
        assertEquals(2, meta.count)
        assertEquals("GET", meta.searchMethod)
        assertEquals(listOf("i1"), index.familyExemplars("f_0001"))
        assertEquals("f_0001", index.sourceFamily(1))
        assertEquals(null, index.familyMeta("f_9999"))
    }

    @Test fun sourceFamily_fallsBackWhenRecNotPositionallyAligned() {
        // 回归：rec 数组被过滤/重排后位置不等于全局序号。旧实现直接 records[i] 会把 i=2 读到 i=9 的族，
        // 导致 get_corpus_source(i) 从错误分片取文。位置命中必须校验 records[i].i == i，错位回退按序号查找。
        val misaligned = """{
          "v": 1, "n": 3,
          "fam": {
            "f_0001": {"t": 0, "m": "GET", "c": 1, "s": 10, "ex": ["i2"]},
            "f_0002": {"t": 0, "m": "GET", "c": 1, "s": 10, "ex": ["i7"]},
            "f_0003": {"t": 0, "m": "GET", "c": 1, "s": 10, "ex": ["i9"]}
          },
          "rec": [
            {"i": 2, "d": "bbb.example.com", "n": "乙阁小说", "t": 0, "f": "f_0001", "g": 1},
            {"i": 7, "d": "ggg.example.com", "n": "庚网文", "t": 0, "f": "f_0002", "g": 1},
            {"i": 9, "d": "jjj.example.com", "n": "壬刊", "t": 0, "f": "f_0003", "g": 1}
          ]
        }"""
        val shards = mapOf(
            "f_0001" to """{"f":"f_0001","ids":["i2"],"n":[{"bookSourceName":"乙阁小说"}]}""",
            "f_0002" to """{"f":"f_0002","ids":["i7"],"n":[{"bookSourceName":"庚网文"}]}""",
            "f_0003" to """{"f":"f_0003","ids":["i9"],"n":[{"bookSourceName":"壬刊"}]}""",
        )
        val index = CorpusIndex(misaligned) { fid -> shards[fid] }
        assertEquals("f_0001", index.sourceFamily(2))
        assertEquals("f_0002", index.sourceFamily(7))
        assertEquals("f_0003", index.sourceFamily(9))
        assertEquals(null, index.sourceFamily(3))
        // sourceJson 经 sourceFamily 定位分片，错位索引下也必须取到正确节点
        runBlocking { assertEquals("""{"bookSourceName":"壬刊"}""", index.sourceJson(9)) }
    }

    @Test fun mergeShardParts_twoParts_normalMerge() {
        val part0 = """{"f":"f1","p":0,"ids":["i0","i1"],"n":[{"a":1},{"a":2}]}"""
        val part1 = """{"f":"f1","p":1,"ids":["i2"],"n":[{"a":3}]}"""
        val result = mergeShardParts("f1", listOf(part0, part1))
        val root = JsonParser.parseString(result).asJsonObject
        assertEquals("f1", root.get("f").asString)
        val ids = root.getAsJsonArray("ids")
        val ns = root.getAsJsonArray("n")
        assertEquals(3, ids.size())
        assertEquals(3, ns.size())
        assertEquals(listOf("i0", "i1", "i2"), (0 until ids.size()).map { ids.get(it).asString })
    }

    @Test fun mergeShardParts_outOfOrder_reordersByP() {
        val part1 = """{"f":"f1","p":1,"ids":["i2"],"n":[{"a":3}]}"""
        val part0 = """{"f":"f1","p":0,"ids":["i0","i1"],"n":[{"a":1},{"a":2}]}"""
        val result = mergeShardParts("f1", listOf(part1, part0))
        val root = JsonParser.parseString(result).asJsonObject
        assertEquals(listOf("i0", "i1", "i2"), (0 until root.getAsJsonArray("ids").size()).map { root.getAsJsonArray("ids").get(it).asString })
    }

    @Test fun mergeShardParts_singleAndEmptyAndAllInvalid() {
        assertNull(mergeShardParts("f1", emptyList()))

        val single = """{"f":"f1","p":0,"ids":["i0"],"n":[{"a":1}]}"""
        val result = mergeShardParts("f1", listOf(single))
        val root = JsonParser.parseString(result).asJsonObject
        assertEquals(1, root.getAsJsonArray("ids").size())

        assertNull(mergeShardParts("f1", listOf("not json", "also not json")))
    }

    @Test fun mergeShardParts_badPartSkipped() {
        val bad = "not json"
        val good = """{"f":"f1","p":1,"ids":["i2"],"n":[{"a":3}]}"""
        val result = mergeShardParts("f1", listOf(bad, good))
        val root = JsonParser.parseString(result).asJsonObject
        assertEquals(1, root.getAsJsonArray("ids").size())
        assertEquals("i2", root.getAsJsonArray("ids").get(0).asString)
    }

    @Test fun sourceJson_readsAcrossMultipleShards() = runBlocking {
        val customIndexJson = """
            {
              "v": 1, "n": 3,
              "fam": {
                "f_0001": {"t": 0, "m": "GET", "c": 3, "s": 2048, "ex": ["i0"]}
              },
              "rec": [
                {"i": 0, "d": "aaa.example.com", "n": "甲站文学", "t": 0, "f": "f_0001", "g": 1},
                {"i": 1, "d": "bbb.example.com", "n": "乙阁小说", "t": 0, "f": "f_0001", "g": 20},
                {"i": 2, "d": "ccc.example.com", "n": "丙漫画", "t": 0, "f": "f_0001", "g": 8}
              ]
            }
        """.trimIndent()
        val multiPartShards = mapOf(
            "f_0001" to listOf(
                """{"f":"f_0001","p":0,"ids":["i0"],"n":[{"bookSourceName":"甲站文学"}]}""",
                """{"f":"f_0001","p":1,"ids":["i1"],"n":[{"bookSourceName":"乙阁小说"}]}""",
                """{"f":"f_0001","p":2,"ids":["i2"],"n":[{"bookSourceName":"丙漫画"}]}"""
            )
        )
        val index = CorpusIndex(customIndexJson) { fid ->
            multiPartShards[fid]?.let { mergeShardParts(fid, it) }
        }
        assertEquals("""{"bookSourceName":"乙阁小说"}""", index.sourceJson(1))
        assertEquals("""{"bookSourceName":"丙漫画"}""", index.sourceJson(2))
    }

    @Test fun sourceAndFamilyCountReflectRecords() {
        val index = build()
        assertEquals(3, index.sourceCount)
        assertEquals(2, index.familyCount)
    }

    // ---------- 真实 assets 全库去重验收（global-dedupe-rebuild 语料安装后生效） ----------

    private fun corpusAssetsDir(): File = listOf(
        File("src/main/assets/corpus"),
        File("app/src/main/assets/corpus"),
    ).first { it.isDirectory }

    private class ShardStore(private val dir: File) {
        private val shardsDir = File(dir, "shards")
        private val names = shardsDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .map { it.name }
            .toSet()
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Optional<String>>()

        fun text(fid: String): String? = cache.computeIfAbsent(fid) {
            val direct = "$fid.json"
            if (direct in names) {
                Optional.of(File(shardsDir, direct).readText())
            } else {
                val parts = names
                    .filter { it.startsWith("$fid-") && it.endsWith(".json") }
                    .mapNotNull { name ->
                        name.substringAfter("$fid-").removeSuffix(".json").toIntOrNull()?.let { it to name }
                    }
                    .sortedBy { it.first }
                    .map { File(shardsDir, it.second).readText() }
                Optional.ofNullable(if (parts.isEmpty()) null else mergeShardParts(fid, parts))
            }
        }.orElse(null)
    }

    /** 读真实 index + 最新 provenance，非全库去重构建时清楚失败（等 stage 安装后再跑）。 */
    private fun mergedCorpusIndex(): Pair<CorpusIndex, JsonObject> {
        val dir = corpusAssetsDir()
        val root = JsonParser.parseString(File(dir, "index.json").readText()).asJsonObject
        val provArray = root.getAsJsonArray("provenance")
            ?: throw AssertionError("index.json 无 provenance 数组：去重语料尚未安装")
        require(provArray.size() > 0) { "provenance 为空：去重语料尚未安装" }
        val prov = provArray.last().asJsonObject
        assertEquals(
            "最新 provenance 必须是全库去重重建（global-dedupe-rebuild）；当前语料可能仍是旧的追加式合入",
            "global-dedupe-rebuild", prov.get("mergePolicy")?.asString,
        )
        val store = ShardStore(dir)
        return CorpusIndex(root.toString()) { fid -> store.text(fid) } to prov
    }

    @Test fun rebuiltCorpus_countsAndSampledSourcesReadable() = runBlocking {
        val dir = corpusAssetsDir()
        val root = JsonParser.parseString(File(dir, "index.json").readText()).asJsonObject
        val (index, prov) = mergedCorpusIndex()
        // 计数契约：重建后 rec 就是主语料全集，计数全部动态读取、不硬编码预期条数
        val selected = prov.get("selectedSources")?.asInt
        if (selected != null) {
            assertEquals("provenance.selectedSources 与实际 rec 条数不符", selected, index.sourceCount)
        }
        assertEquals("root.n 与 rec 条数不符", root.get("n").asInt, index.sourceCount)
        assertEquals("familyCount 与 fam 条目数不符", root.getAsJsonObject("fam").size(), index.familyCount)
        // 全库去重会重编全局 i：旧编号不再稳定，但新编号必须连续覆盖 [0, n)
        val rec = root.getAsJsonArray("rec")
        val seen = HashSet<Int>(rec.size() * 2)
        for (e in rec) {
            val i = e.asJsonObject.get("i").asInt
            assertTrue("rec.i=$i 越界 [0, ${index.sourceCount})", i in 0 until index.sourceCount)
            assertTrue("rec.i=$i 重复", seen.add(i))
        }
        assertEquals("rec.i 未连续覆盖 [0, n)", index.sourceCount, seen.size)
        // 跨首/中/末均匀抽 100 条（不足则全量）：族归属、分片回读、meta/exemplars 一致性。
        // 分片完整性由 Python 侧全量验证，这里只做 JVM 抽样回读。
        val n = index.sourceCount
        val sampleCount = minOf(100, n)
        val sampleIs = if (sampleCount <= 1) {
            setOf(0)
        } else {
            (0 until sampleCount).map { k -> (k.toLong() * (n - 1) / (sampleCount - 1)).toInt() }.toSet()
        }
        val checkedFamilies = mutableSetOf<String>()
        for (i in sampleIs) {
            val fid = index.sourceFamily(i) ?: throw AssertionError("i=$i 无族")
            val meta = index.familyMeta(fid) ?: throw AssertionError("族 $fid 无 meta")
            assertTrue("族 $fid exemplars 超 6", meta.exemplars.size <= 6)
            val json = index.sourceJson(i) ?: throw AssertionError("i=$i 回读失败")
            val node = JsonParser.parseString(json).asJsonObject
            assertNotNull("i=$i 缺 bookSourceName", node.get("bookSourceName"))
            if (checkedFamilies.add(fid)) {
                val exIds = index.familyExemplars(fid)
                assertTrue("族 $fid exemplars 为空", exIds.isNotEmpty())
                for (exId in exIds) {
                    val exI = exId.removePrefix("i").toIntOrNull()
                        ?: throw AssertionError("族 $fid exemplar $exId 非 iN 格式")
                    assertEquals("exemplar $exId 族归属不符", fid, index.sourceFamily(exI))
                    assertNotNull("exemplar $exId 回读失败", index.sourceJson(exI))
                }
            }
        }
        assertTrue("抽样未覆盖任何族", checkedFamilies.isNotEmpty())
    }

    @Test fun rebuiltCorpus_selectedNewSource_matchAndRoundTrip() = runBlocking {
        val dir = corpusAssetsDir()
        val root = JsonParser.parseString(File(dir, "index.json").readText()).asJsonObject
        val rec = root.getAsJsonArray("rec")
        val (index, prov) = mergedCorpusIndex()
        val byI = HashMap<Int, JsonObject>(rec.size() * 2)
        val domainCount = mutableMapOf<String, Int>()
        val nameCount = mutableMapOf<String, Int>()
        for (e in rec) {
            val r = e.asJsonObject
            byI[r.get("i").asInt] = r
            domainCount.merge(r.get("d").asString.lowercase(), 1, Int::plus)
            nameCount.merge(r.get("n").asString.lowercase(), 1, Int::plus)
        }

        // 约定字段：selectedNewSourceIndices = 重建后所有 YCK 侧赢家的全局 i 列表。
        // 缺该字段时退化为全量 rec 挑代表样本，仍验证 match→sourceJson→familyExemplars 链路。
        val candidates = ArrayList<JsonObject>()
        val selArr = prov.getAsJsonArray("selectedNewSourceIndices")
        val label: String
        if (selArr != null) {
            label = "selectedNewSourceIndices"
            for (e in selArr) {
                candidates += byI[e.asInt]
                    ?: throw AssertionError("selectedNewSourceIndices 含未知 i=${e.asInt}")
            }
            require(candidates.isNotEmpty()) { "selectedNewSourceIndices 为空" }
        } else {
            label = "rec"
            for (e in rec) candidates += e.asJsonObject
        }

        fun toMatch(r: JsonObject) = CorpusMatch(
            i = r.get("i").asInt, domain = r.get("d").asString, name = r.get("n").asString,
            type = r.get("t").asInt, family = r.get("f").asString, flags = r.get("g").asInt,
        )

        var picked: CorpusMatch? = null
        var pickedQuery: String? = null
        // 优先选全库仅出现一次的域名：精确域命中可稳定反查回自身
        for (r in candidates) {
            val domain = r.get("d").asString
            if (domain.isNotBlank() && (domainCount[domain.lowercase()] ?: 0) == 1 &&
                index.match(domain).any { it.i == r.get("i").asInt }
            ) {
                picked = toMatch(r)
                pickedQuery = domain
                break
            }
        }
        // 退化一：全库唯一 name
        if (picked == null) {
            for (r in candidates) {
                val name = r.get("n").asString
                if (name.isNotBlank() && (nameCount[name.lowercase()] ?: 0) == 1 &&
                    index.match(name).any { it.i == r.get("i").asInt }
                ) {
                    picked = toMatch(r)
                    pickedQuery = name
                    break
                }
            }
        }
        // 退化二：候选中任取一条可被 match 命中的记录
        if (picked == null) {
            for (r in candidates) {
                val name = r.get("n").asString
                if (name.isNotBlank() && index.match(name).any { it.i == r.get("i").asInt }) {
                    picked = toMatch(r)
                    pickedQuery = name
                    break
                }
            }
        }

        val target = picked ?: throw AssertionError("$label 中无可 match 的源")
        val query = pickedQuery ?: throw AssertionError("pickedQuery 未随 picked 设置")
        // match → sourceJson → familyExemplars 完整链路
        val hits = index.match(query)
        assertTrue("match($query) 未命中 i=${target.i}", hits.any { it.i == target.i })
        val json = index.sourceJson(target.i)
        assertNotNull("i=${target.i} 回读失败", json)
        val exemplars = index.familyExemplars(target.family)
        assertTrue(exemplars.size <= 6)
        assertNotNull(index.familyMeta(target.family))
    }
}
