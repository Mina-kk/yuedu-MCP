package com.mina.legadostudio.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BookSourceValidator URL 合法性 + 类型字段 + Legado #identity 后缀回归。 */
class BookSourceValidatorUrlTest {
    private val validator = BookSourceValidator()

    private fun src(bookSourceUrl: String, extra: String = "") = """{
      "bookSourceName":"示例","bookSourceUrl":"$bookSourceUrl",
      "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#content"}${if (extra.isNotEmpty()) ",$extra" else ""}
    }"""

    @Test fun acceptsIdentitySuffix() {
        // Legado 交换格式允许 bookSourceUrl#identity 锚后缀；校验必须剥掉再验
        assertTrue(validator.validate(src("https://a.com#🎃")).isValid)
        assertTrue(validator.validate(src("https://a.com#")).isValid)      // # 结尾空 identity
        assertTrue(validator.validate(src("https://a.com#pb1101")).isValid)
    }

    @Test fun rejectsNonHttpBookSourceUrl() {
        val report = validator.validate(src("ftp://a.com"))
        assertTrue(report.issues.any { it.path == "bookSourceUrl" && it.message.contains("HTTP") })
    }

    @Test fun rejectsBareDomainBookSourceUrl() {
        // 没有 scheme 的不是合法入口：真机按 URL 解析会失败
        assertFalse(validator.validate(src("a.com")).isValid)
    }

    @Test fun acceptsRelativeSearchUrl() {
        // searchUrl 可以是相对路径（运行时 resolve）；只要不是非法绝对 URL 就不拦
        val report = validator.validate(src("https://a.com", "\"searchUrl\":\"/s?q={{key}}\",\"ruleSearch\":{\"bookList\":\".b\"}"))
        assertFalse(report.issues.any { it.path == "searchUrl" && it.message.contains("HTTP") })
    }

    @Test fun rejectsInvalidLoginUrl() {
        val report = validator.validate(src("https://a.com", "\"loginUrl\":\"not-a-url\""))
        assertTrue(report.issues.any { it.path == "loginUrl" })
    }

    @Test fun rejectsNonStringTypeField() {
        val report = validator.validate(src("https://a.com", "\"bookSourceType\":{\"x\":1}"))
        assertTrue(report.issues.any { it.path == "bookSourceType" && it.message.contains("类型") })
    }

    @Test fun rejectsNonStringBookSourceUrl() {
        val report = validator.validate("""{"bookSourceName":"x","bookSourceUrl":123,"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""")
        assertTrue(report.issues.any { it.path == "bookSourceUrl" })
    }

    @Test fun acceptsJsPrefixedSearchUrl() {
        val report = validator.validate(src("https://a.com", "\"searchUrl\":\"@js:'https://a.com/s?q='+key\",\"ruleSearch\":{\"bookList\":\".b\"}"))
        // @js: 前缀不拦；没有 {{key}} 但有 key 关键字也不报（已含 "key"）
        assertFalse(report.issues.any { it.path == "searchUrl" && it.message.contains("HTTP") })
    }
}
