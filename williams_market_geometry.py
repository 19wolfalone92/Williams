"""Causal numerical readings of Williams' visual chart rules.

TC2_EN ch9, PDF140-142: start at the last passage through the mouth and
compare the PRICE EDGE (highs in a decline, lows in an advance) with Jaw
and Teeth over the SAME interval. Regression is an explicit engineering
interpretation, not a formula printed by Williams. No ATR/angle threshold.
NTD ch3/ch10: sleep is intertwined lines, not merely a narrowing trend.
"""
from math import fsum, isfinite, ulp


def first_wise_man_ao_blocker(side, ao_now, ao_before):
    """TC2 PDF125 expects old-trend AO; PDF131 states absence of that
    context means this is not a countertrend trade. Zero level is not colour.
    """
    if side not in {"LONG", "SHORT"}:
        return "WM1_AO_SIDE_INVALID"
    try:
        now, before = float(ao_now), float(ao_before)
    except (TypeError, ValueError, OverflowError):
        return "WM1_AO_UNVERIFIED"
    if not isfinite(now) or not isfinite(before):
        return "WM1_AO_UNVERIFIED"
    if side == "LONG":
        return None if now < before else "WM1_AO_NOT_RED"
    return None if now > before else "WM1_AO_NOT_GREEN"


def _slope(values):
    n = len(values)
    x = (n - 1) / 2
    try:
        # Preserve fail-closed overflow handling, then remove the absolute
        # price level before fitting. A price translation is not a new angle.
        if not isfinite(fsum(values) / n):
            return float('nan')
        origin = values[0]
        mean = fsum(v-origin for v in values) / n
        return fsum((i-x)*((v-origin)-mean) for i,v in enumerate(values)) / fsum((i-x)**2 for i in range(n))
    except (OverflowError, ValueError):
        return float('nan')


