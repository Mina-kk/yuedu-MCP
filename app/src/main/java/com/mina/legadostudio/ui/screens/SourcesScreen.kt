package com.mina.legadostudio.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.mina.legadostudio.domain.SourceCatalog
import com.mina.legadostudio.domain.SourceGroup
import com.mina.legadostudio.export.ReaderCatalog
import com.mina.legadostudio.export.SourceImportPayload
import com.mina.legadostudio.ui.theme.GlassCard
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.LocalStudioFullscreen
import com.mina.legadostudio.ui.theme.LocalStudioHaze
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
    var viewingProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    DisposableEffect(viewingProject != null) {
        fullscreen?.value = viewingProject != null
        onDispose { fullscreen?.value = false }
    }

    fun show(message: String) {
        notice = message
        toastNotice(context, message)
    }

    /** 分享书源 JSON：统一包成 JSON 数组（阅读导入只认数组），写入 cacheDir/exports 后经 FileProvider 授权调起系统分享面板（QQ/微信等可直接收发） */
    fun shareSource(project: ProjectEntity) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val payload = SourceImportPayload.arrayJson(project.sourceJson)
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val file = File(dir, "${exportFileName(project)}.json")
                    file.writeText(payload, Charsets.UTF_8)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TITLE, project.name.ifBlank { project.id })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "分享书源 JSON"))
                }
            }.onSuccess { show("已生成「${project.name.ifBlank { project.id }}」JSON，选择应用分享") }
                .onFailure { show("分享失败：${it.message.orEmpty()}") }
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

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 72.dp + studioTopInset(), bottom = 108.dp + studioBottomInset()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                GlassCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("本地书源库", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (projects.isEmpty()) "暂无记录。外部 MCP 客户端 调用 save_source 后将按站点域名分组列出。"
                            else "按站点域名分组，组内按保存时间倒序。同一 URL 默认只保留一条成品；下一轮修复时外部客户端才会追加新版本。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (notice.isNotBlank()) {
                            Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
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
                )
            }
        }
        GlassTopBar("书源", modifier = Modifier.align(Alignment.TopCenter))
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.name.ifBlank { "未命名书源" }, fontWeight = FontWeight.Medium)
                Text(project.siteUrl.ifBlank { project.id }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(project.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onDelete(project) }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onDetail(project) },
                modifier = Modifier.weight(1f).height(36.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text("详情", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            TextButton(
                onClick = {
                    onCopy(project)
                    copied = true
                },
                modifier = Modifier.weight(1f).height(36.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text(if (copied) "已复制" else "复制源", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            TextButton(
                onClick = { onImport(project) },
                modifier = Modifier.weight(1f).height(36.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text("导入至阅读", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            TextButton(
                onClick = { onShare(project) },
                modifier = Modifier.weight(1f).height(36.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text("分享JSON", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
                    .padding(top = 64.dp + studioTopInset(), bottom = 16.dp + studioBottomInset())
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
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

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            onCopy(project)
                            copied = true
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(if (copied) "已复制" else "复制源", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = { onImport(project) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("导入至阅读", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = { onShare(project) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("分享JSON", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }

                Text("书源规则 JSON：", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(12.dp)) {
                        Text(
                            text = prettyJson,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        GlassTopBar("书源详情", onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
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