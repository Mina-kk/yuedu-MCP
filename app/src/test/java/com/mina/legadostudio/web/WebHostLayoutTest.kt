package com.mina.legadostudio.web

import com.mina.legadostudio.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主布局常量（`.tasks/host-remote-build.md` 第 6 条）。
 *
 * 验证页曾经 MATCH_PARENT 盖住主 WebView，Vue 渲染的「关闭/完成」工具栏被外站遮住，
 * 用户点不到。现在隔离页从顶部 96dp 之下开始，主 WebView 不 resize（否则底部弹层错位）。
 */
class WebHostLayoutTest {

    @Test
    fun `verification toolbar reserves exactly 96dp`() {
        assertEquals(96, MainActivity.VERIFICATION_TOOLBAR_DP)
        assertEquals(96, MainActivity.verificationToolbarPx(1f))
        assertEquals(192, MainActivity.verificationToolbarPx(2f))
        // 2.625x（常见 420dpi）：96 * 2.625 = 252
        assertEquals(252, MainActivity.verificationToolbarPx(2.625f))
        // 3.5x：96 * 3.5 = 336
        assertEquals(336, MainActivity.verificationToolbarPx(3.5f))
    }

    @Test
    fun `toolbar height scales with density and never collapses`() {
        for (density in listOf(0.75f, 1f, 1.5f, 2f, 2.75f, 3f, 4f)) {
            val px = MainActivity.verificationToolbarPx(density)
            assertTrue("density=$density px=$px", px >= 72)
            assertTrue("density=$density px=$px", px == Math.round(96 * density).toInt())
        }
    }
}
