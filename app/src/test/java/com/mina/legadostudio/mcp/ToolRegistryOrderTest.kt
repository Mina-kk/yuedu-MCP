package com.mina.legadostudio.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地工具目录顺序（`.tasks/host-remote-build.md` 第 1 条）。
 *
 * `fullToolSpecs` 是 ConcurrentHashMap，`values` 迭代顺序是哈希顺序而不是注册顺序；
 * 前端按顺序渲染工具列表，顺序乱跳会让「第 N 个工具」这类操作错位。
 *
 * 这里用纯 JVM 的 [ToolRegistryOrder] 锁住排序算法（不构造 StudioMcpServer：
 * 它需要 Android Context，JVM 单测里没有）。
 */
class ToolRegistryOrderTest {

    @Test
    fun `order snapshot drives the listing and unknown names are appended`() {
        val registered = linkedMapOf(
            "get_source" to "B",
            "fetch_page" to "A",
            "match_sources" to "C",
        )
        val order = listOf("get_source", "fetch_page", "match_sources")
        assertEquals(
            listOf("B", "A", "C"),
            ToolRegistryOrder.arrange(order, registered) { it },
        )
    }

    @Test
    fun `tools missing from the order snapshot are never dropped`() {
        val registered = linkedMapOf("a" to "A", "b" to "B", "c" to "C")
        val arranged = ToolRegistryOrder.arrange(listOf("b"), registered) { it }
        assertEquals("B", arranged.first())
        assertEquals(setOf("A", "B", "C"), arranged.toSet())
        assertEquals(3, arranged.size)
    }

    @Test
    fun `duplicate registration keeps the first position`() {
        val order = mutableListOf<String>()
        listOf("x", "y", "x").forEach { name -> ToolRegistryOrder.register(order, name) }
        assertEquals(listOf("x", "y"), order)
    }

    @Test
    fun `empty registry yields an empty listing`() {
        assertTrue(ToolRegistryOrder.arrange(emptyList(), emptyMap<String, String>()) { it }.isEmpty())
    }
}
