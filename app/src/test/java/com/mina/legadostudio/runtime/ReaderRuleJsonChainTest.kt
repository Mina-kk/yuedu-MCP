package com.mina.legadostudio.runtime

import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import com.script.ScriptException
import com.script.ScriptBindings
import org.htmlunit.corejs.javascript.NativeObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import com.script.rhino.RhinoScriptEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed upstream AnalyzeRule.kt:325-364: JSONPath Maps are not NativeObject. */
class ReaderRuleJsonChainTest {
    private val bookId = "6aa27d43ebce31446c4cd172"
    private val chapterId = "6aa27d44ebce31446c4cd177"

    private fun evaluate(script: String, result: Any?, baseUrl: String): Any? {
        val scope = ScriptBindings()
        scope.put("result", result)
        scope.put("baseUrl", baseUrl)
        scope.chainTo(RhinoScriptEngine.newStandardTopLevel())
        return RhinoScriptEngine.eval(script, scope)
    }

    @Test
    fun jsonPathRowRunsUnchangedSearchBookUrlRule() {
        val row = ReaderRuleSession("""[{"id":"$bookId"}]""").getElements("$[*]").single()
        assertEquals(java.util.LinkedHashMap::class.java, row.javaClass)
        var calls = 0
        val session = ReaderRuleSession(row, "https://jiuxiaozw.com/api/books", evalJs = { script, result ->
            calls++
            evaluate(script, result, "https://jiuxiaozw.com/api/books")
        })
        // Exact ruleSearch.bookUrl from the captured jiuxiao source; no source rewrite.
        val rule = "$.id<js>var id = String(result); if (!/^[a-f0-9]{24}$/.test(id)) throw new Error('Invalid book id'); 'https://jiuxiaozw.com/book/' + id;</js>"
        assertEquals("https://jiuxiaozw.com/book/$bookId", session.getString(rule, isUrl = true))
        assertEquals(1, calls)
        assertTrue(session.content === row)
    }

    private fun session(content: Any, baseUrl: String = "https://example.com/"): ReaderRuleSession =
        ReaderRuleSession(content, baseUrl, evalJs = { script, result -> evaluate(script, result, baseUrl) })

    @Test
    fun jsonPathRowRunsUnchangedChapterUrlRule() {
        val row = session("""{"rows":[{"id":"$chapterId"}]}""").getElements("$.rows[*]").single()
        val base = "https://jiuxiaozw.com/api/books/$bookId/catalog?offset=0&limit=30"
        val rule = """$.id<js>var id = String(result);
var m = /\/api\/books\/([a-f0-9]{24})\/catalog(?:\?|$)/.exec(String(baseUrl));
if (!m || !/^[a-f0-9]{24}$/.test(id)) throw new Error('Invalid catalog chapter identity');
'https://jiuxiaozw.com/book/' + m[1] + '/' + id;</js>"""
        assertEquals("https://jiuxiaozw.com/book/$bookId/$chapterId", session(row, base).getString(rule))
    }

    @Test
    fun nestedJsonPathRowsKeepMapsAndRunAllSegments() {
        val session = session("""{"groups":[{"rows":[{"id":"a"},{"id":"b"}]}]}""")
        val groups = session.getElements("$.groups[*]")
        val rows = session(groups.single()).getElements("$.rows[*]")
        assertTrue(rows.all { it is java.util.LinkedHashMap<*, *> })
        assertEquals(listOf("/book/A!", "/book/B!"), rows.map {
            session(it).getString("$.id<js>String(result).toUpperCase()</js><js>'/book/' + result + '!'</js>")
        })
    }

    @Test
    fun ordinaryMapsRunStringAndListChainsIncludingContentOverride() {
        for (row in listOf(mapOf("id" to "a"), hashMapOf("id" to "a"), linkedMapOf("id" to "a"))) {
            val session = session("<p>original HTML</p>")
            assertEquals("/book/a", session.getString("id<js>'/book/' + result</js>", row))
            assertEquals(listOf("/book/a"), session.getStringList("id<js>'/book/' + result[0]</js>", row))
            assertEquals(listOf("a!"), session(row).getStringList("$.id<js>String(result[0]) + '!'</js>"))
        }
    }

    @Test
    fun mapChainRunsPutReplacementAndPropagatesJavascriptErrors() {
        val vars = hashMapOf<String, String>()
        val session = ReaderRuleSession(linkedMapOf("id" to "abc"), evalJs = { script, result ->
            evaluate(script, result, "")
        }, getVariable = { vars[it].orEmpty() }, putVariable = { key, value -> vars[key] = value })
        assertEquals("xbc!", session.getString("@put:{\"id\":\"$.id\"}$.id##a##x<js>result + '!'</js>"))
        assertEquals("abc", vars["id"])
        assertThrows(ScriptException::class.java) {
            session.getString("$.id<js>throw new Error('chain failure')</js>")
        }
        val errors = mutableListOf<Exception>()
        val broken = session(linkedMapOf("id" to "abc"))
        broken.onError = { errors += it }
        assertEquals("", broken.getString("$.[[["))
        assertEquals(1, errors.size)
    }

    @Test
    fun realNativeObjectRetainsFirstRuleAndDirectPropertySemantics() {
        val native = evaluate("({'a.b':'direct', id:'abc', names:['a','b']})", null, "")
        assertTrue(native is NativeObject)
        val session = session(native!!)
        assertEquals("abc", session.getString("$.id<js>throw new Error('must not run')</js>"))
        // @@ selects the upstream direct-key branch even though this session also supports JSON.
        assertEquals("direct", session.getString("@@a.b<js>throw new Error('must not run')</js>"))
        assertEquals(listOf("a", "b"), session.getStringList("$.names<js>throw new Error('must not run')</js>"))
        assertEquals("ABC", session.getString("<js>String(result.id).toUpperCase()</js><js>throw new Error('must not run')</js>"))
        assertThrows(ScriptException::class.java) { session.getString("<js>throw new Error('native failure')</js>") }
    }

    @Test
    fun realNativeObjectKeepsPutReplacementAndTypedElements() {
        val native = evaluate("({id:'abc', names:['abc','abd']})", null, "")!!
        val vars = hashMapOf<String, String>()
        val session = ReaderRuleSession(native, evalJs = { script, result -> evaluate(script, result, "") },
            getVariable = { vars[it].orEmpty() }, putVariable = { key, value -> vars[key] = value })
        assertEquals("xbc", session.getString("@put:{\"saved\":\"$.id\"}$.id##a##x<js>throw new Error('must not run')</js>"))
        assertEquals("abc", vars["saved"])
        assertEquals(listOf("xbc", "xbd"), session.getStringList("$.names##a##x<js>throw new Error('must not run')</js>"))
        val rows = session.getElements("<js>[{id:'a'},{id:'b'}]</js>")
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is NativeObject })
        assertEquals("a", session.getString("@@id", rows.first()))
        assertTrue(session.getElements("<js>123</js>").isEmpty())
    }

    @Test
    fun gsonLinkedTreeMapKeepsUpstreamDirectFirstKeyBranch() {
        val row = Gson().fromJson("""{"id":"abc","names":["a","b"]}""", Map::class.java)
        assertTrue(row is LinkedTreeMap<*, *>)
        val session = session(row)
        assertEquals("abc", session.getString("id<js>throw new Error('must not run')</js>"))
        assertEquals(listOf("a", "b"), session.getStringList("names<js>throw new Error('must not run')</js>"))
        assertEquals("", session.getString("$.id"))
        assertNull(session.getStringList("$.names"))
    }
}
