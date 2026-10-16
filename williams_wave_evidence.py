"""Causal, non-executable visual evidence for Elliott/Williams wave narratives.

The patterns are hypotheses, not entry signals.  The source of structural
constraints is the user-provided wave-count memo (after D. Vozny).  Alligator,
AO and AC remain separate Williams evidence; no entry permission is granted.

Only already-confirmed pivot centers and already-closed AO readings may enter.
"""

from __future__ import annotations

import math
from typing import Any, Sequence


def _num(value: Any) -> float | None:
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (TypeError, ValueError):
        return None


def _node(pivot: Any) -> dict[str, Any] | None:
    kind = str(getattr(pivot, "kind", "")).upper()
    price = _num(getattr(pivot, "price", None))
    center = getattr(pivot, "center_index", None)
    confirmed = getattr(pivot, "confirmed_index", None)
    if kind not in {"UP", "DOWN"} or price is None or price <= 0:
        return None
    if not isinstance(center, int) or not isinstance(confirmed, int):
        return None
    if center < 0 or confirmed < center:
        return None
    return {"kind": kind, "price": price, "center_index": center,
            "confirmed_index": confirmed}


def _window(nodes: list[dict[str, Any]], pattern: list[str]) -> list[dict[str, Any]]:
    """Pick the most recently *completed* alternating pattern; no future pivots."""
    n = len(pattern)
    for j in range(len(nodes) - n, -1, -1):
        part = nodes[j:j + n]
        if [p["kind"] for p in part] != pattern:
            continue
        if all(part[k]["center_index"] < part[k + 1]["center_index"]
               for k in range(n - 1)):
            return part
    return []


def impulse_evidence(pivots: Sequence[Any], direction: str) -> dict[str, Any]:
    """Validate a six-pivot impulse skeleton, without claiming 5-3-5 interiors.

    An overlapping W4 is a possible diagonal, not a valid ordinary impulse.
    A truncated fifth wave is allowed; W3 may not be the shortest motive leg.
    """
    direction = str(direction).upper()
    if direction not in {"UP", "DOWN"}:
        return {"status": "UNKNOWN", "model": "NONE", "reasons": ["No directional context"]}
    nodes = [n for item in pivots if (n := _node(item)) is not None]
    first, second = (("DOWN", "UP") if direction == "UP" else ("UP", "DOWN"))
    part = _window(nodes, [first, second, first, second, first, second])
    if not part:
        return {"status": "INCOMPLETE", "model": "UNCLASSIFIED",
                "reasons": ["Six confirmed alternating pivots not available"]}
    sign = 1 if direction == "UP" else -1
    p = [sign * x["price"] for x in part]
    motive = [p[1] - p[0], p[3] - p[2], p[5] - p[4]]
    fatal = []
    if motive[0] <= 0 or motive[1] <= 0 or motive[2] <= 0:
        fatal.append("Motive leg fails directional progression")
    if p[2] < p[0]:
        fatal.append("W2 breaches W1 origin")
    if p[3] <= p[1]:
        fatal.append("W3 fails to extend beyond W1")
    if motive[1] < min(motive[0], motive[2]):
        fatal.append("W3 is shortest motive wave")
    if p[4] < p[2]:
        fatal.append("W4 breaches origin of W3; even a diagonal is invalid")
    overlaps = p[4] < p[1]
    truncated = p[5] <= p[3]
    if fatal:
        status, model = "INVALID", "NONE"
        reasons = fatal
    elif overlaps:
        status, model = "ALTERNATIVE", "DIAGONAL_CANDIDATE"
        reasons = ["W4 overlaps W1: ordinary impulse invalid; diagonal interiors unverified"]
    else:
        status, model = "CANDIDATE", "IMPULSE_CANDIDATE"
        reasons = ["Topological rules pass, but subwave interiors and wave degree remain unverified"]
    if truncated:
        reasons.append("Truncated fifth possible: W5 does not exceed W3 extreme")
    return {
        "status": status, "model": model, "direction": direction,
        "reasons": reasons, "wave_3_not_shortest": motive[1] >= min(motive[0], motive[2]),
        "wave_4_overlaps_wave_1": overlaps, "wave_5_truncated": truncated,
        "motive_lengths": [round(v, 12) for v in motive],
        "anchor_center_indices": [x["center_index"] for x in part],
        "confirmed_through_index": max(x["confirmed_index"] for x in part),
        "is_entry_signal": False,
    }


