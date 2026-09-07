package com.mina.legadostudio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFilterUtilsTest {
    @Test
    fun testFormatDateKey() {
        assertEquals("未知日期", LogFilterUtils.formatDateKey(0))
        assertEquals("未知日期", LogFilterUtils.formatDateKey(-1))
        val formatted = LogFilterUtils.formatDateKey(1725600000000L)
        assertTrue(formatted.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")))
    }

    @Test
    fun testExtractRawHost() {
        assertEquals("www.69shu.cx", LogFilterUtils.extractRawHost("https://www.69shu.cx/modules/article/search.php?key=test"))
        assertEquals("biquge.tv", LogFilterUtils.extractRawHost("http://biquge.tv:8080/book/123"))
        assertEquals("hetushu.com", LogFilterUtils.extractRawHost("hetushu.com/txt/1"))
        assertEquals("127.0.0.1", LogFilterUtils.extractRawHost("http://127.0.0.1:58823/mcp"))
        assertEquals("", LogFilterUtils.extractRawHost(""))
        assertEquals("", LogFilterUtils.extractRawHost("   "))
    }

    @Test
    fun testIsLoopbackOrPrivate() {
        assertTrue(LogFilterUtils.isLoopbackOrPrivate("http://127.0.0.1:58823/mcp"))
        assertTrue(LogFilterUtils.isLoopbackOrPrivate("http://localhost:8080/test"))
        assertTrue(LogFilterUtils.isLoopbackOrPrivate("http://192.168.1.100:3000"))
        assertTrue(LogFilterUtils.isLoopbackOrPrivate("http://10.0.0.1/api"))
        assertTrue(LogFilterUtils.isLoopbackOrPrivate("http://172.16.0.5/api"))
        assertTrue(LogFilterUtils.isLoopbackOrPrivate("http://169.254.1.1"))
        assertTrue(LogFilterUtils.isLoopbackOrPrivate("127.0.0.1"))
        assertTrue(LogFilterUtils.isLoopbackOrPrivate("localhost"))
        
        assertFalse(LogFilterUtils.isLoopbackOrPrivate("https://www.69shu.cx/book/1.html"))
        assertFalse(LogFilterUtils.isLoopbackOrPrivate("http://api.biquge.com/search"))
        assertFalse(LogFilterUtils.isLoopbackOrPrivate("69shu.cx"))
    }

    @Test
    fun testExtractPrimaryDomain() {
        assertEquals("69shu.cx", LogFilterUtils.extractPrimaryDomain("https://www.69shu.cx/book/1.html"))
        assertEquals("69shu.cx", LogFilterUtils.extractPrimaryDomain("https://img.cdn.69shu.cx/cover.jpg"))
        assertEquals("biquge.com", LogFilterUtils.extractPrimaryDomain("http://m.biquge.com"))
        assertNull(LogFilterUtils.extractPrimaryDomain("http://127.0.0.1:58823/mcp"))
    }

    @Test
    fun testMatchesDomain() {
        assertTrue(LogFilterUtils.matchesDomain("https://www.69shu.cx/book/1.html", "69shu.cx"))
        assertTrue(LogFilterUtils.matchesDomain("https://img.69shu.cx/a.jpg", "69shu.cx"))
        assertTrue(LogFilterUtils.matchesDomain("https://69shu.cx/", "69shu.cx"))
        assertFalse(LogFilterUtils.matchesDomain("https://not69shu.cx/", "69shu.cx"))
        assertFalse(LogFilterUtils.matchesDomain("https://127.0.0.1/", "69shu.cx"))
    }
}
