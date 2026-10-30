package com.mina.legadostudio.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceValidatorTest {
    private val validator = BookSourceValidator()

    @Test fun rejectsInvalidJson() {
        assertFalse(validator.validate("{").isValid)
    }

    @Test fun malformedJsonIssueCarriesNearbySourceSnippet() {
        // 模拟漏收尾引号的巨型 @js 规则：gson 报 column N，issue 必须带回错误列附近源码，便于直接定位漏字符处
        val filler = "x".repeat(400)
        val broken = """{"bookSourceName":"n","bookSourceUrl":"https://a.com","ruleContent":{"content":"<js>$filler"""
        val issue = validator.validate(broken).issues.first { it.path == "$" }
        assertTrue("expected position marker in: ${issue.message}",
            issue.message.contains("错误位置附近源码") || issue.message.contains("错误位置（"))
        assertTrue(issue.message.contains(filler.takeLast(80)))
    }

    @Test fun malformedMultiLineJsonReportsLineAndColumnWithSnippet() {
        // 阶段二回归：多行 JSON 的语法错必须按 line+column 定位，不能把列号当全串偏移指到第一行
        val json = """
            {
              "bookSourceName": "示例",
              "bookSourceUrl": "https://example.com",
              "ruleToc": {"chapterList": "a"},
              "ruleContent": {"content": "#content"}
              BROKEN_LINE_HERE
            }
        """.trimIndent()
        val issue = validator.validate(json).issues.first { it.path == "$" }
        // Gson 报出 line 6 附近：提示里必须带行号，且截取的片段落在 BROKEN_LINE_HERE 附近而不是第一行
        assertTrue("expected line number in: ${issue.message}", Regex("第 \\d+ 行").containsMatchIn(issue.message))
        assertTrue("snippet should contain the broken token: ${issue.message}", issue.message.contains("BROKEN_LINE_HERE"))
        assertFalse(issue.message.contains("示例"))
    }

    @Test fun rejectsMissingRequiredFields() {
        val report = validator.validate("""{"bookSourceName":"","bookSourceUrl":""}""")
        assertTrue(report.issues.any { it.path == "bookSourceName" })
        assertTrue(report.issues.any { it.path == "ruleContent.content" })
        assertTrue(report.issues.any { it.path == "ruleToc.chapterList" })
    }

    @Test fun acceptsMinimalSource() {
        val json = """{
          "bookSourceName":"示例",
          "bookSourceUrl":"https://example.com",
          "bookSourceType":0,
          "ruleToc":{"chapterList":".list a"},
          "ruleContent":{"content":"#content@html"}
        }"""
        assertTrue(validator.validate(json).issues.toString(), validator.validate(json).isValid)
    }

    @Test fun acceptsVideoTypeSource() {
        // 回归：RuntimeConfigStore 支持 -1..4，用户在 MCP 页选「视频」时 save_source 写入 4，Validator 不能再拒
        val json = """{
          "bookSourceName":"示例视频",
          "bookSourceUrl":"https://example.com",
          "bookSourceType":4,
          "ruleToc":{"chapterList":"a"},
          "ruleContent":{"content":"#content"}
        }"""
        assertTrue(validator.validate(json).issues.toString(), validator.validate(json).isValid)
    }

    @Test fun rejectsOutOfRangeType() {
        val json = """{
          "bookSourceName":"示例",
          "bookSourceUrl":"https://example.com",
          "bookSourceType":5,
          "ruleToc":{"chapterList":"a"},
          "ruleContent":{"content":"#content"}
        }"""
        assertTrue(validator.validate(json).issues.any { it.path == "bookSourceType" })
    }

    @Test fun stringBookSourceTypeDoesNotCrash() {
        // 回归：bookSourceType 给字符串（真机会 ClassCastException）时 validator 不能抛异常，只能记 issue
        val json = """{
          "bookSourceName":"示例","bookSourceUrl":"https://example.com",
          "bookSourceType":"abc",
          "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#content"}
        }"""
        val report = validator.validate(json)
        assertTrue(report.issues.any { it.path == "bookSourceType" })
        assertFalse(report.isValid)
    }

    @Test fun mediaSourceMissingContentRuleIsWarningNotIssue() {
        // 音频源缺 ruleContent.content：是非阻断 warning（isValid 不受影响），不是硬 issue
        val json = """{
          "bookSourceName":"音频","bookSourceUrl":"https://example.com",
          "bookSourceType":1,
          "ruleToc":{"chapterList":"a"}
        }"""
        val report = validator.validate(json)
        assertTrue("媒体源缺正文规则不应影响 isValid：${report.issues}", report.isValid)
        val warning = report.warnings.firstOrNull { it.path == "ruleContent.content" }
        assertTrue("预期 warning 提示资源取证：${report.warnings}", warning != null)
        assertTrue(warning!!.message.contains("资源"))
    }

    @Test fun requiresSearchListWhenSearchEnabled() {
        val json = """{
          "bookSourceName":"示例",
          "bookSourceUrl":"https://example.com",
          "searchUrl":"/search?q={{key}}",
          "ruleToc":{"chapterList":"a"},
          "ruleContent":{"content":"#content"}
        }"""
        assertFalse(validator.validate(json).isValid)
    }

    @Test fun requiresExploreListWhenExploreEnabled() {
        // 回归：exploreUrl 与 ruleExplore.bookList 必须成对出现，否则真机发现页空跑
        val base = """"bookSourceName":"示例","bookSourceUrl":"https://example.com",
          "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#content"}"""
        val missing = validator.validate("""{$base,"exploreUrl":"https://example.com/list"}""")
        assertTrue(missing.issues.any { it.path == "ruleExplore.bookList" })
        val ok = validator.validate("""{$base,"exploreUrl":"https://example.com/list","ruleExplore":{"bookList":".item"}}""")
        assertTrue(ok.issues.toString(), ok.isValid)
    }

    private fun withTocUrl(tocUrl: String) = com.google.gson.Gson().toJson(mapOf(
        "bookSourceName" to "示例", "bookSourceUrl" to "https://example.com",
        "ruleBookInfo" to mapOf("tocUrl" to tocUrl),
        "ruleToc" to mapOf("chapterList" to ".list a"), "ruleContent" to mapOf("content" to "#content"),
    ))

    private fun withJsLib(jsLib: String) = com.google.gson.Gson().toJson(mapOf(
        "bookSourceName" to "示例", "bookSourceUrl" to "https://example.com",
        "jsLib" to jsLib,
        "ruleToc" to mapOf("chapterList" to ".list a"), "ruleContent" to mapOf("content" to "#content"),
    ))

    @Test fun warnsOnBareUrlInRuleJs() {
        val report = validator.validate(withTocUrl("@js:url"))
        assertTrue(report.issues.toString(), report.isValid)
        assertTrue(report.warnings.toString(), report.warnings.any { it.path == "ruleBookInfo.tocUrl" && it.message.contains("baseUrl") })
        assertTrue(validator.validate(withTocUrl("<js>result = url + '/toc'</js>")).warnings.any { it.path == "ruleBookInfo.tocUrl" })
    }

    @Test fun doesNotWarnWhenUrlIsDeclaredOrNotAVariable() {
        listOf(
            "@js:baseUrl", "<js>var url = baseUrl; url</js>", "@js:java.get(\"url\")",
            "@js:({url: 1}).url", "@js:book.url", "@js:// url\nbaseUrl", ".toc a@href",
            "<js>[1].map(url => url + 1)</js>", "<js>function f(a, url) { return url } f(1, baseUrl)</js>",
        ).forEach { rule ->
            val warnings = validator.validate(withTocUrl(rule)).warnings
            assertFalse("rule=$rule warnings=$warnings", warnings.any { it.path == "ruleBookInfo.tocUrl" })
        }
    }

    @Test fun inspectsTemplatesWithoutExecutingThem() {
        assertTrue(validator.validate(withTocUrl("{{url}}/toc")).warnings.any { it.path == "ruleBookInfo.tocUrl" })
        assertTrue(validator.validate(withTocUrl("@JS:isFromBookInfo ? baseUrl : result")).warnings.any { it.message.contains("isFromBookInfo") })
        assertTrue(validator.validate(withTocUrl("<JS>url</JS>")).warnings.any { it.path == "ruleBookInfo.tocUrl" })
        assertFalse(validator.validate(withTocUrl("@js:throw new Error('must not run')")).warnings.any { it.path == "ruleBookInfo.tocUrl" })
    }

    @Test fun respectsDeclarationsAndFunctionScope() {
        listOf(
            "@js:function url() { return baseUrl; } url()",
            "@js:var {url} = book; url",
            "@js:var {url: link} = book; link",
            "@js:try { throw 1; } catch (url) { String(url); }",
            "@js:url = baseUrl; url",
            "@js:/url/.test(result)",
        ).forEach { rule ->
            assertFalse(rule, validator.validate(withTocUrl(rule)).warnings.any { it.path == "ruleBookInfo.tocUrl" })
        }
        assertTrue(validator.validate(withTocUrl("@js:function f(url) { return url; } url")).warnings.any { it.path == "ruleBookInfo.tocUrl" })
        assertTrue(validator.validate(withTocUrl("@js:url = url + '/toc'; url")).warnings.any { it.path == "ruleBookInfo.tocUrl" })
    }

    @Test fun inspectsAdditionalScriptFieldsAsWarnings() {
        listOf("loginCheckJs", "jsLib", "loginUrl", "header", "loginUi").forEach { field ->
            val obj = com.google.gson.JsonParser.parseString(withTocUrl("@js:baseUrl")).asJsonObject
            obj.addProperty(field, if (field in listOf("loginCheckJs", "jsLib")) "url" else "@JS:url")
            val report = validator.validate(obj.toString())
            assertTrue("$field: $report", report.isValid)
            assertTrue("$field: $report", report.warnings.any { it.path == field })
        }
    }

    @Test fun keywordInspectionDoesNotBlockDynamicSearchOrMatchMonkey() {
        fun report(search: String): BookSourceValidator.Report {
            val obj = com.google.gson.JsonParser.parseString(withTocUrl("@js:baseUrl")).asJsonObject
            obj.addProperty("searchUrl", search)
            obj.add("ruleSearch", com.google.gson.JsonObject().apply { addProperty("bookList", ".book") })
            return validator.validate(obj.toString())
        }
        listOf("/monkey", "/search?key=literal", "@JS:java.get('searchRequest')").forEach {
            assertTrue(it, report(it).isValid)
            assertTrue(it, report(it).warnings.any { warning -> warning.path == "searchUrl" })
        }
        listOf("/search?q={{ key }}", "@JS:baseUrl + '/?q=' + key", "/search,{'method':'POST','body':'q={{encodeURIComponent(key)}}'}").forEach {
            assertFalse(it, report(it).warnings.any { warning -> warning.path == "searchUrl" })
        }
    }

    @Test fun warnsOnSingleCharacterCheckKeyWord() {
        fun src(k: String) = com.google.gson.Gson().toJson(mapOf(
            "bookSourceName" to "示例", "bookSourceUrl" to "https://example.com",
            "ruleSearch" to mapOf("checkKeyWord" to k),
            "ruleToc" to mapOf("chapterList" to ".list a"), "ruleContent" to mapOf("content" to "#content"),
        ))
        assertTrue(validator.validate(src(" 我 ")).warnings.any { it.path == "ruleSearch.checkKeyWord" })
        assertFalse(validator.validate(src("捞尸人")).warnings.any { it.path == "ruleSearch.checkKeyWord" })
    }

    @Test fun classInitAndKeyTemplateDoNotThrow() {
        val report = validator.validate(
            """{"bookSourceName":"t","bookSourceUrl":"https://example.com","searchUrl":"/s?q={{key}}","ruleSearch":{"bookList":".b"},"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""",
        )
        assertTrue(report.isValid)
    }

    @Test fun warnsOnJsLibTopLevelReaderBindingsWithoutBlockingSave() {
        listOf(
            "source.get('browserToken')",
            "java.ajax(baseUrl)",
            "cookie.getCookie(baseUrl)",
            "cache.get('token')",
        ).forEach { jsLib ->
            val report = validator.validate(withJsLib(jsLib))
            assertTrue("jsLib=$jsLib issues=${report.issues}", report.isValid)
            assertFalse(report.issues.any { it.path == "jsLib" })
            val warning = report.warnings.firstOrNull { it.path == "jsLib" && it.message.contains("顶层疑似引用") }
            assertTrue("jsLib=$jsLib warnings=${report.warnings}", warning != null)
            assertTrue(warning!!.message.contains("不自动改写"))
        }
        val combined = validator.validate(withJsLib("source.get('t'); cookie.getCookie(baseUrl); cache.put('t','1')"))
        assertTrue(combined.isValid)
        val warning = combined.warnings.first { it.path == "jsLib" && it.message.contains("顶层疑似引用") }
        assertTrue(warning.message.contains("cache") && warning.message.contains("cookie") && warning.message.contains("source"))
    }

    @Test fun warnsOnJsLibTopLevelWebViewCall() {
        val report = validator.validate(withJsLib("""java.webView(null, baseUrl, "document.cookie")"""))
        assertTrue(report.issues.toString(), report.isValid)
        assertTrue(report.warnings.toString(), report.warnings.any {
            it.path == "jsLib" && it.message.contains("java.webView") && it.message.contains("object") && it.message.contains("不自动改写")
        })
        assertTrue(report.warnings.any { it.path == "jsLib" && it.message.contains("顶层疑似引用") && it.message.contains("java") })
    }

    @Test fun doesNotWarnJsLibStringsCommentsOrOfficialThisDestructure() {
        listOf(
            """var s = "source.get('t'); java.webView(null,u,js)"; function f(){ return s; }""",
            """// source.get("browserToken")
               /* java.webView(null, url, js) cookie.getCookie(x) cache.get('t') */
               function f(){ return 1; }""",
            """function getToken(){ const {java, source} = this; return source.get('t') || java.webView(null, baseUrl, "document.cookie"); }""",
            """function source(){ return 1; } function java(){ return 2; }""",
            """function f(){ return book.source + ({source:1}).source; }""",
            """function mkKey(k){ return 'q_'+k; }""",
        ).forEach { jsLib ->
            val warnings = validator.validate(withJsLib(jsLib)).warnings.filter { it.path == "jsLib" }
            assertTrue("jsLib=$jsLib warnings=$warnings", warnings.none { it.message.contains("顶层疑似引用") || it.message.contains("顶层疑似调用") })
            assertTrue("jsLib=$jsLib warnings=$warnings", warnings.none { it.message.contains("不视为必错") })
        }
    }

    @Test fun jsLibInFunctionBareBindingsAreCautiousNotBlocking() {
        val report = validator.validate(withJsLib("function ping(){ return source.get('t') + java.webView(null, baseUrl, '1'); }"))
        assertTrue(report.issues.toString(), report.isValid)
        assertFalse(report.warnings.any { it.path == "jsLib" && it.message.contains("顶层") })
        val cautious = report.warnings.firstOrNull { it.path == "jsLib" && it.message.contains("不视为必错") }
        assertTrue(report.warnings.toString(), cautious != null)
        assertTrue(cautious!!.message.contains("source") && cautious.message.contains("java"))
        assertTrue(cautious.message.contains("java.webView") || cautious.message.contains("裸引用"))
    }

    @Test fun jsLibHttpUrlIsNotInspectedAsScript() {
        val report = validator.validate(withJsLib("https://example.com/lib.js"))
        assertTrue(report.isValid)
        assertFalse(report.warnings.any { it.path == "jsLib" && (it.message.contains("顶层") || it.message.contains("不视为必错")) })
    }
}
