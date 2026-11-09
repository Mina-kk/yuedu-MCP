package com.mina.legadostudio.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentPlainTextTest {
    @Test fun followsFixedTeamWhitespaceAndEscapedLineBreaks() {
        // Fixed Team HtmlFormatter.kt:24-50 followed by BookContent's per-line trim.
        // Java regex \\s excludes the inserted U+3000 indent: a final block leaves a final line.
        org.junit.Assert.assertEquals("第一段\n第二段\n", ContentPlainText.formatKeepImg("<p>第一段</p>\n\n<p>第二段</p>"))
        org.junit.Assert.assertEquals("第一段\n第二段", ContentPlainText.formatKeepImg("第一段\\n\\n第二段"))
        org.junit.Assert.assertEquals("A\nB\n", ContentPlainText.formatKeepImg(" \n<div>A&nbsp;&nbsp;</div><!-- hidden --><div>B</div>\n"))
    }
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
