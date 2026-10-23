"""Course PDF77-84: causal price locations and source-pinned reductions.

Illustrative contract stages are distinct from the active Elliott leg. Price
locations and volume/oscillator proxies do not certify a wave degree or grant
new-exposure authority. No exchange mutation occurs in this module.
"""
from math import isfinite

from williams_wave_momentum import ordered_sum

H1 = 3_600_000
SOURCE = "COURSE_LEVEL_III_PDF77_84"


def _anchors(s, as_of):
    """Reject unverifiable or future pivots, including malformed chronology."""
    if not isinstance(s, dict) or s.get("status") != "PROVISIONAL":
        return None
    a = s.get("anchors", [])
    from williams_wave_hierarchy import FRAME_MS
    duration = FRAME_MS.get(s.get("frame"), 0)
    try:
        if (not duration or not a or not 0 < s["observed_at"] <= as_of or
                any(not isfinite(p["price"]) or p["price"] <= 0 or
                    not 0 < p["extreme_at"] < p["known_at"] <= s["observed_at"] or
                    p["known_at"]-p["extreme_at"] < 3*duration
                    for p in a) or
                any(x["extreme_at"] >= y["extreme_at"] or x["known_at"] >= y["known_at"] or
                    x["kind"] == y["kind"] for x, y in zip(a, a[1:]))):
            return None
        first = "LOW" if s.get("direction") == "UP" else "HIGH" if s.get("direction") == "DOWN" else ""
        if not first or any(p["kind"] != (first if i % 2 == 0 else "HIGH" if first == "LOW" else "LOW")
                            for i, p in enumerate(a)):
            return None
        if any((x["price"] >= y["price"] if x["kind"] == "LOW" else x["price"] <= y["price"])
               for x, y in zip(a, a[1:])):
            return None
    except (KeyError, TypeError, ValueError, OverflowError):
        return None
    return a


def course_four_sign_evidence(scenario, as_of=None):
    observed = scenario.get("observed_at", 0)
    a = _anchors(scenario, observed if as_of is None else as_of)
    completed = bool(a and len(a) == 6 and scenario.get("label") == "W5")
    scale = scenario.get("scale_bars", 0)
    if not isinstance(scale, int) or isinstance(scale, bool):
        scale = 0
    scale_ok = isinstance(scale, int) and not isinstance(scale, bool) and 100 <= scale <= 140
    checks = {"target_zone": scenario.get("target_zone") is True,
              "w3_w5_ao_divergence": scenario.get("divergence") is True,
              "terminal_fractal": scenario.get("terminal_fractal") is True,
              "squat_top_three_volume_proxy": scenario.get("squat") is True}
    n = sum(checks.values())
    status = ("NO_COMPLETED_W5_PIVOT_HYPOTHESIS" if not completed else
              "W3_W5_DIVERGENCE_SCALE_UNVERIFIED" if not scale_ok else
              "COURSE_TERMINAL_CRITERIA_INCOMPLETE" if n < 4 else
              "FOUR_PROXY_FLAGS_OBSERVED_NOT_CERTIFIED")
    return {"source": "COURSE_LEVEL_III_P2_PDF77", "status": status, "criteria": checks,
            "observed_count": n, "required_count": 4, "wave_scale_bars": scale if scale > 0 else None,
            "scale_100_140_observed": scale_ok, "candidate_wave_5_complete": completed,
            "corpus_limit": "VOLUME_PROXY_AND_PROVISIONAL_ELLIOTT_DEGREE",
            "wave_degree_confirmed": False, "can_place_orders": False,
            "can_close": False, "can_reverse": False}


