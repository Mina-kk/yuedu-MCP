package com.mina.legadostudio.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * java.webView(html,url,js) 交接路径的纯逻辑测试（不碰 android.webkit）：
 * - 非空 html 走 loadDataWithBaseURL 计划，url 作 baseURL；
 * - url 必须是公网 http/https，危险 scheme / 回环私网被拒；
 * - html 大小上限；
 * - webJs 轮询脚本：返回 null 表示未就绪（异步就绪方案，替代同步 XHR 泵）。
 */
class WebViewLoadPlanTest {

    @Test fun blankHtmlFallsBackToLoadUrl() {
        val plan = WebViewLoadPlan.plan("https://www.aaawz.cc/book/1.html", "   ")
        assertNull(plan.embeddedHtml)
        assertEquals("https://www.aaawz.cc/book/1.html", plan.url)
    }

    @Test fun nullHtmlFallsBackToLoadUrl() {
        val plan = WebViewLoadPlan.plan("https://example.com/c/2", null)
        assertNull(plan.embeddedHtml)
    }

    @Test fun nonEmptyHtmlUsesBaseUrl() {
        val plan = WebViewLoadPlan.plan("https://www.aaawz.cc/book/1.html", "<div>交接文档</div>")
        assertEquals("<div>交接文档</div>", plan.embeddedHtml)
        assertEquals("https://www.aaawz.cc/book/1.html", plan.url)
    }

    @Test fun httpAndHttpsAccepted() {
        assertEquals("http://example.com/x", WebViewLoadPlan.plan("http://example.com/x", null).url)
        assertEquals("https://example.com/x", WebViewLoadPlan.plan("https://example.com/x", null).url)
    }

    @Test fun dangerousSchemesRejected() {
        listOf(
            "file:///sdcard/x.html",
            "javascript:alert(1)",
            "data:text/html,<b>x</b>",
            "content://com.x/y",
            "about:blank",
        ).forEach { bad ->
            val e = runCatching { WebViewLoadPlan.plan(bad, null) }.exceptionOrNull()
            assertTrue("expected rejection for $bad", e is IllegalArgumentException)
        }
    }

    @Test fun loopbackAndPrivateUrlsRejected() {
        listOf(
            "http://127.0.0.1:8080/x",
            "http://localhost/x",
            "http://10.0.0.2/x",
            "http://192.168.1.5/x",
            "http://172.16.0.9/x",
            "http://169.254.1.1/x",
            "http://2130706433/x",       // 127.0.0.1 十进制
            "http://0x7f000001/x",     // 127.0.0.1 十六进制
            "http://user@127.0.0.1/x",
            "https://[::1]/x",
        ).forEach { bad ->
            val e = runCatching { WebViewLoadPlan.plan(bad, null) }.exceptionOrNull()
            assertTrue("expected private rejection for $bad", e is IllegalArgumentException)
        }
    }

