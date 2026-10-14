package com.mina.legadostudio.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 本请求内经 serverFactory 新建的 Server 实例（SDK 对无会话请求先建实例再校验）。
 * 用 CopyOnWriteArrayList 承受「拦截器与路由 handler 跨 dispatcher」的读写；一次请求最多一个。
 */
private val requestServersKey = AttributeKey<CopyOnWriteArrayList<Server>>("studio.mcp.requestServers")

/**
 * 本请求 DELETE 命中的已登记 owner（仅鉴权通过的 /mcp DELETE 在进 SDK 前捕获）。
 * 单次消费：finally 只取一次，一次请求不会重复触发 DELETE 释放。
 */
private val deleteOwnerKey = AttributeKey<Server>("studio.mcp.deleteOwner")

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
        // 仅鉴权通过的 /mcp DELETE 预捕获 owner：在 SDK 操作前记下「该 wire sid 的已登记 Server」，
        // 供 finally 在 SDK 回 200 且确认不再持 wire 后释放。不预读正文、不改 SDK 任何行为；
        // 未登记/无 sid 的 DELETE 不留属性，走下方 orphan 清理（仅当本请求真建了实例）。
        val isMcpDelete = context.request.path() == McpAccess.PATH && context.request.httpMethod == HttpMethod.Delete
        if (isMcpDelete && sessionId != null) {
            McpSessions.registeredOwnerOf(sessionId)?.let { owner ->
                context.attributes.put(deleteOwnerKey, owner)
            }
        }
        try {
            proceed()
        } finally {
            // 请求收尾一（DELETE 释放）：仅当本请求确为 /mcp DELETE、SDK 回了 200、且预捕获的
            // owner 仍登记且确认不再持有该 wire 会话时才摘除并 best-effort 关闭一次。
            // 401/404/坏协议头或 SDK 非 200 路径一律不动 owner——不按错误码泛化、不覆写 SDK 回调；
            // 消费一次（takeOrNull）保证多协程/重试下也不会重复触发。
            val deleteOwner = context.attributes.takeOrNull(deleteOwnerKey)
            if (deleteOwner != null && sessionId != null &&
                context.response.status()?.value == 200
            ) {
                withContext(NonCancellable) { McpSessions.releaseDeleted(sessionId, deleteOwner) }
            }

            // 请求收尾二（orphan）：仅清理「本请求 factory 新建、确认无 wire 会话、响应未签发
            // 会话头」的实例——校验失败请求被 SDK 先建 Server 再拒绝，旧行为靠 reaper 兜底而临时
            // 留存。已交接会话（wire id 或响应会话头存在）保留，避免客户端拿到会话后立即 DELETE
            // 被二次关闭。takeOrNull 只消费一次防止重复清理；NonCancellable 让请求取消后 close
            // 仍尝试。与 DELETE 释放共用 ServerEntry 门闩：已被上一步摘除的实例这里 claim 不到。
            context.attributes.takeOrNull(requestServersKey)?.forEach { server ->
                val wired = McpSessions.strictWireIdsOf(server)
                val handedOff = (wired == null || wired.isNotEmpty()) ||
                    context.response.headers["Mcp-Session-Id"] != null
                if (!handedOff) withContext(NonCancellable) { McpSessions.releaseOrphan(server) }
            }
        }
    }
    mcpStreamableHttp(
        path = McpAccess.PATH,
        allowedHosts = allowedHosts,
        allowedOrigins = allowedOrigins,
        block = {
            val created = serverFactory()
            call.attributes.computeIfAbsent(requestServersKey) { CopyOnWriteArrayList() }.add(created)
            created
        },
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
