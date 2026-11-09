package com.mina.legadostudio.runtime

import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReaderRuleSession 契约测试。
 *
 * 每个用例断言的上游行为都标了 LegadoTeam/legado@330e606c 的文件:行号，
 * 见 docs/research/rule-engine-implementation.md 的用例表。
 */
class ReaderRuleSessionTest {

    private val html = """
        <html><body>
          <div id="book">
            <h1 class="name">星辰变</h1>
            <span class="author">我吃西红柿</span>
            <a class="detail" href="/book/1.html">详情</a>
          </div>
          <ul id="list">
            <li><a href="/read/1.html">第一章 开端</a></li>
            <li><a href="/read/2.html">第二章 风起</a></li>
          </ul>
        </body></html>
    """.trimIndent()

    private fun session(
        content: Any? = html,
        baseUrl: String = "https://example.com/catalog/",
        evalJs: ((String, Any?) -> Any?)? = null,
    ): ReaderRuleSession {
        val vars = HashMap<String, String>()
        return ReaderRuleSession(
            content = content,
            baseUrl = baseUrl,
            evalJs = evalJs ?: { _, _ -> throw AssertionError("unexpected JS evaluation") },
            getVariable = { vars[it].orEmpty() },
            putVariable = { k, v -> vars[k] = v },
        )
    }

    // ------------------------------------------------------------- 默认 CSS 链

    @Test
    fun defaultCssChainReadsTextAndAttribute() {
        val s = session()
        assertEquals("星辰变", s.getString("class.name@text"))
        assertEquals("我吃西红柿", s.getString("class.author@text"))
        assertEquals("/book/1.html", s.getString("class.detail@href"))
    }

    @Test
    fun defaultCssListJoinsWithNewline() {
        // 上游 AnalyzeByJSoup.getString:44-56：多结果 joinToString("\n")
        val s = session()
        assertEquals("第一章 开端\n第二章 风起", s.getString("#list li a@text"))
    }

    @Test
    fun emptyRuleReturnsEmptyString() {
        val s = session()
        assertEquals("", s.getString(null))
        assertEquals("", s.getString(""))
        assertNull(s.getStringList(""))
    }

    // ------------------------------------------------------------- @put / @get

    @Test
    fun putThenGetAcrossFields() {
        // 上游 AnalyzeRule.putRule:507-511 + makeUpRule @get 分支:814-816
        val s = session()
        assertEquals("星辰变", s.getString("@put:{\"name\":\"class.name@text\"}class.name@text"))
        assertEquals("星辰变", s.get("name"))
        // 后续字段用 @get 引用变量
        assertEquals("星辰变-我吃西红柿", s.getString("@get:{name}-{{@CSS:.author@text}}"))
    }

    @Test
    fun putRuleValueIsEvaluatedAsRule() {
        val s = session()
        s.getString("@put:{\"u\":\"class.detail@href\"}class.name@text")
        assertEquals("/book/1.html", s.get("u"))
    }

    @Test
    fun getMissingVariableIsEmpty() {
        assertEquals("", session().get("nope"))
    }

    // ------------------------------------------------------------- 前缀模式

    @Test
    fun explicitXPathAndJsonAndCssPrefixes() {
        val s = session()
        assertEquals("星辰变", s.getString("@XPath://h1/text()"))
        assertEquals("我吃西红柿", s.getString("@xpath://span[@class='author']/text()"))
        assertEquals("星辰变", s.getString("@@class.name@text"))
        assertEquals("星辰变", s.getString("@CSS:.name@text"))
    }

    @Test
    fun jsonContentUsesJsonPathByDefault() {
        // 上游 SourceRule.init:685：isJSON 时无前缀规则走 JsonPath
        val json = """{"bookName":"星辰变","chapters":[{"name":"第一章"},{"name":"第二章"}]}"""
        val s = session(content = json)
        assertEquals("星辰变", s.getString("bookName"))
        assertEquals("星辰变", s.getString("$.bookName"))
        assertEquals(listOf("第一章", "第二章"), s.getStringList("$.chapters[*].name"))
    }

