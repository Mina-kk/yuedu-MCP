package com.mina.legadostudio.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mina.legadostudio.export.ReaderCatalog
import com.mina.legadostudio.export.ReaderImport

internal fun launchReaderImport(
    context: Context,
    sourceJson: String,
    onNeedChooser: (String, List<ReaderCatalog.App>) -> Unit,
    onNotice: (String) -> Unit,
) {
    if (sourceJson.isBlank()) {
        onNotice("书源内容为空")
        return
    }
    val apps = ReaderImport.resolve(context)
    when {
        apps.isEmpty() -> onNotice("未检测到可导入的阅读客户端")
        apps.size == 1 -> startReaderImport(context, sourceJson, apps.first().packageName, onNotice)
        else -> onNeedChooser(sourceJson, apps)
    }
}

internal fun startReaderImport(
    context: Context,
    sourceJson: String,
    packageName: String,
    onNotice: (String) -> Unit,
) {
    runCatching { ReaderImport.launch(context, sourceJson, packageName) }
        .onSuccess { onNotice("已唤起阅读导入界面") }
        .onFailure { onNotice("唤起阅读失败：${it.message ?: it.javaClass.simpleName}") }
}

internal fun toastNotice(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

@Composable
internal fun ReaderChooserDialog(
    json: String,
    apps: List<ReaderCatalog.App>,
    onDismiss: () -> Unit,
    onPick: (String, String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择阅读客户端") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                apps.forEach { app ->
                    TextButton(
                        onClick = { onPick(json, app.packageName) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(app.label)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
