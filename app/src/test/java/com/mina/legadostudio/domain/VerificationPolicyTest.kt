package com.mina.legadostudio.domain

import com.mina.legadostudio.verification.DomainVerifyMode
import com.mina.legadostudio.verification.VerificationPolicy
import com.mina.legadostudio.verification.VerificationPolicy.VerifyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationPolicyTest {
    private fun decide(mode: DomainVerifyMode, completedRecently: Boolean, viaWebView: Boolean) =
        VerificationPolicy.decide(mode, completedRecently, viaWebView, "https://example.org/verify", "example.org")

    @Test fun autoFirstTimeAsksHumanSession() {
        assertTrue(decide(DomainVerifyMode.AUTO, completedRecently = false, viaWebView = false) is VerifyAction.NewSession)
    }

    @Test fun autoAfterRecentCompletionEnablesWebView() {
        assertTrue(decide(DomainVerifyMode.AUTO, completedRecently = true, viaWebView = false) is VerifyAction.EnableWebView)
    }

    @Test fun autoWebViewChannelStillBlockedAsksHumanAgain() {
        // 回归：WebView 通道仍被拦时绝不能再开 WebView（旧版在这里死循环新建会话）
        assertTrue(decide(DomainVerifyMode.AUTO, completedRecently = true, viaWebView = true) is VerifyAction.NewSession)
        assertTrue(decide(DomainVerifyMode.AUTO, completedRecently = false, viaWebView = true) is VerifyAction.NewSession)
    }

    @Test fun alwaysModeIgnoresCompletionAndChannel() {
        // 「一搜一验」站点：不依赖缓存 cookie，每次都人工验证
        assertTrue(decide(DomainVerifyMode.ALWAYS, completedRecently = true, viaWebView = false) is VerifyAction.NewSession)
        assertTrue(decide(DomainVerifyMode.ALWAYS, completedRecently = true, viaWebView = true) is VerifyAction.NewSession)
    }

    @Test fun webviewModeRetriesUntilWebViewChannelBlocked() {
        assertTrue(decide(DomainVerifyMode.WEBVIEW, completedRecently = false, viaWebView = false) is VerifyAction.EnableWebView)
        assertTrue(decide(DomainVerifyMode.WEBVIEW, completedRecently = true, viaWebView = false) is VerifyAction.EnableWebView)
        assertTrue(decide(DomainVerifyMode.WEBVIEW, completedRecently = false, viaWebView = true) is VerifyAction.NewSession)
    }

    @Test fun newSessionCarriesCurrentUrlAndDomain() {
        val action = decide(DomainVerifyMode.ALWAYS, completedRecently = false, viaWebView = false) as VerifyAction.NewSession
        assertEquals("https://example.org/verify", action.url)
        assertEquals("example.org", action.domain)
    }
}
