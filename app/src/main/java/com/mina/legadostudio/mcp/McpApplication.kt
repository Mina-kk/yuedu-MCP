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
        if (config.tokenRequired) {
            // MCP 规范（Streamable HTTP）：只认标准 Authorization: Bearer 头
            val authorization = context.request.header(HttpHeaders.Authorization).orEmpty()
            val bearer = if (authorization.startsWith("Bearer ", ignoreCase = true)) authorization.substring(7).trim() else ""
            if (!secureEquals(config.token, bearer)) {
                // 传输层拒绝此前完全不记日志，「连不上 MCP」无法排查；这里只记路径与原因，绝不记 token 本体
                logRejectThrottled("mcp auth reject", "unauthorized_bearer path=${context.request.path()}")
                context.respondText("MCP token 无效", status = HttpStatusCode.Unauthorized)
                finish()
            }
        }
        val sessionId = context.request.header("Mcp-Session-Id")
        if (sessionId != null && McpSessions.isReaped(sessionId)) {
            logRejectThrottled("mcp session expired", "session=${sessionId.take(8)}… path=${context.request.path()}")
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

/** 传输层拒绝日志节流：客户端带错 token/旧会话重试时可能每秒打多次，10 秒一条足够排障。 */
private val rejectLogLock = Any()
private val rejectLogLastAt = HashMap<String, Long>()
private fun logRejectThrottled(message: String, detail: String) {
    val now = System.currentTimeMillis()
    synchronized(rejectLogLock) {
        val last = rejectLogLastAt[message] ?: 0L
        if (now - last < 10_000L) return
        rejectLogLastAt[message] = now
    }
    StudioLog.add(message, "W", "mcp", detail)
}
