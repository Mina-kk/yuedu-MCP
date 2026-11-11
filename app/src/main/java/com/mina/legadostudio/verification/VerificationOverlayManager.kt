package com.mina.legadostudio.verification

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mina.legadostudio.MainActivity

/**
 * 常驻圆形悬浮球（圆底 + 「M」字样），业务 UI 全部由**受控本地 WebView** 渲染：
 * - 页面是 [VerificationOverlayPage] 生成的纯 HTML/CSS 字符串（无 JS、无外链、无 JS interface），
 *   经 `loadDataWithBaseURL(about:blank, …)` 离线加载，WebView 关闭 JS/文件/content/网络访问，
 *   并拦截一切非文档自身导航与子资源请求；
 * - 常态蓝底白 M；验证时橙底白 M 与域名提示；
 * - 可自由拖拽；近边停靠后延时裁切半球（窗口始终留在屏内），
 *   验证气泡可见时吸附保持全显；
 * - 半隐首次点击仅展开，再次点击进入 MCP/验证中心。
 *
 * 拖动/点击由宿主 `setOnTouchListener` 处理——这是系统窗口交互，不是业务控件；
 * 页面里没有任何 `<button>/<form>/<input>`。
 * 权限仍完全由 [OverlayPrefs] + 系统悬浮窗授权决定，这里不申请权限、不截屏、不扩大窗口能力。
 */
object VerificationOverlayManager {
    private var windowManager: WindowManager? = null
    private var ballView: WebView? = null
    /** 当前页面是否处于验证提醒态（决定吸附时是否半隐、点击去哪个路由）。 */
    @Volatile private var alertVisible: Boolean = false
    @Volatile private var currentSessionId: String? = null
    @Volatile private var pendingDomain: String? = null
    @Volatile private var pendingPurpose: String? = null

    private val main = Handler(Looper.getMainLooper())