    @Test
    fun jsonListFieldKeepsTypedObjects() {
        val json = """{"chapters":[{"name":"第一章","url":"/1"},{"name":"第二章","url":"/2"}]}"""
        val s = session(content = json)
        val elements = s.getElements("$.chapters[*]")
        assertEquals(2, elements.size)
        assertTrue("JSON list 元素必须保留 Map，不能预字符串化", elements[0] is Map<*, *>)
        // 子规则在元素自身上求值
        assertEquals("第一章", s.getString("name", elements[0]))
    }

    // ------------------------------------------------------------- regex $n

    @Test
    fun regexCaptureGroupsAreTypedLists() {
        // 上游 AnalyzeRule.getElements:464-467 → AnalyzeByRegex.getElements 返回 List<List<String>>
        val s = session(content = "第1章 开端|第2章 风起")
        val elements = s.getElements(":第(\\d+)章\\s*([^|]+)")
        assertEquals(2, elements.size)
        val first = elements[0]
        assertTrue("capture 必须是 List，不能压成 firstOrNull", first is List<*>)
        assertEquals(listOf("第1章 开端", "1", "开端"), first as List<*>)
    }

    @Test
    fun regexDollarGroupSelectsCapture() {
        // 上游 SourceRule.splitRegex:744-771：$1 引用当阶段 capture list。
        // `$n` 只在 getStringList/getElements 的 List 结果上生效（getString 的 Regex 分支返回字面量）。
        val s = session(content = "第1章 开端|第2章 风起")
        val first = s.getElements(":第(\\d+)章\\s*([^|]+)").first()
        assertEquals("1", s.getString("$1", first))
        assertEquals("开端", s.getString("$2", first))
        assertEquals(listOf("开端"), s.getStringList("$2", first))
    }

    @Test
    fun regexStringListFlattensCaptures() {
        val s = session(content = "第1章 开端|第2章 风起")
        val captures = s.getElements(":第(\\d+)章\\s*([^|]+)")
        val list = captures.map { s.getStringList("$1\\n$2", it) }
        assertEquals(
            listOf(listOf("1\\n开端"), listOf("2\\n风起")),
            list,
        )
    }

    // ------------------------------------------------------------- {{}} / @get

    @Test
    fun doubleBraceRuleIsEvaluatedAsRule() {
        // 上游 makeUpRule:794-812：{{}} 内以 @ / $. / // 开头视为规则
        val s = session()
        assertEquals("星辰变/我吃西红柿", s.getString("{{@CSS:.name@text}}/{{@CSS:.author@text}}"))
    }

    @Test
    fun doubleBraceJsUsesEvaluator() {
        val seen = ArrayList<Pair<String, Any?>>()
        val s = session(evalJs = { script, result ->
            seen += script to result
            "JS:$script"
        })
        assertEquals("JS:1+1", s.getString("{{1+1}}"))
        assertEquals("1+1", seen.single().first)
    }

    @Test
    fun jsPrefixAndMultiSegmentJs() {
        // 上游 splitSourceRule:607-617：@js: 之后整段是 JS；`<js>` 与 @js 可多段
        val s = session(evalJs = { script, _ -> "[" + script.trim() + "]" })
        assertEquals("[1+1]", s.getString("@js:1+1"))
        assertEquals("[b]", s.getString("<js>a</js><js>b</js>"))
    }

    @Test
    fun jsReceivesPreviousStageResultNotOriginalContent() {
        // 上游 getString:347-350：result 流经当前阶段；src 才是原始内容（由 runtime 绑定）
        val results = ArrayList<Any?>()
        val s = session(evalJs = { _, result -> results += result; "x" })
        s.getString("class.name@text<js>x</js>")
        val first = results.single()
        assertEquals("getString 的 @text 段给后续 JS 的 result 是已提取文本", "星辰变", first)
    }

    // ------------------------------------------------------------- ## 替换

    @Test
    fun replaceRegexAppliesToResult() {
        // 上游 AnalyzeRule.getString:361-363 / replaceRegex:544-568
        val s = session()
        assertEquals("辰变", s.getString("class.name@text##星##"))
        assertEquals("变", s.getString("class.name@text##星辰##"))
    }

