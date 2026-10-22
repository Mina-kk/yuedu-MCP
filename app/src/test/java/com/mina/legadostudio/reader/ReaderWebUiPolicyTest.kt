package com.mina.legadostudio.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持续 WebView 会话的纯策略（不依赖 android.webkit，JVM 可测）：
 * 严格同源导航、子资源私网阻断、不同端口拒绝、selector/文本的脚本转义、
 * 隐私字段筛选、分页与上限，以及快照解析的 origin 复核。
 *
 * 这些用例只证明策略与固定脚本的构造正确，**不证明真机上页面交互可用**。
 */
class ReaderWebUiPolicyTest {

    private val lan = ReaderWebUiPolicy.Origin.fromOriginString("http://192.168.1.8:1122")!!
    private val loopback = ReaderWebUiPolicy.Origin.fromOriginString("http://127.0.0.1:1162")!!
    private val publicHttps = ReaderWebUiPolicy.Origin.fromOriginString("https://reader.example.com")!!

    // ---- origin 解析与规范化 ----

    @Test fun originNormalizesSchemeHostAndDefaultPort() {
        assertEquals("http", lan.scheme)
        assertEquals("192.168.1.8", lan.host)
        assertEquals(1122, lan.port)
        assertEquals("http://192.168.1.8:1122/", lan.rootUrl())

        assertEquals(ReaderWebUiPolicy.Origin("https", "reader.example.com", 443), publicHttps)
        assertEquals(
            "同一 origin 的显式默认端口应等价",
            publicHttps,
            ReaderWebUiPolicy.Origin.fromUrl("https://READER.example.com:443/x?a=1#h"),
        )
        assertEquals(
            "127.0.0.1 与 localhost 归一到同一回环 host",
            loopback,
            ReaderWebUiPolicy.Origin.fromOriginString("http://localhost:1162"),
        )
        // 无效/非 http(s)/带账号密码的输入不产出 origin
        assertNull(ReaderWebUiPolicy.Origin.fromOriginString(null))
        assertNull(ReaderWebUiPolicy.Origin.fromOriginString(""))
        assertNull(ReaderWebUiPolicy.Origin.fromOriginString("file:///sdcard/x"))
        assertNull(ReaderWebUiPolicy.Origin.fromOriginString("javascript:alert(1)"))
        assertNull(ReaderWebUiPolicy.Origin.fromUrl("http://user:pass@192.168.1.8:1122/"))
        assertNull(ReaderWebUiPolicy.Origin.fromOriginString("http://192.168.1.8:1122/book/1?x=1"))
        // 任意页面 URL 只取 origin（导航判定需要带路径/参数的落点）
        assertEquals(lan, ReaderWebUiPolicy.Origin.fromUrl("http://192.168.1.8:1122/book/1?x=1"))
    }

    // ---- 主文档导航：严格 scheme + host + port ----

    @Test fun navigationAllowsOnlyStrictSameOrigin() {
        assertTrue(ReaderWebUiPolicy.navigationDecision("http://192.168.1.8:1122/", lan).allowed)
        assertTrue(ReaderWebUiPolicy.navigationDecision("http://192.168.1.8:1122/book/1?q=%E6%B5%8B#c", lan).allowed)
        assertTrue(ReaderWebUiPolicy.navigationDecision("http://192.168.1.8:1122", lan).allowed)

        // 不同端口 / 不同 host / 不同 scheme 都必须拒绝
        assertBlocked("CROSS_ORIGIN", ReaderWebUiPolicy.navigationDecision("http://192.168.1.8:1123/", lan))
        assertBlocked("CROSS_ORIGIN", ReaderWebUiPolicy.navigationDecision("http://192.168.1.9:1122/", lan))
        assertBlocked("CROSS_ORIGIN", ReaderWebUiPolicy.navigationDecision("https://192.168.1.8:1122/", lan))
        assertBlocked("CROSS_ORIGIN", ReaderWebUiPolicy.navigationDecision("http://reader.example.com/", lan))
        // 公开 origin 的 http→https 同样不算同源（scheme 严格）
        assertBlocked("CROSS_ORIGIN", ReaderWebUiPolicy.navigationDecision("http://reader.example.com/", publicHttps))

        // 非 http(s) scheme 一律拒绝
        assertBlocked("FORBIDDEN_SCHEME", ReaderWebUiPolicy.navigationDecision("file:///etc/passwd", lan))
        assertBlocked("FORBIDDEN_SCHEME", ReaderWebUiPolicy.navigationDecision("content://x/y", lan))
        assertBlocked("FORBIDDEN_SCHEME", ReaderWebUiPolicy.navigationDecision("javascript:alert(1)", lan))
        assertBlocked("FORBIDDEN_SCHEME", ReaderWebUiPolicy.navigationDecision("data:text/html,<b>x", lan))
        assertBlocked("FORBIDDEN_SCHEME", ReaderWebUiPolicy.navigationDecision("about:blank", lan))
        assertBlocked("FORBIDDEN_SCHEME", ReaderWebUiPolicy.navigationDecision("blob:http://192.168.1.8:1122/x", lan))
        assertBlocked("INVALID_URL", ReaderWebUiPolicy.navigationDecision("", lan))
        assertBlocked("INVALID_URL", ReaderWebUiPolicy.navigationDecision(null, lan))
        // 内嵌账号密码的 URL 不能借同源判定混过去
        assertBlocked(
            "INVALID_URL",
            ReaderWebUiPolicy.navigationDecision("http://a:b@192.168.1.8:1122/", lan),
        )
    }

