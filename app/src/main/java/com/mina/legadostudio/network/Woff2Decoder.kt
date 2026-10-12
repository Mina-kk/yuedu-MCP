package com.mina.legadostudio.network

import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 纯 JVM 的 WOFF2/WOFF/TTF/OTF 字体解码与 cmap 解析。
 *
 * - WOFF2：目录项逐表 Brotli 解压（transformLength=0 时按原样透传），重建 SFNT 头部+目录后再解析；
 *   okhttp-brotli 只处理 HTTP Content-Encoding，不会解 WOFF2 内部的 Brotli，必须在这里做。
 * - WOFF/TTF/OTTO：按 SFNT 目录直接定位 cmap 表。
 * - cmap：format 4（BMP 段映射）与 format 12（分组覆盖全 Unicode）；format 0/6/10/14 不支持时报错。
 *   返回 codepoint → glyphId（**不是字形名/汉字**；glyphId 到真实汉字的比对属于后续的轮廓比对问题）。
 * - 全程严格边界校验：表偏移/长度不许越过输入，索引不许越过表长，结果映射数硬上限，
 *   任何畸形输入一律抛出带原因的 IllegalArgumentException，绝不静默产出半成品。
 */
object Woff2Decoder {

    /** 防御上限：字体本身/解压输出/单表/映射数。aaawz 类反爬字体只有几十 KB，这些值远高于实际需求。 */
    const val MAX_INPUT_BYTES = 4 * 1024 * 1024
    /** 解压后 SFNT 总字节上限：单表 MAX_TABLE_BYTES × 表数不会再超此；也是 woff2 逐表解压累计上限。 */
    const val MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024
    const val MAX_TABLE_BYTES = 2 * 1024 * 1024
    const val MAX_NUM_TABLES = 64
    const val MAX_MAPPINGS = 200_000
    private const val WOFF2_HEADER_SIZE = 48
    /** 重建出的 SFNT 总大小不得超过解压上限：与逐表 MAX_TABLE_BYTES/MAX_DECOMPRESSED_BYTES 对齐。 */
    private const val WOFF2_MAX_FLAVOR_SIZE = MAX_DECOMPRESSED_BYTES.toLong()
    private val SFNT_MAGICS = setOf(0x00010000, 0x4F54544F /* OTTO */, 0x74727565 /* true */, 0x74797031 /* typ1 */)

    /** 结果：sfnt 魔数(0x00010000/OTTO)、码点→glyphId 映射、命中的 cmap 子表格式、原字体规格。 */
    data class Decoded(
        val sfntVersion: Int,
        val cmapFormat: Int,
        val mappings: Map<Int, Int>,
        val numTables: Int,
    ) {
        /** 常用工具方法：按 Unicode 字符串逐码点给出 glyphId（无映射的码点跳过）。 */
        fun glyphIdsFor(text: String): List<Pair<Int, Int>> {
            val out = mutableListOf<Pair<Int, Int>>()
            text.codePoints().forEach { cp -> mappings[cp]?.let { out += cp to it } }
            return out
        }

        /**
         * 人类可读映射行：每行 "U+XXXX\tgid"（按码点排序），供 MCP get_font_map 分页返回。
         * 注意：gid 是字体内部字形序号，不等于汉字；gid→实际汉字的对应需要独立字形比对解决。
         */
        fun mappingLines(): List<String> =
            mappings.entries.sortedBy { it.key }.map { (cp, gid) -> "U+%06X\t%d".format(cp, gid) }
    }

