"""Run the actual Kotlin archive wave replay on stored Binance klines.

Read-only, offline. Higher candles use complete contiguous source groups only;
no lower timeframe, missing candle, execution or Elliott reference is invented.
Requires the project's Kotlin 2.3.21 compiler and org.json jars in --kotlin-jars.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import subprocess

FRAME_MS = {"15m": 900_000, "30m": 1_800_000, "1h": 3_600_000,
            "2h": 7_200_000, "4h": 14_400_000, "6h": 21_600_000,
            "8h": 28_800_000, "12h": 43_200_000, "1d": 86_400_000}
SOURCES = ("WilliamsComplexWave", "WilliamsWaveSubdivisions", "WilliamsWaveHierarchy",
           "WilliamsWaveRhythm", "WilliamsScenarioLifecycle", "WilliamsAdaptiveWaveHistory",
           "WilliamsWaveArchiveReplay")


def aggregate_closed_groups(rows, source_ms, duration):
    """Preserve gaps: aggregation requires every genuine constituent candle."""
    groups = defaultdict(list)
    for row in rows:
        groups[int(row[0]) // duration * duration].append(row)
    out = []
    expected = duration // source_ms
    for opened, group in sorted(groups.items()):
        if len(group) != expected or any(int(row[0]) != opened+i*source_ms
                                         for i, row in enumerate(group)):
            continue
        out.append([opened, float(group[0][1]), max(float(r[2]) for r in group),
                    min(float(r[3]) for r in group), float(group[-1][4]),
                    sum(float(r[5]) for r in group)])
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--kotlin-jars", type=Path, required=True)
    parser.add_argument("--clock", help="Comma-separated clock frames; default every input candle closure")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    args.output.mkdir(parents=True, exist_ok=True)
    jars = sorted(args.kotlin_jars.glob("*.jar"))
    if not jars:
        parser.error("--kotlin-jars must contain the compiler, stdlib, and org.json jars")
    cp = ":".join(map(str, jars))
    classes = args.output / "classes"
    files = [repo / "app/src/main/java/com/williamsbot" / f"{name}.kt" for name in SOURCES]
    files.append(repo / "tools/WilliamsWaveArchiveReplayCli.kt")
    subprocess.run(["java", "-Xmx1g", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp,
                    "-d", str(classes), *map(str, files)], check=True)
    summaries = []
    for source in args.input:
        raw = json.loads(source.read_text())
        if isinstance(raw, dict) and "frames" in raw:
            prepared = raw
        else:
            rows = raw["rows"] if isinstance(raw, dict) else raw
            if isinstance(raw, dict) and raw.get("interval", "15m") != "15m":
                raise ValueError("Raw kline aggregation expects an explicit 15m source")
            rows = sorted(rows, key=lambda row: int(row[0]))
            # Duplicates remain observable rather than being silently collapsed.
            prepared = {"source": {"path": str(source.resolve()),
                        "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                        "origin": raw.get("source", "RETAINED_PUBLIC_BINANCE_USDM_KLINES")
                        if isinstance(raw, dict) else "RETAINED_PUBLIC_BINANCE_USDM_KLINES"},
                        "symbol": raw.get("symbol", source.stem) if isinstance(raw, dict) else source.stem,
                        "frames": {"15m": [[int(r[0]), *map(float, r[1:6])] for r in rows]}}
            for frame, duration in FRAME_MS.items():
                if frame != "15m":
                    prepared["frames"][frame] = aggregate_closed_groups(rows, 900_000, duration)
        if args.clock:
            prepared["clock_frames"] = args.clock.split(",")
        target = args.output / source.stem
        target.mkdir(exist_ok=True)
        input_file = target / "input.json"
        input_file.write_text(json.dumps(prepared, ensure_ascii=False))
        subprocess.run(["java", "-Xmx1g", "-cp", str(classes)+":"+cp,
                        "com.williamsbot.WilliamsWaveArchiveReplayCli", str(input_file), str(target)], check=True)
        summaries.append(json.loads((target / "summary.json").read_text()))
    (args.output / "summary.json").write_text(json.dumps({"cases": summaries}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