    @Test fun embeddedHtmlCannotEscapeToPrivateBase() {
        // 内嵌 html 的 baseURL 同样必须是公网地址——不允许借 html 通道访问回环/私网
        val e = runCatching { WebViewLoadPlan.plan("http://127.0.0.1/page", "<div>x</div>") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test fun oversizedHtmlRejected() {
        val big = "x".repeat(WebViewLoadPlan.MAX_EMBEDDED_HTML_CHARS + 1)
        val e = runCatching { WebViewLoadPlan.plan("https://example.com/", big) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        val ok = WebViewLoadPlan.plan("https://example.com/", "x".repeat(WebViewLoadPlan.MAX_EMBEDDED_HTML_CHARS))
        assertEquals(WebViewLoadPlan.MAX_EMBEDDED_HTML_CHARS, ok.embeddedHtml!!.length)
    }

    @Test fun blankUrlRejected() {
        assertTrue(runCatching { WebViewLoadPlan.plan("  ", null) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { WebViewLoadPlan.plan(null, null) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun pollScriptDefaultExtractsOuterHtml() {
        assertEquals("document.documentElement.outerHTML", WebViewLoadPlan.buildPollScript(null))
        assertEquals("document.documentElement.outerHTML", WebViewLoadPlan.buildPollScript("  "))
    }

    @Test fun pollScriptWrapsExpressionWithEval() {
        val script = WebViewLoadPlan.buildPollScript("document.querySelector('.c').innerHTML")
        // 统一先 eval 取脚本完成值（不再按 \breturn\b 嗅探分支）
        assertTrue(script.contains("r=eval("))
        assertTrue(script.contains("'__STUDIO_ERROR__'"))
        // 返回值 null 显式透出，交给轮询重试
        assertTrue(script.contains("return r==null?null:String(r)"))
    }

    @Test fun pollScriptKeepsExplicitReturn() {
        val script = WebViewLoadPlan.buildPollScript("var a=1; return a+1")
        assertTrue(script.contains("var a=1; return a+1"))
        // 顶层 return 走 eval→SyntaxError→IIFE 回退，不再是关键字嗅探后原样拼接
        assertTrue(script.contains("instanceof SyntaxError"))
        assertTrue(script.contains("(function(){"))
    }

    @Test fun pollScriptDoesNotMisfireOnReturnInsideStringLiteral() {
        // webJs 字符串/注释里含单词 return：旧 \breturn\b 嗅探会把它误判为「函数体」
        // 原样包进 IIFE 后顶层 return 非法 → 静默 undefined 轮询到超时；新实现统一先 eval。
        val script = WebViewLoadPlan.buildPollScript("document.querySelector('#a').innerText || 'return'")
        assertTrue(script.contains("r=eval("))
        assertTrue(script.contains("instanceof SyntaxError"))
    }

    @Test fun pollScriptFallsBackToIifeOnlyOnSyntaxError() {
        // 顶层 return 的唯一合法场景是函数体：eval 抛 SyntaxError 才套 (function(){...})() 重评，
        // 非 SyntaxError（如 ReferenceError）原样抛出转 __STUDIO_ERROR__，不会二次执行。
        val script = WebViewLoadPlan.buildPollScript("var a=1; return a+1")
        assertTrue(script.contains("catch(e0){if(!(e0 instanceof SyntaxError))throw e0;"))
    }

    @Test fun finalUrlRevalidationRejectsRedirectedPrivateTarget() {
        // 入口 url 合法但 WebView 落点到私网/危险 scheme：requirePublicHttpUrl 必须同样拒绝，
        // 不能以 statusCode=200 名义把私网目标写进日志/返回给规则。
        val e1 = runCatching {
            WebViewLoadPlan.requirePublicHttpUrl("http://192.168.1.1/admin", "webView finalUrl")
        }.exceptionOrNull()
        assertTrue(e1 is IllegalArgumentException)
        assertTrue(e1!!.message!!.contains("webView finalUrl"))
        val e2 = runCatching {
            WebViewLoadPlan.requirePublicHttpUrl("file:///etc/passwd", "webView finalUrl")
        }.exceptionOrNull()
        assertTrue(e2 is IllegalArgumentException)
        // 合法公网地址照常放行（重定向到公网 CDN 是正常场景）
        assertEquals(
            "https://cdn.example.com/x",
            WebViewLoadPlan.requirePublicHttpUrl("https://cdn.example.com/x", "webView finalUrl"),
        )
    }

    @Test fun pollScriptQuotesExpressionSafely() {
        val script = WebViewLoadPlan.buildPollScript("var t = \"a\nb\"; t")
        // 通过 JSONObject.quote 转义，不会出现裸换行打断 JS 字符串
        assertFalse(script.contains("a\nb"))
        assertTrue(script.contains("\\n"))
    }

    @Test fun pollScriptDoesNotUnwrapPromise() {
        // webview_render / java.webView 的 webJs 必须保持阅读端「null 重跑」：
        // Promise 完成值会被 String(r) 成 [object Promise]，不能悄悄 then。
        val script = WebViewLoadPlan.buildPollScript("return (async function(){return 1})()")
        assertFalse(script.contains("typeof r.then"))
        assertFalse(script.contains(WebViewLoadPlan.EVAL_TOKEN_PREFIX))
        assertTrue(script.contains("return r==null?null:String(r)"))
    }

    @Test fun newEvalTokenIsUniqueAndPrefixed() {
        val a = WebViewLoadPlan.newEvalToken()
        val b = WebViewLoadPlan.newEvalToken()
        assertTrue(a.startsWith(WebViewLoadPlan.EVAL_TOKEN_PREFIX))
        assertTrue(b.startsWith(WebViewLoadPlan.EVAL_TOKEN_PREFIX))
        assertTrue(a != b)
        assertEquals(WebViewLoadPlan.EVAL_TOKEN_PREFIX.length + 32, a.length)
    }

    @Test fun evalPollScriptRejectsBlankScriptOrToken() {
        assertTrue(runCatching { WebViewLoadPlan.buildEvalPollScript("  ", "t") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { WebViewLoadPlan.buildEvalPollScript("1", "  ") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun evalPollScriptBakesTokenAndChecksWindowFirst() {
        val token = "__studio_eval_deadbeef"
        val js = WebViewLoadPlan.buildEvalPollScript("Promise.resolve(1)", token)
        // token 出现在 window[k] 判定之前，后续轮询同 token 不重跑
        val tokenAt = js.indexOf("\"$token\"")
        val windowAt = js.indexOf("window[k]")
        val evalAt = js.indexOf("r=eval(")
        assertTrue(tokenAt >= 0)
        assertTrue(windowAt >= 0)
        assertTrue(evalAt > windowAt)
        assertTrue(js.indexOf("if(st)") < evalAt)
    }

    @Test fun evalPollScriptPendingFulfilledRejectAndCleanup() {
        val js = WebViewLoadPlan.buildEvalPollScript("1", "__studio_eval_x")
        assertTrue(js.contains("st.s==='p')return null"))
        assertTrue(js.contains("return '__STUDIO_ERROR__'+st.v"))
        assertTrue(js.contains("return String(st.v)"))
        assertTrue(js.contains("delete window[k]"))
        assertTrue(js.contains("st.h!==location.href"))
    }

    @Test fun evalPollScriptThenCatchPersistsStateWithoutRerun() {
        val js = WebViewLoadPlan.buildEvalPollScript("return (async function(){return 1})()", "__studio_eval_p")
        assertTrue(js.contains("typeof r.then==='function'"))
        assertTrue(js.contains("window[k]={s:'p',h:location.href,i:id}"))
        assertTrue(js.contains("r.then(function(v)"))
        assertTrue(js.contains("c.i!==id"))
        assertTrue(js.contains("{s:'f',v:v,h:location.href,i:id}"))
        assertTrue(js.contains("{s:'r',v:e,h:location.href,i:id}"))
        // pending 立刻 return null，不把 Promise 交给 String(r)
        val thenAt = js.indexOf("r.then(")
        val nonPromiseStringAt = js.lastIndexOf("return r==null?null:String(r)")
        assertTrue(thenAt >= 0)
        assertTrue(nonPromiseStringAt > thenAt)
    }

    @Test fun evalPollScriptKeepsNonPromiseNullRetryAndSyntaxErrorFallback() {
        val js = WebViewLoadPlan.buildEvalPollScript("var a=1; return a+1", "__studio_eval_y")
        assertTrue(js.contains("instanceof SyntaxError"))
        assertTrue(js.contains("return r==null?null:String(r)"))
        assertTrue(js.contains("'__STUDIO_ERROR__'"))
        assertTrue(js.contains("var a=1; return a+1"))
    }

    @Test fun evalPollScriptQuotesUserSourceSafely() {
        val js = WebViewLoadPlan.buildEvalPollScript("var t = \"a\nb\"; t", "__studio_eval_q")
        assertFalse(js.contains("a\nb"))
        assertTrue(js.contains("\\n"))
    }

    @Test fun mcpWebviewEvalWiresPromiseUnwrapAndLeavesRenderPoll() {
        val mcp = listOf(
            File("src/main/java/com/mina/legadostudio/mcp/McpCaptureTools.kt"),
            File("app/src/main/java/com/mina/legadostudio/mcp/McpCaptureTools.kt"),
        ).first { it.isFile }.readText()
        val evalStart = mcp.indexOf("server.tool(\"webview_eval\"")
        val evalEnd = mcp.indexOf("server.tool(\"list_captures\"")
        assertTrue(evalStart >= 0 && evalEnd > evalStart)
        val evalBlock = mcp.substring(evalStart, evalEnd)
        assertTrue(evalBlock.contains("Promise"))
        assertTrue(evalBlock.contains("token"))
        assertTrue(evalBlock.contains("webViewCapture.eval"))
        assertFalse(evalBlock.contains("webViewLoader.load"))

        val renderStart = mcp.indexOf("server.tool(\"webview_render\"")
        val renderBlock = mcp.substring(renderStart, evalStart)
        assertTrue(renderBlock.contains("webViewLoader.load"))
        assertFalse(renderBlock.contains("buildEvalPollScript"))

        val engine = listOf(
            File("src/main/java/com/mina/legadostudio/verification/WebViewCaptureEngine.kt"),
            File("app/src/main/java/com/mina/legadostudio/verification/WebViewCaptureEngine.kt"),
        ).first { it.isFile }.readText()
        assertTrue(engine.contains("WebViewLoadPlan.buildPollScript(webJs)"))
        assertTrue(engine.contains("WebViewLoadPlan.buildEvalPollScript(script, token)"))
    }
}
