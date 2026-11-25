"""Qualify the existing 24-month portfolio archive using the verified repair overlay.

Read the previous immutable CI artifacts; never redownload the original Binance
monthly data, mutate original ZIPs, interpolate prices or use contract-price
candles instead of Mark Price. Output only repaired files and a unified manifest.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from research.fetch_portfolio_archives import (
    BASE, DATASETS, MONTHS, archive_path, month_bounds, months, validate,
)
from research.merge_mark_price_repairs import merge_archive_file
from trading_config import DEFAULT_FUTURES_SYMBOLS

ORIGINAL_RUN_ID = 45633468011
ORIGINAL_HEAD = "8adee923b41b389a6ff984f59586d689907736f1"
OVERLAY_DIR = Path("research/mark_price_repairs/2026-06-29")
GAP_START = 1782691200000
GAP_END = 1782777600000


def qualify(original_root: Path, output: Path, revision: str):
    if original_root.resolve() == output.resolve() or original_root.resolve() in output.resolve().parents:
        raise ValueError("output must not modify original artifacts")
    output.mkdir(parents=True, exist_ok=True)
    records, repair_reports = [], []
    seen_symbols = set()
    overlay_manifest = json.loads((OVERLAY_DIR / "manifest.json").read_text())
    if set(overlay_manifest["symbols"]) != set(DEFAULT_FUTURES_SYMBOLS):
        raise ValueError("overlay universe differs from approved portfolio")
    for symbol in DEFAULT_FUTURES_SYMBOLS:
        source = original_root / f"portfolio-archives-{symbol}"
        manifest = json.loads((source / "manifest.json").read_text())
        if (manifest["symbol"] != symbol or symbol in seen_symbols
                or manifest["source"] != BASE
                or manifest["start"] != "2024-11-01T00:00:00Z"
                or manifest["end_exclusive"] != "2026-11-01T00:00:00Z"
                or manifest["expected_archives"] != MONTHS * len(DATASETS)):
            raise ValueError(f"{symbol}: wrong original manifest identity")
        seen_symbols.add(symbol)
        original_rows = {row["path"]: row for row in manifest["archive_rows"]}
        if len(original_rows) != len(manifest["archive_rows"]) or len(original_rows) != 96:
            raise ValueError(f"{symbol}: duplicate or incomplete original manifest")
        for year, month in months():
            start, end = month_bounds(year, month)
            for kind, frame, step in DATASETS:
                path = archive_path(symbol, kind, frame, year, month)
                prior = original_rows[path]
                if (prior["kind"] != kind or prior["frame"] != frame
                        or prior["month"] != f"{year:04}-{month:02}"):
                    raise ValueError(f"{symbol}: inconsistent archive identity {path}")
                original_path = source / path
                raw = original_path.read_bytes()
                digest = hashlib.sha256(raw).hexdigest()
                if digest != prior["sha256"] or len(raw) != prior["bytes"]:
                    raise ValueError(f"{symbol}: original SHA-256/size mismatch {path}")
                facts = validate(raw, kind, step, start, end)
                record = {
                    "symbol": symbol, "path": path, "kind": kind, "frame": frame,
                    "month": prior["month"], "original_sha256": digest,
                    "original_published_checksum_url": BASE + "/" + path + ".CHECKSUM",
                    "original_source_url": BASE + "/" + path,
                    "original_run_id": ORIGINAL_RUN_ID,
                    "original_artifact": f"portfolio-archives-{symbol}",
                }
                if facts["complete"]:
                    if prior["status"] != "VERIFIED" or not prior["complete"]:
                        raise ValueError(f"{symbol}: unexpected source status for {path}")
                    record.update(facts, sha256=digest, bytes=len(raw), status="VERIFIED_ORIGINAL")
                else:
                    if not (kind == "markPriceKlines" and frame == "5m"
                            and (year, month) == (2026, 6) and prior["status"] == "GAPS"
                            and facts["missing_rows"] == 288):
                        raise ValueError(f"{symbol}: unexplained archive gap {path}")
                    repaired_path = output / "repaired" / path
                    overlay = OVERLAY_DIR / f"{symbol}-5m-2026-06-29.csv"
                    report = merge_archive_file(symbol, original_path, overlay,
                                                repaired_path, OVERLAY_DIR / "manifest.json")
                    if (report["repair_start_ms"] != GAP_START
                            or report["repair_end_exclusive_ms"] != GAP_END
                            or report["original_zip_sha256"] != digest
                            or report["recovered_rows"] != 288):
                        raise ValueError(f"{symbol}: unexpected repair provenance")
                    repaired = repaired_path.read_bytes()
                    facts = validate(repaired, kind, step, start, end)
                    if not facts["complete"] or facts["rows"] != 8640:
                        raise ValueError(f"{symbol}: repaired June data still incomplete")
                    repair_reports.append(report)
                    record.update(facts, status="VERIFIED_REPAIRED",
                                  sha256=report["repaired_zip_sha256"],
                                  bytes=len(repaired), overlay_sha256=report["repair_csv_sha256"],
                                  overlay_source=overlay_manifest["source_endpoint"],
                                  repaired_path="repaired/" + path)
                    print(f"REPAIRED {symbol}: {report['original_rows']} + 288 = {facts['rows']}", flush=True)
                records.append(record)
    if len(records) != 1440 or len(repair_reports) != 15:
        raise ValueError("portfolio coverage or repaired archive count mismatch")
    if any(not r["complete"] for r in records):
        raise ValueError("remaining source gaps")
    result = {
        "schema_version": 1, "status": "VERIFIED_ARCHIVE_COVERAGE",
        "source_revision": revision, "original_source_revision": ORIGINAL_HEAD,
        "original_run_id": ORIGINAL_RUN_ID, "symbols": list(DEFAULT_FUTURES_SYMBOLS),
        "start_utc": "2024-11-01T00:00:00Z", "end_exclusive_utc": "2026-11-01T00:00:00Z",
        "verified_original_archives": 1425, "verified_repaired_archives": 15,
        "total_archives": 1440, "recovered_mark_price_candles": 4320,
        "original_archives_modified": False, "no_interpolation": True,
        "no_contract_price_substitution": True,
        "higher_timeframes_need_causal_aggregation": ["15m", "4h", "1d"],
        "preperiod_indicator_warmup_verified": False,
        "historical_contract_filters_verified": False,
        "backtest_completed": False, "execution_authority": False,
        "funding_coverage": "Validated archived event ordering and neighbouring intervals; not private settlement proof.",
        "archives": records,
    }
    payload = (json.dumps(result, sort_keys=True, indent=2) + "\n").encode()
    (output / "portfolio-manifest.json").write_bytes(payload)
    (output / "portfolio-manifest.json.sha256").write_text(
        hashlib.sha256(payload).hexdigest() + "  portfolio-manifest.json\n"
    )
    print("PORTFOLIO_ARCHIVE_COVERAGE_PASS: 1440/1440; 4320 recovered rows; backtest NOT executed", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    qualify(args.original_root, args.output, args.revision)


if __name__ == "__main__":
    main()
