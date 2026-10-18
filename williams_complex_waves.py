"""Causal research grammar for complex Elliott/Williams wave alternatives.

A pivot is usable only after its *confirmation* time. A price skeleton never
proves its internal 5-3 / 3-3 subdivisions: consequently these are bounded,
non-executable alternatives, not certified labels or Futures order triggers.

References: Trading Chaos (1st ed), chapter 7; Trading Chaos Course Level III;
the additional corrective-pattern terminology follows Elliott wave analysis,
and must NOT be misattributed as a verbatim Williams trade rule.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from math import isfinite
from typing import Any, Sequence


@dataclass(frozen=True)
class ComplexWaveEvidence:
    pattern: str
    status: str
    direction: str
    start_at: int
    end_at: int
    known_at: int
    invalidation: float
    reasons: tuple[str, ...]
    can_place_orders: bool = False

    def to_dict(self) -> dict:
        return asdict(self)


def _read(p: Any) -> tuple[str, float, int, int] | None:
    kind = str(getattr(p, "kind", "")).upper()
    try:
        price = float(getattr(p, "price"))
        t = int(getattr(p, "extreme_at"))
        known = int(getattr(p, "known_at"))
    except (ValueError, TypeError, OverflowError, AttributeError):
        return None
    if kind not in {"LOW", "HIGH"} or not isfinite(price) or price <= 0 or t < 0 or known < t:
        return None
    return kind, price, t, known


def detect_complex_wave_patterns(
    pivots: Sequence[Any], *, direction: str, as_of: int
) -> list[ComplexWaveEvidence]:
    """Return only demonstrable outer-price geometry from known pivots.

    At least 6 pivots for a completed 5-wave impulse, 9 for an ABC
    following that impulse, or 6 for an unverified triangle candidate.
    Outside/double-extreme ambiguities must be handled before this call.
    No order authorization is produced, even on an apparent completed W5.
    """
    direction = str(direction).upper()
    if direction not in {"UP", "DOWN"} or not isinstance(as_of, int) or as_of <= 0:
        return []
    parsed = [_read(p) for p in pivots]
    if not parsed or any(x is None for x in parsed):
        return []
    pts = [p for p in parsed if p is not None]
    if any(p[3] > as_of for p in pts) or any(a[2] >= b[2] or a[3] > b[3]
                                           for a, b in zip(pts, pts[1:])):
        return []
    if any(a[0] == b[0] for a, b in zip(pts, pts[1:])):
        return []

    sign = 1 if direction == "UP" else -1
    wanted = "LOW" if sign == 1 else "HIGH"
    result: list[ComplexWaveEvidence] = []

    def add(pattern: str, block: list[tuple], reasons: tuple[str, ...], invalidation: float):
        result.append(ComplexWaveEvidence(pattern, "ALTERNATIVE", direction,
                                          block[0][2], block[-1][2],
                                          max(p[3] for p in block), invalidation,
                                          reasons))

    # Only small trailing windows to keep the algorithm bounded on history.
    for n in (6, 9):
        if len(pts) < n:
            continue
        sub = pts[-n:]
        if sub[0][0] != wanted:
            continue
        p = [sign * x[1] for x in sub]
        if n == 6:
            w1, w3, w5 = p[1]-p[0], p[3]-p[2], p[5]-p[4]
            if (w1 <= 0 or w3 <= 0 or w5 <= 0 or
                    not p[0] < p[2] < p[1] or not p[2] < p[4] < p[3] or
                    w3 < min(w1, w5)):
                continue
            if p[4] <= p[1]:
                add("ENDING_OR_LEADING_DIAGONAL_CANDIDATE", sub,
                    ("W4_OVERLAPS_W1", "INTERNAL_3_OR_5_LEGS_UNVERIFIED"), sub[2][1])
            elif p[5] <= p[3]:
                add("TRUNCATED_FIFTH_CANDIDATE", sub,
                    ("W5_DOES_NOT_EXCEED_W3", "INTERNAL_SUBWAVES_UNVERIFIED"), sub[4][1])
            else:
                add("ORDINARY_IMPULSE_CANDIDATE", sub,
                    ("FIVE_PRICE_SWINGS_ONLY", "INTERNAL_SUBWAVES_UNVERIFIED"), sub[2][1])
        else:
            # 0..5 are a six-pivot motive skeleton, 6=A, 7=B, 8=C.
            q = sub[:6]
            e1, e3, e5 = p[1]-p[0], p[3]-p[2], p[5]-p[4]
            if (e1 <= 0 or e3 <= 0 or e5 <= 0 or
                    not p[0] < p[2] < p[1] or not p[2] < p[4] < p[3] or
                    e3 < min(e1,e5)):
                continue
            # A moves against the original trend, B with it, C against.
            a, b, c = p[5]-p[6], p[7]-p[6], p[7]-p[8]
            if a <= 0 or b <= 0 or c <= 0:
                continue
            b_fraction = b/a
            if b_fraction < 0.90 and p[8] < p[6]:
                add("ZIGZAG_ABC_CANDIDATE", sub,
                    ("B_BELOW_90_PERCENT_OF_A", "INTERNAL_5_3_5_UNVERIFIED"), sub[7][1])
            elif b_fraction >= 0.90:
                kind = ("EXPANDED_FLAT_ABC_CANDIDATE"
                        if p[7] > p[5] and p[8] < p[6]
                        else "RUNNING_FLAT_ABC_CANDIDATE" if p[7] > p[5]
                        else "REGULAR_FLAT_ABC_CANDIDATE")
                add(kind, sub,
                    ("B_RETRACES_AT_LEAST_90_PERCENT_A", "INTERNAL_3_3_5_UNVERIFIED"), sub[7][1])
            # A, B and C are unknown-degree correction legs, not
            # necessarily the final correction of a larger wave.
    # Contracting symmetrical triangle: decreasing swing highs and increasing
    # swing lows across at least 5 confirmed legs. No internal 3-3-3-3-3 proof.
    if len(pts) >= 6:
        sub=pts[-6:]
        highs=[p[1] for p in sub if p[0]=="HIGH"]
        lows=[p[1] for p in sub if p[0]=="LOW"]
        if len(highs)==3 and len(lows)==3 and all(a>b for a,b in zip(highs,highs[1:])) \
                and all(a<b for a,b in zip(lows,lows[1:])) and max(lows)<min(highs):
            add("CONTRACTING_TRIANGLE_ABCDE_CANDIDATE", sub,
                ("HIGHER_LOWS_LOWER_HIGHS", "INTERNAL_3_3_3_3_3_UNVERIFIED"), sub[-2][1])
    # W-X-Y and W-X-Y-X-Z need the internal A/B/C segmentation. A
    # seven-/eleven-swing outer zigzag alone must not certify combinations.
    if len(pts) >= 8:
        sub=pts[-8:]
        add("W_X_Y_COMBINATION_UNRESOLVED", sub,
            ("INTERNAL_CORRECTION_COMPONENTS_NOT_PROVEN",), sub[0][1])
    if len(pts) >= 12:
        sub=pts[-12:]
        add("W_X_Y_X_Z_COMBINATION_UNRESOLVED", sub,
            ("INTERNAL_CORRECTION_COMPONENTS_NOT_PROVEN",), sub[0][1])
    return result
