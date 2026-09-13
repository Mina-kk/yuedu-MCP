package com.mina.legadostudio.domain

import com.mina.legadostudio.mcp.McpConfigStore
import com.mina.legadostudio.mcp.configureStudioMcp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpApplicationContractTest {
    private fun server() = Server(
        serverInfo = Implementation("contract-test", "1"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )

    @Test fun healthIsAvailableWithoutMcpToken() = testApplication {
        application {
            configureStudioMcp(
                configProvider = { McpConfigStore.Config(1237, true, "secret") },
                allowedHosts = listOf("localhost"),
                allowedOrigins = listOf("http://localhost"),
            ) { server() }
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test fun initializeRequiresTokenAndSucceedsWithToken() = testApplication {
        application {
            configureStudioMcp(
                configProvider = { McpConfigStore.Config(1237, true, "secret") },
                allowedHosts = listOf("localhost"),
                allowedOrigins = listOf("http://localhost"),
            ) { server() }
        }
        val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
        val unauthorized = client.post("/mcp") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody(initialize)
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val authorized = client.post("/mcp") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header(HttpHeaders.Authorization, "Bearer secret")
            setBody(initialize)
        }
        assertTrue("status=${authorized.status}", authorized.status.value in 200..299)

        val hostile = client.post("/mcp") {
            header(HttpHeaders.Host, "example.test")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header(HttpHeaders.Authorization, "Bearer secret")
            setBody(initialize)
        }
        assertEquals(HttpStatusCode.Forbidden, hostile.status)
    }

    @Test fun legacyStudioTokenHeaderIsRejected() = testApplication {
        application {
            configureStudioMcp(
                configProvider = { McpConfigStore.Config(1237, true, "secret") },
                allowedHosts = listOf("localhost"),
                allowedOrigins = listOf("http://localhost"),
            ) { server() }
        }
        val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
        val legacy = client.post("/mcp") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header("X-Studio-Token", "secret")
            setBody(initialize)
        }
        assertEquals(HttpStatusCode.Unauthorized, legacy.status)
    }
}
