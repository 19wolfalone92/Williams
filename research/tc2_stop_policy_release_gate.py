"""Conservative non-executable TC2 3/4/5 stop selection evidence gate.

Reads BOTH frozen historic reports, validates their common-cohort integrity,
and explicitly identifies period/horizon/model instability. It must NEVER
choose a new production Binance STOP. Book TC2 PDF pp.161 and 165 allows 3–5
bars but does not certify one universal window. These are correlated,
hypothetical candidate outcomes, not independent signed broker fills.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path

WINDOWS=("3","4","5")
MODES=("6h_static","6h_trail","24h_static","24h_trail")
DIFFS=("4_minus_3","5_minus_3","5_minus_4")
PERIODS=("2025","2026")
EXPECTED_SCHEMAS={
    "2025":"TC2_FROZEN_2025_OPPOSITE_EXIT_3_4_5_READ_ONLY_V1",
    "2026":"TC2_2026_POST_EMBARGO_345_READ_ONLY_RECHECK_V1",
}

def _number(x:object)->float:
    if isinstance(x,bool) or not isinstance(x,(float,int)) or not math.isfinite(x):
        raise ValueError("Non-finite/malformed historical comparison")
    return float(x)

def verify_report(label:str,report:dict)->None:
    if not isinstance(report,dict) or report.get("schema")!=EXPECTED_SCHEMAS[label]:
        raise ValueError(f"{label}: unexpected historical report schema")
    if report.get("source_book")!="TC2_RU_PDF_161_AND_165":
        raise ValueError(f"{label}: source book/pages missing")
    if label=="2025":
        if report.get("study_is_real_fills") is not False or report.get("not_a_prospective_holdout") is not True:
            raise ValueError("2025 retrospective study falsely promoted to a real/prospective trial")
    else:
        if report.get("previously_inspected_data_not_a_new_untouched_holdout") is not True:
            raise ValueError("2026 historical replay falsely represented as untouched future data")
    for permission in ("can_place_orders","can_close","can_move_stop"):
        if report.get(permission) is not False:
            raise ValueError(f"{label}: missing/invalid no-trade authority: {permission}")
    if label=="2025":
        if report.get("book_stop_windows")!=[3,4,5]:
            raise ValueError("2025 book-approved 3/4/5 STOP set has changed")
    elif report.get("comparisons_locked_to_same_3_4_5") is not True:
        raise ValueError("2026 book-approved 3/4/5 comparison has been unlocked")
    if report.get("cost_charged_on",report.get("cost_applied_to")) not in (
        "EXIT_NOTIONAL","EXIT_NOTIONAL_NOT_PRICE_DELTA"
    ):
        raise ValueError(f"{label}: fees modeled on price DELTA or no cost proof")
    cohort=report["overall"]
    if type(cohort.get("candidate_count")) is not int or cohort["candidate_count"]<=0:
        raise ValueError(f"{label}: no candidate count")
    windows=cohort["windows"]
    if set(windows)!=set(MODES):
        raise ValueError(f"{label}: missing or additional model/horizon")
    for name,bucket in windows.items():
        matched=bucket.get("common_matched_eligible")
        if type(matched) is not int or not 0<matched<=cohort["candidate_count"]:
            raise ValueError(f"{label} {name}: no common eligible cohort")
        if set(bucket["summary_common"])!=set(WINDOWS) or set(bucket["comparisons"])!=set(DIFFS):
            raise ValueError(f"{label} {name}: source alternative missing")
        for key,summary in bucket["summary_common"].items():
            if type(summary.get("n")) is not int or summary["n"]!=matched:
                raise ValueError(f"{label} {name} {key}: mismatched cohort")
            _number(summary["mean_delta_bps"])
        for key,comparison in bucket["comparisons"].items():
            if comparison.get("matched_events")!=matched:
                raise ValueError(f"{label} {name} {key}: comparison changed sample")
            _number(comparison["mean_delta_bps"])
            for field in ("wider_is_better_count","wider_is_worse_count","earlier_stop_hits_tighter_only"):
                count=comparison[field]
                if type(count) is not int or not 0<=count<=matched:
                    raise ValueError(f"{label} {name} {key}: invalid {field}")

def _sign(value:float)->str:
    return "POSITIVE" if value>0 else "NEGATIVE" if value<0 else "ZERO"

def assess_stop_selection(reports:dict[str,dict])->dict:
    if set(reports)!=set(PERIODS):
        raise ValueError("Both chronological study periods required")
    for name in PERIODS:
        verify_report(name,reports[name])
    study={}
    for model in MODES:
        row={}
        for window in WINDOWS:
            means={p:_number(reports[p]["overall"]["windows"][model]["summary_common"][window]["mean_delta_bps"])
                    for p in PERIODS}
            row[window]={"mean_bps_by_period":means,
                         "both_periods_positive":all(v>0 for v in means.values())}
        for pair in DIFFS:
            delta={p:_number(reports[p]["overall"]["windows"][model]["comparisons"][pair]["mean_delta_bps"])
                   for p in PERIODS}
            row[pair]={"mean_difference_bps_by_period":delta,
                       "same_direction_across_periods":len({_sign(v) for v in delta.values()})==1,
                       "has_negative_period":any(v<0 for v in delta.values())}
        study[model]=row
    unstable=[f"{model}/{pair}" for model in MODES for pair in DIFFS
              if not study[model][pair]["same_direction_across_periods"]]
    bad=sorted({f"{period}/{model}/{n}" for period in PERIODS for model in MODES for n in WINDOWS
                if study[model][n]["mean_bps_by_period"][period]<=0})
    quarters=reports["2025"].get("by_quarter",{})
    months=reports["2026"].get("by_month",{})
    inperiod_sign_flips={}
    for group,segments in (("2025",quarters),("2026",months)):
        for model in MODES:
            for pair in DIFFS:
                vals=[_number(part["windows"][model]["comparisons"][pair]["mean_delta_bps"])
                      for part in segments.values()
                      if part["windows"][model]["common_matched_eligible"]>0]
                if vals and len({_sign(x) for x in vals})>1:
                    inperiod_sign_flips[f"{group}/{model}/{pair}"]=list(map(_sign,vals))
    reasons=[
      "Neither study is an untouched prospective holdout; both have been inspected.",
      "No authenticated broker-owned fill, signed Binance Demo STOP or real replacement cycle is demonstrated.",
      "The model omits real funding, exact maker/taker fees, bid/ask queue, partial fills and intra-H1 ordering.",
      "Book figures/angulation and Elliott alternatives are not independently calibrated from original chart labels.",
      "Common hypothetical candidate events are correlated within markets; their count is NOT independent trade N.",
    ]
    if unstable:reasons.append("Across-period preference reversals: "+", ".join(unstable))
    if bad:reasons.append("Nonpositive modeled performance groups: "+str(len(bad)))
    if inperiod_sign_flips:reasons.append("Within-period month/quarter reversals: "+str(len(inperiod_sign_flips)))
    return {
      "schema":"TC2_345_HISTORICAL_RELEASE_GATE_V1",
      "source":"2025 historical plus previously inspected 2026 chronological replay",
      "book_pages":[161,165],
      "status":"NOT_APPROVED_RESEARCH_ONLY",
      "selected_live_stop_window":None,
      "can_choose_live_stop":False,
      "can_change_exchange_stop":False,
      "can_place_orders":False,
      "can_close":False,
      "book_permission_is_not_statistical_evidence":True,
      "real_signed_binance_stop_cycle_verified":False,
      "entry_fee_and_funding_complete":False,
      "source_chart_labels_independently_verified":False,
      "gates_unmet":reasons,
      "overall_matched_24h_trailing_by_period":{
          name:reports[name]["overall"]["windows"]["24h_trail"]["common_matched_eligible"]
          for name in PERIODS},
      "periods":study,
      "across_period_sign_changes":unstable,
      "nonpositive_group_count":len(bad),
      "within_period_sign_reversals":inperiod_sign_flips,
      "explanation_ru":(
          "Торговый хаос 2 допускает STOP за 3, 4 или 5 барами H1, однако "
          "историческое превосходство зависит от года, режима переноса, горизонта "
          "и биржевых расходов. В 2025 и исследованной части 2026 найдены "
          "разнонаправленные результаты. Проверки не являются реальными "
          "закрытыми защищёнными сделками Binance, поэтому автоматический "
          "выбор «лучшего» STOP запрещён. Действующий защищённый STOP не изменяется."
      ),
      "source_commit":os.getenv("GITHUB_SHA","LOCAL_AUDIT"),
    }

def main()->int:
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--report-2025",type=Path,required=True)
    p.add_argument("--report-2026",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    args=p.parse_args()
    result=assess_stop_selection({
        "2025":json.loads(args.report_2025.read_text()),
        "2026":json.loads(args.report_2026.read_text()),
    })
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2,allow_nan=False)+"\n")
    print(json.dumps({"status":result["status"],
        "matched":result["overall_matched_24h_trailing_by_period"],
        "period_sign_reversals":result["across_period_sign_changes"],
        "can_change_exchange_stop":False},ensure_ascii=False))
    return 0

if __name__=="__main__":
    raise SystemExit(main())
