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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.data.db.CaptureSessionSummary
import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.domain.CaptureSessionKinds
import com.mina.legadostudio.domain.LogFilterUtils
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.LocalStudioFullscreen
import com.mina.legadostudio.ui.theme.LocalStudioHaze
import com.mina.legadostudio.ui.theme.StudioSpacing
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioTopInset
import com.mina.legadostudio.verification.WebViewCapture
import dev.chrisbanes.haze.hazeSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 抓包会话历史：不可变快照，分页拉取时把会话锚点/日期/来源标签一并取出用于本地筛选展示。 */
internal data class CaptureHistoryState(
    val sessions: List<CaptureSessionSummary> = emptyList(),
    val anchorsByContext: Map<String, String> = emptyMap(),
    val datesByContext: Map<String, List<String>> = emptyMap(),
    val kindsByContext: Map<String, String> = emptyMap(),
    val total: Int = 0,
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    val loadError: String? = null,
    /** 窗口身份：删除会话时 +1，飞行中的翻页结果据此作废（不回填已被删的会话行，防幽灵）。 */
    val epoch: Int = 0,
    /** 已加载会话行内打开的单条详情（id → 所属 cap:contextId）：删除会话时连它一起关。 */
    val openLogContextIds: Map<Long, String> = emptyMap(),
    /** 下一页 keyset 游标：上一页末行会话的 (lastAt, latestLogId)；null=尚未翻页。 */
    val cursorLastAt: Long? = null,
    val cursorLatestId: Long? = null,
) {
    /**
     * 会话被删除后立即收敛本地窗口：行消失、各映射摘键、登记中的单条详情归属同步剔除；
     * 游标（lastAt 是该会话自身的 MAX(createdAt)）天然失效安全——被删会话不再回库，
     * 下一页 keyset 仍按旧游标续页不漏行；total 以 DB 重读值为准另行校正。
     */
    fun removeSession(contextId: String): CaptureHistoryState = copy(
        sessions = sessions.filter { it.contextId != contextId },
        anchorsByContext = anchorsByContext - contextId,
        datesByContext = datesByContext - contextId,
        kindsByContext = kindsByContext - contextId,
        openLogContextIds = openLogContextIds.filterValues { it != contextId },
    )

    /** 登记「单条详情 id 属于哪个 cap: 会话」：删除会话时凭它关掉已打开/加载中的详情层。 */
    fun withOpenLogContext(logId: Long, contextId: String): CaptureHistoryState =
        copy(openLogContextIds = openLogContextIds + (logId to contextId))
}

/**
 * 拉一页抓包会话（cap:contextId 分组摘要），并取每会话首条锚点/日期/来源类型用于筛选展示。
 * keyset 分页：游标是上一页末行会话的 (lastAt, latestLogId)，删除/新插入只影响已翻过的页，
 * 后续页不漏不重（OFFSET 删除后会整体错位跳过紧邻会话——缺页来源，故废弃）。
 */
internal suspend fun loadCapturePage(dao: StudioDao, current: CaptureHistoryState, pageSize: Int = 20): CaptureHistoryState {
    if (current.loading || current.exhausted) return current
    return try {
        val beforeLastAt = current.cursorLastAt ?: Long.MAX_VALUE
        val beforeLatestId = current.cursorLatestId ?: Long.MAX_VALUE
        val page = dao.captureSessionSummariesBefore(beforeLastAt, beforeLatestId, pageSize)
        val total = dao.countCaptureSessions()
        var sessions = current.sessions
        var anchors = current.anchorsByContext
        var dates = current.datesByContext
        var kinds = current.kindsByContext
        var cursorLastAt = current.cursorLastAt
        var cursorLatestId = current.cursorLatestId
        for (s in page) {
            cursorLastAt = s.lastAt
            cursorLatestId = s.latestLogId
            if (sessions.any { it.contextId == s.contextId }) continue
            sessions = sessions + s
            val summaries = dao.httpLogSummariesByContextIdPage(s.contextId, 50, 0)
            val anchor = summaries.firstOrNull { !it.sourceAnchor.isNullOrBlank() }?.sourceAnchor.orEmpty()
            anchors = anchors + (s.contextId to anchor)
            dates = dates + (s.contextId to summaries.map { LogFilterUtils.formatDateKey(it.createdAt) }.distinct().take(4))
            kinds = kinds + (s.contextId to CaptureSessionKinds.uiLabel(CaptureSessionKinds.classify(s)))
        }
        current.copy(
            sessions = sessions, anchorsByContext = anchors, datesByContext = dates, kindsByContext = kinds,
            total = total, loading = false, exhausted = page.size < pageSize,
            cursorLastAt = cursorLastAt, cursorLatestId = cursorLatestId,
        )
    } catch (e: Exception) {
        current.copy(loading = false, loadError = e.message?.take(160) ?: e.javaClass.simpleName)
    }
}

