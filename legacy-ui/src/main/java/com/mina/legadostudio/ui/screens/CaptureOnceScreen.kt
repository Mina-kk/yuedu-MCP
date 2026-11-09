package com.mina.legadostudio.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.ui.theme.GlassCard
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.LocalStudioFullscreen
import com.mina.legadostudio.ui.theme.LocalStudioHaze
import com.mina.legadostudio.ui.theme.StudioSpacing
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioChipBorder
import com.mina.legadostudio.ui.theme.studioChipColors
import com.mina.legadostudio.ui.theme.studioTopInset
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** HTTP 表单里一行的「请求头」草稿：用户按行填 name: value。 */
internal data class CaptureHeaderDraft(val name: String, val value: String)

/** 逐次抓包的一次表单结果：成功/失败统一持有 logIds，UI 侧列表与 MCP 返回一致。 */
internal data class CaptureUiResult(
    val summary: String,
    val error: String?,
    val logs: List<com.mina.legadostudio.network.CaptureOnce.LogEntry>,
    val viaWebView: Boolean,
    val contextId: String,
    /** 本批 contextId 落库总数（可能 > logs.size，超上限时提示截断）。 */
    val logCount: Int,
    /** 记录开关当前状态（抓包入口已拦截关闭；此处用于结果区显示）。 */
    val recordingDisabled: Boolean,
)

/**
 * 逐次抓包独立页（二级路由 capture_once，从「日志→抓包」入口进入）：
 * 手动发一次公网 HTTP 请求，本次事务按独立 cap:contextId 汇总（含重定向逐跳）。
 * 全屏单层 LazyColumn：表单（可折叠）/执行状态/本次结果预览从上到下，无内嵌滚动区。
 */
