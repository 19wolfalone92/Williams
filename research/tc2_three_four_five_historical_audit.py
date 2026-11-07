#!/usr/bin/env python3
"""Book-approved TC2 3/4/5 H1 STOP window comparison on frozen 2025 archive.

NO exchange credentials, no order API, no selection of a live STOP method.
The sample consists of hypothetical WM1/WM2/WM3 OPPOSITE-EXIT candidates
with common initial eligibility across all three variants, not actual fills.
The original 2026 policies are unchanged. 2025 was already seen by research:
it is reverse-time EXTERNAL ROBUSTNESS, NOT prospective holdout.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
from statistics import mean

from research import tc2_context_walkforward as original
from research import tc2_external_2025_validation as external

WINDOWS=(3,4,5)
HORIZONS=(6,24)
MODELS=(False,True)

def _rounded(values:list[float]):
    return round(mean(values),4) if values else None

def _cohort(events:list[dict])->dict:
    out={"candidate_count":len(events),
         "sources":dict(Counter(e["source"] for e in events)),
         "windows":{}}
    for horizon in HORIZONS:
        for trailing in MODELS:
            key=f"{horizon}h_{'trail' if trailing else 'static'}"
            eligible=[]
            excluded=Counter()
            per_window=Counter()
            for e in events:
                r=e["outcomes"][key]
                for n in WINDOWS:
                    if r[n].get("valid"):per_window[str(n)]+=1
                if all(r[n].get("valid") for n in WINDOWS):
                    eligible.append(e)
                else:
                    for n in WINDOWS:
                        if not r[n].get("valid"):
                            excluded[f"{n}:{r[n].get('reason','UNVERIFIED')}"]+=1
            # COMMON matched cohort protects against cherry-picking different
            # candidates that qualify for a narrower/wider initial STOP.
            summaries={str(n):original._summary([
                e["outcomes"][key][n] for e in eligible]) for n in WINDOWS}
            differences={}
            for loose,tight in ((4,3),(5,3),(5,4)):
                delta=[(e["outcomes"][key][loose]["delta_bps"]-
                         e["outcomes"][key][tight]["delta_bps"]) for e in eligible]
                # If the wider STOP keeps a trade, it can BOTH save an early
                # stopout and incur extra downside. Count BOTH; no marketing.
                extra_loss=[max(0.,-x) for x in delta]
                saved=[max(0.,x) for x in delta]
                early=[(e["outcomes"][key][tight]["stop_hit"] and
                        not e["outcomes"][key][loose]["stop_hit"])
                       for e in eligible]
                differences[f"{loose}_minus_{tight}"]={
                    "matched_events":len(delta),
                    "mean_delta_bps":_rounded(delta),
                    "wider_is_better_count":sum(x>0 for x in delta),
                    "wider_is_worse_count":sum(x<0 for x in delta),
                    "extra_loss_from_wider_stop_total_bps":round(sum(extra_loss),4),
                    "additional_gain_from_wider_stop_total_bps":round(sum(saved),4),
                    "worst_wider_minus_tighter_bps":round(min(delta),4) if delta else None,
                    "earlier_stop_hits_tighter_only":sum(early),
                    "not_certified_broker_pnl":True,
                }
            out["windows"][key]={
                "eligible_each_window":dict(per_window),
                "common_matched_eligible":len(eligible),
                "excluded_reason_counts":dict(excluded),
                "summary_common":summaries,
                "comparisons":differences,
                "gap_fill_worse_price_model":True,
                "funding_not_modelled":True,
                "entry_cost_common_and_excluded":True,
            }
    return out


def evaluate_2025(data_root:Path)->dict:
    all_events=[]
    for symbol in original.SYMBOLS:
        frames=external.load_2025(symbol,data_root)
        indic=original.indicators(frames["1h"],"1h")
        for raw in original.signals(frames["1h"],indic,
                                    original.TICK_ASSUMED[symbol]):
            event={**raw,"symbol":symbol}
            event["outcomes"]={
                f"{horizon}h_{'trail' if trailing else 'static'}":{
                    n:original.replay(event,frames["1h"],horizon,n,trailing=trailing)
                    for n in WINDOWS
                } for horizon in HORIZONS for trailing in MODELS
            }
            all_events.append(event)
    quarters={
        name:_cohort([e for e in all_events if start<=e["decision_at"]<end])
        for name,start,end in external.QUARTERS_2025
    }
    symbols={symbol:_cohort([e for e in all_events if e["symbol"]==symbol])
             for symbol in original.SYMBOLS}
    report={
        "schema":"TC2_FROZEN_2025_OPPOSITE_EXIT_3_4_5_READ_ONLY_V1",
        "source_book":"TC2_RU_PDF_161_AND_165",
        "period":"2025-01-01 to 2026-01-01",
        "source_dataset":"10 genuine Binance USD-M Futures PUBLIC H1/H4/D1 archives",
        "closed_candles_per_symbol":dict(external.FRAME_COUNTS),
        "closed_candle_count":10*sum(external.FRAME_COUNTS.values()),
        "source_signal_generator":"research/tc2_context_walkforward.py",
        "book_stop_windows":list(WINDOWS),
        "hypothetical_exit_cost_rate":original.COST_SIDE,
        "cost_applied_to":"EXIT_NOTIONAL_NOT_PRICE_DELTA",
        "study_is_real_fills":False,
        "not_a_prospective_holdout":True,
        "reoptimization_performed":False,
        "can_choose_live_stop":False,
        "can_place_orders":False,
        "can_close":False,
        "can_move_stop":False,
        "limitations":[
            "Original paper candidate is not a broker-owned or fully authenticated TC2 entry",
            "The initial previously active STOP is hypothetical, not a signed Binance STOP_MARKET",
            "2025 archive inspected after 2026 experiments: reverse-time robustness, not prospective holdout",
            "WM1 angulation uses numerical approximation rather than author book-chart labels",
            "The guessed 2026 tick sizes may not match each symbol's 2025 exchange filters",
            "Unknown intrabar order of simultaneous trigger/stop is excluded from eligible results",
            "A fee/slippage approximation is deducted from EXIT NOTIONAL; entry common to comparison excluded",
            "Funding, liquidation, borrow, partial fills, real bid/ask spread and exact order queue unknown",
            "Three STOP windows select different initial protection; compare only COMMON eligible events",
            "Higher leverage, trade frequency or live TC2 setting must NOT be changed using this report alone",
        ],
        "overall":_cohort(all_events),
        "by_quarter":quarters,
        "by_symbol":symbols,
    }
    return report


def main()->int:
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data",type=Path,default=Path("research/data_2025"))
    p.add_argument("--output",type=Path,
        default=Path("build/tc2_2025_three_four_five_shadow.json"))
    a=p.parse_args()
    report=evaluate_2025(a.data)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2,allow_nan=False)+"\n")
    print(json.dumps({"candidate_count":report["overall"]["candidate_count"],
        "matched_24h_trailing":report["overall"]["windows"]["24h_trail"]["common_matched_eligible"],
        "compared_windows":report["book_stop_windows"],
        "live_trading_authority":report["can_choose_live_stop"]},ensure_ascii=False))
    return 0


if __name__=="__main__":
    raise SystemExit(main())
