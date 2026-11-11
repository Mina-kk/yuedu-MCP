"""Source boundary gates only; not Android/WebView or DAO runtime evidence."""
import pathlib
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]
HOST = ROOT / 'app/src/main/java/com/mina/legadostudio/web/InteractiveCaptureHost.kt'

class InteractiveCaptureHostContract(unittest.TestCase):
    def test_recovery_probe_has_no_static_jdk_internal_api_dependency(self):
        # Android's Gradle compilation exposes Android APIs, not sun.misc types.
        # The standalone JVM probe must remain compilable on that same path.
        probe = (ROOT / 'app/src/test/java/com/mina/legadostudio/web/InteractiveCaptureRecoveryCheck.kt').read_text()
        self.assertNotRegex(probe, r'\bimport\s+sun\.')
        self.assertNotRegex(probe, r'\bsun\.misc\.Unsafe\s*::')
        self.assertNotRegex(probe, r'\bas\s+sun\.misc\.Unsafe\b')

    def test_host_reuses_capture_and_network_boundaries(self):
        self.assertTrue(HOST.exists(), 'independent interactive host is missing')
        s = HOST.read_text()
        for contract in ['WebProxyBoundary.acquire', 'WebProxyBoundary.release',
                         'engine.newSession(url, interactive = true)', 'engine.clientFor',
                         'engine.finishSession', 'engine.isCaptureSessionActive',
                         'WebBridgePolicy.verificationResourceDecision',
                         'WebBridgePolicy.isVerificationUrlAllowed',
                         'CaptureSessionExporter.writeSessionJson', 'CaptureActivity.deleteIfInactive',
                         'handler.cancel()', 'MIXED_CONTENT_NEVER_ALLOW']:
            self.assertIn(contract, s)
        for forbidden in ['addJavascriptInterface(', 'addWebMessageListener(', '.proceed()', 'engine.run(']:
            self.assertNotIn(forbidden, s)

if __name__ == '__main__': unittest.main()
