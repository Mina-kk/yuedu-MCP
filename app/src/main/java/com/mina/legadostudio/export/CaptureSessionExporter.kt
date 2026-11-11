package com.mina.legadostudio.export

import com.mina.legadostudio.data.db.HttpLogEntity
import com.google.gson.JsonParser
import java.io.Writer
import java.util.Base64
import java.util.regex.Pattern

/**
 * cap: 抓包会话 JSON 导出的**流式**写盘器：逐页取行、逐行写、不落全量 List、
 * 不拼整串 JSON——大会话（上千行、单行正文几十/几百 K）也不会在内存里堆出双倍数据。
 *
 * 纯 JVM 可测（Android 侧注入 fileReader 读 cacheDir 下的 bodyFile 字节、page 回调分页取行）。
 *
 * 导出形态：
 * ```
 * { "contextId":"cap:…", "exportedAt":…, "total":…,
 *   "logs":[ {实体字段…, "requestHeadersJson":{…}, "responseHeadersJson":{…},
 *             "redirectChainJson":[…], "responseBodyFile":"captures/<ctx>/<seq>.bin",
 *             "responseBodyFileBytes":N, "responseBodyBase64":"<base64>"}, … ],
 *   "count":N, "anchors":["example.com",…] }
 * ```
 * - 实体里本身是 JSON 文本的字段（requestHeaders/responseHeaders/redirectChain）额外导出
 *   展开版 `*Json` 嵌套值，原字符串字段保留（不破坏旧消费方）；
 * - WebView 抓包二进制行（正文里含 `[bodyFile=captures/...]` 标记）额外导出
 *   `responseBodyFile`（相对路径）/`responseBodyFileBytes`/`responseBodyBase64`，
 *   字体/图片/媒体字节原文齐全；
 * - `anchors` 汇总全会话出现的非空 sourceAnchor（书源归属锚点）。
 */
object CaptureSessionExporter {

    data class ResourceReport(var missingResources: Int = 0, var omittedResources: Int = 0)

    /** `[bodyFile=captures/…]` 标记（与 WebViewCapture.bodyFileMark 同约定）。 */
    private val BODY_FILE_PATTERN: Pattern = Pattern.compile("\\[bodyFile=([^\\]]+)\\]")

    /** 一页读一页写的页大小：兼顾 DAO IO 次数与单行内存上限。 */
    const val EXPORT_PAGE_SIZE = 50

    /**
     * 把 contextId 对应的全部日志行流式写入 [out] 为单个 JSON 文档；返回实际写出行数。
     *
     * @param total 预期总行数（写入文档声明字段，供消费方校验完整度）。
     * @param page 翻页回调：参数为 offset，返回该页实体（不足页大小即最后一页）。
     * @param fileReader 按相对路径读字节（实现上读 cacheDir/<path>）；返回 null 时该行只记路径。
     */
    suspend fun writeSessionJson(
        out: Writer,
        contextId: String,
        exportedAt: Long,
        total: Int,
        page: suspend (offset: Int) -> List<HttpLogEntity>,
        fileReader: (String) -> ByteArray?,
        omitResources: Boolean = false,
        report: ResourceReport = ResourceReport(),
    ): Int {
        val initialMissing = report.missingResources
        val initialOmitted = report.omittedResources
        val anchors = linkedSetOf<String>()
        var written = 0
        val w = JsonWriterLite(out)
        w.raw('{')
        w.field("contextId", contextId); w.raw(',')
        w.field("exportedAt", exportedAt); w.raw(',')
        w.field("total", total); w.raw(',')
        w.key("logs"); w.raw('[')
        var first = true
        var offset = 0
        while (true) {
            val rows = page(offset)
            if (rows.isEmpty()) break
            for (e in rows) {
                if (!e.sourceAnchor.isNullOrBlank()) anchors += e.sourceAnchor
                if (!first) w.raw(',')
                first = false
                writeLog(w, e, fileReader, omitResources, report)
                written++
            }
            if (rows.size < EXPORT_PAGE_SIZE) break
            offset += rows.size
        }
        w.raw(']'); w.raw(',')
        w.field("partial", written != total || report.missingResources > initialMissing); w.raw(',')
        w.field("missingResources", report.missingResources - initialMissing); w.raw(',')
        w.field("omittedResources", report.omittedResources - initialOmitted); w.raw(',')
        w.field("count", written); w.raw(',')
        w.key("anchors"); w.raw('[')
        anchors.forEachIndexed { i, a -> if (i > 0) w.raw(','); w.string(a) }
        w.raw(']')
        w.raw('}')
        out.flush()
        return written
    }

