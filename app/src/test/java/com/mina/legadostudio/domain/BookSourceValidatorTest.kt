package com.mina.legadostudio.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSourceValidatorTest {
    private val validator = BookSourceValidator()

    @Test fun rejectsInvalidJson() {
        assertFalse(validator.validate("{").isValid)
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
