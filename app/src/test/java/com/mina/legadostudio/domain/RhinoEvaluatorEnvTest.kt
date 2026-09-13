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
}