    // ---- 子资源：同 origin 允许，跨 origin 公网允许、私网/回环阻断 ----

    @Test fun subresourceAllowsPublicAndSameOriginPrivate() {
        // 同 origin 的子资源始终允许（阅读服务本身就可能开在局域网/回环上）
        assertTrue(ReaderWebUiPolicy.subresourceDecision("http://192.168.1.8:1122/js/app.js", lan).allowed)
        assertTrue(ReaderWebUiPolicy.subresourceDecision("http://127.0.0.1:1162/cover.png", loopback).allowed)

        // 跨 origin 的公网 CDN 允许（阅读 Web 页面可能依赖 CDN 资源）
        assertTrue(ReaderWebUiPolicy.subresourceDecision("https://cdn.example.com/app.js", lan).allowed)
        assertTrue(ReaderWebUiPolicy.subresourceDecision("https://fonts.gstatic.com/f.woff2", publicHttps).allowed)
        // 同一公网 host 的其它端口按跨 origin 处理，公网仍放行
        assertTrue(ReaderWebUiPolicy.subresourceDecision("https://reader.example.com:8443/x.js", publicHttps).allowed)
    }

    @Test fun subresourceBlocksCrossOriginPrivateAndNonHttp() {
        // 跨 origin 的回环/私网/链路本地/CGNAT 一律阻断，避免页面把内网当图床探测
        assertBlocked("PRIVATE_SUBRESOURCE", ReaderWebUiPolicy.subresourceDecision("http://127.0.0.1:8080/x", publicHttps))
        assertBlocked("PRIVATE_SUBRESOURCE", ReaderWebUiPolicy.subresourceDecision("http://localhost/x", publicHttps))
        assertBlocked("PRIVATE_SUBRESOURCE", ReaderWebUiPolicy.subresourceDecision("http://10.0.0.5/x.png", publicHttps))
        assertBlocked("PRIVATE_SUBRESOURCE", ReaderWebUiPolicy.subresourceDecision("http://172.16.3.4/x.gif", lan))
        assertBlocked("PRIVATE_SUBRESOURCE", ReaderWebUiPolicy.subresourceDecision("http://192.168.1.9/x", lan))
        assertBlocked("PRIVATE_SUBRESOURCE", ReaderWebUiPolicy.subresourceDecision("http://169.254.169.254/latest", lan))
        assertBlocked("PRIVATE_SUBRESOURCE", ReaderWebUiPolicy.subresourceDecision("http://[::1]:1122/x", publicHttps))
        // 非 http(s) 子资源同样阻断
        assertBlocked("FORBIDDEN_SCHEME", ReaderWebUiPolicy.subresourceDecision("file:///sdcard/x.png", lan))
        assertBlocked("FORBIDDEN_SCHEME", ReaderWebUiPolicy.subresourceDecision("content://media/1", lan))
        assertBlocked("INVALID_URL", ReaderWebUiPolicy.subresourceDecision(null, lan))
        assertBlocked("INVALID_URL", ReaderWebUiPolicy.subresourceDecision("", lan))
    }

    // ---- 脚本字面量转义 ----

    @Test fun jsStringLiteralEscapesQuotesBackslashAndLineSeparators() {
        assertEquals("\"\"", ReaderWebUiPolicy.jsStringLiteral(""))
        assertEquals("\"plain\"", ReaderWebUiPolicy.jsStringLiteral("plain"))
        // 试图闭合字面量再执行任意代码：必须整体留在字符串里
        val attack = "\");alert(1);(\""
        val literal = ReaderWebUiPolicy.jsStringLiteral(attack)
        assertEquals("\"\\\");alert(1);(\\\"\"", literal)
        assertEquals(
            "字面量内部不能出现未转义的双引号",
            2,
            literal.count { it == '"' } - literal.windowed(2).count { it == "\\\"" },
        )
        assertEquals("\"a\\\\b\"", ReaderWebUiPolicy.jsStringLiteral("a\\b"))
        assertEquals("\"a\\nb\\rc\\td\\be\\ff\"", ReaderWebUiPolicy.jsStringLiteral("a\nb\rc\td\be\u000Cf"))
        assertEquals("\"a\\u0000b\"", ReaderWebUiPolicy.jsStringLiteral("a\u0000b"))
        assertEquals("\"\\u000b\"", ReaderWebUiPolicy.jsStringLiteral("\u000B"))
        // U+2028/U+2029 是 JS 行终止符，未转义会截断字面量
        assertEquals("\"a\\u2028b\\u2029c\"", ReaderWebUiPolicy.jsStringLiteral("a\u2028b\u2029c"))
        // 中文与 emoji 原样保留
        assertEquals("\"测试📚\"", ReaderWebUiPolicy.jsStringLiteral("测试📚"))
    }

