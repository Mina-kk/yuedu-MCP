package com.mina.legadostudio.web

import com.mina.legadostudio.data.db.OperationLogEntity
import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.domain.LogMultiFormatExporter
import com.mina.legadostudio.domain.LogExportFormat
import com.mina.legadostudio.export.CaptureSessionExporter
import com.mina.legadostudio.network.CaptureActivity
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import java.io.StringWriter
import java.time.ZoneId

fun main() = runBlocking {
    val (start, end) = LogManagementRules.dayWindow("2026-03-08", ZoneId.of("America/New_York"))
    check(end - start + 1 == 23L * 60 * 60 * 1000)
    check(runCatching { LogManagementRules.exactId("01") }.isFailure)
    check(runCatching { LogManagementRules.requireComplete(51, 50) }.isFailure)
    val operations = (1..501).map { OperationLogEntity(id=it.toLong(), level="I", category="probe", message="authorization=secret-value", detail="https://example.com?token=secret-value") }
    for (format in LogExportFormat.entries) {
        val text = LogMultiFormatExporter.operation(operations, "all", format, true)
        check(!text.contains("secret-value"))
        if (format == LogExportFormat.JSON) check(JsonParser.parseString(text).asJsonArray.size() == 501)
    }
    val sensitive = """{"X-Access-Token":["LEAK_ONE","LEAK_TWO"],"nested":{"CoOkIe":"LEAK_THREE","password":{"value":"LEAK_FOUR"}},"TOKEN":"LEAK_FIVE","TOKEN":"LEAK_SIX"}"""
    val sanitized = com.mina.legadostudio.diagnostic.LogRedactor.redact(sensitive)
    check(!sanitized.contains("LEAK_")) { "JSON sensitive values leaked" }
    check(JsonParser.parseString(sanitized).isJsonObject)
    val ctx = "cap:12345678-1234-1234-1234-123456789abc"
    val rows = (1..151).map { HttpLogEntity(id=it.toLong(),method="GET",url="https://example.com/$it",finalUrl="https://example.com/$it",statusCode=200,durationMs=1,requestHeaders="{}",responseHeaders="{}",requestBody="",responseBody="body-$it",error="",redirectChain="[]",contextId=ctx) }
    for (format in LogExportFormat.entries) {
        val exported = LogMultiFormatExporter.http(listOf(rows.first().copy(requestHeaders=sensitive,responseHeaders=sensitive,requestBody=sensitive,responseBody=sensitive)), "all", format, true)
        check(!exported.contains("LEAK_")) { "HTTP $format leaked" }
    }
    val offsets = mutableListOf<Int>()
    val out = StringWriter()
    CaptureActivity.start(ctx)
    check(!CaptureActivity.deleteIfInactive(ctx) { error("active session must not export/delete") })
    CaptureActivity.finish(ctx)
    check(CaptureActivity.deleteIfInactive(ctx) {
        val n=CaptureSessionExporter.writeSessionJson(out,ctx,0L,151,{offset->offsets+=offset;rows.drop(offset).take(50)},{null})
        LogManagementRules.requireComplete(151,n)
    })
    check(offsets == listOf(0,50,100,150))
    val parsed=JsonParser.parseString(out.toString()).asJsonObject
    check(parsed.getAsJsonArray("logs").size()==151)
    check(parsed.getAsJsonArray("logs").last().asJsonObject.get("responseBody").asString=="body-151")
    val missing = StringWriter()
    CaptureSessionExporter.writeSessionJson(missing,ctx,0,1,{o -> if(o==0) listOf(rows.first().copy(responseBody="[binary 12B image/png] [bodyFile=captures/test/1.bin]")) else emptyList()},{null})
    check(JsonParser.parseString(missing.toString()).asJsonObject.get("partial")?.asBoolean == true) { "Missing resource silently reported complete" }
    for (omit in listOf(false,true)) {
        val report = CaptureSessionExporter.ResourceReport()
        val output = StringWriter()
        val row = LogMultiFormatExporter.redactHttp(rows.first().copy(requestHeaders=sensitive,responseHeaders=sensitive,responseBody="[binary 12B image/png] [bodyFile=captures/test/1.bin]"))
        CaptureSessionExporter.writeSessionJson(output,ctx,0,1,{o -> if(o==0) listOf(row) else emptyList()},{ if(omit) error("Omitted resource must never be read") else throw java.io.IOException("unreadable") },omit,report)
        val json=JsonParser.parseString(output.toString()).asJsonObject
        check(!output.toString().contains("LEAK_")) { "Capture raw or expanded headers leaked" }
        check(json.get("partial").asBoolean == !omit)
        check(report.omittedResources == if(omit) 1 else 0)
        check(report.missingResources == if(omit) 0 else 1)
        check(json.getAsJsonArray("logs")[0].asJsonObject.get("responseBodyFileStatus").asString == if(omit) "omitted" else "missing")
    }
    val large = java.io.File.createTempFile("logs-large-", ".json")
    try {
        large.bufferedWriter().use { writer ->
            CaptureSessionExporter.writeSessionJson(writer,ctx,0,151,{o -> rows.drop(o).take(50).map { it.copy(responseBody="x".repeat(20000)) }},{null})
        }
        check(large.length() > 2 * 1024 * 1024)
        large.reader().use { check(JsonParser.parseReader(it).asJsonObject.get("count").asInt == 151) }
        val included = StringWriter()
        CaptureSessionExporter.writeSessionJson(included,ctx,0,1,{o -> if(o==0) listOf(rows.first().copy(responseBody="[bodyFile=captures/test/1.bin]")) else emptyList()},{byteArrayOf(0,1,-1)})
        val includedJson=JsonParser.parseString(included.toString()).asJsonObject
        check(!includedJson.get("partial").asBoolean)
        check(includedJson.getAsJsonArray("logs")[0].asJsonObject.get("responseBodyBase64").asString == "AAH/")
        check(!com.mina.legadostudio.diagnostic.LogRedactor.redact("""{"Cookie":"LEAK_TRUNCATED"""").contains("LEAK_"))
        val prepared=LogManagementBridge.PreparedLogExport(large,"large.json","application/json",151,CaptureSessionExporter.ResourceReport())
        check(!prepared.receipt().has("text")); check(prepared.receipt().toString().length < 256)
    } finally { large.delete() }
    println("PASS: DST, exact IDs, completeness, four-format JSON redaction, capture raw/expanded headers, missing/unreadable/omitted resources, >2MiB streamed file, metadata-only receipt, active reservation")
}
