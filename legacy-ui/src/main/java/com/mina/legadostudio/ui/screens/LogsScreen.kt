package com.mina.legadostudio.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.HttpLogSummary
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.diagnostic.CrashItem
import com.mina.legadostudio.domain.LogDeletePlan
import com.mina.legadostudio.domain.HttpLogAttributor
import com.mina.legadostudio.domain.HttpLogExportPlan
import com.mina.legadostudio.domain.HttpLogExportWriter
import com.mina.legadostudio.domain.LogExportFormat
import com.mina.legadostudio.domain.LogFilterUtils
import com.mina.legadostudio.domain.LogMultiFormatExporter
import com.mina.legadostudio.export.CaptureSessionExporter
import com.mina.legadostudio.verification.WebViewCapture
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun formatTime(value: Long): String = formatLogTime(value)

private enum class LogsTab { OPERATION, HTTP, CAPTURE, CRASH, SNAPSHOT }

private const val EXPORT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

// HttpDayPage / HTTP_DAY_PAGE_SIZE / httpDayRange 已提取到同包 HttpDayPage.kt（纯逻辑，无 Compose 依赖）。

/** 打开某一天的滚动窗口：首载即取基线 id 与真实日总数，再取第一页。 */
private suspend fun openHttpDayPage(dao: StudioDao, dateKey: String, epoch: Int): HttpDayPage {
    val (start, end) = httpDayRange(dateKey) ?: return HttpDayPage(dateKey, total = 0, baselineId = 0, exhausted = true, epoch = epoch)
    val baseline = dao.latestHttpLogIdNoCapture() ?: 0L
    val total = dao.countHttpLogsByDayNoCapture(start, end)
    val first = dao.httpLogSummariesByDayBeforeIdNoCapture(start, end, Long.MAX_VALUE, HTTP_DAY_PAGE_SIZE)
    return HttpDayPage(
        dateKey = dateKey, total = total, baselineId = baseline,
        entries = first, minId = first.lastOrNull()?.id,
        exhausted = first.size < HTTP_DAY_PAGE_SIZE,
        epoch = epoch,
    )
}

/**
 * 拉一页「加载更多」的裸结果（不并入）：调用方负责把它 foldNextPage 到发请求时的 loading 页上。
 * 返回值 (nextEntries, exhausted)；当前页不是 loading 态说明已被别的协程顶替，直接放弃。
 */
private suspend fun fetchHttpDayPageMore(dao: StudioDao, request: HttpDayPage): Pair<List<HttpLogSummary>, Boolean>? {
    if (!request.loading) return null
    val (start, end) = httpDayRange(request.dateKey) ?: return (emptyList<HttpLogSummary>() to true)
    return try {
        val page = dao.httpLogSummariesByDayBeforeIdNoCapture(start, end, request.minId ?: Long.MAX_VALUE, HTTP_DAY_PAGE_SIZE)
        page to (page.size < HTTP_DAY_PAGE_SIZE)
    } catch (e: Exception) {
        throw e
    }
}

