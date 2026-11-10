"""Narrow wiring gates; real minified APK replies still require device QA."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class VerificationWireContractTest(unittest.TestCase):
    def test_missing_session_uses_not_found_in_actual_bridge_handler(self):
        source = (ROOT / 'app/src/main/java/com/mina/legadostudio/web/StudioWebBridge.kt').read_text()
        body = source[source.index('private fun handle('):source.index('private suspend fun dispatch(')]
        marker = 'catch (missing: VerificationSessionNotFoundException)'
        self.assertIn(marker, body)
        clause = body.split(marker, 1)[1].split('catch (', 1)[0]
        self.assertIn('WebBridgeProtocol.ERR_NOT_FOUND', clause)
        self.assertNotIn('missing.sessionId', clause)

    def test_verification_completion_uses_explicit_wire_keys(self):
        source = (ROOT / 'app/src/main/java/com/mina/legadostudio/web/StudioWebBridge.kt').read_text()
        body = source[source.index('private suspend fun verificationComplete('):source.index('private suspend fun verificationClear(')]
        self.assertIn('WebBridgePolicy.verificationSessionPayload(completed.toRow())', body)
        self.assertNotIn('gson.toJson(', body)

if __name__ == '__main__':
    unittest.main()
