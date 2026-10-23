"""Independent wave-label benchmarking. Never conflate replay causality with wave accuracy.

Source classes:
- INDEPENDENT_HUMAN_OHLC: label of *frozen* historical candle prefix by an
  identified independent reviewer, with raw input checksum.
- ENGINE_DISCOVERED / BOOK_SCHEMATIC / SYNTHETIC_CONTRACT: NOT accuracy truth.

No benchmark score is released as independently accepted solely by this module:
human validation of chart markups and out-of-sample sampling is still required.
No exchange or order access is present.
"""
from __future__ import annotations
import re
from collections import Counter
from typing import Any

LABELS = ("MOTIVE_FIVE", "CORRECTION_ABC", "COMPLEX_CORRECTION", "NO_VALID_COUNT")
ABSTAIN = "UNRESOLVED"
HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")
INDEPENDENT = "INDEPENDENT_HUMAN_OHLC"

def evaluate_wave_label_quality(
    annotations: list[dict[str, Any]], predictions: list[dict[str, Any]],
    *, min_cases: int = 30, min_symbols: int = 5
) -> dict[str, Any]:
    """Compare frozen *external* annotations to predictions at same time/series.

    Invariants: no future-known prediction, no merged prediction+reference source,
    no silent aggregation of synthetic/book illustrations into live accuracy.
    Missing labels, missing prediction and ambiguous predictions are visible.
    """
    if min_cases < 1 or min_symbols < 1:
        raise ValueError("INVALID_ACCEPTANCE_THRESHOLDS")
    keyed = {}
    invalid_predictions = []
    for p in predictions:
        key = (p.get("symbol"), p.get("frame"), p.get("as_of"))
        if key in keyed:
            invalid_predictions.append({"key":key, "reason":"DUPLICATE_PREDICTION"})
            continue
        keyed[key] = p
    excluded = Counter()
    mismatches = []
    confusion = Counter()
    eligible = 0
    comparable = 0
    abstained = 0
    symbols = set()
    seen_annotations = set()
    for a in annotations:
        provenance = a.get("label_origin","")
        if provenance != INDEPENDENT:
            excluded[provenance or "MISSING_LABEL_PROVENANCE"] += 1
            continue
        key = (a.get("symbol"),a.get("frame"),a.get("as_of"))
        if key in seen_annotations:
            mismatches.append({"key":key,"reason":"DUPLICATE_REFERENCE"})
            continue
        seen_annotations.add(key)
        p = keyed.get(key)
        if p is None:
            mismatches.append({"key":key,"reason":"MISSING_PREDICTION"})
            continue
        digest=a.get("raw_ohlc_sha256")
        frozen=a.get("annotation_frozen_at_ms")
        decision=a.get("as_of")
        pred_time=p.get("prediction_run_at_ms")
        pred_known=p.get("last_signal_known_at")
        if (a.get("label") not in LABELS or p.get("label") not in (*LABELS,ABSTAIN)
            or not isinstance(decision,int) or isinstance(decision,bool) or decision<=0
            or not isinstance(frozen,int) or isinstance(frozen,bool)
            or not isinstance(pred_time,int) or isinstance(pred_time,bool)
            or frozen<=0 or frozen>=pred_time
            or not isinstance(pred_known,int) or isinstance(pred_known,bool)
            or pred_known<0 or pred_known>decision
            or not isinstance(digest,str) or not HEX64.fullmatch(digest)
            or digest.lower()!=str(p.get("raw_ohlc_sha256","")).lower()
            or not isinstance(a.get("independent_reviewer_id"),str)
            or len(a["independent_reviewer_id"].strip())<3):
            mismatches.append({"key":key,"reason":"INVALID_OR_NONBLIND_REFERENCE_OR_FUTURE_LEAK"})
            continue
        eligible+=1
        symbols.add(a["symbol"])
        if p["label"]==ABSTAIN:
            abstained+=1
            continue
        comparable+=1
        confusion[(a["label"],p["label"])]+=1
    correct=sum(v for (gold,got),v in confusion.items() if gold==got)
    coverage=comparable/eligible if eligible else None
    selective_accuracy=correct/comparable if comparable else None
    complete_accuracy=correct/eligible if eligible else None
    status=("NOT_MEASURABLE_NO_INDEPENDENT_LABELS" if eligible==0
            else "INSUFFICIENT_INDEPENDENT_LABELS" if eligible<min_cases or len(symbols)<min_symbols
            else "ELIGIBLE_MEASUREMENT_REQUIRES_HUMAN_SIGNOFF")
    return {"schema":"WILLIAMS_INDEPENDENT_WAVE_LABEL_AUDIT_V1",
      "reference_scope":"INDEPENDENT_ANNOTATED_HISTORICAL_CLOSED_OHLC",
      "status":status,"required_cases":min_cases,"required_symbols":min_symbols,
      "independent_eligible":eligible,"independent_symbols":len(symbols),
      "classified":comparable,"abstained":abstained,
      "coverage":coverage,"selective_accuracy":selective_accuracy,
      "complete_accuracy":complete_accuracy,
      "confusion":[{"reference":g,"predicted":p,"count":n} for (g,p),n in sorted(confusion.items())],
      "excluded_origins":dict(sorted(excluded.items())),
      "invalid_references":mismatches,"invalid_predictions":invalid_predictions,
      "accuracy_certified":False,"profitable_trading_certified":False,
      "device_demo_accepted":False,
      "can_place_orders":False,"can_close":False,"can_reverse":False}
