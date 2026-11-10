package com.mina.legadostudio.mcp

import android.content.Context
import android.content.ContextWrapper
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.data.db.VerificationSessionEntity
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invokes the registered production browser_verify handler, not a duplicate parser. */
class BrowserVerifyArgumentsTest {
    @Test fun invalidUrlFailsBeforeImageFetchOrSessionCreation() = runBlocking {
        val handler = handler()
        for (arguments in listOf(
            """{}""", """{"url":null}""", """{"url":123}""", """{"url":{}}""",
            """{"url":""}""", """{"url":"   "}""", """{"url":"not-a-url"}""",
            """{"url":"http://127.0.0.1/"}""", """{"url":"https://user:secret@example.org/"}""",
            """{"url":"","imageUrl":"https://example.org/captcha.png"}""",
        )) {
            val result = handler(CallToolRequest(CallToolRequestParams(
                name = "browser_verify", arguments = Json.parseToJsonElement(arguments) as JsonObject,
            )))
            assertEquals(arguments, true, result.isError)
            val text = (result.content.single() as TextContent).text
            assertTrue("expected actionable URL validation, got $text", text.contains("url"))
            assertFalse("must validate URL before fetching an image", text.contains("验证码图拉取失败"))
            assertFalse(text.contains("secret"))
        }
    }

    @Test fun verificationEntityMcpJsonRetainsProtocolFieldNames() {
        val row = VerificationSessionEntity(
            id = "regression", jobId = "mcp", domain = "example.org", url = "https://example.org/",
            purpose = "test", status = "WAITING", finalUrl = "", createdAt = 1L, updatedAt = 2L,
            kind = "webview", answer = "", imageData = "",
        )
        val json = JsonParser.parseString(Gson().toJson(row)).asJsonObject
        assertEquals(setOf("id", "jobId", "domain", "url", "purpose", "status", "finalUrl", "createdAt", "updatedAt", "kind", "answer", "imageData"), json.keySet())
        assertEquals(row.url, json["url"].asString)
        assertEquals(row.domain, json["domain"].asString)
    }

    @Suppress("UNCHECKED_CAST")
    private fun handler(): suspend (CallToolRequest) -> CallToolResult {
        // No app services are initialized: any image/DAO access makes the test fail instead of
        // accidentally executing Android/network code. Only Context.applicationContext is needed.
        val context = allocate(TestContext::class.java)
        context.app = allocate(StudioApplication::class.java)
        val owner = StudioMcpServer(context)
        owner.registerVerificationTools(Server(
            Implementation("verification-arguments-test", "1"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        ))
        val field = StudioMcpServer::class.java.getDeclaredField("localHandlers").apply { isAccessible = true }
        return (field.get(owner) as Map<String, suspend (CallToolRequest) -> CallToolResult>).getValue("browser_verify")
    }

    private class TestContext : ContextWrapper(null) {
        lateinit var app: StudioApplication
        override fun getApplicationContext(): Context = app
    }

    private fun <T> allocate(type: Class<T>): T {
        val klass = Class.forName("sun.misc.Unsafe")
        val unsafe = klass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return type.cast(klass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }
}
