package com.mina.legadostudio.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.gson.JsonArray
import com.google.gson.JsonParser

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
}
