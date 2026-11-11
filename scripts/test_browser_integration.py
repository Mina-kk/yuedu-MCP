"""Shared-host wiring gates; not Android runtime evidence."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/mina/legadostudio'
class BrowserIntegrationTest(unittest.TestCase):
    def test_auto_completion_is_conditional_inside_existing_mutex(self):
        text = (JAVA / 'verification/VerificationCoordinator.kt').read_text()
        body = text.split('suspend fun completeAutomatically', 1)[-1].split('/** Explicit user repair', 1)[0]
        self.assertIn('suspend fun completeAutomatically', text)
        for token in ['stateLock.withLock', 'old.status != "WAITING"', 'old.jobId == "manual"', 'old.kind != "webview"', 'old.url != expectedUrl', 'stillCurrent()', 'generation', 'cookies.captureFromWebView']:
            self.assertIn(token, body)
    def test_capture_host_is_connected(self):
        text = (JAVA / 'MainActivity.kt').read_text()
        for token in ['InteractiveCaptureHost(', 'captureHost?.dispose()', 'exportFile(file', 'setCaptureViewport', 'completeAutomatically', 'autoCompleteEligible =']:
            self.assertIn(token, text)
        protocol = (JAVA / 'web/WebBridgeProtocol.kt').read_text()
        for action in ['open','navigate','back','forward','reload','state','transactions','detail','finish','close','export','viewport']:
            self.assertIn('"capture.browser.' + action + '"', protocol)
        self.assertIn('InteractiveCaptureWorkbench', (ROOT / 'web-ui/src/App.vue').read_text())
    def test_management_routes_are_wired(self):
        protocol = (JAVA / 'web/WebBridgeProtocol.kt').read_text()
        bridge = (JAVA / 'web/StudioWebBridge.kt').read_text()
        for method in ['skills.read', 'skills.setEnabled', 'skills.import', 'skills.export', 'sources.analyze', 'sources.deliver', 'sources.delete', 'sources.checkStart', 'sources.checkStatus', 'sources.checkCancel', 'logs.list', 'logs.dayCount', 'logs.deleteDay', 'logs.export']:
            self.assertIn('"' + method + '"', protocol)
            self.assertIn('"' + method + '"', bridge)
        app = (ROOT / 'web-ui/src/App.vue').read_text()
        self.assertIn('@open-capture-browser="store.openCaptureWorkbench"', app)
        host = (JAVA / 'MainActivity.kt').read_text()
        self.assertIn('skillSaf.release()', host)
        self.assertIn('sourceManagement.close()', bridge)
if __name__ == '__main__': unittest.main()
