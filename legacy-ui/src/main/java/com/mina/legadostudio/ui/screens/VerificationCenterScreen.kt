package com.mina.legadostudio.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.studioChipBorder
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioChipColors
import com.mina.legadostudio.ui.theme.studioTopInset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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
    var autoProbe by remember { mutableStateOf<Job?>(null) }
    var completing by remember { mutableStateOf(false) }
    val selected = sessions.firstOrNull { it.id == selectedId }
        ?: sessions.firstOrNull { it.status == "WAITING" }
    LaunchedEffect(selected?.id) { currentUrl = selected?.finalUrl?.takeIf { it.isNotBlank() } ?: selected?.url.orEmpty() }

    suspend fun submitComplete(sessionId: String, url: String, successMessage: String?) {
        if (completing) return
        completing = true
        try {
            app.verification.complete(sessionId, url)
            successMessage?.let { statusMessage = it }
            selectedId = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statusMessage = "提交失败：${e.localizedMessage.orEmpty()}"
            Toast.makeText(context, "验证提交失败，请重试", Toast.LENGTH_SHORT).show()
        } finally {
            completing = false
        }
    }

    // 轮询判定验证是否放行：不再要求 URL 发生跳转（CF/JS 挑战常常原地通过）。
    // 条件：页面非空、无验证页标记、长度稳定（与上次差<5%）连续 2 次；120 秒后停止自动判定，保留手动按钮。
    fun startAutoCompleteProbe(view: WebView, sessionId: String, startUrl: String) {
        autoProbe?.cancel()
        autoProbe = scope.launch {
            var lastLen = -1L
            var stable = 0
            var probedUrl = startUrl
            val deadline = System.currentTimeMillis() + 120_000
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(2_000)
                val urlNow = withContext(Dispatchers.Main) { view.url } ?: break
                if (urlNow != probedUrl) { probedUrl = urlNow; lastLen = -1; stable = 0 }
                val html = withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<String?> { c ->
                        view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                            c.resume(runCatching { com.google.gson.JsonParser.parseString(raw).asString }.getOrNull())
                        }
                    }
                } ?: continue
                if (html.isBlank()) continue
                val len = html.length.toLong()
                val challenged = app.fetcher.verificationMarkerLoose(html) != null
                stable = if (!challenged && lastLen > 0 && kotlin.math.abs(len - lastLen) * 20 < len) stable + 1 else 0
                lastLen = len
                if (!challenged && stable >= 2) {
                    if (completing) break
                    CookieManager.getInstance().flush()
                    delay(500) // 尾随异步下发的 cookie 缓冲
                    CookieManager.getInstance().flush()
                    submitComplete(sessionId, urlNow, "检测到验证已完成，Cookie 已写入运行时。")
                    break
                }
            }
        }
    }

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
            } else if (selected.kind == "image_code") {
                // 图片验证码会话：渲染 data:image 截图 + 文本输入框，提交答案后 MCP 侧 awaitAnswer 拿到值
                var codeInput by rememberSaveable(selected.id) { mutableStateOf("") }
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("图片验证码 · ${selected.domain}", style = MaterialTheme.typography.titleSmall)
                    Text("用途：${selected.purpose}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selected.imageData.isNotBlank()) {
                        val bmp = remember(selected.imageData) {
                            runCatching {
                                val b64 = selected.imageData.substringAfter("base64,")
                                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull()
                        }
                        bmp?.let {
                            androidx.compose.foundation.Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "验证码",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            )
                        } ?: Text("验证码图解码失败", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("验证码图缺失（imageData 为空），可手动打开 ${selected.url} 查看", style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it },
                        label = { Text("输入图中验证码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (statusMessage.isNotBlank()) Text(statusMessage, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { app.verification.close(selected.id); selectedId = null } }) { Text("关闭") }
                        Button(
                            onClick = {
                                scope.launch {
                                    app.verification.complete(selected.id, "", codeInput.trim())
                                    statusMessage = "已提交验证码：${codeInput.trim()}"
                                    selectedId = null
                                }
                            },
                            enabled = codeInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("提交验证码") }
                    }
                }
            } else {
                Text("用途：${selected.purpose}　状态：${selected.status}", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                key(selected.id, refreshNonce) {
                    val sessionId = selected.id
                    val sessionJobId = selected.jobId
                    val sessionStartUrl = selected.finalUrl.takeIf { it.isNotBlank() } ?: selected.url
                    val sessionWaiting by rememberUpdatedState(selected.status == "WAITING")
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
                                        app.verificationWebState.save(sessionId, view)
                                        scope.launch { app.verification.updateCurrentUrl(sessionId, url) }
                                        if (sessionJobId != "manual" && sessionWaiting) {
                                            startAutoCompleteProbe(view, sessionId, url)
                                        }
                                    }
                                }
                                val restored = app.verificationWebState.restore(sessionId, this)
                                if (!restored) loadUrl(sessionStartUrl)
                            }
                        },
                        onRelease = { view ->
                            app.verificationWebState.save(sessionId, view)
                            autoProbe?.cancel()
                            view.stopLoading()
                            (view.parent as? ViewGroup)?.removeView(view)
                            view.destroy()
                        },
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
                    Button(onClick = { scope.launch { submitComplete(selected.id, currentUrl.ifBlank { selected.url }, null) } }, enabled = !completing, modifier = Modifier.weight(1f)) { Text(if (completing) "提交中…" else "已完成验证") }
                }
                Text("外部浏览器会话可能与应用内会话不一致，默认请在上方 WebView 完成验证。", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        GlassTopBar("验证中心", onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
    }
}
