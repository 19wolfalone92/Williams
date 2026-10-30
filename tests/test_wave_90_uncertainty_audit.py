"""Regression gates: explain unknown wave counts without laundering accuracy."""
from dataclasses import replace

import pytest

from tools.wave_h4_causal_research import complete_h4_from_closed_h1, h4_research_context

from tools.audit_wave_90_uncertainty import (
    CUTOFFS, HOUR, audit, phase_evidence,
)
from tools.benchmark_blind_30_h1_wave_cases import load_source
from williams_wave_hierarchy import WaveAnchor
from williams_wave_subdivisions import prove_wave_subdivisions


def test_90_closed_blind_prefixes_and_evidence_keep_expert_accuracy_unknown():
    blind, cases, summary = audit()
    assert len(CUTOFFS) == 6
    assert len(blind) == len(cases) == summary["snapshots"] == 90
    assert len({(c["symbol"], c["as_of"]) for c in blind}) == 90
    assert all(set(c) == {"symbol", "frame", "as_of",
                          "source_window_sha256", "prefix_sha256"} for c in blind)
    assert summary["classifier_abstentions"] == sum(
        c["model_label"] == "UNRESOLVED" for c in cases)
    assert summary["extended_history_classifier_abstentions"] == sum(
        c["extended_history_model_label"] == "UNRESOLVED" for c in cases)
    assert summary["history_lookback_label_disagreements"] == sum(
        c["extended_history_model_label"] != c["model_label"] for c in cases)
    assert all(c["extended_closed_h1_count"] >= 160
               and not c["extended_history_phase_evidence"]["can_place_orders"]
               for c in cases)
    assert all("reconstructed_closed_h4_context" in c and
               not c["reconstructed_closed_h4_context"]["can_place_orders"]
               for c in cases)
    assert not summary["native_binance_h4_klines_used"]
    assert summary["human_expert_annotations"] == 0
    assert summary["accuracy_vs_human"] is None
    assert not summary["human_elliott_accuracy_certified"]
    assert all(c["expert_label"] is None
               and c["phase_evidence"]["independent_human_elliott_count_verified"] is False
               and not c["can_place_orders"]
               and not c["phase_evidence"]["can_close"]
               and not c["phase_evidence"]["can_reverse"]
               for c in cases)


def test_phase_only_uses_closed_prefix_and_survives_unknown_future_prices():
    full, _ = load_source("BTCUSDT")
    as_of = int(CUTOFFS[4].timestamp() * 1000)
    prefix = [r for r in full if r[0] + HOUR <= as_of][-160:]
    baseline = phase_evidence(prefix, as_of)
    # The only allowed bars have already closed; changing future source rows
    # cannot affect the same 160-element historical prefix.
    changed = [list(r) for r in full]
    for row in changed:
        if row[0] + HOUR > as_of:
            row[1:5] = ["1", "999999", "1", "1"]
    poisoned_prefix = [r for r in changed if r[0] + HOUR <= as_of][-160:]
    assert phase_evidence(poisoned_prefix, as_of) == baseline
    assert baseline["candidate_count"] >= 0
    assert not baseline["independent_human_elliott_count_verified"]
    assert not baseline["can_place_orders"]


def test_failed_later_subdivision_retains_only_previous_causal_evidence():
    h = 3_600_000
    prices = [150, 135, 142, 120, 130, 110, 123, 118,
              130, 110, 120, 90, 100, 80]
    times = [30, 31, 32, 34, 35, 36, 38, 40, 42, 43, 44, 46, 47, 48]
    for mirror in (False, True):
        children = [
            WaveAnchor(("HIGH" if i % 2 == 0 else "LOW")
                       if not mirror else ("LOW" if i % 2 == 0 else "HIGH"),
                       (300 - p if mirror else p), i, t * h, (t + 3) * h)
            for i, (p, t) in enumerate(zip(prices, times))
        ]
        parents = [replace(children[i], known_at=children[i].extreme_at + 18 * h)
                   for i in (0, 5, 8, 13)]
        full = prove_wave_subdivisions(parents, children, (5, 3, 5),
                                       parent_duration=6 * h, as_of=80 * h)
        assert full.status == "PRICE_SUBDIVISIONS_CONFIRMED"
        assert len(full.leg_patterns) == 3
        # W5 fails its own price geometry; W1 and W2 remain observed facts.
        # Change an INTERIOR W5 pivot: parent/child endpoint matching must
        # still succeed, while W5 impulse geometry independently fails.
        damaged = (children[:11] +
                   [replace(children[11], price=(160 if mirror else 140))] +
                   children[12:])
        partial = prove_wave_subdivisions(parents, damaged, (5, 3, 5),
                                         parent_duration=6 * h, as_of=80 * h)
        assert partial.status == "UNRESOLVED"
        assert partial.reason == "INTERNAL_IMPULSE_GEOMETRY_FAILED"
        assert partial.observed == (5, 3, 5)
        assert partial.leg_patterns == full.leg_patterns[:2]


def test_complete_utc_h4_requires_four_genuine_closed_hours_and_never_future():
    h=HOUR
    rows=[
        [0,100,105,98,102,10],
        [h,102,106,99,104,11],
        [2*h,104,107,101,103,12],
        [3*h,103,108,100,106,13],
    ]
    h4=complete_h4_from_closed_h1(rows,4*h)
    assert len(h4)==1
    assert (h4[0].t,h4[0].o,h4[0].h,h4[0].l,h4[0].c,h4[0].v)==(
        0,100,108,98,106,46)
    # A fresh (unclosed) or otherwise future hour cannot mutate H4.
    future=[4*h,999,1000,990,998,2]
    assert complete_h4_from_closed_h1(rows+[future],4*h)==h4
    assert complete_h4_from_closed_h1(rows[:3],3*h)==[]
    with pytest.raises(ValueError,match="GAPPED_OR_UNORDERED_SOURCE_H1"):
        complete_h4_from_closed_h1(rows[:2]+rows[3:],4*h)


def test_reconstructed_h4_is_causal_context_not_independent_native_h4():
    rows,_=load_source("BTCUSDT")
    as_of=int(CUTOFFS[3].timestamp()*1000)
    prefix=[row for row in rows if row[0]+HOUR<=as_of][-160:]
    report=h4_research_context(prefix,as_of)
    assert report["closed_h4_bars"]==40
    assert report["source"]=="UTC_H4_EXACTLY_FOUR_FROZEN_BINANCE_CLOSED_H1_NOT_NATIVE_H4"
    assert report["status"]=="PRICE_CONTEXT_ONLY_NO_NATIVE_H4_PROOF"
    assert not report["degree_of_h4_verified_by_expert"]
    assert not report["can_place_orders"] and not report["can_close"] and not report["can_reverse"]
    extra=[list(x) for x in rows if x[0]+HOUR>as_of]
    for x in extra:
        x[1:5]=["1","999999","1","1"]
    assert h4_research_context(prefix+extra,as_of)==report
