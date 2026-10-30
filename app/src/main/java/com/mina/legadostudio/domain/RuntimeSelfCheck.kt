package com.mina.legadostudio.domain

import com.mina.legadostudio.diagnostic.ExceptionDigest
import com.mina.legadostudio.diagnostic.RuntimeFaultStore
import io.legado.app.model.analyzeRule.LegadoRuleEngine

/** 最小规则解析 + 书源校验器自检：失败只报告，不让异常逃逸成类初始化污染。 */
object RuntimeSelfCheck {
    fun inspect(): Map<String, Any?> {
        val rule = runCatching {
            val out = LegadoRuleEngine().extract("<html><body>ok</body></html>", "@css:body@text")
            mapOf("ready" to out.first.orEmpty().contains("ok"), "sample" to out.first)
        }.getOrElse { error ->
            RuntimeFaultStore.record(error, "self_check")
            mapOf("ready" to false, "errorClass" to error.javaClass.name, "chain" to ExceptionDigest.chain(error))
        }
        val validator = runCatching {
            val report = BookSourceValidator().validate(
                """{"bookSourceName":"t","bookSourceUrl":"https://example.com","searchUrl":"/s?q={{key}}","ruleSearch":{"bookList":".b"},"ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c"}}""",
            )
            mapOf("ready" to true, "valid" to report.isValid)
        }.getOrElse { error ->
            RuntimeFaultStore.record(error, "self_check")
            mapOf("ready" to false, "errorClass" to error.javaClass.name, "chain" to ExceptionDigest.chain(error))
        }
        return linkedMapOf(
            "ready" to (rule["ready"] == true && validator["ready"] == true),
            "ruleEngine" to rule,
            "validator" to validator,
            "firstFault" to RuntimeFaultStore.firstOrNull()?.toPayload(),
        )
    }
}
