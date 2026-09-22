package com.mina.legadostudio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogDeletePlanTest {

    @Test
    fun resolvesOnlyNumericVisibleIdsInViewOrder() {
        val visible = listOf("11", "12", "13")
        val selected = setOf("13", "11")
        assertEquals(listOf(11L, 13L), LogDeletePlan.resolveLogIds(selected, visible))
    }

    @Test
    fun dropsNonNumericAndStaleSelections() {
        val visible = listOf("7", "8")
        val selected = setOf("7", "crash-1.txt", "999", "12abc", "")
        assertEquals(listOf(7L), LogDeletePlan.resolveLogIds(selected, visible))
    }

    @Test
    fun emptySelectionYieldsEmptyPlan() {
        assertTrue(LogDeletePlan.resolveLogIds(emptySet(), listOf("1", "2")).isEmpty())
        assertTrue(LogDeletePlan.resolveNames(emptySet(), listOf("crash-1.txt")).isEmpty())
    }

    @Test
    fun namesChannelKeepsOnlyVisibleNames() {
        val visible = listOf("crash-1.txt", "crash-2.txt")
        val selected = setOf("crash-2.txt", "../../studio.db", "crash-9.txt")
        assertEquals(listOf("crash-2.txt"), LogDeletePlan.resolveNames(selected, visible))
    }

    @Test
    fun chunksStayUnderSqliteVariableLimit() {
        val ids = (1L..2500L).toList()
        val batches = LogDeletePlan.chunk(ids)
        assertEquals(3, batches.size)
        assertEquals(listOf(900, 900, 700), batches.map { it.size })
        assertTrue(batches.all { it.size <= LogDeletePlan.MAX_BATCH })
        assertEquals(ids, batches.flatten())
    }

    @Test
    fun chunkOfEmptyProducesNoBatch() {
        assertTrue(LogDeletePlan.chunk(emptyList()).isEmpty())
        assertTrue(LogDeletePlan.chunkNames(emptyList()).isEmpty())
    }

    @Test
    fun maxBatchIsBelowSqliteVariableCeiling() {
        assertTrue("MAX_BATCH 必须小于 SQLite 999 变量上限", LogDeletePlan.MAX_BATCH < 999)
    }
}
