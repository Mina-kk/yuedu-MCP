package com.mina.legadostudio.domain

import com.mina.legadostudio.diagnostic.RuntimeFaultStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSelfCheckTest {
    @Test fun readyWhenRuleEngineAndValidatorLoad() {
        RuntimeFaultStore.clear()
        val report = RuntimeSelfCheck.inspect()
        assertEquals(true, report["ready"])
        val rule = report["ruleEngine"] as Map<*, *>
        val validator = report["validator"] as Map<*, *>
        assertEquals(true, rule["ready"])
        assertEquals(true, validator["ready"])
        RuntimeFaultStore.clear()
    }
}
