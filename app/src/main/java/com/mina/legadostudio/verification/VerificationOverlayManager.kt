package com.mina.legadostudio.verification

import android.animation.ValueAnimator
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
 * - 常态：lime 圆底 + 「M」；验证时：charcoal 圆底 + lime 描边 + 「点击验证 · 域名」气泡；
 * - 可自由拖拽；松手时若靠近屏幕左右边缘则吸附并半隐（一半伸出屏外），
 *   验证气泡可见时吸附保持全显；
 * - 点击跳转 MCP 主页（有验证时直达验证中心）。
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
        main.post {
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
        main.post {
            val appContext = context.applicationContext
            if (!OverlayPrefs.isEnabled(appContext) || !Settings.canDrawOverlays(appContext)) return@post
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
        main.post { applyNormalStyle() }
    }

    /** 彻底移除悬浮球（用户关开关 / 服务销毁时调用）。 */
    @Synchronized
    fun hideAll() {
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
        val view = ballView ?: return
        ballView = null
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
            gravity = Gravity.TOP or Gravity.START
            x = d.widthPixels - ballPx
            y = d.heightPixels / 3
        }

        val view = runCatching { buildBallView(context, params) }.getOrNull() ?: return
        ballView = view
        runCatching { wm.addView(view, params) }.onFailure {
            ballView = null
            runCatching { view.destroy() }
        }
    }

    private fun buildBallView(context: Context, params: WindowManager.LayoutParams): WebView {
        val view = WebView(context)
        hardenSettings(view)
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
        attachDragAndClick(context, view, params)
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

    private fun loadPage(view: WebView, html: String) {
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

    private fun attachDragAndClick(context: Context, container: WebView, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var dragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (!dragging && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) dragging = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { windowManager?.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onBallClick(context) else snapToEdge(container, params)
                    true
                }
                else -> false
            }
        }
    }

    /** 松手归位：靠近左右边缘才吸附；吸附后球半隐（验证气泡可见时保持全显贴边），否则停在原地，y 始终钳制在屏内。 */
    private fun snapToEdge(view: WebView, params: WindowManager.LayoutParams) {
        val wm = windowManager ?: return
        val d = view.resources.displayMetrics
        val screenW = d.widthPixels
        val screenH = d.heightPixels
        val ballWidth = (BALL_SIZE_DP * d.density + 0.5f).toInt()
        val centerX = params.x + view.width / 2
        val clampedY = params.y.coerceIn(0, (screenH - view.height).coerceAtLeast(0))
        val targetX = when {
            alertVisible ->
                if (centerX < screenW / 2) 0 else screenW - view.width
            centerX < ballWidth -> -view.width / 2
            screenW - centerX < ballWidth -> screenW - view.width / 2
            else -> params.x
        }
        if (targetX == params.x && clampedY == params.y) return
        val startX = params.x
        val startY = params.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                params.x = (startX + (targetX - startX) * f).toInt()
                params.y = (startY + (clampedY - startY) * f).toInt()
                runCatching { wm.updateViewLayout(view, params) }
            }
            start()
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

    private fun applyAlertStyle(domain: String, purpose: String) {
        val view = ballView ?: return
        alertVisible = true
        loadPage(view, VerificationOverlayPage.alertPage(domain, purpose))
    }

    private fun applyNormalStyle() {
        val view = ballView ?: return
        alertVisible = false
        loadPage(view, VerificationOverlayPage.normalPage())
    }

    /** 圆球直径（dp）：48dp 球体 + 2dp 描边余量，页面按 48px 绘制。 */
    private const val BALL_SIZE_DP = 52f

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
