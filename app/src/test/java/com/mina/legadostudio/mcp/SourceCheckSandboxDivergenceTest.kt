package com.mina.legadostudio.mcp

import com.mina.legadostudio.domain.CheckSourceRunner
import com.mina.legadostudio.domain.RecentCheckOutcomes
import com.mina.legadostudio.domain.SourceCheckEvidence
import com.mina.legadostudio.runtime.LegadoRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

class SourceCheckSandboxDivergenceTest {

    @Test fun sandboxReasonsAreClassifiedPrecisely() {
        val cases = listOf(
            "jsLib 为外部 URL，依赖它的报错属验收环境限制" to "external_jslib_skipped",
            "CONTENT_TOO_LARGE：超出容量" to "content_too_large",
            "二进制资源已按文本类型跳过，未下载正文" to "binary_skipped",
            "正文 endReason=page_limit" to "page_limit",
            "【API 提示】阅读端通常可用" to "missing_java_api",
            "【签名提示】沙箱不支持该签名" to "signature_unsupported",
        )
        cases.forEach { (message, reason) ->
            assertEquals(reason, SourceCheckEvidence.sandboxReason(message))
            assertEquals("sandbox", SourceCheckEvidence.categorizeMessage(message))
            assertTrue(SourceCheckEvidence.isSandboxLimit(message, null))
        }
    }

    @Test fun onlyKnownReaderApiNamesAreSandboxLimits() {
        val names = listOf(
            "Packages", "org", "java.lang", "javax", "android", "java.getElement", "java.getElements",
            "importScript", "t2s", "s2t", "createAsymmetricCrypto", "createSymmetricCrypto",
            "getVerificationCode", "startBrowser", "startBrowserAwait",
        )
        names.forEach { name ->
            assertEquals(name, "sandbox", SourceCheckEvidence.categorizeMessage("$name is not defined"))
            assertEquals(name, "missing_java_api", SourceCheckEvidence.sandboxReason("$name is not defined"))
        }
        assertEquals("sandbox", SourceCheckEvidence.categorizeMessage("TypeError: java.getElement is not a function"))
        assertEquals("missing_java_api", SourceCheckEvidence.sandboxReason("TypeError: java.getElement is not a function"))
        assertEquals("sandbox", SourceCheckEvidence.categorizeMessage("Cannot find function getElement in object"))
        assertEquals("missing_java_api", SourceCheckEvidence.sandboxReason("Cannot find function getElement in object"))
        assertEquals("unknown", SourceCheckEvidence.categorizeMessage("TypeError: someRuleHelper is not a function"))
        assertEquals("unknown", SourceCheckEvidence.categorizeMessage("someRuleHelper is not defined"))
        assertFalse(SourceCheckEvidence.isSandboxLimit("someRuleHelper is not defined", null))
    }

    @Test fun zeroResultSearchIsRuleEvidenceWhateverTheKeyword() {
        for (keyword in listOf("我", "我的")) {
            val evidence = SourceCheckEvidence.of(
                "搜索",
                LegadoRuntime.DebugReport("搜索", keyword, listOf("列表 0 条"), emptyList<Any>()),
            )
            assertFalse(evidence.ok)
            assertEquals("empty", evidence.category)
            assertEquals(null, evidence.sandboxReason)
            assertFalse(evidence.reason!!.contains("无需修改规则"))
        }
    }

    @Test fun undefinedUrlIsRuleErrorNotSandboxLimit() {
        val message = "ReferenceError: “url” 未定义。 (<script-5>#1)\n【规则错误】阅读(Legado) 规则 JS 中没有 `url` 变量，阅读端同样会报「url 未定义」；取当前页地址请改用 `baseUrl`（如 `@js:baseUrl`）。"
        assertEquals("unknown", SourceCheckEvidence.categorizeMessage(message))
        assertEquals(null, SourceCheckEvidence.sandboxReason(message))
        assertFalse(SourceCheckEvidence.isSandboxLimit(message, null))
    }

