package com.mina.legadostudio.mcp

import com.google.gson.Gson
import com.mina.legadostudio.diagnostic.ExceptionDigest
import com.mina.legadostudio.diagnostic.RuntimeFaultStore

/**
 * 工具级可编程错误：中文 `message` 保持旧文案，同时附带 code/category/retryable。
 * 客户端仍可用 `NoClassDefFoundError` 子串判断，自动化也可读 `code`。
 */
object McpToolErrors {
    const val APP_COMPONENT_LOAD_FAILED = "APP_COMPONENT_LOAD_FAILED"
    const val CATEGORY_APP_FAULT = "app_fault"

    private val gson = Gson()

    fun linkageMessage(error: Throwable): String =
        "运行组件加载失败（${error.javaClass.simpleName}），请核对已安装 APK 版本并导出操作日志"

    fun linkageFields(error: Throwable, first: RuntimeFaultStore.Snapshot? = RuntimeFaultStore.firstOrNull()): Map<String, Any?> {
        val message = linkageMessage(error)
        return linkedMapOf(
            "message" to message,
            "code" to APP_COMPONENT_LOAD_FAILED,
            "category" to CATEGORY_APP_FAULT,
            "retryable" to false,
            "errorClass" to error.javaClass.name,
            "causeClass" to error.cause?.javaClass?.name,
            "causeChain" to ExceptionDigest.chain(error),
            "firstFailure" to (first ?: RuntimeFaultStore.record(error)).toPayload(),
        )
    }

    /** 旧中文一行 + JSON 对象，grep 类名与解析 code 都可用。 */
    fun linkageText(error: Throwable, first: RuntimeFaultStore.Snapshot? = RuntimeFaultStore.firstOrNull()): String {
        val fields = linkageFields(error, first)
        return fields["message"].toString() + "\n" + gson.toJson(fields)
    }
}
