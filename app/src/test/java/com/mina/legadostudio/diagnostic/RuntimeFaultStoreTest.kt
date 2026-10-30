package com.mina.legadostudio.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeFaultStoreTest {
    @Test fun recordsOnlyTheFirstFailure() {
        RuntimeFaultStore.clear()
        val first = RuntimeFaultStore.record(ExceptionInInitializerError("init"), "inspect_rule")
        val second = RuntimeFaultStore.record(NoClassDefFoundError("later"), "validate_source")
        assertEquals(first.errorClass, second.errorClass)
        assertEquals("inspect_rule", RuntimeFaultStore.firstOrNull()?.tool)
        assertEquals(ExceptionInInitializerError::class.java.name, first.errorClass)
        RuntimeFaultStore.clear()
    }
}