def angulation_evidence(side, bars, jaw, teeth, lips, *, expected_step_ms=None):
    """TC2_EN142/160, figs 9.3–9.11/10.7: mouth passage, price-facing edge,
    Jaw/Teeth slopes; a single outside bar may itself create the angle.

    Fit the actual outside path as well as the mouth-anchored path: an old
    jump must not lend its angle to later flat/parallel/converging prices.
    Regression and the eight-ULP precision guard are engineering choices,
    not a printed formula, ATR cutoff, or angle-in-degrees trading threshold.
    """
    result = dict(valid=False, score=0.0, reason="MOUTH_CROSSING_UNOBSERVED",
                  anchor_index=None, method="MOUTH_ANCHORED_EDGE_SLOPES",
                  numerical_interpretation=True, first_outside_index=None,
                  outside_gap_growth_pct=None, gap_growth_baseline_index=None,
                  gap_growth_basis=None,
                  method_version=2, price_edge=None,
                  anchor_time_ms=None, source_end_bar_open_ms=None,
                  numeric_price_resolution=None, departure_price_slope=None,
                  departure_jaw_slope=None, departure_teeth_slope=None,
                  departure_relative_slope=None,
                  score_units='PERCENT_OF_SIGNAL_CLOSE_PER_BAR_NOT_ANGLE_DEGREES',
                  book_reference="TC2_CH9_FIG_9_3_TO_9_11_AND_CH10_FIG_10_7")
    n = len(bars)
    if (side not in {"LONG", "SHORT"} or n < 3 or
            any(len(x) != n for x in (jaw, teeth, lips)) or
            (expected_step_ms is not None and (not isinstance(expected_step_ms, int) or expected_step_ms <= 0))):
        return result | {"reason": "INVALID_GEOMETRY_INPUT"}
    def usable(i):
        b = bars[i]
        return (all(isfinite(v) and v > 0 for v in
                    (b.o,b.h,b.l,b.c,jaw[i],teeth[i],lips[i])) and
                b.h >= max(b.o,b.c,b.l) and b.l <= min(b.o,b.c))
    def crosses_mouth(i):
        return bars[i].h >= min(jaw[i],teeth[i],lips[i]) and bars[i].l <= max(jaw[i],teeth[i],lips[i])
    def outside_mouth(i):
        return (bars[i].h < min(jaw[i],teeth[i],lips[i]) if side == "LONG"
                else bars[i].l > max(jaw[i],teeth[i],lips[i]))
    if not usable(n-1):
        return result | {"reason":"INVALID_SIGNAL_CANDLE_OR_LINES"}
    if not outside_mouth(n-1):
        return result | {"reason":"SIGNAL_BAR_TOUCHES_MOUTH"}
    anchor = None
    for i in range(n-2, -1, -1):
        if not usable(i):
            break
        if crosses_mouth(i):
            anchor = i
            break
    if anchor is None:
        return result
    if not all(usable(i) for i in range(anchor,n)):
        return result | {"anchor_index":anchor,"reason":"INSUFFICIENT_PATH"}
    step = expected_step_ms if expected_step_ms is not None else bars[-1].t-bars[-2].t
    if step <= 0 or any(bars[i].t-bars[i-1].t != step for i in range(anchor+1,n)):
        return result | {"anchor_index":anchor,"examined_bars":n-anchor,"reason":"CANDLE_PATH_GAP"}
    first_outside = next((i for i in range(anchor+1,n) if outside_mouth(i)),None)
    if first_outside is None:
        return result | {"anchor_index":anchor,"examined_bars":n-anchor,"reason":"INSUFFICIENT_OUTSIDE_PATH"}
    # Fig10.7 explicitly allows the divergent bar to create its own angle.
    # With only one outside bar, compare its edge with the observed mouth
    # passage. Otherwise retain the post-exit check: a first gap followed by
    # parallel paths still fails (fig9.5). Neither branch invents an ATR rule.
    single_bar = first_outside == n-1
    baseline = anchor if single_bar else first_outside
    basis = "MOUTH_TO_SINGLE_OUTSIDE_BAR" if single_bar else "AFTER_FIRST_OUTSIDE_BAR"
    sign = -1 if side == "LONG" else 1
    edge = [b.h if side == "LONG" else b.l for b in bars[anchor:]]
    price_slope, jaw_slope, teeth_slope = map(_slope,(edge,jaw[anchor:],teeth[anchor:]))
    separation = min(sign*(price_slope-jaw_slope),sign*(price_slope-teeth_slope))
    endpoint = min(sign*((edge[-1]-edge[0])-(line[-1]-line[anchor])) for line in (jaw,teeth))
    basic = sign*price_slope > 0 and sign*(edge[-1]-edge[0]) > 0 and separation > 0 and endpoint > 0
    def gap(line,i):
        return sign*((bars[i].h if side=="LONG" else bars[i].l)-line[i])
    post_growth = min(gap(jaw,n-1)-gap(jaw,baseline),
                      gap(teeth,n-1)-gap(teeth,baseline))
    outside_edge = [b.h if side == 'LONG' else b.l for b in bars[baseline:]]
    post_price, post_jaw, post_teeth = map(_slope, (outside_edge, jaw[baseline:], teeth[baseline:]))
    post_separation = min(sign*(post_price-post_jaw), sign*(post_price-post_teeth))
    post_motion = sign*(outside_edge[-1]-outside_edge[0])
    # Four endpoint operands plus rounding of the subtractions need a numeric
    # resolution guard. Eight ULP is machine precision, NOT a book distance,
    # candle-size, ATR, or angle-in-degrees trading threshold.
    resolution = 8 * max(ulp(v) for series in (edge, jaw[anchor:], teeth[anchor:]) for v in series)
    if not all(isfinite(v) for v in (price_slope,jaw_slope,teeth_slope,separation,endpoint,
                                     post_growth,post_price,post_jaw,post_teeth,
                                     post_separation,post_motion,resolution)):
        return result | {"reason":"NUMERICAL_GEOMETRY_UNVERIFIED","anchor_index":anchor,
                         "examined_bars":n-anchor,"first_outside_index":first_outside,
                         "gap_growth_baseline_index":baseline,"gap_growth_basis":basis}
    span, post_span = n-anchor-1, n-baseline-1
    whole_resolved = min(sign*price_slope*span, sign*(edge[-1]-edge[0]),
                         separation*span, endpoint) > resolution
    post_moves = sign*post_price*post_span > resolution and post_motion > resolution
    post_resolved = min(sign*post_price*post_span, post_motion,
                        post_separation*post_span, post_growth) > resolution
    valid = basic and whole_resolved and post_growth > 0 and post_moves and post_separation > 0 and post_resolved
    score = separation/bars[-1].c*100 if valid else 0.0
    growth_pct = post_growth/bars[-1].c*100
    if not isfinite(score) or not isfinite(growth_pct):
        return result | {"reason":"NUMERICAL_GEOMETRY_UNVERIFIED","anchor_index":anchor,
                         "examined_bars":n-anchor,"first_outside_index":first_outside,
                         "gap_growth_baseline_index":baseline,"gap_growth_basis":basis}
    reason = ("PRICE_PATH_NOT_STEEPER" if not whole_resolved
              else "PARALLEL_AFTER_MOUTH_EXIT" if post_growth<=resolution
              else "PRICE_NOT_DEPARTING_AFTER_MOUTH_EXIT" if not post_moves
              else "PRICE_PATH_NOT_STEEPER_AFTER_MOUTH_EXIT" if post_separation*post_span<=resolution
              else "SINGLE_BAR_ANGULAR_DEPARTURE" if single_bar
              else "DIVERGING_PRICE_PATH")
    return result | dict(valid=valid, score=score,
        reason=reason,anchor_index=anchor,examined_bars=n-anchor,
        price_slope=price_slope,jaw_slope=jaw_slope,teeth_slope=teeth_slope,
        first_outside_index=first_outside,outside_gap_growth_pct=growth_pct,
        gap_growth_baseline_index=baseline,gap_growth_basis=basis,
        price_edge='HIGH' if side == 'LONG' else 'LOW',
        anchor_time_ms=bars[anchor].t, source_end_bar_open_ms=bars[-1].t,
        numeric_price_resolution=resolution, departure_price_slope=post_price,
        departure_jaw_slope=post_jaw, departure_teeth_slope=post_teeth,
        departure_relative_slope=post_separation)