    @Test fun clickAndFillScriptsEmbedInputsAsLiteralsOnly() {
        val click = ReaderWebUiPolicy.buildClickScript("a[href=\";alert(1)//\"]", 2)
        assertTrue("selector 必须以字面量形式出现", click.contains("\"a[href=\\\";alert(1)//\\\"]\""))
        assertTrue(click.contains("var sel="))
        assertTrue(click.contains(",idx=2,"))
        assertTrue("点击要真实派发 click", click.contains("el.click()"))
        assertTrue("点击要带 pointer 序列", click.contains("pointerdown"))

        val fill = ReaderWebUiPolicy.buildFillScript("input[name=q]", "a\"b\\c\nd", 0)
        assertTrue(fill.contains("var sel=\"input[name=q]\""))
        assertTrue("填充文本必须逐字符转义后嵌入", fill.contains("txt=\"a\\\"b\\\\c\\nd\""))
        assertTrue("填充要触发 input", fill.contains("Event(\"input\""))
        assertTrue("填充要触发 change", fill.contains("Event(\"change\""))
        val enter = ReaderWebUiPolicy.buildPressEnterScript("input[name=\";alert(1)//\"]", 3)
        assertTrue(enter.contains("var sel=\"input[name=\\\";alert(1)//\\\"]\",idx=3"))
        assertTrue(enter.contains("new KeyboardEvent(\"keydown\""))
        assertTrue(enter.contains("HTMLFormElement.prototype.requestSubmit.call(form)"))
        assertTrue(enter.contains("action.origin!==location.origin"))
        assertTrue(enter.contains("UNSAFE_FORM"))
        assertTrue(enter.contains("UNSUPPORTED_TARGET"))
        // 脚本里没有任何把模型文本当代码执行的入口
        listOf(click, fill, enter, ReaderWebUiPolicy.buildScrollScript(100)).forEach { script ->
            assertFalse(script, script.contains("eval("))
            assertFalse(script, script.contains("Function("))
            assertFalse(script, script.contains("setTimeout("))
            assertFalse(script, script.contains("import("))
        }
    }

    @Test fun scrollDeltaIsClampedBothDirections() {
        assertTrue(ReaderWebUiPolicy.buildScrollScript(500).contains("scrollBy(0,500)"))
        assertTrue(ReaderWebUiPolicy.buildScrollScript(-500).contains("scrollBy(0,-500)"))
        val huge = ReaderWebUiPolicy.buildScrollScript(Int.MAX_VALUE)
        assertTrue(huge.contains("scrollBy(0,${ReaderWebUiPolicy.MAX_SCROLL_DELTA})"))
        val tiny = ReaderWebUiPolicy.buildScrollScript(Int.MIN_VALUE)
        assertTrue(tiny.contains("scrollBy(0,-${ReaderWebUiPolicy.MAX_SCROLL_DELTA})"))
    }

    @Test fun selectorValidationRejectsEmptyOversizedAndControlChars() {
        assertNull(ReaderWebUiPolicy.selectorProblem("div > a.link"))
        assertEquals("EMPTY_SELECTOR", ReaderWebUiPolicy.selectorProblem("   "))
        assertEquals(
            "SELECTOR_TOO_LONG",
            ReaderWebUiPolicy.selectorProblem("a".repeat(ReaderWebUiPolicy.Limits().maxSelectorChars + 1)),
        )
        assertEquals("CONTROL_CHARS", ReaderWebUiPolicy.selectorProblem("div\u0000a"))
        assertEquals("CONTROL_CHARS", ReaderWebUiPolicy.selectorProblem("div\na"))
        // 收敛后的 selector 上限可配置
        assertNull(ReaderWebUiPolicy.selectorProblem("abcdef", ReaderWebUiPolicy.Limits(maxSelectorChars = 6)))
        assertEquals("SELECTOR_TOO_LONG", ReaderWebUiPolicy.selectorProblem("abcdefg", ReaderWebUiPolicy.Limits(maxSelectorChars = 6)))
    }

    // ---- 快照体积上限与分页 ----

    @Test fun snapshotScriptEmbedsLimits() {
        val script = ReaderWebUiPolicy.buildSnapshotScript(
            ReaderWebUiPolicy.Limits(
                maxTitleChars = 10,
                maxPathChars = 20,
                maxTextChars = 30,
                maxElements = 5,
                maxElementTextChars = 40,
                maxSelectorChars = 50,
                maxHrefChars = 60,
                maxValueChars = 70,
            ),
        )
        assertTrue(script.contains("t:30"))
        assertTrue(script.contains("k:10"))
        assertTrue(script.contains("p:20"))
        assertTrue(script.contains("e:5"))
        assertTrue(script.contains("et:40"))
        assertTrue(script.contains("s:50"))
        assertTrue(script.contains("h:60"))
        assertTrue(script.contains("v:70"))
        // 脚本自身限制遍历数量与文本长度，不把整页 DOM 交给模型
        assertTrue(script.contains("if(out.length>=L.e)break;"))
        assertTrue(script.contains("text.substring(0,L.t)"))
        assertTrue(script.contains("JSON.stringify"))
    }

    @Test fun parseSnapshotClipsTitleTextAndElements() {
        val raw = jsString(
            """
            {"ok":true,"href":"${lan.rootUrl()}book/1?token=secret","title":"${"标".repeat(500)}",
             "path":"/book/1?token=secret&page=2",
             "text":"${"正".repeat(500)}",
             "candidates":200,
             "elements":[
               {"order":0,"index":0,"tag":"A","selector":"a.link","text":"${"可".repeat(300)}"},
               {"order":1,"index":3,"tag":"button","selector":"#go","text":"Go","disabled":true}
             ]}
            """.trimIndent(),
        )
        val limits = ReaderWebUiPolicy.Limits(maxTitleChars = 20, maxTextChars = 100, maxElements = 1, maxElementTextChars = 30)
        val parsed = ReaderWebUiPolicy.parseSnapshot(raw, lan, limits)
        assertTrue(parsed is ReaderWebUiPolicy.SnapshotParse.Ok)
        val snapshot = (parsed as ReaderWebUiPolicy.SnapshotParse.Ok).snapshot

        assertEquals(20, snapshot.title.length)
        assertEquals(100, snapshot.text.length)
        assertTrue("正文被截断时必须标出", snapshot.textTruncated)
        assertEquals(1, snapshot.elements.size)
        assertEquals(30, snapshot.elements.first().text.length)
        assertEquals("a", snapshot.elements.first().tag)
        assertTrue("超过上限的元素数量要提示", snapshot.elementLimitReached)
        // 同源 path 保留，但带令牌的查询参数必须剔除
        assertEquals("/book/1?page=2", snapshot.path)
    }

