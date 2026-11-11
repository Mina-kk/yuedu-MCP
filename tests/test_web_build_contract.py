"""Build integration invariants for the Rust/Vue APK, not runtime QA."""
from pathlib import Path
import json
import unittest

ROOT = Path(__file__).resolve().parents[1]

class WebBuildContractTest(unittest.TestCase):
    def test_compose_dependencies_are_not_packaged(self):
        app = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertNotIn('org.jetbrains.kotlin.plugin.compose', app)
        self.assertNotIn('androidx.compose', app)
        self.assertNotIn('activity-compose', app)
        self.assertNotIn('com.google.android.material', app)

    def test_web_assets_cannot_be_silently_omitted(self):
        app = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('syncWebUiAssets', app)
        self.assertIn('web-ui/dist', app)
        self.assertIn('index.html', app)
        self.assertIn('wasm', app)

    def test_clean_build_entrypoint_and_ci_include_web_pipeline(self):
        build_script = ROOT / 'scripts/build-web.sh'
        self.assertTrue(build_script.is_file(), 'missing reproducible web build entrypoint')
        text = build_script.read_text()
        for command in ['ui-core/build-web.sh', 'npm ci', 'npm test', 'npm run build', 'ui-core/smoke.mjs']:
            self.assertIn(command, text)
        ci = (ROOT / '.github/workflows/ci.yml').read_text()
        for command in ['setup-node', 'wasm32-unknown-unknown', 'wasm-bindgen-cli', 'scripts/build-web.sh', "unittest discover -s scripts"]:
            self.assertIn(command, ci)

    def test_remote_ci_is_branch_scoped_and_hosted(self):
        ci = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('refactor/rust-vue-reader-semantics', ci)
        self.assertIn('contents: read', ci)
        self.assertNotIn('self-hosted', ci)
        self.assertIn('cancel-in-progress: true', ci)
        self.assertIn('compileDebugKotlin', ci)
        self.assertIn('if-no-files-found: error', ci)

    def test_upgrade_preserves_identity_and_database(self):
        app = (ROOT / 'app/build.gradle.kts').read_text()
        db = (ROOT / 'app/src/main/java/com/mina/legadostudio/data/db/StudioDatabase.kt').read_text()
        self.assertIn('applicationId = "com.mina.legadostudio"', app)
        self.assertIn('versionCode = 207', app)
        self.assertIn('versionName = "2.0.6"', app)
        self.assertIn('"studio-v1.db"', db)
        self.assertNotIn('.fallbackToDestructiveMigration', db)

    def test_release_version_and_manual_verification_scope_are_documented(self):
        package = json.loads((ROOT / 'web-ui/package.json').read_text())
        lock = json.loads((ROOT / 'web-ui/package-lock.json').read_text())
        self.assertEqual(package['version'], '2.0.6')
        self.assertEqual(lock['version'], package['version'])
        self.assertEqual(lock['packages']['']['version'], package['version'])
        for name in ['README.md', 'BUILDING.md']:
            text = (ROOT / name).read_text()
            self.assertIn('2.0.6', text)
            self.assertIn('versionCode 207', text)
            self.assertIn('验证由用户手动完成', text)
            self.assertIn('不提供自动完成', text)

    def test_window_theme_does_not_require_material_widgets(self):
        for path in ['app/src/main/res/values/themes.xml', 'app/src/main/res/values-v27/themes.xml']:
            self.assertNotIn('Theme.Material3', (ROOT / path).read_text())

if __name__ == '__main__':
    unittest.main()
