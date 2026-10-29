#!/usr/bin/env python3
"""Audit 90 frozen real H1 wave snapshots without manufacturing Elliott truth.

This deliberately separates (1) price-based candidate observations,
(2) production classifier outputs, and (3) independent HUMAN verification.
Neither a higher diagnostic coverage nor a proxy agreement is accuracy.
No trading or exchange APIs are called.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from tools.benchmark_blind_30_h1_wave_cases import (  # noqa: E402
    ALL_15, DATES, digest, load_source, model_label,
)
from williams_wave_hierarchy import (  # noqa: E402
    WaveBar, build_wave_hierarchy,
)

HOUR = 3_600_000
# Exact pre-existing 15-pair x 6-date benchmark selection. Do not create
# a different 90 and incorrectly compare it to the 4.64.5 release count.
SYMBOLS = ALL_15
CUTOFFS = tuple(datetime.fromisoformat(t) for t in DATES)


def phase_evidence(prefix, as_of):
    """Describe all causal H1 hypotheses; NEVER turn a partial wave into W5."""
    bars = [WaveBar(int(r[0]), *map(float, r[1:6])) for r in prefix]
    report = build_wave_hierarchy({"1h": bars}, as_of, analysis_frames=("1h",))
    assert not report.frame_errors, report.frame_errors
    live = [s for s in report.scenarios
            if s.frame == "1h" and s.anchors
            and s.anchors[-1].known_at <= as_of
            and s.status in {"PROVISIONAL", "ALTERNATIVE", "UNRESOLVED"}]
    # Completion is not unique: contradictory orientations and degrees matter.
    directions = sorted({s.direction for s in live})
    scenarios = sorted(live, key=lambda s: (-len(s.anchors),
                     -s.anchors[-1].known_at, s.scenario_id))
    recent = [s for s in scenarios if as_of - s.anchors[-1].known_at <= 30 * HOUR]
    count_six = [s for s in recent if len(s.anchors) == 6 and s.label == "W5"]
    valid_w5 = [s for s in count_six if s.status == "PROVISIONAL"]
    shapes = [p for p in report.to_dict().get("complex_wave_patterns", [])
              if p.get("frame") == "1h" and p.get("known_at", as_of) <= as_of]
    evidence = [{
        "scenario_id": s.scenario_id,
        "direction": s.direction, "confirmed_last_leg": s.label,
        "developing_leg": s.active_label,
        "wave_degree_frame": s.frame, "status": s.status,
        "confirmed_pivots": len(s.anchors),
        "last_pivot_known_at": s.anchors[-1].known_at,
        "w4_ao": s.w4_zero,
        "third_momentum_status": (s.third_momentum or {}).get("status", "UNAVAILABLE"),
        "alternatives": sorted(s.alternatives),
        "invalid_reasons": sorted(s.invalid_reasons),
    } for s in scenarios[:8]]
    # A developing W2/W3/W4/W5 or ABC remains informative without becoming
    # an independent MOTIVE_FIVE / CORRECTION_ABC truth label.
    if not scenarios:
        phase = "NO_CAUSAL_PRICE_WAVE_HYPOTHESIS"
    elif len(directions) > 1:
        phase = "COMPETING_PRICE_DIRECTIONS"
    elif valid_w5:
        phase = "FIVE_LEG_PRICE_HYPOTHESIS_NOT_EXPERT_PROVEN"
    elif any(len(s.anchors) >= 4 for s in recent):
        phase = "DEVELOPING_MOTIVE_OR_CORRECTION"
    else:
        phase = "EARLY_OR_HISTORICAL_PIVOTS_ONLY"
    blockers = []
    if not recent:
        blockers.append("NO_RECENT_CONFIRMED_FRACTAL")
    if not valid_w5:
        blockers.append("NO_UNAMBIGUOUS_RECENT_PROVISIONAL_W5")
    if len(directions) > 1:
        blockers.append("CONFLICTING_WAVE_DIRECTIONS")
    if not shapes:
        blockers.append("NO_COMPLETED_CAUSAL_COMPLEX_CORRECTION_PROOF")
    if any(s.status == "ALTERNATIVE" for s in scenarios):
        blockers.append("ALTERNATIVE_PRICE_GEOMETRY")
    if any(s.status == "UNRESOLVED" for s in scenarios):
        blockers.append("DEVELOPING_LEG_NOT_YET_CONFIRMED")
    return {
        "phase": phase, "blockers": blockers,
        "candidate_count": len(scenarios), "recent_candidate_count": len(recent),
        "latest_w5_price_hypotheses": len(valid_w5),
        "complex_price_candidates": len(shapes),
        "candidate_directions": directions,
        "candidate_evidence": evidence,
        "independent_human_elliott_count_verified": False,
        "can_place_orders": False, "can_close": False, "can_reverse": False,
    }


def audit():
    """Return blind selection and AFTER-selection diagnostic data separately."""
    blind, cases = [], []
    for symbol in SYMBOLS:
        full, source_sha = load_source(symbol)
        for cutoff in CUTOFFS:
            as_of = int(cutoff.timestamp() * 1000)
            closed = [r for r in full if int(r[0]) + HOUR <= as_of]
            if len(closed) < 160:
                raise ValueError("INSUFFICIENT_FROZEN_CLOSED_H1")
            prefix = closed[-160:]
            if (len(prefix) != 160
                    or any(int(b[0]) + HOUR > as_of for b in prefix)
                    or any(int(b[0]) - int(a[0]) != HOUR
                           for a, b in zip(prefix, prefix[1:]))):
                raise ValueError("GAP_OR_FUTURE_LEAK_IN_H1_PREFIX")
            key = {"symbol": symbol, "frame": "1h", "as_of": as_of,
                   "source_window_sha256": source_sha,
                   "prefix_sha256": digest(prefix)}
            blind.append(key)
            label, label_evidence = model_label(prefix, as_of)
            # This output is strictly AFTER constructing the blind selection.
            cases.append(key | {
                "model_label": label, "model_evidence": label_evidence,
                "phase_evidence": phase_evidence(prefix, as_of),
                "expert_label": None, "expert_label_origin": "NOT_SUPPLIED",
                "human_accuracy_verified": False,
                "can_place_orders": False,
            })
    if len(blind) != 90 or len({(x["symbol"], x["as_of"]) for x in blind}) != 90:
        raise ValueError("BLIND_SELECTION_NOT_EXACTLY_90_UNIQUE_SNAPSHOTS")
    counts = Counter(c["model_label"] for c in cases)
    reasons = Counter(reason for c in cases for reason in c["phase_evidence"]["blockers"])
    phases = Counter(c["phase_evidence"]["phase"] for c in cases)
    summary = {
        "schema": "WILLIAMS_WAVE_90_OBSERVATION_AUDIT_V1",
        "status": "NO_INDEPENDENT_EXPERT_WAVE_LABELS",
        "source": "FROZEN_BINANCE_PUBLIC_USDM_FUTURES_CLOSED_H1",
        "snapshots": 90, "symbols": list(SYMBOLS),
        "classifier_counts": dict(sorted(counts.items())),
        "classifier_abstentions": counts["UNRESOLVED"],
        "descriptive_phase_counts": dict(sorted(phases.items())),
        "evidence_blocker_counts": dict(sorted(reasons.items())),
        "human_expert_annotations": 0,
        "accuracy_vs_human": None,
        "human_elliott_accuracy_certified": False,
        "partial_phase_is_not_independent_wave_classification": True,
        "can_place_orders": False, "can_close": False, "can_reverse": False,
    }
    return blind, cases, summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    blind, cases, summary = audit()
    args.output.mkdir(parents=True, exist_ok=True)
    for filename, data in (
        ("blind_90_selection.json", {"schema": "WILLIAMS_BLIND_WAVE_90_V1",
                                      "no_model_predictions_or_expert_labels": True,
                                      "snapshots": blind}),
        ("wave_90_phase_diagnostics.json", {"summary": summary, "cases": cases}),
        ("wave_90_summary.json", summary),
    ):
        (args.output / filename).write_text(
            json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
