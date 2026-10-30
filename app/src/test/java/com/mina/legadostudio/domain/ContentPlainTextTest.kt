package com.mina.legadostudio.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentPlainTextTest {
    @Test fun formatKeepImgStripsTagsKeepsImgAndTrimsLines() {
        val html = "<p>  hello  </p><div>world</div><img src='a.png' alt='x'>"
        val out = ContentPlainText.formatKeepImg(html)
        assertTrue(out.contains("hello"))
        assertTrue(out.contains("world"))
        assertTrue(out.contains("<img"))
        assertFalse(out.contains("<p>"))
        assertFalse(out.contains("<div>"))
        assertFalse(out.lines().any { it.startsWith(" ") || it.endsWith(" ") })
    }
}
