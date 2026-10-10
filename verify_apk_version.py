#!/usr/bin/env python3
"""Compare embedded APK versionName against the canonical VERSION file."""
from __future__ import annotations
import argparse
import os
import shutil
import subprocess
from pathlib import Path
from release_contract import ReleaseContractError, parse_apk_version_from_badging, read_canonical_version, validate_artifact_version

def find_aapt() -> str:
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if android_home:
        candidates = sorted((Path(android_home) / "build-tools").glob("*/aapt"), reverse=True)
        if candidates:
            return str(candidates[0])
    tool = shutil.which("aapt")
    if tool:
        return tool
    raise ReleaseContractError("Android aapt is unavailable; cannot verify APK metadata.")

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--variant", required=True, choices=("debug", "release"))
    parser.add_argument("--aapt")
    args = parser.parse_args()
    if not args.apk.is_file():
        raise ReleaseContractError(f"APK artifact is missing: {args.apk}")
    version = read_canonical_version(Path(__file__).resolve().parent / "VERSION")
    tool = args.aapt or find_aapt()
    result = subprocess.run([tool, "dump", "badging", str(args.apk)], check=True, capture_output=True, text=True)
    actual = parse_apk_version_from_badging(result.stdout)
    validate_artifact_version(version, actual, args.variant)
    print(f"APK VERSION CONTRACT: PASS ({args.variant}, {actual})")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
