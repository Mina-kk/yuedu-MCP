"""Run real SkillRepository with filesystem/prefs Android IO fixtures; no Gradle."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/mina/legadostudio'

class SkillManagementTest(unittest.TestCase):
    def test_saf_captures_before_picker(self):
        source = (SRC / 'web/SkillManagementSaf.kt').read_text()
        export = source.split('override suspend fun exportPackage', 1)[1].split('fun release()', 1)[0]
        self.assertLess(export.index('repository.snapshotPackage(id)'), export.index('document('))
        self.assertIn('snapshot.writeTo(it)', export)
        self.assertNotIn('repository.exportPackage', export)

    def test_repository_packages(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d)
            files = {
                'Context.kt': '''package android.content
import java.io.File
class Context(val filesDir: File, val cacheDir: File, val assets: Assets) {
 companion object { const val MODE_PRIVATE = 0 }
 val prefs = Prefs()
 fun getSharedPreferences(n: String, m: Int) = prefs
}
class Assets(val root: File) {
 fun list(p: String): Array<String>? = File(root,p).list()
 fun open(p: String) = File(root,p).inputStream()
}
class Prefs {
 val data = mutableMapOf<String,Boolean>()
 fun getBoolean(k: String, d: Boolean) = data[k] ?: d
 fun edit() = Editor(this)
}
class Editor(val p: Prefs) {
 fun putBoolean(k: String,v: Boolean): Editor { p.data[k]=v; return this }
 fun apply() {}
 fun commit() = true
}''',
                'Keep.kt': 'package androidx.annotation\nannotation class Keep',
                'SerializedName.kt': 'package com.google.gson.annotations\nannotation class SerializedName(val value: String)',
                'Test.kt': '''import android.content.*
import com.mina.legadostudio.skills.*
import com.mina.legadostudio.web.*
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import java.io.*
import java.util.zip.*
fun pack(vararg entries: Pair<String,ByteArray>): ByteArray {
 val out=ByteArrayOutputStream()
 ZipOutputStream(out).use { z -> entries.forEach { (n,b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
 return out.toByteArray()
}
fun reject(block: () -> Unit) { check(runCatching(block).isFailure) { "unsafe import was accepted" } }
fun main(args: Array<String>) {
 val root=File(args[0]); val assets=File(root,"assets"); File(assets,"skills/builtin").mkdirs()
 File(assets,"skills/builtin/SKILL.md").writeText("name: Builtin")
 val repo=SkillRepository(Context(File(root,"files").apply{mkdirs()},File(root,"cache").apply{mkdirs()},Assets(assets)))
 val peer=SkillRepository(Context(File(root,"files"),File(root,"cache"),Assets(assets)))
 // Block the actual ZIP sink at its first byte, then mutate through another repository.
 // A snapshot must already be complete, and the sink must not hold the repository lock.
 for (operation in listOf("delete", "save")) {
  repo.save("race", "name: Before")
  val entered=java.util.concurrent.CountDownLatch(1)
  val resume=java.util.concurrent.CountDownLatch(1)
  val sink=object: ByteArrayOutputStream() {
   override fun write(b: Int) { entered.countDown(); check(resume.await(5,java.util.concurrent.TimeUnit.SECONDS)); super.write(b) }
   override fun write(b: ByteArray, off: Int, len: Int) { entered.countDown(); check(resume.await(5,java.util.concurrent.TimeUnit.SECONDS)); super.write(b,off,len) }
  }
  val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
  try {
   val exporting=pool.submit { repo.exportPackage("race",sink) }
   check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
   val writing=pool.submit { if(operation=="delete") peer.delete("race") else peer.save("race","name: After") }
   try { writing.get(3,java.util.concurrent.TimeUnit.SECONDS) } finally { resume.countDown() }
   exporting.get(5,java.util.concurrent.TimeUnit.SECONDS)
   val contents=mutableMapOf<String,String>()
   ZipInputStream(sink.toByteArray().inputStream()).use { z -> while(true) { val e=z.nextEntry ?: break; contents[e.name]=z.readBytes().toString(Charsets.UTF_8) } }
   check(contents["race/SKILL.md"]=="name: Before") { "$operation changed export snapshot: $contents" }
  } finally { resume.countDown(); pool.shutdownNow() }
 }
 for (operation in listOf("save", "delete", "import", "enable", "export", "read")) {
  repo.save("shared", "name: Shared")
  val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
  val started=java.util.concurrent.CountDownLatch(1)
  val release=java.util.concurrent.CountDownLatch(1)
  val held=java.util.concurrent.CountDownLatch(1)
  try {
   val holder=pool.submit { repo.withSnapshot { held.countDown(); check(release.await(5,java.util.concurrent.TimeUnit.SECONDS)) } }
   check(held.await(5,java.util.concurrent.TimeUnit.SECONDS))
   val writer=pool.submit {
    started.countDown()
    when(operation) {
     "save" -> peer.save("shared","name: Changed")
     "delete" -> peer.delete("shared")
     "import" -> peer.importPackage("fresh.md","name: Fresh".byteInputStream())
     "enable" -> peer.setEnabled("shared",false)
     "export" -> peer.exportPackage("shared",ByteArrayOutputStream())
     else -> peer.read("shared")
    }
   }
   check(started.await(5,java.util.concurrent.TimeUnit.SECONDS))
   check(runCatching { writer.get(150,java.util.concurrent.TimeUnit.MILLISECONDS) }.exceptionOrNull() is java.util.concurrent.TimeoutException) { "$operation bypassed shared snapshot" }
   release.countDown(); holder.get(5,java.util.concurrent.TimeUnit.SECONDS); writer.get(5,java.util.concurrent.TimeUnit.SECONDS)
  } finally { release.countDown(); pool.shutdownNow() }
 }
 repo.save("immutable","name: Immutable")
 val snapshot=repo.snapshotPackage("immutable")
 val original=ByteArrayOutputStream().also { snapshot.writeTo(it) }.toByteArray()
 snapshot.writeTo(object: OutputStream() {
  override fun write(b: Int) {}
  override fun write(b: ByteArray) { b.fill(0) }
 })
 val again=ByteArrayOutputStream().also { snapshot.writeTo(it) }.toByteArray()
 check(original.contentEquals(again)) { "sink mutated snapshot bytes" }
 File(root,"files/skills/broken").mkdirs()
 val brokenOut=ByteArrayOutputStream()
 reject { repo.exportPackage("broken",brokenOut) }
 check(brokenOut.size()==0) { "invalid package wrote output" }
 val md="name: Demo\\n# Demo".toByteArray()
 val bytes=pack("demo/SKILL.md" to md,"demo/references/a.md" to "reference".toByteArray(),"demo/assets/a.bin" to byteArrayOf(0,1,-1))
 val info=repo.importPackage("demo.zip",bytes.inputStream()); check(info.id=="demo")
 repo.setEnabled("demo",false); check(!repo.isEnabled("demo")); check(repo.read("demo").contains("Demo"))
 val out=ByteArrayOutputStream(); repo.exportPackage("demo",out)
 repo.delete("demo"); repo.importPackage("roundtrip.zip",out.toByteArray().inputStream())
 check(File(root,"files/skills/demo/assets/a.bin").readBytes().contentEquals(byteArrayOf(0,1,-1)))
 check(repo.readReference("demo","references/a.md")=="reference")
 reject { repo.importPackage("bad.zip",pack("../escape" to md,"evil/SKILL.md" to md).inputStream()) }
 reject { repo.importPackage("bad.zip",pack("/absolute" to md,"evil/SKILL.md" to md).inputStream()) }
 reject { repo.importPackage("bad.zip",pack("a/SKILL.md" to md,"b/SKILL.md" to md).inputStream()) }
 reject { repo.importPackage("builtin.zip",pack("builtin/SKILL.md" to md).inputStream()) }
 reject { repo.importPackage("big.md",ByteArray(2*1024*1024+1){65}.inputStream()) }
 reject { repo.importPackage("bad.txt",md.inputStream()) }
 reject { repo.importPackage("demo.zip",bytes.inputStream()) }
 reject { repo.importPackage("big.zip",pack("large/SKILL.md" to md,"large/a.bin" to ByteArray(20*1024*1024+1)).inputStream()) }
 reject { repo.importPackage("many.zip",pack("many/SKILL.md" to md, *Array(500) { "many/$it" to byteArrayOf(1) }).inputStream()) }
 val rootInfo=repo.importPackage("root-skill.zip",pack("SKILL.md" to md,"assets/a.bin" to byteArrayOf(9)).inputStream())
 check(rootInfo.id=="root-skill")
 check(repo.read("demo").contains("Demo")); check(!File(root,"cache").listFiles().orEmpty().any())
 val builtin=ByteArrayOutputStream(); repo.exportPackage("builtin",builtin); check(builtin.size()>0)
 runBlocking {
  val docs = object : SkillManagementDocuments {
   override suspend fun importPackage(repository: SkillRepository): SkillRepository.SkillInfo? = null
   override suspend fun exportPackage(repository: SkillRepository,id: String) = false
  }
  val module=SkillManagementModule(repo,docs)
  val params=JsonObject().apply { addProperty("id","demo") }
  check(!module.handle("skills.read",params).get("enabled").asBoolean)
  params.addProperty("enabled",true)
  check(module.handle("skills.setEnabled",params).get("enabled").asBoolean)
  check(repo.isEnabled("demo"))
  check(module.handle("skills.import",JsonObject()).get("cancelled").asBoolean)
  check(!module.handle("skills.export",params).get("saved").asBoolean)
  params.addProperty("enabled","false")
  check(runCatching { module.handle("skills.setEnabled",params) }.isFailure)
 }
 println("PASS: attachments roundtrip, disabled read, built-in protection, traversal, ambiguity, limits, cleanup, management module")
}'''
            }
            for name, content in files.items():
                (p / name).write_text(content)
            libs = pathlib.Path('/opt/gradle-8.13/lib')
            std = libs / 'kotlin-stdlib-2.0.21.jar'
            gson = next(pathlib.Path('/root/.gradle/caches').rglob('gson-2.11.0.jar'))
            cp = f'{std}:{gson}:{libs / "kotlinx-coroutines-core-jvm-1.6.4.jar"}'
            subprocess.run(['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', str(libs / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-classpath', cp, '-d', str(p / 'out'), str(SRC / 'skills/SkillRepository.kt'), str(SRC / 'web/SkillManagementModule.kt'), *map(str, p.glob('*.kt'))], check=True)
            subprocess.run(['java', '-Xmx128m', '-cp', f'{p / "out"}:{cp}', 'TestKt', str(p / 'data')], check=True)

if __name__ == '__main__':
    unittest.main()
