package com.mina.legadostudio.verification

/**
 * 验证处置策略（纯函数，便于单测）。
 *
 * 判定表：
 * | mode    | viaWebView | completedRecently | 动作          |
 * |---------|-----------|-------------------|---------------|
 * | AUTO    | false     | false             | NewSession    |
 * | AUTO    | false     | true              | EnableWebView |
 * | AUTO    | true      | 任意              | NewSession    |
 * | ALWAYS  | 任意      | 任意              | NewSession    |
 * | WEBVIEW | false     | 任意              | EnableWebView |
 * | WEBVIEW | true      | 任意              | NewSession    |
 *
 * viaWebView=true 表示该请求已经过 WebView 通道仍被拦截：
 * 再开 WebView 毫无意义，必须转人工验证会话（此分支终止旧版「完成后仍循环新建会话」的死循环）。
 * ALWAYS 模式面向「一搜一个验证、验证地址总变」的站点：不做任何缓存复用判断，每次都人工验证。
 */
object VerificationPolicy {
    sealed interface VerifyAction {
        /** 开启 WEBVIEW 模式并自动重试一次（由调用方执行重试取证）。 */
        data class EnableWebView(val domain: String) : VerifyAction

        /** 建人工验证会话，等用户在验证中心完成。 */
        data class NewSession(val url: String, val domain: String) : VerifyAction
    }

    fun decide(
        mode: DomainVerifyMode,
        completedRecently: Boolean,
        viaWebView: Boolean,
        url: String = "",
        domain: String = "",
    ): VerifyAction = when {
        mode == DomainVerifyMode.ALWAYS -> VerifyAction.NewSession(url, domain)
        viaWebView -> VerifyAction.NewSession(url, domain)
        mode == DomainVerifyMode.WEBVIEW -> VerifyAction.EnableWebView(domain)
        mode == DomainVerifyMode.AUTO && completedRecently -> VerifyAction.EnableWebView(domain)
        else -> VerifyAction.NewSession(url, domain)
    }
}
