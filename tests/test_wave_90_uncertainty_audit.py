"""Regression gates: explain unknown wave counts without laundering accuracy."""
from dataclasses import replace

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
