#!/usr/bin/env python3
"""OUT-OF-YEAR robustness of frozen 2026 TC2 context policies on 2025 Futures.

Read-only, public, fully CLOSED H1/H4/D1. All research rules come unchanged
from tc2_context_walkforward.py. The 2025 year was inspected only AFTER 2026
model selection and is thus retrospective reverse-time external validation,
NOT prospective holdout, not complete TC2 trades, and not a live strategy.
"""
from __future__ import annotations

import argparse
import collections
import json
import math
import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from research import tc2_context_walkforward as original

START_2025 = 1735689600000  # 2025-01-01 00:00 UTC
END_2025 = 1767225600000    # 2026-01-01 00:00 UTC
DATASET_SCHEMA = "WILLIAMS_USDM_PUBLIC_FROZEN_CLOSED_OHLCV_V1"
FRAME_COUNTS = {"1h":8760,"4h":2190,"1d":365}
QUARTERS_2025 = (
    ("2025-Q1", 1735689600000,1743465600000),
    ("2025-Q2", 1743465600000,1751328000000),
    ("2025-Q3", 1751328000000,1759276800000),
    ("2025-Q4", 1759276800000,1767225600000),
)

def load_2025(symbol:str, root:Path)->dict[str,list[tuple]]:
    if symbol not in original.SYMBOLS:
        raise ValueError("2025 external robustness cannot add outside symbols")
    path=root / f"usdm_closed_2025_{symbol}.json"
    obj=json.loads(path.read_text(encoding="utf-8"))
    if (obj.get("schema")!=DATASET_SCHEMA or
        obj.get("symbol")!=symbol or
        obj.get("venue")!="Binance USDⓈ-M Futures mainnet PUBLIC read-only" or
        obj.get("requested_from_utc")!="2025-01-01T00:00:00Z" or
        obj.get("as_of_exclusive_utc")!="2026-01-01T00:00:00Z" or
        obj.get("source_endpoint")!="/fapi/v1/klines" or
        obj.get("execution_authority") is not False or
        obj.get("columns")!=["open_time_ms","open","high","low","close","base_volume"]):
        raise ValueError("2025 Binance USD-M dataset provenance mismatch")
    out={}
    for frame,span in original.FRAMES.items():
        rows=obj["frames"].get(frame)
        if not isinstance(rows,list) or len(rows)!=FRAME_COUNTS[frame]:
            raise ValueError(f"{symbol}/{frame}: incomplete annual history")
        items=[]
        for i,row in enumerate(rows):
            if (not isinstance(row,list) or len(row)!=6 or
                type(row[0]) is not int or row[0]!=START_2025+i*span):
                raise ValueError(f"{symbol}/{frame}: missing, duplicate or misdated candle")
            v=[float(x) for x in row[1:]]
            o,hi,lo,c,vol=v
            if (not all(math.isfinite(x) for x in v) or
                min(o,hi,lo,c)<=0 or vol<0 or
                hi<max(o,c,lo) or lo>min(o,c,hi)):
                raise ValueError(f"{symbol}/{frame}: malformed OHLCV")
            if row[0]+span>END_2025:
                raise ValueError("2025 includes future annual bar")
            items.append((row[0],o,hi,lo,c,vol))
        out[frame]=items
    return out

def _cohort(events:list[dict])->dict[str,Any]:
    report={"all_candidates":len(events),"by_source":dict(collections.Counter(
        x["source"] for x in events))}
    for name,criterion in (
        ("h4_d1_only","hold"),
        ("h4_swing_confirmed","hold_structure"),
        ("h4_and_d1_swings_confirmed","hold_h4_and_d1_structure"),
    ):
        cohort=[x for x in events if x["context"].get(criterion,False)]
        report[name]={"proposed_holds":len(cohort),
            "candidate_count_by_source":dict(collections.Counter(
                x["source"] for x in cohort))}
        for h in (6,24):
            for n in (3,5):
                for trailing in (False,True):
                    key=f"{h}h_{'trail' if trailing else 'stop'}{n}"
                    outcomes=[x["outcomes"][key] for x in cohort
                              if x["outcomes"][key].get("valid")]
                    report[name][key]=original._summary(outcomes)
                    report[name][key]["excluded_before_hold"]=len(cohort)-len(outcomes)
                    report[name][key]["was_independent_real_binance_fill"]=False
    return report

