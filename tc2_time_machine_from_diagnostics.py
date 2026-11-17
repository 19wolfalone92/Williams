"""Offline read-only bridge: diagnostics ZIP closed H1 -> TC2 Time Machine.

Example:
python tc2_time_machine_from_diagnostics.py --zip Williams_Diagnostics.zip \
  --symbol BNBUSDT --side SHORT --trigger 670.53 --stop 690.69

The supplied order is a hypothetical PLAN AT THE ARCHIVE SNAPSHOT (not
proof that an old entry signal existed or was valid at this later time).
No exchange/Android access; refuses incomplete or noncontiguous H1.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import zipfile

from tc2_time_machine_research import H1Bar, StopEntry, bootstrap_tc2_h1


def research_archive(
    zip_path: str | Path,
    *,
    symbol: str,
    side: str,
    trigger: float,
    stop: float,
    seed: int = 19,
    replays: int = 10_000,
) -> dict:
    allowed = set("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
    symbol = symbol.upper()
    if not symbol or any(x not in allowed for x in symbol):
        raise ValueError("invalid symbol")
    with zipfile.ZipFile(zip_path) as archive:
        meta = archive.getinfo("signal_decision_history.json")
        if meta.file_size > 80_000_000:
            raise ValueError("unreasonably large signal history")
        source = json.loads(archive.read(meta))
    trace = source.get(symbol)
    if not isinstance(trace, dict):
        raise ValueError("symbol not found in diagnostic H1 archive")
    if trace.get("frame") != "1h" or trace.get("history_contiguous") is not True:
        raise ValueError("archive does not prove contiguous H1 history")
    rows = trace.get("candles")
    if not isinstance(rows, list) or not rows:
        raise ValueError("no H1 OHLC for symbol")
    candles = []
    for row in rows:
        if not isinstance(row, list) or len(row) < 5:
            raise ValueError("malformed archive candle")
        candles.append(H1Bar(int(row[0]), float(row[1]), float(row[2]),
                             float(row[3]), float(row[4])))
    end = int(trace["as_of_close_ms"])
    report = bootstrap_tc2_h1(
        candles, StopEntry(side.upper(), trigger, stop), asof_ms=end,
        seed=seed, replays=replays)
    report["symbol"] = symbol
    report["data_origin"] = "ANDROID_DIAGNOSTIC_CLOSED_H1_AS_OF_SNAPSHOT"
    report["comparison_scope"] = "NO_OLD_SIGNAL_REPLAY_NO_EXECUTION_NO_AUTHORITY"
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zip", required=True, help="diagnostic archive ZIP")
    parser.add_argument("--symbol", required=True, help="e.g. BNBUSDT")
    parser.add_argument("--side", required=True, choices=("LONG", "SHORT"))
    parser.add_argument("--trigger", required=True, type=float)
    parser.add_argument("--stop", required=True, type=float)
    parser.add_argument("--replays", type=int, default=10_000)
    parser.add_argument("--seed", type=int, default=19)
    args = parser.parse_args()
    print(json.dumps(research_archive(args.zip, symbol=args.symbol,
        side=args.side, trigger=args.trigger, stop=args.stop,
        seed=args.seed, replays=args.replays), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
