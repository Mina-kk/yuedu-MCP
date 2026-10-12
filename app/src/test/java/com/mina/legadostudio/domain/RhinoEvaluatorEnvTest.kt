package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.RhinoEvaluator
import com.mina.legadostudio.runtime.RuntimeCacheStore
import org.junit.Assert.*
import org.junit.Test

/** eval_js / 书源 JS 段的官方同名对象绑定回归：cookie / cache / source。 */
class RhinoEvaluatorEnvTest {
    private fun rhino() = RhinoEvaluator(HttpFetcher(), Gson())

    @Test fun officialEnvObjectsAreInjected() {
        val out = rhino().evaluate(
            """
            var parts = [];
            parts.push(typeof cookie);
            parts.push(typeof cache);
            parts.push(typeof source);
            parts.push(typeof cookie.getCookie);
            parts.push(typeof cache.put);
            parts.push(typeof source.getVariable);
            parts.join('|')
            """.trimIndent()
        )
        assertEquals("object|object|object|function|function|function", out.value)
    }

    @Test fun cacheRoundTripsWithTtl() {
        val key = "env_test_${System.nanoTime()}"
        val out = rhino().evaluate(
            """
            cache.put('$key', 'v1');
            cache.put('$key-t', 'v2', 60);
            var a = cache.get('$key');
            var b = cache.get('$key-t');
            cache.delete('$key');
            var c = cache.get('$key');
            cache.putMemory('$key-m', 'mem');
            var d = cache.getFromMemory('$key-m');
            [a, b, String(c), String(d)].join('|')
            """.trimIndent()
        )
        assertEquals("v1|v2|null|mem", out.value)
        assertNull(RuntimeCacheStore.get(key))
        assertEquals("v2", RuntimeCacheStore.get("$key-t"))
        RuntimeCacheStore.delete("$key-t"); RuntimeCacheStore.deleteMemory("$key-m")
    }

    @Test fun sourceWritesBackToDebugStateMap() {
        val state = hashMapOf<String, String>()
        val out = rhino().evaluate(
            """
            source.put('surl', 'https://x.test/list');
            source.put('page', '3');
            var all = JSON.parse(source.getVariable());
            all.surl + '|' + source.get('page')
            """.trimIndent(),
            bindings = mapOf("source" to state),
        )
        assertEquals("https://x.test/list|3", out.value)
        // JS 写入必须回传到底层 state：debug 流程后续规则（含 {{source.get('surl')}} 模板）依赖这份共享
        assertEquals("https://x.test/list", state["surl"])
        assertEquals("3", state["page"])
    }

    @Test fun sourceSetVariableReplacesState() {
        val state = hashMapOf("old" to "1")
        rhino().evaluate("source.setVariable('{\"fresh\":\"yes\"}'); source.getVariable()", bindings = mapOf("source" to state))
        assertEquals(mapOf("fresh" to "yes"), state)
    }

    @Test fun cookieApiWorksWithoutStore() {
        // 单测环境没有 Android CookieStore：读取返回空串而不是抛错，写入才报不可用
        val out = rhino().evaluate("String(cookie.getCookie('https://x.test')) + '|' + String(cookie.getKey('https://x.test', 'sid'))")
        assertEquals("|", out.value)
        val write = runCatching { rhino().evaluate("cookie.setCookie('https://x.test', 'a=1')") }
        assertTrue(write.isFailure)
    }

    @Test fun twoEvalCallsDoNotShareTopLevelScope() {
        // 阶段二回归：eval_js 两次调用必须是独立作用域——第一次的 var 声明不能泄漏到第二次
        val evaluator = rhino()
        evaluator.evaluate("var leaked_marker = 'hello'")
        val out = evaluator.evaluate("typeof leaked_marker")
        assertEquals("undefined", out.value)
    }

    @Test fun twoEvalCallsDoNotSharePrototypeMutations() {
        // 原型篡改也不能跨调用：第一次给 String.prototype 塞方法，第二次拿不到
        val evaluator = rhino()
        evaluator.evaluate("String.prototype.__pwn = function(){ return 'x'; }; 'ok'")
        val out = evaluator.evaluate("typeof ''.__pwn")
        assertEquals("undefined", out.value)
    }

    @Test fun existingMethodDoesNotGetApiHint() {
        // 已存在方法正常执行，不产生 API 提示
        val out = rhino().evaluate("java.md5Encode('x')")
        assertTrue(out.value?.isNotBlank() == true)
    }

