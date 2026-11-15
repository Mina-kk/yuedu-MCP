"""Source-only Android wiring checks; real URI delivery is a device acceptance gate."""
import pathlib
import unittest

SOURCE = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/java/com/mina/legadostudio/web/SourceManagementModule.kt'

class SourceDeliveryShareContract(unittest.TestCase):
    def test_social_app_mime_and_uri_grant(self):
        text = SOURCE.read_text()
        branch = text.split('"share" -> {', 1)[1].split('"save" ->', 1)[0]
        self.assertIn('setType("text/plain")', branch)
        self.assertIn('Intent.EXTRA_STREAM', branch)
        self.assertIn('Intent.EXTRA_SUBJECT', branch)
        self.assertIn('ClipData.newUri', branch)
        self.assertIn('Intent.FLAG_GRANT_READ_URI_PERMISSION', branch)
        self.assertGreater(branch.index('activity.startActivity'), branch.index('withContext(Dispatchers.Main)'))

if __name__ == '__main__':
    unittest.main()
