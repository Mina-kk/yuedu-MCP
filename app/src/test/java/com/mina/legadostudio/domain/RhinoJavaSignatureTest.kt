package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.LegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import com.mina.legadostudio.runtime.RuntimeCacheStore
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** 真机修书源复盘回归：java.get/post/head 的 timeout 重载、null headers 警告、签名提示改写。 */
class RhinoJavaSignatureTest {
    private fun rhino() = RhinoEvaluator(HttpFetcher(), Gson())

    private fun runtime() = EmbeddedLegadoRuntime(
        HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson())
    )

    @Test fun getWithHeadersAndTimeoutHitsNetworkLikeRealLegado() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok-body").setHeader("Set-Cookie", "sid=abc; Path=/"))
            server.start()
            // 真机案例形态：java.get(url, hashMap, 15000) —— JS 对象 headers + 毫秒超时
            val js = """
                var h = {"X-Test":"yes"};
                var r = java.get("${server.url("/p")}", h, 15000);
                [r.statusCode(), r.body(), String(r.url()).indexOf("http") == 0, r.cookies().sid].join('|')
            """.trimIndent()
            val out = rhino().evaluate(js)
            assertEquals("200|ok-body|true|abc", out.value)
            assertEquals("yes", server.takeRequest().getHeader("X-Test"))
        }
    }

    @Test fun getAcceptsAllTimeoutNumberShapes() {
        // int/long/double 形态的毫秒超时必须都能匹配到重载（不实际发请求，只验证签名解析到方法）
        val js = """
            var kinds = [];
            try { java.get('https://127.0.0.1:1/x', {}, 15000); } catch(e) { kinds.push('i'); }
            try { java.get('https://127.0.0.1:1/x', {}, 15000.5); } catch(e) { kinds.push('d'); }
            try { java.head('https://127.0.0.1:1/x', {}, '15000'); } catch(e) { kinds.push('s'); }
            try { java.post('https://127.0.0.1:1/x', 'b', {}, 15000); } catch(e) { kinds.push('p'); }
            kinds.join(',')
        """.trimIndent()
        // 不抛 EvaluatorException = 重载命中；网络错误进 catch 路径
        val out = rhino().evaluate(js)
        assertEquals("i,d,s,p", out.value)
    }

    @Test fun nullHeadersAcceptedButWarns() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("n"))
            server.start()
            val out = rhino().evaluate("java.get('${server.url("/n")}', null).body()")
            assertEquals("n", out.value)
            assertTrue(out.logs.any { it.contains("null headers") && it.contains("HashMap") })
        }
    }

    @Test fun postSendsRawBodyAndEncodeURIUsesPercent20() = runBlocking {
        // java.post：body 原样发送（JS 侧已自行编码，不再二次编码），缺省 Content-Type form-urlencoded
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("p-ok"))
            server.start()
            val js = """
                var body = "keyboard=" + java.encodeURI(key) + "&tempid=1";
                var r = java.post("${server.url("/e/search")}", body, {"X-Post":"yes"});
                r.body()
            """.trimIndent()
            val out = rhino().evaluate(js, bindings = mapOf("key" to "万相 之王+100%&"))
            assertEquals("p-ok", out.value)
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("java.post 请求应在 5 秒内到达", recorded)
            checkNotNull(recorded)
            assertEquals("POST", recorded.method)
            assertEquals("/e/search", recorded.path)
            assertEquals("keyboard=%E4%B8%87%E7%9B%B8%20%E4%B9%8B%E7%8E%8B%2B100%25%26&tempid=1", recorded.body.readUtf8())
            assertEquals("application/x-www-form-urlencoded; charset=utf-8", recorded.getHeader("Content-Type"))
            assertEquals("yes", recorded.getHeader("X-Post"))
        }
    }

    @Test fun postSendsRawKeyBodyUnencoded() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("raw-ok"))
            server.start()
            val js = """
                var body = "keyboard=" + key + "&tempid=1";
                var r = java.post("${server.url("/e/search")}", body, {"X-Post":"yes"});
                r.body()
            """.trimIndent()
            val out = rhino().evaluate(js, bindings = mapOf("key" to "万相 之王+100%&"))
            assertEquals("raw-ok", out.value)
            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("java.post 请求应在 5 秒内到达", recorded)
            checkNotNull(recorded)
            assertEquals("POST", recorded.method)
            assertEquals("/e/search", recorded.path)
            assertEquals("keyboard=万相 之王+100%&&tempid=1", recorded.body.readUtf8())
            assertEquals("application/x-www-form-urlencoded; charset=utf-8", recorded.getHeader("Content-Type"))
            assertEquals("yes", recorded.getHeader("X-Post"))
        }
    }

    @Test fun unsupportedSignatureRewritesToSandboxLimit() {
        // 沙箱确实没有的重载（4 参 java.get：url,headers,timeout,extra）：报「方法不存在」且附验收环境限制说明
        val err = runCatching {
            rhino().evaluate("java.get('https://x.test/', {}, 15000, 'extra')")
        }.exceptionOrNull()
        assertTrue(err != null)
        val message = err!!.message.orEmpty()
        assertTrue("expected 签名提示 in: $message", message.contains("沙箱不支持该签名"))
        assertTrue("expected 验收环境限制 in: $message", message.contains("验收环境限制，非书源错误"))
        assertTrue(message.contains("【API 提示】"))
    }

    @Test fun debugReportCarriesJsLogsAndCacheSnapshot() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/1'>n</a></div>"))
            server.start()
            val key = "snap_${System.nanoTime()}"
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "searchUrl":"search?q={{key}}",
              "ruleSearch":{"bookList":"<js>java.log('ls-line-1');cache.putMemory('$key','mv');cache.put('$key-d','dv',60);['<a href=\"/b/1\">x</a>']</js>","name":"a@text","bookUrl":"a@href"},
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
            }"""
            val report = runtime().debug(source, "k") as LegadoRuntime.DebugReport
            assertTrue(report.jsLogs?.any { it.contains("ls-line-1") } == true)
            assertEquals("mv", report.cacheSnapshot?.get(key))
            assertTrue(report.recentCachePuts?.any { it.contains("putMemory") && it.contains(key) } == true)
            assertTrue(report.recentCachePuts?.any { it.contains("put") && it.contains("$key-d") } == true)
            // 序列化出 JSON 键（MCP 端按名字读）
            val json = Gson().toJson(report)
            assertTrue(json.contains("\"jsLogs\""))
            assertTrue(json.contains("\"cacheSnapshot\""))
            assertTrue(json.contains("\"recentCachePuts\""))
            RuntimeCacheStore.delete("$key-d"); RuntimeCacheStore.deleteMemory(key)
        }
    }

    @Test fun contentReportCarriesLengthAndPreview() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div id='c'>床前明月光疑是地上霜举头望明月低头思故乡</div>"))
            server.start()
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@text"}
            }"""
            val report = runtime().debug(source, "--${server.url("c1")}")
            assertEquals(20, report.contentLength)
            assertTrue(report.contentPreview.orEmpty().startsWith("床前明月光"))
            assertTrue(report.lines.any { it.contains("正文字数") && it.contains("预览") })
            val json = Gson().toJson(report)
            assertTrue(json.contains("\"contentLength\""))
            assertTrue(json.contains("\"contentPreview\""))
        }
    }

    @Test fun jsLibErrorAttachesLibLineContext() {
        // jsLib 第 2 行的函数体炸掉：行号超出规则脚本但落在 jsLib 内 → 标注「可能对应 jsLib」
        val lib = "function ok(){return 1}\nfunction boom(){ throw new Error('lib-boom'); }"
        val err = runCatching {
            rhino().evaluate("boom()", bindings = mapOf(RhinoEvaluator.BINDING_JSLIB to { lib }))
        }.exceptionOrNull()
        assertTrue(err != null)
        val message = err!!.message.orEmpty()
        assertTrue("expected jsLib 行归属 in: $message", message.contains("jsLib 第2行"))
        assertTrue(message.contains("lib-boom"))
    }

    @Test fun ruleJsErrorAttachesRuleLineContext() {
        val js = "var a = 1;\nvar b = 2;\nthrow new Error('rule-boom');\nvar c = 3;"
        val err = runCatching { rhino().evaluate(js) }.exceptionOrNull()
        assertTrue(err != null)
        val message = err!!.message.orEmpty()
        assertTrue("expected 规则 JS 行归属 in: $message", message.contains("规则 JS 第3行"))
        assertTrue(message.contains("rule-boom"))
    }

    @Test fun oldDebugReportFieldsUntouched() = runBlocking {
        // 新字段缺省为 null 时序列化仍带键但值为 null（向后兼容：读方按可空处理）
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<div class='book'><a href='/b/1'>n</a></div>"))
            server.start()
            val source = """{
              "bookSourceName":"测试","bookSourceUrl":"${server.url("/")}",
              "searchUrl":"search?q={{key}}",
              "ruleSearch":{"bookList":".book","name":"a@text","bookUrl":"a@href"},
              "ruleToc":{"chapterList":"a"},"ruleContent":{"content":"#c@html"}
            }"""
            val report = runtime().debug(source, "k")
            assertNull(report.jsLogs)
            assertNull(report.cacheSnapshot)
            assertNull(report.contentLength)
        }
    }
}
