package com.mina.legadostudio.runtime

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JS 绑定语义回归（上游 LegadoTeam/legado @330e606 AnalyzeRule.evalJS 896-936 / AnalyzeUrl.evalJS）。
 *
 * 这些用例锁住的是「沙箱与阅读端执行同一份规则 JS 时变量从哪来」：
 * - `src` 恒为原始内容，`result` 才是链中当前段结果（旧实现把 src 也设成 previous）；
 * - `java.put/get` 走 AnalyzeRule 的 chapter→book→ruleData→source 层级，同一次 debug 的字段间共享；
 * - `java.getString/getStringList/getElement/getElements/setContent` 与规则核同一实现，
 *   默认取原始内容而不是上次 setContent 留下的空 Document，CSS 无匹配返回空而不是整页。
 */
class ReaderJsBindingsTest {

    private fun evaluator() = RhinoEvaluator(HttpFetcher(), Gson())

    // ---------------------------------------------------------------- src / result

    @Test fun srcStaysOriginalContentWhileResultIsChainValue() {
        val html = "<div id='a'>第一段</div><div id='b'>第二段</div>"
        val js = """
            var out = [];
            out.push('#a@text');
            out.push(String(result));
            out.push(String(src));
            out.join('|')
        """.trimIndent()
        val raw = evaluator().evaluateRaw(
            js,
            baseUrl = "https://example.test/page",
            previous = "#b@text",
            bindings = mapOf(RhinoEvaluator.BINDING_SOURCE_CONTENT to html),
        )
        // result = 链中上一段（这里是规则串本身），src = 原始 HTML
        assertEquals("#b@text|$html", raw.value.toString())
    }

    @Test fun srcIsOriginalContentForSecondJsSegmentInChain() {
        val html = "<p class='t'>原始文本</p>"
        val session = ReaderRuleSession(
            content = html,
            baseUrl = "https://example.test/page",
            evalJs = { script, result ->
                evaluator().evaluateRaw(
                    script,
                    baseUrl = "https://example.test/page",
                    previous = result,
                    bindings = mapOf(RhinoEvaluator.BINDING_SOURCE_CONTENT to html),
                ).value
            },
        )
        // 第一段 CSS 取 .t 文本；第二段 JS 的 src 仍必须是整页 HTML
        val value = session.getString(".t@text<js>String(src).indexOf('原始文本') >= 0 ? result + '+src-ok' : result + '+src-lost'</js>")
        assertEquals("原始文本+src-ok", value)
    }

    // -------------------------------------------------------------- java.put/get

    @Test fun javaPutGetUsesAnalyzeRuleVariableLayers() {
        val chapterVars = mutableMapOf<String, String>()
        val bookVars = mutableMapOf<String, String>()
        val sourceVars = mutableMapOf<String, String>()
        val raw = evaluator().evaluateRaw(
            "java.put('k', 'fromJs'); java.get('k')",
            bindings = mapOf(
                RhinoEvaluator.BINDING_VARIABLES to ReaderRuntimeVariables(
                    get = { key ->
                        chapterVars[key]?.takeIf { it.isNotEmpty() }
                            ?: bookVars[key]?.takeIf { it.isNotEmpty() }
                            ?: sourceVars[key].orEmpty()
                    },
                    put = { key, value -> chapterVars[key] = value },
                    bookName = { "书名" },
                    chapterTitle = { "第一章" },
                ),
            ),
        )
        assertEquals("fromJs", raw.value)
        assertEquals("fromJs", chapterVars["k"])
    }

    @Test fun javaGetReadsBookAndChapterSpecialKeys() {
        val raw = evaluator().evaluateRaw(
            "java.get('bookName') + '|' + java.get('title')",
            bindings = mapOf(
                RhinoEvaluator.BINDING_VARIABLES to ReaderRuntimeVariables(
                    get = { "" },
                    put = { _, _ -> },
                    bookName = { "天命之子" },
                    chapterTitle = { "第三章" },
                ),
            ),
        )
        assertEquals("天命之子|第三章", raw.value)
    }

    @Test fun javaPutIsSharedAcrossFieldsInSameDebugAndIsolatedBetweenRuns() {
        val shared = mutableMapOf<String, String>()
        fun runField(value: String): String {
            val raw = evaluator().evaluateRaw(
                "java.put('token', '$value'); java.get('token')",
                bindings = mapOf(
                    RhinoEvaluator.BINDING_VARIABLES to ReaderRuntimeVariables(
                        get = { shared[it].orEmpty() },
                        put = { k, v -> shared[k] = v },
                    ),
                ),
            )
            return raw.value.toString()
        }
        assertEquals("a", runField("a"))
        assertEquals("a", runField("a"))
        // 另一个任务（独立变量层）看不到上一次 debug 的写入
        val other = mutableMapOf<String, String>()
        val raw = evaluator().evaluateRaw(
            "String(java.get('token'))",
            bindings = mapOf(
                RhinoEvaluator.BINDING_VARIABLES to ReaderRuntimeVariables(
                    get = { other[it].orEmpty() },
                    put = { k, v -> other[k] = v },
                ),
            ),
        )
        assertEquals("", raw.value)
    }

