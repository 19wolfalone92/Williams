import json
from dataclasses import replace
from pathlib import Path

import pytest

from williams_course_correction import (
    STAGES,
    TARGETS,
    CorrectionEvidence,
    CorrectionState,
    acknowledge_correction_fill,
    correction_policy_snapshot,
    plan_correction,
)

CASES = json.loads((Path(__file__).parents[1]/"app/src/test/resources/williams_course_correction_contract.json").read_text())["cases"]


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
@pytest.mark.parametrize("case", CASES, ids=lambda c:c["id"])
def test_source_correction_zones_require_causal_evidence_and_distinct_flat_owner(side, case):
    mirror = (lambda p:p) if side == "LONG" else (lambda p:200-p)
    s = CorrectionState("correction", "motive", side, 100, case["previous"], case["units"])
    e = CorrectionEvidence(mirror(120), mirror(100), mirror(80), mirror(case["price"]),
                           case.get("known_at", 200), case.get("degree", True), True,
                           case.get("a_complete", True), case.get("c_complete", True))
    p = plan_correction(s, case["stage"], e, 300, 301,
        motive_closed_and_flat=case.get("flat", True), exchange_reconciled=True,
        protection_verified=case.get("protected", True), risk_admitted=case.get("risk", True))
    assert (p.status if p.status == "PROPOSED" else p.reason) == case["expected"]
    assert p.side == ("SHORT" if side == "LONG" else "LONG")
    assert not p.can_place_orders
    if p.status == "PROPOSED":
        assert p.target_units == TARGETS[case["stage"]]
        assert p.delta == p.target_units-s.confirmed_units


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_corrective_lifecycle_requires_exact_fills_and_rejects_foreign_or_partial_replays(side):
    s = CorrectionState("correction", "motive", side, 100)
    mirror = (lambda p:p) if side == "LONG" else (lambda p:200-p)
    for i, (stage, price) in enumerate(zip(STAGES, (110, 80, 100, 60))):
        close = 300+i*100
        e = CorrectionEvidence(mirror(120), mirror(100), mirror(80), mirror(price), close-1, True, True, True, True)
        p = plan_correction(s, stage, e, close, close+1, motive_closed_and_flat=True,
            exchange_reconciled=True, protection_verified=True, risk_admitted=True)
        assert p.status == "PROPOSED"
        args = {"fill_id": f"fill-{i}", "delta": p.delta, "units": p.target_units,
                "close_ms": close, "exchange_reconciled": True, "protection_verified": True}
        for changed, proposal, kwargs in [
            (s, p, dict(args, delta=p.delta-1)),
            (replace(s, motive_campaign_id="another-motive"), p, args),
            (s, replace(p, side=s.motive_side), args),
            (s, p, dict(args, protection_verified=False)),
        ]:
            with pytest.raises(ValueError):
                acknowledge_correction_fill(changed, proposal, **kwargs)
        s = acknowledge_correction_fill(s, p, **args)
        assert acknowledge_correction_fill(s, p, **args) == s
    assert s.confirmed_units == 0 and s.revision == 4
    snapshot = correction_policy_snapshot()
    assert snapshot["targets"] == [3, 1, 4, 0] and not snapshot["can_place_orders"]


def test_actual_market_phase_exposes_separate_source_corrective_policy():
    from williams_market_phase import market_phase_context
    assert market_phase_context({})["course_correction_policy"] == correction_policy_snapshot()
