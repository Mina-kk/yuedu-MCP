package com.mina.legadostudio.verification

import android.content.ContextWrapper
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.data.db.VerificationSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 跨 kind 复用阻断：webview 与 image_code 会话共用 jobId="mcp" + 同域时会互相复用 WAITING 行。
 * 2.0.12 的无条件刷新把复用行的 kind 也改写，导致 AI 交替调用 startBrowserAwait 与
 * getVerificationCode 时同一会话在两种类型间翻转：用户按 webview 流程完成（无答案文本），
 * awaitAnswer 永远等不到非空 answer，AI 侧表现为「用户取消或等待超时」。
 * 复用必须限定同 kind；不同 kind 各保留独立 WAITING 会话。
 */
class VerificationCrossKindReuseTest {
    private class Fixture {
        val saved = mutableListOf<VerificationSessionEntity>()
        private val dao = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StudioDao::class.java)) { _, method, args ->
            when (method.name) {
                "waitingVerification" -> saved.lastOrNull { it.status == "WAITING" && it.jobId == args!![0] && it.domain == args[1] }
                "saveVerificationSession" -> { saved += args!![0] as VerificationSessionEntity; Unit }
                "deleteVerificationSession" -> { saved.removeAll { it.id == (args!![0] as String) }; Unit }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as StudioDao
        val coordinator = VerificationCoordinator(
            allocate(ContextWrapper::class.java), dao,
            allocate(RuntimeCookieStore::class.java), allocate(VerificationWebViewStateStore::class.java),
        ) { }
    }

    @Test fun webviewAndImageCodeKeepSeparateWaitingSessionsForTheSameDomain() = runBlocking {
        val f = Fixture()
        // AI 先建 webview 会话（startBrowserAwait）
        val webview = f.coordinator.create("mcp", "https://iyueba.net/z/", "浏览器验证", kind = "webview")
        // 再建 image_code 会话（getVerificationCode，同域）
        val imageCode = f.coordinator.create("mcp", "https://iyueba.net/captcha.jpg", "图片验证码", kind = "image_code", imageData = "data:image/png;base64,AA")
        // 必须是两个独立会话：AI 的 awaitAnswer 与 awaitCompleted 各自等各自的行
        assertNotEquals("webview and image_code must NOT share a WAITING row", webview.id, imageCode.id)
        assertEquals("webview", f.saved.first { it.id == webview.id }.kind)
        assertEquals("image_code", f.saved.first { it.id == imageCode.id }.kind)
    }

    @Test fun sameKindReuseStillRefreshesImageAndAdvancesUpdatedAt() = runBlocking {
        val f = Fixture()
        val first = f.coordinator.create("mcp", "https://iyueba.net/captcha.jpg", "图片验证码", kind = "image_code", imageData = "data:image/png;base64,OLD")
        Thread.sleep(2)
        val second = f.coordinator.create("mcp", "https://iyueba.net/captcha.jpg", "图片验证码", kind = "image_code", imageData = "data:image/png;base64,NEW")
        assertEquals("same-kind reuse keeps one row", first.id, second.id)
        assertEquals("data:image/png;base64,NEW", second.imageData)
        assertEquals("image_code", second.kind)
    }

    @Test fun completingWebviewSessionDoesNotDisturbImageCodeSession() = runBlocking {
        val f = Fixture()
        val webview = f.coordinator.create("mcp", "https://iyueba.net/z/", "浏览器验证", kind = "webview")
        val imageCode = f.coordinator.create("mcp", "https://iyueba.net/captcha.jpg", "图片验证码", kind = "image_code", imageData = "data:image/png;base64,AA")
        // 用户按 webview 流程完成（answer 为空）
        f.saved.replaceAll { if (it.id == webview.id) it.copy(status = "COMPLETED", finalUrl = "https://iyueba.net/z/?code=2764") else it }
        // image_code 会话必须仍是 WAITING，等待用户输入答案
        val row = f.saved.first { it.id == imageCode.id }
        assertEquals("WAITING", row.status)
        assertEquals("image_code", row.kind)
    }

    companion object {
        private fun <T : Any> allocate(type: Class<T>): T {
            val klass = Class.forName("sun.misc.Unsafe")
            val unsafe = klass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return requireNotNull(type.cast(klass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)))
        }
    }
}