    private class Reader(val data: ByteArray) {
        var pos = 0
        fun u8(): Int { check(pos + 1 <= data.size) { "越界读取 u8 @${pos}" }; return data[pos++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun u32s(): Int { val v = u32(); check(v <= Int.MAX_VALUE) { "u32 超出 int 范围" }; return v.toInt() }
        fun bytes(n: Int): ByteArray { check(n >= 0 && pos + n <= data.size) { "越界读取 $n 字节 @${pos}/${data.size}" }; return data.copyOfRange(pos, pos + n).also { pos += n } }
        fun skip(n: Long) { check(n >= 0 && pos + n <= data.size) { "越界 skip $n @${pos}/${data.size}" }; pos += n.toInt() }
    }

    private data class TableEntry(val tag: String, val offset: Int, val length: Int)

    /** 魔数快检：wOF2/wOFF/0x00010000(TTF)/OTTO/true/typ1 之一即放行；供 HttpFetcher 放行前校验。 */
    fun looksLikeFont(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val sig = bytes.copyOfRange(0, 4).decodeToString()
        if (sig == "wOF2" || sig == "wOFF") return true
        val magic = ((bytes[0].toLong() and 0xFF) shl 24) or ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or (bytes[3].toLong() and 0xFF)
        return SFNT_MAGICS.any { it.toLong() == magic }
    }

    /**
     * 输入任意支持字体（WOFF2/WOFF/TTF/OTTO），解包/还原成 sfnt(TTF/OTF) 裸字节流：
     * - wOF2/wOFF → 逐表解压重建 SFNT 目录+数据；裸 sfnt（TTF/OTTO/true/typ1）原样返回。
     * - woff2 的 glyf/loca 若带 transformed glyf（transformVersion≠3）会在内部抛
     *   IllegalArgumentException：此处不实现 glyf 逆变换，重建出的占位数据对轮廓解析无意义。
     *   未变换的 woff2（fontTools 等产出 transformVersion=3 的 glyf/loca）与 woff/ttf/otf 正常。
     * 供 QueryTTF 这类需要完整 sfnt（glyf/loca 轮廓）的消费方使用；只要 cmap 用 decode()。
     */
    fun unpackToSfnt(font: ByteArray): ByteArray {
        require(font.size >= 4) { "输入太短，不是字体文件" }
        require(font.size <= MAX_INPUT_BYTES) { "FONT_TOO_LARGE：输入超过 ${MAX_INPUT_BYTES / 1024 / 1024}MB" }
        val signature = font.copyOfRange(0, 4).decodeToString()
        return when (signature) {
            "wOF2" -> unpackWoff2(font, forGlyph = true)
            "wOFF" -> unpackWoff(font)
            else -> {
                val magic = be32(font, 0)
                require(SFNT_MAGICS.any { it.toLong() == magic }) { "不支持的字体魔数 $signature（仅支持 wOF2/wOFF/TTF/OTTO）" }
                font
            }
        }
    }

    /** 输入任意支持字体（WOFF2/WOFF/TTF/OTTO），返回解析结果；非字体输入抛 IllegalArgumentException。 */
    fun decode(font: ByteArray): Decoded {
        require(font.size >= 4) { "输入太短，不是字体文件" }
        require(font.size <= MAX_INPUT_BYTES) { "FONT_TOO_LARGE：输入超过 ${MAX_INPUT_BYTES / 1024 / 1024}MB" }
        val signature = font.copyOfRange(0, 4).decodeToString()
        // decode 只读 cmap：glyf/loca 的 transform 不逆变换也能正确读出 cmap（占位+按 origLength 布局，
        // cmap 内容与偏移不受影响）——这里 forGlyph=false 容忍变换，与 unpackToSfnt（forGlyph=true 严格拒绝）区分。
        val sfnt = when (signature) {
            "wOF2" -> unpackWoff2(font, forGlyph = false)
            "wOFF" -> unpackWoff(font)
            else -> {
                val magic = be32(font, 0)
                require(SFNT_MAGICS.any { it.toLong() == magic }) { "不支持的字体魔数 $signature（仅支持 wOF2/wOFF/TTF/OTTO）" }
                font
            }
        }
        val tables = sfntTables(sfnt)
        val cmap = tables.firstOrNull { it.tag == "cmap" }
            ?: throw IllegalArgumentException("字体缺少 cmap 表")
        val cmapData = sfnt.copyOfRange(cmap.offset, cmap.offset + cmap.length)
        val (format, mappings) = parseCmap(cmapData)
        return Decoded(be32(sfnt, 0).toInt(), format, mappings, tables.size)
    }

    /**
     * 便捷方法：从文本中提取「在 cmap 里有映射」的码点→glyphId。
     * 用于反爬字体替换场景：正文里的私有码位/错序汉字先映射到 glyphId 再做字形比对。
     */
    fun decodeMappings(font: ByteArray): Map<Int, Int> = decode(font).mappings

    // ---------- WOFF1 ----------
    // wOFF 头 44B：sig,flavor,length,numTables,reserved,totalSfntSize,majVer,minVer,
    // metaOffset,metaLength,metaOrigLength,privOffset,privLength；
    // 目录 20B/表：tag,offset,compLength,origLength,checksum。comp<orig 时 zlib 压缩，否则原样。
    private fun unpackWoff(src: ByteArray): ByteArray {
        require(src.size >= 44) { "wOFF 头太短" }
        require(be32s(src, 8) == src.size) { "wOFF length 与文件大小不符" }
        val flavor = be32(src, 4)
        require(flavor == 0x00010000L || flavor == 0x4F54544FL) { "wOFF flavor 不支持" }
        val numTables = be16(src, 12)
        require(numTables in 1..MAX_NUM_TABLES) { "wOFF numTables 越界：$numTables" }
        require(44 + numTables * 20 <= src.size) { "wOFF 目录越界" }
        data class WD(val tag: String, val off: Int, val compLen: Int, val origLen: Int)
        val dirs = (0 until numTables).map {
            val p = 44 + it * 20
            val tag = src.copyOfRange(p, p + 4).decodeToString()
            val off = be32s(src, p + 4); val compLen = be32s(src, p + 8); val origLen = be32s(src, p + 12)
            require(off >= 0 && compLen >= 0 && off.toLong() + compLen <= src.size) { "wOFF 表 $tag 区间越界" }
            require(origLen <= MAX_TABLE_BYTES) { "wOFF 表 $tag 原始长度过大" }
            WD(tag, off, compLen, origLen)
        }
        // 各表 origLen 之和同样受解压总量上限约束（与 woff2 路径一致，numTables×MAX_TABLE_BYTES 不能撑爆内存）
        require(dirs.sumOf { it.origLen.toLong() } <= MAX_DECOMPRESSED_BYTES) { "wOFF 各表 origLen 总和超限" }
        // 重建 SFNT：目录按原 tag 顺序，数据按 origLen 布局（解压到 origLen，未压缩表原样）
        val headerSize = 12 + numTables * 16
        var offset = headerSize
        val entries = mutableListOf<TableEntry>()
        val tableData = HashMap<String, ByteArray>()
        val inflater = java.util.zip.Inflater()
        try {
            for (d in dirs) {
                val raw = src.copyOfRange(d.off, d.off + d.compLen)
                val data = if (d.compLen < d.origLen) {
                    inflater.reset(); inflater.setInput(raw)
                    val buf = ByteArray(d.origLen)
                    var got = 0
                    while (got < d.origLen && !inflater.finished()) {
                        val n = inflater.inflate(buf, got, d.origLen - got)
                        if (n <= 0) break
                        got += n
                    }
                    require(got == d.origLen) { "wOFF 表 ${d.tag} zlib 解压长度不符（$got/${d.origLen}）" }
                    buf
                } else {
                    require(d.compLen == d.origLen) { "wOFF 表 ${d.tag} compLen>origLen 非法" }
                    raw
                }
                offset = (offset + 3) and 3.inv()
                entries += TableEntry(d.tag, offset, d.origLen)
                tableData[d.tag] = data
                offset += d.origLen
            }
        } finally { inflater.end() }
        val sfnt = ByteArrayOutputStream(offset)
        writeU32(sfnt, flavor); writeU16(sfnt, numTables)
        val maxPow2 = Integer.highestOneBit(numTables)
        writeU16(sfnt, maxPow2 * 16); writeU16(sfnt, 31 - Integer.numberOfLeadingZeros(maxPow2)); writeU16(sfnt, numTables * 16 - maxPow2 * 16)
        for (e in entries) {
            sfnt.write(e.tag.toByteArray(Charsets.US_ASCII))
            writeU32(sfnt, 0); writeU32(sfnt, e.offset); writeU32(sfnt, e.length)
        }
        var dataOffset = headerSize
        for (d in dirs) {
            dataOffset = (dataOffset + 3) and 3.inv()
            while (sfnt.size() < dataOffset) sfnt.write(0)
            sfnt.write(tableData.getValue(d.tag))
            dataOffset += d.origLen
        }
        return sfnt.toByteArray()
    }

    // ---------- WOFF2 ----------

    private fun unpackWoff2(src: ByteArray, forGlyph: Boolean = false): ByteArray {
        val r = Reader(src)
        require(r.u32s() == 0x774F4632) { "非 wOF2 签名" }
        val flavor = r.u32s()
        val length = r.u32()
        r.u16(); r.u16() // numTables, reserved
        val totalSfntSize = r.u32()
        val totalCompressedSize = r.u32()
        r.u32() // major/minor version packed
        val metaOffset = r.u32(); val metaLength = r.u32(); val metaOrigLength = r.u32()
        val privOffset = r.u32(); val privLength = r.u32()
        check(r.pos == WOFF2_HEADER_SIZE)
        // 头部声明一致性与硬上限
        check(length == src.size.toLong()) { "woff2 header length($length) 与文件实际大小(${src.size})不一致" }
        check(totalSfntSize in 1..WOFF2_MAX_FLAVOR_SIZE) { "totalSfntSize 越界：$totalSfntSize" }
        // totalCompressedSize 是压缩流本身长度，不得比文件剩余还大；它同时是 brotli 读入上限
        check(totalCompressedSize in 1..src.size.toLong()) { "totalCompressedSize 越界：$totalCompressedSize" }
        check(metaLength == 0L || metaOffset + metaLength <= src.size) { "metadata 越界" }
        check(privLength == 0L || privOffset + privLength <= src.size) { "private data 越界" }
        require(flavor == 0x00010000 || flavor == 0x4F54544F) { "仅支持 TTF/OTTO 风味的 woff2" }

        val numTables = be16(src, 12)
        require(numTables in 1..MAX_NUM_TABLES) { "numTables 越界：$numTables" }
        check(WOFF2_HEADER_SIZE + numTables <= src.size) { "目录区越界" }

        // 逐表目录：flags(1B) + tag(4B 若 flags&0x3F==0x3F) + origLength(UIntBase128) + transformLength(UIntBase128,若有变换)
        data class Dir(val tag: String, val flags: Int, val orig: Int, val trans: Int, val hasTransform: Boolean)
        val dirs = mutableListOf<Dir>()
        for (i in 0 until numTables) {
            val flags = r.u8()
            val tag = if ((flags and 0x3F) == 0x3F) r.bytes(4).decodeToString() else WOFF2_KNOWN_TAGS[flags and 0x3F]
            val orig = readUIntBase128(r)
            val transformVersion = (flags ushr 6) and 0x03
            // glyf/loca 的变换规则特殊：version 3 = null transform（无变换）；其余表 version != 0 才有 transformLength
            val hasTransform = if (tag == "glyf" || tag == "loca") transformVersion != 3 else transformVersion != 0
            val trans = if (hasTransform) readUIntBase128(r) else orig
            check(orig <= MAX_TABLE_BYTES) { "表 $tag 原始长度过大：$orig" }
            check(trans <= MAX_TABLE_BYTES) { "表 $tag 变换长度过大：$trans" }
            dirs += Dir(tag, flags, orig, trans, hasTransform)
        }
        // 逐表 origLength 之和不能超 SFNT 总声明：否则解压总量会突破上限
        check(dirs.sumOf { it.orig.toLong() } <= totalSfntSize + numTables * 16 + 64) {
            "各表 origLength 之和超过 totalSfntSize"
        }
        // cmap 自身必须无变换才能正确读出码点映射。
        dirs.firstOrNull { it.tag == "cmap" && it.hasTransform }
            ?.let { throw IllegalArgumentException("cmap 表带变换（transformVersion≠0），无法安全还原码点映射") }
        // transformed glyf 逆变换（三角化点阵→二次轮廓）未实现：
        // - forGlyph=true（QueryTTF 轮廓解析路径）：带变换时 brotli 流里只有变换态数据，重建出的
        //   glyf/loca 是占位字节，轮廓指纹必错——直接拒绝而不是静默产出错误指纹；
        // - forGlyph=false（decode 只读 cmap）：按 transformLength 读占位、目录仍按 origLength 布局，
        //   cmap 内容与偏移不受影响，容忍并正常产出。
        if (forGlyph) {
            dirs.firstOrNull { (it.tag == "glyf" || it.tag == "loca") && it.hasTransform }
                ?.let { throw IllegalArgumentException("woff2 glyf/loca 表带 transformed glyf（transformVersion≠3），未实现逆变换：请改用未变换的 woff2/woff/ttf，或先拿到解码后的明文 TTF") }
        }

        val compressedStart = r.pos
        check(compressedStart <= src.size) { "压缩数据区起点越界" }
        // 压缩流只切到 totalCompressedSize：尾部 metadata/private data 不属于 brotli 流，
        // 多切会让 brotli 读到非压缩字节。声明的压缩区必须完整落在文件内——不许截断容忍，
        // 否则声明越界的畸形文件会静默通过。
        check(compressedStart.toLong() + totalCompressedSize <= src.size) {
            "压缩数据区越过文件尾（start=$compressedStart compressedSize=$totalCompressedSize fileSize=${src.size}）"
        }
        val compressedEnd = compressedStart + totalCompressedSize.toInt()
        val compressed = src.copyOfRange(compressedStart, compressedEnd)
        val brotli = BrotliInputStream(ByteArrayInputStream(compressed))
        // 逐表解压：每个表读它自己的 transformLength（无变换读 origLength 字节原样数据）。
        // 除了单表上限还累计 totalDecoded：numTables×MAX_TABLE_BYTES 不能撑爆内存。
        val tableData = HashMap<String, ByteArray>()
        var totalDecoded = 0L
        for (dir in dirs) {
            val need = if (dir.hasTransform) dir.trans else dir.orig
            check(need <= MAX_TABLE_BYTES) { "表 ${dir.tag} 解压长度过大：$need" }
            totalDecoded += need
            check(totalDecoded <= MAX_DECOMPRESSED_BYTES) { "解压总量超过 ${MAX_DECOMPRESSED_BYTES / 1024 / 1024}MB" }
            val buf = ByteArray(need)
            var off = 0
            while (off < need) {
                val n = brotli.read(buf, off, need - off)
                if (n < 0) throw IllegalArgumentException("woff2 brotli 流提前结束（表 ${dir.tag} 需要 $need 字节）")
                off += n
            }
            tableData[dir.tag] = buf
        }
        brotli.close()

        // 重建 SFNT：header + 目录 + 逐表 4 字节对齐数据
        val headerSize = 12 + numTables * 16
        var offset = headerSize
        val entries = mutableListOf<TableEntry>()
        for (dir in dirs) {
            val data = tableData.getValue(dir.tag)
            // 变换表读的是 transformLength 字节，但 SFNT 目录按 origLength 布局；
            // cmap 已强制无变换，只有 glyf/loca/其他表可能是变换数据，目录长度仍写 orig。
            check((if (dir.hasTransform) dir.trans else dir.orig).toLong() == data.size.toLong()) {
                "表 ${dir.tag} 解压后长度不符"
            }
            offset = (offset + 3) and 3.inv()
            entries += TableEntry(dir.tag, offset, dir.orig)
            offset += dir.orig
        }
        check(offset.toLong() <= totalSfntSize + numTables * 3 + 64) { "重建 SFNT 大小异常" }
        val sfnt = ByteArrayOutputStream(offset)
        writeU32(sfnt, flavor); writeU16(sfnt, numTables)
        // searchRange 等字段可算可不算——读取方只按目录走
        val maxPow2 = Integer.highestOneBit(numTables)
        writeU16(sfnt, maxPow2 * 16); writeU16(sfnt, 31 - Integer.numberOfLeadingZeros(maxPow2)); writeU16(sfnt, numTables * 16 - maxPow2 * 16)
        for (e in entries) {
            sfnt.write(e.tag.toByteArray(Charsets.US_ASCII))
            writeU32(sfnt, 0) // checksum 不重建：解析器不校验
            writeU32(sfnt, e.offset); writeU32(sfnt, e.length)
        }
        var dataOffset = headerSize
        for (dir in dirs) {
            val data = tableData.getValue(dir.tag)
            dataOffset = (dataOffset + 3) and 3.inv()
            while (sfnt.size() < dataOffset) sfnt.write(0)
            // 变换表数据按 transformLength 读出但目录按 origLength 布局：
            // 截断/补零到 orig（cmap 强制无变换不受影响；glyf/loca 等占位即可，不参与解析）
            val out = if (data.size >= dir.orig) data.copyOf(dir.orig)
                else data + ByteArray(dir.orig - data.size)
            sfnt.write(out)
            dataOffset += dir.orig
        }
        return sfnt.toByteArray()
    }

    /**
     * woff2 目录的 UIntBase128 变长整数（表目录 origLength/transformLength 字段的规范编码，
     * 与 glyf 变换内部使用的 255UShort 是两种不同编码）：
     * 大端 7-bit 分组，每字节最高位为延续位，必须是最短编码且至多 5 字节，值不得超 u32。
     * 拒绝：首字节 0x80（等价于前导零，非最短编码）、5 字节读满延续位仍置位、解码值溢出 u32。
     */
    private fun readUIntBase128(r: Reader): Int {
        var result = 0L
        for (i in 0 until 5) {
            val b = r.u8()
            check(i != 0 || b != 0x80) { "UIntBase128 首字节为 0x80（非最短编码）" }
            result = (result shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) {
                // 5×7=35 位，可能超出 u32/int；规范限定 UIntBase128 值域为 u32，这里按 int 收紧
                check(result <= Int.MAX_VALUE) { "UIntBase128 超出 int 范围：$result" }
                return result.toInt()
            }
        }
        throw IllegalArgumentException("UIntBase128 超过 5 字节")
    }

    // ---------- SFNT / cmap ----------

    /** SFNT 目录 → tag → (offset,length)。严格边界：所有表必须落在文件内。 */
    private fun sfntTables(sfnt: ByteArray): List<TableEntry> {
        require(sfnt.size >= 12) { "SFNT 头太短" }
        val magic = be32(sfnt, 0)
        require(SFNT_MAGICS.any { it.toLong() == magic }) { "SFNT 魔数不支持：0x${magic.toString(16)}" }
        val numTables = be16(sfnt, 4)
        require(numTables in 1..MAX_NUM_TABLES) { "numTables 越界：$numTables" }
        require(12 + numTables * 16 <= sfnt.size) { "表目录越界" }
        val out = mutableListOf<TableEntry>()
        var pos = 12
        repeat(numTables) {
            val tag = sfnt.copyOfRange(pos, pos + 4).decodeToString()
            val offset = be32s(sfnt, pos + 8)
            val length = be32s(sfnt, pos + 12)
            require(offset >= 0 && length >= 0 && offset.toLong() + length <= sfnt.size) {
                "表 $tag 区间越界（offset=$offset length=$length size=${sfnt.size}）"
            }
            out += TableEntry(tag, offset, length)
            pos += 16
        }
        return out
    }

    private data class Subtable(val format: Int, val data: ByteArray)

    /** cmap 表 → 选最优子表（format12 优先，其次 format4），返回 (格式, 映射)。 */
    private fun parseCmap(cmap: ByteArray): Pair<Int, Map<Int, Int>> {
        require(cmap.size >= 4) { "cmap 表太短" }
        require(be16(cmap, 0) == 0) { "cmap 版本非法" }
        val num = be16(cmap, 2)
        require(num in 1..64) { "cmap 子表数越界：$num" }
        require(4 + num * 8 <= cmap.size) { "cmap 子表目录越界" }
        val subs = mutableListOf<Subtable>()
        for (i in 0 until num) {
            val offset = be32s(cmap, 4 + i * 8 + 4)
            require(offset + 2 <= cmap.size) { "cmap 子表 $i 偏移越界" }
            val format = be16(cmap, offset)
            subs += Subtable(format, cmap.copyOfRange(offset, cmap.size))
        }
        // format12 覆盖全 Unicode 优先；否则 format4
        subs.firstOrNull { it.format == 12 }?.let { return 12 to parseFormat12(it.data) }
        subs.firstOrNull { it.format == 4 }?.let { return 4 to parseFormat4(it.data) }
        throw IllegalArgumentException("不支持的 cmap 格式：${subs.map { it.format }}（仅支持 4/12）")
    }

    /**
     * cmap format 4：分段映射（endCodes/startCodes/idDelta/idRangeOffset）。
     * 每个码点单独求值，受 MAX_MAPPINGS 上限约束。
     */
    private fun parseFormat4(d: ByteArray): Map<Int, Int> {
        require(d.size >= 16) { "format4 太短" }
        val length = be16(d, 2)
        require(length >= 16 && length <= d.size) { "format4 length 越界：$length/${d.size}" }
        val sub = d.copyOfRange(0, length)
        val segCountX2 = be16(sub, 6)
        require(segCountX2 % 2 == 0 && segCountX2 in 2..8192) { "format4 segCount 越界：$segCountX2" }
        val segCount = segCountX2 / 2
        val endBase = 14
        val startBase = endBase + segCountX2 + 2
        val deltaBase = startBase + segCountX2
        val roBase = deltaBase + segCountX2
        require(roBase + segCountX2 <= sub.size) { "format4 数组区越界" }
        val endCodes = IntArray(segCount) { be16(sub, endBase + it * 2) }
        val startCodes = IntArray(segCount) { be16(sub, startBase + it * 2) }
        val idDeltas = IntArray(segCount) { bes16(sub, deltaBase + it * 2) }
        val idRangeOffsets = IntArray(segCount) { be16(sub, roBase + it * 2) }
        val map = HashMap<Int, Int>()
        for (s in 0 until segCount) {
            val start = startCodes[s]; val end = endCodes[s]
            require(end >= start) { "format4 段 $s 起止倒置：$start>$end" }
            if (end - start > 0x10000) throw IllegalArgumentException("format4 段 $s 跨度过大")
            val roPos = roBase + s * 2
            for (cp in start..end) {
                if (cp == 0xFFFF) continue
                val gid = if (idRangeOffsets[s] == 0) {
                    (cp + idDeltas[s]) and 0xFFFF
                } else {
                    val glyphIndexAddr = roPos + idRangeOffsets[s] + 2 * (cp - start)
                    require(glyphIndexAddr + 2 <= sub.size) { "format4 glyphIndexArray 越界" }
                    val raw = be16(sub, glyphIndexAddr)
                    if (raw == 0) 0 else (raw + idDeltas[s]) and 0xFFFF
                }
                if (gid != 0) {
                    map[cp] = gid
                    if (map.size > MAX_MAPPINGS) throw IllegalArgumentException("cmap 映射数超过上限")
                }
            }
        }
        return map
    }

    /** cmap format 12：groups(startChar,endChar,startGlyph) 顺序连续映射。 */
    private fun parseFormat12(d: ByteArray): Map<Int, Int> {
        require(d.size >= 16) { "format12 太短" }
        val length = be32s(d, 4)
        require(length >= 16 && length <= d.size) { "format12 length 越界：$length/${d.size}" }
        val nGroups = be32s(d, 12)
        require(nGroups in 0..65536) { "format12 组数越界：$nGroups" }
        require(16 + nGroups * 12 <= length) { "format12 组表越界" }
        val map = HashMap<Int, Int>()
        for (g in 0 until nGroups) {
            val base = 16 + g * 12
            val start = be32s(d, base); val end = be32s(d, base + 4); val startGid = be32s(d, base + 8)
            require(end >= start) { "format12 组 $g 起止倒置" }
            require(end - start <= 0x10FFFF) { "format12 组 $g 跨度过大" }
            var gid = startGid
            for (cp in start..end) {
                map[cp] = gid++
                if (map.size > MAX_MAPPINGS) throw IllegalArgumentException("cmap 映射数超过上限")
            }
        }
        return map
    }

    // ---------- 工具 ----------

    private fun be16(d: ByteArray, o: Int): Int = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
    private fun bes16(d: ByteArray, o: Int): Int { val v = be16(d, o); return if (v >= 0x8000) v - 0x10000 else v }
    private fun be32(d: ByteArray, o: Int): Long =
        (d[o].toLong() and 0xFF shl 24) or (d[o + 1].toLong() and 0xFF shl 16) or
            (d[o + 2].toLong() and 0xFF shl 8) or (d[o + 3].toLong() and 0xFF)
    private fun be32s(d: ByteArray, o: Int): Int { val v = be32(d, o); require(v <= Int.MAX_VALUE) { "u32 溢出 @$o" }; return v.toInt() }
    private fun writeU16(out: ByteArrayOutputStream, v: Int) { out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFF).toInt()); out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt()); out.write((v and 0xFF).toInt())
    }
    private fun writeU32(out: ByteArrayOutputStream, v: Int) = writeU32(out, v.toLong() and 0xFFFFFFFFL)

    /** woff2 规范固定表 tag（flags&0x3F 的索引值）；0x3F 表示自定义 4 字节 tag。 */
    private val WOFF2_KNOWN_TAGS = arrayOf(
        "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post",
        "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ", "VORG", "EBDT",
        "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea",
        "vmtx", "BASE", "GDEF", "GPOS", "GSUB", "EBSC", "JSTF", "MATH",
        "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt", "avar",
        "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar",
        "gvar", "hsty", "just", "lcar", "mort", "morx", "opbd", "prop",
        "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill",
    )
}