    // ------------------------------------------------- java.getString family

    @Test fun javaGetStringDefaultsToSessionOriginalContentNotEmptyDocument() {
        val html = "<div class='list'><p class='title'>修罗武神</p><p class='title'>天命之子</p></div>"
        val raw = evaluator().evaluateRaw(
            "java.getString('.title@text')",
            bindings = mapOf(RhinoEvaluator.BINDING_SOURCE_CONTENT to html),
        )
        assertEquals("修罗武神\n天命之子", raw.value)
    }

    @Test fun javaGetStringReturnsEmptyWhenCssDoesNotMatch() {
        val html = "<div class='list'><p class='title'>甲</p></div>"
        val raw = evaluator().evaluateRaw(
            "java.getString('.missing@text') + '|' + java.getStringList('.missing@text').length",
            bindings = mapOf(RhinoEvaluator.BINDING_SOURCE_CONTENT to html),
        )
        assertEquals("|0", raw.value)
    }

    @Test fun javaSetContentChangesSessionContentForLaterCalls() {
        val raw = evaluator().evaluateRaw(
            """
            java.setContent("<div class='x'>替换后</div>");
            java.getString('.x@text')
            """.trimIndent(),
            bindings = mapOf(RhinoEvaluator.BINDING_SOURCE_CONTENT to "<div class='x'>原始</div>"),
        )
        assertEquals("替换后", raw.value)
    }

    @Test fun javaGetStringListSplitsNewlineJoinedString() {
        val html = "<div class='c'>a</div><div class='c'>b</div>"
        val raw = evaluator().evaluateRaw(
            "java.getStringList('@js: \"x\\ny\"').length",
            bindings = mapOf(RhinoEvaluator.BINDING_SOURCE_CONTENT to html),
        )
        assertEquals("2", raw.value)
    }

    @Test fun javaGetElementAndElementsKeepTypedNodes() {
        val html = "<div class='c'><a href='/1'>一</a></div><div class='c'><a href='/2'>二</a></div>"
        val raw = evaluator().evaluateRaw(
            """
            var one = java.getElement('.c a');
            var all = java.getElements('.c a');
            one.attr('href') + '|' + all.length + '|' + all[1].text()
            """.trimIndent(),
            bindings = mapOf(RhinoEvaluator.BINDING_SOURCE_CONTENT to html),
        )
        assertEquals("/1|2|二", raw.value)
    }

    @Test fun javaGetStringWithoutContentUsesOriginalContentNotPreviousDocument() {
        // 旧实现：setContent 之后 currentDocument 变空文档，后续无参 getString 取空。
        val html = "<div class='a'>A</div>"
        val raw = evaluator().evaluateRaw(
            """
            java.setContent("<div class='b'>B</div>");
            var first = java.getString('.b@text');
            java.setContent(null);
            first + '|' + java.getString('.a@text')
            """.trimIndent(),
            bindings = mapOf(RhinoEvaluator.BINDING_SOURCE_CONTENT to html),
        )
        assertEquals("B|A", raw.value)
    }

    // ------------------------------------------------------ diagnostics wording

    @Test fun undefinedUrlInsideBoundContextIsNotDeclaredRuleError() {
        val error = runCatching {
            evaluator().evaluateRaw(
                "typeof url === 'undefined' ? url : 'x'",
                bindings = mapOf(RhinoEvaluator.BINDING_SOURCE_CONTENT to "<div/>"),
            )
        }.exceptionOrNull()
        // 绑定上下文里 url 本就未定义：JS 自身 typeof 分支能处理，不抛错
        assertEquals(null, error)
    }

    @Test fun undefinedVariableMessageDoesNotClaimReaderAlsoFails() {
        val error = runCatching {
            evaluator().evaluateRaw(
                "url",
                baseUrl = "https://example.test/book/1",
                bindings = mapOf(RhinoEvaluator.BINDING_SOURCE_CONTENT to "<div/>"),
            )
        }.exceptionOrNull()
        assertTrue("expected ReferenceError", error != null)
        val message = error!!.message.orEmpty()
        assertTrue(message, message.contains("baseUrl"))
        assertFalse("不得断言阅读端必然同样报错：$message", message.contains("阅读端同样会报"))
        assertFalse("不得把未定义一律归为规则错误：$message", message.contains("【规则错误】"))
    }
}
