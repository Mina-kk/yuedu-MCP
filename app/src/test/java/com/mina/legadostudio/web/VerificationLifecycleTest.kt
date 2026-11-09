package com.mina.legadostudio.web

import org.junit.Assert.*
import org.junit.Test

class VerificationLifecycleTest {
    @Test fun closingWhileAcquireWaitsInvalidatesOpenAndDefersLeaseRelease() {
        val state = VerificationLifecycle<String>()
        val owner = state.begin()
        val released = mutableListOf<Any>()
        state.cancel({ true }, { released.add(it) })
        assertFalse(state.canAttach(owner))
        assertTrue(released.isEmpty())
        assertTrue(runCatching { state.begin() }.isFailure)
        assertTrue(runCatching { state.attach(owner, "late webview") }.isFailure)
        assertTrue(state.finish(owner, { true }, { released.add(it) }))
        assertEquals(listOf(owner), released)
        assertFalse(state.busy)
    }

    @Test fun failedDestroyRetainsLeaseAndResourceUntilConfirmedDestroyed() {
        val state = VerificationLifecycle<String>()
        val owner = state.begin()
        state.attach(owner, "external page")
        var releases = 0
        assertFalse(state.cancel({ false }, { releases++ }))
        assertTrue(state.busy)
        assertEquals("external page", state.resource)
        assertEquals(0, releases)
        assertTrue(state.cancel({ true }, { releases++ }))
        assertFalse(state.busy)
        assertEquals(1, releases)
    }

    @Test fun throwingDestroyCannotFailOpenAndOldOwnerCannotTouchNewOwner() {
        val state = VerificationLifecycle<String>()
        val old = state.begin()
        state.attach(old, "old")
        var releases = 0
        assertFalse(state.finish(old, { error("detach/destroy failed") }, { releases++ }))
        assertEquals(0, releases)
        assertTrue(state.finish(old, { true }, { releases++ }))
        val next = state.begin()
        assertFalse(state.finish(old, { true }, { releases++ }))
        assertTrue(state.canAttach(next))
        assertEquals(1, releases)
    }
}
