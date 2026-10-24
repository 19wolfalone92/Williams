"""Causal numerical readings of Williams' visual chart rules.

TC2 ch9, PDF122-124: start at the last passage through the mouth and
compare the PRICE EDGE (highs in a decline, lows in an advance) with Jaw
and Teeth over the SAME interval. Regression is an explicit engineering
interpretation, not a formula printed by Williams. No ATR/angle threshold.
NTD ch3/ch10: sleep is intertwined lines, not merely a narrowing trend.
"""
from math import isfinite


def first_wise_man_ao_blocker(side, ao_now, ao_before):
    """TC2 ch9: countertrend WM1 requires falling AO for bullish reversal,
    rising AO for bearish reversal; value versus zero does not determine color.
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
    mean = sum(values) / n
    return sum((i-x)*(v-mean) for i,v in enumerate(values)) / sum((i-x)**2 for i in range(n))


def angulation_evidence(side, bars, jaw, teeth, lips, *, expected_step_ms=None):
    """TC2 ch9 figs 9.3–9.11: mouth passage, price-facing edge, Jaw/Teeth
    slopes and continued angular opening AFTER the first fully outside bar.

    No arbitrary angle threshold. Regression and gap growth are declared
    conservative numerical proxies, not exact author-defined formulas.
    """
    result = dict(valid=False, score=0.0, reason="MOUTH_CROSSING_UNOBSERVED",
                  anchor_index=None, method="MOUTH_ANCHORED_EDGE_SLOPES",
                  numerical_interpretation=True, first_outside_index=None,
                  outside_gap_growth_pct=None, book_reference="TC2_CH9_FIG_9_3_TO_9_11")
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
    if n-anchor < 3 or not all(usable(i) for i in range(anchor,n)):
        return result | {"anchor_index":anchor,"reason":"INSUFFICIENT_PATH"}
    step = expected_step_ms if expected_step_ms is not None else bars[-1].t-bars[-2].t
    if step <= 0 or any(bars[i].t-bars[i-1].t != step for i in range(anchor+1,n)):
        return result | {"anchor_index":anchor,"examined_bars":n-anchor,"reason":"CANDLE_PATH_GAP"}
    first_outside = next((i for i in range(anchor+1,n) if outside_mouth(i)),None)
    if first_outside is None or first_outside==n-1:
        return result | {"anchor_index":anchor,"examined_bars":n-anchor,"reason":"INSUFFICIENT_OUTSIDE_PATH"}
    sign = -1 if side == "LONG" else 1
    edge = [b.h if side == "LONG" else b.l for b in bars[anchor:]]
    price_slope, jaw_slope, teeth_slope = map(_slope,(edge,jaw[anchor:],teeth[anchor:]))
    separation = min(sign*(price_slope-jaw_slope),sign*(price_slope-teeth_slope))
    endpoint = min(sign*((edge[-1]-edge[0])-(line[-1]-line[anchor])) for line in (jaw,teeth))
    basic = sign*price_slope > 0 and sign*(edge[-1]-edge[0]) > 0 and separation > 0 and endpoint > 0
    def gap(line,i):
        return sign*((bars[i].h if side=="LONG" else bars[i].l)-line[i])
    post_growth = min(gap(jaw,n-1)-gap(jaw,first_outside),
                      gap(teeth,n-1)-gap(teeth,first_outside))
    valid = basic and post_growth>0
    reason = ("PRICE_PATH_NOT_STEEPER" if not basic
              else "PARALLEL_AFTER_MOUTH_EXIT" if post_growth<=0
              else "DIVERGING_PRICE_PATH")
    return result | dict(valid=valid, score=separation/bars[-1].c*100 if valid else 0.0,
        reason=reason,anchor_index=anchor,examined_bars=n-anchor,
        price_slope=price_slope,jaw_slope=jaw_slope,teeth_slope=teeth_slope,
        first_outside_index=first_outside,outside_gap_growth_pct=post_growth/bars[-1].c*100)


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
