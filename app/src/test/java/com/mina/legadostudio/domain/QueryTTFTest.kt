package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.QueryTTF
import com.mina.legadostudio.network.Woff2Decoder
import com.mina.legadostudio.runtime.RhinoEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * QueryTTF（通用字形轮廓比对）纯 JVM 单测。
 * fixtures 与 Woff2DecoderTest 共用（fontfix/）：
 * - font.ttf：cmap format4，中(U+4E2D)→gid1、文(U+6587)→gid1；gid1 轮廓为 1 轮廓 3 点 (0,0)(500,0)(500,700)。
 * - font.woff：同字体的 WOFF1 封装（含完整 glyf，可解析轮廓）。
 * - font.woff2：glyf/loca 带 transformed glyf（transformVersion=0），QueryTTF 必须明确拒绝。
 * 另用 remapFormat4Cmap 在内存里把 font.ttf 的 cmap 改写成「私有码位→gid1」模拟 cmap 打乱字体。
 */
class QueryTTFTest {

    private fun res(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("fontfix/$name")!!.readBytes()

    private fun be16(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

    private fun be32(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
            ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

    /**
     * 把 font.ttf 的 cmap format4 子表改写成 (startCp→gid1)，其余字节原样——
     * 模拟「同一个字形、不同编码位」的 cmap 打乱字体。
     * font.ttf cmap format4 @subOff：segCount=3，段为 [start1,start1]→gid1 / [start2,start2]→gid1 / [0xFFFF,0xFFFF]。
     */
    private fun remapFormat4Cmap(ttf: ByteArray, startCp1: Int, startCp2: Int = startCp1): ByteArray {
        val d = ttf.copyOf()
        val numTables = be16(d, 4)
        var cmapOff = -1
        for (i in 0 until numTables) {
            val p = 12 + i * 16
            if (d.copyOfRange(p, p + 4).decodeToString() == "cmap") cmapOff = be32(d, p + 8)
        }
        check(cmapOff > 0)
        // format4 子表（offset 20 起）：startCode 数组在 base+16+segCountX2，seg1@+0、seg2@+2
        val sub = cmapOff + 20
        val segX2 = be16(d, sub + 6)
        val startBase = sub + 16 + segX2
        d[startBase] = (startCp1 ushr 8).toByte(); d[startBase + 1] = startCp1.toByte()
        d[startBase + 2] = (startCp2 ushr 8).toByte(); d[startBase + 3] = startCp2.toByte()
        // endCode 同步成同值（单码点段）：endBase = sub+14，seg1@+0、seg2@+2
        val endBase = sub + 14
        d[endBase] = (startCp1 ushr 8).toByte(); d[endBase + 1] = startCp1.toByte()
        d[endBase + 2] = (startCp2 ushr 8).toByte(); d[endBase + 3] = startCp2.toByte()
        // idDelta = gid - cp（mod 2^16）：gid 恒为 1
        val deltaBase = startBase + segX2
        val d1 = (1 - startCp1) and 0xFFFF
        val d2 = (1 - startCp2) and 0xFFFF
        d[deltaBase] = (d1 ushr 8).toByte(); d[deltaBase + 1] = d1.toByte()
        d[deltaBase + 2] = (d2 ushr 8).toByte(); d[deltaBase + 3] = d2.toByte()
        return d
    }

    @Test fun parsesTtfAndBuildsGlyphFingerprint() {
        val q = QueryTTF(res("font.ttf"))
        assertEquals(1, q.getGlyfIdByUnicode(0x4E2D))
        assertEquals(1, q.getGlyfIdByUnicode(0x6587))
        // gid1 轮廓：3 点相对坐标 (0,0)|(500,0)|(0,700) —— 与 fixture glyf 字节一致
        val glyf = q.getGlyfByUnicode(0x4E2D)
        assertEquals("0,0|500,0|0,700", glyf)
        assertEquals(0x4E2D, q.getUnicodeByGlyf(glyf))
    }

    @Test fun parsesWoff1ToSameFingerprint() {
        val q = QueryTTF(res("font.woff"))
        assertEquals("0,0|500,0|0,700", q.getGlyfByUnicode(0x4E2D))
    }

    @Test fun rejectsWoff2WithTransformedGlyf() {
        val err = runCatching { QueryTTF(res("font.woff2")) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message.orEmpty().contains("transformed glyf"))
    }

    @Test fun replaceFontRoundTripsAcrossShuffledCmap() {
        // 错误字体：同一个字形被编到私有码位 U+E000；正确字体：正常 中→该字形
        val error = QueryTTF(remapFormat4Cmap(res("font.ttf"), 0xE000))
        val correct = QueryTTF(res("font.ttf"))
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val result = evaluator.evaluateRaw(
            """java.replaceFont(text, err, ok)""".trimIndent(),
            bindings = mapOf("text" to "AB", "err" to error, "ok" to correct),
        )
        assertEquals("A中B", result.value)
    }

    @Test fun replaceFontFilterDropsGlyphlessChars() {
        val error = QueryTTF(remapFormat4Cmap(res("font.ttf"), 0xE000))
        val correct = QueryTTF(res("font.ttf"))
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        // 官方语义：filter=true 删除「在 error 字体里无轮廓」的字符——
        // 'A'/'B' 不在 error 字体 cmap 里（glyphId=0），被删；
        // U+E000 有轮廓 → 反查 correct 字体 → 还原为 中。
        val filtered = evaluator.evaluateRaw(
            """java.replaceFont(text, err, ok, true)""",
            bindings = mapOf("text" to "AB", "err" to error, "ok" to correct),
        )
        assertEquals("中", filtered.value)
        // filter 缺省 false：无轮廓字符保留原字
        val kept = evaluator.evaluateRaw(
            """java.replaceFont(text, err, ok)""",
            bindings = mapOf("text" to "AB", "err" to error, "ok" to correct),
        )
        assertEquals("A中B", kept.value)
    }

    @Test fun jsQueryTtfAcceptsBase64AndHoldsObject() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        val b64 = Base64.getEncoder().encodeToString(res("font.ttf"))
        val value = evaluator.evaluateRaw(
            """
            var ttf = java.queryTTF("$b64");
            ttf.getGlyfByUnicode(0x4E2D);
            """.trimIndent()
        )
        assertEquals("0,0|500,0|0,700", value.value)
    }

    @Test fun jsQueryTtfEndToEndReplaceWithShuffledFont() {
        val evaluator = RhinoEvaluator(HttpFetcher(), Gson())
        // 正确字体 base64 + 打乱字体 base64 都经 JS 侧 queryTTF 解析再 replaceFont
        val okB64 = Base64.getEncoder().encodeToString(res("font.ttf"))
        val errB64 = Base64.getEncoder().encodeToString(remapFormat4Cmap(res("font.ttf"), 0xE000))
        val value = evaluator.evaluateRaw(
            """
            var ok = java.queryTTF("$okB64");
            var err = java.queryTTF("$errB64");
            java.replaceFont("XY", err, ok);
            """.trimIndent()
        )
        assertEquals("X中Y", value.value)
    }

    @Test fun rejectsNonFontInput() {
        assertTrue(runCatching { QueryTTF("<html>".toByteArray()) }.isFailure)
        assertTrue(runCatching { QueryTTF(byteArrayOf(0x50, 0x4B, 3, 4)) }.isFailure) // zip
    }
}
