"""Deterministic, non-executable Profitunity Course campaign plan.

Trading Chaos Course Level III pp. 77-84 illustrates
3 -> 1 -> 6 -> 10 -> 3 -> 6 -> 0 contracts over wave stages.
These values are NOT fixed lot sizes or TC2's 1:5:4:3:2 budget weights.
All units below are abstract risk-budget slots, never Binance quantity.
"""
from __future__ import annotations
from dataclasses import dataclass, asdict, replace
import hashlib

STAGES = ("W1", "W2", "W3", "W3_EXTENSION", "W4", "W5", "EXIT")
TARGETS = {"W1": 3, "W2": 1, "W3": 6, "W3_EXTENSION": 10,
           "W4": 3, "W5": 6, "EXIT": 0}
NEXT = {None: "W1", "W1": "W2", "W2": "W3", "W3": "W3_EXTENSION",
        "W3_EXTENSION": "W4", "W4": "W5", "W5": "EXIT"}

@dataclass(frozen=True)
class CourseState:
    campaign_id: str
    side: str
    stage: str = ""
    confirmed_units: int = 0
    revision: int = 0
    last_signal_close_ms: int = 0
    processed_fill_ids: tuple[str, ...] = ()

    def to_dict(self) -> dict:
        return asdict(self)

@dataclass(frozen=True)
class CourseTransition:
    status: str
    stage: str
    action: str
    signed_unit_delta: int
    target_units: int
    idempotency_key: str = ""
    reason: str = ""
    can_place_orders: bool = False

    def to_dict(self) -> dict:
        return asdict(self)

def course_plan(
    state: CourseState, next_stage: str, signal_close_ms: int, *,
    wave_confirmed: bool, exchange_reconciled: bool, protection_verified: bool,
    entry_or_add_risk_admitted: bool, allowed_risk_slots: int = 10,
    max_signal_age_ms: int = 18_000_000, now_ms: int
) -> CourseTransition:
    """Sequential read-only stage admission; never submits an exchange order."""
    def blocked(reason: str) -> CourseTransition:
        return CourseTransition("BLOCKED", next_stage, "NONE", 0,
                                state.confirmed_units, reason=reason)
    if (not state.campaign_id or state.side not in ("LONG", "SHORT") or
        state.stage not in ("",)+STAGES or state.confirmed_units < 0 or
        state.revision < 0 or not isinstance(signal_close_ms,int) or
        not isinstance(now_ms,int)):
        return blocked("INVALID_STATE")
    # The illustrated W3_EXTENSION is optional; direct W3 -> W4 is valid.
    if (next_stage not in STAGES or
        (NEXT.get(state.stage or None) != next_stage and
         not (state.stage=="W3" and next_stage=="W4"))):
        return blocked("NON_SEQUENTIAL_STAGE")
    if not (0 < signal_close_ms <= now_ms and
            now_ms-signal_close_ms <= max_signal_age_ms and
            signal_close_ms > state.last_signal_close_ms):
        return blocked("SIGNAL_NOT_CLOSED_FRESH_AND_NEW")
    if not wave_confirmed:
        return blocked("WAVE_DEGREE_UNCONFIRMED")
    if not exchange_reconciled:
        return blocked("EXCHANGE_RECONCILIATION_REQUIRED")
    target = TARGETS[next_stage]
    if allowed_risk_slots < 0 or (target > allowed_risk_slots and target > state.confirmed_units):
        return blocked("RISK_SLOT_CAP")
    delta = target-state.confirmed_units
    if delta != 0 and not protection_verified:
        return blocked("PROTECTIVE_STOP_NOT_VERIFIED")
    if delta > 0 and not entry_or_add_risk_admitted:
        return blocked("RISK_GATE_BLOCKS_NEW_EXPOSURE")
    action = "ADD" if delta > 0 else "REDUCE" if delta < 0 else "HOLD"
    nonce = f"{state.campaign_id}|{state.side}|{next_stage}|{signal_close_ms}|{state.revision}"
    key = "COURSE_"+hashlib.sha256(nonce.encode()).hexdigest()[:24]
    return CourseTransition("PROPOSED",next_stage,action,delta,target,key,
                            "COURSE_LEVEL_III_ILLUSTRATIVE_STAGE_NOT_EXECUTABLE")