internal fun captureSessionMatches(s: CaptureSessionSummary, anchor: String, dates: List<String>, filter: String): Boolean {
    if (filter.isBlank()) return true
    return s.contextId.contains(filter, ignoreCase = true) ||
        anchor.contains(filter, ignoreCase = true) ||
        dates.any { it.contains(filter) }
}

/**
 * 抓包会话历史独立页（二级路由 capture_history，从「日志→抓包」入口进入）：
 * 单层 LazyColumn，筛选项/统计/行/加载更多全在同一主滚动容器；包含逐次与浏览器抓包会话，
 * 来源标签按真实 originKind 识别，不可靠显示「抓包」不伪造。默认进入即加载首屏。
 */
@Composable
fun CaptureHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as StudioApplication
    val haze = LocalStudioHaze.current
    val dao = app.database.dao()
    val scope = rememberCoroutineScope()

    var history by remember { mutableStateOf(CaptureHistoryState()) }
    var filter by remember { mutableStateOf("") }
    var idInput by remember { mutableStateOf("") }
    var lookupMessage by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    // 删除会话二次确认快照：(cap:contextId, 点击瞬间的会话条数)；弹窗期间翻页/筛选不串数据
    var pendingSessionDelete by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var deletingSession by remember { mutableStateOf(false) }

    // 覆盖层（会话详情/单条详情）
    var viewingHttpId by remember { mutableStateOf<Long?>(null) }
    var viewingFullLog by remember { mutableStateOf<HttpLogEntity?>(null) }
    var viewingCaptureContextId by remember { mutableStateOf<String?>(null) }
    val fullscreen = LocalStudioFullscreen.current
    androidx.compose.runtime.DisposableEffect(viewingHttpId != null, viewingCaptureContextId != null) {
        fullscreen?.value = viewingHttpId != null || viewingCaptureContextId != null
        onDispose { fullscreen?.value = false }
    }
    LaunchedEffect(viewingHttpId) {
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

    fun loadMore() {
        if (deletingSession || history.loading || history.exhausted) return
        // 快照未置 loading 的 request 传给 loadCapturePage（其入口守卫对 loading 态直接返回）；
        // UI 态单独置 loading 供去重与进度显示。
        val request = history
        history = request.copy(loading = true)
        scope.launch {
            val next = withContext(Dispatchers.IO) { loadCapturePage(dao, request) }
            // 与 HttpDayPage 同构的「发请求实例身份」守卫：当前窗口必须还是发出请求时那个
            // loading 页（同一 epoch）。删除会话把窗口置 loading=false 且 epoch+1，旧结果
            // 即便不带幽灵行也作废不回填（防止旧快照把删除后视图退回删除前窗口）。
            val cur = history
            if (cur.loading && cur.epoch == request.epoch) history = next
        }
    }

    // 默认进入即加载首屏（含失败重试由下方按钮触发）
    LaunchedEffect(Unit) { loadMore() }

    /**
     * 删除一个完整抓包会话（点击确认那一刻快照传入 ctx/count，协程内不读可变 state）：
     * - 活跃会话拒绝：可见浏览器页未「结束并保存」/无头 webview_capture 进行中的 cap:
     *   仍在追加日志，删行会与在途写入竞态复活成幽灵会话（WebViewCaptureEngine 活跃表为准）；
     * - DAO 层双谓词（精确等值 + LIKE 'cap:%'）：只删这一个会话，普通 HTTP/其它会话不动；
     * - 先关已打开详情再删：会话层/单条详情层都是本会话的数据，删后不能留着看尸体；
     * - epoch+1 作废飞行翻页回填（防幽灵行）；行立即从窗口剔除，total 再按 DB 重读校正。
     */
    fun deleteSession(ctx: String, expectedCount: Int) {
        if (deletingSession) return
        pendingSessionDelete = null
        if (app.webViewCapture.isCaptureSessionActive(ctx)) {
            message = "该会话仍在进行（浏览器抓包未结束），先到「结束并保存」再删除"
            return
        }
        deletingSession = true
        // 先关会话详情层再动库；单条详情是否属于本会话在协程里判（加载空窗时查库归属），
        // 详情页不会展示已删会话。点击确认即快照 openId/openEntity，协程内不再读可变 state。
        if (viewingCaptureContextId == ctx) viewingCaptureContextId = null
        val openId = viewingHttpId
        val openCtx = if (openId == null) null else (viewingFullLog?.contextId ?: history.openLogContextIds[openId])
        // 删除期间窗口换身份：任何在飞的「加载更多」结果作废
        history = history.copy(epoch = history.epoch + 1, loading = false)
        scope.launch {
            // 空窗详情（已置 viewingHttpId 但实体还在加载/未登记）删前查库归属：此时行尚未删，
            // 查得到；属于本会话才关，不误关其它会话的详情。
            val openBelongs = openId != null && (openCtx == ctx || openCtx == null &&
                withContext(Dispatchers.IO) { runCatching { dao.httpLog(openId)?.contextId }.getOrNull() } == ctx)
            val removed = runCatching {
                withContext(Dispatchers.IO) {
                    // 先删行再清落盘目录：两行都在 IO 上串行，竞态窗口内即使活跃表外会话
                    // 仍有在途写入，也比「先清目录再删行」少留孤儿文件引用。
                    val n = dao.deleteCaptureSessionByContextId(ctx)
                    // 会话落盘资源目录（字体/图片原始字节）随会话一起清，不留孤儿文件；
                    // 目录名经 captureDirName 校验（非 cap:/非法名不解析，不会误删其它目录）
                    WebViewCapture.captureDirName(ctx)?.let { dirName ->
                        File(context.cacheDir, "captures/$dirName").deleteRecursively()
                    }
                    n
                }
            }
            if (openBelongs && viewingHttpId == openId) viewingHttpId = null
            deletingSession = false
            removed.onSuccess { n ->
                history = history.removeSession(ctx)
                val realTotal = withContext(Dispatchers.IO) {
                    runCatching { dao.countCaptureSessions() }.getOrNull()
                }
                if (realTotal != null) history = history.copy(total = realTotal)
                message = if (n > 0) {
                    "已删除会话 ${ctx.removePrefix("cap:").take(13)}… 的 $n 条事务"
                } else {
                    "会话已不存在（$expectedCount 条快照过期或被并发清理）"
                }
            }.onFailure {
                message = "删除失败：${it.message?.take(160)}"
            }
        }
    }

    fun lookupById(raw: String) {
        // 全库精确查找：不依赖已加载页，直接按 cap:ID 查 count
        val id = raw.trim().removePrefix("cap:").trim()
        if (id.isEmpty()) return
        val ctx = "cap:$id"
        scope.launch {
            lookupMessage = "查找中…"
            val count = withContext(Dispatchers.IO) { dao.countHttpLogsByContextId(ctx) }
            if (count > 0) {
                lookupMessage = ""
                viewingCaptureContextId = ctx
            } else {
                lookupMessage = "未找到 cap:$id（可能已清理或 ID 有误）"
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val shown = history.sessions.filter { s ->
            captureSessionMatches(
                s,
                history.anchorsByContext[s.contextId].orEmpty(),
                history.datesByContext[s.contextId].orEmpty(),
                filter.trim(),
            )
        }
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
            item(key = "filter") {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("筛选已加载：锚点 / 日期 / contextId") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
            // 全库精确查找：关键词筛选只覆盖已加载页，找任意历史会话用 cap ID 直达
            item(key = "lookup") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = idInput,
                            onValueChange = { idInput = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("按抓包 ID 精确查找（cap:xxx 或裸 ID，全库）") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { lookupById(idInput) }, enabled = idInput.isNotBlank()) {
                            Text("查找", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (lookupMessage.isNotBlank()) {
                        Text(lookupMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (message.isNotBlank()) {
                        Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (history.sessions.isEmpty() && !history.loading) {
                item(key = "empty") {
                    EmptyHint(
                        history.loadError?.let { "加载失败：$it（点下方加载更多重试）" }
                            ?: "暂无抓包会话（跑一次「逐次抓包」或用「浏览器抓包」即落库；AI 经 MCP 发起的抓包同样在此）",
                    )
                }
            } else {
                item(key = "stats") {
                    Text(
                        "共 ${history.total} 个会话，已加载 ${history.sessions.size} 个，匹配 ${shown.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                items(shown, key = { it.contextId }) { s ->
                    val anchor = history.anchorsByContext[s.contextId].orEmpty()
                    val kind = history.kindsByContext[s.contextId].orEmpty()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .clickable { viewingCaptureContextId = s.contextId }
                            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (kind.isNotBlank()) {
                                    Text(
                                        kind,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                Text(
                                    s.contextId.removePrefix("cap:").take(13) + "…",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                buildString {
                                    append(formatLogTime(s.lastAt))
                                    append(" · ${s.totalCount} 条")
                                    if (s.hopCount > 0) append(" · ${s.hopCount} 中间跳")
                                    // 末行状态码仅对逐次抓包有「最终落点」语义；浏览器/未知会话不显示，不冒称最终响应
                                    if (kind == "逐次抓包") s.lastStatus?.let { append(" · $it") }
                                    if (anchor.isNotBlank()) append(" · $anchor")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("详情", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        // 逐行删除：只删这一个 cap: 会话（DAO 双谓词兜底），弹二次确认
                        IconButton(
                            onClick = { pendingSessionDelete = s.contextId to s.totalCount },
                            enabled = !deletingSession,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "删除会话",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                if (shown.isEmpty() && history.sessions.isNotEmpty()) {
                    item(key = "no_match") {
                        EmptyHint("已加载 ${history.sessions.size} 个会话中无匹配；换关键词、「加载更多」翻更早历史，或上方按抓包 ID 全库精确查找")
                    }
                }
            }
            item(key = "load_more") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { loadMore() }, enabled = !deletingSession && !history.loading && !history.exhausted) {
                        if (history.loading) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                if (history.exhausted) "已全部加载"
                                else if (history.loadError != null && history.sessions.isEmpty()) "重试加载"
                                else "加载更多",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    history.loadError?.takeIf { history.sessions.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        GlassTopBar("抓包会话历史", onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))

        // 抓包会话详情（二级覆盖层）；单条详情须绘制在其上
        viewingCaptureContextId?.let { ctx ->
            CaptureSessionDetail(
                dao = dao,
                contextId = ctx,
                onBack = { viewingCaptureContextId = null },
                onOpenLog = { viewingHttpId = it },
                // 详情页顶栏同样露「删除整会话」入口：点击回到本层弹二次确认（不直接删库）
                onDeleteSession = { target -> pendingSessionDelete = target to (history.sessions.firstOrNull { it.contextId == target }?.totalCount ?: -1) },
                // 登记详情行归属：删除会话时凭它关单条详情（含加载空窗内的行）
                onLogOpened = { logId -> history = history.withOpenLogContext(logId, ctx) },
            )
        }
        if (viewingHttpId != null) {
            viewingFullLog?.let { fullLog ->
                HttpLogDetail(log = fullLog, onBack = { viewingHttpId = null })
            }
        }
    }

    // 删除整会话二次确认：标题带 cap ID 与点击瞬间条数；活跃会话在确认前会被 deleteSession 拦住
    pendingSessionDelete?.let { (ctx, count) ->
        AlertDialog(
            onDismissRequest = { pendingSessionDelete = null },
            title = { Text("删除抓包会话") },
            text = {
                Text(
                    "将永久删除 $ctx 的全部${if (count >= 0) " $count 条" else ""}事务记录" +
                        "（含该会话落盘的字体/图片资源文件）。其它抓包会话与普通 HTTP 日志不受影响。此操作不可撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteSession(ctx, count) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingSessionDelete = null }) { Text("取消") } },
        )
    }
}