@Composable
fun LogsScreen(
    onOpenGuide: () -> Unit = {},
    onOpenBrowserCapture: () -> Unit = {},
    onOpenCaptureOnce: () -> Unit = {},
    onOpenCaptureHistory: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as StudioApplication
    val haze = LocalStudioHaze.current
    val dao = app.database.dao()
    val scope = rememberCoroutineScope()
    val operations by dao.observeOperationLogs(500).collectAsState(initial = emptyList())
    // 操作日志全库日期导航（与 HTTP 的 DISTINCT 同构）：500 条窗口截断前的历史天也可直达/整删
    val operationLogDays by dao.observeOperationLogDays().collectAsState(initial = emptyList())
    // 操作日志逐日全库计数（日期弹窗与「删除该日」确认用真实 DB 口径，不用窗口内条数伪称全日）
    var opDayCounts by remember { mutableStateOf(mapOf<String, Int>()) }
    // 普通 HTTP 按天滚动窗口（无固定总上限）：日期导航走全库 DISTINCT，页内走 id keyset；
    // cap:% 抓包事务在 SQL 层排除（NULL contextId 保留），抓包高频记录不进本页签。
    val httpLogDays by dao.observeHttpLogDaysNoCapture().collectAsState(initial = emptyList())
    var httpDayPage by remember { mutableStateOf<HttpDayPage?>(null) }
    val httpLatestId by dao.observeLatestHttpLogIdNoCapture().collectAsState(initial = null)
    val snapshots by dao.observeDiagnosticSnapshots().collectAsState(initial = emptyList())
    var crashes by remember { mutableStateOf(app.crashLogs.list()) }
    // rememberSaveable：logs 被 capture_history/capture_once/browser_capture 等二级路由压栈时
    // 其 composable 离开组合，普通 remember 会丢——表现为「从历史页返回日志页落回操作日志页签」。
    // saveable 存活期间恢复 CAPTURE；进程重建（saver 失效）回默认 OPERATION，属可接受降级。
    var tab by rememberSaveable { mutableStateOf(LogsTab.OPERATION) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var viewingHttpId by remember { mutableStateOf<Long?>(null) }
    var viewingFullLog by remember { mutableStateOf<HttpLogEntity?>(null) }

    // 抓包页签只是三个独立二级页的入口（capture_once / browser_capture / capture_history）：
    // 表单、会话历史与详情均在各全屏页自持，本页不再承载抓包状态。
    var viewingCaptureContextId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewingHttpId) {
        val targetId = viewingHttpId
        if (targetId != null) {
            viewingFullLog = withContext(Dispatchers.IO) { dao.httpLog(targetId) }
        } else {
            viewingFullLog = null
        }
    }

    // 覆盖层加载空窗兜底：单条详情 viewingFullLog==null 期间其内部 BackHandler 尚未注册，
    // 由这里直接关掉最顶层（单条详情 > 会话详情）；无覆盖层时保持禁用，不吞页面级返回。
    BackHandler(enabled = viewingHttpId != null || viewingCaptureContextId != null) {
        if (viewingHttpId != null) viewingHttpId = null else viewingCaptureContextId = null
    }

    // 日期分页：操作日志导航合并「全库 DISTINCT 日」与 500 条窗口内分天（去重），可直达任意历史天；
    // HTTP 日期导航来自全库 DISTINCT（不受窗口限制，可直达任意历史天）
    val todayKey = remember { LogFilterUtils.formatDateKey(System.currentTimeMillis()) }
    val opDates = remember(operations, operationLogDays) {
        val set = (operationLogDays + operations.map { LogFilterUtils.formatDateKey(it.createdAt) }).distinct().toMutableList()
        if (todayKey !in set) set.add(0, todayKey)
        set.sortedDescending()
    }
    var selectedOpDate by remember { mutableStateOf(todayKey) }

    // HTTP 日志：选中日期 = 一个 HttpDayPage 滚动窗口（keyset 分页可翻完全日，不锁总条数）
    val httpDates = remember(httpLogDays, todayKey) {
        val set = httpLogDays.toMutableList()
        if (todayKey !in set) set.add(0, todayKey)
        set.sortedDescending()
    }
    var selectedHttpDate by remember { mutableStateOf(todayKey) }
    var selectedHttpAnchor by remember { mutableStateOf<String?>(null) }
    var showDatePickerDialog by remember { mutableStateOf(false) }
    // 日期弹窗逐日真实计数（SQL count，排除 cap:%）：与「该日期是否有记录」同一全库口径
    var httpDayCounts by remember { mutableStateOf(mapOf<String, Int>()) }
    // 「删除该日全部」二次确认快照：(目标页签, 日期, 点击瞬间的真实条数)；弹窗期间换日/翻页不串数据
    var pendingDayDelete by remember { mutableStateOf<Triple<LogsTab, String, Int>?>(null) }
    // 整日删除后刷新当前 HTTP 窗口与逐日真实计数，即使日期列表仍包含该日也能更新。
    var httpDayRefreshTick by remember { mutableStateOf(0) }
    var dayCountRefreshTick by remember { mutableStateOf(0) }

    // 打开/切换日期：整页重置（绝不复用旧日期窗口/旧锚点筛选），再异步拉首屏。
    // 写入用「epoch + 当前仍无页」双判据：A→B→A 同 key 的旧 IO 不能覆盖后开的 B/A 新窗口。
    // httpDayRefreshTick 挂进 key：当前查看日被整删/整删混入后主动重开，飞行中的「加载更多」结果作废不回填。
    var httpDayEpoch by remember { mutableStateOf(0) }
    LaunchedEffect(selectedHttpDate, httpDayRefreshTick) {
        httpDayPage = null
        selectedHttpAnchor = null
        val epoch = ++httpDayEpoch
        val dateKey = selectedHttpDate
        val opened = withContext(Dispatchers.IO) {
            runCatching { openHttpDayPage(dao, dateKey, epoch) }
                .getOrElse { HttpDayPage(dateKey, total = 0, baselineId = 0, exhausted = true, loadError = it.message?.take(160), epoch = epoch) }
        }
        val cur = httpDayPage
        // 只允许「同一 epoch 且当前窗口为空（尚未被更晚的打开占用）」时回填；否则丢弃过期结果
        // 只允许「同一 epoch 且当前窗口为空（尚未被更晚的打开占用）」时回填；否则丢弃过期结果
        if (cur == null) {
            if (dateKey == selectedHttpDate) httpDayPage = opened
        } else if (cur.dateKey == dateKey && cur.epoch == epoch && cur.entries.isEmpty() && !cur.loading) {
            if (dateKey == selectedHttpDate) httpDayPage = opened
        }
    }

    // 新插入增量：MAX(id) 越过本页基线时取增量行并入当前日期页头（不走 OFFSET，无漂移）；
    // 读写都基于回填瞬间的 cur（最新页实例）：loading 请求飞行中新行也进 cur.entries，不会被翻页回填覆盖。
    LaunchedEffect(httpLatestId) {
        val latest = httpLatestId ?: return@LaunchedEffect
        val page = httpDayPage ?: return@LaunchedEffect
        if (latest <= page.baselineId || page.dateKey != selectedHttpDate) return@LaunchedEffect
        val dateKey = selectedHttpDate
        val baseline = page.baselineId
        val inc = withContext(Dispatchers.IO) {
            runCatching {
                dao.httpLogSummariesAfterIdNoCapture(baseline)
                    .filter { LogFilterUtils.formatDateKey(it.createdAt) == dateKey }
            }.getOrElse { emptyList() }
        }
        if (inc.isNotEmpty()) {
            val cur = httpDayPage
            if (cur != null && cur.dateKey == dateKey) {
                val merged = cur.mergeNewer(inc)
                httpDayPage = cur.copy(entries = merged, baselineId = latest, total = cur.total + (merged.size - cur.entries.size))
            }
        }
    }

    // 日期弹窗逐日计数：对全库 DISTINCT 出的每一天查 countHttpLogsByDayNoCapture（排除 cap:%）。
    LaunchedEffect(httpLogDays, dayCountRefreshTick) {
        val days = httpLogDays
        if (days.isEmpty()) { httpDayCounts = emptyMap(); return@LaunchedEffect }
        val counts = withContext(Dispatchers.IO) {
            days.associateWith { key ->
                httpDayRange(key)?.let { (s, e) ->
                    runCatching { dao.countHttpLogsByDayNoCapture(s, e) }.getOrDefault(0)
                } ?: 0
            }
        }
        // 期间又有新日期进来则丢弃本次结果，等下一轮 LaunchedEffect 重算
        if (days == httpLogDays) httpDayCounts = counts
    }

    // 操作日志逐日计数：对全库 DISTINCT 出的每一天查 countOperationLogsByDay（真实 DB 口径，不受 500 窗口限制）。
    LaunchedEffect(operationLogDays, dayCountRefreshTick) {
        val days = operationLogDays
        if (days.isEmpty()) { opDayCounts = emptyMap(); return@LaunchedEffect }
        val counts = withContext(Dispatchers.IO) {
            days.associateWith { key ->
                httpDayRange(key)?.let { (s, e) ->
                    runCatching { dao.countOperationLogsByDay(s, e) }.getOrDefault(0)
                } ?: 0
            }
        }
        if (days == operationLogDays) opDayCounts = counts
    }

    // 当数据更新且当前选中日期不在列表中时，智能回退
    LaunchedEffect(opDates) {
        if (selectedOpDate !in opDates && opDates.isNotEmpty()) {
            selectedOpDate = opDates.first()
        }
    }
    LaunchedEffect(httpDates) {
        if (selectedHttpDate !in httpDates && httpDates.isNotEmpty()) {
            selectedHttpDate = httpDates.first()
        }
    }

    // 过滤后的列表（一天一页）
    val filteredOperations = remember(operations, selectedOpDate) {
        operations.filter { LogFilterUtils.formatDateKey(it.createdAt) == selectedOpDate }
    }
    // 当前 HTTP 窗口已加载行（回环/私网为展示层过滤，不改 SQL 日总数口径）
    val cleanHttpLogs = remember(httpDayPage) {
        httpDayPage?.takeIf { it.dateKey == selectedHttpDate }?.entries
            ?.filterNot { LogFilterUtils.isLoopbackOrPrivate(it.url) }.orEmpty()
    }
    // 已加载窗口内各条日志的归属结论：summary 投影含 url/finalUrl/requestHeaders/redirectChain/sourceAnchor，
    // 足以跑共享归属器（EXPLICIT/SAME_HOST/REFERER/UNATTRIBUTED/INFRASTRUCTURE），与 MCP/导出同一口径。
    // 窗口未翻到底时桶计数只代表已加载部分（UI 会明示）。
    val httpDayDecisions = remember(cleanHttpLogs) {
        val anchors = cleanHttpLogs.mapNotNull { it.sourceAnchor?.takeIf(String::isNotBlank) }.distinct()
        val ctxAnchors = com.mina.legadostudio.domain.ContextAnchorRegistry.snapshot()
        cleanHttpLogs.associate { s -> s.id to HttpLogAttributor.attribute(s.toAttributionEntity(), anchors, ctxAnchors) }
    }
    val filteredHttpLogs = remember(cleanHttpLogs, selectedHttpAnchor, httpDayDecisions) {
        when (selectedHttpAnchor) {
            null -> cleanHttpLogs
            else -> cleanHttpLogs.filter { s ->
                val d = httpDayDecisions[s.id]
                (d?.anchor ?: HttpLogAttributor.UNATTRIBUTED_KEY) == selectedHttpAnchor
            }
        }
    }
    // 已加载窗口内锚点分布：按归属器结论分桶（含 Referer 镜像归并），未归属/基础设施各占一桶
    val httpAnchorBuckets = remember(httpDayDecisions) {
        httpDayDecisions.values
            .groupingBy { it.anchor ?: HttpLogAttributor.UNATTRIBUTED_KEY }
            .eachCount()
            .toSortedMap()
    }

    val fullscreen = LocalStudioFullscreen.current
    DisposableEffect(viewingHttpId != null, viewingCaptureContextId != null) {
        fullscreen?.value = viewingHttpId != null || viewingCaptureContextId != null
        onDispose { fullscreen?.value = false }
    }

    var recording by remember { mutableStateOf(app.httpLogs.enabled) }
    var pendingDelete by remember { mutableStateOf(false) }
    var exportTab by remember { mutableStateOf<LogsTab?>(null) }
    // 导出弹窗打开瞬间的勾选快照（已解析为 Long id）：弹窗存活期间勾选/翻页/切换筛选都不串数据
    var exportSelectedSnapshot by remember { mutableStateOf(listOf<Long>()) }
    var message by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(tab) {
        selected = emptySet()
        expanded = null
        viewingHttpId = null
        viewingCaptureContextId = null
        selectedHttpAnchor = null
    }

    // 制作书源时新 HTTP 记录会持续插入到顶部：
    // 用户停留在顶部时自动跟随最新一条；已下翻浏览历史时保持原位不打断
    LaunchedEffect(tab, filteredHttpLogs.firstOrNull()?.id) {
        if (tab == LogsTab.HTTP && viewingHttpId == null &&
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 48
        ) {
            listState.scrollToItem(0)
        }
    }

    // 当前 Tab + 当前日期筛选下的可见 id：勾选与删除的唯一合法范围。
    // 用底层状态做 remember 键，内容不变时实例保持稳定，避免每帧重组把 LaunchedEffect 反复重启。
    val visibleIds = remember(tab, operations, selectedOpDate, httpDayPage, selectedHttpDate, selectedHttpAnchor, crashes, snapshots) {
        when (tab) {
            LogsTab.OPERATION -> filteredOperations.map { it.id.toString() }
            LogsTab.HTTP -> filteredHttpLogs.map { it.id.toString() }
            // 抓包页无可勾选的行（会话历史在卡片内管理，不进勾选删除通道），顶部全选/删除自动禁用
            LogsTab.CAPTURE -> emptyList()
            LogsTab.CRASH -> crashes.map { it.name }
            LogsTab.SNAPSHOT -> snapshots.map { it.id }
        }
    }
    val visibleIdSet = remember(visibleIds) { visibleIds.toSet() }

    // 选中集合始终收敛到当前可见视图：切 Tab / 切日期 / 删除后列表变化时，
    // 视图外的陈旧选中项自动脱落，杜绝跨视图误删，也杜绝非法 id 进入删除通道
    LaunchedEffect(visibleIdSet) {
        val trimmed = selected.intersect(visibleIdSet)
        if (trimmed != selected) selected = trimmed
    }

    fun toggle(id: String, checked: Boolean) {
        selected = if (checked) selected + id else selected - id
    }

    fun deleteSelected() {
        // 点击即快照：选中集合与可见列表都以点击瞬间的值为准，协程内不再读可变 state
        val chosen = selected
        val visible = visibleIds
        val currentTab = tab
        // 先复位 UI 态再做删除：对话框关闭、勾选清空，删除过程中列表变化也不会串味
        pendingDelete = false
        selected = emptySet()
        expanded = null
        scope.launch {
            runCatching {
                var removed = 0
                when (currentTab) {
                    // CAPTURE 无勾选项，删除按钮已禁用；走到这里时 chosen 必为空，无需删
                    LogsTab.OPERATION, LogsTab.HTTP, LogsTab.CAPTURE -> {
                        // 只删当前视图可见项，非数字/陈旧选中一律剔除；按 MAX_BATCH 切批远离 SQLite 变量上限
                        LogDeletePlan.chunk(LogDeletePlan.resolveLogIds(chosen, visible)).forEach { batch ->
                            removed += if (currentTab == LogsTab.OPERATION) {
                                dao.deleteOperationLogs(batch)
                            } else {
                                dao.deleteHttpLogs(batch)
                            }
                        }
                        // HTTP 删除后主动收敛窗口：MAX(id) 可能没变（删的不是最后一行）而不触发 Flow，
                        // 先从已加载行剔除已删 id（幽灵活立即消失），再从 DB 回填该日真实 count 校正 total。
                        if (currentTab == LogsTab.HTTP && removed > 0) {
                            val deletedIds = LogDeletePlan.resolveLogIds(chosen, visible).toSet()
                            val cur = httpDayPage
                            if (cur != null) {
                                httpDayPage = cur.removeIds(deletedIds)
                                val dateKey = cur.dateKey
                                val real = withContext(Dispatchers.IO) {
                                    httpDayRange(dateKey)?.let { (s, e) ->
                                        runCatching { dao.countHttpLogsByDayNoCapture(s, e) }.getOrNull()
                                    }
                                }
                                val cur2 = httpDayPage
                                if (real != null && cur2 != null && cur2.dateKey == dateKey) {
                                    httpDayPage = cur2.copy(total = real)
                                }
                            }
                        }
                    }
                    LogsTab.CRASH -> {
                        removed = app.crashLogs.delete(LogDeletePlan.resolveNames(chosen, visible))
                        crashes = app.crashLogs.list()
                    }
                    LogsTab.SNAPSHOT -> {
                        LogDeletePlan.chunkNames(LogDeletePlan.resolveNames(chosen, visible)).forEach { batch ->
                            removed += app.snapshots.delete(batch)
                        }
                    }
                }
                message = if (removed > 0) "已删除 $removed 条记录" else "没有可删除的记录"
            }.onFailure { message = it.message.orEmpty() }
        }
    }

    /**
     * 「选择日期」弹窗内的整删入口：按真实数据库日期范围删除该日全部记录，不受 UI 已加载窗口上限。
     * 参数在点确认那一刻快照传入（tab/dateKey/count）；协程内不再读可变 state。
     * - HTTP 只删普通事务（SQL 层排除 cap:%，NULL contextId 保留）：逐次/浏览器/MCP 抓包会话一行不动；
     * - OPERATION 删该日全库操作日志（不只 500 条窗口里那部分）；
     * - 飞行中的「加载更多」/换日期竞态：删除成功后 httpDayRefreshTick+1 整窗重开，守卫双重判 dateKey 防串日。
     */
    fun deleteDayLogs(deleteTab: LogsTab, dateKey: String, expectedCount: Int) {
        val range = httpDayRange(dateKey)
        pendingDayDelete = null
        showDatePickerDialog = false
        if (range == null) { message = "日期格式无效，无法删除"; return }
        // 先复位该页签上的勾选/展开：删整日后视图行会变化，勾选对老 id 的陈旧引用立即脱落
        selected = emptySet()
        expanded = null
        scope.launch {
            runCatching {
                val removed = withContext(Dispatchers.IO) {
                    when (deleteTab) {
                        LogsTab.HTTP -> dao.deleteHttpLogsByDayNoCapture(range.first, range.second)
                        LogsTab.OPERATION -> dao.deleteOperationLogsByDay(range.first, range.second)
                        else -> 0 // CAPTURE/CRASH/SNAPSHOT 弹窗不露出该入口，防御性不动数据
                    }
                }
                // 若被整删的正是当前查看日：HTTP 窗口整页重开（旧窗口/飞行请求对删后游标而言语义已作废），
                // 并清掉锚点筛选（桶分布已随行消失）。守卫 dateKey 仍是当前查看日才动窗口，防删除期间
                // 用户切日把新日的窗口误清。
                if (deleteTab == LogsTab.HTTP && dateKey == selectedHttpDate) {
                    selectedHttpAnchor = null
                    httpDayPage = null
                    httpDayRefreshTick++ // 触发 LaunchedEffect(selectedHttpDate, tick) 重开窗口
                }
                dayCountRefreshTick++
                message = if (removed > 0) {
                    "已删除 $dateKey 的 $removed 条${if (deleteTab == LogsTab.HTTP) " HTTP" else "操作"}日志"
                } else {
                    "$dateKey 已无记录（${expectedCount} 条快照过期或被并发清理）"
                }
            }.onFailure { message = "删除失败：${it.message?.take(160)}" }
        }
    }

    /**
     * 按所选格式导出日志并调起系统分享（FileProvider 授权一次读取）。
     *
     * HTTP 三个范围：当日（锚点筛选=当日该桶）/ 全部日期（锚点跨天归并）/ 仅已勾选。
     * 取数走 [HttpLogExportPlan]：第一趟轻量投影 keyset 页归属扫描（id<beforeId，无 OFFSET 漂移、
     * 不回读正文），第二趟按 id 分批取实体交 [HttpLogExportWriter] 流式写盘——
     * 旧实现「OFFSET 分页 + 整表 List 驻留内存」在扫表期间会被新插入推漂移、大响应体有 OOM 风险。
     */
    fun shareExport(currentTab: LogsTab, format: LogExportFormat, redact: Boolean, exportScope: String, selectedIds: List<Long>) {
        val isHttp = currentTab == LogsTab.HTTP
        val dateKey = if (isHttp) selectedHttpDate else selectedOpDate
        val ops = filteredOperations
        val anchorFilter = if (isHttp) selectedHttpAnchor else null
        val request = HttpLogExportPlan.Request(
            scope = HttpLogExportPlan.scopeFromKey(exportScope),
            dateKey = dateKey,
            anchorFilter = anchorFilter,
            selectedIds = if (isHttp) selectedIds else emptyList(),
        )
        val scopeLabel = HttpLogExportPlan.describe(request)
        // 文件名带范围段：跨日/勾选导出不再顶着当天日期名（后者会让人误以为只有当日数据）
        val scopeTag = when (HttpLogExportPlan.scopeFromKey(exportScope)) {
            HttpLogExportPlan.Scope.ALL -> "全部日期"
            HttpLogExportPlan.Scope.SELECTED -> "勾选${selectedIds.size}条"
            HttpLogExportPlan.Scope.DAY -> dateKey
        }
        val fileName = LogMultiFormatExporter.fileName(if (isHttp) "HTTP日志" else "操作日志", scopeTag, format)
        exportTab = null
        scope.launch {
            var stagingFile: File? = null
            runCatching {
                val exported = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val expireBefore = System.currentTimeMillis() - EXPORT_RETENTION_MS
                    dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < expireBefore) it.delete() }
                    // 一致性锚点快照：导出开始时取一次，scan 归属与 writer 逐行归属共用同一份，
                    // 避免两次 snapshot 之间 context 映射变化导致行归属与选中桶对不上
                    val ctxAnchors = com.mina.legadostudio.domain.ContextAnchorRegistry.snapshot()
                    // 半成品文件：先写 .tmp，全部写完后原子改名；同名已存在则 -2/-3 避让
                    var target = File(dir, fileName)
                    var suffix = 2
                    while (target.exists()) {
                        target = File(dir, fileName.substringBeforeLast('.') + "-$suffix." + fileName.substringAfterLast('.'))
                        suffix++
                    }
                    val tmp = File(dir, target.name + ".tmp")
                    stagingFile = tmp
                    val count = if (isHttp) {
                        val exportSource = object : HttpLogExportPlan.Source {
                            override suspend fun summaryDayPage(dayStart: Long, dayEnd: Long, beforeId: Long, limit: Int) =
                                dao.httpLogSummariesByDayBeforeIdNoCapture(dayStart, dayEnd, beforeId, limit)
                            override suspend fun summaryAllPage(beforeId: Long, limit: Int) =
                                dao.httpLogSummariesAllBeforeIdNoCapture(beforeId, limit)
                            override suspend fun entitiesByIds(ids: List<Long>) = dao.httpLogsByIds(ids)
                        }
                        val dayWindow = httpDayRange(dateKey)
                        // 第一趟：投影扫描出导出 id + 桶分布 + 锚点候选（与写出器共用同一锚点快照）
                        val scan = HttpLogExportPlan.scan(exportSource, request, dayWindow, contextAnchors = ctxAnchors)
                        // TXT 分桶需按桶序交批：桶 key 升序、桶内 id 升序（批内再按 createdAt 排）
                        val txtGrouping = format == LogExportFormat.TEXT &&
                            anchorFilter == null && scan.bucketCounts.size > 1
                        tmp.bufferedWriter(Charsets.UTF_8).use { writer ->
                            val exporter = HttpLogExportWriter(
                                out = writer, title = "HTTP 事务 · $scopeLabel", format = format, redact = redact,
                                groupByAnchor = txtGrouping, anchors = scan.anchors,
                                contextAnchors = ctxAnchors,
                            )
                            exporter.begin(scan.total)
                            if (txtGrouping) {
                                // 桶序交批（key 升序）：每桶先写节头（含条数），再按批取实体写出。
                                scan.idsByBucket.forEach { (bucket, bucketIds) ->
                                    exporter.beginBucket(bucket, scan.bucketCounts[bucket] ?: bucketIds.size)
                                    HttpLogExportPlan.forEachEntityBatch(exportSource, bucketIds) { exporter.writeBatch(it) }
                                }
                            } else {
                                HttpLogExportPlan.forEachEntityBatch(exportSource, scan.ids) { exporter.writeBatch(it) }
                            }
                            exporter.end()
                        }
                        scan.total
                    } else {
                        tmp.writeText(LogMultiFormatExporter.operation(ops, dateKey, format, redact), Charsets.UTF_8)
                        ops.size
                    }
                    check(tmp.renameTo(target)) { "导出文件改名失败" }
                    stagingFile = null
                    target to count
                }
                val (file, count) = exported
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = format.mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "分享日志").apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // startActivity 必须回主线程：IO 线程调起分享面板在部分 ROM 上唤不出/丢 ClipData 授权
                withContext(Dispatchers.Main) { context.startActivity(chooser) }
                message = "已导出 ${file.name}（$scopeLabel，$count 条，${file.length()} 字节）"
            }.onFailure {
                stagingFile?.delete()
                message = "导出失败：${it.message}"
                Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 调起系统分享导出单个或多个崩溃日志 */
    fun shareCrashFiles(names: List<String>) {
        if (names.isEmpty()) return
        scope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    val outName = if (names.size == 1) names.first() else "崩溃日志汇总_$stamp.txt"
                    val content = names.joinToString("\n\n" + "=".repeat(40) + "\n\n") { name ->
                        "【$name】\n" + app.crashLogs.read(name)
                    }
                    File(dir, outName).apply { writeText(content, Charsets.UTF_8) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "分享崩溃日志").apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                message = "已导出 ${file.name}"
            }.onFailure {
                message = "导出崩溃日志失败：${it.message}"
                Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 64.dp + studioTopInset())) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = StudioSpacing.screen, vertical = StudioSpacing.medium),
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.medium),
            ) {
                listOf(
                    LogsTab.OPERATION to "操作日志",
                    LogsTab.HTTP to "HTTP",
                    LogsTab.CAPTURE to "抓包",
                    LogsTab.CRASH to "崩溃",
                    LogsTab.SNAPSHOT to "诊断快照",
                ).forEach { (value, label) ->
                    val on = tab == value
                    FilterChip(
                        selected = on,
                        onClick = { tab = value },
                        label = { Text(label) },
                        colors = studioChipColors(),
                        border = studioChipBorder(on),
                        leadingIcon = if (on) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else null,
                    )
                }
            }

            if (message.isNotBlank()) {
                Text(message, Modifier.padding(horizontal = StudioSpacing.card, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            // 分类专属控制区
            when (tab) {
                LogsTab.OPERATION -> {
                    LogDateSwitchBar(
                        selectedDate = selectedOpDate,
                        todayKey = todayKey,
                        dates = opDates,
                        recordCount = filteredOperations.size,
                        onDateSelect = { selectedOpDate = it },
                        onOpenPicker = { showDatePickerDialog = true },
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.card), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { exportSelectedSnapshot = LogDeletePlan.resolveLogIds(selected, visibleIds); exportTab = LogsTab.OPERATION },
                            enabled = filteredOperations.isNotEmpty(),
                        ) { Text("导出当天 ${filteredOperations.size} 条", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                LogsTab.HTTP -> {
                    // 按天滚动窗口：当日总数来自 SQL count（全量口径）；未翻到底时明示「已加载部分」，
                    // 锚点桶计数与筛选结果只覆盖已加载窗口，不得伪装成全日分布。
                    val page = httpDayPage?.takeIf { it.dateKey == selectedHttpDate }
                    if (page != null && page.total > page.entries.size) {
                        Text(
                            "当日共 ${page.total} 条 · 已加载部分 ${page.entries.size} 条，锚点分布与筛选只覆盖已加载区间；下方「加载更多」可翻完全日",
                            Modifier.padding(horizontal = StudioSpacing.card, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LogDateSwitchBar(
                        selectedDate = selectedHttpDate,
                        todayKey = todayKey,
                        dates = httpDates,
                        recordCount = page?.total ?: 0,
                        onDateSelect = { selectedHttpDate = it },
                        onOpenPicker = { showDatePickerDialog = true },
                    )
                    // 锚点筛选条：当日各书源锚点（含「未标注」桶）+ 全部
                    if (httpAnchorBuckets.size > 1 || httpAnchorBuckets.keys.any { it != HttpLogAttributor.UNATTRIBUTED_KEY }) {
                        LazyRow(
                            Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.card, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            item {
                                FilterChip(
                                    selected = selectedHttpAnchor == null,
                                    onClick = { selectedHttpAnchor = null },
                                    label = { Text("全部") },
                                    colors = studioChipColors(),
                                    border = studioChipBorder(selectedHttpAnchor == null),
                                    leadingIcon = if (selectedHttpAnchor == null) { { Icon(Icons.Outlined.Check, contentDescription = null) } } else null,
                                )
                            }
                            httpAnchorBuckets.forEach { (anchor, count) ->
                                item(key = anchor) {
                                    val on = selectedHttpAnchor == anchor
                                    val label = when (anchor) {
                                        HttpLogAttributor.UNATTRIBUTED_KEY -> "未归属"
                                        HttpLogAttributor.INFRASTRUCTURE_ANCHOR -> "基础设施"
                                        else -> anchor
                                    }
                                    FilterChip(
                                        selected = on,
                                        onClick = { selectedHttpAnchor = anchor },
                                        label = { Text("$label($count)") },
                                        colors = studioChipColors(),
                                        border = studioChipBorder(on),
                                        leadingIcon = if (on) { { Icon(Icons.Outlined.Check, contentDescription = null) } } else null,
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.card, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            // 导出按日全量（排除 cap:%）：表述用 SQL 日总数，不用已加载窗口数伪称；锚点筛选只圈定归属桶
                            if (selectedHttpAnchor != null) "HTTP 事务 · 已选锚点筛选（导出该桶全部）"
                            else "记录 HTTP 事务",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    // 点击即快照勾选集（解析成 Long id）：弹窗打开期间改勾选/翻页都不串进本次导出
                                    exportSelectedSnapshot = LogDeletePlan.resolveLogIds(selected, visibleIds)
                                    exportTab = LogsTab.HTTP
                                },
                                enabled = (page?.total ?: 0) > 0 || selected.isNotEmpty(),
                            ) { Text(if (selected.isNotEmpty()) "导出（已勾选 ${selected.size}）" else "导出", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Switch(checked = recording, onCheckedChange = { recording = it; app.httpLogs.enabled = it })
                        }
                    }
                }
                // 抓包独立页签：紧凑入口区（全部内容在下方同一 LazyColumn 内滚动），普通 HTTP 日志不混入
                LogsTab.CAPTURE -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.card, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "记录 HTTP 事务（抓包必需）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Switch(checked = recording, onCheckedChange = { recording = it; app.httpLogs.enabled = it })
                    }
                }
                LogsTab.CRASH -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.card, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "崩溃记录（共 ${crashes.size} 条）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = {
                                val toExport = if (selected.isNotEmpty()) selected.toList() else crashes.map { it.name }
                                shareCrashFiles(toExport)
                            },
                            enabled = crashes.isNotEmpty(),
                        ) {
                            Text(if (selected.isNotEmpty()) "导出已选 (${selected.size})" else "导出全部崩溃", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                LogsTab.SNAPSHOT -> Button(
                    onClick = {
                        scope.launch {
                            runCatching { app.snapshots.create() }
                                .onSuccess { message = "已创建：${it.title}" }
                                .onFailure { message = it.message.orEmpty() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.card, vertical = StudioSpacing.small),
                ) { Text("创建诊断快照") }
            }

            LazyColumn(
                Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier),
                state = listState,
                contentPadding = PaddingValues(
                    start = StudioSpacing.screen,
                    end = StudioSpacing.screen,
                    top = StudioSpacing.medium,
                    bottom = StudioSpacing.screenBottomBase + studioBottomInset(),
                ),
                verticalArrangement = Arrangement.spacedBy(StudioSpacing.medium),
            ) {
                when (tab) {
                    LogsTab.OPERATION -> if (filteredOperations.isEmpty()) item {
                        EmptyHint("该日期暂无操作日志。")
                    } else items(filteredOperations, key = { it.id }) { log ->
                        val id = log.id.toString()
                        LogRow(id, selected, ::toggle, { expanded = if (expanded == id) null else id }) {
                            Text("${log.level} · ${log.category} · ${formatTime(log.createdAt)}", style = MaterialTheme.typography.labelMedium)
                            Text(log.message, fontWeight = FontWeight.SemiBold)
                            if (expanded == id && log.detail.isNotBlank()) Text(log.detail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    LogsTab.HTTP -> {
                        if (filteredHttpLogs.isEmpty()) item {
                            val page = httpDayPage?.takeIf { it.dateKey == selectedHttpDate }
                            EmptyHint(
                                when {
                                    page == null || page.loading -> "正在加载该日期 HTTP 事务…"
                                    page.loadError != null -> "加载失败：${page.loadError}（点下方加载更多重试）"
                                    selectedHttpAnchor != null && page.entries.isNotEmpty() ->
                                        "已加载 ${page.entries.size} 条内无该锚点记录；当日共 ${page.total} 条，${if (page.exhausted) "已加载完全日" else "点下方「加载更多」继续翻找"}"
                                    else -> "该日期暂无 HTTP 事务记录。"
                                }
                            )
                        } else items(filteredHttpLogs, key = { it.id }) { log ->
                            val id = log.id.toString()
                            LogRow(id, selected, ::toggle, { viewingHttpId = log.id }) {
                                Text("${log.method} ${log.url}", fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(
                                    "${log.statusCode} · ${log.durationMs}ms · ${formatTime(log.createdAt)}${log.error.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (!log.sourceAnchor.isNullOrBlank() || !log.originKind.isNullOrBlank()) {
                                    Text(
                                        buildString {
                                            log.sourceAnchor?.takeIf { it.isNotBlank() }?.let { append("锚点 $it") }
                                            log.originKind?.takeIf { it.isNotBlank() }?.let { if (isNotEmpty()) append(" · "); append(it) }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // cap: 抓包事务不进入本列表（SQL 已排除），逐跳回看走「抓包」页签
                            }
                        }
                        // 滚动窗口「加载更多」：keyset 翻页直到当日穷尽；锚点筛选不匹配时也可继续向下翻找
                        run {
                            val page = httpDayPage?.takeIf { it.dateKey == selectedHttpDate }
                            if (page != null && (!page.exhausted || page.loading || page.loadError != null)) {
                                item {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                                        TextButton(
                                            onClick = {
                                                if (page.loading) return@TextButton
                                                // 发请求即换身份（beginLoadMore: loading=true 且 epoch+1）：
                                                // 只有拿着这个 request 实例的协程允许回填，旧请求/旧日期结果作废。
                                                val request = page.beginLoadMore()
                                                httpDayPage = request
                                                scope.launch {
                                                    val fetched = withContext(Dispatchers.IO) {
                                                        runCatching { fetchHttpDayPageMore(dao, request) }
                                                    }
                                                    val cur = httpDayPage
                                                    // 同一性守卫：当前窗口必须还是发请求时那个 loading 页实例
                                                    // （同 epoch、同 dateKey）。飞行期间 LaunchedEffect(httpLatestId)
                                                    // 把新行 mergeNewer 到 request（同一实例）上，fold 到 cur 不丢；
                                                    // 若期间被换日期/重开覆盖（实例已变），本结果直接丢弃。
                                                    if (cur != null && cur === request && cur.loading && cur.epoch == request.epoch && cur.dateKey == request.dateKey) {
                                                        httpDayPage = fetched.fold(
                                                            onSuccess = { pair ->
                                                                if (pair == null) cur.copy(loading = false)
                                                                else request.foldNextPage(cur, pair.first, pair.second)
                                                            },
                                                            onFailure = { cur.copy(loading = false, loadError = it.message?.take(160) ?: it.javaClass.simpleName) },
                                                        )
                                                    }
                                                }
                                            },
                                            enabled = !page.loading,
                                        ) {
                                            if (page.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                            else Text(
                                                if (page.loadError != null) "重试加载更多"
                                                else "加载更多（当日共 ${page.total} 条，已加载 ${page.entries.size}）",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    LogsTab.CAPTURE -> {
                        // 三个入口全部放进主滚动容器：小屏可滚、底部始终可达，不再是固定区+空列表
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenCaptureOnce) {
                                Column(Modifier.fillMaxWidth().padding(StudioSpacing.card), verticalArrangement = Arrangement.spacedBy(StudioSpacing.small)) {
                                    Text("逐次抓包", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "手动发一次公网 HTTP 请求，按独立 cap:contextId 汇总本次事务（含重定向逐跳证据）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenBrowserCapture) {
                                Column(Modifier.fillMaxWidth().padding(StudioSpacing.card), verticalArrangement = Arrangement.spacedBy(StudioSpacing.small)) {
                                    Text("浏览器抓包", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "可见可交互的 WebView 抓包页：点链接/翻页期间 GET/HEAD 由 OkHttp 供给或仅观察（非原生网络栈抓包），记入同一 cap: 会话",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenCaptureHistory) {
                                Column(Modifier.fillMaxWidth().padding(StudioSpacing.card), verticalArrangement = Arrangement.spacedBy(StudioSpacing.small)) {
                                    Text("抓包会话历史", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "逐次与浏览器抓包会话按 cap:contextId 分组持久化，重启可回看；支持分页加载与按抓包 ID 全库精确查找",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        item {
                            EmptyHint("抓包记录不混入 HTTP 页签（该页按天滚动窗口可翻完全日）；AI 经 MCP 发起的 capture_once / webview_capture 会话同样出现在会话历史。")
                        }
                    }
                    LogsTab.CRASH -> if (crashes.isEmpty()) item { EmptyHint("暂无崩溃记录。") }
                    else items(crashes, key = { it.name }) { crash ->
                        CrashRow(
                            crash = crash,
                            selected = selected,
                            expanded = expanded,
                            app = app,
                            onToggle = ::toggle,
                            onExpand = { expanded = if (expanded == crash.name) null else crash.name },
                            onShare = { shareCrashFiles(listOf(crash.name)) },
                        )
                    }
                    LogsTab.SNAPSHOT -> if (snapshots.isEmpty()) item { EmptyHint("暂无诊断快照。") }
                    else items(snapshots, key = { it.id }) { snap ->
                        LogRow(snap.id, selected, ::toggle, { expanded = if (expanded == snap.id) null else snap.id }) {
                            Text(snap.title, fontWeight = FontWeight.SemiBold)
                            Text(formatTime(snap.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (expanded == snap.id) Text(app.snapshots.read(snap).take(6_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        GlassTopBar("日志", actions = {
            TextButton(onClick = onOpenGuide) { Text("功能介绍", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = {
                selected = if (selected.size == visibleIds.size) emptySet() else visibleIds.toSet()
            }, enabled = visibleIds.isNotEmpty()) {
                Text(
                    if (selected.size == visibleIds.size && visibleIds.isNotEmpty()) "取消全选" else "全选",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { pendingDelete = true }, enabled = selected.isNotEmpty()) { Text("删除", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }, modifier = Modifier.align(Alignment.TopCenter))
    }

    // 日期快速跳选弹窗（自适应操作日志或 HTTP 日志）
    if (showDatePickerDialog) {
        val isHttp = tab == LogsTab.HTTP
        val currentDates = if (isHttp) httpDates else opDates
        val currentDate = if (isHttp) selectedHttpDate else selectedOpDate
        // 两侧都走 SQL 逐日全库口径：HTTP 排除 cap:% 抓包（NULL contextId 保留）；操作日志按真实日期计数
        // 不用窗口内条数伪称全日——500 条窗口截断前的历史行也计入、也能被「删除该日」删掉。
        val getCount: (String) -> Int = { dateKey ->
            if (isHttp) httpDayCounts[dateKey] ?: 0
            else opDayCounts[dateKey] ?: operations.count { LogFilterUtils.formatDateKey(it.createdAt) == dateKey }
        }
        val dialogTab = tab
        val selectedCount = getCount(currentDate)

        AlertDialog(
            onDismissRequest = { showDatePickerDialog = false },
            title = { Text(if (isHttp) "选择 HTTP 日志日期" else "选择操作日志日期") },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    currentDates.forEach { dateKey ->
                        val isSelected = dateKey == currentDate
                        val count = getCount(dateKey)
                        val label = if (dateKey == todayKey) "$dateKey (今天)" else dateKey
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable {
                                    if (isHttp) selectedHttpDate = dateKey else selectedOpDate = dateKey
                                    showDatePickerDialog = false
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "$count 条",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (count > 0) {
                                    IconButton(
                                        onClick = { pendingDayDelete = Triple(dialogTab, dateKey, count) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "删除 $dateKey 全部 $count 条",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 整删入口对当前查看日始终可见（条数=全库真实口径，不受已加载窗口/500 条上限）
                    if (selectedCount > 0) {
                        TextButton(
                            onClick = { pendingDayDelete = Triple(dialogTab, currentDate, selectedCount) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "删除 $currentDate 全部 $selectedCount 条${if (isHttp) " HTTP" else "操作"}日志",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (isHttp) {
                        Text(
                            "只删普通 HTTP 事务；抓包 cap: 会话（逐次/浏览器）不受影响",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDatePickerDialog = false }) { Text("关闭") }
            },
        )
    }

    // 「删除该日全部」二次确认：标题带日期与点击瞬间的真实条数；HTTP 明示不触及抓包会话
    pendingDayDelete?.let { (deleteTab, deleteDate, deleteCount) ->
        AlertDialog(
            onDismissRequest = { pendingDayDelete = null },
            title = { Text("删除 $deleteDate 全部 $deleteCount 条") },
            text = {
                Text(
                    if (deleteTab == LogsTab.HTTP)
                        "将按数据库实际日期删除 $deleteDate 当天全部普通 HTTP 日志（含未加载到列表的部分）。抓包 cap: 会话不受影响。此操作不可撤销。"
                    else
                        "将按数据库实际日期删除 $deleteDate 当天全部操作日志（不限于当前列表里看到的部分）。此操作不可撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteDayLogs(deleteTab, deleteDate, deleteCount) }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDayDelete = null }) { Text("取消") } },
        )
    }

    // 抓包会话详情：按 cap:contextId 从库里逐页取（重启可回看，不受 500 条窗口限制）。
    // 注意必须先画会话层再画单条详情：详情是从会话行点开的二级覆盖层，顺序反了会被遮住。
    viewingCaptureContextId?.let { ctx ->
        CaptureSessionDetail(
            dao = dao,
            contextId = ctx,
            onBack = { viewingCaptureContextId = null },
            onOpenLog = { viewingHttpId = it },
        )
    }

    // HTTP 详情：独立全屏层 + 自身滚动，查看时不受列表新增/滑动影响（须位于会话层之上）
    if (viewingHttpId != null) {
        viewingFullLog?.let { fullLog ->
            HttpLogDetail(log = fullLog, onBack = { viewingHttpId = null })
        }
    }

    if (pendingDelete) AlertDialog(
        onDismissRequest = { pendingDelete = false },
        title = { Text("删除 ${selected.size} 条记录") },
        text = { Text("此操作不可撤销，仅作用于当前分类与当前筛选视图中的已选记录。") },
        confirmButton = { TextButton(onClick = { pendingDelete = false; deleteSelected() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { pendingDelete = false }) { Text("取消") } },
    )

    exportTab?.let { exporting ->
        val isHttp = exporting == LogsTab.HTTP
        val dayPage = httpDayPage?.takeIf { it.dateKey == selectedHttpDate }
        // 勾选快照在置 exportTab 那一刻已捕获（exportSelectedSnapshot）：弹窗打开期间勾选变化不串数据
        val selectedCount = exportSelectedSnapshot.size
        LogExportDialog(
            kindLabel = if (isHttp) "HTTP 日志" else "操作日志",
            dateKey = if (isHttp) selectedHttpDate else selectedOpDate,
            count = if (isHttp) filteredHttpLogs.size else filteredOperations.size,
            dayTotal = if (isHttp) dayPage?.total ?: filteredHttpLogs.size else filteredOperations.size,
            scopeHint = if (isHttp && selectedHttpAnchor != null) {
                "当前锚点筛选「${HttpLogExportPlan.defaultBucketLabel(selectedHttpAnchor!!)}」：导出该归属桶记录（按归属器口径重算，不受已加载窗口限制），抓包 cap: 事务不混入"
            } else if (isHttp) {
                "普通 HTTP 事务导出（不含抓包 cap: 会话，抓包走「抓包」页签回看）"
            } else {
                null
            },
            // HTTP 有范围选择：当日 / 全部日期（锚点跨天）/ 仅已勾选（选中>0 才出现）
            exportScopes = if (isHttp) {
                HttpLogExportPlan.scopeOptions(
                    dateKey = selectedHttpDate,
                    dayTotal = dayPage?.total ?: filteredHttpLogs.size,
                    selectedCount = selectedCount,
                    anchorFilter = selectedHttpAnchor,
                )
            } else {
                null
            },
            onDismiss = { exportTab = null },
            onConfirm = { format, redact, scopeKey -> shareExport(exporting, format, redact, scopeKey, exportSelectedSnapshot) },
        )
    }
}

@Composable
private fun LogRow(
    id: String,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onExpand: () -> Unit,
    content: @Composable () -> Unit,
) {
    GlassCard {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = id in selected, onCheckedChange = { onToggle(id, it) })
            Column(Modifier.weight(1f).clickable(onClick = onExpand).padding(vertical = 10.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun CrashRow(
    crash: CrashItem,
    selected: Set<String>,
    expanded: String?,
    app: StudioApplication,
    onToggle: (String, Boolean) -> Unit,
    onExpand: () -> Unit,
    onShare: () -> Unit,
) {
    GlassCard {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = crash.name in selected, onCheckedChange = { onToggle(crash.name, it) })
            Column(Modifier.weight(1f).clickable(onClick = onExpand).padding(vertical = 10.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(crash.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onShare) {
                        Text("分享", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(formatTime(crash.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expanded == crash.name) {
                    Text(
                        app.crashLogs.read(crash.name).take(12_000),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = true,
                    )
                }
            }
        }
    }
}



@Composable
private fun LogDateSwitchBar(
    selectedDate: String,
    todayKey: String,
    dates: List<String>,
    recordCount: Int,
    onDateSelect: (String) -> Unit,
    onOpenPicker: () -> Unit,
) {
    val currentIndex = dates.indexOf(selectedDate)
    val hasNewer = currentIndex > 0
    val hasOlder = currentIndex >= 0 && currentIndex < dates.size - 1

    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.screen, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.medium, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = {
                    if (hasOlder) onDateSelect(dates[currentIndex + 1])
                },
                enabled = hasOlder,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "前一天",
                    tint = if (hasOlder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                )
            }

            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenPicker() }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val dateLabel = if (selectedDate == todayKey) "$selectedDate (今天)" else selectedDate
                Text(
                    dateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "当天共 $recordCount 条记录 · 点此快速选天 ▾",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(
                onClick = {
                    if (hasNewer) onDateSelect(dates[currentIndex - 1])
                },
                enabled = hasNewer,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = "后一天",
                    tint = if (hasNewer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                )
            }
        }
    }
}


/**
 * 导出整个 cap: 抓包会话为 JSON 并经 FileProvider 分享（写 cacheDir/exports，已注册）。
 * 流式写盘（[CaptureSessionExporter.writeSessionJson] 分页取行、逐行写），不整装载 List/JSON 串；
 * WebView 抓包二进制行内嵌 base64 字节 + 落盘相对路径；末尾汇总 anchors（书源锚点）。
 */
internal fun exportCaptureSession(
    context: android.content.Context,
    contextId: String,
    scope: CoroutineScope,
    onNotice: (String) -> Unit = { toastNotice(context, it) },
) {
    scope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val app = context.applicationContext as StudioApplication
                val dao = app.database.dao()
                val total = dao.countHttpLogsByContextId(contextId)
                if (total <= 0) error("会话暂无记录")
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val expireBefore = System.currentTimeMillis() - EXPORT_RETENTION_MS
                dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < expireBefore) it.delete() }
                // 导出前清掉 cacheDir/captures/ 里已过保留期的会话目录（保留当前要导出的这个），
                // 既不丢即将导出的字体字节，也把长期堆积的旧原始文件收掉。
                runCatching {
                    WebViewCapture.purgeExpiredCaptures(context.cacheDir, WebViewCapture.CAPTURE_FILE_RETENTION_MS, keepCtx = contextId)
                }
                val file = File(dir, "capture_${contextId.removePrefix("cap:").take(8)}_${System.currentTimeMillis()}.json")
                val written = file.bufferedWriter(Charsets.UTF_8).use { writer ->
                    CaptureSessionExporter.writeSessionJson(
                        out = writer,
                        contextId = contextId,
                        exportedAt = System.currentTimeMillis(),
                        total = total,
                        page = { offset -> dao.httpLogsByContextIdPage(contextId, CaptureSessionExporter.EXPORT_PAGE_SIZE, offset) },
                        fileReader = { rel -> runCatching { File(context.cacheDir, rel).takeIf { it.isFile }?.readBytes() }.getOrNull() },
                    )
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "导出抓包会话").apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // startActivity 回主线程：IO 线程调起分享面板在部分 ROM 上唤不出/丢 ClipData 授权
                withContext(Dispatchers.Main) { context.startActivity(chooser) }
                file.name to written
            }
        }.onSuccess { (name, count) -> onNotice("已导出 $name（$count 条事务；二进制字节内嵌在 JSON）") }
            .onFailure { onNotice("导出失败：${it.message?.take(120)}") }
    }
}