    @Test fun pageLimitIsRecordedButNotFailed() {
        val evidence = SourceCheckEvidence.of(
            "正文",
            LegadoRuntime.DebugReport(
                "正文", "https://example.test/chapter", listOf("第1页 HTTP 200"),
                mapOf(
                    "pages" to 1,
                    "content" to "有正文",
                    "contentTotalChars" to 3,
                    "contentDiagnostics" to mapOf("endReason" to "page_limit", "pages" to listOf(mapOf("httpStatus" to 200))),
                ),
            ),
        )
        assertTrue(evidence.ok)
        assertEquals("sandbox", evidence.category)
        assertEquals("page_limit", evidence.sandboxReason)
    }

    @Test fun allSandboxFailuresAreInconclusiveButMixedFailureIsNot() = runBlocking {
        suspend fun run(detailFailure: String): Map<String, Any> {
            val runtime = object : LegadoRuntime {
                override suspend fun inspect(request: LegadoRuntime.InspectRequest) = error("unused")
                override suspend fun debug(sourceJson: String, entry: String): LegadoRuntime.DebugReport {
                    if (entry.startsWith("http")) throw IllegalStateException(detailFailure)
                    error("unexpected")
                }
                override suspend fun evaluate(js: String, baseUrl: String, previous: Any?) = error("unused")
                override fun validate(sourceJson: String) = com.mina.legadostudio.domain.BookSourceValidator().validate(sourceJson)
            }
            return CheckSourceRunner.run(
                CheckSourceRunner.Request(
                    """{"bookSourceName":"x","bookSourceUrl":"https://example.test","ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""",
                    detailUrl = "https://example.test/book",
                ), runtime, null,
            )
        }
        val sandboxOnly = run("【API 提示】missing")
        assertEquals("inconclusive", (sandboxOnly["verdict"] as Map<*, *>)["summary"])
        assertEquals(false, RecentCheckOutcomes.get("https://example.test")?.hadFailure)
        val mixed = run("rule selector failed")
        assertEquals("unusable", (mixed["verdict"] as Map<*, *>)["summary"])
        assertEquals(true, RecentCheckOutcomes.get("https://example.test")?.hadFailure)
    }

    @Test fun divergencePayloadDeduplicatesReasonsAndIncludesDefaultUa() {
        val fields = sandboxDivergenceFields(
            """{"bookSourceUrl":"https://example.test"}""",
            mapOf("evidence" to mapOf("正文" to SourceCheckEvidence.StageEvidence(
                "正文", true, true, category = "sandbox", sandboxReason = "page_limit",
            ))),
        )
        val divergence = fields["sandboxDivergence"] as List<*>
        assertTrue(divergence.any { (it as Map<*, *>)["reason"] == "page_limit" })
        assertTrue(divergence.any { (it as Map<*, *>)["reason"] == "default_user_agent" })
        val policy = fields["ruleEditPolicy"] as Map<*, *>
        assertEquals(listOf("page_limit", "default_user_agent"), policy["doNotEditFor"])
        assertTrue(policy["reason"].toString().contains("reader_web_debug"))
    }

    @Test fun rateLimitedStageIsListedAsDoNotEdit() {
        val fields = sandboxDivergenceFields(
            """{"bookSourceUrl":"https://example.test","header":"{\"User-Agent\":\"x\"}"}""",
            mapOf("evidence" to mapOf("搜索" to SourceCheckEvidence.StageEvidence(
                "搜索", true, false, category = "rate_limited",
            ))),
        )
        val policy = fields["ruleEditPolicy"] as Map<*, *>
        assertEquals(listOf("site_rate_limited"), policy["doNotEditFor"])
    }

    @Test fun productionSourceHasNoForbiddenRuleRewritePhrasing() {
        val roots = listOf("src/main/java", "app/src/main/java").map(::File).filter { it.isDirectory }
        val source = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .joinToString("\n") { it.readText() }
        listOf("请改写调用", "请内联或改写", "显式写上目标 UA").forEach { phrase ->
            assertFalse("生产源码包含禁止措辞：$phrase", source.contains(phrase))
        }
    }
}
