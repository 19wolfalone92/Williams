"""NTD chapter 8 Balance-Line signal geometry (research / non-executable).

Source: Bill Williams, New Trading Dimensions (Russian PDF), pp. 99-114,
esp. figures 8-8, 8-9, 8-13, 8-16, 8-20 and summary p. 114.

The Balance Line is the BLUE Jaw, not the fractal Teeth. Read highs for
BUY, lows for SELL, from RIGHT to LEFT. Against Balance Line require two
progressively more extreme reference highs/lows; away from it, one. Double
the requirement for BUY in RED zone or SELL in GREEN zone.

This is deliberately a *formation descriptor*, not an exchange permission.
Bar timestamps and quote validity must be verified by the outer runtime.
"""
from __future__ import annotations
from dataclasses import dataclass, asdict
from math import isfinite

@dataclass(frozen=True)
class BalanceLineEvidence:
    side: str
    status: str
    base_index: int = -1
    reference_index: int = -1
    required_steps: int = 0
    confirmed_steps: int = 0
    trigger_price: float = 0.0
    protective_reference: float = 0.0
    zone: str = "UNKNOWN"
    relation: str = "UNKNOWN"
    reason: str = ""
    can_place_orders: bool = False

    def to_dict(self) -> dict:
        return asdict(self)

def ntd_balance_line_evidence(
    highs, lows, jaw, teeth, lips, ao, ac, *,
    side: str, tick_size: float, lookback: int = 20
) -> BalanceLineEvidence:
    """Analyse ONLY a supplied completed-bar prefix; never forecast or execute.

    A qualifying higher high / lower low is sought to the LEFT of the
    most recent right-to-left base. Intervening weaker reference bars do not
    count. A later touch of the pending stop invalidates the candidate.
    """
    side = str(side or "").upper()
    blocked = lambda reason: BalanceLineEvidence(side, "BLOCKED", reason=reason)
    if side not in {"LONG", "SHORT"}:
        return blocked("INVALID_SIDE")
    try:
        tick = float(tick_size)
        series = [[float(v) for v in item] for item in (highs,lows,jaw,teeth,lips,ao,ac)]
    except (TypeError, ValueError, OverflowError):
        return blocked("MALFORMED_INDICATORS")
    if not isfinite(tick) or tick <= 0:
        return blocked("INVALID_TICK")
    n = len(series[0])
    if n < 5 or lookback < 3 or any(len(s) != n for s in series):
        return blocked("INSUFFICIENT_OR_MISMATCHED_BARS")
    h,l,j,t,lip,o,a = series
    # Indicator warm-up values earlier in history may be NaN. We only need
    # a valid base, reference path and two *completed* AO/AC zone readings.
    if not all(isfinite(v) for v in (o[-1], o[-2], a[-1], a[-2])):
        return blocked("ZONE_NOT_READY")
    zone = ("GREEN" if o[-1] > o[-2] and a[-1] > a[-2]
            else "RED" if o[-1] < o[-2] and a[-1] < a[-2] else "GRAY")
    is_long = side == "LONG"
    prices = h if is_long else l
    # Rightmost equal extremum is the newest valid base: do not use later bars
    # as if they were available when that base was formed.
    start = max(1, n - lookback)
    possible = [i for i in range(start, n)
                if all(isfinite(v) for v in (h[i],l[i],j[i],t[i],lip[i]))
                and l[i] > 0 and h[i] >= l[i] and min(j[i],t[i],lip[i]) > 0]
    if not possible:
        return blocked("NO_VALID_BASE")
    base = min(possible, key=lambda i: (prices[i], -i)) if is_long else max(possible, key=lambda i: (prices[i], i))
    base_price = prices[base]
    # NTD pp104/108, figs 8-9(D), 8-20(D): use the position of the
    # first qualifying SIGNAL-reference bar relative to current Jaw at
    # that bar. The base bar can lie on the OTHER side of Balance Line.
    relation, needed = "UNKNOWN", 0
    count, previous, reference = 0, base_price, -1
    # Read from RIGHT to LEFT: each 'new high/low' must exceed the previous
    # qualifying extremum in the trade direction, not merely the base.
    for k in range(base - 1, max(-1, base - 1 - 2 * lookback), -1):
        if not isfinite(prices[k]) or prices[k] <= 0:
            return blocked("INVALID_PRICE_IN_FORMATION")
        if (prices[k] > previous if is_long else prices[k] < previous):
            if count == 0:
                if not isfinite(j[k]) or j[k] <= 0:
                    return blocked("SIGNAL_JAW_NOT_READY")
                relation = ("ABOVE" if prices[k] > j[k] else "BELOW"
                            if prices[k] < j[k] else "ON")
                uphill = (is_long and relation != "ABOVE") or (not is_long and relation != "BELOW")
                needed = (2 if uphill else 1) * (
                    2 if (is_long and zone == "RED") or (not is_long and zone == "GREEN") else 1
                )
            count += 1
            previous = prices[k]
            reference = k
            if count == needed:
                break
    if needed == 0:
        return blocked("NO_NEW_REFERENCE_EXTREME")
    if count != needed:
        return BalanceLineEvidence(side, "INCOMPLETE", base, reference, needed, count,
                                   zone=zone, relation=relation,
                                   reason="MISSING_REQUIRED_REFERENCE_EXTREMES")
    trigger = previous + tick if is_long else previous - tick
    stop = l[base] if is_long else h[base]
    if not all(isfinite(v) and v > 0 for v in (trigger, stop)):
        return blocked("INVALID_ORDER_GEOMETRY")
    if (is_long and stop >= trigger) or (not is_long and stop <= trigger):
        return blocked("INVALID_PROTECTIVE_REFERENCE")
    # The book's no-feed-the-Alligator restriction: the projected stop must
    # clear the complete *current* mouth, not just Jaw/Teeth in isolation.
    mouth = max(j[n-1],t[n-1],lip[n-1]) if is_long else min(j[n-1],t[n-1],lip[n-1])
    if not isfinite(mouth) or (trigger <= mouth if is_long else trigger >= mouth):
        return BalanceLineEvidence(side, "BLOCKED", base, reference, required, count,
                                   trigger, stop, zone, relation, "INSIDE_ALLIGATOR_MOUTH")
    # No resurrection after a gap / prior touch. The first possible execution
    # is strictly AFTER base closes, so only later completed bars are tested.
    crossed = any((h[i] >= trigger if is_long else l[i] <= trigger)
                  for i in range(base+1,n) if isfinite(h[i]) and isfinite(l[i]))
    if crossed:
        return BalanceLineEvidence(side, "BLOCKED", base, reference, required, count,
                                   trigger, stop, zone, relation, "TRIGGER_ALREADY_TOUCHED")
    return BalanceLineEvidence(side, "CANDIDATE", base, reference, required, count,
                               trigger, stop, zone, relation,
                               "NTD_CH8_REFERENCE_FORMATION_RESEARCH_ONLY")