def course_price_location(stage, side, scenario, price, as_of):
    """Numerical Course predicates, never an instruction based on a wave label.

    W1 and W3 buys use a 50-62% retracement; W5 uses 38-50% of W3.
    W3_EXTENSION is 110% of W1 measured FROM W2, not from Point 0.
    Reductions and final exits need a separately matched terminal structure.
    """
    out = {"source": SOURCE, "stage": stage, "status": "UNVERIFIED_PRICE_LOCATION",
           "reason": "COURSE_ANCHORS_OR_PRICE_UNVERIFIED", "can_place_orders": False,
           "can_close": False, "can_reverse": False, "wave_degree_confirmed": False}
    a = _anchors(scenario, as_of)
    sign = 1 if side in ("LONG", "BUY") else -1 if side in ("SHORT", "SELL") else 0
    required = {"W1": 2, "W2": 2, "W3": 3, "W3_EXTENSION": 3, "W4": 4, "W5": 5, "EXIT": 6}.get(stage)
    if (not sign or not a or required is None or len(a) != required or
            scenario.get("direction") != ("UP" if sign == 1 else "DOWN") or
            not isinstance(price, (int, float)) or not isfinite(price) or price <= 0):
        return out
    p = [sign*x["price"] for x in a[:required]]
    if p[1] <= p[0] or (required >= 3 and not p[0] < p[2] < p[1]) or (
            required >= 4 and p[3] <= p[1]) or (
            required >= 5 and not p[1] < p[4] < p[3]):
        out["reason"] = "COURSE_MOTIVE_PRICE_GRAMMAR_UNVERIFIED"
        return out
    out.update(origin_at=a[0]["extreme_at"], scenario_id=scenario.get("scenario_id", ""),
               evidence_known_at=max(x["known_at"] for x in a[:required]),
               status="LOCATION_ONLY_REQUIRES_SOURCE_CONFIRMATION", reason="COURSE_TERMINAL_OR_RETRACEMENT_SIGNS_REQUIRED")
    if stage in ("W1", "W3", "W5"):
        base, top = (p[2], p[3]) if stage == "W5" else (p[0], p[1])
        low, high = (.38, .50) if stage == "W5" else (.50, .62)
        ratio = (top-sign*price)/(top-base)
        levels = sorted((sign*(top-high*(top-base)), sign*(top-low*(top-base))))
        inside = low-1e-12 <= ratio <= high+1e-12
        out.update(retracement_ratio=ratio, retracement_range=[low, high], price_zone=levels,
                   price_in_zone=inside, reason="COURSE_PRICE_ZONE_OBSERVED" if inside else "COURSE_PRICE_OUTSIDE_RETRACEMENT_ZONE")
        # A confirmed pivot outside this same price zone cannot be replaced by
        # a later qualifying price after the original setup has failed.
        if stage in ("W3", "W5"):
            pivot = p[2] if stage == "W3" else p[4]
            pivot_ratio = (top-pivot)/(top-base)
            out["confirmed_retracement_ratio"] = pivot_ratio
            if not low-1e-12 <= pivot_ratio <= high+1e-12:
                out.update(price_in_zone=False, reason="COURSE_CONFIRMED_RETRACEMENT_OUTSIDE_ZONE")
    elif stage == "W3_EXTENSION":
        ratio = (sign*price-p[2])/(p[1]-p[0])
        out.update(w3_to_w1_ratio=ratio, price_level=sign*(p[2]+1.1*(p[1]-p[0])),
                   price_in_zone=ratio >= 1.1-1e-12,
                   reason="COURSE_W3_110_PERCENT_OBSERVED" if ratio >= 1.1-1e-12 else "COURSE_W3_BELOW_110_PERCENT")
    else:
        out.update(price_in_zone=False, reason="COURSE_MATCHED_TERMINAL_STRUCTURE_REQUIRED")
    if required >= 4:
        out["w3_to_w1_ratio"] = (p[3]-p[2])/(p[1]-p[0])
    out["invalidation_level"] = a[1]["price"] if stage in ("W5", "EXIT") else a[0]["price"]
    out["invalidation_is_anchor_not_exchange_stop"] = True
    out["required_source_evidence"] = {
        "W1": ["PRIOR_OPPOSITE_FIVE_WAVE_TERMINAL", "RETRACEMENT_TURN"],
        "W2": ["MATCHED_W1_TERMINAL_SUBDIVISION"],
        "W3": ["W2_CONFIRMED_FRACTAL", "SQUAT_AMONG_THREE_W2_EXTREMES"],
        "W3_EXTENSION": ["OWNED_W3_CAMPAIGN", "NEW_RISK_GATES"],
        "W4": ["MATCHED_W3_TERMINAL_SUBDIVISION", "W3_AO_PEAK_STRONGER_THAN_W1"],
        "W5": ["W4_AO_ZERO", "TWO_W4_FRACTALS", "SQUAT_AMONG_THREE_W4_EXTREMES"],
        "EXIT": ["MATCHED_W5_TERMINAL_SUBDIVISION", "TERMINAL_TARGET"],
    }[stage]
    from williams_course_campaign import course_target
    out["example_target_units"] = course_target(stage,out.get("w3_to_w1_ratio"))
    out["quantity_basis"] = "ILLUSTRATIVE_CONTRACT_UNITS_NOT_RISK_WEIGHTS_OR_BINANCE_LOTS"
    return out


def course_stage_locations(scenario, as_of):
    stages = {2: ("W1", "W2"), 3: ("W3", "W3_EXTENSION"), 4: ("W4",),
              5: ("W5",), 6: ("EXIT",)}.get(len(scenario.get("anchors", [])), ())
    return [course_price_location(stage, "LONG" if scenario.get("direction") == "UP" else "SHORT",
                                  scenario, scenario.get("observed_close_price", 0), as_of) for stage in stages]