    @Test fun snapshotIncludesAccessibleSearchControlsWithoutEchoingSecrets() {
        val script = ReaderWebUiPolicy.buildSnapshotScript()
        assertTrue(script.contains("[role=\"searchbox\"]"))
        assertTrue(script.contains("[aria-label][tabindex]"))
        assertTrue(script.contains("type===\"search\"?\"searchbox\""))
        assertTrue(script.contains("privateInput(el,tag,type,name)"))
        val raw = jsString(
            """{"ok":true,"href":"http://192.168.1.8:1122/","path":"/","candidates":1,
              "elements":[{"tag":"input","selector":"#q","type":"search","role":"searchbox",
              "ariaLabel":"搜索书名","placeholder":"书名","value":"private-search"}]}""",
        )
        val element = (ReaderWebUiPolicy.parseSnapshot(raw, lan) as ReaderWebUiPolicy.SnapshotParse.Ok).snapshot.elements.single()
        assertEquals("searchbox", element.toPayload()["role"])
        assertEquals("搜索书名", element.toPayload()["ariaLabel"])
        assertNull("search 值不可回显", element.value)
    }

    @Test fun parseSnapshotRejectsOffOriginAndMalformed() {
        fun page(href: String) = jsString(
            """{"ok":true,"href":"$href","title":"t","path":"/","text":"x","candidates":0,"elements":[]}""",
        )
        assertTrue(ReaderWebUiPolicy.parseSnapshot(page("http://192.168.1.8:1122/"), lan) is ReaderWebUiPolicy.SnapshotParse.Ok)
        // 跳到别的端口 / 别的 host / 别的 scheme / 私网：一律 OffOrigin，调用方据此销毁会话
        listOf(
            "http://192.168.1.8:1123/",
            "http://192.168.1.9:1122/",
            "https://192.168.1.8:1122/",
            "http://127.0.0.1:1122/",
            "file:///sdcard/x.html",
        ).forEach { href ->
            assertTrue(href, ReaderWebUiPolicy.parseSnapshot(page(href), lan) is ReaderWebUiPolicy.SnapshotParse.OffOrigin)
        }
        // 不可解析 / 脚本报错 / null 回包
        assertTrue(ReaderWebUiPolicy.parseSnapshot(null, lan) is ReaderWebUiPolicy.SnapshotParse.Malformed)
        assertTrue(ReaderWebUiPolicy.parseSnapshot("null", lan) is ReaderWebUiPolicy.SnapshotParse.Malformed)
        assertTrue(ReaderWebUiPolicy.parseSnapshot(jsString("not json"), lan) is ReaderWebUiPolicy.SnapshotParse.Malformed)
        assertTrue(
            ReaderWebUiPolicy.parseSnapshot(jsString("""{"ok":false,"code":"SNAPSHOT_ERROR"}"""), lan)
                is ReaderWebUiPolicy.SnapshotParse.Malformed,
        )
    }

    @Test fun parseSnapshotSkipsUnusableElements() {
        val raw = jsString(
            """
            {"ok":true,"href":"http://192.168.1.8:1122/","title":"t","path":"/","text":"x","candidates":4,
             "elements":[
               {"tag":"a","selector":"","text":"空 selector"},
               {"tag":"","selector":"a","text":"空 tag"},
               {"tag":"a","selector":"a{color:red}","text":"ok"},
               {"tag":"a","selector":"a\u0000b","text":"控制字符"}
             ]}
            """.trimIndent(),
        )
        val parsed = ReaderWebUiPolicy.parseSnapshot(raw, lan) as ReaderWebUiPolicy.SnapshotParse.Ok
        assertEquals(1, parsed.snapshot.elements.size)
        assertEquals("a{color:red}", parsed.snapshot.elements.first().selector)
        assertEquals("order 必须重新连续编号", 0, parsed.snapshot.elements.first().order)
        assertTrue(parsed.snapshot.elementLimitReached)
    }

    // ---- 隐私：敏感输入值不回显 ----

    @Test fun sensitiveInputValuesAreNotEchoed() {
        fun element(type: String, name: String, value: String) = jsString(
            """
            {"ok":true,"href":"http://192.168.1.8:1122/","title":"t","path":"/","text":"x","candidates":1,
             "elements":[{"tag":"input","index":0,"selector":"input[name=$name]","text":"","type":"$type","name":"$name","value":"$value"}]}
            """.trimIndent(),
        )
        fun snapshot(type: String, name: String, value: String = "s3cret"): ReaderWebUiPolicy.Element =
            (ReaderWebUiPolicy.parseSnapshot(element(type, name, value), lan) as ReaderWebUiPolicy.SnapshotParse.Ok)
                .snapshot.elements.single()

        // 普通文本/数字输入才回显
        assertEquals("s3cret", snapshot("text", "keyword").value)
        assertEquals("s3cret", snapshot("number", "page").value)
        assertEquals("缺省 type 视为 text", "s3cret", snapshot("", "keyword").value)

        // password / hidden / file / email / tel / url / search / checkbox 等一律不回显
        listOf("password", "hidden", "file", "email", "tel", "url", "search", "checkbox", "radio", "date").forEach { type ->
            assertNull("$type 的值不能回显", snapshot(type, "field").value)
        }
        // 即使 type=text，字段名敏感也不回显（页面常把令牌塞进 text 输入）
        listOf("token", "access_token", "apiKey", "password", "passwd", "pwd", "secret", "jwt", "sessionId", "X-CSRF-Token")
            .forEach { name -> assertNull("$name 的值不能回显", snapshot("text", name).value) }
        // 非表单元素没有 value 语义
        val div = jsString(
            """{"ok":true,"href":"http://192.168.1.8:1122/","title":"t","path":"/","text":"x","candidates":1,
                "elements":[{"tag":"div","selector":"#x","text":"x","value":"leak"}]}""",
        )
        val divElement = (ReaderWebUiPolicy.parseSnapshot(div, lan) as ReaderWebUiPolicy.SnapshotParse.Ok)
            .snapshot.elements.single()
        assertNull(divElement.value)
    }

