"""Lightweight actual host/engine/recorder regression; no Gradle or emulator."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class RecoveryTest(unittest.TestCase):
    def test_real_summary_failure(self):
        cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
        names = ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-reflect', 'annotations', 'trove4j', 'kotlinx-coroutines-core-jvm', 'gson', 'okhttp-jvm', 'okio-jvm']
        jars = []
        for name in names:
            matches = sorted(cache.glob(f'*/{name}/**/*.jar'))
            if matches:
                preferred = [p for p in matches if '/2.3.20/' in str(p) or '/1.10.2/' in str(p)]
                jars.append((preferred or matches)[-1])
        jars += list((Path.home() / '.gradle/caches/8.13/transforms').glob('*/transformed/jetified-okhttp-release-runtime.jar'))[:1]
        cp = os.pathsep.join(map(str, jars))
        classes = ROOT / 'app/build/tmp/kotlin-classes/debug'
        android = '/opt/android-sdk/platforms/android-36/android.jar'
        base = ROOT / 'app/src/main/java/com/mina/legadostudio'
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'), prefix='capture-recovery-') as out:
            # Android logging is the only runtime platform API exercised by the failed writer.
            stub = Path(out) / 'Log.java'
            stub.write_text('package android.util; public class Log { public static int w(String t,String m){return 0;} public static int e(String t,String m,Throwable e){return 0;} }')
            subprocess.run(['javac', '-d', out, str(stub)], check=True)
            sources = [base / 'network/HttpLogRecorder.kt', base / 'web/InteractiveCaptureHost.kt', ROOT / 'app/src/test/java/com/mina/legadostudio/web/InteractiveCaptureRecoveryCheck.kt']
            subprocess.run(['java','-Xmx384m','-XX:ActiveProcessorCount=2','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17',f'-Xfriend-paths={classes}','-classpath',f'{classes}:{android}:{cp}','-d',out,*map(str,sources)], check=True, timeout=90)
            subprocess.run(['java','-Xmx256m','-XX:ActiveProcessorCount=2','-cp',f'{out}:{classes}:{android}:{cp}','com.mina.legadostudio.web.InteractiveCaptureRecoveryCheckKt'],check=True,timeout=20)

if __name__ == '__main__': unittest.main()
