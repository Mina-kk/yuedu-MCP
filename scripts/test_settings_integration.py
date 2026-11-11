"""Source wiring gates; not Android runtime permission evidence."""
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/mina/legadostudio'
class Integration(unittest.TestCase):
    def test_fixed_permission_routes_and_readback(self):
        protocol = (JAVA / 'web/WebBridgeProtocol.kt').read_text()
        bridge = (JAVA / 'web/StudioWebBridge.kt').read_text()
        for route in ['notification.request', 'notification.openSettings', 'notification.openChannel', 'overlay.openSettings', 'overlay.setEnabled', 'device.openBatterySettings', 'device.openAutoStartSettings']:
            self.assertIn('"'+route+'"', protocol)
        for field in ['overlayEnabled', 'channelCreated', 'mcpChannelEnabled']:
            self.assertIn('"'+field+'"', bridge)
        self.assertIn('authorizeOrigin(origin, isMainFrame)', bridge)
        self.assertIn('host.requestNotificationPermission()', bridge)
        self.assertIn('host.setOverlayEnabled(enabled)', bridge)
    def test_exit_stop_is_explicit_and_reader_clear_reads_back(self):
        bridge = (JAVA / 'web/StudioWebBridge.kt').read_text()
        self.assertIn('strictBoolean(params, "stopService", false)', bridge)
        self.assertIn('if (stopService) McpService.stop', bridge)
        self.assertIn('M_READER_CLEAR ->', bridge)
        self.assertIn('store.clear()', bridge)
        self.assertIn('store.load() == null && !store.hasToken()', bridge)
if __name__ == '__main__': unittest.main()