    @Test fun sensitiveValueIsAlsoRemovedFromElementText() {
        // 页面把密码/令牌塞进 aria-label 或按钮文本时，text 也不能成为泄密通道
        val raw = jsString(
            """
            {"ok":true,"href":"http://192.168.1.8:1122/","title":"t","path":"/","text":"x","candidates":2,
             "elements":[
               {"tag":"input","selector":"#pwd","text":"s3cret","type":"password","name":"pwd","value":"s3cret"},
               {"tag":"input","selector":"#kw","text":"keyword","type":"text","name":"keyword","value":"keyword"}
             ]}
            """.trimIndent(),
        )
        val elements = (ReaderWebUiPolicy.parseSnapshot(raw, lan) as ReaderWebUiPolicy.SnapshotParse.Ok).snapshot.elements
        assertEquals("敏感输入框的 text 不能等于它的值", "", elements[0].text)
        assertNull(elements[0].value)
        // 普通输入框的文本照常保留
        assertEquals("keyword", elements[1].text)
        assertEquals("keyword", elements[1].value)
    }

    @Test fun autocompleteHintsAlsoSuppressValues() {
        fun snapshot(autocomplete: String): ReaderWebUiPolicy.Element {
            val raw = jsString(
                """{"ok":true,"href":"http://192.168.1.8:1122/","title":"t","path":"/","text":"x","candidates":1,
                    "elements":[{"tag":"input","selector":"#f","text":"","type":"text","autocomplete":"$autocomplete","value":"s3cret"}]}""",
            )
            return (ReaderWebUiPolicy.parseSnapshot(raw, lan) as ReaderWebUiPolicy.SnapshotParse.Ok)
                .snapshot.elements.single()
        }
        listOf("current-password", "new-password", "cc-number", "cc-csc").forEach { hint ->
            assertNull("$hint 不能回显", snapshot(hint).value)
        }
        assertEquals("s3cret", snapshot("off").value)
        assertEquals("s3cret", snapshot("username").value)
    }

    @Test fun fillScriptTargetsOnlyEditableElements() {
        val script = ReaderWebUiPolicy.buildFillScript("#x", "v", 0)
        assertTrue("普通 input/textarea 走原型 setter", script.contains("HTMLInputElement.prototype"))
        assertTrue("下拉框走 value + change", script.contains("tag===\"select\""))
        assertTrue("可编辑区域走 textContent", script.contains("editable(el)"))
        assertTrue("既不是输入框也不是可编辑区域时明确拒绝", script.contains("UNSUPPORTED_TARGET"))
        assertTrue("文件输入框明确拒绝", script.contains("UNSUPPORTED_INPUT"))
        assertTrue("缺失 contenteditable 属性不能当作空属性", script.contains("attr!==null"))
        assertTrue("只允许 true 或显式空属性", script.contains("attr===\"\"||String(attr).toLowerCase()===\"true\""))
        assertTrue("只读控件不可填充", script.contains("el.disabled||el.readOnly"))
        assertTrue("按钮和隐藏控件不可填充", script.contains("\"hidden\",\"button\",\"submit\""))
    }

    @Test fun sensitiveParamNamesMatchWholeWordsOnly() {
        listOf("token", "access_token", "accessToken", "X-Auth-Token", "password", "pwd", "api_key", "apikey", "sig", "signature")
            .forEach { assertTrue(it, ReaderWebUiPolicy.isSensitiveParamName(it)) }
        // 完整词匹配，不做子串匹配，避免把正常参数误删
        listOf("keyword", "page", "q", "title", "bookUrl", "tokenizer", "author", "comment").forEach { name ->
            assertFalse(name, ReaderWebUiPolicy.isSensitiveParamName(name))
        }
        assertFalse(ReaderWebUiPolicy.isSensitiveParamName(""))
    }

    @Test fun redactSensitiveParamsKeepsSafeQuery() {
        assertEquals("/search?q=%E4%B9%A6&page=2", ReaderWebUiPolicy.redactSensitiveParams("/search?q=%E4%B9%A6&page=2"))
        assertEquals("/x?page=2", ReaderWebUiPolicy.redactSensitiveParams("/x?token=abc&page=2"))
        assertEquals("/x?page=2", ReaderWebUiPolicy.redactSensitiveParams("/x?page=2&access_token=abc"))
        assertEquals("/x", ReaderWebUiPolicy.redactSensitiveParams("/x?token=abc"))
        // hash 路由里的查询参数同样处理
        assertEquals("/#/reader?book=1", ReaderWebUiPolicy.redactSensitiveParams("/#/reader?book=1&jwt=abc"))
        assertEquals("/book/1", ReaderWebUiPolicy.redactSensitiveParams("/book/1"))
        assertEquals("", ReaderWebUiPolicy.redactSensitiveParams(""))
    }

