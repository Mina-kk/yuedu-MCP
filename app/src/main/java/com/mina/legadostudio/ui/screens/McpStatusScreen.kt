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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mina.legadostudio.ui.theme.studioChipBorder
import com.mina.legadostudio.ui.theme.studioChipColors
import com.mina.legadostudio.ui.theme.studioFieldColors
import com.mina.legadostudio.ui.theme.studioTopInset
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class McpTab(val label: String) { CONNECT("连接"), SETTINGS("设置"), READINESS("前置条件") }

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
    var tab by remember { mutableStateOf(McpTab.CONNECT) }
    var configTargetLan by remember { mutableStateOf(false) }
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
    // 服务启动后以持久化配置为准展示鉴权头，避免用户正在编辑的草稿令牌污染复制内容
    val activeToken = status["token"]?.toString().orEmpty().ifBlank { token }
    val activeTokenRequired = status["tokenRequired"] == true || tokenRequired
    val headerLine = McpAccess.tokenHeaderLine(activeToken)
    val localConfigJson = McpAccess.clientConfigJson(endpoint, activeToken, activeTokenRequired)
    val lanConfigJson = lanEndpoints.firstOrNull()?.let { McpAccess.clientConfigJson(it, activeToken, activeTokenRequired) }
    fun copy(label: String, value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        copied = "已复制 $label"
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 64.dp + studioTopInset())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                McpTab.entries.forEach { value ->
                    val on = tab == value
                    FilterChip(
                        selected = on,
                        onClick = { tab = value },
                        label = { Text(value.label) },
                        colors = studioChipColors(),
                        border = studioChipBorder(on),
                        leadingIcon = if (on) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else null,
                    )
                }
            }

            LazyColumn(
                Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 108.dp + studioBottomInset()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    McpTab.CONNECT -> {
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
                                                else "启动后按下方接入信息填写客户端即可",
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
                                    if (running) OutlinedButton(onClick = { McpService.restart(context); scope.launch { refresh() } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp)); Text("重启服务", Modifier.padding(start = 6.dp)) }
                                }
                            }
                        }
                        item {
                            val cs = MaterialTheme.colorScheme
                            val showingLan = configTargetLan && lanEndpoints.isNotEmpty()
                            val shownUrl = if (showingLan) lanEndpoints.first() else endpoint
                            val bearerValue = McpAccess.bearerTokenValue(activeToken)
                            val hasToken = activeTokenRequired && activeToken.isNotBlank()
                            GlassCard {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("接入信息", style = MaterialTheme.typography.titleMedium)
                                    StudioSegmentedControl(
                                        options = listOf("本机", "局域网"),
                                        selectedIndex = if (showingLan) 1 else 0,
                                        onSelect = { index -> configTargetLan = index == 1 },
                                    )
                                    if (configTargetLan && lanEndpoints.isEmpty()) {
                                        Text("未连接局域网：客户端与本 App 在同一台设备时用「本机」；在其他设备（电脑/另一台手机）时，两台设备连同一 Wi-Fi 后这里会自动出现局域网地址。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    }
                                    // 第 1 步：所有客户端都要填的服务器链接
                                    Text("① 服务器链接（所有客户端都填这里）", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                                    Column(
                                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(cs.surface.copy(alpha = 0.6f)).padding(12.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            Text(shownUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                            OutlinedButton(onClick = { copy("服务器链接", shownUrl) }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)); Text("复制", Modifier.padding(start = 4.dp)) }
                                        }
                                    }
                                    if (!hasToken) {
                                        Text("② 鉴权：未开启令牌校验，客户端的 Token / 请求头全部留空即可。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    } else {
                                        // 第 2 步：按客户端界面形态对号入座
                                        Text("② 鉴权：看你的客户端长什么样，对号入座", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                                        Text("有「Token」或「Bearer Token」输入框 → 只粘贴下面的令牌本身，不要加 Bearer 前缀：", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                        Column(
                                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(cs.surface.copy(alpha = 0.6f)).padding(12.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                Text(activeToken, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                                OutlinedButton(onClick = { copy("Token", activeToken) }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)); Text("复制", Modifier.padding(start = 4.dp)) }
                                            }
                                        }
                                        Text("只有「自定义请求头：名称 + 值」两个框 → 照下面两行一格一个复制（名称填 Authorization，不要填 Bearer）：", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                        Column(
                                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(cs.surface.copy(alpha = 0.6f)).padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text("请求头名称", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                Text(McpAccess.AUTH_HEADER, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                                OutlinedButton(onClick = { copy("请求头名称", McpAccess.AUTH_HEADER) }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)); Text("复制", Modifier.padding(start = 4.dp)) }
                                            }
                                            Text("请求头值", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                Text(bearerValue, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                                OutlinedButton(onClick = { copy("请求头值", bearerValue) }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)); Text("复制", Modifier.padding(start = 4.dp)) }
                                            }
                                        }
                                    }
                                    Text(
                                        "「本机」的 127.0.0.1 只对装在本 App 同一台设备上的客户端有效；客户端在其他设备上时请切到「局域网」再用对应地址。",
                                        style = MaterialTheme.typography.bodySmall, color = cs.outline,
                                    )
                                    if (copied.isNotBlank()) Text(copied, color = cs.primary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item {
                            val cs = MaterialTheme.colorScheme
                            val showingLan = configTargetLan && lanEndpoints.isNotEmpty()
                            val shownConfig = if (showingLan) lanConfigJson ?: localConfigJson else localConfigJson
                            var jsonExpanded by remember { mutableStateOf(false) }
                            GlassCard {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.weight(1f)) {
                                            Text("配置文件客户端（高级）", style = MaterialTheme.typography.titleMedium)
                                            Text("Claude Code / Cline / Cherry Studio 等支持整段 mcpServers JSON 配置的客户端", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                        }
                                        TextButton(onClick = { jsonExpanded = !jsonExpanded }) { Text(if (jsonExpanded) "收起" else "展开") }
                                    }
                                    if (jsonExpanded) {
                                        Column(
                                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(cs.surface.copy(alpha = 0.6f)).padding(12.dp),
                                        ) {
                                            Text(shownConfig, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Button(
                                            onClick = { copy(if (showingLan) "局域网客户端配置" else "客户端配置", shownConfig) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Text("复制客户端配置", Modifier.padding(start = 6.dp)) }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { copy("MCP URL", if (showingLan) lanEndpoints.first() else endpoint) }, modifier = Modifier.weight(1f)) { Text("仅复制 URL") }
                                            if (activeTokenRequired) OutlinedButton(onClick = { copy("鉴权请求头", headerLine) }, modifier = Modifier.weight(1f)) { Text("仅复制鉴权头") }
                                        }
                                        if (copied.isNotBlank()) Text(copied, color = cs.primary, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        item { ReadinessCard("MCP HTTP 健康检查", !running || healthOk, if (!running) "服务未启动" else if (healthOk) "127.0.0.1:${status["port"]}/health 正常" else "进程已启动，健康检查失败", Icons.Outlined.CheckCircle) { if (running) McpService.restart(context) } }
                    }
                    McpTab.SETTINGS -> {
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
                                Text("修改端口或令牌后需重新复制「连接」页的客户端配置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                        }
                        item {
                            val cs = MaterialTheme.colorScheme
                            var sourceType by remember { mutableStateOf(app.runtimeConfig.bookSourceType) }
                            GlassCard {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("书源类型", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "选择本次制作的目标类型，保存书源时自动写入 bookSourceType（「自动」不写入）；文本类型下抓取会提示跳过图片、音视频、安装包等二进制资源。图文漫画等类型不确定的站点选「自动」。",
                                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                    )
                                    StudioSegmentedControl(
                                        options = listOf("自动") + RuntimeConfigStore.TYPE_NAMES,
                                        selectedIndex = sourceType + 1,
                                        onSelect = { index ->
                                            sourceType = index - 1
                                            app.runtimeConfig.bookSourceType = index - 1
                                        },
                                    )
                                    Text(
                                        when (sourceType) {
                                            -1 -> "自动：不写入 bookSourceType、不干预抓取，适合图文漫画混合等类型不确定的站点"
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
                    }
                    McpTab.READINESS -> {
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
                }
            }
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
