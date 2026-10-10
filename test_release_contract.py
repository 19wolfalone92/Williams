from __future__ import annotations
import tempfile
import unittest
from pathlib import Path
from release_contract import (
    ReleaseContractError, assert_version_alignment, execution_start_blocker,
    parse_apk_version_from_badging, read_canonical_version,
    validate_android_gradle_version_source, validate_android_ui_contract, validate_gradle_wrapper_version,
    validate_artifact_version, validate_release_contract, _validate_workflows,
)

ROOT = Path(__file__).resolve().parent

class ReleaseContractTests(unittest.TestCase):
    def test_backend_android_version_mismatch_is_rejected(self):
        with self.assertRaises(ReleaseContractError):
            assert_version_alignment("4.25.0", "4.24.0", "4.25.0")

    def test_missing_version_source_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ReleaseContractError):
                read_canonical_version(Path(d) / "VERSION")

    def test_stale_android_interface_version_is_rejected(self):
        with self.assertRaises(ReleaseContractError):
            validate_android_ui_contract('Text("WILLIAMS COCKPIT 4.24.0")')

    def test_apk_artifact_version_must_match_sources(self):
        actual = parse_apk_version_from_badging("package: name='com.williamsbot' versionCode='10' versionName='4.24.0'")
        with self.assertRaises(ReleaseContractError):
            validate_artifact_version("4.25.0", actual, "release")
        with self.assertRaises(ReleaseContractError):
            validate_artifact_version("4.25.0", "4.24.0-debug", "debug")

    def test_wrong_testnet_mainnet_configuration_is_rejected(self):
        blocker = execution_start_blocker(
            {"TESTNET": "false", "ALLOW_LIVE": "false", "DRY_RUN": "true"},
            api_key="test-key", api_secret="test-secret", configured_testnet=False,
        )
        self.assertIsNotNone(blocker)
        self.assertIn("ALLOW_LIVE", blocker)

    def test_missing_mode_or_credentials_blocks_start(self):
        self.assertIsNotNone(execution_start_blocker({}, api_key="k", api_secret="s", configured_testnet=True))
        self.assertIsNotNone(execution_start_blocker({"TESTNET": "true"}, api_key="", api_secret="", configured_testnet=True))

    def test_release_gate_is_required_in_each_workflow(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for name in (
                ".github/workflows/android-apk.yml", ".github/workflows/campaign-ci.yml",
                ".github/workflows/python-ci.yml", ".github/workflows/testnet-readonly.yml",
            ):
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("name: test\njobs:\n  build:\n    steps:\n      - run: echo no-gate\n", encoding="utf-8")
            with self.assertRaises(ReleaseContractError):
                _validate_workflows(root)

    def test_android_gradle_rejects_a_hard_coded_version(self):
        with self.assertRaises(ReleaseContractError):
            validate_android_gradle_version_source('android { defaultConfig { versionName = "4.24.0" } }')

    def test_outdated_gradle_wrapper_is_rejected(self):
        with self.assertRaises(ReleaseContractError):
            validate_gradle_wrapper_version("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.5-bin.zip")

    def test_current_repository_release_contract_passes(self):
        self.assertEqual(validate_release_contract(ROOT), read_canonical_version(ROOT / "VERSION"))

if __name__ == "__main__":
    unittest.main()
