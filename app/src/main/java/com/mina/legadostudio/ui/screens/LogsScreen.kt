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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.diagnostic.CrashItem
import com.mina.legadostudio.domain.LogFilterUtils
import com.mina.legadostudio.ui.theme.GlassCard
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.LocalStudioFullscreen
import com.mina.legadostudio.ui.theme.LocalStudioHaze
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioChipBorder
import com.mina.legadostudio.ui.theme.studioChipColors
import com.mina.legadostudio.ui.theme.studioTopInset
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class LogsTab { OPERATION, HTTP, CRASH, SNAPSHOT }

@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as StudioApplication
    val haze = LocalStudioHaze.current
    val dao = app.database.dao()
    val scope = rememberCoroutineScope()
    val operations by dao.observeOperationLogs(500).collectAsState(initial = emptyList())
    val httpLogs by dao.observeHttpLogs(500).collectAsState(initial = emptyList())
    val snapshots by dao.observeDiagnosticSnapshots().collectAsState(initial = emptyList())
    var crashes by remember { mutableStateOf(app.crashLogs.list()) }
    var tab by remember { mutableStateOf(LogsTab.OPERATION) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var viewingHttpId by remember { mutableStateOf<Long?>(null) }

    // 日期分页：一天一页（操作日志与 HTTP 日志一致）
    val todayKey = remember { LogFilterUtils.formatDateKey(System.currentTimeMillis()) }
    val opDates = remember(operations) {
        val set = operations.map { LogFilterUtils.formatDateKey(it.createdAt) }.distinct().toMutableList()
        if (todayKey !in set) set.add(0, todayKey)
        set.sortedDescending()
    }
    var selectedOpDate by remember { mutableStateOf(todayKey) }

    // HTTP 日志：过滤掉本地回环与私网端点（如 127.0.0.1、localhost 等），按日期分页（一天一页）
    val cleanHttpLogs = remember(httpLogs) {
        httpLogs.filterNot { LogFilterUtils.isLoopbackOrPrivate(it.url) }
    }
    val httpDates = remember(cleanHttpLogs) {
        val set = cleanHttpLogs.map { LogFilterUtils.formatDateKey(it.createdAt) }.distinct().toMutableList()
        if (todayKey !in set) set.add(0, todayKey)
        set.sortedDescending()
    }
    var selectedHttpDate by remember { mutableStateOf(todayKey) }
    var showDatePickerDialog by remember { mutableStateOf(false) }

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
    val filteredHttpLogs = remember(cleanHttpLogs, selectedHttpDate) {
        cleanHttpLogs.filter { LogFilterUtils.formatDateKey(it.createdAt) == selectedHttpDate }
    }

    val fullscreen = LocalStudioFullscreen.current
    DisposableEffect(viewingHttpId != null) {
        fullscreen?.value = viewingHttpId != null
        onDispose { fullscreen?.value = false }
    }

    var recording by remember { mutableStateOf(app.httpLogs.enabled) }
    var pendingDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(tab) {
        selected = emptySet()
        expanded = null
        viewingHttpId = null
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

    val ids = when (tab) {
        LogsTab.OPERATION -> filteredOperations.map { it.id.toString() }
        LogsTab.HTTP -> filteredHttpLogs.map { it.id.toString() }
        LogsTab.CRASH -> crashes.map { it.name }
        LogsTab.SNAPSHOT -> snapshots.map { it.id }
    }

    fun toggle(id: String, checked: Boolean) {
        selected = if (checked) selected + id else selected - id
    }

    fun deleteSelected() {
        scope.launch {
            runCatching {
                when (tab) {
                    LogsTab.OPERATION -> dao.deleteOperationLogs(selected.map { it.toLong() })
                    LogsTab.HTTP -> dao.deleteHttpLogs(selected.map { it.toLong() })
                    LogsTab.CRASH -> {
                        app.crashLogs.delete(selected)
                        crashes = app.crashLogs.list()
                    }
                    LogsTab.SNAPSHOT -> app.snapshots.delete(selected.toList())
                }
                message = "已删除 ${selected.size} 条记录"
                selected = emptySet()
                expanded = null
            }.onFailure { message = it.message.orEmpty() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 64.dp + studioTopInset())) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    LogsTab.OPERATION to "操作日志",
                    LogsTab.HTTP to "HTTP",
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
                Text(message, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
                }
                LogsTab.HTTP -> {
                    LogDateSwitchBar(
                        selectedDate = selectedHttpDate,
                        todayKey = todayKey,
                        dates = httpDates,
                        recordCount = filteredHttpLogs.size,
                        onDateSelect = { selectedHttpDate = it },
                        onOpenPicker = { showDatePickerDialog = true },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("记录 HTTP 事务", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = recording, onCheckedChange = { recording = it; app.httpLogs.enabled = it })
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) { Text("创建诊断快照") }
                else -> Unit
            }

            LazyColumn(
                Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier),
                state = listState,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 108.dp + studioBottomInset()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    LogsTab.HTTP -> if (filteredHttpLogs.isEmpty()) item {
                        EmptyHint("该日期暂无 HTTP 事务记录。")
                    } else items(filteredHttpLogs, key = { it.id }) { log ->
                        val id = log.id.toString()
                        LogRow(id, selected, ::toggle, { viewingHttpId = log.id }) {
                            Text("${log.method} ${log.url}", fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(
                                "${log.statusCode} · ${log.durationMs}ms · ${formatTime(log.createdAt)}${log.error.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    LogsTab.CRASH -> if (crashes.isEmpty()) item { EmptyHint("暂无崩溃记录。") }
                    else items(crashes, key = { it.name }) { crash ->
                        CrashRow(crash, selected, expanded, app, ::toggle) { expanded = if (expanded == crash.name) null else crash.name }
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
            TextButton(onClick = {
                selected = if (selected.size == ids.size) emptySet() else ids.toSet()
            }, enabled = ids.isNotEmpty()) { Text(if (selected.size == ids.size && ids.isNotEmpty()) "取消全选" else "全选") }
            TextButton(onClick = { pendingDelete = true }, enabled = selected.isNotEmpty()) { Text("删除") }
        }, modifier = Modifier.align(Alignment.TopCenter))
    }

    // 日期快速跳选弹窗（自适应操作日志或 HTTP 日志）
    if (showDatePickerDialog) {
        val isHttp = tab == LogsTab.HTTP
        val currentDates = if (isHttp) httpDates else opDates
        val currentDate = if (isHttp) selectedHttpDate else selectedOpDate
        val getCount: (String) -> Int = { dateKey ->
            if (isHttp) cleanHttpLogs.count { LogFilterUtils.formatDateKey(it.createdAt) == dateKey }
            else operations.count { LogFilterUtils.formatDateKey(it.createdAt) == dateKey }
        }

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
                            Text(
                                "$count 条",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDatePickerDialog = false }) { Text("关闭") }
            },
        )
    }

    // HTTP 详情：独立全屏层 + 自身滚动，查看时不受列表新增/滑动影响
    val viewingLog = httpLogs.firstOrNull { it.id == viewingHttpId }
    if (viewingLog != null) {
        HttpLogDetail(log = viewingLog, onBack = { viewingHttpId = null })
    }

    if (pendingDelete) AlertDialog(
        onDismissRequest = { pendingDelete = false },
        title = { Text("删除 ${selected.size} 条记录") },
        text = { Text("此操作不可撤销，仅作用于当前分类与当前筛选视图中的已选记录。") },
        confirmButton = { TextButton(onClick = { pendingDelete = false; deleteSelected() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { pendingDelete = false }) { Text("取消") } },
    )
}

/** HTTP 事务详情页：独占滚动容器，长按可选中复制 */
@Composable
private fun HttpLogDetail(log: HttpLogEntity, onBack: () -> Unit) {
    // 系统返回键/手势只关闭详情层，回到日志列表，不触发外层“回首页”逻辑
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp + studioTopInset(), bottom = 16.dp + studioBottomInset())
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${log.method} ${log.url}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "时间：${formatTime(log.createdAt)} · 状态：${log.statusCode} · 耗时：${log.durationMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (log.error.isNotBlank()) Text("错误：${log.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                DetailBlock("最终 URL", log.finalUrl)
                if (log.redirectChain != "[]") DetailBlock("重定向链", log.redirectChain)
                DetailBlock("请求头", log.requestHeaders)
                if (log.requestBody.isNotBlank()) DetailBlock("请求体", log.requestBody)
                DetailBlock("响应头", log.responseHeaders)
                if (log.responseBody.isNotBlank()) DetailBlock("响应体", log.responseBody)
            }
        }
        GlassTopBar("HTTP 详情", onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun DetailBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
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
) {
    GlassCard {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = crash.name in selected, onCheckedChange = { onToggle(crash.name, it) })
            Column(Modifier.weight(1f).clickable(onClick = onExpand).padding(vertical = 10.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(crash.name, fontWeight = FontWeight.SemiBold)
                Text(formatTime(crash.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expanded == crash.name) {
                    Text(app.crashLogs.read(crash.name).take(6_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun formatTime(value: Long): String = DateFormat.getDateTimeInstance().format(Date(value))

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

    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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
