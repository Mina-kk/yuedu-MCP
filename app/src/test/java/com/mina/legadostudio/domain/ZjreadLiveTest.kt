package com.mina.legadostudio.domain

import com.google.gson.Gson
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import io.legado.app.model.analyzeRule.LegadoRuleEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/** 临时验证 zjread_js_lib.js 在 Rhino 沙箱里跑通（真实联网）。验证完删除。 */
class ZjreadLiveTest {

    private val workDir = File(System.getProperty("user.dir")).let {
        if (File(it, "work/zjread").isDirectory) File(it, "work/zjread") else File(it.parentFile, "work/zjread")
    }
    private val libFile = File(workDir, "zjread_js_lib.js")

    private fun evaluator(): RhinoEvaluator = RhinoEvaluator(HttpFetcher(), Gson())
    private fun runtime() = EmbeddedLegadoRuntime(
        HttpFetcher(), BookSourceValidator(), LegadoRuleEngine(), RhinoEvaluator(HttpFetcher(), Gson())
    )

    @Before fun requireLocalWorkDir() {
        Assume.assumeTrue("缺少本地 work/zjread，跳过联网验证", libFile.isFile)
    }

    private fun lib() = libFile.readText()

    @Test fun probeSandboxApis() {
        val js = File(workDir, "rhino_probe.js").readText()
        val r = evaluator().evaluate(js, "https://www.zjread.cc")
        println(r.value)
        assertTrue(r.value != null && !r.value!!.contains("!!"))
    }

    @Test fun bootstrapAndConfig() = runBlocking {
        val js = lib() + """
ZJ.boot();
var cfg = ZJ.api("/api/config");
JSON.stringify({sid: ZJ.session_id, cfgCode: cfg.code});
"""
        val r = evaluator().evaluate(js, "https://www.zjread.cc")
        println(r.value)
        assertTrue(r.value != null && r.value!!.contains("\"cfgCode\":0"))
    }

    /** 单次 PBKDF2 计时（验证 Rhino 里可行，不跑完整 PoW） */
    @Test fun pbkdf2Timing() = runBlocking {
        val js = lib() + """
var t0=Date.now();
var dk=ZJ.debugPbkdf2();
JSON.stringify({ms:Date.now()-t0, len:dk.length});
"""
        val r = evaluator().evaluate(js, "https://www.zjread.cc")
        println(r.value)
        assertTrue(r.value != null && r.value!!.contains("\"len\":32"))
    }

    @Test fun detailCatalog() = runBlocking {
        val js = lib() + """
ZJ.boot();
var det=ZJ.detail("b~jUwff02tXeE5W4ZNwMRWSIQT-BOMvhtefH4-4YiOH1ftJi1on3s");
var cat=ZJ.catalog("b~jUwff02tXeE5W4ZNwMRWSIQT-BOMvhtefH4-4YiOH1ftJi1on3s");
JSON.stringify({det:det.code, cat:cat.code, n:cat.data&&cat.data.length, ch0:cat.data&&cat.data[0]&&cat.data[0].name});
"""
        val r = evaluator().evaluate(js, "https://www.zjread.cc")
        println(r.value)
        assertTrue(r.value != null && r.value!!.contains("\"det\":0") && r.value!!.contains("\"cat\":0"))
    }

    @Test fun chapterListHtml() = runBlocking {
        val js = lib() + """
var h=ZJ.chapterListHtml("https://www.zjread.cc/book/b~jUwff02tXeE5W4ZNwMRWSIQT-BOMvhtefH4-4YiOH1ftJi1on3s");
JSON.stringify({len:h.length, first:h.substring(0,160)});
"""
        val r = evaluator().evaluate(js, "https://www.zjread.cc")
        println(r.value)
        assertTrue(r.value != null && r.value!!.contains("read/"))
    }
}
