"""Exercise a clean, credential-free APK on a disposable Android emulator.

Never enables trading or enters credentials. This is UI/lifecycle acceptance,
not a Binance fill/STOP test or physical Samsung background certification.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import time
from xml.etree import ElementTree

PACKAGE = "com.williamsbot.debug"


def adb(*args: str, binary: bool = False) -> str | bytes:
    result = subprocess.run(["adb", *args], capture_output=True, timeout=45, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")


def adb_evidence(*args: str) -> str:
    """Bounded recovery for transient emulator ADB disconnects at teardown.

    Never mark a smoke run PASS when logcat/service evidence is still missing.
    Do not retry UI assertions or an actual detected app crash.
    """
    last_failure: Exception | None = None
    for attempt in range(3):
        try:
            return str(adb(*args))
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            last_failure = exc
            if attempt < 2:
                subprocess.run(["adb", "reconnect"], capture_output=True, timeout=12,
                               check=False)
                time.sleep(2)
    assert last_failure is not None
    raise last_failure


def run(apk: Path, output: Path) -> dict:
    if str(adb("shell", "getprop", "ro.kernel.qemu")).strip() != "1":
        raise RuntimeError("Disposable emulator required; never operate on a physical phone")
    output.mkdir(parents=True, exist_ok=True)
    expected = apk.with_name(apk.name + ".sha256")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if not expected.exists() or expected.read_text().split()[0] != digest:
        raise RuntimeError("APK does not match the build job checksum")
    report = {"apk_sha256": digest, "api_level": str(adb("shell", "getprop", "ro.build.version.sdk")).strip(),
              "checks": [], "trading_enabled": False, "signed_exchange_verified": False,
              "physical_android_verified": False}
    adb("install", "-r", "-g", str(apk))
    adb("logcat", "-c")
    component = PACKAGE + "/com.williamsbot.MainActivity"

    def launch() -> None:
        result = str(adb("shell", "am", "start", "-W", "-n", component))
        if "Status: ok" not in result:
            raise RuntimeError("Activity did not launch: " + result)

    def hierarchy() -> ElementTree.Element:
        adb("shell", "uiautomator", "dump", "/sdcard/williams-smoke.xml")
        return ElementTree.fromstring(str(adb("shell", "cat", "/sdcard/williams-smoke.xml")))

    def visible(label: str) -> bool:
        return any(n.get("text") == label or n.get("content-desc") == label for n in hierarchy().iter("node"))

    def tap(label: str) -> None:
        for node in hierarchy().iter("node"):
            if node.get("text") == label or node.get("content-desc") == label:
                bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if bounds:
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                    time.sleep(1)
                    return
        raise RuntimeError("Visible UI control missing: " + label)

    def check(name: str) -> tuple[int, int]:
        if not str(adb("shell", "pidof", PACKAGE)).strip():
            raise RuntimeError("App process disappeared during " + name)
        tree = hierarchy()
        if not any(n.get("package") == PACKAGE for n in tree.iter("node")):
            raise RuntimeError("App did not render its visible UI during " + name)
        ElementTree.ElementTree(tree).write(output / (name + ".xml"), encoding="utf-8")
        png = adb("exec-out", "screencap", "-p", binary=True)
        (output / (name + ".png")).write_bytes(png)
        width, height = struct.unpack(">II", png[16:24])
        report["checks"].append({"name": name, "status": "PASS", "width": width, "height": height})
        return width, height

    test_failure = None
    try:
        launch()
        time.sleep(8)
        check("cold_start")
        for label in ("Рынок", "Сделки", "Анализ", "Система", "Главная"):
            tap(label)
            check("navigation_" + {"Рынок":"market", "Сделки":"trades", "Анализ":"analysis",
                                   "Система":"system", "Главная":"home"}[label])
        tap("Ориентация экрана"); tap("Горизонтально")
        width, height = check("landscape")
        if width <= height:
            raise RuntimeError("Landscape selection was not applied")
        tap("Ориентация экрана"); tap("Вертикально")
        width, height = check("portrait")
        if width >= height:
            raise RuntimeError("Portrait selection was not applied")
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        time.sleep(3)
        tap("Сделки")
        check("offline_ui")
        adb("shell", "svc", "wifi", "enable")
        adb("shell", "svc", "data", "enable")
        adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
        time.sleep(3)
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        launch()
        check("screen_off_return")
        adb("shell", "am", "force-stop", PACKAGE)
        launch()
        time.sleep(3)
        check("process_restart")
        if not visible("Сделки"):
            raise RuntimeError("Navigation missing after process restart")
        report["result"] = "UI_LIFECYCLE_PASS_NO_TRADING"
    except Exception as failure:
        # If ADB dies during a UI assertion, do not hide that original failure
        # behind another ADB exception from the mandatory logcat collection.
        test_failure = failure
        report["result"] = "FAIL_UI_OR_LIFECYCLE"
        report["failure_type"] = type(failure).__name__
        raise
    finally:
        log = ""
        evidence_failures = []
        try:
            log = adb_evidence("logcat", "-d", "-v", "threadtime")
            (output / "logcat.txt").write_text(log)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            evidence_failures.append("ADB_LOGCAT_" + type(exc).__name__)
        try:
            services = adb_evidence("shell", "dumpsys", "activity", "services", PACKAGE)
            (output / "services.txt").write_text(services)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            evidence_failures.append("ADB_SERVICES_" + type(exc).__name__)
        # Missing ADB evidence means *unknown*, not "no crashes".
        fatal = "FATAL EXCEPTION" in log and ("Process: " + PACKAGE) in log
        anr = "ANR in " + PACKAGE in log
        report["app_crash_observed"] = fatal if log else None
        report["app_anr_observed"] = anr if log else None
        report["evidence_collection_failures"] = evidence_failures
        if fatal or anr:
            report["result"] = "FAIL_CRASH_OR_ANR"
        elif evidence_failures and test_failure is None:
            report["result"] = "FAIL_EMULATOR_ADB_EVIDENCE_UNAVAILABLE"
        (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        if test_failure is None:
            if fatal or anr:
                raise RuntimeError("Application crash/ANR found in emulator logcat")
            if evidence_failures:
                raise RuntimeError("Cannot certify lifecycle: Android emulator ADB evidence unavailable")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.apk, args.output), ensure_ascii=False))