    // ---- 动作回包解析 ----

    @Test fun parseActionResultDecodesEvaluateJavascriptPayload() {
        val ok = ReaderWebUiPolicy.parseActionResult(jsString("""{"ok":true}"""))
        assertTrue(ok.ok)
        assertNull(ok.code)

        val scrolled = ReaderWebUiPolicy.parseActionResult(jsString("""{"ok":true,"y":960}"""))
        assertTrue(scrolled.ok)
        assertEquals(960, scrolled.scrollY)

        assertEquals("NO_MATCH", ReaderWebUiPolicy.parseActionResult(jsString("""{"ok":false,"code":"NO_MATCH"}""")).code)
        assertEquals(
            "INDEX_OUT_OF_RANGE",
            ReaderWebUiPolicy.parseActionResult(jsString("""{"ok":false,"code":"INDEX_OUT_OF_RANGE"}""")).code,
        )
        // 非 JSON / null / 未编码的裸回包都退化成 SCRIPT_ERROR
        listOf(null, "", "null", "undefined", "garbage").forEach { raw ->
            assertEquals(raw, "SCRIPT_ERROR", ReaderWebUiPolicy.parseActionResult(raw).code)
        }
    }

    @Test fun decodeJsResultUnwrapsJsonStringOnly() {
        assertEquals("""{"ok":true}""", ReaderWebUiPolicy.decodeJsResult(jsString("""{"ok":true}""")))
        assertNull(ReaderWebUiPolicy.decodeJsResult("null"))
        assertNull(ReaderWebUiPolicy.decodeJsResult(null))
        assertNull("非字符串回包不当作脚本结果", ReaderWebUiPolicy.decodeJsResult("42"))
    }

    @Test fun stabilitySignatureRequiresCompleteAndUnchanged() {
        val first = "complete|/book/1|?a=1|120|900"
        assertTrue(ReaderWebUiPolicy.isStable(first, first))
        assertFalse("只出现一次不算稳定", ReaderWebUiPolicy.isStable(null, first))
        assertFalse("签名变化说明 DOM 仍在更新", ReaderWebUiPolicy.isStable(first, "complete|/book/1|?a=1|121|900"))
        assertFalse("未加载完不算稳定", ReaderWebUiPolicy.isStable("interactive|/|?|1|1", "interactive|/|?|1|1"))
        assertFalse("脚本异常不算稳定", ReaderWebUiPolicy.isStable("ERR", "ERR"))
        val signature = ReaderWebUiPolicy.buildSignatureScript()
        assertNotNull(signature)
        assertTrue(signature.contains("document.readyState"))
        assertTrue("SPA hash 路由参与签名", signature.contains("location.hash"))
        assertTrue("等长正文变化须改变摘要", signature.contains("s.charCodeAt(j)"))
        assertTrue("文本只取有界片段", signature.contains("t.slice(0,4096)"))
        assertTrue("可编辑控件值参与摘要但不回传原值", signature.contains("i<80") && signature.contains("slice(0,120)"))
    }

    // ---- 会话身份：origin / revision / 令牌指纹 ----

    @Test fun identityPinsOriginRevisionAndTokenFingerprint() {
        val identity = ReaderWebUiPolicy.SessionIdentity.of("http://192.168.1.8:1122", 3L, "tok-abcdef")!!
        assertEquals(lan, identity.origin)
        assertEquals(3L, identity.revision)
        // 指纹不出现明文，且长度固定（SHA-256 十六进制）
        assertFalse(identity.tokenFingerprint.contains("tok-abcdef"))
        assertEquals(64, identity.tokenFingerprint.length)
        // 同样的输入得到同样的指纹；空令牌与无令牌等价
        assertEquals(identity, ReaderWebUiPolicy.SessionIdentity.of("http://192.168.1.8:1122", 3L, "tok-abcdef"))
        assertEquals(
            ReaderWebUiPolicy.SessionIdentity.tokenFingerprint(null),
            ReaderWebUiPolicy.SessionIdentity.tokenFingerprint("   "),
        )
        assertTrue(ReaderWebUiPolicy.SessionIdentity.tokenFingerprint(null).isEmpty())
        // 地址不合法 → 没有身份
        assertNull(ReaderWebUiPolicy.SessionIdentity.of("file:///x", 1L, "t"))
        assertNull(ReaderWebUiPolicy.SessionIdentity.of(null, 1L, "t"))
    }

    @Test fun identityChangeDetectsOriginTokenAndRevision() {
        fun identity(origin: String = "http://192.168.1.8:1122", revision: Long = 3L, token: String = "abc") =
            ReaderWebUiPolicy.SessionIdentity.of(origin, revision, token)!!

        val pinned = identity()
        assertNull("完全一致才是同一身份", ReaderWebUiPolicy.identityChange(pinned, identity()))
        // 地址（含端口/scheme）变化
        assertEquals(
            ReaderWebUiPolicy.IdentityChange.ORIGIN_CHANGED,
            ReaderWebUiPolicy.identityChange(pinned, identity(origin = "http://192.168.1.8:1123")),
        )
        assertEquals(
            ReaderWebUiPolicy.IdentityChange.ORIGIN_CHANGED,
            ReaderWebUiPolicy.identityChange(pinned, identity(origin = "https://192.168.1.8:1122")),
        )
        // 同 origin 换令牌：同样是一次新配置，旧 cookie/SPA 状态不可复用
        assertEquals(
            ReaderWebUiPolicy.IdentityChange.TOKEN_CHANGED,
            ReaderWebUiPolicy.identityChange(pinned, identity(token = "other")),
        )
        assertEquals(
            ReaderWebUiPolicy.IdentityChange.TOKEN_CHANGED,
            ReaderWebUiPolicy.identityChange(pinned, identity(token = "")),
        )
        // 令牌与地址都没变、只有 revision 推进（用户保存后改回同样的值）：仍算新配置
        assertEquals(
            ReaderWebUiPolicy.IdentityChange.CONFIG_CHANGED,
            ReaderWebUiPolicy.identityChange(pinned, identity(revision = 4L)),
        )
        // 未固定/未配置时无从比较，不产生变化
        assertNull(ReaderWebUiPolicy.identityChange(null, identity()))
        assertNull(ReaderWebUiPolicy.identityChange(pinned, null))
    }

