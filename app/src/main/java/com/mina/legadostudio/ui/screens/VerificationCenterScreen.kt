package com.mina.legadostudio.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.studioChipBorder
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioChipColors
import com.mina.legadostudio.ui.theme.studioTopInset
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VerificationCenterScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as StudioApplication
    val sessions by app.verification.observe().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentUrl by rememberSaveable { mutableStateOf("") }
    var refreshNonce by rememberSaveable { mutableStateOf(0) }
    var statusMessage by remember { mutableStateOf("") }
    val selected = sessions.firstOrNull { it.id == selectedId }
        ?: sessions.firstOrNull { it.status == "WAITING" }
    LaunchedEffect(selected?.id) { currentUrl = selected?.finalUrl?.takeIf { it.isNotBlank() } ?: selected?.url.orEmpty() }

    Box(Modifier.fillMaxSize()) {
        // 底栏为悬浮胶囊，底部留出 tab bar + 手势条的高度，避免操作按钮被遮挡
        Column(Modifier.fillMaxSize().padding(top = 64.dp + studioTopInset(), bottom = 84.dp + studioBottomInset())) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { it.id }) { session -> FilterChip(selected = session.id == selected?.id, onClick = { selectedId = session.id }, label = { Text("${session.domain.ifBlank { "验证" }} · ${if (session.status == "COMPLETED") "已完成" else "等待"}") }, colors = studioChipColors(), border = studioChipBorder(session.id == selected?.id)) }
            }
            if (selected == null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("暂无待处理验证会话。由 MCP `browser_verify` 创建。", style = MaterialTheme.typography.bodyMedium)
                    Text("也可直接点底栏「验证中心」进入，通知栏被划掉也不影响。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("用途：${selected.purpose}　状态：${selected.status}", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                key(selected.id, refreshNonce) {
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = app.runtimeConfig.userAgent
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.safeBrowsingEnabled = true
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean = request.url.scheme !in setOf("http", "https")
                                    override fun onPageFinished(view: WebView, url: String) {
                                        currentUrl = url
                                        CookieManager.getInstance().flush()
                                        app.verificationWebState.save(selected.id, view)
                                        scope.launch { app.verification.updateCurrentUrl(selected.id, url) }
                                        if (selected.jobId != "manual" && selected.status == "WAITING") {
                                            view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                                                val html = runCatching { com.google.gson.JsonParser.parseString(raw).asString }.getOrDefault("")
                                                val redirected = url != selected.url && url != selected.finalUrl
                                                if (redirected && html.isNotBlank() && !app.fetcher.looksLikeVerification(403, url, html)) {
                                                    statusMessage = "检测到验证已完成，Cookie 已写入运行时。"
                                                    scope.launch { app.verification.complete(selected.id, url); selectedId = null }
                                                }
                                            }
                                        }
                                    }
                                }
                                val restored = app.verificationWebState.restore(selected.id, this)
                                if (!restored) loadUrl(selected.finalUrl.takeIf { it.isNotBlank() } ?: selected.url)
                            }
                        },
                        onRelease = { view -> app.verificationWebState.save(selected.id, view); view.stopLoading(); view.destroy() },
                    )
                }
                if (statusMessage.isNotBlank()) Text(statusMessage, Modifier.padding(horizontal = 12.dp))
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { app.verificationWebState.clear(selected.id); refreshNonce++ }) { Text("刷新") }
                    OutlinedButton(onClick = { scope.launch { app.verification.clear(selected.id); refreshNonce++ } }) { Text("清 Cookie") }
                    OutlinedButton(onClick = { scope.launch { app.verification.close(selected.id); selectedId = null } }) { Text("关闭") }
                }
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl.ifBlank { selected.url }))) }) { Text("外部浏览器（备用）") }
                    Button(onClick = { scope.launch { app.verification.complete(selected.id, currentUrl.ifBlank { selected.url }); selectedId = null } }, modifier = Modifier.weight(1f)) { Text("已完成验证") }
                }
                Text("外部浏览器会话可能与应用内会话不一致，默认请在上方 WebView 完成验证。", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        GlassTopBar("内部验证中心", onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
    }
}
