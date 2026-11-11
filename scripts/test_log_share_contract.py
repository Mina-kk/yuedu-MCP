"""Lightweight source guards; Android chooser execution still needs device QA."""
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

class LogShareContract(unittest.TestCase):
    def test_non_context_http_summary_has_timestamp(self):
        source = (ROOT / 'app/src/main/java/com/mina/legadostudio/mcp/McpLogTools.kt').read_text()
        mapping = source.split('val mapped = items.map { s ->', 1)[1].split('ok(app.gson', 1)[0]
        self.assertIn('"createdAt" to s.createdAt', mapping)

    def test_share_uses_existing_export_and_file_provider(self):
        path = ROOT / 'app/src/main/java/com/mina/legadostudio/web/LogShareLauncher.kt'
        self.assertTrue(path.exists(), 'Native log share launcher is missing')
        source = path.read_text()
        for required in ['exporter.prepare(params)', 'data.file.inputStream()', 'data.receipt()', 'Dispatchers.IO', 'Dispatchers.Main', 'FileProvider.getUriForFile', '"${activity.packageName}.files"', '"exports/log-share"', 'Intent.ACTION_SEND', 'Intent.EXTRA_STREAM', 'Intent.FLAG_GRANT_READ_URI_PERMISSION', 'ClipData.newRawUri', 'Intent.createChooser', 'addProperty("launched", true)']:
            self.assertIn(required, source)
        self.assertNotIn('Intent.EXTRA_TEXT', source)
        self.assertNotIn('addProperty("text"', source)
        self.assertNotIn('addProperty("saved"', source)
        self.assertIn('file.delete()', source)

if __name__ == '__main__':
    unittest.main()
