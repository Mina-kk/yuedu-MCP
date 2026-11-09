package com.mina.legadostudio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mina.legadostudio.domain.HttpLogExportPlan
import com.mina.legadostudio.domain.LogExportFormat
import com.mina.legadostudio.ui.theme.StudioSpacing

@Composable
fun LogExportDialog(
    kindLabel: String,
    dateKey: String,
    /** 当前筛选下已加载/已见的条数（HTTP 可能只是日窗口的一部分） */
    count: Int,
    /** 该日期全库真实条数（HTTP 为排除 cap:% 的 SQL 日总数）：> count 时不得把窗口数伪称全量 */
    dayTotal: Int = count,
    /** 导出范围口径提示（锚点筛选/抓包排除等），null = 不显示 */
    scopeHint: String? = null,
    /**
     * 可选导出范围（HTTP 才有：当日 / 全部日期 / 仅已勾选）。
     * null = 单一范围（操作日志固定当日），不显示范围 radio。
     */
    exportScopes: List<HttpLogExportPlan.ScopeOption>? = null,
    onDismiss: () -> Unit,
    onConfirm: (format: LogExportFormat, redact: Boolean, scopeKey: String) -> Unit,
) {
    var format by remember { mutableStateOf(LogExportFormat.TEXT) }
    var redact by remember { mutableStateOf(true) }
    // 默认范围由选项集决定：有勾选默认「仅已勾选」（按钮已明示勾选数，默认当日会误导出全库），
    // 无勾选默认当日。key 挂 exportScopes：切 Tab/日期/锚点使选项集变化时弹窗实例重组，
    // scopeKey 随之重取默认，不残留上一个快照的陈旧选择；弹窗存活期间用户可显式切换当日/全部日期。
    var scopeKey by remember(exportScopes) { mutableStateOf(HttpLogExportPlan.defaultScopeKey(exportScopes)) }

    // 弹窗存活期间范围集可能变化（如勾选清空使「仅已勾选」选项消失）：
    // 陈旧 scopeKey 不在当前选项里时回退默认，不让 selectedScope 变成 null 导致空文案/静默导出错范围
    val effectiveScopeKey = scopeKey.takeIf { k -> exportScopes == null || exportScopes.any { it.key == k } }
        ?: HttpLogExportPlan.defaultScopeKey(exportScopes)
    val selectedScope = exportScopes?.firstOrNull { it.key == effectiveScopeKey }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出$kindLabel") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(StudioSpacing.medium),
            ) {
                // 范围说明：单范围（操作日志）沿用旧文案；多范围（HTTP）按所选 scope 动态描述
                if (exportScopes == null) {
                    Text(
                        if (dayTotal > count) {
                            "将导出 $dateKey 的 $kindLabel：当日全库共 $dayTotal 条（当前列表已加载 $count 条，导出不受窗口限制）。生成文件后可直接分享到 QQ、微信等应用。"
                        } else {
                            "将导出 $dateKey 的 $count 条$kindLabel，生成文件后可直接分享到 QQ、微信等应用。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "将导出：${selectedScope?.label.orEmpty()}${selectedScope?.hint?.let { "（$it）" }.orEmpty()}。生成文件后可直接分享到 QQ、微信等应用。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("范围", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                        exportScopes.forEach { option ->
                            val on = option.key == effectiveScopeKey
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(StudioSpacing.medium))
                                    .selectable(selected = on, onClick = { scopeKey = option.key }, role = Role.RadioButton)
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = on, onClick = null)
                                Column(Modifier.weight(1f).padding(start = StudioSpacing.medium)) {
                                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        option.hint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                scopeHint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("格式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    LogExportFormat.entries.forEach { option ->
                        val on = option == format
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(StudioSpacing.medium))
                                .selectable(selected = on, onClick = { format = option }, role = Role.RadioButton)
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = on, onClick = null)
                            Column(Modifier.weight(1f).padding(start = StudioSpacing.medium)) {
                                Text(formatLabel(option), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    formatHint(option),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(StudioSpacing.medium))
                        .toggleable(value = redact, onValueChange = { redact = it }, role = Role.Switch)
                        .padding(vertical = StudioSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("脱敏", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "隐藏 Authorization、Cookie、Token、API Key 等敏感值",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = redact, onCheckedChange = null)
                }
                if (!redact) {
                    Text(
                        "未脱敏的日志可能包含登录凭据，请只分享给信任的人。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            // 「仅已勾选」范围只在 selectedCount>0 时才出现；选了它本身即证明有可导出的勾选集
            val confirmEnabled = dayTotal > 0 || count > 0 ||
                selectedScope?.key == HttpLogExportPlan.SCOPE_SELECTED
            TextButton(onClick = { onConfirm(format, redact, effectiveScopeKey) }, enabled = confirmEnabled) { Text("分享") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatLabel(format: LogExportFormat): String = when (format) {
    LogExportFormat.TEXT -> "纯文本 (.txt)"
    LogExportFormat.JSON -> "JSON (.json)"
    LogExportFormat.CSV -> "CSV 表格 (.csv)"
    LogExportFormat.HTML -> "网页 (.html)"
}

private fun formatHint(format: LogExportFormat): String = when (format) {
    LogExportFormat.TEXT -> "适合直接贴给 AI 或开发者"
    LogExportFormat.JSON -> "结构化数据，便于脚本处理"
    LogExportFormat.CSV -> "可用 Excel / WPS 打开"
    LogExportFormat.HTML -> "浏览器打开即是表格"
}
