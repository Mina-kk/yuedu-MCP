"""The native source save callback must not re-enter the JS text gate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "app/src/main/java/com/mina/legadostudio/web"


class SourceFileExportContractTest(unittest.TestCase):
    def test_source_save_uses_internal_callback_while_js_keeps_text_gate(self):
        bridge = (WEB / "StudioWebBridge.kt").read_text()
        self.assertTrue("SourceManagementModule(activity, ::sourceFileExport)" in bridge, "source save still enters generic text gate")
        generic = bridge.split("private suspend fun fileExport(", 1)[1].split("private suspend fun fileImport(", 1)[0]
        self.assertIn("WebBridgeProtocol.checkExportText(text)", generic)
        self.assertIn("host.export(SafRequest.Export", generic)
        self.assertIn('WebBridgeProtocol.M_FILE_EXPORT -> fileExport(params)', bridge)
        self.assertNotIn('"sourceFileExport" ->', bridge)
        callback = bridge.split('private suspend fun sourceFileExport(', 1)[1].split('private val logManagement', 1)[0]
        self.assertIn('SourceFileExport(activity.cacheDir, host).export(params)', callback)
        exporter = (WEB / 'SourceFileExport.kt').read_text()
        self.assertIn('host.exportFile(file, name, "application/json")', exporter)
        self.assertIn('File.createTempFile(', exporter)
        self.assertNotIn('checkExportText', exporter)
        self.assertNotIn('deleteRecursively', exporter)
        self.assertNotIn('toByteArray', exporter)
        saf = (WEB / 'SafLauncher.kt').read_text().split('suspend fun exportFile(', 1)[1].split('/** @return', 1)[0]
        self.assertIn('DigestInputStream', saf)
        self.assertIn('expected.digest().contentEquals(actual.digest())', saf)


if __name__ == "__main__":
    unittest.main()
