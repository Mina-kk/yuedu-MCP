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
}