def course_trim_proof(hierarchy, side, origin_at, previous_stage, opened_at, now_ms, bars):
    """Pin the observed end of W1/W3 to a *different*, contained five-leg count.

    H1 active W2/W4 alone cannot reduce a campaign. All evidence must be
    closed, fresh, after the owner's entry, and belong to the same origin.
    Child frame may be H1 or any genuinely available finer frame; no M15 gate.
    """
    out = {"status": "BLOCKED", "reason": "COURSE_STAGE_ORIGIN_OR_HISTORY_UNVERIFIED",
           "source": SOURCE, "can_place_orders": False, "can_close": False,
           "can_reverse": False, "wave_degree_confirmed": False}
    as_of = hierarchy.get("as_of", 0)
    expected = "W2" if previous_stage == "W1" else "W4" if previous_stage in ("W3", "W3_EXTENSION") else None
    if (not expected or not 0 < origin_at < opened_at <= now_ms or
            not 0 < as_of <= now_ms or now_ms-as_of >= H1 or
            "1h" in hierarchy.get("frame_errors", {})):
        return out
    sign = 1 if side in ("LONG", "BUY") else -1 if side in ("SHORT", "SELL") else 0
    if not sign:
        return out
    parents = []
    for s in hierarchy.get("scenarios", []):
        a = _anchors(s, as_of)
        if (a and s.get("frame") == "1h" and s.get("direction") == ("UP" if sign == 1 else "DOWN") and
                s.get("active_label") == expected and len(a) == (2 if expected == "W2" else 4) and
                a[0]["extreme_at"] == origin_at):
            parents.append(s)
    if len(parents) != 1:
        out["reason"] = "COURSE_MATCHED_PARENT_AMBIGUOUS_OR_MISSING"
        return out
    parent = parents[0]
    a = parent["anchors"]
    start, end = a[-2:]
    children = []
    from williams_wave_hierarchy import FRAME_MS
    for child in hierarchy.get("scenarios", []):
        c = _anchors(child, as_of)
        if (c and child.get("frame") not in hierarchy.get("frame_errors", {}) and
                FRAME_MS.get(child.get("frame"), H1+1) <= H1 and child.get("direction") == parent["direction"] and
                len(c) == 6 and c[0]["extreme_at"] == start["extreme_at"] and c[-1]["extreme_at"] == end["extreme_at"] and
                c[0]["price"] == start["price"] and c[-1]["price"] == end["price"] and
                course_four_sign_evidence(child, as_of)["status"] == "FOUR_PROXY_FLAGS_OBSERVED_NOT_CERTIFIED"):
            children.append(child)
    if len(children) != 1:
        out["reason"] = "COURSE_TERMINAL_DEGREE_AMBIGUOUS_OR_INCOMPLETE"
        return out
    child = children[0]
    known = max(end["known_at"], child["anchors"][-1]["known_at"])
    if end["extreme_at"] < opened_at or not opened_at < known <= as_of or now_ms-known > 18_000_000:
        out["reason"] = "COURSE_TERMINAL_PREENTRY_OR_STALE"
        return out
    # Course PDF81: the W3 5/34 peak must exceed the W1 peak. Recompute
    # from real, contiguous, closed H1 bars; never trust a supplied score.
    if expected == "W4":
        rows = [b for b in bars if b.t+H1 <= as_of]
        if (len(rows) < 40 or any(y.t-x.t != H1 for x, y in zip(rows, rows[1:])) or
                rows[0].t+33*H1 > a[0]["extreme_at"] or rows[-1].t < end["extreme_at"]):
            out["reason"] = "COURSE_W3_W1_MOMENTUM_HISTORY_UNVERIFIED"
            return out
        med = [(b.h+b.l)/2 for b in rows]
        osc = [float("nan")]*33 + [ordered_sum(med[i-4:i+1])/5-ordered_sum(med[i-33:i+1])/34 for i in range(33, len(rows))]
        peaks = [max((sign*v for b, v in zip(rows, osc) if left["extreme_at"] <= b.t <= right["extreme_at"] and isfinite(v)), default=float("nan"))
                 for left, right in ((a[0], a[1]), (a[2], a[3]))]
        if not all(isfinite(v) and v > 0 for v in peaks) or peaks[1] <= peaks[0]:
            out["reason"] = "COURSE_W3_OSCILLATOR_NOT_STRONGER_THAN_W1"
            return out
    out.update(status="OBSERVED_SOURCE_PINNED_REDUCTION", reason="COURSE_MATCHED_TERMINAL_PROXY_CRITERIA",
               stage=expected, target_units=1 if expected == "W2" else 3,
               origin_at=origin_at, parent_id=parent.get("scenario_id", ""),
               terminal_id=child.get("scenario_id", ""), signal_close_ms=known)
    return out
