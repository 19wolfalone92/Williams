#!/usr/bin/env python3
"""Read-only, fail-closed Android evidence collector for Williams Bot 2.1.

Does not install APKs, change app data, clear logcat, call exchange APIs, or submit orders.
Requires explicit --serial when multiple Android devices are connected.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

EXPECTED_PACKAGE = "com.williamsbot.debug"
EXPECTED_VERSION = "4.25.0-debug"
EXPECTED_VERSION_CODE = "1531"
EXPECTED_APK_SHA256 = "4823d5a414afa3343be65e4bdb15c030ad86d29ffb487bcda3378fe270ebccd5"

# Conservative redaction: redact values, not just labels. If a secret may remain,
# the report is marked BLOCKED and raw logs are not copied into the public package.
SECRET_PATTERNS = [
    re.compile(r"(?i)(api[_-]?key|api[_-]?secret|access[_-]?token|refresh[_-]?token|authorization|signature|password|passwd)\s*([=:])\s*([^\s,;]+)"),
    re.compile(r"(?i)(Bearer\s+)[A-Za-z0-9._~+/-]+=*"),
    re.compile(r"(?i)(X-MBX-APIKEY\s*:\s*)[^\s]+"),
]
POTENTIAL_SECRET = re.compile(r"(?i)(api[_-]?key|api[_-]?secret|access[_-]?token|refresh[_-]?token|authorization|signature|password|passwd|bearer\s|x-mbx-apikey)")

def redact(text: str) -> tuple[str, bool]:
    changed = False
    for pattern in SECRET_PATTERNS:
        text, n = pattern.subn(lambda m: (m.group(1) + (m.group(2) if len(m.groups()) > 1 and m.group(2) else "") + "[REDACTED]") if m.lastindex and m.lastindex >= 2 else "[REDACTED]", text)
        changed = changed or n > 0
    # Fail closed if a sensitive marker remains after known-value redaction.
    unresolved = bool(POTENTIAL_SECRET.search(text))
    return text, unresolved

def run(cmd: Sequence[str], timeout: int = 20) -> subprocess.CompletedProcess[str]:
    return subprocess.run(list(cmd), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout, check=False)

def require_ok(result: subprocess.CompletedProcess[str], label: str) -> str:
    if result.returncode != 0:
        raise RuntimeError(f"{label} failed (exit={result.returncode}): {result.stdout[-1500:]}")
    return result.stdout

def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def parse_devices(output: str) -> list[str]:
    devices = []
    for line in output.splitlines()[1:]:
        fields = line.strip().split()
        if len(fields) >= 2 and fields[1] == "device":
            devices.append(fields[0])
    return devices

def parse_package_metadata(dumpsys: str) -> dict[str, str | None]:
    version = re.search(r"\bversionName=([^\s]+)", dumpsys)
    code = re.search(r"\bversionCode=(\d+)", dumpsys)
    return {"versionName": version.group(1) if version else None,
            "versionCode": code.group(1) if code else None}

def collect(args: argparse.Namespace) -> int:
    if not shutil.which("adb"):
        print("BLOCKED: adb executable not found", file=sys.stderr)
        return 2
    out = Path(args.output).resolve()
    out.mkdir(parents=True, exist_ok=False)
    start = datetime.now(timezone.utc).isoformat()
    versions = {}
    vr = run(["adb", "version"])
    versions["adb"] = vr.stdout.splitlines()[0] if vr.returncode == 0 and vr.stdout else "UNKNOWN"
    devices_out = require_ok(run(["adb", "devices"]), "adb devices")
    devices = parse_devices(devices_out)
    if args.serial:
        if args.serial not in devices:
            print("BLOCKED: selected serial is not a connected device in state 'device'", file=sys.stderr)
            return 2
        serial = args.serial
    elif len(devices) == 1:
        serial = devices[0]
    elif len(devices) == 0:
        print("BLOCKED: no authorized Android device connected", file=sys.stderr)
        return 2
    else:
        print("BLOCKED: multiple devices; rerun with explicit --serial", file=sys.stderr)
        return 2

    def adb(*parts: str, timeout: int = 20) -> str:
        return require_ok(run(["adb", "-s", serial, *parts], timeout=timeout), "adb " + " ".join(parts[:2]))

    device = {
        "serial": serial,
        "model": adb("shell", "getprop", "ro.product.model").strip(),
        "android_release": adb("shell", "getprop", "ro.build.version.release").strip(),
        "sdk": adb("shell", "getprop", "ro.build.version.sdk").strip(),
        "abi": adb("shell", "getprop", "ro.product.cpu.abilist").strip(),
        "meminfo": adb("shell", "cat", "/proc/meminfo"),
        "data_filesystem": adb("shell", "df", "-h", "/data"),
    }
    package_result = run(["adb", "-s", serial, "shell", "dumpsys", "package", EXPECTED_PACKAGE])
    package_text = require_ok(package_result, "dumpsys package")
    metadata = parse_package_metadata(package_text)
    if metadata["versionName"] != EXPECTED_VERSION or metadata["versionCode"] != EXPECTED_VERSION_CODE:
        print(f"BLOCKED: installed package metadata mismatch: {metadata}", file=sys.stderr)
        return 3

    # Attempt to hash the installed base APK itself. Do not substitute the downloaded artifact hash.
    paths_text = adb("shell", "pm", "path", EXPECTED_PACKAGE).strip()
    base_path = next((line.split(":", 1)[1].strip() for line in paths_text.splitlines() if line.startswith("package:")), None)
    installed_apk = out / "installed-base.apk"
    installed_hash = None
    installed_hash_status = "UNAVAILABLE"
    if base_path:
        pulled = run(["adb", "-s", serial, "pull", base_path, str(installed_apk)], timeout=60)
        if pulled.returncode == 0 and installed_apk.is_file():
            installed_hash = sha256_file(installed_apk)
            installed_hash_status = "MATCH" if installed_hash == EXPECTED_APK_SHA256 else "MISMATCH"
        else:
            installed_apk.unlink(missing_ok=True)

    # Preserve pre-existing device logs; do not clear logcat.
    launch = run(["adb", "-s", serial, "shell", "monkey", "-p", EXPECTED_PACKAGE, "1"], timeout=30)
    (out / "launch-result.txt").write_text(launch.stdout, encoding="utf-8")
    log_result = run(["adb", "-s", serial, "logcat", "-d", "-v", "threadtime", "-t", "2500"], timeout=45)
    log_text = log_result.stdout if log_result.returncode == 0 else ""
    safe_log, unresolved = redact(log_text)
    if not unresolved:
        (out / "logcat-redacted.txt").write_text(safe_log, encoding="utf-8")
    else:
        # Never publish uncertain logs. Keep only a non-sensitive summary.
        (out / "logcat-export-status.txt").write_text(
            "BLOCKED: potential sensitive marker remains after redaction; raw log not exported.\n",
            encoding="utf-8")

    snapshot = {
        "schema": "williams-device-evidence-v1",
        "collected_at_utc": start,
        "repository": "19wolfalone92/Williams",
        "expected_artifact": {"package": EXPECTED_PACKAGE, "versionName": EXPECTED_VERSION,
                              "versionCode": EXPECTED_VERSION_CODE, "apk_sha256": EXPECTED_APK_SHA256},
        "device": device,
        "installed_package": {"package": EXPECTED_PACKAGE, **metadata,
                              "installed_base_apk_sha256": installed_hash,
                              "installed_hash_status": installed_hash_status},
        "launch": {"exit_code": launch.returncode, "output": launch.stdout[-2000:]},
        "logcat": {"exit_code": log_result.returncode, "lines_requested": 2500,
                   "export_status": "REDACTED" if not unresolved and log_result.returncode == 0 else "BLOCKED_SENSITIVE_OR_UNAVAILABLE"},
        "runtime_snapshot": {"status": "NOT EXPOSED BY GENERIC ADB COLLECTOR",
                             "native_engine": "UNVERIFIED", "sqlite": "UNVERIFIED",
                             "market_data": "UNVERIFIED", "scanner": "UNVERIFIED",
                             "execution_gate": "UNVERIFIED", "recovery_gate": "UNVERIFIED"},
        "tool_versions": versions,
        "limitations": ["Generic ADB evidence does not prove NativeEngine, SQLite, market-data, Scanner or Gate correctness.",
                        "Installed APK hash is only verified if the installed base APK could be pulled and hashed.",
                        "No exchange requests or trading mutations are issued by this collector."]
    }
    (out / "device-snapshot.json").write_text(json.dumps(snapshot, indent=2) + "\n", encoding="utf-8")

    # Hash all collected files except the manifest itself.
    entries = []
    for path in sorted(p for p in out.rglob("*") if p.is_file() and p.name != "manifest.json"):
        entries.append({"path": str(path.relative_to(out)), "size_bytes": path.stat().st_size,
                        "sha256": sha256_file(path)})
    manifest = {"schema": "williams-evidence-manifest-v1", "collected_at_utc": start,
                "files": entries, "sensitive_log_markers_unresolved": unresolved}
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(out), "device_serial": serial,
                      "installed_hash_status": installed_hash_status,
                      "log_export_status": snapshot["logcat"]["export_status"],
                      "manifest_files": len(entries)}, indent=2))
    return 0 if launch.returncode == 0 and installed_hash_status == "MATCH" and not unresolved else 4

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Explicit adb serial; required when multiple devices are connected")
    parser.add_argument("--output", required=True, help="New, non-existing output directory")
    args = parser.parse_args()
    try:
        return collect(args)
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
