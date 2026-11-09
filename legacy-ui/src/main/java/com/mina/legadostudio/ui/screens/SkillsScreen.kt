package com.mina.legadostudio.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.skills.SkillRepository
import com.mina.legadostudio.ui.theme.GlassCard
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.LocalStudioHaze
import com.mina.legadostudio.ui.theme.StudioSpacing
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioFieldColors
import com.mina.legadostudio.ui.theme.studioTopInset
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val NEW_SKILL_TEMPLATE = """
---
name: 自定义技能
---

# 自定义技能
""".trimIndent()

@Composable
fun SkillsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as StudioApplication
    val scope = rememberCoroutineScope()
    val haze = LocalStudioHaze.current
    var skills by remember { mutableStateOf(app.skills.list()) }
    var notice by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var newId by remember { mutableStateOf("") }
    var newMarkdown by remember { mutableStateOf(NEW_SKILL_TEMPLATE) }
    var pendingDelete by remember { mutableStateOf<SkillRepository.SkillInfo?>(null) }
    var opened by remember { mutableStateOf<SkillRepository.SkillInfo?>(null) }
    var editorText by remember { mutableStateOf("") }
    var pendingExportId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        skills = app.skills.list()
    }

    fun show(message: String) {
        notice = message
        toastNotice(context, message)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = queryDisplayName(context, uri).ifBlank { "skill.md" }
                    context.contentResolver.openInputStream(uri)?.use { app.skills.importPackage(name, it) }
                        ?: error("无法读取文件")
                }
            }.onSuccess { info ->
                reload()
                show("已导入 ${info.name}")
            }.onFailure { show(it.message.orEmpty()) }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { app.skills.exportPackage(id, it) }
                        ?: error("无法写入文件")
                }
            }.onSuccess { show("已导出") }
                .onFailure { show(it.message.orEmpty()) }
        }
    }

    fun exportSkill(id: String) {
        pendingExportId = id
        exportLauncher.launch("$id.zip")
    }

    fun openSkill(info: SkillRepository.SkillInfo) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { app.skills.read(info.id) }
            }.onSuccess { markdown ->
                opened = info
                editorText = markdown
            }.onFailure { show(it.message.orEmpty()) }
        }
    }

    if (opened != null) {
        val skill = opened!!
        BackHandler { opened = null }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = 64.dp + studioTopInset()).padding(horizontal = StudioSpacing.screen), verticalArrangement = Arrangement.spacedBy(StudioSpacing.medium)) {
                Text(if (skill.builtIn) "内置技能，只读" else "自定义技能", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (skill.builtIn) {
                    Text(
                        editorText,
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    OutlinedTextField(
                        editorText,
                        { editorText = it },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        colors = studioFieldColors(),
                    )
                }
            }
            GlassTopBar(
                skill.name,
                onBack = { opened = null },
                actions = {
                    if (!skill.builtIn) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { app.skills.save(skill.id, editorText) }
                                }.onSuccess {
                                    reload()
                                    show("已保存")
                                }.onFailure { show(it.message.orEmpty()) }
                            }
                        }) { Text("保存") }
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        return
    }

    val builtIn = skills.filter { it.builtIn }
    val custom = skills.filterNot { it.builtIn }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier),
            contentPadding = PaddingValues(start = StudioSpacing.screen, end = StudioSpacing.screen, top = StudioSpacing.screenTopBase + studioTopInset(), bottom = StudioSpacing.screenBottomBase + studioBottomInset()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                GlassCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("技能库", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "内置技能固定只读，可查看与导出。自定义技能支持新增、导入、导出和删除。禁用的技能不会通过 MCP 提供给外部 Agent。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (notice.isNotBlank()) {
                            Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Text("内置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 6.dp, top = 4.dp)) }
            items(builtIn, key = { "in-${it.id}" }) { info ->
                SkillRow(
                    info,
                    onView = { openSkill(info) },
                    onExport = { exportSkill(info.id) },
                    onDelete = null,
                    onToggle = { enabled -> app.skills.setEnabled(info.id, enabled); reload() },
                )
            }
            item { Text("自定义", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) }
            if (custom.isEmpty()) {
                item {
                    Text("暂无自定义技能。可新增或导入 .md / .zip。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                }
            } else {
                items(custom, key = { "cu-${it.id}" }) { info ->
                    SkillRow(
                        info,
                        onView = { openSkill(info) },
                        onExport = { exportSkill(info.id) },
                        onDelete = { pendingDelete = info },
                        onToggle = { enabled -> app.skills.setEnabled(info.id, enabled); reload() },
                    )
                }
            }
        }
        GlassTopBar(
            "技能",
            actions = {
                TextButton(onClick = {
                    newId = ""
                    newMarkdown = NEW_SKILL_TEMPLATE
                    creating = true
                }) { Text("新增") }
                TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("导入") }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新增技能") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(newId, { newId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ID") }, singleLine = true, colors = studioFieldColors())
                    OutlinedTextField(
                        newMarkdown,
                        { newMarkdown = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("SKILL.md") },
                        minLines = 8,
                        colors = studioFieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = newId.trim()
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { app.skills.save(id, newMarkdown) }
                        }.onSuccess {
                            creating = false
                            reload()
                            show("已新增")
                        }.onFailure { show(it.message.orEmpty()) }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }
    pendingDelete?.let { info ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除技能") },
            text = { Text("将删除自定义技能「${info.name}」。内置技能不可删除。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = info
                    pendingDelete = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                require(app.skills.delete(target.id)) { "Skill 不存在或不是自定义技能" }
                            }
                        }.onSuccess {
                            reload()
                            show("已删除")
                        }.onFailure { show(it.message.orEmpty()) }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SkillRow(
    info: SkillRepository.SkillInfo,
    onView: () -> Unit,
    onExport: () -> Unit,
    onDelete: (() -> Unit)?,
    onToggle: (Boolean) -> Unit,
) {
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(info.name, fontWeight = FontWeight.SemiBold)
                    Text(info.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = info.enabled, onCheckedChange = onToggle)
            }
            if (!info.enabled) {
                Text("已禁用：MCP 不再提供此技能", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onView) { Text("查看") }
                TextButton(onClick = onExport) { Text("导出") }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index).orEmpty()
    }
    return uri.lastPathSegment.orEmpty()
}
