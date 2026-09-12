package com.mina.legadostudio.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
