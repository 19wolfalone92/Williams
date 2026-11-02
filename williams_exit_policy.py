"""Edition-specific, tightening-only Williams exits (no exchange mutations)."""
from decimal import Decimal, InvalidOperation
from math import isfinite


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



def tc2_exact_structural_stop(side, source_extreme, tick):
    """TC2 chapter 12 PDF165: EXACTLY one exchange tick beyond a real bar.

    Same contract as Android tc2ExactStructuralStop: the SOURCE candle
    extremum must already lie on the Binance PRICE_FILTER tick grid.
    No float divide/floor/ceil heuristics, implicit round-to-grid, or
    replacement order from invented off-grid source data.
    """
    if side not in {"LONG", "SHORT", "BUY", "SELL"}:
        return None
    try:
        if not all(isfinite(float(v)) and float(v) > 0 for v in (source_extreme, tick)):
            return None
        source = Decimal(str(source_extreme))
        step = Decimal(str(tick))
        if step <= 0 or source % step != 0:
            return None
        stop = source + (-step if side in {"LONG", "BUY"} else step)
        return float(stop) if stop > 0 and isfinite(float(stop)) else None
    except (InvalidOperation, ValueError, TypeError, OverflowError):
        return None


def plan_williams_stop(side, old_stop, entry, initial_stop, mark, tick, opened_at, bars,
                       *, window=5, fee_allowance=.0035, break_even=True):
    """TC2 PDF158/163/165: verified 3/5-bar and confirmed fractal stops only.

    window=None selects only the confirmed-fractal component of this policy.
    Legacy keyword arguments remain accepted, but *never* authorize automatic
    1R/break-even. A favorable mark move without a book structure does nothing.
    """
    if side not in {"LONG", "SHORT", "BUY", "SELL"}:
        return None
    long = side in {"LONG", "BUY"}
    if not all(isfinite(v) and v > 0 for v in (old_stop, mark, tick)):
        return None
    if window not in (None, 3, 5) or not isfinite(fee_allowance) or fee_allowance < 0:
        return None
    if any(not all(isfinite(v) and v > 0 for v in (b.h, b.l)) or b.h < b.l for b in bars):
        return None
    if opened_at <= 0 or any(
        not isinstance(b.t, int) or b.t < 0 or b.t % 3_600_000
        for b in bars
    ) or any(b.t-a.t != 3_600_000 for a,b in zip(bars,bars[1:])):
        return None
    gap = max(2*tick, mark*.00025)
    proposals = []

    def offer(source_extreme, reason):
        price = tc2_exact_structural_stop(side, source_extreme, tick)
        if price is not None and (
            price > old_stop + tick*.5 and price+gap < mark if long
            else price < old_stop-tick*.5 and price-gap > mark
        ):
            proposals.append({"stop": price, "reason": reason})

    if window is not None and len(bars) >= window and (opened_at <= 0 or bars[-1].t + 3_600_000 > opened_at):
        offer(min(b.l for b in bars[-window:]) if long else max(b.h for b in bars[-window:]),
              f"TC2_LAST_{window}_CLOSED_BARS")
    for i in range(len(bars)-3, 1, -1):
        if bars[i].t < opened_at or opened_at <= 0:
            break
        extreme = bars[i].l if long else bars[i].h
        start = i
        value = lambda j: bars[j].l if long else bars[j].h
        if value(i+1) == extreme:
            continue
        count, j = 0, i-1
        while j >= 0 and count < 2:
            if value(j)==extreme:
                start=j
            elif value(j)>extreme if long else value(j)<extreme:
                count+=1
            else:
                break
            j-=1
        if count<2 or bars[start].t < opened_at:
            continue
        if all((value(j)>extreme if long else value(j)<extreme) for j in (i+1,i+2)):
            before = len(proposals)
            offer(extreme,"CONFIRMED_H1_FRACTAL")
            if len(proposals) > before:
                break
    return (max(proposals,key=lambda p:p["stop"]) if long else min(proposals,key=lambda p:p["stop"])) if proposals else None
