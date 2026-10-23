"""Course PDF83-84 corrective example, in abstract units, without order authority.

After the motive campaign is confirmed flat: opposite 3 -> 1 -> 4 -> 0.
The PDF84 phrase 'leaving 1 long' conflicts with its short sale, covering two
of three, and its diagram; the consistent residual is ONE SHORT (mirrored
for a bearish motive). These example units are never Binance quantities.
"""
from dataclasses import dataclass, replace
from hashlib import sha256
from math import isfinite

STAGES = ("A_ENTRY", "A_REDUCE", "C_ADD", "CORRECTION_EXIT")
TARGETS = dict(zip(STAGES, (3, 1, 4, 0)))


@dataclass(frozen=True)
class CorrectionState:
    campaign_id: str
    motive_campaign_id: str
    motive_side: str
    flat_confirmed_at_ms: int
    stage: str = ""
    confirmed_units: int = 0
    revision: int = 0
    last_signal_close_ms: int = 0
    fill_ids: tuple[str, ...] = ()

    @property
    def side(self):
        return "SHORT" if self.motive_side == "LONG" else "LONG"


@dataclass(frozen=True)
class CorrectionEvidence:
    motive_extreme: float
    first_a_extreme: float
    a_end: float
    price: float
    known_at_ms: int
    same_degree_confirmed: bool
    motive_five_complete: bool
    a_complete: bool = False
    c_five_complete: bool = False


@dataclass(frozen=True)
class CorrectionProposal:
    status: str
    stage: str
    side: str
    delta: int = 0
    target_units: int = 0
    key: str = ""
    reason: str = ""
    can_place_orders: bool = False


def _key(s, stage, close):
    raw = f"{s.campaign_id}|{s.motive_campaign_id}|{s.motive_side}|{s.flat_confirmed_at_ms}|{stage}|{close}|{s.revision}"
    return "COURSE_ABC_" + sha256(raw.encode()).hexdigest()[:24]


def plan_correction(s, stage, e, close_ms, now_ms, *, motive_closed_and_flat,
                    exchange_reconciled, protection_verified, risk_admitted,
                    max_units=10):
    def blocked(why):
        return CorrectionProposal("BLOCKED", stage, s.side,
                                  target_units=s.confirmed_units, reason=why)
    if (not s.campaign_id or not s.motive_campaign_id or s.campaign_id == s.motive_campaign_id
            or s.motive_side not in ("LONG", "SHORT") or s.stage not in ("",) + STAGES
            or s.confirmed_units != (TARGETS[s.stage] if s.stage else 0) or s.revision < 0):
        return blocked("INVALID_CORRECTIVE_OWNER_STATE")
    expected = STAGES[0] if not s.stage else (STAGES[STAGES.index(s.stage)+1] if s.stage != STAGES[-1] else None)
    if stage != expected:
        return blocked("NON_SEQUENTIAL_CORRECTIVE_STAGE")
    if (s.flat_confirmed_at_ms <= 0 or not motive_closed_and_flat
            or close_ms <= s.flat_confirmed_at_ms):
        return blocked("MOTIVE_FLAT_NOT_CONFIRMED_BEFORE_NEW_CAMPAIGN")
    if not (s.last_signal_close_ms < close_ms <= now_ms and now_ms-close_ms <= 18_000_000
            and s.flat_confirmed_at_ms < e.known_at_ms <= close_ms):
        return blocked("CORRECTION_EVIDENCE_NOT_CAUSAL_FRESH_AND_NEW")
    if not e.same_degree_confirmed or not e.motive_five_complete:
        return blocked("CORRECTION_WAVE_DEGREE_UNCONFIRMED")
    if not exchange_reconciled:
        return blocked("EXCHANGE_RECONCILIATION_REQUIRED")
    if not all(isfinite(x) and x > 0 for x in (e.motive_extreme, e.first_a_extreme, e.a_end, e.price)):
        return blocked("CORRECTION_PRICE_UNVERIFIED")
    sign = 1 if s.motive_side == "LONG" else -1
    first = sign * (e.motive_extreme-e.first_a_extreme)
    a_length = sign * (e.motive_extreme-e.a_end)
    if first <= 0 or a_length <= 0:
        return blocked("CORRECTION_DIRECTION_UNVERIFIED")
    if stage == "A_ENTRY":
        ratio = sign * (e.price-e.first_a_extreme)/first
        if not .5-1e-12 <= ratio <= .62+1e-12:
            return blocked("A_ENTRY_OUTSIDE_50_62_RETRACEMENT")
    elif stage in ("A_REDUCE", "C_ADD"):
        if not e.a_complete:
            return blocked("A_END_NOT_CONFIRMED")
        if stage == "C_ADD":
            ratio = sign * (e.price-e.a_end)/a_length
            if not .38-1e-12 <= ratio <= .5+1e-12:
                return blocked("B_OUTSIDE_38_50_RETRACEMENT_OF_A")
    elif not e.c_five_complete:
        return blocked("C_FIFTH_NOT_CONFIRMED")
    target = TARGETS[stage]
    delta = target-s.confirmed_units
    if not protection_verified:
        return blocked("PROTECTIVE_STOP_NOT_VERIFIED")
    if delta > 0 and (not risk_admitted or target > max_units):
        return blocked("RISK_GATE_BLOCKS_NEW_EXPOSURE")
    return CorrectionProposal("PROPOSED", stage, s.side, delta, target,
                              _key(s, stage, close_ms), "COURSE_PDF83_84_ABSTRACT_UNITS")


def acknowledge_correction_fill(s, p, *, fill_id, delta, units, close_ms,
                                exchange_reconciled, protection_verified):
    if not fill_id or p.status != "PROPOSED" or p.can_place_orders:
        raise ValueError("CORRECTION_FILL_UNVERIFIED")
    if fill_id in s.fill_ids:
        return s
    expected = STAGES[0] if not s.stage else (STAGES[STAGES.index(s.stage)+1] if s.stage != STAGES[-1] else None)
    if (not exchange_reconciled or not protection_verified or p.stage != expected
            or p.side != s.side or p.key != _key(s, p.stage, close_ms)
            or p.target_units != TARGETS.get(p.stage) or units != p.target_units
            or delta != p.delta or delta != units-s.confirmed_units
            or close_ms <= max(s.last_signal_close_ms, s.flat_confirmed_at_ms)):
        raise ValueError("STALE_FOREIGN_OR_PARTIAL_CORRECTION_FILL")
    return replace(s, stage=p.stage, confirmed_units=units, revision=s.revision+1,
                   last_signal_close_ms=close_ms, fill_ids=(*s.fill_ids, fill_id))


def correction_policy_snapshot():
    return {"source":"COURSE_PDF83_84", "stages":list(STAGES), "targets":[3,1,4,0],
            "a_entry_retracement":[.5,.62], "b_retracement_of_a":[.38,.5],
            "reverse_policy":"CLOSE_OWNED_EXPOSURE_CONFIRM_FLAT_THEN_NEW_RISK_GATES",
            "quantity_basis":"ILLUSTRATIVE_UNITS_NOT_BINANCE_LOTS",
            "source_discrepancy":"PDF84_LEAVING_1_LONG_CONTRADICTS_SHORT_ARITHMETIC",
            "can_place_orders":False}
