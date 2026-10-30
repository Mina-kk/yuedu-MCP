package com.mina.legadostudio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentCheckOutcomesTest {
    @Test fun `records trimmed key and latest outcome`() {
        RecentCheckOutcomes.clear()
        RecentCheckOutcomes.record(" https://example.test/source ", true, 10L)
        assertEquals(RecentCheckOutcomes.Outcome(true, 10L), RecentCheckOutcomes.get("https://example.test/source"))
        RecentCheckOutcomes.record("https://example.test/source", false, 20L)
        assertEquals(RecentCheckOutcomes.Outcome(false, 20L), RecentCheckOutcomes.get(" https://example.test/source "))
        RecentCheckOutcomes.clear()
    }

    @Test fun `evicts oldest outcome above capacity`() {
        RecentCheckOutcomes.clear()
        repeat(501) { index -> RecentCheckOutcomes.record("source-$index", index % 2 == 0, index.toLong()) }
        assertNull(RecentCheckOutcomes.get("source-0"))
        assertEquals(RecentCheckOutcomes.Outcome(true, 500L), RecentCheckOutcomes.get("source-500"))
        RecentCheckOutcomes.clear()
    }
}
