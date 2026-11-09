package com.mina.legadostudio.runtime

import com.mina.legadostudio.network.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求模板 / URL option / header 语义回归。
 *
 * 上游依据（LegadoTeam/legado @330e606）：
 * - `AnalyzeUrl.initUrl` 175-183：analyzeJs → replaceKeyPageJs → analyzeUrl；
 * - `analyzeJs` 188-211：`@js:` / `<js></js>` 逐段，字面量段里的 `@result` 用上一段结果替换；
 * - `replaceKeyPageJs` 216-243：先 `{{}}`（平衡扫描，Double 整型按 %.0f），再 `<a,b,c>` 分页段；
 * - `analyzeUrl` 248-317：`\s*,\s*(?=\{` 切 URL 与选项，`option.js` 在 URL 绝对化后执行且
 *   `baseUrl` 已归一为站点根（NetworkUtils.getBaseUrl），`result` 才是完整 url；
 * - 编码 329-389：charset 为空时 UTF-8 且已编码片段原样保留（不双编码），
 *   `charset=escape` 走 EncoderUtils.escape（%uXXXX）；
 * - `BaseSource.getHeaderMap` 197-222：header 是纯 JS（JS_PATTERN 全匹配）时求值后按 JSON 解析。
 */
class ReaderRequestTemplateTest {

    private fun eval(script: String, result: Any? = null, bindings: Map<String, Any?> = emptyMap()): Any? =
        RhinoEvaluator(HttpFetcher(), com.google.gson.Gson()).evaluateRaw(
            script,
            baseUrl = "https://site.test",
            previous = result,
            bindings = bindings,
        ).value

    // ------------------------------------------------------------- @js / <js>

    @Test fun analyzeJsHandlesCaseInsensitivePrefixAndInlineTag() {
        val out = ReaderRequestTemplate.analyzeJs(
            "https://site.test/list/<js>1+1</js>/@JS:'x'.toUpperCase()",
            evalJs = { script, result -> eval(script, result) },
        )
        assertEquals("https://site.test/list/2/X", out)
    }

    @Test fun analyzeJsSplicesResultIntoFollowingLiteral() {
        // 上游：字面量段里的 @result 用上一段结果替换，而不是把整段丢掉
        val out = ReaderRequestTemplate.analyzeJs(
            "https://site.test/@js:'a.com'/p?u=@result",
            evalJs = { script, result -> eval(script, result) },
        )
        assertEquals("https://site.test/a.com/p?u=a.com", out)
    }

    @Test fun templateReplacesDoubleWithIntegerFormatting() {
        val out = ReaderRequestTemplate.replaceTemplates(
            "https://site.test/{{page*10}}/{{page}}",
            page = 3,
            evalJs = { script, result -> eval(script, result) },
        )
        assertEquals("https://site.test/30/3", out)
    }

    @Test fun pageSegmentPicksIndexAndFallsBackToLast() {
        assertEquals(
            "https://site.test/s/2.html",
            ReaderRequestTemplate.replaceTemplates("https://site.test/s/<1,2,3>.html", page = 2, evalJs = { _, _ -> null }),
        )
        assertEquals(
            "https://site.test/s/3.html",
            ReaderRequestTemplate.replaceTemplates("https://site.test/s/<1,2,3>.html", page = 9, evalJs = { _, _ -> null }),
        )
    }

    @Test fun templateRunsBeforePageSegmentSoComparisonsAreNotSplit() {
        // 先 {{}} 再 <...>：{{page < 3 ? 1 : 2}} 里的 `<` 不能被当成分页段
        val out = ReaderRequestTemplate.replaceTemplates(
            "https://site.test/{{page < 3 ? 1 : 2}}/p",
            page = 1,
            evalJs = { script, result -> eval(script, result) },
        )
        assertEquals("https://site.test/1/p", out)
    }

    // -------------------------------------------------------------- URL option

    @Test fun urlOptionAllowsWhitespaceBeforeBrace() {
        val parsed = ReaderRequestTemplate.splitUrlOption("https://site.test/api , {\"method\":\"POST\",\"body\":\"q=1\"}")
        assertEquals("https://site.test/api", parsed.urlPart)
        assertEquals("POST", parsed.option?.get("method")?.asString)
    }

    @Test fun urlOptionJsRunsAfterAbsoluteUrlWithSiteBaseUrl() {
        // 上游 option.js：baseUrl 已是站点根，result 是完整 url
        val resolved = ReaderRequestTemplate.resolveUrl(
            template = "https://site.test/book/1,{\"js\":\"result + '/extra'\"}",
            sourceUrl = "https://site.test/",
            page = null,
            evalJs = { script, result -> eval(script, result) },
        )
        assertEquals("https://site.test/book/1/extra", resolved)
    }

    @Test fun urlOptionHeadersAcceptJsonStringAndObject() {
        val fromString = ReaderRequestTemplate.resolveUrl(
            template = "https://site.test/a,{\"headers\":\"{\\\"X-A\\\":\\\"1\\\"}\"}",
            sourceUrl = "https://site.test/",
            page = null,
            evalJs = { _, _ -> null },
        )
        val parsedString = ReaderRequestTemplate.parseOption(fromString)
        assertEquals("1", parsedString.headers["X-A"])

        val fromObject = ReaderRequestTemplate.resolveUrl(
            template = "https://site.test/a,{\"header\":{\"X-B\":\"2\"}}",
            sourceUrl = "https://site.test/",
            page = null,
            evalJs = { _, _ -> null },
        )
        assertEquals("2", ReaderRequestTemplate.parseOption(fromObject).headers["X-B"])
    }

