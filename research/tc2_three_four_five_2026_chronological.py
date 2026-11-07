#!/usr/bin/env python3
"""Independent-time-segment replication of TC2 3/4/5 stop research on 2026.

Reuses original fixed 2026-09-01 post-embargo date for all ten *public* USD-M
symbols; does not refit thresholds or switch the production STOP policy.
IMPORTANT: this 2026 window has been examined in previous research. It is
not an untouched prospective trial; do NOT rename it a certified holdout.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from research import tc2_context_walkforward as source
from research import tc2_three_four_five_historical_audit as audit

MONTHS=(
 ("2026-09",1788220800000,1790812800000),
 ("2026-10",1790812800000,1793491200000),
 ("2026-11-partial",1793491200000,source.END),
)

def evaluate_2026(data_root:Path)->dict:
    events=[]
    inputs={}
    for symbol in source.SYMBOLS:
        frames=source.load(symbol,data_root)
        h1=source.indicators(frames["1h"],"1h")
        inputs[symbol]={key:len(value) for key,value in frames.items()}
        for raw in source.signals(frames["1h"],h1,source.TICK_ASSUMED[symbol]):
            if not source.OOS_START<=raw["decision_at"]<source.END:
                continue
            item={**raw,"symbol":symbol}
            item["outcomes"]={
                f"{hours}h_{'trail' if trailing else 'static'}":{
                    n:source.replay(item,frames["1h"],hours,n,trailing=trailing)
                    for n in audit.WINDOWS}
                for hours in audit.HORIZONS for trailing in audit.MODELS
            }
            events.append(item)
    return {
        "schema":"TC2_2026_POST_EMBARGO_345_READ_ONLY_RECHECK_V1",
        "source_book":"TC2_RU_PDF_161_AND_165",
        "period_utc":"2026-09-01 to 2026-11-05T06:00:00Z",
        "chronology_dev_ends_at_ms":source.TRAIN_END,
        "embargo_to_ms":source.OOS_START,
        "previously_inspected_data_not_a_new_untouched_holdout":True,
        "research_only":True,
        "can_move_stop":False,"can_place_orders":False,"can_close":False,
        "comparisons_locked_to_same_3_4_5":True,
        "per_symbol_input_counts":inputs,
        "cost_assumption_exit_fraction":source.COST_SIDE,
        "cost_charged_on":"EXIT_NOTIONAL",
        "limitations":[
            "The same 2026 chronological window was previously inspected in other studies",
            "Book WM1 numerical angulation is not independently human-labelled",
            "Research candidates are not broker-owned fills or protected live trades",
            "No authenticated historic tick, M1 sequencing, funding or live bid/ask",
            "Statistical correlation across symbols and hourly signals forbids independent trade-count claims",
            "3/4/5 comparison cannot authorize any real exchange order or policy switch",
        ],
        "overall":audit._cohort(events),
        "by_month":{name:audit._cohort([e for e in events if low<=e["decision_at"]<high])
                    for name,low,high in MONTHS},
        "by_symbol":{symbol:audit._cohort([e for e in events if e["symbol"]==symbol])
                     for symbol in source.SYMBOLS}
    }

def main()->int:
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data",type=Path,default=Path("research/data"))
    p.add_argument("--output",type=Path,default=Path("build/tc2_2026_post_embargo_345.json"))
    a=p.parse_args()
    result=evaluate_2026(a.data)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2,allow_nan=False)+"\n")
    print(json.dumps({
        "source":"frozen 2026 Sep-Nov known H1 data",
        "candidate_count":result["overall"]["candidate_count"],
        "common_matched_24h_trailing":result["overall"]["windows"]["24h_trail"]["common_matched_eligible"],
        "stop_windows":[3,4,5],
        "can_choose_live_stop":False
    },ensure_ascii=False))
    return 0

if __name__=="__main__":raise SystemExit(main())
