package com.mina.legadostudio.verification

import android.content.ContextWrapper
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.data.db.VerificationSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * image_code 复用契约：站点每次 getVerificationCode 都拉一张新图，旧挑战随之作废。
 * 复用同一 WAITING 会话时必须把新图写进记录，否则用户一直对着旧图输入，永远失败。
 */
class VerificationImageCodeReuseTest {
    private class Fixture {
        val saved = mutableListOf<VerificationSessionEntity>()
        // Reuse path only writes; the notification endpoint is stubbed like the concurrency test.
        private val dao = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StudioDao::class.java)) { _, method, args ->
            when (method.name) {
                "waitingVerification" -> saved.lastOrNull()?.takeIf { it.status == "WAITING" && it.jobId == args!![0] && it.domain == args[1] }
                "saveVerificationSession" -> { saved += args!![0] as VerificationSessionEntity; Unit }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as StudioDao
        val coordinator = VerificationCoordinator(
            allocate(ContextWrapper::class.java), dao,
            allocate(RuntimeCookieStore::class.java), allocate(VerificationWebViewStateStore::class.java),
        ) { }
    }

    @Test fun reusedWaitingImageCodeSessionAlwaysRefreshesTheNewestCaptcha() = runBlocking {
        val f = Fixture()
        val first = f.coordinator.create("mcp", "https://example.org/captcha", "图片验证码（java.getVerificationCode）", kind = "image_code", imageData = "data:image/png;base64,OLD")
        val second = f.coordinator.create("mcp", "https://example.org/captcha", "图片验证码（java.getVerificationCode）", kind = "image_code", imageData = "data:image/png;base64,NEW")
        assertEquals(first.id, second.id)
        assertEquals("data:image/png;base64,NEW", second.imageData)
        assertEquals("data:image/png;base64,NEW", f.saved.last().imageData)
        assertTrue(f.saved.size >= 2)
    }

    @Test fun sameImageStillTouchesUpdatedAtSoTheListSurfacesTheRefreshedRow() = runBlocking {
        val f = Fixture()
        val first = f.coordinator.create("mcp", "https://example.org/captcha", "图片验证码（java.getVerificationCode）", kind = "image_code", imageData = "data:image/png;base64,SAME")
        // 即使图完全一致，复用也必须推进 updatedAt（列表按 updatedAt 排序，用户要看到刷新）。
        Thread.sleep(2)
        val second = f.coordinator.create("mcp", "https://example.org/captcha", "图片验证码（java.getVerificationCode）", kind = "image_code", imageData = "data:image/png;base64,SAME")
        assertEquals(first.id, second.id)
        assertTrue("updatedAt must advance on reuse", second.updatedAt > first.updatedAt)
    }

    companion object {
        private fun <T : Any> allocate(type: Class<T>): T {
            val klass = Class.forName("sun.misc.Unsafe")
            val unsafe = klass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return requireNotNull(type.cast(klass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)))
        }
    }
}
