package com.mina.legadostudio.verification

/**
 * 悬浮球/验证提示的**受控本地页面**（纯 HTML + CSS 字符串，不含任何 JS）。
 *
 * 设计约束（业务 UI 一律 Web，不用 Android 原生控件绘制）：
 * - 页面完全离线：无 `<script>`、无外链 `<link>/<img>/<iframe>`、无 `url()`/`@import`，
 *   因此即使 WebView 被误配也发不出请求；WebView 侧仍另外禁用 JS/文件/content/网络访问；
 * - 无 JS interface、无 `<button>/<form>/<input>`：点击与拖动由宿主 `onTouchListener` 处理，
 *   那是系统交互层，不是业务控件；
 * - 所有动态文本（域名、用途）经 [escapeHtml] 转义并先过 [sanitizeDomain]/[sanitizePurpose] 钳制，
 *   不允许任何未转义插值；
 * - 主题取 vault 默认 lime/charcoal（`web-ui/src/lib/palettes.js` 的 `lime` 预设：
 *   c1=#CCFF4D, c2=#222222），保证与 Web UI 同一套配色。
 *
 * 纯 Kotlin、不依赖 android.webkit，JVM 单测可直接覆盖；WebView 的落地在 VerificationOverlayManager。
 */
object VerificationOverlayPage {

    /** 主题色：与 vault 默认 lime 预设一致。 */
    const val COLOR_LIME = "#CCFF4D"
    const val COLOR_CHARCOAL = "#222222"
    /** lime 底上的深色前景（palettes.js onC1），保证对比度。 */
    const val COLOR_ON_LIME = "#16210A"

    /** 域名最长展示字符数（含省略号），防止超长 host 撑破气泡。 */
    const val MAX_DOMAIN_CHARS = 64
    /** 用途/提示文案最长字符数。 */
    const val MAX_PURPOSE_CHARS = 28
    /** 页面 HTML 硬上限，防御性断言用。 */
    const val MAX_PAGE_CHARS = 16_000

    /** 球体绘制尺寸（CSS px）：窗口 52dp 时留 2dp 描边余量。 */
    const val BALL_CSS_PX = 48

    /** 气泡最大宽度（CSS px）：提醒态窗口上限 240dp，气泡不得超出。 */
    const val ALERT_BUBBLE_MAX_WIDTH_CSS_PX = 220

    /** 气泡内单行文本最大宽度（CSS px）：留出左右 padding。 */
    const val ALERT_LABEL_MAX_WIDTH_CSS_PX = 200

    /** 提醒态窗口最大高度（CSS px）：球 48 + 间距 6 + 两行气泡。 */
    const val ALERT_WINDOW_MAX_HEIGHT_CSS_PX = 110

    /**
     * loadDataWithBaseURL 的 baseURL：不透明、不可解析为任何真实 origin。
     * 只允许文档自身（含 fragment）导航，其它一律拦截。
     */
    const val BASE_URL = "about:blank"

    /** 圆球常态页：lime 圆底 + 「M」。 */
    fun normalPage(): String = document(
        body = """
            <div class="ball normal"><span class="glyph">M</span></div>
        """.trimIndent(),
    )

    /**
     * 验证提醒页：charcoal 圆底 + lime 描边 + 「M」，下方 lime 气泡显示
     * 「点击验证 · 域名」与可选用途。域名/用途均转义 + 钳制。
     */
    fun alertPage(domain: String, purpose: String = ""): String {
        val host = escapeHtml(sanitizeDomain(domain))
        val note = escapeHtml(sanitizePurpose(purpose))
        val bubbleText = if (host.isEmpty()) "点击验证" else "点击验证 · $host"
        val noteHtml = if (note.isEmpty()) "" else """<span class="note">$note</span>"""
        return document(
            body = """
                <div class="ball alert"><span class="glyph">M</span></div>
                <div class="bubble"><span class="label">$bubbleText</span>$noteHtml</div>
            """.trimIndent(),
        )
    }

