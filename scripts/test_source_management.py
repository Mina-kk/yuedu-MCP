"""Compile the real source module against cached app classes + Android API, no Gradle/stubs."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/mina/legadostudio/web/SourceManagementModule.kt'

class SourceManagementTest(unittest.TestCase):
    def test_reader_delivery_does_not_reserialize(self):
        source = SRC.read_text()
        self.assertNotIn('ReaderImport.launch(activity, text, pkg)', source,
                         'ReaderImport.launch rewrites raw JSON through SourceImportPayload')
        self.assertIn('ReaderImportService.prepare(activity, text, ReaderImport.TTL_MS)', source)

    def test_real_module(self):
        libs = Path('/opt/gradle-8.13/lib')
        cache = Path('/root/.gradle/caches')
        compiler_cp = os.pathsep.join(str(next(cache.rglob(name))) for name in ['kotlin-compiler-embeddable-2.3.20.jar', 'kotlin-stdlib-2.3.20.jar', 'kotlin-script-runtime-2.3.20.jar', 'kotlin-reflect-1.6.10.jar', 'kotlinx-coroutines-core-jvm-1.8.0.jar', 'annotations-13.0.jar'])
        classes = ROOT / 'app/build/tmp/kotlin-classes/debug'
        cp = os.pathsep.join(map(str, [classes, Path('/opt/android-sdk/platforms/android-36/android.jar'), next(cache.rglob('kotlin-stdlib-2.3.20.jar')), libs / 'kotlinx-coroutines-core-jvm-1.6.4.jar', next(cache.rglob('gson-2.11.0.jar')), next(cache.rglob('core-1.18.0-api.jar')), next(cache.rglob('room-common-jvm-2.8.4.jar'))]))
        with tempfile.TemporaryDirectory(prefix='source-module-') as d:
            out = Path(d) / 'out'
            probe = Path(d) / 'SourceProbe.kt'
            probe.write_text('''package com.mina.legadostudio.web
import sun.misc.Unsafe
fun main() {
 val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
 val instance = (unsafeField.get(null) as Unsafe).allocateInstance(SourceManagementModule::class.java)
 val array = SourceManagementModule::class.java.getDeclaredMethod("array", List::class.java).apply { isAccessible = true }
 val raw = " \\r\\n{ \\"n\\":9007199254740993, \\"text\\":\\"中文\\", \\"n\\":1e+03 }\\t"
 check(array.invoke(instance,listOf(raw, "{}")) == "[\\n" + raw + ",\\n{}\\n]")
 for (bad in listOf("{unquoted:1}", "{\\"a\\":1}//comment", "[]", "null", "{} {}")) {
  check(runCatching { array.invoke(instance,listOf(bad)) }.isFailure) { "accepted unsafe JSON: $bad" }
 }
 val mutexField = SourceManagementModule::class.java.getDeclaredField("mutex").apply { isAccessible = true }
 mutexField.set(instance, kotlinx.coroutines.sync.Mutex())
 kotlinx.coroutines.runBlocking {
  for (scope in listOf("all", "domain")) {
   val p = com.google.gson.JsonObject().apply { addProperty("scope",scope); addProperty("token","snapshot"); addProperty("confirmation","清空全库") }
   val failure = runCatching { (instance as SourceManagementModule).dispatch("sources.delete",p) }.exceptionOrNull()
   check(failure is IllegalStateException && failure.message!!.contains("重新分析")) { "missing snapshot must fail before storage: $failure" }
  }
 }
 println("PASS raw bytes, strict JSON rejection, fail-closed deletion")
}
''')
            subprocess.run(['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-Xfriend-paths=' + str(classes), '-classpath', cp, '-d', str(out), str(SRC), str(ROOT / 'app/src/main/java/com/mina/legadostudio/data/ProjectRepository.kt'), str(ROOT / 'app/src/main/java/com/mina/legadostudio/data/db/StudioDao.kt'), str(probe)], check=True, timeout=90)
            subprocess.run(['java', '-Xmx128m', '-cp', str(out) + os.pathsep + cp, 'com.mina.legadostudio.web.SourceProbeKt'], check=True, timeout=30)

if __name__ == '__main__':
    unittest.main()
