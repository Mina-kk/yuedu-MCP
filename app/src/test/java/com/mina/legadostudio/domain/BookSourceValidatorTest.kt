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
        assertTrue(issue.message.contains("错误位置附近源码"))
        assertTrue(issue.message.contains(filler.takeLast(80)))
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
