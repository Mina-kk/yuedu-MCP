package com.mina.legadostudio.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.data.db.ProjectEntity
import com.mina.legadostudio.domain.CheckSourceRunner
import com.mina.legadostudio.domain.SourceCatalog
import com.mina.legadostudio.domain.SourceDedupeAnalyzer
import com.mina.legadostudio.domain.SourceFilterPolicy
import com.mina.legadostudio.domain.SourceGroup
import com.mina.legadostudio.export.ReaderCatalog
import com.mina.legadostudio.export.SourceImportPayload
import com.mina.legadostudio.mcp.McpSourceTools
import com.mina.legadostudio.network.HttpOrigin
import com.mina.legadostudio.ui.theme.GlassCard
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.LocalStudioFullscreen
import com.mina.legadostudio.ui.theme.LocalStudioHaze
import com.mina.legadostudio.ui.theme.StudioSpacing
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioTopInset
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun SourcesScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as StudioApplication
    val haze = LocalStudioHaze.current
    val fullscreen = LocalStudioFullscreen.current
    val projects by app.projects.observe().collectAsState(initial = emptyList())
    val groups = remember(projects) { SourceCatalog.groupByDomain(projects) }
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf("") }
    var chooser by remember { mutableStateOf<Pair<String, List<ReaderCatalog.App>>?>(null) }
    var pendingDelete by remember { mutableStateOf<ProjectEntity?>(null) }
    var pendingDeleteGroup by remember { mutableStateOf<SourceGroup?>(null) }
    var pendingClearAll by remember { mutableStateOf(false) }
    var viewingProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var analyzing by remember { mutableStateOf(false) }
    var excludeLogin by remember { mutableStateOf(false) }
    var excludeCaptcha by remember { mutableStateOf(false) }
    var excludeFailed by remember { mutableStateOf(false) }
    var excludeUnverified by remember { mutableStateOf(false) }
    var collapseIdentical by remember { mutableStateOf(false) }
    /**
     * check_source 证据按稳定 projectId 存（不按列表下标）：projectId → (被检测时的 sourceJson 指纹, 结果)。
     * 指纹 = 检测发起那一刻的 sourceJson 原文；内容变了（同 ID 又保存了新版本）即视为过期证据，
     * 喂给 SourceFilterPolicy 前必须用当前 sourceJson 逐条重新比对——防止「A 的结果套到 B 头上」。
     */
    var checkEvidence by remember { mutableStateOf<Map<String, Pair<String, Map<String, Any>>>>(emptyMap()) }
    var pendingSingleCheck by remember { mutableStateOf<ProjectEntity?>(null) }
    var checkingProjectId by remember { mutableStateOf<String?>(null) }
    var checkJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    DisposableEffect(viewingProject != null) {
        fullscreen?.value = viewingProject != null
        onDispose { fullscreen?.value = false }
    }

    // 离开书源页时取消还在跑的单源检测：晚到的结果不允许再写回（免得套到排序后别的项目上）
    DisposableEffect(Unit) {
        onDispose {
            checkJob?.cancel()
            checkJob = null
            checkingProjectId = null
        }
    }

    fun show(message: String) {
        notice = message
        toastNotice(context, message)
    }

    /** 分享书源 JSON：统一包成 JSON 数组（阅读导入只认数组），写入 cacheDir/exports 后经 FileProvider 授权调起系统分享面板（QQ/微信等可直接收发） */
    fun shareSource(project: ProjectEntity) {
        scope.launch {
            runCatching {
                // IO 内只做文件写盘与 intent 组装；startActivity 回主线程——
                // 部分 ROM（Android 8+）从后台线程调起分享面板会唤不出/丢授权
                val intent = withContext(Dispatchers.IO) {
                    val payload = SourceImportPayload.arrayJson(project.sourceJson)
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val file = File(dir, "${exportFileName(project)}.json")
                    file.writeText(payload, Charsets.UTF_8)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    Intent(Intent.ACTION_SEND).apply {
                        // MIME 用 text/plain 而非 application/json：主流社交 App（微信/QQ）对
                        // application/json 的 ACTION_SEND 支持差，分享面板可能直接无目标；
                        // text/plain 能落到「发送给好友/保存」入口，文件名仍带 .json 后缀
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, file.name)
                        putExtra(Intent.EXTRA_TITLE, project.name.ifBlank { project.id })
                        // ClipData 携带 content:// URI：Android 8+ 上仅靠 EXTRA_STREAM+GRANT flag
                        // 对 Direct Share/预览缩略图的目标进程不授权，ClipData 才覆盖全部目标
                        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                val chooser = Intent.createChooser(intent, "分享书源 JSON").apply {
                    if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) { context.startActivity(chooser) }
            }.onSuccess { show("已生成「${project.name.ifBlank { project.id }}」JSON，选择应用分享") }
                .onFailure { show("分享失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun copySource(project: ProjectEntity) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("bookSource", SourceImportPayload.arrayJson(project.sourceJson))
        clipboard?.setPrimaryClip(clip)
        notice = "已复制「${project.name.ifBlank { project.id }}」书源"
    }

    fun importSource(sourceJson: String) {
        launchReaderImport(context, sourceJson, onNeedChooser = { json, apps -> chooser = json to apps }, onNotice = ::show)
    }

    /** 分享一次过滤预览的「保留集」：必须是分析卡片当前算出的结果，绝不回退到全库。 */
    fun shareSourceArray(fileLabel: String, payload: String) {
        scope.launch {
            runCatching {
                val intent = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val file = File(dir, "$fileLabel.json")
                    file.writeText(payload, Charsets.UTF_8)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, file.name)
                        putExtra(Intent.EXTRA_TITLE, fileLabel)
                        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                val chooserIntent = Intent.createChooser(intent, "分享书源 JSON").apply {
                    if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) { context.startActivity(chooserIntent) }
            }.onSuccess { show("已生成导出结果 JSON（$fileLabel），选择应用分享") }
                .onFailure { show("分享失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun copyText(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("bookSource", text))
        notice = "已复制$label"
        toastNotice(context, "已复制$label")
    }

    /**
     * 过滤预览的导出决策管线（与 MCP export_checked_sources 同一套纯函数）：
     * SourceFilterPolicy.filter → exportArrayJson（逐字节原文 + 仅 export_one 零冲突折叠）。
     * 注意：本地分享/复制/导入走文件与回环服务，不经 MCP 内联返回，所以不调
     * McpSourceTools.buildExport 的整包字符串——那会带上 MCP 单次回包 11.5k 内联上限；
     * 这里用 exportArrayJson 无上限组装 JSON 数组，大库也能导。
     * 返回 Triple(payload?, 理由/报错, exportedCount)；payload 为 null 时一律禁止导出/导入。
     */
    fun computePreviewPayload(
        list: List<ProjectEntity>,
        checks: Map<Int, Map<String, Any>>,
        login: Boolean, captcha: Boolean, failed: Boolean, unverified: Boolean,
        collapse: Boolean,
    ): Triple<String?, String, Int> {
        val excludes = buildSet {
            if (login) add(SourceFilterPolicy.Exclude.LOGIN)
            if (captcha) add(SourceFilterPolicy.Exclude.CAPTCHA)
            if (failed) add(SourceFilterPolicy.Exclude.FAILED)
            if (unverified) add(SourceFilterPolicy.Exclude.UNVERIFIED)
        }
        val filter = SourceFilterPolicy.filter(list.map { it.sourceJson }, checks, excludes)
        if (filter.keptCount == 0) {
            val why = if (filter.total == 0) "书源库为空"
            else "当前过滤开关把全部 ${filter.total} 条都排除了（unverified/failed 依赖本页逐条「检测」出的 check_source 结果；未检测时按未验证处理）"
            return Triple(null, "已阻止空导入：$why。请放宽过滤条件。", 0)
        }
        val keptRows = filter.kept.map { d ->
            val p = list[d.index]
            McpSourceTools.ExportRow(p.id, p.siteUrl, p.sourceJson, p.updatedAt)
        }
        val (text, exportErr) = exportArrayJson(keptRows, collapseIdentical = collapse)
        if (exportErr != null) return Triple(null, exportErr, filter.keptCount)
        if (text == null) return Triple(null, "导出组装失败（无输出）", filter.keptCount)
        val exported = runCatching { com.google.gson.JsonParser.parseString(text).asJsonArray.size() }.getOrDefault(filter.keptCount)
        val collapsedNote = if (exported < filter.keptCount) "（已安全折叠完全相同副本 ${filter.keptCount - exported} 条）" else ""
        return Triple(text, "保留 ${filter.keptCount} 条，实际导出 $exported 条 JSON 数组$collapsedNote", exported)
    }

    /**
     * 单源实时检测：等价于 MCP check_source(auto=true, refresh=true)，全程联网实时、不使用任务快照；
     * 只在用户点对勾按钮时触发，绝不批量执行。结果按 projectId + 被检测时的 sourceJson 指纹存，
     * sourceJson 变了（同 ID 保存新版本）旧证据自动作废。
     */
    fun runSingleCheck(project: ProjectEntity) {
        pendingSingleCheck = null
        checkJob?.cancel() // 同时只允许一个单源检测；新的取消旧的
        val fingerprint = project.sourceJson // 发起时锁定原文指纹，结果写回时校验
        val job = scope.launch {
            checkingProjectId = project.id
            try {
                val result = CheckSourceRunner.run(
                    CheckSourceRunner.Request(source = fingerprint, refresh = true, auto = true),
                    runtime = app.runtime,
                    cacheFetch = null,
                    origin = HttpOrigin(sourceAnchor = SourceDedupeAnalyzer.canonicalUrlOf(project.siteUrl) ?: project.siteUrl, originKind = "check_source"),
                )
                // 写回前再确认该项目当前 sourceJson 仍是发起时那份；变了就把这次结果作废。
                // 必须查 repository 最新值（suspend 直查 DB），不能用闭包里 projects 的 Compose
                // 快照——执行期间列表可能已更新，旧快照会把已变内容的证据错误保留下来。
                val current = app.projects.get(project.id)
                if (current != null && current.sourceJson == fingerprint) {
                    checkEvidence = checkEvidence + (project.id to (fingerprint to result))
                    val summary = (result["verdict"] as? Map<*, *>)?.get("summary")?.toString() ?: "?"
                    val note = if (summary == "full") "（full=本次检测通过，非永久可用保证）" else ""
                    show("「${project.name.ifBlank { project.id }}」检测完成：$summary$note；可在分析面板开 excludeFailed/excludeUnverified 使用该结果")
                } else {
                    show("「${project.name.ifBlank { project.id }}」检测完成，但该书源内容已变化（结果作废，未写入证据）")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 取消不吞：上浮结束协程，也不写回证据
            } catch (t: Exception) {
                // 只兜业务异常；OOM 等 fatal Error（Throwable 非 Exception）不吞，任其崩溃上报
                show("检测失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                if (checkingProjectId == project.id) checkingProjectId = null
            }
        }
        checkJob = job
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier),
            contentPadding = PaddingValues(start = StudioSpacing.screen, end = StudioSpacing.screen, top = StudioSpacing.screenTopBase + studioTopInset(), bottom = StudioSpacing.screenBottomBase + studioBottomInset()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                GlassCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("本地书源库", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (projects.isEmpty()) "暂无记录。外部 MCP 客户端 调用 save_source 后将按站点域名分组列出。"
                            else "按站点域名分组，组内按保存时间倒序；支持一键删除整组与一键清空（仅删本地库记录，不影响已导入阅读客户端的书源）。顶栏「分析/过滤」做只读静态分析与过滤预览。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (notice.isNotBlank()) {
                            Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (analyzing && projects.isNotEmpty()) {
                item {
                    SourceAnalysisCard(
                        projects = projects,
                        checkResults = checkEvidence,
                        excludeLogin = excludeLogin, onExcludeLogin = { excludeLogin = it },
                        excludeCaptcha = excludeCaptcha, onExcludeCaptcha = { excludeCaptcha = it },
                        excludeFailed = excludeFailed, onExcludeFailed = { excludeFailed = it },
                        excludeUnverified = excludeUnverified, onExcludeUnverified = { excludeUnverified = it },
                        collapseIdentical = collapseIdentical, onCollapseIdentical = { collapseIdentical = it },
                        checkingProjectId = checkingProjectId,
                        checkingJobActive = checkingProjectId != null, // checkingProjectId 是状态，job 结束会在 finally 清掉它
                        onRequestCheck = { pendingSingleCheck = it },
                        onCancelCheck = { checkJob?.cancel() },
                        computePayload = { list, checks ->
                            computePreviewPayload(list, checks, excludeLogin, excludeCaptcha, excludeFailed, excludeUnverified, collapseIdentical)
                        },
                        onCopy = ::copyText,
                        onShare = ::shareSourceArray,
                        onImport = { importSource(it) },
                    )
                }
            }
            items(groups, key = { it.domain }) { group ->
                DomainSourceGroup(
                    group = group,
                    expanded = group.domain in expanded,
                    onToggle = {
                        expanded = if (group.domain in expanded) expanded - group.domain else expanded + group.domain
                    },
                    onDetail = { viewingProject = it },
                    onCopy = ::copySource,
                    onImport = { importSource(it.sourceJson) },
                    onShare = ::shareSource,
                    onDelete = { pendingDelete = it },
                    onDeleteGroup = { pendingDeleteGroup = it },
                )
            }
        }
        GlassTopBar(
            "书源",
            actions = {
                if (projects.isNotEmpty()) {
                    TextButton(onClick = { analyzing = !analyzing }) {
                        Text(if (analyzing) "收起分析" else "分析/过滤")
                    }
                    TextButton(onClick = { pendingClearAll = true }) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    chooser?.let { (json, apps) ->
        ReaderChooserDialog(
            json = json,
            apps = apps,
            onDismiss = { chooser = null },
            onPick = { sourceJson, packageName ->
                chooser = null
                startReaderImport(context, sourceJson, packageName, ::show)
            },
        )
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除书源") },
            text = { Text("将从工坊本地库移除「${project.name.ifBlank { project.id }}」（${formatTime(project.updatedAt)}），不影响同域名其他版本及已导入阅读客户端的副本。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = project
                    pendingDelete = null
                    scope.launch {
                        runCatching { app.projects.delete(listOf(target.id)) }
                            .onSuccess { show("已删除 1 条书源") }
                            .onFailure { show(it.message.orEmpty()) }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    pendingDeleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGroup = null },
            title = { Text("一键删除站点书源") },
            text = { Text("将彻底删除域名「${group.domain}」下的全部 ${group.items.size} 个版本记录。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = group.items.map { it.id }
                    val domain = group.domain
                    pendingDeleteGroup = null
                    scope.launch {
                        runCatching { app.projects.delete(ids) }
                            .onSuccess { show("已删除 $domain 下全部 ${ids.size} 条记录") }
                            .onFailure { show(it.message.orEmpty()) }
                    }
                }) { Text("一键删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteGroup = null }) { Text("取消") } },
        )
    }

    if (pendingClearAll) {
        AlertDialog(
            onDismissRequest = { pendingClearAll = false },
            title = { Text("清空书源库") },
            text = { Text("将清空本地工坊保存的全部 ${projects.size} 条书源记录。已导入阅读客户端的书源不受影响。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingClearAll = false
                    val allIds = projects.map { it.id }
                    scope.launch {
                        runCatching { app.projects.delete(allIds) }
                            .onSuccess { show("已清空本地书源库") }
                            .onFailure { show(it.message.orEmpty()) }
                    }
                }) { Text("全部清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingClearAll = false }) { Text("取消") } },
        )
    }

    pendingSingleCheck?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingSingleCheck = null },
            title = { Text("对该书源执行实时检测？") },
            text = {
                Text(
                    "将对「${project.name.ifBlank { project.id }}」（${project.siteUrl.ifBlank { "无站点 URL" }}）执行一次 check_source(auto=true, refresh=true)：\n" +
                        "· 联网执行该书源自身的 JS 规则与搜索/详情/目录/正文探测（不使用缓存快照）\n" +
                        "· 仅检测这一条书源，不会批量执行；结果留在本页内存中供 excludeFailed/excludeUnverified 判定\n" +
                        "· 不会删除任何数据"
                )
            },
            confirmButton = {
                TextButton(onClick = { runSingleCheck(project) }) { Text("开始检测") }
            },
            dismissButton = { TextButton(onClick = { pendingSingleCheck = null }) { Text("取消") } },
        )
    }

    // 书源详情视图（JSON 格式展示）
    viewingProject?.let { project ->
        SourceDetailView(
            project = project,
            onBack = { viewingProject = null },
            onCopy = ::copySource,
            onImport = { importSource(it.sourceJson) },
            onShare = ::shareSource,
        )
    }
}

@Composable
private fun DomainSourceGroup(
    group: SourceGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDetail: (ProjectEntity) -> Unit,
    onCopy: (ProjectEntity) -> Unit,
    onImport: (ProjectEntity) -> Unit,
    onShare: (ProjectEntity) -> Unit,
    onDelete: (ProjectEntity) -> Unit,
    onDeleteGroup: (SourceGroup) -> Unit,
) {
    GlassCard {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(group.domain, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${group.items.size} 条记录 · 最近 ${formatTime(group.latest.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { onDeleteGroup(group) },
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除整组",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider()
                    group.items.forEachIndexed { index, project ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        SourceVersionRow(
                            project = project,
                            onDetail = onDetail,
                            onCopy = onCopy,
                            onImport = onImport,
                            onShare = onShare,
                            onDelete = onDelete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceVersionRow(
    project: ProjectEntity,
    onDetail: (ProjectEntity) -> Unit,
    onCopy: (ProjectEntity) -> Unit,
    onImport: (ProjectEntity) -> Unit,
    onShare: (ProjectEntity) -> Unit,
    onDelete: (ProjectEntity) -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onDetail(project) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(project.name.ifBlank { "未命名书源" }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text(project.siteUrl.ifBlank { project.id }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(project.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
            }
            IconButton(
                onClick = { onDelete(project) },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除书源",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                )
            }
        }

        // 4 个核心功能按钮：2x2 对称网格，整齐大方
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onDetail(project) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("详情", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                TextButton(
                    onClick = {
                        onCopy(project)
                        copied = true
                    },
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(if (copied) "已复制" else "复制源", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onImport(project) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("导入至阅读", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                TextButton(
                    onClick = { onShare(project) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("分享JSON", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 制作成功的书源详情页：格式化 JSON 展示、支持选择复制与快速导入
 */
@Composable
private fun SourceDetailView(
    project: ProjectEntity,
    onBack: () -> Unit,
    onCopy: (ProjectEntity) -> Unit,
    onImport: (ProjectEntity) -> Unit,
    onShare: (ProjectEntity) -> Unit,
) {
    BackHandler(onBack = onBack)
    val prettyJson = remember(project.sourceJson) { formatJson(project.sourceJson) }
    var copied by remember { mutableStateOf(false) }
    var wrapJson by remember { mutableStateOf(true) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp + studioTopInset(), bottom = StudioSpacing.card + studioBottomInset())
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = StudioSpacing.card),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    project.name.ifBlank { "未命名书源" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "域名：${SourceCatalog.domainOf(project.siteUrl)} · 站点：${project.siteUrl.ifBlank { "无" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "更新时间：${formatTime(project.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StudioSpacing.medium)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                onCopy(project)
                                copied = true
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(if (copied) "已复制" else "复制源", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = { onImport(project) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("导入至阅读", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(
                        onClick = { onShare(project) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("分享JSON", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("书源规则 JSON：", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { wrapJson = !wrapJson }) {
                        Text(if (wrapJson) "自动换行: 开" else "自动换行: 关", style = MaterialTheme.typography.labelSmall)
                    }
                }
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    if (wrapJson) {
                        Box(Modifier.fillMaxWidth().padding(StudioSpacing.large)) {
                            Text(
                                text = prettyJson,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(StudioSpacing.large),
                        ) {
                            Text(
                                text = prettyJson,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
        GlassTopBar(
            "书源详情",
            onBack = onBack,
            actions = {
                TextButton(onClick = { wrapJson = !wrapJson }) {
                    Text(if (wrapJson) "换行" else "单行")
                }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

private fun formatJson(json: String): String {
    return runCatching {
        val trimmed = json.trim()
        if (trimmed.startsWith("{")) {
            JSONObject(trimmed).toString(2)
        } else if (trimmed.startsWith("[")) {
            JSONArray(trimmed).toString(2)
        } else {
            json
        }
    }.getOrDefault(json)
}

/** 分享用文件名：优先书源名，剥离文件系统非法字符，过长截断；空名退化为 id */
private fun exportFileName(project: ProjectEntity): String {
    val raw = project.name.ifBlank { project.id }.ifBlank { "booksource" }
    val safe = raw.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1f]"), "_").trim().trim('.').take(64)
    return safe.ifBlank { "booksource" }
}

private fun formatTime(value: Long): String = DateFormat.getDateTimeInstance().format(Date(value))
// ---------------------------------------------------------------------------
// 「分析/过滤」卡片：全静态分析（不联网、不执行 JS），唯一联网入口是逐条「检测」
// （等价 check_source auto=true refresh=true）。导出结果恒为 JSON 数组
// （阅读导入只认数组），且只允许导出「预览保留集」，绝不回退到全库、绝不删数据。
// ---------------------------------------------------------------------------

private data class FilterPreviewRow(
    val project: ProjectEntity?,
    val decision: SourceFilterPolicy.Decision,
)

/** 决策行的人读标题：优先 bookSourceName，缺了回退 name/siteUrl/id/index。 */
private fun previewRowTitle(row: FilterPreviewRow): String {
    row.project?.let { return it.name.ifBlank { it.siteUrl.ifBlank { it.id } } }
    return row.decision.source.get("bookSourceName")?.takeIf { it.isJsonPrimitive }?.asString
        ?: "#${row.decision.index}"
}

/**
 * 本地导出组装（纯函数，JVM 可测；无 MCP 单次回包上限）：
 * - 保持 kept 顺序、每条 sourceJson **逐字节原文**——按已校验的原文直接拼成 JSON 数组，
 *   不重序列化（重序列化会丢键序/空白、长串可能被截断改形）；
 * - 任一成员 JSON 无法解析为对象立即报错（不产伪可用空对象）；
 * - collapse=true 时只折叠 SourceDedupeAnalyzer 判定为 action=export_one 的簇
 *   （安全桶+结构全等+零冲突），action=none 冲突/不确定簇一律原样保留；
 * - 恒返回 JSON 数组（阅读导入只认数组），无大小上限。
 */
internal fun exportArrayJson(kept: List<McpSourceTools.ExportRow>, collapseIdentical: Boolean): Pair<String?, String?> {
    if (kept.isEmpty()) return null to "没有可导出的书源（过滤后为空）"
    // 逐条校验是合法 JSON 对象后才允许进结果；校验用解析树，但输出用原文。
    val parsed = mutableListOf<Pair<McpSourceTools.ExportRow, com.google.gson.JsonObject>>()
    kept.forEachIndexed { i, row ->
        val el = runCatching { com.google.gson.JsonParser.parseString(row.sourceJson) }.getOrNull()
        if (el == null || !el.isJsonObject) {
            return null to "导出中止：kept[$i]（projectId=${row.projectId}）的 sourceJson 不是合法 JSON 对象，不能伪装成可用书源导出"
        }
        parsed += row to el.asJsonObject
    }
    var rows: List<Pair<McpSourceTools.ExportRow, com.google.gson.JsonObject>> = parsed
    if (collapseIdentical) {
        val report = SourceDedupeAnalyzer.analyzeRows(parsed.map { (r, _) ->
            SourceDedupeAnalyzer.Row(r.projectId, r.siteUrl, r.sourceJson, r.updatedAt)
        })
        val keepIds = report.clusters.filter { it.action == SourceDedupeAnalyzer.ACTION_EXPORT_ONE }
            .associate { it.clusterKey to it.keepProjectId }
        val clusterKeyOf = report.items.associate { it.projectId to it.clusterKey }
        rows = parsed.filter { (r, _) ->
            val key = clusterKeyOf[r.projectId]
            val keep = key?.let { keepIds[it] }
            keep == null || keep == r.projectId
        }
    }
    // 逐字节原文拼接：按 kept 顺序用每条 sourceJson 原文拼数组，导入端拿到入库原文（不改键序/不截断）。
    return rows.joinToString(prefix = "[", postfix = "]", separator = ",") { it.first.sourceJson } to null
}

/**
 * 证据隔离解析（纯函数，JVM 可测）：把「projectId → (sourceJson 指纹, check 结果)」
 * 映射到当前 projects 列表的下标 Map。
 * - 键按 projectId 比对，不按位置：列表排序/新增/删除不会让 A 的结果串到 B；
 * - 指纹按 sourceJson 原文逐字节比对：同 ID 保存了新版本（内容变了）→ 旧证据 stale，不进 byIndex；
 * - projectId 已不在库里（被删）→ 计入 dropped 但不算 stale。
 * 返回 (byIndex 当前有效证据, staleCount 内容已变的条数)。
 */
internal fun resolveCheckEvidence(
    projects: List<ProjectEntity>,
    evidence: Map<String, Pair<String, Map<String, Any>>>,
): ResolvedEvidence {
    val byIndex = mutableMapOf<Int, Map<String, Any>>()
    var stale = 0
    val byId = projects.withIndex().associate { it.value.id to it.index }
    for ((projectId, entry) in evidence) {
        val (fingerprint, result) = entry
        val index = byId[projectId] ?: continue // 已删除：静默丢
        if (projects[index].sourceJson == fingerprint) byIndex[index] = result else stale++
    }
    return ResolvedEvidence(byIndex, stale)
}

internal class ResolvedEvidence(
    val byIndex: Map<Int, Map<String, Any>>,
    val staleCount: Int,
)

@Composable
private fun SourceAnalysisCard(
    projects: List<ProjectEntity>,
    /** projectId → (发起检测时的 sourceJson 指纹, check_source 结果)；指纹不匹配=过期证据，不复用 */
    checkResults: Map<String, Pair<String, Map<String, Any>>>,
    excludeLogin: Boolean, onExcludeLogin: (Boolean) -> Unit,
    excludeCaptcha: Boolean, onExcludeCaptcha: (Boolean) -> Unit,
    excludeFailed: Boolean, onExcludeFailed: (Boolean) -> Unit,
    excludeUnverified: Boolean, onExcludeUnverified: (Boolean) -> Unit,
    collapseIdentical: Boolean, onCollapseIdentical: (Boolean) -> Unit,
    checkingProjectId: String?,
    checkingJobActive: Boolean,
    onRequestCheck: (ProjectEntity) -> Unit,
    onCancelCheck: () -> Unit,
    computePayload: (List<ProjectEntity>, Map<Int, Map<String, Any>>) -> Triple<String?, String, Int>,
    onCopy: (String, String) -> Unit,
    onShare: (String, String) -> Unit,
    onImport: (String) -> Unit,
) {
    val dedupe = remember(projects) { SourceDedupeAnalyzer.analyze(projects) }
    val excludes = remember(excludeLogin, excludeCaptcha, excludeFailed, excludeUnverified) {
        buildSet {
            if (excludeLogin) add(SourceFilterPolicy.Exclude.LOGIN)
            if (excludeCaptcha) add(SourceFilterPolicy.Exclude.CAPTCHA)
            if (excludeFailed) add(SourceFilterPolicy.Exclude.FAILED)
            if (excludeUnverified) add(SourceFilterPolicy.Exclude.UNVERIFIED)
        }
    }
    /**
     * 证据隔离：checkResults 按稳定 projectId 存 + 发起时的 sourceJson 指纹；
     * 这里把「指纹与当前 sourceJson 一致」的证据映射到当前 projects 下标喂给 filter，
     * 指纹不匹配（同 ID 保存了新版本）的一律丢弃——绝不把 A 的检测结果套到 B/新内容上。
     */
    val resolved = remember(projects, checkResults) { resolveCheckEvidence(projects, checkResults) }
    val indexCheckResults = resolved.byIndex
    val staleCount = resolved.staleCount
    val hasCheckEvidence = indexCheckResults.isNotEmpty()
    val filter = remember(projects, indexCheckResults, excludes) {
        SourceFilterPolicy.filter(projects.map { it.sourceJson }, indexCheckResults, excludes)
    }
    val rows = remember(projects, filter) {
        val decisionByIndex = (filter.kept + filter.excluded).associateBy { it.index }
        projects.mapIndexedNotNull { index, p ->
            decisionByIndex[index]?.let { FilterPreviewRow(p, it) }
        }
    }
    val exportPreview = remember(projects, indexCheckResults, excludes, collapseIdentical) {
        computePayload(projects, indexCheckResults)
    }
    var showAllDecisions by remember { mutableStateOf(false) }

    GlassCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("书源静态分析（不联网 · 不执行 JS · 不删除数据）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val s = dedupe.stats
            Text(
                "共 ${s.totalProjects} 条：可解析 ${s.parseableJson} / 无法解析 ${s.unparseableJson} / 缺 bookSourceUrl ${s.missingSourceUrl}；" +
                    "重复候选簇 ${s.clusterCount}（完全一致 ${s.identicalClusters}）· 冲突 ${s.conflictCount} 项 · 可安全折叠副本 ${s.redundantCopies} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (dedupe.conflicts.isNotEmpty()) {
                Text(
                    "冲突（永远保留，待人工确认，绝不自动折叠）：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                dedupe.conflicts.forEach { c ->
                    Text(
                        "· [${c.kind}] ${c.projectIds.joinToString()}：${c.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    )
                }
            }
            if (dedupe.clusters.isNotEmpty()) {
                Text("重复簇（action=none 的冲突/不确定簇不参与折叠）：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                dedupe.clusters.forEach { c ->
                    Text(
                        "· ${if (c.action == SourceDedupeAnalyzer.ACTION_EXPORT_ONE) "export_one → 保留 ${c.keepProjectId}" else "none"}（${c.memberIds.size} 条）${c.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val manualReview = dedupe.clusters.filter { it.action == SourceDedupeAnalyzer.ACTION_NONE }
            if (manualReview.isNotEmpty()) {
                Text(
                    "待人工确认 ${manualReview.size} 簇（${manualReview.sumOf { it.memberIds.size }} 条）：同桶但内容/身份不一致，预览中全部保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            HorizontalDivider()
            Text("排除开关（只影响本次导出/导入预览，不改库；默认全关）：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilterSwitchRow("需要登录", "loginUrl/loginUi 已配置（字段判定）", excludeLogin, onExcludeLogin)
            FilterSwitchRow("验证码/JS 挑战", "需 check_source 证据；loginUrl 指向验证页可字段判定", excludeCaptcha, onExcludeCaptcha)
            FilterSwitchRow("检测失败", "仅按 check_source 阶段结果；无证据不声称已验", excludeFailed, onExcludeFailed)
            FilterSwitchRow("未实测/未验证", "check_source 关键阶段未跑齐即排除，可把全库排空", excludeUnverified, onExcludeUnverified)
            if (!hasCheckEvidence && (excludeCaptcha || excludeFailed)) {
                Text(
                    "注意：当前没有 check_source 证据，该开关只能靠书源字段判定，不能声称已验证；要实测请在下方逐条点「检测」（check_source auto=true, refresh=true）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (staleCount > 0) {
                Text(
                    "$staleCount 条 check_source 证据已过期（对应书源保存了新版本，内容指纹变了），已自动作废、未参与本次判定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            FilterSwitchRow("安全折叠完全相同副本", "仅折叠 action=export_one 且零冲突的簇；冲突/不确定永远保留。默认关闭", collapseIdentical, onCollapseIdentical)

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "预览：保留 ${filter.keptCount} / 排除 ${filter.excludedCount}（共 ${filter.total}）" +
                        if (!hasCheckEvidence) " · 无 check_source 证据" else " · 已附 ${indexCheckResults.size} 条有效证据",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (checkingJobActive && checkingProjectId != null) {
                    CircularProgressIndicator(Modifier.height(14.dp), strokeWidth = 2.dp)
                    TextButton(onClick = onCancelCheck, contentPadding = PaddingValues(0.dp)) {
                        Text("取消检测", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            val shownRows = if (showAllDecisions) rows else rows.take(20)
            shownRows.forEach { row ->
                DecisionRow(
                    row = row,
                    checking = row.project?.id != null && row.project.id == checkingProjectId,
                    onRequestCheck = onRequestCheck,
                )
            }
            if (rows.size > shownRows.size) {
                TextButton(onClick = { showAllDecisions = true }) { Text("展开全部 ${rows.size} 条决策") }
            }

            HorizontalDivider()
            val (payload, payloadMessage, exportedCount) = exportPreview
            Text(
                payloadMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (payload == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { payload?.let { onCopy("导出结果（${exportedCount} 条书源数组）", it) } },
                    enabled = payload != null,
                    modifier = Modifier.weight(1f),
                ) { Text("复制结果", maxLines = 1) }
                TextButton(
                    onClick = { payload?.let { onShare("sources_export_filtered", it) } },
                    enabled = payload != null,
                    modifier = Modifier.weight(1f),
                ) { Text("分享文件", maxLines = 1) }
                TextButton(
                    onClick = { payload?.let(onImport) },
                    enabled = payload != null,
                    modifier = Modifier.weight(1f),
                ) { Text("导入阅读", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun FilterSwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun DecisionRow(
    row: FilterPreviewRow,
    checking: Boolean,
    onRequestCheck: (ProjectEntity) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (row.decision.keep) "✓ 保留" else "✗ 排除",
                color = if (row.decision.keep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                previewRowTitle(row),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.project?.let { p ->
                if (checking) {
                    CircularProgressIndicator(Modifier.height(16.dp).padding(end = 2.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { onRequestCheck(p) }, modifier = Modifier.height(24.dp)) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "对该书源执行 check_source 实时检测（联网）",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.height(16.dp),
                        )
                    }
                }
            }
        }
        if (row.decision.reasons.isNotEmpty()) {
            row.decision.reasons.forEach { reason ->
                Text("  · $reason", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (row.decision.keep) {
            Text("  · 未命中任何已开启的排除条件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
