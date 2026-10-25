"""2024 sealed closed H1 source set: independent *frequency* of TC2 H1 shock veto.

Not a simulated portfolio, fill model, book accuracy or evidence of profitability.
The threshold policy was selected after the three 25 Oct 2026 stop-outs.
"""
from __future__ import annotations

import json
import statistics
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / "app/src/test/resources"
SYMBOLS = (
    "ADA", "AVAX", "BCH", "BNB", "BTC", "DOGE", "DOT", "ETH",
    "LINK", "LTC", "NEAR", "SOL", "TRX", "UNI", "XRP",
)
HOUR_MS = 3_600_000


def count_shock_veto_windows(rows: list[list], direction: str) -> int:
    assert direction in {"LONG", "SHORT"}
    n = 0
    for i in range(11, len(rows)):
        # Prefix-only: no row with open time > current fully closed bar.
        history = rows[i - 11 : i + 1]
        last = history[-1]
        prior = history[-5:-1]
        ranges = sorted(float(x[2]) - float(x[3]) for x in history)
        median = (ranges[5] + ranges[6]) / 2
        spread = float(last[2]) - float(last[3])
        if median <= 0 or spread <= 0:
            continue
        close_location = (float(last[4]) - float(last[3])) / spread
        counter_extreme = (
            float(last[3]) < min(float(x[3]) for x in prior)
            if direction == "LONG"
            else float(last[2]) > max(float(x[2]) for x in prior)
        )
        if counter_extreme and spread / median >= 1.5 and (
            close_location <= 0.25 if direction == "LONG"
            else close_location >= 0.75
        ):
            n += 1
    return n


def test_frozen_2024_fifteen_symbol_h1_shock_veto_coverage() -> None:
    long = short = windows = 0
    for symbol in SYMBOLS:
        path = SRC / f"blind_wave_20241109_29_{symbol}USDT.json"
        fixture = json.loads(path.read_text(encoding="utf-8"))
        assert fixture["source"] == "BINANCE_PUBLIC_USDM_FUTURES_CLOSED_H1"
        assert fixture["symbol"] == f"{symbol}USDT"
        assert fixture["interval"] == "1h"
        rows = fixture["rows"]
        assert len(rows) == 480
        assert all(int(b[0]) - int(a[0]) == HOUR_MS for a, b in zip(rows, rows[1:]))
        windows += len(rows) - 11
        long += count_shock_veto_windows(rows, "LONG")
        short += count_shock_veto_windows(rows, "SHORT")
        # Future mutation cannot change an earlier-prefix decision.
        first_prefix = rows[:200]
        polluted = [list(r) for r in rows]
        polluted[-1][2] = "9999999"
        polluted[-1][4] = "9999999"
        assert count_shock_veto_windows(first_prefix, "LONG") == (
            count_shock_veto_windows(polluted[:200], "LONG")
        )
    # Frozen benchmark parity; they are candidate-independent *frequencies*,
    # NOT counts of avoided losses, actual TC2 campaign stop proposals or PnL.
    assert (windows, long, short) == (7035, 195, 290)
