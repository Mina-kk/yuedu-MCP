package com.mina.legadostudio.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import java.security.MessageDigest

fun Application.configureStudioMcp(
    configProvider: () -> McpConfigStore.Config,
    allowedHosts: List<String>,
    allowedOrigins: List<String>,
    serverFactory: RoutingContext.() -> Server,
) {
    routing {
        get("/health") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText("{\"status\":\"ok\",\"mcpPath\":\"${McpAccess.PATH}\"}", contentType = io.ktor.http.ContentType.Application.Json)
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        context.response.header(HttpHeaders.CacheControl, "no-store")
        if (context.request.path() == "/health") return@intercept
        val config = configProvider()
        if (config.tokenRequired && !secureEquals(config.token, context.request.header(McpAccess.TOKEN_HEADER).orEmpty())) {
            context.respondText("MCP token 无效", status = HttpStatusCode.Unauthorized)
            finish()
        }
        val sessionId = context.request.header("Mcp-Session-Id")
        if (sessionId != null && McpSessions.isReaped(sessionId)) {
            context.respondText("MCP session expired", status = HttpStatusCode.NotFound)
            finish()
        }
    }
    mcpStreamableHttp(
        path = McpAccess.PATH,
        allowedHosts = allowedHosts,
        allowedOrigins = allowedOrigins,
        block = serverFactory,
    )
}

private fun secureEquals(expected: String, actual: String): Boolean =
    MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