def acknowledge_course_fill(
    state: CourseState, proposal: CourseTransition, *,
    fill_id: str, confirmed_delta: int, new_confirmed_units: int,
    signal_close_ms: int, exchange_reconciled: bool,
    protective_stop_verified: bool
) -> CourseState:
    """Acknowledge verified fills only; replaying the same fill is idempotent."""
    if not fill_id or proposal.status != "PROPOSED" or proposal.can_place_orders:
        raise ValueError("UNVERIFIABLE_COURSE_FILL")
    if fill_id in state.processed_fill_ids:
        return state
    if not exchange_reconciled or not protective_stop_verified:
        raise ValueError("RECONCILIATION_OR_PROTECTION_MISSING")
    allowed = (NEXT.get(state.stage or None)==proposal.stage or
               (state.stage=="W3" and proposal.stage=="W4"))
    expected_nonce = f"{state.campaign_id}|{state.side}|{proposal.stage}|{signal_close_ms}|{state.revision}"
    expected_key = "COURSE_"+hashlib.sha256(expected_nonce.encode()).hexdigest()[:24]
    delta = TARGETS.get(proposal.stage, -1) - state.confirmed_units
    action = "ADD" if delta > 0 else "REDUCE" if delta < 0 else "HOLD"
    if (not allowed or proposal.idempotency_key != expected_key or
        proposal.target_units != TARGETS.get(proposal.stage) or
        proposal.signed_unit_delta != delta or proposal.action != action):
        raise ValueError("STALE_OR_FOREIGN_COURSE_PROPOSAL")
    if (confirmed_delta != proposal.signed_unit_delta or
        new_confirmed_units != state.confirmed_units+confirmed_delta or
        new_confirmed_units != proposal.target_units or new_confirmed_units < 0 or
        signal_close_ms <= state.last_signal_close_ms):
        raise ValueError("FILL_QUANTITY_OR_TIME_MISMATCH")
    return replace(state,stage=proposal.stage,confirmed_units=new_confirmed_units,
                   revision=state.revision+1,last_signal_close_ms=signal_close_ms,
                   processed_fill_ids=(*state.processed_fill_ids,fill_id))


def course_active_context(report,side,origin_at=None):
    """Pin a unique real H1 motive origin; competing degrees remain unresolved."""
    direction={"BUY":"UP","LONG":"UP","SELL":"DOWN","SHORT":"DOWN"}.get(side)
    if not direction or report is None or "1h" in report.frame_errors:return None
    candidates=[s for s in report.scenarios if s.frame=="1h" and s.status=="PROVISIONAL" and s.direction==direction
                and s.active_label in {"W1","W2","W3","W4","W5","A","C"}
                and (origin_at is None or s.anchors[0].extreme_at==origin_at)]
    if origin_at is None:candidates=[s for s in candidates if s.active_label in {"W1","W3","W5"}]
    if len(candidates)!=1:return None
    h=candidates[0]
    return {"origin_at":h.anchors[0].extreme_at,"phase":h.active_label,"scenario_id":h.scenario_id,
            "known_at":h.anchors[-1].known_at,"observed_at":h.observed_at}


def course_next_stage(stage,phase,new_formation=False):
    """Explicit Course example ratio policy; quantities always pass Futures risk.

    W3 expansion is optional, so a directly observed W4 may reduce either
    W3 or W3_EXTENSION. Wave labels cannot cause a full exit by themselves.
    """
    if stage=="W1" and phase=="W2":return "W2"
    if stage=="W2" and phase=="W3" and new_formation:return "W3"
    if stage=="W3" and phase=="W3" and new_formation:return "W3_EXTENSION"
    if stage in {"W3","W3_EXTENSION"} and phase=="W4":return "W4"
    if stage=="W4" and phase=="W5" and new_formation:return "W5"
    return None