def abc_evidence(pivots: Sequence[Any]) -> dict[str, Any]:
    """Find a recent three-leg A-B-C *candidate*, never assert internals."""
    nodes = [n for item in pivots if (n := _node(item)) is not None]
    possibilities = []
    for first, second, direction in (("UP", "DOWN", "DOWN"), ("DOWN", "UP", "UP")):
        part = _window(nodes, [first, second, first, second])
        if part:
            possibilities.append((part[-1]["confirmed_index"], part, direction))
    if not possibilities:
        return {"status": "INCOMPLETE", "model": "UNCLASSIFIED"}
    _, part, direction = max(possibilities, key=lambda x: x[0])
    sign = 1 if direction == "UP" else -1
    p = [sign * n["price"] for n in part]
    a = p[1] - p[0]
    b = p[2] - p[1]
    c = p[3] - p[2]
    if a <= 0 or b >= 0 or c <= 0:
        return {"status": "INVALID", "model": "NONE",
                "reasons": ["A-B-C directional legs do not alternate"]}
    retracement = abs(b) / a
    return {
        "status": "ALTERNATIVE", "model": "ABC_UNRESOLVED",
        "direction": direction, "b_retracement_of_a": round(retracement, 5),
        "possible_models": (["FLAT", "ZIGZAG", "COMBINATION"] if retracement >= 0.9
                            else ["ZIGZAG", "COMBINATION", "FLAT"]),
        "reasons": ["Overlapping alternating pivots do not prove ABC completion; cannot distinguish a zigzag, flat or combination without internal degrees"],
        "anchor_center_indices": [n["center_index"] for n in part],
        "confirmed_through_index": part[-1]["confirmed_index"],
        "is_entry_signal": False,
    }



def impulse_correction_evidence(pivots: Sequence[Any], direction: str) -> dict[str, Any]:
    """Describe a possible 1-2-3-4-5 -> A-B-C transition.

    A six-pivot impulse *skeleton* is required first. Each additional A/B/C
    turning point must already be confirmed. Internal wave degrees are not
    verified, and 50% retracements are measured, never imposed as hard rules.
    """
    direction = str(direction).upper()
    if direction not in {"UP", "DOWN"}:
        return {"status": "UNKNOWN", "stage": "NONE", "is_entry_signal": False}
    qualified = [(item, n) for item in pivots
                 if (n := _node(item)) is not None]
    first, second = (("DOWN", "UP") if direction == "UP"
                     else ("UP", "DOWN"))
    sequence = [first, second, first, second, first, second,
                first, second, first]
    sign = 1 if direction == "UP" else -1
    for start in range(len(qualified) - 6, -1, -1):
        remaining = len(qualified) - start
        # Old formations must not masquerade as the current A/B/C scenario.
        if remaining > 9:
            continue
        part = qualified[start:]
        nodes = [n for _, n in part]
        if [n["kind"] for n in nodes] != sequence[:remaining]:
            continue
        if any(nodes[k]["center_index"] >= nodes[k + 1]["center_index"]
               for k in range(len(nodes) - 1)):
            continue
        base = impulse_evidence([item for item, _ in part[:6]], direction)
        # Diagonal interiors are not verified by the six-pivot skeleton;
        # never promote a diagonal alternative into a post-impulse ABC count.
        if base["status"] != "CANDIDATE":
            continue
        prices = [n["price"] for n in nodes]
        p = [price * sign for price in prices]
        if remaining >= 7 and not p[6] < p[5]:
            continue
        if remaining >= 8 and not p[7] > p[6]:
            continue
        if remaining >= 9 and not p[8] < p[7]:
            continue
        denominator = p[3] - p[2]
        if denominator <= 0:
            continue
        after_impulse = remaining - 6
        stages = (
            "W5_CANDIDATE", "A_PIVOT_CONFIRMED",
            "B_PIVOT_CONFIRMED", "C_PIVOT_CONFIRMED"
        )
        output: dict[str, Any] = {
            "status": "STRUCTURAL_HYPOTHESIS",
            "direction_of_preceding_impulse": direction,
            "stage": stages[after_impulse],
            "ordinary_impulse_or_diagonal": base["model"],
            "wave4_retracement_of_wave3": round((p[3] - p[4]) / denominator, 6),
            "wave2_level": prices[2],
            "wave4_level": prices[4],
            "wave5_level": prices[5],
            "confirmed_through_index": nodes[-1]["confirmed_index"],
            "anchor_center_indices": [n["center_index"] for n in nodes],
            "internal_subwaves_verified": False,
            "ratios_are_filters": False,
            "is_entry_signal": False,
            "can_place_orders": False,
        }
        if after_impulse >= 1:
            output["a_level"] = prices[6]
        if after_impulse >= 2:
            a_size = p[5] - p[6]
            if a_size <= 0:
                continue
            output["b_level"] = prices[7]
            output["b_retracement_of_a"] = round((p[7] - p[6]) / a_size, 6)
            output["b_exceeds_wave5_level"] = p[7] >= p[5]
        if after_impulse >= 3:
            output["c_level"] = prices[8]
            output["c_size_relative_to_a"] = round((p[7] - p[8]) / a_size, 6)
            output["c_nearby_reference_levels"] = {
                "wave2": prices[2], "wave4": prices[4]
            }
        return output
    return {
        "status": "INCOMPLETE", "stage": "NONE",
        "reason": "No causally confirmed impulse-to-ABC skeleton",
        "is_entry_signal": False, "can_place_orders": False
    }