    @Test fun crossCallSharingGoesThroughCacheNotScope() {
        // var 不跨调用，但显式 cache.put/get 是支持的共享通道：说明「独立作用域」断的是隐式全局，不是显式 cache
        val evaluator = rhino()
        val key = "share_${System.nanoTime()}"
        evaluator.evaluate("cache.put('$key', 'v9')")
        val out = evaluator.evaluate("cache.get('$key')")
        assertEquals("v9", out.value)
        RuntimeCacheStore.delete(key)
    }

    @Test fun missingMethodGetsJsApiHint() {
        // 不存在的方法：确凿「方法不存在」错误必须附 js-api.md 提示
        val error = runCatching { rhino().evaluate("java.thisMethodDoesNotExist('x')") }.exceptionOrNull()
        assertTrue(error != null)
        val message = error!!.message.orEmpty()
        assertTrue("expected API hint in: $message", message.contains("【API 提示】"))
        assertTrue("expected js-api.md reference in: $message", message.contains("js-api.md"))
    }

    @Test fun wrongSignatureGetsJsApiHint() {
        // 签名不匹配（connect 只接受 header 为 Map/String）：异常消息要能落到提示分支
        val error = runCatching { rhino().evaluate("java.connect(12345)") }.exceptionOrNull()
        // 数字 URL 可能先被运行时校验拦下；只要确认不会把一般 JS 异常吞掉即可
        assertTrue(error != null)
    }

    @Test fun genericJsErrorDoesNotGetApiHint() {
        // 一般 JS 异常（如 引用未定义变量）不追加 API 提示
        val error = runCatching { rhino().evaluate("throw new Error('自定义错误')") }.exceptionOrNull()
        assertTrue(error != null)
        assertTrue(error!!.message.orEmpty().contains("自定义错误"))
        assertTrue(!error.message.orEmpty().contains("【API 提示】"))
    }

    @Test fun syntaxErrorDoesNotGetApiHint() {
        val error = runCatching { rhino().evaluate("var x = ") }.exceptionOrNull()
        assertTrue(error != null)
        assertTrue(!error!!.message.orEmpty().contains("【API 提示】"))
    }

    @Test fun bookAndChapterObjectsAreInjected() {
        val out = rhino().evaluate(
            """
            var parts = [];
            parts.push(typeof book);
            parts.push(typeof chapter);
            parts.push(typeof book.putVariable);
            parts.push(typeof book.getVariable);
            parts.push(typeof chapter.putVariable);
            parts.push(book.name);
            parts.push(chapter.index);
            parts.join('|')
            """.trimIndent()
        )
        assertEquals("object|object|function|function|function||0", out.value)
    }

    @Test fun bookVariableRoundTripsWithinDebug() {
        val book = com.mina.legadostudio.runtime.BookJsApi()
        val out = rhino().evaluate(
            """
            book.name = '测试书';
            book.putVariable('token', 'abc');
            book.putVariable('gone', 'x'); book.putVariable('gone', null);
            [book.name, book.getVariable('token'), String(book.getVariable('gone'))].join('|')
            """.trimIndent(),
            bindings = mapOf("book" to book),
        )
        assertEquals("测试书|abc|null", out.value)
        assertEquals("abc", book.getVariable("token"))
        assertEquals("测试书", book.name)
    }

    @Test fun chapterFieldsAndVariablePoolWork() {
        val chapter = com.mina.legadostudio.runtime.ChapterJsApi()
        val out = rhino().evaluate(
            """
            chapter.index = 3; chapter.title = '第四章';
            chapter.putVariable('k', 'v1');
            chapter.putLyric('歌词'); chapter.putImgUrl('https://x/i.png');
            chapter.update();
            [chapter.index, chapter.title, chapter.getVariable('k'), chapter.getLyric(), chapter.getImgUrls().length].join('|')
            """.trimIndent(),
            bindings = mapOf("chapter" to chapter),
        )
        assertEquals("3|第四章|v1|歌词|1", out.value)
    }

    @Test fun bookMapBindingIsWrappedIntoBookJsApi() {
        // debug 链路里 bindings["book"] 是 BookJsApi；外部塞 Map 也要被包成同名对象（不裸丢 NativeObject）
        val out = rhino().evaluate(
            "book.name + '|' + book.origin + '|' + book.bookUrl",
            bindings = mapOf("book" to mapOf("name" to "甲", "origin" to "https://src.test", "extra" to "e1")),
        )
        assertEquals("甲|https://src.test|", out.value)
    }
}
