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
    if next_stage not in STAGES or NEXT.get(state.stage or None) != next_stage:
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
    if NEXT.get(state.stage or None) != proposal.stage or not proposal.idempotency_key:
        raise ValueError("STALE_COURSE_PROPOSAL")
    if (confirmed_delta != proposal.signed_unit_delta or
        new_confirmed_units != state.confirmed_units+confirmed_delta or
        new_confirmed_units != proposal.target_units or new_confirmed_units < 0 or
        signal_close_ms <= state.last_signal_close_ms):
        raise ValueError("FILL_QUANTITY_OR_TIME_MISMATCH")
    return replace(state,stage=proposal.stage,confirmed_units=new_confirmed_units,
                   revision=state.revision+1,last_signal_close_ms=signal_close_ms,
                   processed_fill_ids=(*state.processed_fill_ids,fill_id))