def ao_divergence_evidence(pivots: Sequence[Any], ao: Sequence[Any], direction: str) -> dict[str, Any]:
    """Compare price swing extremes to AO *leg* peaks/troughs, not same-bar AO.

    The momentum peak may precede the price extreme, as in the annotated M15
    screenshot. This routine never asks for bars beyond the confirmed pivot.
    """
    direction = str(direction).upper()
    if direction not in {"UP", "DOWN"}:
        return {"status": "UNKNOWN", "divergence": "NONE"}
    nodes = [n for item in pivots if (n := _node(item)) is not None]
    extremum = "UP" if direction == "UP" else "DOWN"
    swing = [i for i, n in enumerate(nodes) if n["kind"] == extremum]
    if len(swing) < 2:
        return {"status": "INSUFFICIENT_PIVOTS", "divergence": "NONE"}
    valid = []
    for ix in swing:
        if ix == 0 or nodes[ix - 1]["kind"] == extremum:
            continue
        start, end = nodes[ix - 1]["center_index"], nodes[ix]["center_index"]
        confirmed = nodes[ix]["confirmed_index"]
        if not (0 <= start < end < len(ao) and confirmed < len(ao)):
            continue
        vals = [_num(v) for v in ao[start + 1:end + 1]]
        vals = [v for v in vals if v is not None]
        if not vals:
            continue
        peak = max(vals) if direction == "UP" else min(vals)
        valid.append((nodes[ix], peak))
    if len(valid) < 2:
        return {"status": "INSUFFICIENT_AO", "divergence": "NONE"}
    (old, old_ao), (new, new_ao) = valid[-2:]
    if direction == "UP":
        price_extends = new["price"] > old["price"]
        momentum_weakens = new_ao < old_ao
        divergence = "BEARISH"
        zero_side = old_ao > 0 and new_ao > 0
    else:
        price_extends = new["price"] < old["price"]
        momentum_weakens = new_ao > old_ao
        divergence = "BULLISH"
        zero_side = old_ao < 0 and new_ao < 0
    identified = price_extends and momentum_weakens
    return {
        "status": "DETECTED" if identified else "NOT_DETECTED",
        "divergence": divergence if identified else "NONE",
        "strength": "CLEAR_ZERO_SIDE" if identified and zero_side else "WEAK_OR_MIXED",
        "price_extends": price_extends, "momentum_weakens": momentum_weakens,
        "price_extremes": [old["price"], new["price"]],
        "ao_leg_extremes": [round(old_ao, 12), round(new_ao, 12)],
        "anchor_center_indices": [old["center_index"], new["center_index"]],
        "confirmed_through_index": max(old["confirmed_index"], new["confirmed_index"]),
        "is_entry_signal": False,
    }


def collect_visual_wave_evidence(pivots: Sequence[Any], ao: Sequence[Any], direction: str) -> dict[str, Any]:
    # Causal guard independent of the caller: a pivot whose confirmation bar
    # is not contained in the supplied CLOSED-candle sequence is invisible.
    confirmed_pivots = [pivot for pivot in pivots
                        if (node := _node(pivot)) is not None
                        and node["confirmed_index"] < len(ao)]
    return {
        "impulse": impulse_evidence(confirmed_pivots, direction),
        "impulse_up": impulse_evidence(confirmed_pivots, "UP"),
        "impulse_down": impulse_evidence(confirmed_pivots, "DOWN"),
        "abc": abc_evidence(confirmed_pivots),
        "impulse_correction_up": impulse_correction_evidence(confirmed_pivots, "UP"),
        "impulse_correction_down": impulse_correction_evidence(confirmed_pivots, "DOWN"),
        "ao_divergence": ao_divergence_evidence(confirmed_pivots, ao, direction),
        # Counter-trend exhaustion evidence must not disappear when the
        # Alligator has already switched direction during a reversal.
        "ao_divergence_bearish": ao_divergence_evidence(confirmed_pivots, ao, "UP"),
        "ao_divergence_bullish": ao_divergence_evidence(confirmed_pivots, ao, "DOWN"),
        "authority": "DIAGNOSTIC_ONLY",
        "origin": "VOZNY_MEMO_PLUS_WILLIAMS_AO_ENGINEERING",
        "can_place_orders": False,
    }
