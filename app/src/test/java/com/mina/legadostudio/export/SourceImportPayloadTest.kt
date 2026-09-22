package com.mina.legadostudio.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceImportPayloadTest {

    @Test fun wrapsSingleObjectIntoArray() {
        val out = SourceImportPayload.arrayJson("{\"bookSourceName\":\"A\"}")
        assertTrue(out.startsWith("["))
        assertTrue(out.trimEnd().endsWith("]"))
        assertTrue(out.contains("\"bookSourceName\":\"A\""))
    }

    @Test fun keepsExistingArrayAsIs() {
        val out = SourceImportPayload.arrayJson("[{\"bookSourceName\":\"A\"},{\"bookSourceName\":\"B\"}]")
        assertTrue(out.startsWith("["))
        assertTrue(out.contains("\"bookSourceName\":\"B\""))
    }

    @Test fun rejectsInvalidJson() {
        val error = runCatching { SourceImportPayload.arrayJson("not-json") }.exceptionOrNull()
        assertTrue(error != null && error.message?.contains("书源 JSON 无效") == true)
    }
}