    /** 按当前开关/权限/验证状态刷新悬浮球（服务启停、权限变化、验证到来时都调用）。 */
    @Synchronized
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        if (!OverlayPrefs.isEnabled(appContext) || !Settings.canDrawOverlays(appContext)) {
            hideAll()
            return
        }
        val generation = lifecycleGeneration
        main.post {
            if (generation != lifecycleGeneration || !serviceActive || !OverlayPrefs.isEnabled(appContext) || !Settings.canDrawOverlays(appContext)) return@post
            if (ballView == null) createBall(appContext)
            val sid = currentSessionId
            val dom = pendingDomain
            if (sid != null && dom != null) applyAlertStyle(dom, pendingPurpose.orEmpty()) else applyNormalStyle()
        }
    }

    /** 验证到来时：若悬浮球开着，切到提醒样式。 */
    @Synchronized
    fun showVerificationAlert(context: Context, domain: String, sessionId: String, purpose: String = "") {
        currentSessionId = sessionId
        pendingDomain = domain
        pendingPurpose = purpose
        val generation = lifecycleGeneration
        main.post {
            if (generation != lifecycleGeneration || currentSessionId != sessionId) return@post
            val appContext = context.applicationContext
            if (!serviceActive || !OverlayPrefs.isEnabled(appContext) || !Settings.canDrawOverlays(appContext)) return@post
            if (ballView == null) createBall(appContext)
            applyAlertStyle(domain, purpose)
        }
    }

    /** 验证完成/取消：恢复常态样式。 */
    @Synchronized
    fun hideVerificationAlert(sessionId: String? = null) {
        if (sessionId != null && sessionId != currentSessionId) return
        currentSessionId = null
        pendingDomain = null
        pendingPurpose = null
        main.post { if (currentSessionId == null) applyNormalStyle() }
    }

    /** 彻底移除悬浮球（用户关开关 / 服务销毁时调用）。 */
    @Synchronized
    fun hideAll() {
        lifecycleGeneration++
        main.post {
            currentSessionId = null
            pendingDomain = null
            pendingPurpose = null
            alertVisible = false
            teardownView()
        }
    }

    /** 主线程回收：先解绑 client/listener 再 destroy，避免回调打回已销毁的 WebView。 */
    private fun teardownView() {
        cancelMotion()
        touching = false
        normalAnchor = null
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        val view = ballView ?: return
        ballView = null
        lastPage = null
        view.setOnTouchListener(null)
        view.webViewClient = WebViewClient()
        runCatching { windowManager?.removeView(view) }
        runCatching { view.stopLoading() }
        runCatching { view.loadUrl("about:blank") }
        runCatching { view.removeAllViews() }
        runCatching { view.destroy() }
    }

    private fun createBall(context: Context) {
        val wm = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        windowManager = wm
        val d = context.resources.displayMetrics
        val ballPx = (BALL_SIZE_DP * d.density + 0.5f).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            ballPx,
            ballPx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (d.widthPixels - ballPx).coerceAtLeast(0)
            y = d.heightPixels / 3
        }

        val view = runCatching { buildBallView(context, params) }.getOrNull() ?: return
        ballView = view
        runCatching {
            wm.addView(view, params)
            geometry.y = d.heightPixels / 3
            displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            displayManager?.registerDisplayListener(displayListener, main)
            applyWindowSize(view, false)
            scheduleCollapse()
        }.onFailure {
            com.mina.legadostudio.mcp.StudioLog.add("overlay attach failed", "W", "overlay", it.javaClass.simpleName)
            teardownView()
        }
    }

    private fun buildBallView(context: Context, params: WindowManager.LayoutParams): WebView {
        val view = WebView(context)
        hardenSettings(view)
        // 窗口尺寸与页面视口一致：52dp 正方形时页面正好铺满，提醒态放宽后再换页。
        view.layoutParams = WindowManager.LayoutParams(
            params.width,
            params.height,
            params.type,
            params.flags,
            params.format,
        )
        view.setBackgroundColor(0x00000000)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.isLongClickable = false
        view.isHapticFeedbackEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        // 无 JS interface：不调用任何 addJavascriptInterface；JS 本身也已关闭。
        view.webViewClient = object : WebViewClient() {
            // 任何导航（含重定向、window.open、intent://）都不允许离开本地文档
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                !VerificationOverlayPage.isAllowedNavigation(request.url?.toString())

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(v: WebView, url: String?): Boolean =
                !VerificationOverlayPage.isAllowedNavigation(url)

            // 子资源一律拒绝：页面本身不引用任何资源，出现请求即视为异常
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                BLOCKED_RESPONSE

            @Deprecated("Deprecated in Java")
            override fun shouldInterceptRequest(v: WebView, url: String?): WebResourceResponse? = BLOCKED_RESPONSE

            override fun shouldOverrideKeyEvent(v: WebView, event: android.view.KeyEvent): Boolean = true
        }
        loadPage(view, VerificationOverlayPage.normalPage())
        alertVisible = false
        attachDragAndClick(context, view)
        return view
    }

    /** WebView 硬化：禁 JS、禁文件/content 访问、禁网络、禁持久化与地理位置。 */
    private fun hardenSettings(view: WebView) {
        val s: WebSettings = view.settings
        s.javaScriptEnabled = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.domStorageEnabled = false
        @Suppress("DEPRECATION") s.databaseEnabled = false
        s.setSupportMultipleWindows(false)
        s.mediaPlaybackRequiresUserGesture = true
        s.blockNetworkLoads = true
        s.blockNetworkImage = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        s.loadsImagesAutomatically = false
        s.setNeedInitialFocus(false)
        @Suppress("DEPRECATION") s.saveFormData = false
        s.userAgentString = OVERLAY_USER_AGENT
    }

    private var lastPage: String? = null
    private fun loadPage(view: WebView, html: String) {
        if (lastPage == html) return
        lastPage = html
        runCatching {
            view.loadDataWithBaseURL(
                VerificationOverlayPage.baseUrl(),
                html,
                "text/html",
                "utf-8",
                null,
            )
        }
    }

    private val geometry = OverlayGeometry()
    private var touching = false
    @Volatile private var lifecycleGeneration = 0L
    @Volatile private var serviceActive = false
    private var displayManager: android.hardware.display.DisplayManager? = null
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            touching = false
            cancelMotion()
            ballView?.let { applyWindowSize(it, alertVisible) }
            scheduleCollapse()
        }
    }
    private val collapse = Runnable {
        if (!touching && !alertVisible) {
            geometry.collapse()
            ballView?.let { applyWindowSize(it, false) }
        }
    }
    private fun cancelMotion() { main.removeCallbacks(collapse) }
    private fun scheduleCollapse() {
        cancelMotion()
        if (!touching && !alertVisible) main.postDelayed(collapse, 3000L)
    }
    fun setServiceActive(context: Context, active: Boolean) {
        serviceActive = active
        if (active) refresh(context) else hideAll()
    }
    private fun attachDragAndClick(context: Context, container: WebView) {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        var revealed = false
        var pointer = -1
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelMotion(); touching = true
                    pointer = event.getPointerId(0)
                    revealed = geometry.hidden
                    geometry.expand()
                    applyWindowSize(container, alertVisible)
                    startX = geometry.x; startY = geometry.y
                    downX = event.rawX; downY = event.rawY; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!touching || pointer < 0 || event.getPointerId(0) != pointer) return@setOnTouchListener true
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) dragging = true
                    if (dragging) {
                        geometry.move(startX + dx, startY + dy)
                        applyWindowSize(container, alertVisible)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!touching || pointer < 0) return@setOnTouchListener true
                    touching = false; pointer = -1
                    if (dragging) geometry.release() else if (!revealed) onBallClick(context)
                    applyWindowSize(container, alertVisible)
                    scheduleCollapse()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    touching = false; pointer = -1; dragging = false
                    cancelMotion(); geometry.release()
                    applyWindowSize(container, alertVisible); scheduleCollapse()
                }
            }
            true
        }
    }

    private fun onBallClick(context: Context) {
        val hasVerification = currentSessionId != null
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("route", if (hasVerification) "verification" else "mcp")
        }
        runCatching { context.startActivity(intent) }
    }

    private var normalAnchor: Pair<Int, Int>? = null
    private fun applyAlertStyle(domain: String, purpose: String) {
        val view = ballView ?: return
        if (!alertVisible) normalAnchor = geometry.x to geometry.y
        cancelMotion()
        touching = false
        geometry.expand()
        alertVisible = true
        // 先改窗口尺寸再换页：页面按新视口排版，气泡不会被 52dp 方形裁掉。
        applyWindowSize(view, alert = true)
        loadPage(view, VerificationOverlayPage.alertPage(domain, purpose))
    }

    private fun applyNormalStyle() {
        val view = ballView ?: return
        alertVisible = false
        normalAnchor?.let { (x, y) -> geometry.x = x; geometry.y = y }
        normalAnchor = null
        applyWindowSize(view, alert = false)
        scheduleCollapse()
    }

    /**
     * 窗口尺寸策略（纯尺寸，不改任何 Web/网络策略）：
     * - 常态：52dp 正方形，球完整可见；
     * - 提醒态：放宽到气泡尺寸（宽 ≤ 240dp、高 ≤ 110dp），并 clamp 到屏幕内，
     *   同时把 x 钳回屏内，避免气泡被屏幕边缘裁掉；
     * - 关闭提醒恢复 52dp 正方形。
     */
    private fun applyWindowSize(view: WebView, alert: Boolean) {
        val wm = windowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val d = view.resources.displayMetrics
        val bounds = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds
            else android.graphics.Rect(0, 0, d.widthPixels, d.heightPixels)
        val insets = if (Build.VERSION.SDK_INT >= 30) {
            val i = wm.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            android.graphics.Rect(i.left, i.top, i.right, i.bottom)
        } else android.graphics.Rect(0, (24 * d.density).toInt(), 0, (48 * d.density).toInt())
        val w = (bounds.width() - insets.left - insets.right).coerceAtLeast(1)
        val h = (bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
        val width = ((if (alert) ALERT_MAX_WIDTH_DP else BALL_SIZE_DP) * d.density + .5f).toInt().coerceAtMost(w)
        val height = ((if (alert) ALERT_MAX_HEIGHT_DP else BALL_SIZE_DP) * d.density + .5f).toInt().coerceAtMost(h)
        geometry.resize(w, h, width, height)
        params.width = geometry.width; params.height = height
        params.x = insets.left + geometry.x; params.y = insets.top + geometry.y
        runCatching { wm.updateViewLayout(view, params) }.onFailure {
            com.mina.legadostudio.mcp.StudioLog.add("overlay layout failed", "W", "overlay", it.javaClass.simpleName)
            teardownView()
        }
        if (!alert) loadPage(view, VerificationOverlayPage.normalPage(if (geometry.hidden) geometry.side else 0))
    }

    /** 圆球直径（dp）：48dp 球体 + 2dp 描边余量，页面按 48px 绘制。 */
    private const val BALL_SIZE_DP = 48f

    /** 提醒态窗口上限：气泡宽 240dp、高 110dp（球 52dp + 气泡两行）。 */
    private const val ALERT_MAX_WIDTH_DP = 240f
    private const val ALERT_MAX_HEIGHT_DP = 110f

    /** 不透出任何设备指纹的 UA；页面不联网，UA 只影响渲染。 */
    private const val OVERLAY_USER_AGENT = "StudioOverlay/1.0"

    /** 子资源统一 403：页面无外链，请求出现即拒绝，杜绝任何外站加载。 */
    private val BLOCKED_RESPONSE: WebResourceResponse by lazy {
        WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
    }

    /** 仅用于日志/调试：当前页面是否为验证提醒态。 */
    internal fun isAlertVisible(): Boolean = alertVisible

    /** 供宿主判断路由用（不暴露给页面）。 */
    internal fun hasPendingVerification(): Boolean = currentSessionId != null
}
