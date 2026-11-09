package com.mina.legadostudio.runtime

/**
 * 验收环境能力缺失（不是书源规则错误）。
 *
 * 用于「阅读端能做、沙箱当前环境做不了」的能力：远程 jsLib 下载被禁用、WebView 规则无平台等。
 * 调用方必须把它当 environment 处理（提示环境限制），不能归因成规则写错，
 * 也不能像旧实现那样静默跳过能力再让后续规则报 ReferenceError。
 */
class ReaderRuntimeEnvironmentException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    /** 机器可读的能力标识，如 `remote_js_lib` / `web_js` */
    var capability: String? = null
        private set

    fun withCapability(name: String): ReaderRuntimeEnvironmentException = apply { capability = name }
}
