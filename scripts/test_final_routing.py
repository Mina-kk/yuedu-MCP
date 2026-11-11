"""Host wiring guards, not proof of Android clipboard/chooser effects."""
from pathlib import Path
import re
import unittest
ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / 'app/src/main/java/com/mina/legadostudio/web'

class FinalRoutingTest(unittest.TestCase):
    def test_header_copy_native_sink_precedes_endpoint_lookup(self):
        b = (WEB / 'StudioWebBridge.kt').read_text()
        self.assertIn('addProperty("connectionHeaderCopy", true)', b)
        body = b.split('private suspend fun connectionCopy', 1)[1].split('// --- tools.list', 1)[0]
        self.assertLess(body.index('copyConnectionHeader('), body.index('McpAccess.lanEndpoints'))
        self.assertIn('writeClipboard("MCP 请求头", it)', body)
        self.assertIn('!config.tokenRequired', body)
        self.assertIn('EXTRA_IS_SENSITIVE', body)
        self.assertIn('clipboard.setPrimaryClip(clip)', body)
        self.assertIn('复制失败，请重试', body)
    def test_log_share_wired_to_same_exporter(self):
        b = (WEB / 'StudioWebBridge.kt').read_text()
        p = (WEB / 'WebBridgeProtocol.kt').read_text()
        self.assertIn('"logs.share"', p)
        self.assertIn('LogShareLauncher(activity, logManagement)', b)
        self.assertIn('"logs.share" -> logShareLauncher.share(params)', b)
        self.assertIn('addProperty("logShare", true)', b)
    def test_logs_export_streams_native_file_without_js_body(self):
        b = (WEB / 'StudioWebBridge.kt').read_text()
        a = (ROOT / 'app/src/main/java/com/mina/legadostudio/MainActivity.kt').read_text()
        self.assertIn('logManagement.export(params) { file, name, mime ->', b)
        self.assertIn('host.exportFile(file, name, mime)', b)
        self.assertIn('override suspend fun exportFile(file: java.io.File, name: String, mime: String)', a)
        self.assertIn('saf.exportFile(file, name, mime)', a)
        e = (WEB / 'LogManagementBridge.kt').read_text()
        self.assertNotIn('addProperty("text"', e)
        self.assertNotIn('StringWriter', e)
        self.assertIn('CaptureActivity.deleteIfInactive(context)', e)
    def test_every_allowlisted_method_has_dispatch_branch(self):
        p = (WEB / 'WebBridgeProtocol.kt').read_text()
        constants = dict(re.findall(r'const val (M_\w+) = "([^"]+)"', p))
        methods = p.split('val METHODS:', 1)[1].split('/**', 1)[0]
        expected = set(re.findall(r'"([a-z][^"\s]+)"', methods))
        expected.update(constants[n] for n in re.findall(r'\bM_\w+', methods))
        b = (WEB / 'StudioWebBridge.kt').read_text().split('private suspend fun dispatch', 1)[1].split('private fun strictBoolean', 1)[0]
        labels = '\n'.join(line.split('->', 1)[0] for line in b.splitlines() if line.lstrip().startswith('"'))
        actual = set(re.findall(r'"([^"]+)"', labels))
        actual.update(constants[n] for n in re.findall(r'WebBridgeProtocol\.(M_\w+)', b))
        self.assertEqual(expected, actual)

if __name__ == '__main__': unittest.main()
