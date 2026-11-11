"""Lightweight real Kotlin DAO algorithm + SQLite JDBC transaction/concurrency probe.
Room generated Android transaction wrapper is not exercised by this JVM test.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/java/com/mina/legadostudio'

class AtomicDeleteTest(unittest.TestCase):
    def test_transaction_contract_and_execution(self):
        source = (BASE / 'data/db/StudioDao.kt').read_text()
        self.assertRegex(source, r'@Transaction\s+suspend fun deleteProjectsFromSnapshot')
        cache = Path('/root/.gradle/caches')
        def jar(name): return str(next(cache.rglob(name)))
        compiler = os.pathsep.join(jar(n) for n in ['kotlin-compiler-embeddable-2.3.20.jar','kotlin-stdlib-2.3.20.jar','kotlin-script-runtime-2.3.20.jar','kotlin-reflect-1.6.10.jar','kotlinx-coroutines-core-jvm-1.8.0.jar','annotations-13.0.jar'])
        cp = os.pathsep.join([str(ROOT/'app/build/tmp/kotlin-classes/debug'),jar('kotlin-stdlib-2.3.20.jar'),jar('kotlinx-coroutines-core-jvm-1.8.0.jar'),jar('room-common-jvm-2.8.4.jar'),jar('sqlite-jdbc-3.41.2.2.jar')])
        with tempfile.TemporaryDirectory(prefix='source-atomic-') as d:
            out = Path(d)/'out'
            probe = ROOT/'app/src/test/java/com/mina/legadostudio/data/SourceAtomicDeleteProbe.kt'
            subprocess.run(['java','-Xmx256m','-XX:ActiveProcessorCount=2','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(out),str(BASE/'data/db/StudioDao.kt'),str(BASE/'data/ProjectRepository.kt'),str(probe)],check=True,timeout=90)
            subprocess.run(['java','-Xmx128m','-cp',str(out)+os.pathsep+cp,'com.mina.legadostudio.data.SourceAtomicDeleteProbeKt',str(Path(d)/'test.db')],check=True,timeout=45)

if __name__ == '__main__': unittest.main()
