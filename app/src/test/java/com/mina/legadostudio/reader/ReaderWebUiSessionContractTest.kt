package com.mina.legadostudio.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ReaderWebUiSession` 的**源码契约**（与 ReaderWebToolContractTest 同风格）。
 *
 * 原因：WebView 本体在 JVM 单测里无法实例化，会话的「入口/出口配置身份复检」只能靠源码级断言
 * 钉住形状；纯判定与脱敏本身由 ReaderWebUiPolicyTest 覆盖。
 *
 * 这里守的是一条安全性质：**旧页面的观测结果不能被标成新配置**。
 * 允许重构实现，但不允许删掉这些步骤。
 */
class ReaderWebUiSessionContractTest {

    private fun sessionSource(): String {
        val file = listOf(
            File("src/main/java/com/mina/legadostudio/reader/ReaderWebUiSession.kt"),
            File("app/src/main/java/com/mina/legadostudio/reader/ReaderWebUiSession.kt"),
        ).first { it.isFile }
        return file.readText()
    }

    private fun bodyOf(name: String): String {
        val source = sessionSource()
        val start = listOf("suspend fun $name(", "fun $name(", "private fun $name(")
            .map { source.indexOf(it) }
            .firstOrNull { it >= 0 }
        assertTrue("缺少方法 $name", start != null)
        // 取到下一个方法（或类尾）为止，覆盖整个方法体
        val next = listOf("\n    suspend fun ", "\n    private fun ", "\n    private suspend fun ")
            .map { source.indexOf(it, start!! + 1) }
            .filter { it > 0 }
            .minOrNull()
        return if (next == null) source.substring(start!!) else source.substring(start!!, next)
    }

    @Test fun sessionPinsIdentityNotOnlyOrigin() {
        val source = sessionSource()
        // 会话状态里必须有身份（origin + revision + 令牌指纹），不能只存 origin
        assertTrue(source.contains("ReaderWebUiPolicy.SessionIdentity"))
        assertTrue(source.contains("val identity: ReaderWebUiPolicy.SessionIdentity"))
        assertTrue(source.contains("val origin: ReaderWebUiPolicy.Origin get() = identity.origin"))
        // 身份比较只走统一判定
        assertTrue(source.contains("ReaderWebUiPolicy.identityChange("))
    }

    @Test fun everyOperationRechecksIdentityAtEntryAndBeforeReturning() {
        // 每个动作入口先复核；返回值来自 readSnapshotLocked，而它内部还要再复核一次
        listOf("open", "snapshot", "click", "pressEnter", "fill", "scroll", "back").forEach { action ->
            val body = bodyOf(action)
            assertTrue("$action 入口必须复核配置身份", body.contains("requireIdentity()"))
            assertTrue("$action 必须经 readSnapshotLocked 返回", body.contains("readSnapshotLocked("))
        }
        // 出口复核：readSnapshotLocked 在读完 DOM 后、返回前再检查一次身份
        val snapshotFn = bodyOf("readSnapshotLocked")
        assertTrue(snapshotFn.contains("verifyIdentityBeforeReturn("))
        val entryIdx = snapshotFn.indexOf("buildSnapshotScript")
        val exitIdx = snapshotFn.indexOf("verifyIdentityBeforeReturn(")
        assertTrue("出口复核必须在读完 DOM 之后", entryIdx >= 0 && exitIdx > entryIdx)
    }

    @Test fun identityChangeDestroysSessionAndReportsConfigChanged() {
        val requireFn = bodyOf("requireIdentity")
        assertTrue("身份不符必须销毁旧 WebView", requireFn.contains("destroyNow()"))
        assertTrue("身份不符必须报 CONFIG_CHANGED", requireFn.contains("configChanged("))
        val verifyFn = bodyOf("verifyIdentityBeforeReturn")
        assertTrue("出口复核同样要销毁", verifyFn.contains("destroyNow()"))
        assertTrue(verifyFn.contains("configChanged("))
        // 变化分类由纯逻辑给出，会话不自己拼比较条件
        val source = sessionSource()
        assertTrue(source.contains("\"CONFIG_CHANGED\""))
        assertTrue(source.contains("ReaderWebUiPolicy.IdentityChange"))
    }

    @Test fun openReusesOnlyWhenIdentityMatches() {
        val body = bodyOf("open")
        // 复用条件里必须包含 identityChange == null，而不是只比 origin
        assertTrue(body.contains("ReaderWebUiPolicy.identityChange(existing.identity, current.identity) == null"))
        assertFalse("不得退回到只比 origin", body.contains("existing.origin == "))
        assertTrue("身份不同要重建", body.contains("attach(current.identity)"))
    }

    @Test fun clickAndEnterRequireObservedEffectNotMerelyDispatched() {
        listOf("click", "pressEnter").forEach { action ->
            val body = bodyOf(action)
            assertTrue("$action 派发前应采样", body.contains("buildSignatureScript()"))
            assertTrue("$action 须等待异步动作", body.contains("awaitActionObservation("))
            assertTrue("$action 须明确核验结果", body.contains("requireObserved(outcome)"))
            assertTrue("$action 应先复核身份和同源快照再报告", body.indexOf("readSnapshotLocked(") < body.indexOf("requireObserved(outcome)"))
        }
        val observe = bodyOf("awaitActionObservation")
        assertTrue(observe.contains("ACTION_NO_EFFECT_MS"))
        assertTrue(observe.contains("ACTION_MAX_OBSERVE_MS"))
        assertTrue(observe.contains("state.startedGeneration > generation"))
        assertTrue(observe.contains("ACTION_SETTLE_MS"))
        val outcome = bodyOf("requireObserved")
        assertTrue(outcome.contains("\"NO_EFFECT\""))
        assertTrue(outcome.contains("\"ACTION_UNVERIFIED\""))
        assertTrue(outcome.contains("ActionObservation.OBSERVED -> Unit"))
        assertFalse(bodyOf("click").contains("awaitReady(requireLoaded = false)"))
    }

    @Test fun snapshotIsRedactedWithTheSavedTokenBeforeReturning() {
        val body = bodyOf("readSnapshotLocked")
        assertTrue("返回前必须做令牌脱敏", body.contains("ReaderWebUiPolicy.redactToken("))
        // 脱敏用的令牌来自出口复核读到的**最新**配置，而不是入口的旧值
        val tokenIdx = body.indexOf("verifyIdentityBeforeReturn(")
        val redactIdx = body.indexOf("ReaderWebUiPolicy.redactToken(")
        assertTrue("先复核身份再取令牌脱敏", tokenIdx >= 0 && redactIdx > tokenIdx)
    }

    @Test fun sessionStillAvoidsTokenEchoAndNeverPersistsPages() {
        val source = sessionSource()
        // 身份通过统一构造入口建立（内部用指纹，不保存明文令牌）
        assertTrue(source.contains("ReaderWebUiPolicy.SessionIdentity.of(config)"))
        assertTrue(source.contains("ReaderWebUiPolicy.SessionIdentity"))
        // 不得落盘页面正文/截图、不得把令牌写进日志
        assertFalse("不得把令牌写进日志", source.contains("Log.d(") || source.contains("Log.i(") || source.contains("println("))
        assertFalse(source.contains("FileOutputStream"))
        assertFalse(source.contains("FileWriter"))
        assertFalse(source.contains("SharedPreferences"))
        // 仍然不注册 JS bridge、不放开 file/content access
        assertFalse("不得注册 JS bridge（注释里说明可以）", source.contains(".addJavascriptInterface("))
        assertTrue(source.contains("allowFileAccess = false"))
        assertTrue(source.contains("allowContentAccess = false"))
    }
}