    @Test
    fun replaceFirstOnlyReplacesFirstMatch() {
        val s = session(content = "aaaa")
        assertEquals("b", s.getString("##a##b###"))
        assertEquals("bbbb", s.getString("##a##b"))
    }

    // ------------------------------------------------------------- list 语义

    @Test
    fun finalStringIsSplitByNewline() {
        // 上游 getStringList:279-281：最终 String 按 \n 拆行
        val s = session()
        assertEquals(listOf("第一章 开端", "第二章 风起"), s.getStringList("#list li a@text"))
    }

    @Test
    fun jsObjectListFieldKeepsObjects() {
        // JS 返回对象数组时不能预 stringify
        val s = session(evalJs = { _, _ -> listOf(mapOf("name" to "第一章"), mapOf("name" to "第二章")) })
        val elements = s.getElements("@js:chapters")
        assertEquals(2, elements.size)
        assertTrue(elements[0] is Map<*, *>)
        assertEquals("第一章", s.getString("name", elements[0]))
    }

    @Test
    fun getElementsOnScalarIsEmpty() {
        // 上游 getElements:477-501：非列表结果返回空列表
        val s = session()
        assertEquals(emptyList<Any>(), s.getElements("class.name@text"))
        assertTrue(s.getElements("class.name@text").isEmpty())
    }

    @Test
    fun getElementsKeepsDomNodesTyped() {
        val s = session()
        val elements = s.getElements("#list li a")
        assertEquals(2, elements.size)
        assertTrue(elements[0] is Element)
    }

    @Test
    fun getElementReturnsNodeForCssAndNullForEmptyRule() {
        val s = session()
        val nodes = s.getElement("class.name") as List<*>
        assertTrue(nodes.single() is Element)
        assertNull(s.getElement(""))
    }

    // ------------------------------------------------------------- isUrl

    @Test
    fun isUrlResolvesRelativeAndDedupes() {
        // 上游 getStringList:282-293
        val s = session()
        assertEquals(
            listOf("https://example.com/read/1.html", "https://example.com/read/2.html"),
            s.getStringList("#list li a@href", isUrl = true),
        )
    }

    @Test
    fun isUrlFallsBackToBaseUrlWhenEmpty() {
        // 上游 getString:374-380
        val s = session()
        assertEquals("https://example.com/catalog/", s.getString("class.missing@href", isUrl = true))
    }

    @Test
    fun isUrlTakesFirstDomResultOnly() {
        // 上游 getString:352-356 → getString0
        val s = session()
        assertEquals(
            "https://example.com/read/1.html",
            s.getString("#list li a@href", isUrl = true),
        )
    }

    @Test
    fun absoluteUrlKeepsAbsAndDataUrls() {
        val s = session()
        assertEquals("https://other/x", s.absoluteUrl("https://other/x"))
        assertEquals("data:image/png;base64,AAA", s.absoluteUrl("data:image/png;base64,AAA"))
        assertEquals("", s.absoluteUrl("javascript:void(0)"))
    }

    // ------------------------------------------------------------- 平台能力

    @Test(expected = UnsupportedOperationException::class)
    fun webJsWithoutPlatformIsExplicitlyUnsupported() {
        session().getString("@webjs:document.title")
    }

    @Test
    fun jsEvaluatorErrorsPropagate() {
        val s = session(evalJs = { _, _ -> throw IllegalStateException("boom") })
        try {
            s.getString("@js:throw new Error('x')")
            throw AssertionError("JS 错误必须抛出，不能静默返回假值")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun contentCannotBeNull() {
        try {
            ReaderRuleSession(null)
            throw AssertionError("上游 setContent 对 null 内容抛 AssertionError")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.isNullOrEmpty())
        }
    }

    @Test
    fun setContentResetsAnalyzersAndBaseUrl() {
        val s = session()
        assertEquals("星辰变", s.getString("class.name@text"))
        s.setContent("<html><body><h1 class=\"name\">新书</h1></body></html>", "https://b.example/")
        assertEquals("新书", s.getString("class.name@text"))
        assertEquals("https://b.example/", s.baseUrl)
    }
}