def evaluate_2025(root:Path)->dict[str,Any]:
    symbol_metrics={}
    all_events=[]
    for symbol in original.SYMBOLS:
        frames=load_2025(symbol,root)
        h1=original.indicators(frames["1h"],"1h")
        h4=original.indicators(frames["4h"],"4h")
        d1=original.indicators(frames["1d"],"1d")
        events=[]
        for raw in original.signals(frames["1h"],h1,original.TICK_ASSUMED[symbol]):
            e={**raw,"symbol":symbol}
            c=original.regime(e,h1,h4,d1,frames["4h"],frames["1d"])
            # The 2026 model contains separate strict-H4 and H4&D1
            # conditions. Do not re-train or choose new thresholds on 2025.
            e["context"]=c
            e["outcomes"]={
                f"{hours}h_{'trail' if trailing else 'stop'}{bars}":
                    original.replay(e,frames["1h"],hours,bars,trailing=trailing)
                for hours in (6,24)
                for bars in (3,5)
                for trailing in (False,True)
            }
            events.append(e)
        all_events.extend(events)
        symbol_metrics[symbol]={"closed_candles":dict(FRAME_COUNTS),
            "events":_cohort(events)}
    # All months and symbols reported, including zeros and negative deltas.
    by_quarter={q:_cohort([x for x in all_events if lo<=x["decision_at"]<hi])
                for q,lo,hi in QUARTERS_2025}
    report={
        "schema":"WILLIAMS_TC2_2025_OUT_OF_YEAR_REVERSE_TIME_ROBUSTNESS_V1",
        "period_utc":"2025-01-01 to 2026-01-01",
        "dataset":"10 symbols x (8760 H1 + 2190 H4 + 365 D1) public genuine Futures bars",
        "frozen_closed_bar_count":len(original.SYMBOLS)*sum(FRAME_COUNTS.values()),
        "closed_candle_counts_per_symbol":dict(FRAME_COUNTS),
        "source_2026_decision_rules_unchanged":True,
        "source_definition":"research/tc2_context_walkforward.py",
        "research_only":True,
        "execution_authority":False,
        "fills_certified":False,
        "not_a_prospective_holdout":True,
        "no_reoptimized_thresholds":True,
        "strict_calendar_quarters":[x[0] for x in QUARTERS_2025],
        "sources_all":dict(collections.Counter(x["source"] for x in all_events)),
        "overall":_cohort(all_events),
        "quarters":by_quarter,
        "symbols":symbol_metrics,
        "limitations":[
            "Retrospective 2025 chosen after seeing 2026; NOT an untouched prospective holdout",
            "WM1 angulation is a numerical proxy, not book drawing gold labels",
            "WM2/WM3 candidates are not complete owned TC2 trade campaigns",
            "2026 estimated ticks applied to 2025 bars; historic exchange tick not authenticated",
            "Original STOP was a hypothetical 3/5-bar history STOP, not signed Binance position protection",
            "3/5 trailing on later closed H1 bars is counterfactual, no real Binance stop updates",
            "Gap fill at adverse opening price is only an approximation",
            "Unknown first trigger hour path is skipped; no M1 tick order or bid/ask",
            "No funding, partial fills, reverse pyramid, leverage, liquidation or actual execution",
            "Many symbols/signals correlate; do not present candidate count as independent trades",
            "No future H4/D1 or unconfirmed pivot levels used for decisions",
            "Cannot establish market understanding percentage or profitable live exit rule",
        ]
    }
    return report

def main()->int:
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data",type=Path,default=Path("research/data_2025"))
    p.add_argument("--output",type=Path,default=Path("build/tc2_context_external_2025.json"))
    args=p.parse_args()
    r=evaluate_2025(args.data)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(r,indent=2,ensure_ascii=False,allow_nan=False)+"\n",
                           encoding="utf-8")
    print(json.dumps({
        "frozen_candles":r["frozen_closed_bar_count"],
        "candidate_events":r["overall"]["all_candidates"],
        "strict_d1_h4_holds":r["overall"]["h4_and_d1_swings_confirmed"]["proposed_holds"],
        "static5":r["overall"]["h4_d1_only"]["24h_stop5"],
        "trailing5":r["overall"]["h4_d1_only"]["24h_trail5"],
        "has_trade_authority":r["execution_authority"],
    },ensure_ascii=False))
    return 0

if __name__=="__main__":
    raise SystemExit(main())