    /** 单个实体 → 一个 JSON 对象（含嵌套 JSON 展开与 bodyFile 字节内嵌）。 */
    private fun writeLog(w: JsonWriterLite, e: HttpLogEntity, fileReader: (String) -> ByteArray?, omitResources: Boolean, report: ResourceReport) {
        w.raw('{')
        w.field("id", e.id); w.raw(',')
        w.field("method", e.method); w.raw(',')
        w.field("url", e.url); w.raw(',')
        w.field("finalUrl", e.finalUrl); w.raw(',')
        w.field("statusCode", e.statusCode); w.raw(',')
        w.field("durationMs", e.durationMs); w.raw(',')
        w.field("error", e.error); w.raw(',')
        w.field("createdAt", e.createdAt); w.raw(',')
        w.fieldNullable("sourceAnchor", e.sourceAnchor); w.raw(',')
        w.fieldNullable("contextId", e.contextId); w.raw(',')
        w.fieldNullable("originKind", e.originKind); w.raw(',')
        w.field("requestBody", e.requestBody); w.raw(',')
        w.field("responseBody", e.responseBody); w.raw(',')
        w.field("requestHeaders", e.requestHeaders); w.raw(',')
        w.field("responseHeaders", e.responseHeaders); w.raw(',')
        w.field("redirectChain", e.redirectChain); w.raw(',')
        w.jsonTextField("requestHeadersJson", e.requestHeaders); w.raw(',')
        w.jsonTextField("responseHeadersJson", e.responseHeaders); w.raw(',')
        w.jsonTextField("redirectChainJson", e.redirectChain)
        // 如实标不完整：日志正文被 HttpLogCaps 截断（行尾带截断标记）时导出
        // responseBodyTruncated=true——消费方据此知道这行正文不是完整响应。
        // isTruncatedMarked 同时命中旧「…[正文已截断]」与新「…[正文已截断，原长 N 字符]」。
        if (com.mina.legadostudio.network.HttpLogCaps.isTruncatedMarked(e.responseBody)) {
            w.raw(','); w.field("responseBodyTruncated", true)
        }
        // bodyFile 展开：正文里的 [bodyFile=…] 标记 → 读文件内嵌 base64 字节
        val ref = bodyFileRef(e.responseBody)
        if (ref != null) {
            w.raw(',')
            w.field("responseBodyFile", ref)
            val bytes = if (omitResources) null else try { fileReader(ref) } catch (_: java.io.IOException) { null }
            val status = if (omitResources) { report.omittedResources++; "omitted" }
                else if (bytes == null) { report.missingResources++; "missing" } else "included"
            w.raw(','); w.field("responseBodyFileStatus", status)
            if (bytes != null) {
                w.raw(',')
                w.field("responseBodyFileBytes", bytes.size); w.raw(',')
                w.field("responseBodyBase64", Base64.getEncoder().encodeToString(bytes))
            }
        }
        w.raw('}')
    }

    /** 从正文文本提取 `[bodyFile=captures/…]` 相对路径；仅允许 captures/ 前缀且不含 `..`。 */
    fun bodyFileRef(responseBody: String): String? {
        val m = BODY_FILE_PATTERN.matcher(responseBody)
        if (!m.find()) return null
        val p = m.group(1)?.trim().orEmpty()
        return if (p.startsWith("captures/") && !p.contains("..")) p else null
    }

