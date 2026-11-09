package com.mina.legadostudio.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.ui.theme.GlassCard
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.LocalStudioFullscreen
import com.mina.legadostudio.ui.theme.StudioSpacing
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioTopInset
import com.mina.legadostudio.verification.WebViewCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 事务面板折叠态高度（只剩标题行）。 */
private val TxPanelHeaderHeight = 44.dp

/** WebView 的最小可见高度：键盘/窄屏挤压下面板也不得把它压到 0。 */
private val MinWebViewHeight = 120.dp

/**
 * 浏览器抓包页：**可见、可交互**的独立页面（Compose AndroidView 承载真实 WebView）。
 *
 * 与 MCP webview_capture 无头模式的区别：这里的 WebView 一直存活，用户可以像普通
 * 浏览器一样点击链接、章节、翻页按钮——期间页面的 GET/HEAD 请求由
 * [com.mina.legadostudio.verification.WebViewCaptureEngine] 装配的 WebViewClient
 * **用独立 OkHttpClient 供给或仅观察**（这是 OkHttp 的独立请求证据，不是 WebView 原生
 * 网络栈抓包），并喂回 WebView。私网/DNS 私网被空响应或跳转拦截真正阻断；3xx 供给侧
 * 记 OBSERVED_ONLY 后由 WebView 对同一 URL 重发、自行跳转（不宣称逐跳抓全链）。
 *
 * 布局（围绕「网页占满高度、控件可收起」）：
 * - 常驻顶栏一行：当前 URL（单行，点一下展开**并聚焦**地址栏）+ 展开/收起 +
 *   溢出菜单（前进/导出会话/结束并保存/会话详情）；
 * - 「更多」展开区：后退/前进/重载图标 + 事务芯片（带计数）+ 完整地址栏（操作行水平滚动不拥挤）；
 * - 底部事务面板默认**折叠**成一条标题栏（事务计数常显，点整行展开/收起），展开后高度 =
 *   可用高 × 面板比例（0.25–0.75，「-/+」步进）；根容器 `imePadding` + 高度按
 *   `BoxWithConstraints` 实测量动态夹取，键盘弹出或窄屏时 WebView 至少保留 [MinWebViewHeight]；
 * - WebView 本体按 contextId key 常驻，面板/地址区开合、重组都不重建实例；
 * - **会话恢复策略（安全）**：Session 对象不跨进程/配置重建还原——构造时缺引擎私有
 *   dnsGuard 会把 DNS 私网守卫降级为仅字面校验。saveable 只存 URL/界面态；重建后用保存的
 *   URL 重新 `newSession()` 开带守卫的**新**会话（新 contextId）。旧会话的结算责任在
 *   销毁前的 onDispose（已写汇总行）；进程死亡则旧 Session 随进程消失——两条路径都不会对
 *   新 Session 二次结算，也不会重复写汇总行。已结束会话的 contextId 保留在
 *   `lastContextId`，重建后「会话详情」仍可回看。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserCaptureScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as StudioApplication
    val dao = app.database.dao()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val addressFocus = remember { FocusRequester() }

    // 活会话只在当前组合里存活（见头注释的安全策略）；UI 态可 saveable。
    var session by remember { mutableStateOf<WebViewCapture.Session?>(null) }
    var sessionStartedAt by rememberSaveable { mutableStateOf(0L) }
    var addressText by rememberSaveable { mutableStateOf("") }
    var currentUrl by rememberSaveable { mutableStateOf("") }
    var sessionFinished by rememberSaveable { mutableStateOf(false) }
    var lastContextId by rememberSaveable { mutableStateOf("") }
    var txnTick by remember { mutableStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var showTxPanel by rememberSaveable { mutableStateOf(false) }
    var txPanelFraction by rememberSaveable { mutableStateOf(0.40f) }
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    var viewingHttpId by rememberSaveable { mutableStateOf<Long?>(null) }
    var viewingFullLog by remember { mutableStateOf<HttpLogEntity?>(null) }
    var showSessionDetail by rememberSaveable { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var focusAddressField by remember { mutableStateOf(false) }
    // 无会话页的说明默认收起（点「说明」展开），避免说明卡占掉大半个空屏
    var aboutExpanded by rememberSaveable { mutableStateOf(false) }

    fun show(message: String) = toastNotice(context, message)

    // 会话事务流式观察：拦截器/异步落库写库后自动补齐（与 LogsScreen 同一口径，预览上限 300 条）。
    // liveCtx 只跟活会话走；lastContextId 是给「会话详情」回看用的（含已结束/上次会话）。
    val liveCtx = session?.contextId
    val viewableCtx = liveCtx ?: lastContextId.ifBlank { null }
    val sessionLogs by produceStateTx(dao, liveCtx, txnTick)

    // 系统返回键：覆盖层优先（单条详情加载期间 viewingFullLog 仍为 null、其内部 BackHandler
    // 尚未挂上，此时由外层直接关掉最顶层；会话详情次之），都不在才退 WebView 历史/离开页面。
    // 顺序固定：单条详情 > 会话详情 > WebView 后退 > onBack——不依赖各覆盖层 handler 的注册时序。
    BackHandler {
        when {
            viewingHttpId != null -> viewingHttpId = null
            showSessionDetail -> showSessionDetail = false
            else -> {
                val wv = webViewRef
                if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
            }
        }
    }

    // 覆盖层（会话详情/单条详情）打开时通知全局隐藏底部胶囊（与 LogsScreen 同一口径），
    // 避免底栏悬浮在覆盖层之上遮挡返回路径。
    val fullscreen = LocalStudioFullscreen.current
    DisposableEffect(showSessionDetail, viewingHttpId != null) {
        val overlayOpen = showSessionDetail || viewingHttpId != null
        if (overlayOpen) fullscreen?.value = true
        onDispose { if (overlayOpen) fullscreen?.value = false }
    }

    androidx.compose.runtime.LaunchedEffect(viewingHttpId) {
        val id = viewingHttpId
        viewingFullLog = if (id != null) withContext(Dispatchers.IO) { dao.httpLog(id) } else null
    }

    /**
     * 进程/配置重建后的安全恢复：不还原 Session 对象（缺 dnsGuard 会降级私网守卫），
     * 用保存的 URL 重新开带守卫的新会话。只在会话未结束时自动续；已结束会话不重启。
     * 失败（记录开关关闭/URL 不再合法）则落到入口页，URL 预留在地址栏，由用户决定。
     */
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (session == null && currentUrl.isNotBlank() && !sessionFinished) {
            runCatching { app.webViewCapture.newSession(currentUrl, interactive = true) }
                .onSuccess { s ->
                    session = s
                    sessionStartedAt = System.currentTimeMillis()
                    sessionFinished = false
                    lastContextId = s.contextId
                    addressText = s.entryUrl
                    show("已在新会话继续抓包（contextId 已更新）")
                }
                .onFailure { show(it.message?.take(200) ?: "无法恢复抓包会话") }
        }
    }

    // 顶栏 URL 点击 → 展开并聚焦地址栏（LaunchedEffect 保证字段已挂到树上再请求焦点）。
    androidx.compose.runtime.LaunchedEffect(moreExpanded, focusAddressField) {
        if (moreExpanded && focusAddressField) {
            focusAddressField = false
            runCatching { addressFocus.requestFocus() }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 离开页面：先结算并写会话汇总行（否则已结束的会话还一直被 WebView 继续记录），
            // 再销毁 WebView 断掉后续回调。只结算当前活 session——重建路径下旧会话
            // 已由前一次 onDispose 结算过或随进程消失，这里不会碰到它（无二次写汇总）。
            val s = session
            if (s != null && !sessionFinished) {
                app.webViewCapture.finishSession(s, currentUrl.ifBlank { s.entryUrl }, sessionStartedAt)
            }
            runCatching { webViewRef?.destroy() }
            webViewRef = null
        }
    }

    /** 结束当前会话（写会话汇总行）后开始新会话；旧 WebView 引用先清掉，避免新会话起来前旧实例还在收回调。 */
    fun startSession(url: String) {
        val target = url.trim()
        if (target.isEmpty()) return
        // 先开新会话成功，再结算旧会话——newSession 失败（记录关闭/URL 不合法）时
        // 旧会话照常续跑，不会白丢一段抓包。
        val next = runCatching { app.webViewCapture.newSession(target, interactive = true) }
            .getOrElse {
                show(it.message?.take(200) ?: "无法开始抓包会话")
                return
            }
        val old = session
        if (old != null && !sessionFinished) {
            runCatching { app.webViewCapture.finishSession(old, currentUrl.ifBlank { old.entryUrl }, sessionStartedAt) }
        }
        runCatching { webViewRef?.stopLoading() }
        webViewRef = null
        session = next
        sessionStartedAt = System.currentTimeMillis()
        sessionFinished = false
        lastContextId = next.contextId
        currentUrl = next.entryUrl
        addressText = next.entryUrl
        keyboard?.hide()
    }

    fun finishCurrent() {
        val s = session ?: return
        if (sessionFinished) return
        val finalUrl = currentUrl.ifBlank { s.entryUrl }
        app.webViewCapture.finishSession(s, finalUrl, sessionStartedAt)
        sessionFinished = true
        // 手动过挑战页的 cookie 常只在 CookieManager 里、没落 prefs 桶——会话结束时同步采一次，
        // get_cookies/apply_webview_cookies/fetcher 才能复用到这批 cookie。
        scope.launch { runCatching { app.cookieStore.captureFromWebView(finalUrl) } }
        show("会话已保存（contextId=${s.contextId}），可在「抓包会话」历史回看")
    }

    // BoxWithConstraints + imePadding：maxHeight 是扣除键盘后的实际可用高，
    // 面板高度与 WebView 高度都按它动态分配，键盘弹出不会把内容推出屏外。
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        // 内容区可用高 = 实测可用高 - 顶栏与上下系统避让。
        val availHeight = (maxHeight - 64.dp - studioTopInset() - studioBottomInset()).coerceAtLeast(0.dp)
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 64.dp + studioTopInset(), bottom = studioBottomInset()),
        ) {
            // ── 常驻顶栏：当前 URL + 展开/收起 + 溢出菜单 ─────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.screen, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = currentUrl.ifBlank { "输入网址开始抓包" },
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            moreExpanded = true
                            focusAddressField = true
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (currentUrl.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { moreExpanded = !moreExpanded }) {
                    Icon(
                        if (moreExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (moreExpanded) "收起地址与导航" else "展开地址与导航",
                    )
                }
                Box {
                    IconButton(onClick = { moreMenu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("前进") },
                            enabled = session != null,
                            onClick = {
                                moreMenu = false
                                webViewRef?.takeIf { it.canGoForward() }?.goForward()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出会话") },
                            enabled = session != null,
                            onClick = {
                                moreMenu = false
                                session?.let { exportCaptureSession(context, it.contextId, scope) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("结束并保存") },
                            enabled = session != null && !sessionFinished,
                            onClick = {
                                moreMenu = false
                                finishCurrent()
                            },
                        )
                        DropdownMenuItem(
                            // 无活会话但留有 contextId（重建后/已结束）也可回看整链
                            text = { Text("会话详情") },
                            enabled = viewableCtx != null,
                            onClick = {
                                moreMenu = false
                                showSessionDetail = true
                            },
                        )
                    }
                }
            }

            // ── 展开区：导航图标 + 事务芯片 + 完整地址栏（水平滚动行不拥挤）──
            if (moreExpanded) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = StudioSpacing.screen),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        IconButton(onClick = { webViewRef?.takeIf { it.canGoBack() }?.goBack() }, enabled = session != null) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "后退")
                        }
                        IconButton(onClick = { webViewRef?.takeIf { it.canGoForward() }?.goForward() }, enabled = session != null) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "前进")
                        }
                        IconButton(onClick = { webViewRef?.reload() }, enabled = session != null) {
                            Icon(Icons.Outlined.RestartAlt, contentDescription = "重载")
                        }
                        TextButton(
                            onClick = { showTxPanel = !showTxPanel },
                            enabled = session != null,
                        ) {
                            Text(if (showTxPanel) "收起事务" else "事务(${sessionLogs.size})", style = MaterialTheme.typography.labelSmall)
                        }
                        if (session != null) {
                            // 低频操作只在溢出菜单留一份（导出/结束并保存/会话详情），不在展开区复制整排
                            TextButton(onClick = { moreMenu = true }) {
                                Text("更多操作", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = addressText,
                            onValueChange = { addressText = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(addressFocus),
                            label = { Text("入口 URL（http/https，公网）") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { startSession(addressText) }, enabled = addressText.isNotBlank()) {
                            Text("前往")
                        }
                    }
                }
            }

            // ── 主区：可见可交互 WebView（新会话 = 新实例）；未开会话时地址输入放首屏 ──
            val activeSession = session
            if (activeSession == null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = StudioSpacing.screen),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 地址输入直达首屏：不藏进「展开区」，键盘弹出时输入框仍在可视范围内
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = addressText,
                            onValueChange = { addressText = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(addressFocus),
                            label = { Text("入口 URL（http/https，公网）") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { startSession(addressText) },
                            enabled = addressText.isNotBlank(),
                        ) { Text("前往") }
                    }
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(StudioSpacing.card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "输入网址后点「前往」开始抓包浏览；期间事务会显示在页面底部面板",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "说明 · 边界",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { aboutExpanded = !aboutExpanded }
                                    .padding(vertical = 2.dp),
                            )
                            if (aboutExpanded) {
                                Text(
                                    "页面是真实可交互的 WebView：可以点链接/章节/翻页，期间 GET/HEAD 由 OkHttp 供给或仅观察" +
                                        "（非原生 WebView 网络栈抓包）、记入同一 cap: 会话。" +
                                        "仅允许公网 HTTP/HTTPS；私网/回环地址会被阻断并记 BLOCKED 行。" +
                                        "无头一次性抓取走 MCP webview_capture（不可交互）。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (viewableCtx != null) {
                                TextButton(onClick = { showSessionDetail = true }) {
                                    Text("查看上次会话详情", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            } else {
                // txnTick 仅供事务计数刷新；WebView 本体按会话 key 重建，导航中不重建。
                @Suppress("UNUSED_EXPRESSION") txnTick
                androidx.compose.runtime.key(activeSession.contextId) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                app.webViewCapture.configure(this)
                                webViewClient = app.webViewCapture.clientFor(
                                    activeSession,
                                    onTransaction = { txnTick += 1 },
                                    onMainFrameNavigated = { navigated ->
                                        currentUrl = navigated
                                        addressText = navigated
                                    },
                                )
                                webViewRef = this
                                loadUrl(activeSession.entryUrl)
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }

            // ── 底部事务面板：默认折叠成一条（计数常显），展开后高度按实测可用高动态夹取 ──
            if (activeSession != null) {
                // 展开态上限 = avail - MinWebViewHeight（键盘弹出时同步收缩，WebView 不会被压到 0）；
                // 极端窄高下面板退化为仅标题行。
                val panelMax = (availHeight - MinWebViewHeight).coerceAtLeast(TxPanelHeaderHeight)
                val panelHeight = if (showTxPanel) {
                    (availHeight * txPanelFraction).coerceIn(TxPanelHeaderHeight, panelMax)
                } else {
                    TxPanelHeaderHeight
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(panelHeight)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showTxPanel = !showTxPanel }
                            .padding(horizontal = StudioSpacing.card, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "本会话事务 ${sessionLogs.size}" +
                                (if (activeSession.sessionExhausted) " · 容量已耗尽" else "") +
                                (if (sessionFinished) " · 已保存" else "") +
                                (if (!showTxPanel) " · 展开" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (showTxPanel) {
                            // 「-/+」步进调整面板高度（占可用高 25%~75%），点标题行整行即折叠
                            TextButton(onClick = { txPanelFraction = (txPanelFraction - 0.1f).coerceIn(0.25f, 0.75f) }) {
                                Text("-", style = MaterialTheme.typography.labelSmall)
                            }
                            Text("${(txPanelFraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { txPanelFraction = (txPanelFraction + 0.1f).coerceIn(0.25f, 0.75f) }) {
                                Text("+", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { showSessionDetail = true }) {
                                Text("整链详情", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (showTxPanel) {
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(horizontal = StudioSpacing.screen, vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (sessionLogs.isEmpty()) {
                                item {
                                    Text(
                                        "暂无事务记录（页面还在加载，或记录开关已关闭）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 6.dp),
                                    )
                                }
                            }
                            items(sessionLogs, key = { it.id }) { entry ->
                                TxRow(entry = entry, onClick = { viewingHttpId = entry.id })
                            }
                        }
                    }
                }
            }
        }
        GlassTopBar("浏览器抓包", onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))

        // 整链会话详情（复用日志页的会话回看层，含导出）；无活会话时回看 lastContextId
        if (showSessionDetail && viewableCtx != null) {
            CaptureSessionDetail(
                dao = dao,
                contextId = viewableCtx,
                onBack = { showSessionDetail = false },
                onOpenLog = { viewingHttpId = it },
            )
        }
        // 单条 HTTP 详情（须位于会话层之上）
        if (viewingHttpId != null) {
            viewingFullLog?.let { fullLog ->
                HttpLogDetail(log = fullLog, onBack = { viewingHttpId = null })
            }
        }
    }
}

/** 事务面板单行：状态码 + 方法 + URL + 耗时/错误摘要。 */
@Composable
private fun TxRow(entry: com.mina.legadostudio.network.CaptureOnce.LogEntry, onClick: () -> Unit) {
    GlassCard {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${entry.statusCode} ${entry.method}",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        entry.originKind == "webview_capture_blocked" -> MaterialTheme.colorScheme.error
                        entry.statusCode in 300..399 -> MaterialTheme.colorScheme.tertiary
                        entry.statusCode >= 400 || entry.statusCode == 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Text(entry.originKind.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(entry.url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.error.isNotBlank()) {
                Text(entry.error.take(120), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 观察 cap:contextId 的事务行：轻量投影 Flow（SQL 层 LIMIT 300、不含正文），新行自动补齐，最新在前。 */
@Composable
private fun produceStateTx(
    dao: com.mina.legadostudio.data.db.StudioDao,
    contextId: String?,
    @Suppress("UNUSED_PARAMETER") tick: Int,
): androidx.compose.runtime.State<List<com.mina.legadostudio.network.CaptureOnce.LogEntry>> {
    return androidx.compose.runtime.produceState(initialValue = emptyList(), contextId) {
        val ctx = contextId
        if (ctx == null) { value = emptyList(); return@produceState }
        dao.observeHttpLogSummariesByContextIdDesc(ctx, 300).collect { list ->
            value = list.map {
                com.mina.legadostudio.network.CaptureOnce.LogEntry(
                    it.id, it.method, it.url, it.finalUrl, it.statusCode, it.durationMs,
                    it.error.take(300), it.originKind,
                )
            }
        }
    }
}

// 会话导出复用 LogsScreen.exportCaptureSession（写 cacheDir/exports，FileProvider 分享，含过期清理）。