    @Test fun sessionIdentityOfConfigUsesSavedFields() {
        val config = ReaderWebConfigStore.Config("http://127.0.0.1:1162", "secret-token-value", 7L)
        val identity = ReaderWebUiPolicy.SessionIdentity.of(config)!!
        assertEquals(loopback, identity.origin)
        assertEquals(7L, identity.revision)
        assertEquals(ReaderWebUiPolicy.SessionIdentity.tokenFingerprint("secret-token-value"), identity.tokenFingerprint)
        // revision 缺省值 0 时仍能靠 origin + 指纹区分身份
        val noRevision = ReaderWebUiPolicy.SessionIdentity.of(
            ReaderWebConfigStore.Config("http://127.0.0.1:1162", "secret-token-value"),
        )!!
        assertEquals(ReaderWebUiPolicy.IdentityChange.CONFIG_CHANGED, ReaderWebUiPolicy.identityChange(identity, noRevision))
    }

    // ---- 令牌脱敏 ----

    @Test fun redactTokenReplacesOnlyTheSavedToken() {
        val snapshot = ReaderWebUiPolicy.Snapshot(
            title = "首页 token-live-123 已登录",
            path = "/#/r?access_token=token-live-123&page=2",
            text = "正文里也出现了 token-live-123，其余内容保持不变。",
            textTruncated = false,
            elements = listOf(
                ReaderWebUiPolicy.Element(
                    order = 0,
                    index = 0,
                    tag = "a",
                    text = "打开 token-live-123",
                    selector = "a",
                    ariaLabel = "token-live-123",
                    href = "/book/1?t=token-live-123",
                    value = "token-live-123",
                ),
            ),
            elementLimitReached = false,
        )
        val redacted = ReaderWebUiPolicy.redactToken(snapshot, "token-live-123")
        assertEquals(ReaderWebUiPolicy.TOKEN_PLACEHOLDER, "[令牌已隐藏]")
        assertTrue(redacted.title.contains(ReaderWebUiPolicy.TOKEN_PLACEHOLDER))
        assertTrue(redacted.title.contains("首页"))
        assertTrue(redacted.path.contains(ReaderWebUiPolicy.TOKEN_PLACEHOLDER))
        assertTrue("其余查询参数要保留", redacted.path.contains("page=2"))
        assertTrue(redacted.text.contains(ReaderWebUiPolicy.TOKEN_PLACEHOLDER))
        assertTrue("正文其余内容不能被清空", redacted.text.contains("其余内容保持不变"))
        val element = redacted.elements.single()
        assertTrue(element.text.contains(ReaderWebUiPolicy.TOKEN_PLACEHOLDER))
        assertEquals(ReaderWebUiPolicy.TOKEN_PLACEHOLDER, element.ariaLabel)
        assertEquals("/book/1?t=${ReaderWebUiPolicy.TOKEN_PLACEHOLDER}", element.href)
        assertEquals(ReaderWebUiPolicy.TOKEN_PLACEHOLDER, element.value)
        // 任何字段都不再出现明文
        assertFalse(redacted.toPayload().toString().contains("token-live-123"))
        // 原快照不被就地修改
        assertTrue(snapshot.text.contains("token-live-123"))
    }

    @Test fun redactTokenLeavesSnapshotUntouchedWhenNothingToHide() {
        val snapshot = ReaderWebUiPolicy.Snapshot(
            title = "书源管理",
            path = "/#/sources",
            text = "正常正文",
            textTruncated = false,
            elements = emptyList(),
            elementLimitReached = false,
        )
        // 没存令牌 / 令牌为空 / 令牌不在页面里：内容原样返回
        assertSame(snapshot, ReaderWebUiPolicy.redactToken(snapshot, null))
        assertSame(snapshot, ReaderWebUiPolicy.redactToken(snapshot, ""))
        assertSame(snapshot, ReaderWebUiPolicy.redactToken(snapshot, "not-present-token"))
    }

