package com.mina.legadostudio.runtime

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIdentityCompatibilityTest {
    @Test fun unchangedQidianSourceGetKeyReturnsFullSourceIdentity() {
        // Real XIU2 source calls source.getKey() before Cookie/browser handoff.
        // Fixed Team BookSource.kt:110-112 returns bookSourceUrl, not host/origin normalization.
        val anchor = "https://www.qidian.com#variant"
        val result = RhinoEvaluator(HttpFetcher(), Gson()).evaluateRaw(
            "source.getKey()", baseUrl = "https://m.qidian.com/book/1/",
            bindings = mapOf(
                RhinoEvaluator.BINDING_SOURCE_ANCHOR to anchor,
                "source" to linkedMapOf<String, String>(),
            ),
        )
        assertEquals(anchor, result.value.toString())
    }
}
