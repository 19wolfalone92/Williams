"""Auditable progress toward a 95% selected-rule test threshold.

IMPORTANT: This is a *bounded test-evidence coverage* metric, NOT a measurement
of the percent of book semantics correctly implemented. Each author-drawn
chart, subjective wave genealogy, exchange fill and device execution remains a
distinct acceptance obligation. This helper cannot authorize any order.
"""
from __future__ import annotations

import argparse
import json
from math import ceil
from pathlib import Path

BOOKS=("TC1","NTD","TC2","COURSE")
BOUNDED_STATUSES={"BOUNDED_TESTED","SOURCE_VERIFIED"}
EXCLUDED={"PROJECT_POLICY","USER_POLICY"}
ROOT=Path(__file__).resolve().parents[1]


def evaluate(registry:dict, *, target:float=95.0)->dict:
    if not (0 < target <= 100):raise ValueError("Invalid target")
    scoped=[r for r in registry.get("rules",[])
            if r.get("book") in BOOKS and r.get("status") not in EXCLUDED]
    if not scoped or len({r.get("id") for r in scoped}) != len(scoped):
        raise ValueError("Empty or duplicate book-rule scope")
    bounded=[r for r in scoped if r.get("status") in BOUNDED_STATUSES]
    source_verified=[r for r in scoped if r.get("status")=="SOURCE_VERIFIED"]
    required=ceil(len(scoped)*target/100)
    percent=round(100*len(bounded)/len(scoped),3)
    missing_figures=not bool(registry.get("all_historical_figures_digitized"))
    missing_device=not bool(registry.get("physical_android_demo_verified"))
    full_book_certified=bool(registry.get("whole_book_certified"))
    return {
        "metric":"BOUNDED_TEST_EVIDENCE_FOR_SELECTED_FORMAL_RULES_NOT_BOOK_ACCURACY",
        "target_percent":target,"scope_selected_rules":len(scoped),
        "scope_books":list(BOOKS),
        "bounded_evidence_rules":len(bounded),
        "source_verified_rule_count":len(source_verified),
        "bounded_evidence_percent":percent,
        "needed_for_95_percent":required,
        "remaining_rules_until_bounded_evidence_target":max(0,required-len(bounded)),
        "target_met":len(bounded)>=required,
        "all_source_figures_digitized":not missing_figures,
        "physical_android_demo_verified":not missing_device,
        "whole_book_certified":full_book_certified,
        "book_fidelity_95_percent_certified":False,
        "blocking_selected_rules":[{"id":r["id"],"status":r["status"],
                                    "remaining_evidence":r.get("remaining_evidence","")}
                                   for r in scoped if r not in bounded],
        "limitations":[
            "A synthetic pass is not fidelity against independent book chart labels.",
            "Bounded tests are not a physical Binance protective STOP or real fill.",
            "This report NEVER enables trading or claims full-book parity."
        ]
    }


def main()->int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry",type=Path,
        default=ROOT/"docs/williams_book_rule_registry.json")
    parser.add_argument("--output",type=Path)
    parser.add_argument("--require-95",action="store_true",
        help="nonzero until bounded selected-rule evidence reaches >=95%")
    args=parser.parse_args()
    report=evaluate(json.loads(args.registry.read_text()))
    result=json.dumps(report,ensure_ascii=False,indent=2)
    print(result)
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(result+"\n")
    return 2 if args.require_95 and not report["target_met"] else 0


if __name__=="__main__":raise SystemExit(main())
