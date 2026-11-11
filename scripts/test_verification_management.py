"""Run actual pure Kotlin probe without Gradle or an emulator."""
import glob
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class VerificationManagementTest(unittest.TestCase):
    def test_kotlin_probe(self):
        jars = []
        for artifact in ('kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-reflect', 'kotlinx-coroutines-core-jvm', 'annotations'):
            matches = glob.glob(str(Path.home() / '.gradle/caches/modules-2/files-2.1' / '*' / artifact / '**' / '*.jar'), recursive=True)
            if matches:
                jars.append(sorted(matches)[-1])
        cp = os.pathsep.join(jars)
        base = ROOT / 'app/src/main/java/com/mina/legadostudio/web'
        test = ROOT / 'app/src/test/java/com/mina/legadostudio/web/VerificationManagementProbeCheck.kt'
        with tempfile.TemporaryDirectory(prefix='verification-', dir=os.environ.get('TMPDIR')) as out:
            subprocess.run(['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', out, str(base / 'VerificationManagementProbe.kt'), str(test)], check=True, timeout=60)
            subprocess.run(['java', '-cp', out + os.pathsep + cp, 'com.mina.legadostudio.web.VerificationManagementProbeCheckKt'], check=True, timeout=15)

if __name__ == '__main__':
    unittest.main()