    /**
     * 域名清洗：去掉控制字符/零宽字符/空白，只保留 host 允许的字符集，
     * 超长截断并补省略号。空结果表示「无域名可展示」。
     */
    fun sanitizeDomain(raw: String?): String {
        val cleaned = raw.orEmpty()
            .filter { it.code in 0x21..0x7E || it.isLetterOrDigit() }
            .filter { it !in "\"'<>`\\/" }
        return clamp(cleaned, MAX_DOMAIN_CHARS)
    }

    /** 用途文案清洗：折叠空白、剔除控制字符与尖括号/引号，超长截断。 */
    fun sanitizePurpose(raw: String?): String {
        val cleaned = raw.orEmpty()
            .filter { it.code >= 0x20 }
            .filter { it !in "<>\"'`" }
            .replace(Regex("\\s+"), " ")
            .trim()
        return clamp(cleaned, MAX_PURPOSE_CHARS)
    }

    private fun clamp(value: String, max: Int): String {
        if (value.length <= max) return value
        val keep = (max - 1).coerceAtLeast(0)
        return value.take(keep) + "…"
    }

    /** HTML 文本/属性转义（含引号与反引号），保证插值无法逃出文本节点。 */
    fun escapeHtml(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (c in raw) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                '`' -> sb.append("&#96;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 唯一允许的导航目标：文档自身（[BASE_URL] 可选带 fragment）；其余全部拒绝。 */
    fun isAllowedNavigation(url: String?): Boolean {
        val target = url?.trim().orEmpty()
        if (target.isEmpty()) return false
        val base = target.substringBefore('#')
        return base == BASE_URL && !target.startsWith("javascript:", ignoreCase = true)
    }

    fun baseUrl(): String = BASE_URL

    private fun document(body: String): String = """
        <!DOCTYPE html>
        <html lang="zh-CN"><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; script-src 'none'; connect-src 'none'; frame-src 'none'">
        <style>
        html,body{margin:0;padding:0;background:transparent;overflow:hidden;
          -webkit-user-select:none;user-select:none;-webkit-tap-highlight-color:transparent}
        body{display:flex;flex-direction:column;align-items:center;justify-content:flex-start}
        .ball{box-sizing:border-box;width:${BALL_CSS_PX}px;height:${BALL_CSS_PX}px;border-radius:50%;
          display:flex;align-items:center;justify-content:center;
          box-shadow:0 2px 8px rgba(0,0,0,.28)}
        .ball.normal{background:$COLOR_LIME;border:0}
        .ball.alert{background:$COLOR_CHARCOAL;border:2px solid $COLOR_LIME}
        .glyph{font:700 20px/1 -apple-system,"Noto Sans SC",Roboto,sans-serif;letter-spacing:.5px}
        .ball.normal .glyph{color:$COLOR_ON_LIME}
        .ball.alert .glyph{color:$COLOR_LIME}
        .bubble{margin-top:6px;max-width:${ALERT_BUBBLE_MAX_WIDTH_CSS_PX}px;box-sizing:border-box;
          display:flex;flex-direction:column;align-items:flex-start;gap:2px;
          padding:4px 10px;border-radius:12px;background:$COLOR_LIME;
          box-shadow:0 2px 8px rgba(0,0,0,.22)}
        .label{font:600 11px/1.25 -apple-system,"Noto Sans SC",Roboto,sans-serif;
          color:$COLOR_ON_LIME;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:${ALERT_LABEL_MAX_WIDTH_CSS_PX}px}
        .note{font:400 10px/1.2 -apple-system,"Noto Sans SC",Roboto,sans-serif;
          color:$COLOR_ON_LIME;opacity:.72;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:${ALERT_LABEL_MAX_WIDTH_CSS_PX}px}
        </style>
        </head><body>
        $body
        </body></html>
    """.trimIndent()
}