def alligator_phases(bars, jaw, teeth, lips):
    """Order, both mouth gaps, slope and price; no fixed width threshold.

    Two consecutive intertwined observations distinguish sleep from the
    first loss of trend order. This persistence is an engineering policy.
    A shrinking but still ordered mouth is satiated, never sleeping.
    """
    n = len(bars)
    if any(len(x) != n for x in (jaw,teeth,lips)):
        return ["UNKNOWN"]*n
    states = ["UNKNOWN"]*n
    def valid(i):
        return all(isfinite(v) and v > 0 for v in (jaw[i],teeth[i],lips[i],bars[i].c))
    def order(i):
        return 1 if lips[i]>teeth[i]>jaw[i] else -1 if lips[i]<teeth[i]<jaw[i] else 0
    for i in range(1,n):
        if not valid(i) or not valid(i-1):
            continue
        direction, previous = order(i), order(i-1)
        gaps = (abs(jaw[i]-teeth[i]),abs(teeth[i]-lips[i]))
        old = (abs(jaw[i-1]-teeth[i-1]),abs(teeth[i-1]-lips[i-1]))
        if direction == 0:
            states[i] = "SLEEPING" if previous == 0 else "SATIATED"
        elif direction != previous:
            states[i] = "AWAKENING"
        elif (direction*(bars[i].c-lips[i])>0 and
              all(direction*(line[i]-line[i-1])>0 for line in (jaw,teeth,lips)) and
              all(a>=b for a,b in zip(gaps,old)) and any(a>b for a,b in zip(gaps,old))):
            states[i] = "EATING"
        elif any(a<b for a,b in zip(gaps,old)) or direction*(bars[i].c-lips[i])<=0:
            states[i] = "SATIATED"
        else:
            states[i] = "AWAKENING"
    return states


def ntd_initial_context(bars,jaw,teeth,lips,formation, tick):
    """A fresh NTD cycle needs observed sleep and its first external breakout.

    Later fractals remain usable additions for an owned campaign. They must
    not create a late initial entry after an earlier external fractal broke.
    """
    proof = dict(eligible=False, reason="NTD_SLEEP_UNOBSERVED", sleep_at_ms=None,
                 numerical_interpretation=True)
    if not 0 <= formation < len(bars) or not isfinite(tick) or tick <= 0:
        return proof
    phases = alligator_phases(bars,jaw,teeth,lips)
    sleep = next((i for i in range(formation,-1,-1) if phases[i]=="SLEEPING"),None)
    if sleep is None:
        return proof
    proof["sleep_at_ms"] = bars[sleep].t
    from williams_edition_rules import edition_fractals
    for f in edition_fractals(bars):
        if f.confirmation <= sleep:
            continue
        sign = 1 if f.kind=="HIGH" else -1
        trigger = f.price+sign*tick
        for i in range(f.confirmation+1,len(bars)):
            if sign*((bars[i].h if sign==1 else bars[i].l)-trigger)>=0:
                # An earlier touch consumes this formation even when invalid.
                if isfinite(teeth[i]) and sign*(trigger-teeth[i])>0:
                    return proof | {"reason":"NTD_FIRST_BREAKOUT_ALREADY_OCCURRED", "first_breakout_at_ms":bars[i].t}
                break
    return proof | {"eligible":True,"reason":"NTD_SLEEP_AND_UNCONSUMED_FIRST_FRACTAL"}
