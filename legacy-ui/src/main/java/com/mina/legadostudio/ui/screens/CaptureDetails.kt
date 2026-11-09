package com.mina.legadostudio.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.HttpLogSummary
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.ui.theme.GlassCard
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.StudioSpacing
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioTopInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** 日志界面统一的时间格式（抓包页系与 LogsScreen 共用）。 */
internal fun formatLogTime(value: Long): String = DateFormat.getDateTimeInstance().format(Date(value))

@Composable
internal fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
}

/** 会话摘要行/详情行的来源类型标签：只认真实 originKind，不可靠则标「事务」不伪造。 */
internal fun captureEntryRole(originKind: String?): String = when (originKind) {
    "capture_hop" -> "中间跳"
    "capture_once" -> "最终落点"
    "webview_capture" -> "会话汇总"
    "webview_capture_resource" -> "供给/观察"
    "webview_capture_error" -> "错误"
    "webview_capture_blocked" -> "阻断"
    null, "" -> "事务"
    else -> "事务"
}

/**
 * 会话来源标签：capture_once/capture_hop → 逐次抓包；webview_capture* → 浏览器抓包；
 * 其余（无法可靠识别，如老数据缺 originKind）→ 不伪造，显示「抓包」。
 */
internal fun captureSessionKindLabel(originKinds: Collection<String?>): String = when {
    originKinds.any { it == "capture_once" || it == "capture_hop" } -> "逐次抓包"
    originKinds.any { it?.startsWith("webview_capture") == true } -> "浏览器抓包"
    else -> "抓包"
}

/** 请求头/响应头落库是 JSON 对象（{"Name": "v"}）；详情展示转回 `Name: v` 行格式便于阅读，解析失败原样返回。 */
internal fun prettyPrintHeaderBlock(raw: String): String {
    val t = raw.trim()
    if (!t.startsWith("{") || !t.endsWith("}")) return raw
    return try {
        val map = com.google.gson.Gson().fromJson(t, Map::class.java) as? Map<*, *> ?: return raw
        map.entries.joinToString("\n") { (k, v) -> "$k: $v" }
    } catch (_: Exception) {
        raw
    }
}

/**
 * HTTP 事务详情页：独占滚动容器。
 * 顶部先给「状态 + 方法 + 两行省略 URL + 时间/耗时」概要（长 URL 不霸占首屏）；
 * 各块均为可复制/可选文本；换行开关只影响正文块（关时该块横向滚动不换行）。
 */