@Composable
fun CaptureOnceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as StudioApplication
    val haze = LocalStudioHaze.current
    val dao = app.database.dao()
    val scope = rememberCoroutineScope()

    // 表单与执行状态（本页自有，不回 LogsScreen）
    var captureExpanded by remember { mutableStateOf(true) }
    var captureUrl by remember { mutableStateOf("") }
    var captureMethod by remember { mutableStateOf("GET") }
    var captureHeaders by remember { mutableStateOf(listOf<CaptureHeaderDraft>()) }
    var captureBody by remember { mutableStateOf("") }
    var captureBusy by remember { mutableStateOf(false) }
    var captureResult by remember { mutableStateOf<CaptureUiResult?>(null) }
    var recording by remember { mutableStateOf(app.httpLogs.enabled) }

    // 覆盖层（会话详情/单条详情）打开时通知全局隐藏底部胶囊（与 LogsScreen 同一口径）。
    var viewingHttpId by remember { mutableStateOf<Long?>(null) }
    var viewingFullLog by remember { mutableStateOf<HttpLogEntity?>(null) }
    var viewingCaptureContextId by remember { mutableStateOf<String?>(null) }
    val fullscreen = LocalStudioFullscreen.current
    androidx.compose.runtime.DisposableEffect(viewingHttpId != null, viewingCaptureContextId != null) {
        fullscreen?.value = viewingHttpId != null || viewingCaptureContextId != null
        onDispose { fullscreen?.value = false }
    }
    androidx.compose.runtime.LaunchedEffect(viewingHttpId) {
        val id = viewingHttpId
        viewingFullLog = if (id != null) withContext(Dispatchers.IO) { dao.httpLog(id) } else null
    }

    // 系统返回键：覆盖层优先（单条详情加载期间 viewingFullLog 仍为 null、其内部 BackHandler
    // 尚未挂上，此时由外层直接关掉最顶层；会话详情次之），无覆盖层才退出页面——
    // 顺序固定为 单条详情 > 会话详情 > onBack，不依赖各覆盖层 handler 的注册时序。
    BackHandler {
        when {
            viewingHttpId != null -> viewingHttpId = null
            viewingCaptureContextId != null -> viewingCaptureContextId = null
            else -> onBack()
        }
    }

    // 抓包完成后按 cap:contextId 流式观察：拦截器/异步落库的日志写库后自动补齐到结果列表。
    val captureContextId = captureResult?.contextId?.takeIf { it.isNotBlank() }
    val captureLiveLogs by produceState(initialValue = emptyList<com.mina.legadostudio.network.CaptureOnce.LogEntry>(), captureContextId) {
        val ctx = captureContextId
        if (ctx == null) { value = emptyList(); return@produceState }
        dao.observeHttpLogsByContextId(ctx).collect { list ->
            // 结果区预览最多 200 条：完整会话走「查看本次会话详情」的 CaptureSessionDetail 分页，可看全
            value = list.take(200).map {
                com.mina.legadostudio.network.CaptureOnce.LogEntry(
                    it.id, it.method, it.url, it.finalUrl, it.statusCode, it.durationMs,
                    it.error.take(300), it.originKind,
                )
            }
        }
    }

    fun runCapture() {
        if (captureBusy) return
        captureBusy = true
        captureResult = null
        val headers = captureHeaders
            .mapNotNull { d -> d.name.trim().takeIf { it.isNotEmpty() }?.let { n -> n to d.value } }
            .toMap()
        val url = captureUrl.trim()
        val method = captureMethod
        val body = captureBody.takeIf { it.isNotBlank() }
        scope.launch {
            val outcome = runCatching {
                app.captureOnce.run(
                    com.mina.legadostudio.network.CaptureOnce.Params(
                        url = url, method = method, headers = headers, body = body,
                    )
                )
            }
            captureResult = outcome.fold(
                onSuccess = { r ->
                    CaptureUiResult(
                        summary = "${r.code} · ${r.elapsedMs}ms · ${r.finalUrl}",
                        error = null, logs = r.logs, viaWebView = r.viaWebView,
                        contextId = r.contextId, logCount = r.logCount,
                        recordingDisabled = r.recordingDisabled,
                    )
                },
                onFailure = { e ->
                    val partial = (e as? com.mina.legadostudio.network.CaptureOnce.CaptureFailedException)?.partial
                    CaptureUiResult(
                        summary = partial?.hint ?: "请求失败",
                        error = e.message?.take(300) ?: e.javaClass.simpleName,
                        logs = partial?.logs.orEmpty(),
                        viaWebView = partial?.viaWebView == true,
                        contextId = partial?.contextId.orEmpty(),
                        logCount = partial?.logCount ?: 0,
                        recordingDisabled = partial?.recordingDisabled == true,
                    )
                },
            )
            captureBusy = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier),
            contentPadding = PaddingValues(
                start = StudioSpacing.screen,
                end = StudioSpacing.screen,
                top = 64.dp + studioTopInset(),
                bottom = StudioSpacing.screenBottomBase + studioBottomInset(),
            ),
            verticalArrangement = Arrangement.spacedBy(StudioSpacing.medium),
        ) {
            // 记录开关短行（与抓包入口同一开关；关闭时抓包会被引擎拒绝并在结果区提示）
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.card),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "记录 HTTP 事务",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Switch(checked = recording, onCheckedChange = { recording = it; app.httpLogs.enabled = it })
                }
            }

            // 表单卡（可折叠；默认展开，发完可收起腾位置看结果）
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(StudioSpacing.card), verticalArrangement = Arrangement.spacedBy(StudioSpacing.small)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { captureExpanded = !captureExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("请求表单", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                Text(
                                    "仅公网 HTTP/HTTPS；私网/回环/DNS 重绑定会被拒绝（SSRF 守卫）。需先开启 HTTP 记录",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(if (captureExpanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }

                        if (captureExpanded) {
                            OutlinedTextField(
                                value = captureUrl,
                                onValueChange = { captureUrl = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("请求 URL（http/https）") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall,
                            )

                            // 方法选择
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("GET", "POST", "HEAD").forEach { m ->
                                    FilterChip(
                                        selected = captureMethod == m,
                                        onClick = { captureMethod = m },
                                        label = { Text(m) },
                                        colors = studioChipColors(),
                                        border = studioChipBorder(captureMethod == m),
                                        leadingIcon = if (captureMethod == m) { { Icon(Icons.Outlined.Check, contentDescription = null) } } else null,
                                    )
                                }
                            }

                            // 可选请求头（按行 name: value）
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                captureHeaders.forEachIndexed { index, draft ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedTextField(
                                            value = draft.name,
                                            onValueChange = { v -> captureHeaders = captureHeaders.toMutableList().also { it[index] = draft.copy(name = v) } },
                                            modifier = Modifier.weight(2f),
                                            label = { Text("头名") },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall,
                                        )
                                        OutlinedTextField(
                                            value = draft.value,
                                            onValueChange = { v -> captureHeaders = captureHeaders.toMutableList().also { it[index] = draft.copy(value = v) } },
                                            modifier = Modifier.weight(3f),
                                            label = { Text("值") },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall,
                                        )
                                        TextButton(onClick = { captureHeaders = captureHeaders.toMutableList().also { it.removeAt(index) } }) {
                                            Text("删", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                TextButton(onClick = { captureHeaders = captureHeaders + CaptureHeaderDraft("", "") }) {
                                    Text("+ 添加请求头", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            if (captureMethod == "POST") {
                                OutlinedTextField(
                                    value = captureBody,
                                    onValueChange = { captureBody = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("POST 正文") },
                                    minLines = 2,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                )
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = { runCapture() }, enabled = !captureBusy && captureUrl.isNotBlank()) {
                                    if (captureBusy) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text("发送")
                                    }
                                }
                                if (captureBusy) {
                                    Text("请求中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // 本次结果：流式观察补齐异步落库行；预览上限 200 条，完整走会话详情分页
            captureResult?.let { r ->
                val shownLogs = if (captureLiveLogs.isNotEmpty()) captureLiveLogs else r.logs
                val shownCount = if (captureLiveLogs.isNotEmpty()) captureLiveLogs.size else r.logCount
                item(key = "result_summary") {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(StudioSpacing.card), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(r.summary, style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            if (r.error != null) {
                                Text(r.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (r.viaWebView) {
                                Text(
                                    "本次经 WebView 通道取证（站点处于 WebView 模式）；无头一次性抓包也可走 MCP webview_capture",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (r.recordingDisabled) {
                                Text(
                                    "HTTP 事务记录当前已关闭：本次不落库。请开启上方「记录 HTTP 事务」后再抓包",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (shownCount > shownLogs.size) {
                                Text(
                                    "共 $shownCount 条，此处仅预览前 ${shownLogs.size} 条；全部逐跳点下方「查看本次会话详情」分页加载",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (r.contextId.isNotBlank()) {
                                TextButton(onClick = { viewingCaptureContextId = r.contextId }) {
                                    Text("查看本次会话详情", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                if (shownLogs.isEmpty()) {
                    item(key = "result_empty") {
                        EmptyHint("本次未产生日志（请求未发出或被拦截）")
                    }
                } else {
                    items(shownLogs, key = { it.id }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .clickable { viewingHttpId = entry.id }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${entry.method} ${entry.url}",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${entry.statusCode} · ${entry.durationMs}ms" +
                                        entry.originKind?.let { " · $it" }.orEmpty() +
                                        entry.error.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("详情", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                EmptyHint("发送后本次事务按 cap:contextId 归档；「抓包」入口页的会话历史可回看全部历史抓包（含浏览器抓包）。抓包记录不混入 HTTP 页签。")
            }
        }

        GlassTopBar("逐次抓包", onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))

        // 抓包会话详情（二级覆盖层）；单条详情须绘制在其上
        viewingCaptureContextId?.let { ctx ->
            CaptureSessionDetail(
                dao = dao,
                contextId = ctx,
                onBack = { viewingCaptureContextId = null },
                onOpenLog = { viewingHttpId = it },
            )
        }
        if (viewingHttpId != null) {
            viewingFullLog?.let { fullLog ->
                HttpLogDetail(log = fullLog, onBack = { viewingHttpId = null })
            }
        }
    }
}
