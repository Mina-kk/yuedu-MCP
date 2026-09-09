package com.mina.legadostudio.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.device.DeviceReadiness
import com.mina.legadostudio.mcp.McpAccess
import com.mina.legadostudio.mcp.McpConfigStore
import com.mina.legadostudio.network.RuntimeConfigStore
import com.mina.legadostudio.service.McpService
import com.mina.legadostudio.ui.theme.GlassCard
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.LocalStudioHaze
import com.mina.legadostudio.ui.theme.StudioSegmentedControl
import com.mina.legadostudio.ui.theme.ThemeMode
import com.mina.legadostudio.ui.theme.TonalIconBox
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioFieldColors
import com.mina.legadostudio.ui.theme.studioTopInset
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun McpStatusScreen(onOpenVerification: () -> Unit = {}, themeMode: ThemeMode = ThemeMode.SYSTEM, onThemeModeChange: (ThemeMode) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as StudioApplication
    val haze = LocalStudioHaze.current
    val sessions by app.database.dao().observeVerificationSessions().collectAsState(initial = emptyList())
    val waiting = sessions.filter { it.status == "WAITING" }
    var status by remember { mutableStateOf(McpService.status(context)) }
    var portText by remember { mutableStateOf((status["port"] ?: McpConfigStore.DEFAULT_PORT).toString()) }
    var tokenRequired by remember { mutableStateOf(status["tokenRequired"] == true) }
    var token by remember { mutableStateOf(status["token"]?.toString().orEmpty()) }
    var configMessage by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf("") }
    var healthOk by remember { mutableStateOf(false) }
    var lanEndpoints by remember { mutableStateOf(emptyList<String>()) }
    var readiness by remember { mutableStateOf(DeviceReadiness(context).inspect((status["port"] as? Int) ?: McpConfigStore.DEFAULT_PORT, status["running"] == true)) }
    val scope = rememberCoroutineScope()
    suspend fun refresh(skipHealth: Boolean = false) {
        val snapshot = withContext(Dispatchers.IO) {
            val next = McpService.status(context)
            val port = (next["port"] as? Int) ?: McpConfigStore.DEFAULT_PORT
            val running = next["running"] == true
            Triple(next, DeviceReadiness(context).inspect(port, running), running && !skipHealth && DeviceReadiness(context).checkMcpHealth(port)) to McpAccess.lanEndpoints(port)
        }
        status = snapshot.first.first
        readiness = snapshot.first.second
        healthOk = snapshot.first.third
        lanEndpoints = snapshot.second
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scope.launch { refresh() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { refresh() } }
    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(5_000)
        }
    }
    val running = status["running"] == true
    val port = (status["port"] as? Int) ?: McpConfigStore.DEFAULT_PORT
    val endpoint = (status["endpoints"] as? List<*>)?.firstOrNull()?.toString().orEmpty()
        .ifBlank { McpAccess.endpoints(port).first() }
    val headerLine = McpAccess.tokenHeaderLine(token)
    fun copy(label: String, value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        copied = "已复制 $label"
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 72.dp + studioTopInset(), bottom = 108.dp + studioBottomInset()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (waiting.isNotEmpty()) item {
                val cs = MaterialTheme.colorScheme
                GlassCard(fill = cs.tertiaryContainer.copy(alpha = 0.7f), onClick = onOpenVerification) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("站点验证待处理 · ${waiting.first().domain}", fontWeight = FontWeight.Bold)
                        Text("在应用内完成验证后即可重试原请求。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            item {
                val cs = MaterialTheme.colorScheme
                GlassCard(fill = com.mina.legadostudio.ui.theme.glassFill()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TonalIconBox(
                                if (running) Icons.Outlined.CheckCircle else Icons.Outlined.PowerSettingsNew,
                                if (running) cs.primary else cs.surfaceVariant,
                                if (running) cs.onPrimary else cs.onSurfaceVariant,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(if (running) "服务运行中" else "服务已停止", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (running) "活跃会话：${status["clientCount"] ?: 0} · 累计 ${status["sessionTotal"] ?: 0} · 最近访问：${(status["lastAccessAt"] as? Long)?.takeIf { it > 0 }?.let { java.text.DateFormat.getTimeInstance().format(it) } ?: "暂无"}"
                                    else "可预先复制 MCP 与鉴权请求头；服务启动后即可接入",
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                )
                            }
                            Switch(checked = running, onCheckedChange = { enabled ->
                                if (!enabled) {
                                    McpService.stop(context)
                                    status = status + ("running" to false)
                                } else when {
                                    !readiness.notificationPermission -> if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else DeviceReadiness(context).openNotificationSettings()
                                    !readiness.notificationsEnabled -> DeviceReadiness(context).openNotificationSettings()
                                    !readiness.mcpChannelEnabled -> DeviceReadiness(context).openNotificationChannel("studio_mcp")
                                    !readiness.batteryUnrestricted -> DeviceReadiness(context).requestBatteryUnrestricted()
                                    !readiness.portAvailable -> Unit
                                    else -> {
                                        McpService.start(context)
                                        status = status + ("running" to true)
                                    }
                                }
                                scope.launch { refresh(skipHealth = true) }
                            })
                        }
                        Column(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(cs.surface.copy(alpha = 0.6f)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("MCP", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            Text(endpoint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text("局域网 MCP", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            Text(
                                lanEndpoints.firstOrNull() ?: "未连接局域网（同一 Wi-Fi 下可用）",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (lanEndpoints.isEmpty()) cs.onSurfaceVariant else cs.onSurface,
                            )
                            Text(
                                "同一 Wi-Fi 下的设备可用此地址接入；鉴权请求头与本机相同。",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                            if (tokenRequired) {
                                Text("鉴权请求头", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                Text(headerLine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { copy("MCP", endpoint) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Text("复制 MCP", Modifier.padding(start = 6.dp)) }
                            if (tokenRequired) OutlinedButton(onClick = { copy("鉴权请求头", headerLine) }, modifier = Modifier.weight(1f)) { Text("复制鉴权请求头") }
                        }
                        OutlinedButton(
                            onClick = { lanEndpoints.firstOrNull()?.let { copy("局域网 MCP", it) } },
                            enabled = lanEndpoints.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Text("复制局域网 MCP", Modifier.padding(start = 6.dp)) }
                        if (tokenRequired) OutlinedButton(onClick = { copy("访问令牌", token) }, modifier = Modifier.fillMaxWidth()) { Text("复制访问令牌") }
                        if (copied.isNotBlank()) Text(copied, color = cs.primary, style = MaterialTheme.typography.bodySmall)
                        if (running) OutlinedButton(onClick = { McpService.restart(context); scope.launch { refresh() } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp)); Text("重启服务", Modifier.padding(start = 6.dp)) }
                    }
                }
            }
            item {
                GlassCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("外观", style = MaterialTheme.typography.titleMedium)
                        StudioSegmentedControl(
                            options = ThemeMode.entries.map { it.label },
                            selectedIndex = ThemeMode.entries.indexOf(themeMode),
                            onSelect = { index -> onThemeModeChange(ThemeMode.entries[index]) },
                        )
                    }
                }
            }
            item {
                val cs = MaterialTheme.colorScheme
                var sourceType by remember { mutableStateOf(app.runtimeConfig.bookSourceType) }
                GlassCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("书源类型", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "选择本次制作的目标类型，保存书源时自动写入 bookSourceType；文本类型下抓取会自动跳过图片、音视频、安装包等二进制资源，避免不相干内容干扰规则编写。",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                        StudioSegmentedControl(
                            options = RuntimeConfigStore.TYPE_NAMES,
                            selectedIndex = sourceType,
                            onSelect = { index ->
                                sourceType = index
                                app.runtimeConfig.bookSourceType = index
                            },
                        )
                        Text(
                            when (sourceType) {
                                1 -> "音频：正文规则产出播放地址，抓取到的媒体资源仅保留 URL 引用"
                                2 -> "图片：正文保留 <img> 标签列表，抓取时不下载图片本体"
                                3 -> "文件：正文规则产出下载链接，抓取时不下载文件本体"
                                4 -> "视频：正文规则产出播放地址，抓取到的媒体资源仅保留 URL 引用"
                                else -> "文本：抓取时跳过图片、音视频等二进制资源，只返回净化后的正文"
                            },
                            style = MaterialTheme.typography.bodySmall, color = cs.primary,
                        )
                    }
                }
            }
            item {
                GlassCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("连接参数", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(portText, { portText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("端口 1024–65530") }, singleLine = true, colors = studioFieldColors())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("访问令牌校验"); Switch(tokenRequired, { tokenRequired = it }) }
                    if (tokenRequired) OutlinedTextField(token, { token = it }, modifier = Modifier.fillMaxWidth(), label = { Text("访问令牌") }, singleLine = true, colors = studioFieldColors())
                    Button(onClick = {
                        val port = portText.toIntOrNull()
                        when {
                            port == null || port !in 1024..65530 -> configMessage = "端口需为 1024–65530 之间的整数"
                            tokenRequired && token.isBlank() -> configMessage = "已开启访问令牌校验，请填写访问令牌"
                            else -> runCatching {
                                McpConfigStore(context).save(McpConfigStore.Config(port, tokenRequired, token))
                                if (running) McpService.restart(context)
                                configMessage = "参数已保存"
                                scope.launch { refresh() }
                            }.onFailure { configMessage = "保存失败：${it.message ?: "未知错误"}" }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("保存并重载") }
                    if (configMessage.isNotBlank()) Text(configMessage, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                } }
            }
            item { ReadinessCard("MCP HTTP 健康检查", !running || healthOk, if (!running) "服务未启动" else if (healthOk) "127.0.0.1:${status["port"]}/health 正常" else "进程已启动，健康检查失败", Icons.Outlined.CheckCircle) { if (running) McpService.restart(context) } }
            item { Text("运行前置条件", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 6.dp)) }
            item { ReadinessCard("通知权限", readiness.notificationPermission && readiness.notificationsEnabled, "用于常驻展示 MCP 连接状态", Icons.Outlined.Notifications) {
                if (Build.VERSION.SDK_INT >= 33 && !readiness.notificationPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else DeviceReadiness(context).openNotificationSettings()
            } }
            item { ReadinessCard("MCP 通知渠道", readiness.mcpChannelEnabled, "通知栏常驻展示 Endpoint 与运行状态", Icons.Outlined.Notifications) { DeviceReadiness(context).openNotificationChannel("studio_mcp") } }
            item { ReadinessCard("电池策略：无限制", readiness.batteryUnrestricted, "避免后台 MCP 进程被系统冻结", Icons.Outlined.BatterySaver) { DeviceReadiness(context).requestBatteryUnrestricted() } }
            item { ReadinessCard("局域网地址（仅供参考）", true, readiness.localAddresses.joinToString().ifBlank { "未枚举到非回环地址，不影响本机 MCP" }, Icons.Outlined.CheckCircle) { DeviceReadiness(context).openAppDetails() } }
            item { ReadinessCard("端口可用", readiness.portAvailable, "当前端口 ${(status["port"] ?: McpConfigStore.DEFAULT_PORT)}", Icons.Outlined.CheckCircle) { } }
            item { OutlinedButton(onClick = { DeviceReadiness(context).openAutoStart() }, modifier = Modifier.fillMaxWidth()) { Text("系统自启动管理") } }
            item { Text("仅暴露 127.0.0.1；切换网络无需变更 Endpoint。局域网 IP 仅供参考。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
        GlassTopBar("MCP 宿主", modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun ReadinessCard(title: String, ready: Boolean, detail: String, icon: ImageVector, action: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    GlassCard {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalIconBox(
                if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                if (ready) cs.primaryContainer else cs.errorContainer,
                if (ready) cs.onPrimaryContainer else cs.onErrorContainer,
                size = 42,
            )
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (!ready) Button(onClick = action) { Text("启用") }
        }
    }
}