    @Test fun shortTokensAreRedactedToo() {
        // 安全优先：1～3 字符的令牌同样必须替换。
        // 用不会与正文其它单词撞形的大写/数字令牌，才能干净地断言「哪里都没了」。
        listOf("Z", "Zq", "Zq7").forEach { token ->
            val snapshot = ReaderWebUiPolicy.Snapshot(
                title = "登录 $token 成功",
                path = "/#/r?t=$token&page=2",
                text = "正文里出现 $token，其余内容保持不变。",
                textTruncated = false,
                elements = listOf(
                    ReaderWebUiPolicy.Element(
                        order = 0,
                        index = 0,
                        tag = "a",
                        text = "打开 $token",
                        selector = "a",
                        ariaLabel = token,
                        href = "/book?t=$token#frag",
                        value = token,
                    ),
                ),
                elementLimitReached = false,
            )
            val redacted = ReaderWebUiPolicy.redactToken(snapshot, token)
            assertEquals("登录 ${ReaderWebUiPolicy.TOKEN_PLACEHOLDER} 成功", redacted.title)
            assertEquals("/#/r?t=${ReaderWebUiPolicy.TOKEN_PLACEHOLDER}&page=2", redacted.path)
            assertEquals("正文里出现 ${ReaderWebUiPolicy.TOKEN_PLACEHOLDER}，其余内容保持不变。", redacted.text)
            val element = redacted.elements.single()
            assertEquals("打开 ${ReaderWebUiPolicy.TOKEN_PLACEHOLDER}", element.text)
            assertEquals(ReaderWebUiPolicy.TOKEN_PLACEHOLDER, element.ariaLabel)
            assertEquals("/book?t=${ReaderWebUiPolicy.TOKEN_PLACEHOLDER}#frag", element.href)
            assertEquals(ReaderWebUiPolicy.TOKEN_PLACEHOLDER, element.value)
        }
    }

    @Test fun shortTokenOverRedactionIsAcceptedOverLeaking() {
        // 1 字符令牌会把正文里所有同形字符一并遮盖：这是刻意接受的代价，安全优先于可读性
        val snapshot = ReaderWebUiPolicy.Snapshot(
            title = "a",
            path = "/a",
            text = "a a a",
            textTruncated = false,
            elements = emptyList(),
            elementLimitReached = false,
        )
        val redacted = ReaderWebUiPolicy.redactToken(snapshot, "a")
        assertEquals(ReaderWebUiPolicy.TOKEN_PLACEHOLDER, redacted.title)
        assertEquals("/${ReaderWebUiPolicy.TOKEN_PLACEHOLDER}", redacted.path)
        assertEquals(
            listOf(ReaderWebUiPolicy.TOKEN_PLACEHOLDER, ReaderWebUiPolicy.TOKEN_PLACEHOLDER, ReaderWebUiPolicy.TOKEN_PLACEHOLDER)
                .joinToString(" "),
            redacted.text,
        )
        // 不含该字符的字段完全不动
        assertEquals("lorem ipsum", ReaderWebUiPolicy.redactSecrets("lorem ipsum", "a"))
    }

    @Test fun shortTokensAreRedactedInUrlEncodedForm() {
        // 需要转义的短令牌（空格/加号/斜杠/中文）：编码形态同样要替换。
        // 不用「令牌已隐藏」里出现的字符，否则断言「不再包含」会被占位符本身干扰。
        listOf("a b", "a+b", "a/b", "书").forEach { token ->
            val encoded = java.net.URLEncoder.encode(token, "UTF-8")
            val redacted = ReaderWebUiPolicy.redactSecrets("raw=$token enc=$encoded safe=1", token)
            assertFalse("[$token] 明文形态要替换", redacted.contains(token))
            if (encoded != token) assertFalse("[$token] 编码形态要替换", redacted.contains(encoded))
            assertTrue("[$token] 其余内容要保留", redacted.contains("safe=1"))
        }
        assertEquals(
            ReaderWebUiPolicy.TOKEN_PLACEHOLDER,
            ReaderWebUiPolicy.redactSecrets("ab", "ab"),
        )
        assertEquals(
            "x${ReaderWebUiPolicy.TOKEN_PLACEHOLDER}y",
            ReaderWebUiPolicy.redactSecrets("xaby", "ab"),
        )
    }

    @Test fun redactSecretsAlsoMatchesUrlEncodedToken() {
        // path/href 里的令牌常被 URL 编码（+/=/空格）
        val token = "a+b/c=d"
        val encoded = java.net.URLEncoder.encode(token, "UTF-8")
        assertTrue(encoded != token)
        val text = "raw=$token enc=$encoded safe=other"
        val redacted = ReaderWebUiPolicy.redactSecrets(text, token)
        assertFalse("明文形态要替换", redacted.contains(token))
        assertFalse("URL 编码形态也要替换", redacted.contains(encoded))
        assertTrue(redacted.contains("safe=other"))
        assertEquals(2, Regex(Regex.escape(ReaderWebUiPolicy.TOKEN_PLACEHOLDER)).findAll(redacted).count())
    }

    @Test fun redactSecretsHandlesLongTokensAndEmptyInput() {
        assertEquals("", ReaderWebUiPolicy.redactSecrets("", "long-token-value"))
        assertEquals("x", ReaderWebUiPolicy.redactSecrets("x", "long-token-value"))
        assertEquals(
            ReaderWebUiPolicy.TOKEN_PLACEHOLDER,
            ReaderWebUiPolicy.redactSecrets("long-token-value", "long-token-value"),
        )
        // 前后空白不参与匹配（保存时已 trim）
        assertEquals(
            "v=${ReaderWebUiPolicy.TOKEN_PLACEHOLDER}",
            ReaderWebUiPolicy.redactSecrets("v=long-token-value", "  long-token-value  "),
        )
    }

    // ---- helpers ----

    private fun assertBlocked(code: String, decision: ReaderWebUiPolicy.Decision) {
        assertFalse("$code 应被阻断", decision.allowed)
        assertEquals(code, decision.code)
    }

    /** `evaluateJavascript` 的回包形态：JSON 编码后的字符串。 */
    private fun jsString(payload: String): String = ReaderWebUiPolicy.jsStringLiteral(payload)
}
