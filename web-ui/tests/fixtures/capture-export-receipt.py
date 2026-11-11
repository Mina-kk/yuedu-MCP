"""Generate a fresh receipt using actual Kotlin host/exporter, without Gradle.

Requires cached Kotlin/JVM dependencies, Android SDK and existing app classes.
Missing prerequisites fail the Vue integration test; never skip or reuse JSON.
Only Android construction, Room IO and the SAF callback are fixture boundaries.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'


def main():
    jars = []
    for name in ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime',
                 'kotlin-reflect', 'annotations', 'trove4j',
                 'kotlinx-coroutines-core-jvm', 'gson', 'okhttp-jvm', 'okio-jvm']:
        matches = sorted(CACHE.glob(f'*/{name}/**/*.jar'))
        if not matches:
            # Kotlin 2.3 no longer needs trove; Android OkHttp uses transformed AAR below.
            if name in ('trove4j', 'okhttp-jvm'):
                continue
            raise RuntimeError(f'Missing JVM fixture dependency: {name}')
        preferred = [p for p in matches if '/2.3.20/' in str(p) or '/1.10.2/' in str(p)]
        jars.append((preferred or matches)[-1])
    jars += list((Path.home() / '.gradle/caches/8.13/transforms').glob(
        '*/transformed/jetified-okhttp-release-runtime.jar'))[:1]
    cp = os.pathsep.join(map(str, jars))
    classes = ROOT / 'app/build/tmp/kotlin-classes/release'
    android = Path(os.environ.get('ANDROID_HOME', '/opt/android-sdk')) / 'platforms/android-36/android.jar'
    for required in [classes / 'com/mina/legadostudio/data/db/StudioDao.class', android]:
        if not required.exists():
            raise RuntimeError(f'Missing JVM fixture prerequisite: {required}')
    base = ROOT / 'app/src/main/java/com/mina/legadostudio'
    sources = [base / 'web/InteractiveCaptureHost.kt',
               base / 'export/CaptureSessionExporter.kt',
               Path(__file__).with_name('CaptureExportCheck.kt')]
    with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'), prefix='vue-capture-export-') as out:
        subprocess.run([
            'java', '-Xmx384m', '-XX:ActiveProcessorCount=2', '-cp', cp,
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
            '-jvm-target', '17', f'-Xfriend-paths={classes}',
            '-classpath', f'{classes}:{android}:{cp}', '-d', out, *map(str, sources),
        ], check=True, timeout=90, stdout=sys.stderr)
        subprocess.run([
            'java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp',
            f'{out}:{classes}:{android}:{cp}',
            'com.mina.legadostudio.web.CaptureExportCheckKt', out,
        ], check=True, timeout=20, stdout=sys.stderr)
        # The probe writes this only after comparing actual file and host metadata.
        print((Path(out) / 'receipt.json').read_text())


if __name__ == '__main__':
    main()
