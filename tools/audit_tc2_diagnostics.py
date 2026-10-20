#!/usr/bin/env python3
"""Offline audit of archived WILLIAMS BOT diagnostic snapshots.

Read-only. Does not fetch candles, submit orders, backfill missing fills or
pretend a post-trade H1 snapshot proves the original intrabar execution path.
CLI: python tools/audit_tc2_diagnostics.py diagnostic.zip --output audit.json
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import math
from pathlib import Path
import zipfile

HOUR_MS = 3_600_000
WATCH_SYMBOLS = ("ETHUSDT", "SOLUSDT", "XRPUSDT")


def read_report(path: str | Path) -> dict:
    path = Path(path)
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            return json.loads(archive.read("diagnostic_report.json"))
    return json.loads(path.read_text(encoding="utf-8"))


def iso(ms: int) -> str:
    return dt.datetime.fromtimestamp(ms / 1000, tz=dt.timezone.utc).isoformat()


def audit_trade(trade: dict, trace: dict | None) -> dict:
    sym = trade.get("symbol", "UNKNOWN")
    source = trade.get("entry_snapshot") or {}
    signal_at = int(source.get("signal_bar_time_ms") or 0)
    opened = int(trade.get("opened_at") or 0)
    closed = int(trade.get("closed_at") or 0)
    exit_reason = trade.get("exit_reason", "UNVERIFIED")
    signal_type = source.get("signal_type", "UNKNOWN")
    direction = trade.get("direction", "UNKNOWN")
    candles = (trace or {}).get("candles") or []
    observed_bars = {int(row[0]): row for row in candles if isinstance(row, list) and len(row) >= 5}
    signal = observed_bars.get(signal_at)
    previous = observed_bars.get(signal_at - HOUR_MS)
    unknown = []
    if source.get("strategy_profile") != "TC2_THREE_WISE_MEN":
        unknown.append("NOT_A_TC2_SOURCE")
    if signal is None or previous is None:
        unknown.append("SIGNAL_AND_PREVIOUS_H1_BAR_NOT_AVAILABLE")
    if opened <= signal_at + HOUR_MS or closed < opened:
        unknown.append("ENTRY_OR_CLOSE_CHRONOLOGY_NOT_PROVEN")
    if not candles:
        unknown.append("H1_REPLAY_CANDLES_MISSING")
    elif any(int(b[0]) - int(a[0]) != HOUR_MS for a, b in zip(candles, candles[1:])):
        unknown.append("H1_REPLAY_HISTORY_HAS_GAPS")
    # A WM1 requires additional angulation/Alligator geometry, assessed
    # independently by the Kotlin engine. This local check is necessary but
    # *not sufficient* for a book-canonical WM1 signal.
    reversal_geometry = None
    book_trigger_candidate = None
    book_initial_stop_candidate = None
    if signal is not None and previous is not None and signal_type in ("REVERSAL", "SHORT_REVERSAL"):
        high, low, close = map(float, (signal[2], signal[3], signal[4]))
        prev_high, prev_low = float(previous[2]), float(previous[3])
        mid = (high + low) / 2
        if direction == "SHORT":
            reversal_geometry = high > prev_high and close <= mid
        elif direction == "LONG":
            reversal_geometry = low < prev_low and close >= mid
        # Estimate exchange tick from the book-aligned protective stop; the
        # diagnostic does not contain guaranteed historic exchangeInfo ticks.
        stop = source.get("initial_protective_stop")
        try:
            stop = float(stop)
            tick = stop - high if direction == "SHORT" else low - stop
            if math.isfinite(tick) and tick > 0:
                book_trigger_candidate = low - tick if direction == "SHORT" else high + tick
                book_initial_stop_candidate = stop
        except (ValueError, TypeError):
            pass
    else:
        unknown.append("WM1_REVERSAL_GEOMETRY_NOT_RECONSTRUCTABLE")
    # A legacy label alone is not an authenticated Binance closing-order fill.
    # Even though gross/net PnL may be authenticated, reason provenance remains
    # separate from income-trade provenance.
    chain = (trace or {}).get("order_chain") or []
    exit_orders = [row for row in chain if row.get("intent_role") == "EXIT"
                   and row.get("order_status") == "FILLED"]
    classified = (
        "DISCRETIONARY_OPPOSITION_MARKET_EXIT" if exit_reason.startswith("OPPOSITE_WILLIAMS")
        else "EXCHANGE_RECONCILED_CLOSE_UNVERIFIED_CAUSE" if exit_reason == "EXCHANGE_RECONCILED_CLOSE"
        else "OTHER_RECORDED_EXIT"
    )
    if classified == "EXCHANGE_RECONCILED_CLOSE_UNVERIFIED_CAUSE":
        unknown.append("NO_EXCHANGE_STOP_FILL_ID_TO_PROVE_EXIT_CAUSE")
    if source.get("wave_context_at_entry", "") in ("", None, "{}"):
        unknown.append("ENTRY_WAVE_CONTEXT_NOT_DURABLE")
    if not (trace or {}).get("wave_hypotheses"):
        unknown.append("POST_TRADE_WAVE_PHASE_UNRESOLVED")
    result = {
        "symbol": sym,
        "source": source.get("strategy_profile", "UNKNOWN"),
        "direction": direction,
        "signal_type": signal_type,
        "signal_h1_open_utc": iso(signal_at) if signal_at else None,
        "signal_known_utc": iso(signal_at + HOUR_MS) if signal_at else None,
        "entry_utc": iso(opened) if opened else None,
        "exit_utc": iso(closed) if closed else None,
        "observed_h1_bars": len(candles),
        "reversal_geometry_valid_without_alligator": reversal_geometry,
        "angulation_confirmed_from_original_decision": False,
        "candidate_book_h1_trigger": book_trigger_candidate,
        "persisted_order_trigger": float(source["entry_price"]) if source.get("entry_price") else None,
        "estimated_original_protective_stop": book_initial_stop_candidate,
        "actual_exchange_entry": trade.get("entry_price"),
        "actual_exchange_exit": trade.get("exit_price"),
        "net_pnl_usdt": trade.get("net_pnl_usdt"),
        "fee_known": trade.get("fee_known", False),
        "recorded_exit_reason": exit_reason,
        "exit_class": classified,
        "exchange_confirmed_exit_intent_in_trace": bool(exit_orders),
        "exchange_stop_order_fill_identity_verified": False,
        "limitations": unknown,
    }
    if reversal_geometry is False:
        result["limitations"].append("WM1_BAR_GEOMETRY_CONTRADICTS_BOOK_TEST")
    return result


def audit_snapshot(report: dict, symbols: tuple[str, ...] = WATCH_SYMBOLS) -> dict:
    observations = report.get("status", {}).get("trade_observations", [])
    history = report.get("signal_decision_history", {})
    trades = [a for sym in symbols for a in observations if a.get("symbol") == sym
              and a.get("status") == "CLOSED"]
    checks = [audit_trade(t, history.get(t["symbol"])) for t in trades]
    total = sum(float(t["net_pnl_usdt"]) for t in checks if t["net_pnl_usdt"] is not None)
    return {
        "schema": "WILLIAMS_TC2_ARCHIVED_CAUSAL_AUDIT_V1",
        "source_version": report.get("system_info", {}).get("app_version", "UNKNOWN"),
        "source_observed_at": report.get("created_at"),
        "purpose": "HISTORICAL_DIAGNOSTIC_AUDIT_NOT_FULL_EXCHANGE_REPLAY",
        "symbols_requested": list(symbols),
        "closed_trades_found": len(checks),
        "recorded_net_pnl_usdt": round(total, 8),
        "discretionary_opposition_market_exits": sum(
            a["exit_class"] == "DISCRETIONARY_OPPOSITION_MARKET_EXIT" for a in checks
        ),
        "exchange_reconciled_unknown_causes": sum(
            a["exit_class"] == "EXCHANGE_RECONCILED_CLOSE_UNVERIFIED_CAUSE" for a in checks
        ),
        "trades": checks,
        "not_proven": [
            "Actual moment-of-entry Alligator/WM1 proof for 4.34, unless original decision event is provided",
            "Closed-bar OHLC cannot determine order of stop/trigger touches within the same hour",
            "Book rules at a historical trade are not proven by subsequent scanner indicators",
            "Stop fill client/order ID and Binance algo status are required to certify an exchange stop exit",
            "Missing M15 is not a trading blocker for TC2, H1 signal trigger policy",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("diagnostic", help="diagnostic_report.json or .zip")
    parser.add_argument("--output", help="write machine-readable JSON report")
    parser.add_argument("--symbols", nargs="+", default=list(WATCH_SYMBOLS))
    args = parser.parse_args()
    results = audit_snapshot(read_report(args.diagnostic), tuple(args.symbols))
    content = json.dumps(results, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(content + "\n", encoding="utf-8")
    else:
        print(content)


if __name__ == "__main__":
    main()
