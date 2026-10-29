"""Read-only, complete H4 bars derived from the frozen exchange H1 source.

This is NOT independently downloaded Binance H4 history. All H4 OHLCV
extremes are calculated from four contiguous *already closed* UTC H1
candles. Partial H4 candles, gaps, duplicated timestamps and future
candles cannot become wave evidence or exchange instructions.
"""
from __future__ import annotations

from math import fsum, isfinite

from williams_wave_hierarchy import WaveBar, build_wave_hierarchy

HOUR = 3_600_000
H4 = 4 * HOUR
SOURCE = "UTC_H4_EXACTLY_FOUR_FROZEN_BINANCE_CLOSED_H1_NOT_NATIVE_H4"
NO_TRADE = {"can_place_orders": False, "can_close": False, "can_reverse": False}


def complete_h4_from_closed_h1(rows, as_of: int) -> list[WaveBar]:
    if not isinstance(as_of, int) or as_of < H4:
        raise ValueError("INVALID_H4_CUTOFF")
    closed = []
    for row in rows:
        if len(row) != 6:
            raise ValueError("INVALID_H1_ROW")
        t = int(row[0])
        if t + HOUR > as_of:
            continue  # future or unclosed source bar has no observable effect
        o, h, l, c, v = (float(x) for x in row[1:])
        if (t % HOUR != 0 or t < 0 or
                not all(isfinite(x) for x in (o, h, l, c, v)) or
                min(o, l, c) <= 0 or h < max(o, c, l) or
                l > min(o, c) or v < 0):
            raise ValueError("INVALID_CLOSED_SOURCE_H1")
        if closed and t != closed[-1][0] + HOUR:
            raise ValueError("GAPPED_OR_UNORDERED_SOURCE_H1")
        closed.append((t, o, h, l, c, v))
    result = []
    buckets = {}
    for row in closed:
        bucket = row[0] - row[0] % H4
        buckets.setdefault(bucket, []).append(row)
    for start, group in sorted(buckets.items()):
        if len(group) != 4:
            continue
        if ([r[0] for r in group] != [start + i * HOUR for i in range(4)]
                or start + H4 > as_of):
            raise ValueError("NON_CAUSAL_OR_MISALIGNED_H4")
        result.append(WaveBar(
            start, group[0][1], max(r[2] for r in group),
            min(r[3] for r in group), group[-1][4],
            fsum(r[5] for r in group),
        ))
    return result


def h4_research_context(rows, as_of):
    h4 = complete_h4_from_closed_h1(rows, as_of)
    h1 = [WaveBar(int(r[0]), *map(float, r[1:6]))
          for r in rows if int(r[0]) + HOUR <= as_of]
    # Enforce that H4 is observational only, and all context stays behind
    # the decision boundary. Do not create a D1 candidate from 20 days.
    if len(h4) < 40:
        return {"source": SOURCE, "status": "INSUFFICIENT_COMPLETE_H4_BARS",
                "closed_h4_bars": len(h4), "h4_price_scenarios": 0,
                "nested_h4_h1_links": 0,
                "degree_of_h4_verified_by_expert": False, **NO_TRADE}
    report = build_wave_hierarchy({"4h": h4, "1h": h1}, as_of,
                                  analysis_frames=("4h", "1h"))
    states = [s for s in report.scenarios if s.frame == "4h"]
    h1s = [s for s in report.scenarios if s.frame == "1h"]
    return {
        "source": SOURCE, "status": "PRICE_CONTEXT_ONLY_NO_NATIVE_H4_PROOF",
        "closed_h4_bars": len(h4), "h4_frame_error": report.frame_errors.get("4h"),
        "h4_price_scenarios": len(states), "h1_price_scenarios": len(h1s),
        "h4_candidate_directions": sorted({s.direction for s in states
                                           if s.status != "INVALIDATED"}),
        "h4_candidate_labels": sorted({s.label for s in states
                                       if s.status != "INVALIDATED"}),
        "nested_h4_h1_links": len(report.links),
        "h4_w4_cross_observed": sum(s.w4_zero == "MINIMUM_ONLY"
                                     for s in states),
        "highest_h4_pivot_known_at": max(
            (s.anchors[-1].known_at for s in states), default=0),
        "degree_of_h4_verified_by_expert": False,
        "d1_history_40_complete_bars_available": False,
        **NO_TRADE,
    }
