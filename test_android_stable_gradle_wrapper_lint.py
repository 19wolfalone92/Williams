"""Guard the stable, SHA-256-pinned Gradle wrapper against moving to nightly
distributions merely to silence an Android Lint future-version advisory.

2026-11-11: the official stable 8.x series is at 8.14.6; Lint
incorrectly demands 8.14.7, which exists as snapshots, not a stable release.
All OTHER lint issues remain fatal; never change abortOnError/warningsAsErrors.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def test_gradle_wrapper_stays_stable_checksum_pinned_without_snapshot_bypass():
    wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
    assert "gradle-8.14.6-bin.zip" in wrapper
    assert "distributions-snapshots" not in wrapper
    import re
    checksum = re.search(r"^distributionSha256Sum=([0-9a-f]{64})$", wrapper, re.M)
    assert checksum, "Do not use an unverified Gradle distribution"


def test_lint_keeps_all_safety_errors_fatal_except_unreleased_gradle_version_tip():
    cfg = (ROOT / "app/build.gradle.kts").read_text()
    assert "abortOnError = true" in cfg
    assert "warningsAsErrors = true" in cfg
    assert 'disable += "AndroidGradlePluginVersion"' in cfg
    assert "Revisit this exception when Gradle 8.14.7 is officially released" in cfg
    assert 'disable += "NewApi"' not in cfg
    assert 'disable += "MissingPermission"' not in cfg