    // ----------------------------------------------------------------- charset

    @Test fun queryIsNotDoubleEncodedWhenAlreadyEncoded() {
        val out = ReaderRequestTemplate.resolveUrl(
            template = "https://site.test/s?q=%E5%89%91&p=1",
            sourceUrl = "https://site.test/",
            page = null,
            evalJs = { _, _ -> null },
        )
        assertEquals("https://site.test/s?q=%E5%89%91&p=1", out)
    }

    @Test fun rawQueryIsEncodedAsRfc3986WithoutPlus() {
        val out = ReaderRequestTemplate.resolveUrl(
            template = "https://site.test/s?q=剑 来&p=1",
            sourceUrl = "https://site.test/",
            page = null,
            evalJs = { _, _ -> null },
        )
        assertTrue(out, out.contains("q=%E5%89%91%20%E6%9D%A5"))
        assertFalse("空格不得编码成 +：$out", out.contains("+"))
    }

    @Test fun escapeCharsetUsesPercentU() {
        val out = ReaderRequestTemplate.resolveUrl(
            template = "https://site.test/s?q=中文,{\"charset\":\"escape\"}",
            sourceUrl = "https://site.test/",
            page = null,
            evalJs = { _, _ -> null },
        )
        assertTrue(out, out.contains("%u4e2d%u6587"))
    }

    // ----------------------------------------------------------------- headers

    @Test fun headerJsIsEvaluatedAndParsedAsJson() {
        val headers = ReaderRequestTemplate.resolveHeaders(
            rawHeader = "@js: JSON.stringify({Referer: baseUrl + '/list', 'X-T': String(1+1)})",
            sourceUrl = "https://site.test/",
            evalJs = { script, _ -> eval(script) },
        )
        assertEquals("https://site.test//list", headers["Referer"])
        assertEquals("2", headers["X-T"])
    }

    @Test fun plainJsonHeaderIsParsedWithoutJs() {
        val headers = ReaderRequestTemplate.resolveHeaders(
            rawHeader = "{\"X-Static\":\"1\"}",
            sourceUrl = "https://site.test/",
            evalJs = { _, _ -> error("静态 JSON header 不应执行 JS") },
        )
        assertEquals("1", headers["X-Static"])
    }

    @Test fun headerJsFailureIsReportedNotSilentlyDropped() {
        val note = mutableListOf<String>()
        val headers = ReaderRequestTemplate.resolveHeaders(
            rawHeader = "@js: notDefinedAtAll.x",
            sourceUrl = "https://site.test/",
            evalJs = { _, _ -> throw IllegalStateException("boom") },
            onError = { note += it },
        )
        assertTrue(headers.isEmpty())
        assertTrue("header JS 失败必须留下诊断：$note", note.isNotEmpty())
    }

    // ---------------------------------------------------------------- jsLib

    @Test fun jsLibJsonMapWithUrlsIsClassifiedAsRemote() {
        val spec = ReaderRequestTemplate.parseJsLibSpec(
            """{"a":"https://cdn.test/a.js","b":"function f(){return 1}"}""",
        )
        assertEquals(1, spec.remoteUrls.size)
        assertEquals("https://cdn.test/a.js", spec.remoteUrls.first())
        assertTrue(spec.inlineParts.any { it.contains("function f") })
    }

    @Test fun jsLibPlainInlineStaysInline() {
        val spec = ReaderRequestTemplate.parseJsLibSpec("function f(){return 1}")
        assertTrue(spec.remoteUrls.isEmpty())
        assertEquals(listOf("function f(){return 1}"), spec.inlineParts)
    }

    @Test fun remoteJsLibWithoutLoaderRaisesEnvironmentError() {
        val error = runCatching {
            ReaderRequestTemplate.loadJsLib(
                raw = """{"lib":"https://cdn.test/a.js"}""",
                sourceUrl = "https://site.test/",
                fetcher = null,
            )
        }.exceptionOrNull()
        assertTrue("远程 jsLib 不得静默跳过", error is ReaderRuntimeEnvironmentException)
        assertNull(error?.cause)
    }

    @Test fun remoteJsLibUsesFetcherAndCachesByContentHash() {
        var calls = 0
        val cache = ReaderRuntimeJsLibCache()
        val loader: (String) -> String = { calls++; "var LIB='loaded';" }
        val first = ReaderRequestTemplate.loadJsLib(
            raw = """{"lib":"https://cdn.test/a.js"}""",
            sourceUrl = "https://site.test/",
            fetcher = loader,
            cache = cache,
        )
        val second = ReaderRequestTemplate.loadJsLib(
            raw = """{"lib":"https://cdn.test/a.js"}""",
            sourceUrl = "https://site.test/",
            fetcher = loader,
            cache = cache,
        )
        assertEquals(1, calls)
        assertEquals(first.inlineSources, second.inlineSources)
        assertTrue(first.inlineSources.first().contains("LIB='loaded'"))
        assertTrue(first.diagnostics.any { it.contains("cdn.test") })
    }
}
