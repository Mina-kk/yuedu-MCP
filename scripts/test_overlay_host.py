"""Lightweight real Kotlin tests; no Gradle or emulator."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/mina/legadostudio'

class OverlayHostTest(unittest.TestCase):
    def test_original_ball_style(self):
        page = (SRC / 'verification/VerificationOverlayPage.kt').read_text()
        self.assertIn('#007AFF', page)
        self.assertIn('#FFFFFF', page)
        self.assertIn('#E65100', page)
        self.assertIn("script-src 'none'", page)

    def test_permission_host_contract(self):
        host = (SRC / 'MainActivity.kt').read_text()
        self.assertIn('ActivityResultContracts.RequestPermission()', host)
        self.assertIn('fun requestNotificationPermission()', host)
        self.assertIn('fun openMcpNotificationChannel()', host)
        self.assertIn('fun setOverlayEnabled(enabled: Boolean)', host)
        self.assertIn('notificationRequest?.cancel()', host)

    def test_real_geometry(self):
        source = SRC / 'verification/OverlayGeometry.kt'
        self.assertTrue(source.exists(), 'missing pure overlay geometry')
        code = '''import com.mina.legadostudio.verification.*
fun main() {
 val s = OverlayGeometry()
 s.resize(400, 800, 48, 48)
 s.x = 0; s.y = 900; s.release()
 check(s.side == -1 && s.y == 752)
 s.collapse(); check(s.hidden && s.width == 24 && s.x == 0)
 check(!s.tap()); check(!s.hidden && s.width == 48)
 check(s.tap())
 s.x = 352; s.release(); s.collapse()
 check(s.side == 1 && s.x == 376)
 s.resize(200, 100, 48, 48)
 check(s.x == 176 && s.y == 52)
 s.expand(); s.move(80, -100); s.release()
 check(s.side == 0 && s.y == 0 && !s.hidden)
 s.collapse(); check(!s.hidden)
 val page = VerificationOverlayPage.normalPage(-1)
 check(page.contains("translateX(-50%)"))
 check(page.contains("#007AFF") && page.contains("#FFFFFF"))
 val alert = VerificationOverlayPage.alertPage("<script>evil</script>", "<img>")
 check(!alert.contains("<script>") && !alert.contains("<img>"))
 check(alert.contains("#E65100") && alert.contains("script-src 'none'"))
 check(!VerificationOverlayPage.isAllowedNavigation("https://evil.example"))
 println("geometry + real page security/style PASS")
}'''
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d); (p / 'Test.kt').write_text(code)
            libs = pathlib.Path('/opt/gradle-8.13/lib')
            std = libs / 'kotlin-stdlib-2.0.21.jar'
            subprocess.run(['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', str(libs / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', str(std), '-d', str(p / 'out'), str(source), str(SRC / 'verification/VerificationOverlayPage.kt'), str(p / 'Test.kt')], check=True)
            subprocess.run(['java', '-cp', f'{p / "out"}:{std}', 'TestKt'], check=True)

if __name__ == '__main__':
    unittest.main()