    /** 极简 JSON 输出器：只写本导出需要的类型，不引第三方（性能按单遍 Writer 设计）。 */
    private class JsonWriterLite(private val out: Writer) {
        fun raw(c: Char) { out.write(c.code) }
        fun key(k: String) { string(k); out.write(':'.code) }
        fun field(k: String, v: String) { key(k); string(v) }
        fun fieldNullable(k: String, v: String?) { key(k); if (v == null) out.write("null") else string(v) }
        fun field(k: String, v: Long) { key(k); out.write(v.toString()) }
        fun field(k: String, v: Int) { key(k); out.write(v.toString()) }
        fun field(k: String, v: Boolean) { key(k); out.write(v.toString()) }
        /**
         * v 本身是 JSON 文本（"{…}"/"[…]"）：先脱敏再校验合法性后原样内嵌；否则当普通字符串写出。
         * 首尾字符配对只是粗筛，真正用 JsonParser 校验——脏数据（中间未转义控制符/截断串）
         * 内嵌会产出非法 JSON；校验不过一律转义成字符串，保证导出文档整体合法。
         * 展开前对敏感头值脱敏：requestHeaders/responseHeaders/redirectChain 里的
         * token/Cookie 即使落库时漏脱敏，导出也不能原样带出去。
         */
        fun jsonTextField(k: String, v: String) {
            key(k)
            val t = redactSensitive(v.trim())
            val looksJson = (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
            // 校验过则内嵌已脱敏的 t；校验不过也把脱敏后的 t 转义写出（不写未脱敏原始 v）。
            if (looksJson && isValidJson(t)) out.write(t) else string(t)
        }

        private fun isValidJson(s: String): Boolean =
            runCatching { JsonParser.parseString(s); true }.getOrDefault(false)

        /**
         * JSON 文本里的敏感头值兜底脱敏：对 "Authorization"/"Cookie"/"Set-Cookie"/含 "api-key"/"token"
         * 的 key，把其字符串值替换成 "***"。只在能解析成 JSON 对象/数组时逐字段脱敏；
         * 解析失败原样返回（由 isValidJson 决定内嵌或转义，不会以未脱敏形态内嵌）。
         */
        private fun redactSensitive(json: String): String {
            val t = json.trim()
            if (!((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]")))) return json
            return runCatching {
                redactElement(JsonParser.parseString(t)).toString()
            }.getOrDefault(json)
        }

        private fun redactElement(el: com.google.gson.JsonElement): com.google.gson.JsonElement {
            if (el.isJsonObject) {
                val obj = el.asJsonObject
                val out = com.google.gson.JsonObject()
                for ((key, value) in obj.entrySet()) {
                    out.add(key, if (isSensitiveKey(key)) com.google.gson.JsonPrimitive("***") else redactElement(value))
                }
                return out
            }
            if (el.isJsonArray) {
                val out = com.google.gson.JsonArray()
                for (item in el.asJsonArray) out.add(redactElement(item))
                return out
            }
            return el
        }

        private fun isSensitiveKey(key: String): Boolean =
            key.equals("Authorization", true) || key.equals("Cookie", true) ||
                key.equals("Set-Cookie", true) || key.contains("api-key", true) ||
                key.contains("api_key", true) || key.equals("token", true) ||
                key.contains("access_token", true) || key.contains("secret", true)
        fun string(s: String) {
            out.write('"'.code)
            for (c in s) when (c) {
                '"' -> out.write("\\\"")
                '\\' -> out.write("\\\\")
                '\n' -> out.write("\\n")
                '\r' -> out.write("\\r")
                '\t' -> out.write("\\t")
                '\b' -> out.write("\\b")
                else -> if (c < ' ') out.write(String.format("\\u%04x", c.code)) else out.write(c.code)
            }
            out.write('"'.code)
        }
    }
}
