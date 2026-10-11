import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("collect_android_evidence.py")
SPEC = importlib.util.spec_from_file_location("collect_android_evidence", MODULE_PATH)
collector = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(collector)


class DeviceEvidenceCollectorTests(unittest.TestCase):
    def test_adb_devices_empty_is_zero(self):
        self.assertEqual(collector.parse_devices("List of devices attached\n\n"), [])

    def test_adb_unauthorized_is_not_accepted(self):
        self.assertEqual(collector.parse_devices("List of devices attached\nABC unauthorized\n"), [])

    def test_adb_offline_is_not_accepted(self):
        self.assertEqual(collector.parse_devices("List of devices attached\nABC offline\n"), [])

    def test_single_device_is_parsed(self):
        self.assertEqual(collector.parse_devices("List of devices attached\nABC123 device\n"), ["ABC123"])

    def test_multiple_devices_are_all_reported(self):
        self.assertEqual(collector.parse_devices("List of devices attached\nA device\nB device\n"), ["A", "B"])

    def test_expected_package_version_parsed(self):
        self.assertEqual(collector.parse_package_metadata("versionCode=1531 minSdk=26\n versionName=4.25.0-debug"),
                         {"versionName": "4.25.0-debug", "versionCode": "1531"})

    def test_wrong_version_detectable(self):
        self.assertNotEqual(collector.parse_package_metadata("versionCode=1531 versionName=4.24.0-debug")["versionName"],
                            collector.EXPECTED_VERSION)

    def test_wrong_version_code_detectable(self):
        self.assertNotEqual(collector.parse_package_metadata("versionCode=1 versionName=4.25.0-debug")["versionCode"],
                            collector.EXPECTED_VERSION_CODE)

    def test_missing_version_fails_closed(self):
        self.assertEqual(collector.parse_package_metadata("Package [com.williamsbot.debug]")["versionName"], None)

    def test_missing_version_code_fails_closed(self):
        self.assertEqual(collector.parse_package_metadata("versionName=4.25.0-debug")["versionCode"], None)

    def test_expected_apk_sha_is_fixed_control(self):
        self.assertEqual(len(collector.EXPECTED_APK_SHA256), 64)
        self.assertTrue(all(c in "0123456789abcdef" for c in collector.EXPECTED_APK_SHA256))

    def test_secret_assignment_is_redacted(self):
        text, unresolved = collector.redact("api_secret=abc123")
        self.assertNotIn("abc123", text)
        self.assertFalse(unresolved)

    def test_bearer_token_is_redacted(self):
        text, unresolved = collector.redact("Authorization: Bearer abc.def.ghi")
        self.assertNotIn("abc.def.ghi", text)
        self.assertFalse(unresolved)

    def test_exchange_header_is_redacted(self):
        text, unresolved = collector.redact("X-MBX-APIKEY: abc123")
        self.assertNotIn("abc123", text)
        self.assertFalse(unresolved)

    def test_unrecognized_secret_marker_blocks_export(self):
        text, unresolved = collector.redact("credential password was printed in a custom format")
        self.assertTrue(unresolved)

    def test_plain_log_is_not_flagged(self):
        text, unresolved = collector.redact("ActivityManager: displayed com.williamsbot.debug")
        self.assertFalse(unresolved)
        self.assertIn("com.williamsbot.debug", text)

    def test_hash_is_deterministic(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sample"
            path.write_bytes(b"williams-test")
            first = collector.sha256_file(path)
            second = collector.sha256_file(path)
            self.assertEqual(first, second)
            self.assertEqual(len(first), 64)


if __name__ == "__main__":
    unittest.main()
