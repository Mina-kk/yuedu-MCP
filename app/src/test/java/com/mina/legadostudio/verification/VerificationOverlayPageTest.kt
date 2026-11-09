package com.mina.legadostudio.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球 WebView 页面（纯 HTML/CSS，无 JS）的纯逻辑测试：
 * - 域名/文案转义：注入的 `<script>`、引号、尖括号不能逃出文本节点；
 * - 外站无桥：页面里不存在任何可发起请求的元素/属性，也没有 JS interface 痕迹；
 * - 样式与取值限制：主题色固定 lime/charcoal、域名长度与字符集被钳制、不出现全屏原生按钮；
 * - baseURL 与导航策略：只允许 data: 文档本身，其余一律拦截。
 */
class VerificationOverlayPageTest {

    private val evil = "evil\"><script>alert(1)</script><img src=x onerror=alert(2)>"

    @Test fun escapeHtmlNeutralizesMarkup() {
        val out = VerificationOverlayPage.escapeHtml(evil)
        assertFalse(out.contains("<script"))
        assertFalse(out.contains("<img"))
        assertFalse(out.contains("\""))
        assertFalse(out.contains("'"))
        assertTrue(out.contains("&lt;script&gt;"))
        assertTrue(out.contains("&quot;"))
        assertTrue(out.contains("&gt;"))
    }

    @Test fun escapeHtmlHandlesAmpersandAndBacktick() {
        assertEquals("a&amp;b&#96;c", VerificationOverlayPage.escapeHtml("a&b`c"))
    }

    @Test fun normalPageContainsNoInjectionFromDomain() {
        val html = VerificationOverlayPage.normalPage()
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("onerror"))
    }

    @Test fun alertPageEscapesDomain() {
        val html = VerificationOverlayPage.alertPage(evil)
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<img"))
        // 域名先过 sanitizeDomain（剔除 <>"'`/\），剩下的残留文本再转义：不会出现可执行标签
        assertFalse(html.contains("alert(1)</"))
        assertTrue(html.contains("script"))
    }

    @Test fun alertPageEscapesPurpose() {
        val html = VerificationOverlayPage.alertPage("a.com", "<b>purpose</b>\"")
        assertFalse(html.contains("<b>purpose"))
        assertFalse(html.contains("</b>"))
        // 尖括号被 sanitizePurpose 剔除，引号被 escapeHtml 转义
        assertTrue(html.contains("bpurpose/b"))
    }

    @Test fun sanitizeDomainDropsUnsafeCharsAndClampsLength() {
        assertEquals("a.com", VerificationOverlayPage.sanitizeDomain("a.com"))
        val long = "x".repeat(400) + ".com"
        val out = VerificationOverlayPage.sanitizeDomain(long)
        assertTrue(out.length <= VerificationOverlayPage.MAX_DOMAIN_CHARS)
        assertEquals("", VerificationOverlayPage.sanitizeDomain("   "))
        // 控制字符/空白/不可见字符一律剔除，避免撑破气泡
        assertEquals("ab", VerificationOverlayPage.sanitizeDomain("a\u0000\u200B b"))
    }

    @Test fun sanitizePurposeClampsLength() {
        val out = VerificationOverlayPage.sanitizePurpose("p".repeat(500))
        assertTrue(out.length <= VerificationOverlayPage.MAX_PURPOSE_CHARS)
    }

    @Test fun pagesUseVaultPalette() {
        for (html in listOf(VerificationOverlayPage.normalPage(), VerificationOverlayPage.alertPage("a.com"))) {
            assertTrue(html.contains(VerificationOverlayPage.COLOR_LIME))
            assertTrue(html.contains(VerificationOverlayPage.COLOR_CHARCOAL))
        }
    }

    @Test fun pagesAreOfflineAndBridgeless() {
        for (html in listOf(VerificationOverlayPage.normalPage(), VerificationOverlayPage.alertPage("a.com"))) {
            assertFalse(html.contains("<script"))
            assertFalse(html.contains("javascript:"))
            assertFalse(html.contains("http://"))
            assertFalse(html.contains("https://"))
            assertFalse(html.contains("<img"))
            assertFalse(html.contains("<iframe"))
            assertFalse(html.contains("<link"))
            assertFalse(html.contains("@import"))
            assertFalse(html.contains("url("))
            // 无表单/按钮：点击由原生 onTouchListener 管，页面里不放可交互控件
            assertFalse(html.contains("<button"))
            assertFalse(html.contains("<form"))
            assertFalse(html.contains("<input"))
            assertTrue(html.contains("viewport"))
        }
    }

    @Test fun alertPageShowsDomainAndHint() {
        val html = VerificationOverlayPage.alertPage("www.example.com", "登录确认")
        assertTrue(html.contains("www.example.com"))
        assertTrue(html.contains("登录确认"))
        assertTrue(html.contains("点击验证"))
    }

    @Test fun alertPageWithoutDomainFallsBackToHint() {
        val html = VerificationOverlayPage.alertPage("", "")
        assertTrue(html.contains("点击验证"))
        assertFalse(html.contains("null"))
    }

    @Test fun baseUrlIsOpaqueAndNavigationBlocked() {
        assertEquals(VerificationOverlayPage.BASE_URL, VerificationOverlayPage.baseUrl())
        assertTrue(VerificationOverlayPage.baseUrl().startsWith(VerificationOverlayPage.BASE_URL))
        // 自身文档放行，其它一切拦截
        assertTrue(VerificationOverlayPage.isAllowedNavigation(VerificationOverlayPage.BASE_URL))
        assertTrue(VerificationOverlayPage.isAllowedNavigation(VerificationOverlayPage.BASE_URL + "#x"))
        assertFalse(VerificationOverlayPage.isAllowedNavigation("https://evil.example/x"))
        assertFalse(VerificationOverlayPage.isAllowedNavigation("http://evil.example/x"))
        assertFalse(VerificationOverlayPage.isAllowedNavigation("file:///etc/passwd"))
        assertFalse(VerificationOverlayPage.isAllowedNavigation("content://media/1"))
        assertFalse(VerificationOverlayPage.isAllowedNavigation("javascript:alert(1)"))
        assertFalse(VerificationOverlayPage.isAllowedNavigation("intent://x"))
        assertFalse(VerificationOverlayPage.isAllowedNavigation(""))
        assertFalse(VerificationOverlayPage.isAllowedNavigation(null))
    }

    @Test fun pageHtmlStaysSmall() {
        assertTrue(VerificationOverlayPage.normalPage().length < VerificationOverlayPage.MAX_PAGE_CHARS)
        assertTrue(VerificationOverlayPage.alertPage("a".repeat(200)).length < VerificationOverlayPage.MAX_PAGE_CHARS)
    }
}