@Composable
internal fun HttpLogDetail(log: HttpLogEntity, onBack: () -> Unit) {
    // 系统返回键/手势只关闭详情层，回到日志列表，不触发外层“回首页”逻辑
    BackHandler(onBack = onBack)
    var wrapContent by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp + studioTopInset(), bottom = StudioSpacing.card + studioBottomInset())
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = StudioSpacing.card),
                verticalArrangement = Arrangement.spacedBy(StudioSpacing.medium),
            ) {
                // 概要：状态码先行，URL 两行省略不挤占首屏；完整 URL 在下方「最终 URL」块内可整段选中复制
                Text(
                    "${log.statusCode} · ${log.method}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    log.url,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "时间：${formatLogTime(log.createdAt)} · 耗时：${log.durationMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (log.error.isNotBlank()) Text("错误：${log.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (!log.sourceAnchor.isNullOrBlank() || !log.contextId.isNullOrBlank() || !log.originKind.isNullOrBlank()) {
                    DetailBlock("来源", buildString {
                        log.sourceAnchor?.takeIf { it.isNotBlank() }?.let { append("锚点：$it") }
                        log.contextId?.takeIf { it.isNotBlank() }?.let { if (isNotEmpty()) append("\n"); append("contextId：$it") }
                        log.originKind?.takeIf { it.isNotBlank() }?.let { if (isNotEmpty()) append("\n"); append("链路：$it") }
                    }, wrap = wrapContent)
                }
                DetailBlock("最终 URL", log.finalUrl.ifBlank { log.url }, wrap = wrapContent)
                if (log.originKind == "capture_hop") {
                    // 中间跳日志：url=本跳请求、finalUrl=下一跳目标、响应头含 Location，redirectChain 恒为 []
                    Text(
                        "重定向中间跳：本条是链上一跳（HTTP ${log.statusCode}），「最终 URL」即跳转目标 Location；链上其余跳见同 cap:contextId 会话的其他行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (log.originKind?.startsWith("webview_capture") == true) {
                    Text(
                        "浏览器抓包行：本行是 OkHttp 供给/观察证据（OBSERVED_ONLY 或 BLOCKED 见错误栏），不是 WebView 原生网络栈抓包",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (log.redirectChain != "[]") {
                    DetailBlock("重定向链", log.redirectChain, wrap = wrapContent)
                    if (log.contextId?.startsWith("cap:") == true) {
                        Text(
                            "redirectChain 记录各跳目标 URL 文本；逐跳独立证据（各跳请求/响应头）见同会话 originKind=capture_hop 行",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "旧版记录：中间跳未单独落库，本字段是仅有的跳点信息",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DetailBlock("请求头", prettyPrintHeaderBlock(log.requestHeaders), wrap = wrapContent)
                if (log.requestBody.isNotBlank()) {
                    DetailBlock("请求体", log.requestBody, wrap = wrapContent)
                    if (log.requestBody.contains(com.mina.legadostudio.network.HttpLogCaps.TRUNCATED_MARK_PREFIX)) {
                        Text("请求体已截断；此处仅为脱敏片段，并非完整内容", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                DetailBlock("响应头", prettyPrintHeaderBlock(log.responseHeaders), wrap = wrapContent)
                if (log.responseBody.isNotBlank()) {
                    DetailBlock("响应体", log.responseBody, wrap = wrapContent)
                    if (log.responseBody.contains(com.mina.legadostudio.network.HttpLogCaps.TRUNCATED_MARK_PREFIX)) {
                        Text("响应体已截断；此处仅为脱敏片段，并非完整内容", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    "敏感头已脱敏（Authorization/Cookie/Set-Cookie/api-key 显示为 ***）；URL/query 可能带令牌，分享前自行检查",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GlassTopBar(
            "HTTP 详情",
            onBack = onBack,
            actions = {
                TextButton(onClick = { wrapContent = !wrapContent }) {
                    Text(if (wrapContent) "换行" else "单行")
                }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun DetailBlock(label: String, value: String, wrap: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        if (wrap) {
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                softWrap = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
            }
        }
    }
}

/** 会话详情分页快照：entries 按 ASC 追加，hopIds/finalEntry/legacy 每次重算。 */
internal data class CaptureSessionPage(
    val contextId: String,
    val entries: List<HttpLogSummary> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    val loadError: String? = null,
) {
    val hopIds: List<Long> get() = entries.filter { it.originKind == "capture_hop" }.map { it.id }
    /** 仅对逐次抓包会话有意义：最后一条非 hop 行即最终落点；浏览器会话不以此冒充最终状态。 */
    val finalEntry: HttpLogSummary? get() = entries.lastOrNull { it.originKind != "capture_hop" }
    /** 是否逐次抓包会话（含 capture_once/capture_hop 行）；用于决定「最终落点」标签是否可信。 */
    val isOnceCapture: Boolean get() = entries.any { it.originKind == "capture_once" || it.originKind == "capture_hop" }
    /** 旧版只有一条 final+redirectChain（无 capture_hop 行）时提示。 */
    val legacy: Boolean get() = entries.isNotEmpty() && hopIds.isEmpty() &&
        entries.any { it.redirectChain != "[]" && it.redirectChain.isNotBlank() }
}

private const val SESSION_PAGE_SIZE = 50

/** 拉一页会话事务（ASC 追加），total/exhausted 每次刷新。 */
internal suspend fun loadSessionPage(dao: StudioDao, current: CaptureSessionPage): CaptureSessionPage {
    return try {
        val total = dao.countHttpLogsByContextId(current.contextId)
        val page = dao.httpLogSummariesByContextIdPage(current.contextId, SESSION_PAGE_SIZE, current.entries.size)
        val merged = current.entries + page.filter { p -> current.entries.none { it.id == p.id } }
        current.copy(entries = merged, total = total, loading = false, exhausted = page.size < SESSION_PAGE_SIZE)
    } catch (e: Exception) {
        current.copy(loading = false, loadError = e.message?.take(160) ?: e.javaClass.simpleName)
    }
}

/**
 * 抓包会话详情：按 cap:contextId 从 Room 逐页取该批全部事务（ASC），
 * 逐次抓包区分 capture_hop 中间跳与最终落点；浏览器抓包行按真实 originKind 标注
 * （供给/观察/阻断/汇总），不用「最终落点」冒充。每行可进单条 HttpLogDetail 看头/体/错误。
 */
@Composable
internal fun CaptureSessionDetail(
    dao: StudioDao,
    contextId: String,
    onBack: () -> Unit,
    onOpenLog: (Long) -> Unit,
    /** 非空时顶栏露「删除」入口；点击由外层弹二次确认并真正删整会话（本组件不直接删库）。 */
    onDeleteSession: ((String) -> Unit)? = null,
    /** 打开单条详情行时回调（logId）：外层据此登记「该详情属于本会话」，删会话时连它一起关。 */
    onLogOpened: ((Long) -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(CaptureSessionPage(contextId)) }
    var sessionAnchor by remember { mutableStateOf("") }
    var sessionFirstAt by remember { mutableStateOf<Long?>(null) }
    var sessionLastAt by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(contextId) {
        // 首屏即拉第一页（ASC）；锚点/时间/来源标签从首批投影里取
        page = page.copy(loading = true)
        page = withContext(Dispatchers.IO) { loadSessionPage(dao, page) }
        sessionFirstAt = page.entries.firstOrNull()?.createdAt
        sessionLastAt = page.entries.lastOrNull()?.createdAt
        sessionAnchor = page.entries.firstOrNull { !it.sourceAnchor.isNullOrBlank() }?.sourceAnchor.orEmpty()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 64.dp + studioTopInset(), bottom = StudioSpacing.card + studioBottomInset()),
        ) {
            // 紧凑概要头（固定区，不滚动）：会话类型 + ID + 数量/时间一行说清
            Column(
                Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.card, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SelectionContainer {
                    Text(
                        "${captureSessionKindLabel(page.entries.map { it.originKind })} · $contextId",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    buildString {
                        append("共 ${page.total} 条事务")
                        // hopIds 只是已加载窗口内的计数，exhausted 之前不得当总数报
                        if (page.hopIds.isNotEmpty()) {
                            append(if (page.exhausted) " · ${page.hopIds.size} 个重定向中间跳" else " · 已见 ${page.hopIds.size} 个中间跳（未加载完）")
                        }
                        // 「最终」只用于真正逐次抓包的终止行；浏览器会话不冒称最终状态
                        if (page.isOnceCapture) page.finalEntry?.let { append(" · 最终 ${it.statusCode}") }
                        if (sessionAnchor.isNotBlank()) append(" · 锚点 $sessionAnchor")
                        if (sessionFirstAt != null && sessionLastAt != null) {
                            append(" · ${formatLogTime(sessionFirstAt!!)} → ${formatLogTime(sessionLastAt!!)}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (page.legacy) {
                    Text(
                        "本会话为旧版记录格式：中间跳未单独落库，各跳 URL 仅在最终落点的 redirectChain 文本中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                page.loadError?.let {
                    Text("加载失败：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = StudioSpacing.screen, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(StudioSpacing.small),
            ) {
                if (page.entries.isEmpty() && !page.loading) {
                    item { EmptyHint("该抓包会话暂无日志（可能记录被清理）") }
                }
                itemsIndexed(page.entries, key = { _, e -> e.id }) { _, entry ->
                    val isHop = entry.originKind == "capture_hop"
                    val roleLabel = when {
                        isHop -> "中间跳 ${entry.statusCode} (capture_hop)"
                        page.isOnceCapture && page.finalEntry?.id == entry.id -> "最终落点"
                        else -> captureEntryRole(entry.originKind)
                    }
                    GlassCard {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onLogOpened?.invoke(entry.id); onOpenLog(entry.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    roleLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isHop) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                )
                                Text(formatLogTime(entry.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${entry.method} ${entry.url}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${entry.statusCode} · ${entry.durationMs}ms" +
                                    (if (isHop && entry.finalUrl.isNotBlank()) " → ${entry.finalUrl.take(80)}" else "") +
                                    entry.error.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (!page.exhausted) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                            TextButton(
                                onClick = {
                                    if (page.loading) return@TextButton
                                    page = page.copy(loading = true, loadError = null)
                                    scope.launch {
                                        page = withContext(Dispatchers.IO) { loadSessionPage(dao, page) }
                                        sessionLastAt = page.entries.lastOrNull()?.createdAt
                                        if (sessionAnchor.isBlank()) {
                                            sessionAnchor = page.entries.firstOrNull { !it.sourceAnchor.isNullOrBlank() }?.sourceAnchor.orEmpty()
                                        }
                                    }
                                },
                                enabled = !page.loading,
                            ) {
                                if (page.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                else Text("加载更多（剩 ${page.total - page.entries.size} 条）", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        GlassTopBar(
            "抓包会话",
            onBack = onBack,
            actions = {
                TextButton(onClick = { exportCaptureSession(context, contextId, scope) }) { Text("导出") }
                if (onDeleteSession != null) {
                    TextButton(onClick = { onDeleteSession(contextId) }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
