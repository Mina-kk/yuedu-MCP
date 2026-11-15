package com.mina.legadostudio.web

import android.app.Activity
import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.data.ProjectRepository
import com.mina.legadostudio.data.db.ProjectEntity
import com.mina.legadostudio.data.db.StudioDao
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Runs the real delivery dispatcher and repository; only Android construction/DAO IO/SAF are replaced. */
class SourceDirectDeliveryTest {
    private class Host : Activity() {
        lateinit var app: StudioApplication
        override fun getApplicationContext(): Context = app
    }
    private fun <T : Any> allocate(type: Class<T>): T {
        val klass = Class.forName("sun.misc.Unsafe")
        val unsafe = klass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return checkNotNull(type.cast(klass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)))
    }
    private fun row(id: String, raw: String) = ProjectEntity(id, "书源 $id", "https://same.example", raw, "draft", "", 1, 2)
    private val a = row("a", " {\n  \"bookSourceName\": \"A\", \"large\": 9007199254740993\n} ")
    private val b = row("b", "{\"bookSourceName\":\"B\",\"large\":9007199254740995}")
    private class Fixture(val module: SourceManagementModule, val requests: MutableList<JsonObject>, val reads: () -> Int)
    private fun fixture(rows: List<ProjectEntity> = listOf(a, b), saved: Boolean = true): Fixture {
        var reads = 0
        val dao = Proxy.newProxyInstance(StudioDao::class.java.classLoader, arrayOf(StudioDao::class.java)) { _, method, _ ->
            check(method.name == "allProjects") { "Unexpected DAO operation" }
            reads++; rows.toList()
        } as StudioDao
        val app = allocate(StudioApplication::class.java)
        for ((field, value) in listOf("projects\$delegate" to lazyOf(ProjectRepository(dao)), "gson\$delegate" to lazyOf(Gson()))) {
            StudioApplication::class.java.getDeclaredField(field).apply { isAccessible = true }.set(app, value)
        }
        val host = allocate(Host::class.java).apply { this.app = app }
        val requests = mutableListOf<JsonObject>()
        val module = SourceManagementModule(host) { p -> requests.add(p.deepCopy()); JsonObject().apply { addProperty("saved", saved) } }
        return Fixture(module, requests) { reads }
    }
    private fun request(json: String) = JsonParser.parseString(json).asJsonObject

    @Test fun draftFilenameIsSafeAndSourceBytesAreUntouched() = runBlocking {
        val f = fixture()
        val raw = "  {\"bookSourceName\":\"../书源/非法\\\\名称\\n\",\"big\":9007199254740997}  "
        val p = JsonObject().apply { addProperty("action", "save"); addProperty("source", raw); addProperty("name", "/private/ignored.json") }
        val result = f.module.dispatch("sources.deliver", p).asJsonObject
        val name = f.requests.single()["name"].asString
        assertTrue("Single source should have a useful name", name.contains("书源"))
        assertTrue(name.endsWith(".json"))
        assertFalse(name.contains('/') || name.contains('\\') || name.contains('\n') || name.startsWith('.'))
        assertFalse(name.contains("ignored"))
        assertTrue(f.requests.single()["text"].asString == "[\n$raw\n]")
        assertEquals(0, f.reads())
        assertEquals("source", result["scope"].asString)
        f.module.close()
    }

    @Test fun exactly500IdsAreAcceptedWithoutTruncation() = runBlocking {
        val rows = (1..500).map { a.copy(id = "id-$it") }
        val f = fixture(rows)
        val ids = rows.joinToString(",", "[", "]") { "\"${it.id}\"" }
        val result = f.module.dispatch("sources.deliver", request("{\"action\":\"save\",\"projectIds\":$ids}")).asJsonObject
        assertEquals(500, result["count"].asInt)
        assertEquals(1, f.reads())
        assertEquals("book-sources.json", f.requests.single()["name"].asString)
        f.module.close()
    }

    @Test fun wholeLibraryIncludesAllVersionsWithoutAnalysis() = runBlocking {
        val rows = (0..500).map { a.copy(id = "id-$it") } + b
        val f = fixture(rows)
        val result = f.module.dispatch("sources.deliver", request("""{"action":"save","scope":"all"}""")).asJsonObject
        assertTrue(result["saved"].asBoolean)
        assertEquals(1, f.reads())
        assertTrue(f.requests.single()["text"].asString == rows.joinToString(",\n", "[\n", "\n]") { it.sourceJson })
        assertEquals("all", result["scope"].asString)
        assertEquals(rows.size, result["count"].asInt)
        assertEquals("save", result["action"].asString)
        f.module.close()
    }

    @Test fun exactlyOneSelectorIsRequiredEvenForDraftSource() = runBlocking {
        val f = fixture()
        val selectors = listOf(""""source":"{}"""", """"projectIds":["a"]""", """"scope":"all"""", """"token":"snapshot"""")
        for (i in selectors.indices) for (j in i + 1 until selectors.size) {
            val p = request("{\"action\":\"save\",${selectors[i]},${selectors[j]}}")
            assertTrue("Mixed selectors must not export", runCatching { f.module.dispatch("sources.deliver", p) }.isFailure)
        }
        assertTrue(runCatching { f.module.dispatch("sources.deliver", request("""{"action":"save"}""")) }.isFailure)
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.reads())
        f.module.close()
    }

    @Test fun invalidIdsRejectEntireSelectionBeforeAnySave() = runBlocking {
        for (ids in listOf("[]", "[\"a\",\"a\"]", "[\"\"]", "[\" \u0020\"]", "[1]", "[null]", "{}", "null", "[\"a\",\"missing\"]", "[\" a\"]", (0..500).joinToString(",", "[", "]") { "\"id-$it\"" })) {
            val f = fixture()
            assertTrue("Invalid IDs must not produce partial export", runCatching { f.module.dispatch("sources.deliver", request("{\"action\":\"save\",\"projectIds\":$ids}")) }.isFailure)
            assertTrue(f.requests.isEmpty())
            f.module.close()
        }
    }

    @Test fun unknownScopeAndEmptyLibraryReject() = runBlocking {
        for (selector in listOf(""""scope":"page"""", """"scope":null""", """"scope":"all"""")) {
            val f = fixture(emptyList())
            assertTrue(runCatching { f.module.dispatch("sources.deliver", request("{\"action\":\"save\",$selector}")) }.isFailure)
            assertTrue(f.requests.isEmpty())
            f.module.close()
        }
    }

    @Test fun savedFalseRemainsFalse() = runBlocking {
        val f = fixture(saved = false)
        val result = f.module.dispatch("sources.deliver", request("""{"action":"save","projectIds":["a"]}""")).asJsonObject
        assertFalse(result["saved"].asBoolean)
        assertEquals("projectIds", result["scope"].asString)
        assertEquals(1, result["count"].asInt)
        f.module.close()
    }

    @Test fun strictValidationRejectsEntireDelivery() = runBlocking {
        for (bad in listOf("[]", "null", "{}{}", "{\"x\":1,}", "{unquoted:1}", "{\"x\":NaN}")) {
            val f = fixture(listOf(a, b.copy(sourceJson = bad)))
            assertTrue(runCatching { f.module.dispatch("sources.deliver", request("""{"action":"save","projectIds":["a","b"]}""")) }.isFailure)
            assertTrue(f.requests.isEmpty())
            f.module.close()
        }
    }

    @Test fun exactIdsDeliverOriginalBytesWithoutAnalysis() = runBlocking {
        val f = fixture()
        val result = f.module.dispatch("sources.deliver", request("""{"action":"save","projectIds":["b","a"]}""")).asJsonObject
        assertTrue(result["saved"].asBoolean)
        assertEquals(1, f.reads())
        assertEquals(1, f.requests.size)
        assertTrue("Original source bytes and requested ordering must survive", f.requests.single()["text"].asString == "[\n${b.sourceJson},\n${a.sourceJson}\n]")
        f.module.close()
    }
}
