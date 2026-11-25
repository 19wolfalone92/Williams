#!/usr/bin/env python3
"""Merge official Binance Mark Price repair overlays into June 2026 monthly ZIPs.

The original monthly archives are never mutated. This helper produces a new ZIP
and a machine-readable repair report. It fails closed on missing, duplicate,
overlapping, malformed, or non-chronological candle timestamps.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import zipfile
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

CSV_HEADER = (
    "open_time", "open", "high", "low", "close", "volume", "close_time",
    "quote_volume", "count", "taker_buy_volume", "taker_buy_quote_volume", "ignore",
)
MONTH_START_MS = 1_780_272_000_000
MONTH_END_EXCLUSIVE_MS = 1_782_864_000_000
REPAIR_START_MS = 1_782_691_200_000
REPAIR_END_EXCLUSIVE_MS = 1_782_777_600_000
INTERVAL_MS = 300_000
MONTH_ROWS = 8_640
REPAIR_ROWS = 288


class RepairValidationError(ValueError):
    """Raised when the original archive or repair overlay cannot be trusted."""


def _parse_csv(data: bytes, label: str) -> tuple[list[str], list[list[str]]]:
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise RepairValidationError(f"{label}: CSV is not UTF-8") from exc
    reader = csv.reader(io.StringIO(text, newline=""))
    try:
        header = next(reader)
    except StopIteration as exc:
        raise RepairValidationError(f"{label}: CSV is empty") from exc
    if tuple(header) != CSV_HEADER:
        raise RepairValidationError(f"{label}: unexpected Mark Price CSV header")
    rows = list(reader)
    if not rows or any(len(row) != len(CSV_HEADER) for row in rows):
        raise RepairValidationError(f"{label}: empty or malformed CSV rows")
    return header, rows


def _validate_row(row: list[str], interval_ms: int, label: str) -> int:
    try:
        timestamp = int(row[0])
        close_time = int(row[6])
        open_price, high, low, close = (Decimal(row[i]) for i in (1, 2, 3, 4))
    except (ValueError, InvalidOperation) as exc:
        raise RepairValidationError(f"{label}: invalid timestamp or OHLC value") from exc
    if timestamp < 0 or close_time != timestamp + interval_ms - 1:
        raise RepairValidationError(f"{label}: invalid closeTime at {timestamp}")
    if not all(value.is_finite() and value > 0 for value in (open_price, high, low, close)):
        raise RepairValidationError(f"{label}: non-positive or non-finite OHLC at {timestamp}")
    if high < max(open_price, close) or low > min(open_price, close) or low > high:
        raise RepairValidationError(f"{label}: OHLC consistency failure at {timestamp}")
    return timestamp


def _validate_exact_grid(
    rows: list[list[str]], start_ms: int, end_exclusive_ms: int, interval_ms: int, label: str
) -> list[int]:
    expected = list(range(start_ms, end_exclusive_ms, interval_ms))
    timestamps = [_validate_row(row, interval_ms, label) for row in rows]
    if len(timestamps) != len(set(timestamps)):
        raise RepairValidationError(f"{label}: duplicate candle timestamps")
    if timestamps != expected:
        raise RepairValidationError(f"{label}: timestamps are not the exact expected UTC grid")
    return timestamps


def merge_mark_price_monthly_archive(
    original_zip: bytes,
    repair_csv: bytes,
    *,
    symbol: str,
    expected_start_ms: int = MONTH_START_MS,
    expected_end_exclusive_ms: int = MONTH_END_EXCLUSIVE_MS,
    repair_start_ms: int = REPAIR_START_MS,
    repair_end_exclusive_ms: int = REPAIR_END_EXCLUSIVE_MS,
    interval_ms: int = INTERVAL_MS,
) -> tuple[bytes, dict[str, Any]]:
    """Return a repaired ZIP plus a provenance report; input bytes remain unchanged."""
    if not symbol or not symbol.isalnum() or interval_ms <= 0:
        raise RepairValidationError("invalid symbol or interval")
    try:
        source_zip = zipfile.ZipFile(io.BytesIO(original_zip), "r")
    except zipfile.BadZipFile as exc:
        raise RepairValidationError("original monthly archive is not a valid ZIP") from exc
    with source_zip:
        csv_members = [info for info in source_zip.infolist() if info.filename.lower().endswith(".csv")]
        if len(csv_members) != 1:
            raise RepairValidationError("original archive must contain exactly one CSV")
        csv_info = csv_members[0]
        original_csv = source_zip.read(csv_info.filename)
        original_header, original_rows = _parse_csv(original_csv, "original")
        repair_header, repair_rows = _parse_csv(repair_csv, "repair")
        if original_header != repair_header:
            raise RepairValidationError("original and repair CSV headers differ")

        expected_month = list(range(expected_start_ms, expected_end_exclusive_ms, interval_ms))
        original_timestamps = [_validate_row(row, interval_ms, "original") for row in original_rows]
        if len(original_timestamps) != len(set(original_timestamps)):
            raise RepairValidationError("original archive contains duplicate timestamps")
        if any(ts < expected_start_ms or ts >= expected_end_exclusive_ms for ts in original_timestamps):
            raise RepairValidationError("original archive contains timestamps outside the target month")

        repair_timestamps = _validate_exact_grid(
            repair_rows, repair_start_ms, repair_end_exclusive_ms, interval_ms, "repair"
        )
        original_set = set(original_timestamps)
        repair_set = set(repair_timestamps)
        if original_set.intersection(repair_set):
            raise RepairValidationError("repair overlay overlaps timestamps already in the original archive")
        missing = set(expected_month).difference(original_set)
        if missing != repair_set:
            raise RepairValidationError(
                f"repair overlay does not exactly cover the original archive gap "
                f"(missing={len(missing)}, overlay={len(repair_set)})"
            )

        merged_rows = sorted(original_rows + repair_rows, key=lambda row: int(row[0]))
        _validate_exact_grid(
            merged_rows, expected_start_ms, expected_end_exclusive_ms, interval_ms, "merged"
        )
        if len(merged_rows) != MONTH_ROWS:
            raise RepairValidationError(f"merged month must contain exactly {MONTH_ROWS} rows")

        output_csv = io.StringIO(newline="")
        writer = csv.writer(output_csv, lineterminator="\n")
        writer.writerow(original_header)
        writer.writerows(merged_rows)
        merged_csv = output_csv.getvalue().encode("utf-8")

        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", allowZip64=True) as target:
            for info in source_zip.infolist():
                payload = merged_csv if info.filename == csv_info.filename else source_zip.read(info.filename)
                target.writestr(info, payload)
        repaired_zip = output.getvalue()

    report = {
        "schema_version": 1,
        "status": "REPAIRED_AND_VERIFIED",
        "symbol": symbol.upper(),
        "interval": "5m",
        "month_start_ms": expected_start_ms,
        "month_end_exclusive_ms": expected_end_exclusive_ms,
        "repair_start_ms": repair_start_ms,
        "repair_end_exclusive_ms": repair_end_exclusive_ms,
        "original_rows": len(original_rows),
        "recovered_rows": len(repair_rows),
        "merged_rows": len(merged_rows),
        "unique_timestamps": len(merged_rows),
        "original_zip_sha256": hashlib.sha256(original_zip).hexdigest(),
        "repair_csv_sha256": hashlib.sha256(repair_csv).hexdigest(),
        "repaired_zip_sha256": hashlib.sha256(repaired_zip).hexdigest(),
        "original_zip_modified": False,
        "no_interpolation": True,
        "no_contract_price_substitution": True,
    }
    return repaired_zip, report


def merge_archive_file(
    symbol: str, original_path: Path, overlay_path: Path, output_path: Path, manifest_path: Path
) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("status") != "VERIFIED_REPAIR_OVERLAY":
        raise RepairValidationError("repair manifest is not in VERIFIED_REPAIR_OVERLAY state")
    record = next((row for row in manifest.get("files", []) if row.get("symbol") == symbol.upper()), None)
    if record is None:
        raise RepairValidationError(f"{symbol}: no entry in repair manifest")
    original_zip = original_path.read_bytes()
    repair_csv = overlay_path.read_bytes()
    actual_overlay_sha = hashlib.sha256(repair_csv).hexdigest()
    if actual_overlay_sha != record.get("sha256"):
        raise RepairValidationError(f"{symbol}: overlay SHA-256 does not match manifest")
    repaired_zip, report = merge_mark_price_monthly_archive(
        original_zip, repair_csv, symbol=symbol.upper()
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(repaired_zip)
    report["input_archive"] = str(original_path)
    report["repair_overlay"] = str(overlay_path)
    report["output_archive"] = str(output_path)
    report_path = output_path.with_suffix(output_path.suffix + ".repair.json")
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--symbol", required=True)
    parser.add_argument("--input", required=True, type=Path, help="Original June 2026 monthly Mark Price ZIP")
    parser.add_argument("--overlay", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    overlay = args.overlay or Path("research/mark_price_repairs/2026-06-29") / (
        f"{args.symbol.upper()}-5m-2026-06-29.csv"
    )
    manifest = args.manifest or overlay.parent / "manifest.json"
    report = merge_archive_file(args.symbol, args.input, overlay, args.output, manifest)
    print(json.dumps(report, indent=2))
    print("MARK_PRICE_ARCHIVE_REPAIR_PASS")


if __name__ == "__main__":
    main()
