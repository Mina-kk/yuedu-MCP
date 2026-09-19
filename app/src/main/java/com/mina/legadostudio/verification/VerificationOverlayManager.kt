package com.mina.legadostudio.verification

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mina.legadostudio.MainActivity
import com.mina.legadostudio.R

/**
 * 常驻圆形悬浮球（圆底 + 「M」字样）：
 * - 常态：主题蓝圆底 + 「M」；
 * - 验证时：橙色圆底 + 「点击验证 · 域名」小气泡；
 * - 可自由拖拽到任意位置；松手时若靠近屏幕左右边缘则吸附并半隐（一半伸出屏外），
 *   验证气泡可见时吸附保持全显；
 * - 点击跳转 MCP 主页（有验证时直达验证中心）。
 */
object VerificationOverlayManager {
    private var windowManager: WindowManager? = null
    private var ballView: View? = null
    private var bubbleView: TextView? = null
    @Volatile private var currentSessionId: String? = null
    @Volatile private var pendingDomain: String? = null

    private val main = Handler(Looper.getMainLooper())

    /** 按当前开关/权限/验证状态刷新悬浮球（服务启停、权限变化、验证到来时都调用）。 */
    @Synchronized
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        if (!OverlayPrefs.isEnabled(appContext) || !Settings.canDrawOverlays(appContext)) {
            hideAll()
            return
        }
        if (ballView == null) createBall(appContext)
        val sid = currentSessionId
        val dom = pendingDomain
        if (sid != null && dom != null) applyAlertStyle(dom) else applyNormalStyle()
    }

    /** 验证到来时：若悬浮球开着，切到提醒样式。 */
    @Synchronized
    fun showVerificationAlert(context: Context, domain: String, sessionId: String, purpose: String = "") {
        currentSessionId = sessionId
        pendingDomain = domain
        main.post {
            val appContext = context.applicationContext
            if (!OverlayPrefs.isEnabled(appContext) || !Settings.canDrawOverlays(appContext)) return@post
            if (ballView == null) createBall(appContext)
            applyAlertStyle(domain)
        }
    }

    /** 验证完成/取消：恢复常态样式。 */
    @Synchronized
    fun hideVerificationAlert(sessionId: String? = null) {
        if (sessionId != null && sessionId != currentSessionId) return
        currentSessionId = null
        pendingDomain = null
        main.post { applyNormalStyle() }
    }

    /** 彻底移除悬浮球（用户关开关 / 服务销毁时调用）。 */
    @Synchronized
    fun hideAll() {
        main.post {
            ballView?.let { runCatching { windowManager?.removeView(it) } }
            ballView = null
            bubbleView = null
            currentSessionId = null
            pendingDomain = null
        }
    }

    private fun createBall(context: Context) {
        val wm = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        windowManager = wm
        val d = context.resources.displayMetrics
        val dp = { v: Float -> (v * d.density + 0.5f).toInt() }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = d.widthPixels - dp(64f)
            y = d.heightPixels / 3
        }

        val ball = buildBallView(context, dp, params)
        ballView = ball
        runCatching { wm.addView(ball, params) }.onFailure { ballView = null }
    }

    private fun buildBallView(context: Context, dp: (Float) -> Int, params: WindowManager.LayoutParams): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val ballSize = dp(48f)
        val ball = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ballSize, ballSize)
            background = context.getDrawable(R.drawable.overlay_ball_bg)
            id = android.R.id.icon
        }

        val label = TextView(context).apply {
            text = "M"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ballSize, ballSize)
        }
        ball.addView(label)

        val bubble = TextView(context).apply {
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = context.getDrawable(R.drawable.overlay_bubble_bg)
            visibility = View.GONE
            maxLines = 1
            setSingleLine()
            id = android.R.id.text1
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6f) }
            layoutParams = lp
        }
        bubbleView = bubble

        container.addView(ball)
        container.addView(bubble)

        attachDragAndClick(context, container, params)
        return container
    }

    private fun attachDragAndClick(context: Context, container: View, params: WindowManager.LayoutParams) {
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
    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val wm = windowManager ?: return
        val d = view.resources.displayMetrics
        val screenW = d.widthPixels
        val screenH = d.heightPixels
        val ballWidth = (48f * d.density + 0.5f).toInt()
        val centerX = params.x + view.width / 2
        val clampedY = params.y.coerceIn(0, (screenH - view.height).coerceAtLeast(0))
        val targetX = when {
            bubbleView?.visibility == View.VISIBLE ->
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
        context.startActivity(intent)
    }

    private fun applyAlertStyle(domain: String) {
        val ball = ballView?.findViewById<LinearLayout>(android.R.id.icon) ?: return
        val bubble = bubbleView ?: return
        ball.background = ball.context.getDrawable(R.drawable.overlay_ball_alert)
        bubble.text = "点击验证 · $domain"
        bubble.visibility = View.VISIBLE
    }

    private fun applyNormalStyle() {
        val ball = ballView?.findViewById<LinearLayout>(android.R.id.icon) ?: return
        val bubble = bubbleView ?: return
        ball.background = ball.context.getDrawable(R.drawable.overlay_ball_bg)
        bubble.visibility = View.GONE
    }
}
