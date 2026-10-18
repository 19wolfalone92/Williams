"""Edition-specific, tightening-only Williams exits (no exchange mutations)."""
from math import ceil, floor, isfinite


def zone_trailing_activation(ind, side, opened_at=0):
    """NTD ch7 PDF91-96: AO+AC colours, independent of candle body colour.

    Detect an earlier fifth zone bar after an offline interval as well. The
    caller persists activation; a later grey/opposite zone does not reset it.
    """
    if not {"ao", "ac"}.issubset(ind.columns):
        return None
    streak = 0
    sign = 1 if side in {"BUY", "LONG"} else -1
    for i in range(1, len(ind)):
        a, b = ind.iloc[i-1], ind.iloc[i]
        stamp = b.get("open_time", ind.index[i])
        when = int(stamp.timestamp()*1000) if hasattr(stamp, "timestamp") else int(stamp or 0)
        if opened_at and when + 3_600_000 <= opened_at:
            streak = 0
            continue
        values = [float(a["ao"]), float(b["ao"]), float(a["ac"]), float(b["ac"])]
        good = all(isfinite(v) for v in values) and sign*(values[1]-values[0]) > 0 and sign*(values[3]-values[2]) > 0
        streak = streak + 1 if good else 0
        if streak == 5:
            return {"index": i, "bar_time_ms": when}
    return None


def plan_williams_stop(side, old_stop, entry, initial_stop, mark, tick, opened_at, bars,
                       *, window=5, fee_allowance=.0035, break_even=True):
    """TC2 PDF158/163/165: 3-5 bar stop, confirmed fractal, optional 1R overlay.

    1R cost-buffered break-even is project policy, explicitly not a book rule.
    Missing initial R disables only that overlay, never structural management.
    """
    if side not in {"LONG", "SHORT", "BUY", "SELL"}:
        return None
    long = side in {"LONG", "BUY"}
    if not all(isfinite(v) and v > 0 for v in (old_stop, mark, tick)):
        return None
    if window not in (3, 5) or not isfinite(fee_allowance) or fee_allowance < 0:
        return None
    if any(not all(isfinite(v) and v > 0 for v in (b.h, b.l)) or b.h < b.l for b in bars):
        return None
    if any(a.t >= b.t for a,b in zip(bars,bars[1:])):
        return None
    gap = max(2*tick, mark*.00025)
    proposals = []

    def offer(raw, reason, rounded=False):
        price = raw if rounded else (floor(raw/tick + 1e-12)*tick if long else ceil(raw/tick-1e-12)*tick)
        if isfinite(price) and price > 0 and (
            price > old_stop + tick*.5 and price+gap < mark if long
            else price < old_stop-tick*.5 and price-gap > mark
        ):
            proposals.append({"stop": price, "reason": reason})

    if len(bars) >= window and opened_at > 0 and bars[-1].t + 3_600_000 > opened_at:
        offer(min(b.l for b in bars[-window:])-tick if long else max(b.h for b in bars[-window:])+tick,
              f"TC2_LAST_{window}_CLOSED_BARS")
    for i in range(len(bars)-3, 1, -1):
        if bars[i].t < opened_at or opened_at <= 0:
            break
        extreme = bars[i].l if long else bars[i].h
        start = i
        value = lambda j: bars[j].l if long else bars[j].h
        if value(i+1) == extreme:
            continue
        while start > 0 and value(start-1) == extreme:
            start -= 1
        if start < 2 or bars[start].t < opened_at:
            continue
        if all((value(j)>extreme if long else value(j)<extreme) for j in (start-2,start-1,i+1,i+2)):
            before = len(proposals)
            offer(extreme-tick if long else extreme+tick,"CONFIRMED_H1_FRACTAL")
            if len(proposals) > before:
                break
    valid_r = (initial_stop is not None and isfinite(initial_stop) and
               isfinite(entry) and entry > 0 and initial_stop > 0 and
               (initial_stop < entry if long else initial_stop > entry))
    if break_even and valid_r and (mark-entry if long else entry-mark) >= abs(entry-initial_stop):
        raw = entry*(1+fee_allowance if long else 1-fee_allowance)
        price = ceil(raw/tick-1e-12)*tick if long else floor(raw/tick+1e-12)*tick
        offer(price,"COST_BUFFERED_BREAKEVEN_1R",True)
    return (max(proposals,key=lambda p:p["stop"]) if long else min(proposals,key=lambda p:p["stop"])) if proposals else None
