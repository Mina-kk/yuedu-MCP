package com.mina.legadostudio.verification

import com.mina.legadostudio.domain.LogFilterUtils
import java.util.UUID

/**
 * `java.webView(html, url, js)` / WebViewPageLoader 的纯逻辑判定与注入脚本构造。
 * 与 android.webkit 无关，JVM 单测可直接覆盖；WebViewPageLoader 只负责把它们落到 WebView 上。
 */
object WebViewLoadPlan {

    /** 内嵌 HTML 上限：与抓取通道 maxBodyBytes=4_000_000 同量级，挡住超大 payload 卡死渲染。 */
    const val MAX_EMBEDDED_HTML_CHARS = 4_000_000

    /** webJs 轮询固定间隔（毫秒）。返回 null = 未就绪，到 load() 总超时为止，不用同步 XHR 泵。 */
    const val POLL_INTERVAL_MS = 300L

    /** webview_eval 挂到 window 上的 Promise 状态键前缀；每次 eval 再拼唯一后缀。 */
    const val EVAL_TOKEN_PREFIX = "__studio_eval_"

    /**
     * 校验后的加载计划。
     * [embeddedHtml] 非空 → `loadDataWithBaseURL(baseUrl, html)`；为 null → `loadUrl(url)`。
     */
    data class Plan(
        val url: String,
        val embeddedHtml: String?,
    )

    /**
     * 校验 url + 可选内嵌 html，返回加载计划；不合法直接抛 IllegalArgumentException。
     * 规则：
     * - url 必须是合法 http/https（data/javascript/file/content 等 scheme 一律拒绝）；
     * - url 不允许是回环/私网地址（与 HttpLogRecorder/LogFilterUtils 同一口径）；
     * - html 仅 null 或全空白时退回 loadUrl；非空 html 走 loadDataWithBaseURL 且受大小上限约束。
     */
    fun plan(url: String?, html: String?): Plan {
        return Plan(requirePublicHttpUrl(url, "webView url"), html?.takeIf { it.isNotBlank() }?.also { embedded ->
            require(embedded.length <= MAX_EMBEDDED_HTML_CHARS) {
                "webView html 超过 ${MAX_EMBEDDED_HTML_CHARS} 字符上限（当前 ${embedded.length}）"
            }
        })
    }

    /**
     * 单一校验口径：非空 + http/https scheme + 非回环/私网。
     * 入口 url 与 WebView 加载结束后的 finalUrl 共用——页面重定向到
     * file/javascript/私网地址时不能带着「已通过校验」的假设透传。
     */
    fun requirePublicHttpUrl(url: String?, label: String = "webView url"): String {
        val target = url?.trim().orEmpty()
        require(target.isNotEmpty()) { "$label 不能为空" }
        val scheme = target.substringBefore(':').lowercase()
        require(target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
            "$label 仅支持 http/https，拒绝 scheme=${scheme.ifBlank { "无" }}: $target"
        }
        require(!LogFilterUtils.isLoopbackOrPrivate(target)) { "$label 不允许回环/私网地址：$target" }
        return target
    }

    /**
     * 构造注入 WebView 的取值脚本：
     * - webJs 为空 → 直接取 document.documentElement.outerHTML；
     * - 先 `eval(<webJs>)`：表达式与「var 声明+末尾表达式」都按脚本完成值取回；
     *   eval 抛 SyntaxError（顶层 `return` 的唯一合法场景）才退回 `(function(){<webJs>})()` 形态重评，
     *   不用 \\breturn\\b 关键字嗅探——字符串/注释/正则字面量里出现单词 return 曾被误判成
     *   「带 return 的函数体」而静默 undefined 轮询到超时；合法脚本不会被求值两次；
     * - 异常转成 `__STUDIO_ERROR__<msg>` 哨兵，JS 返回 null/undefined → 返回 null 交给上层重试轮询。
     */
    fun buildPollScript(webJs: String?): String {
        if (webJs.isNullOrBlank()) return "document.documentElement.outerHTML"
        val src = quoteJsString(webJs)
        return "(function(){try{var r;try{r=eval($src);}" +
            "catch(e0){if(!(e0 instanceof SyntaxError))throw e0;r=eval(\"(function(){\"+$src+\"})()\");}" +
            "return r==null?null:String(r);}catch(e){return '__STUDIO_ERROR__'+e;}})()"
    }

    /** webview_eval 一次调用一枚 token，挂到 window 上跨 300ms 轮询复用。 */
    fun newEvalToken(): String = EVAL_TOKEN_PREFIX + UUID.randomUUID().toString().replace("-", "")

    /**
     * webview_eval 专用注入脚本（**不要**给 webview_render / java.webView 的 webJs 用）。
     *
     * 与 [buildPollScript] 的差异：脚本完成值若是 thenable，只 eval 一次，把状态挂到
     * `window[token]`，后续同 token 轮询不重跑用户脚本——
     * pending → JS `null`（Kotlin 侧按未就绪继续轮询）；
     * fulfilled → `String(原值)`（含 fulfilled null/undefined → `"null"`/`"undefined"`）；
     * reject / then 抛错 → `__STUDIO_ERROR__`。
     * 非 Promise 仍是 `null` 重跑、有值 `String(r)`，与旧 eval 行为一致。
     * 页面跳转（`location.href` 对不上）丢弃旧 token 并在新文档上重新首轮执行。
     */
    fun buildEvalPollScript(script: String, token: String): String {
        require(script.isNotBlank()) { "webview_eval script 不能为空" }
        require(token.isNotBlank()) { "webview_eval token 不能为空" }
        val src = quoteJsString(script)
        val tok = quoteJsString(token)
        return "(function(){try{" +
            "var k=$tok,st=window[k];" +
            "if(st){" +
            "if(st.h!==location.href){try{delete window[k]}catch(eD){window[k]=undefined}}" +
            "else if(st.s==='p')return null;" +
            "else if(st.s==='r'){try{delete window[k]}catch(eR){}return '__STUDIO_ERROR__'+st.v}" +
            "else{try{delete window[k]}catch(eF){}return String(st.v)}" +
            "}" +
            "var r;try{r=eval($src);}" +
            "catch(e0){if(!(e0 instanceof SyntaxError))throw e0;r=eval(\"(function(){\"+$src+\"})()\");}" +
            "if(r!=null&&(typeof r==='object'||typeof r==='function')&&typeof r.then==='function'){" +
            "var id=((st&&st.i)||0)+1;" +
            "window[k]={s:'p',h:location.href,i:id};" +
            "try{r.then(function(v){var c=window[k];if(!c||c.i!==id)return;window[k]={s:'f',v:v,h:location.href,i:id}}," +
            "function(e){var c=window[k];if(!c||c.i!==id)return;window[k]={s:'r',v:e,h:location.href,i:id}})}" +
            "catch(eT){try{delete window[k]}catch(eX){}return '__STUDIO_ERROR__'+eT}" +
            "return null;" +
            "}" +
            "return r==null?null:String(r);" +
            "}catch(e){return '__STUDIO_ERROR__'+e}})()"
    }

    /**
     * 把任意字符串转成合法 JS 字符串字面量（双引号包裹）。
     * 不用 org.json.JSONObject.quote：JVM 单测里 Android stub 不可用，且输出语义与 JSONObject.quote 一致。
     */
    fun quoteJsString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c.code in 0x7F..0x9F) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
