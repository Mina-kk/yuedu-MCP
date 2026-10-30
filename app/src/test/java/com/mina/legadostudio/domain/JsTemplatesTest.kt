package com.mina.legadostudio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsTemplatesTest {
    @Test fun findsKeyAndJsExpressionsWithoutRegex() {
        val hits = JsTemplates.findAll("prefix{{key}}mid{{java.time()}}suffix")
        assertEquals(2, hits.size)
        assertEquals("key", hits[0].expression)
        assertEquals("java.time()", hits[1].expression)
        assertEquals("{{key}}", "prefix{{key}}mid".substring(hits[0].range))
    }

    @Test fun emptyAndUnclosedAreIgnored() {
        assertTrue(JsTemplates.findAll("").isEmpty())
        assertTrue(JsTemplates.findAll("{{no-end").isEmpty())
        assertTrue(JsTemplates.findAll("plain").isEmpty())
    }
}
