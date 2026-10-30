package com.mina.legadostudio.mcp

import com.mina.legadostudio.diagnostic.RuntimeFaultStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolErrorsTest {
    @Test fun linkageTextKeepsOldChineseAndAddsCode() {
        RuntimeFaultStore.clear()
        val text = McpToolErrors.linkageText(NoClassDefFoundError("com.mina.Foo"))
        assertTrue(text.contains("运行组件加载失败（NoClassDefFoundError）"))
        assertTrue(text.contains("APP_COMPONENT_LOAD_FAILED"))
        assertTrue(text.contains("app_fault"))
        assertTrue(text.contains("\"retryable\":false"))
        assertFalse(text.contains("https://"))
        RuntimeFaultStore.clear()
    }
}
