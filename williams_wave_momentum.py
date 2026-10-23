"""Source-scoped W3 momentum evidence, never a wave identity or trade signal.

TC1 PDF60 / Course PDF42: price extreme precedes the global 5/34 peak
by 1-5 actual bars. The oscillator turn becomes known only on a later close.
"""
from math import isfinite


def ordered_sum(values):
    """Use the same left-to-right Double sum as Kotlin's analytical kernel.

    Python 3.12's built-in sum uses a different algorithm. A different AO
    rounding at zero must not create different wave evidence across devices.
    """
    total=0.0
    for value in values:
        total+=value
    return total


def third_momentum_unavailable(reason):
    return {"status":reason,"global_peak":None,"global_peak_indexes":[],
        "ao_peak_index":None,"price_to_ao_lag_bars":None,
        "peak_turn_known_at_ms":None,"signal_sma5_at_peak":None,
        "signal_cross_known_at_ms":None,"book_lag_range":"1_TO_5_REAL_BARS",
        "source":"TC1_PDF60_COURSE_PDF42","wave_degree_confirmed":False,
        "can_place_orders":False,"can_close_positions":False}


def evaluate_third_momentum(rows,ao,cycle_start,third_price_index,
                            fourth_price_index,direction,frame_ms):
    if (direction not in {"UP","DOWN"} or frame_ms<=0 or len(rows)!=len(ao)
            or cycle_start<0 or third_price_index<cycle_start
            or fourth_price_index<=third_price_index or fourth_price_index>=len(rows)
            or not all(isfinite(x) for x in ao[cycle_start:fourth_price_index+1])):
        return third_momentum_unavailable("INVALID_OR_UNWARMED_MOMENTUM_WINDOW")
    sign=1 if direction=="UP" else -1
    indexes=range(cycle_start,fourth_price_index+1)
    maximum=max(sign*ao[i] for i in indexes)
    if maximum<=0:
        return third_momentum_unavailable("NO_SAME_DIRECTION_GLOBAL_MOMENTUM")
    global_peaks=[i for i in indexes if sign*ao[i]==maximum]
    candidates=[peak for peak in global_peaks if 1<=peak-third_price_index<=5
        and all(rows[i].h<=rows[third_price_index].h if direction=="UP"
                else rows[i].l>=rows[third_price_index].l
                for i in range(max(cycle_start,peak-5),peak))]
    chosen=candidates[-1] if candidates else global_peaks[-1]
    turn=next((i for i in range(chosen+1,fourth_price_index+1) if sign*ao[i]<maximum),None)
    after_turn=turn is not None and all(sign*ao[i]==maximum for i in range(chosen+1,turn))
    status=("GLOBAL_PEAK_OUTSIDE_W3_PRICE_LAG_WINDOW" if not candidates else
        "MULTIPLE_GLOBAL_PEAKS_DEGREE_UNCONFIRMED" if any(i not in candidates for i in global_peaks) else
        "GLOBAL_PEAK_TURN_NOT_YET_OBSERVED" if not after_turn else
        "GLOBAL_PEAK_AND_PRICE_LAG_OBSERVED")

    def signal(i):
        return ordered_sum(ao[i-4:i+1])/5 if i>=37 and all(isfinite(x) for x in ao[i-4:i+1]) else None

    signal_cross=next((i for i in range(chosen+1,fourth_price_index+1)
        if signal(i) is not None and sign*ao[i]<sign*signal(i)),None)
    result=third_momentum_unavailable(status)
    result.update(global_peak=sign*maximum,global_peak_indexes=global_peaks,ao_peak_index=chosen,
        price_to_ao_lag_bars=chosen-third_price_index,
        peak_turn_known_at_ms=rows[turn].t+frame_ms if after_turn else None,
        signal_sma5_at_peak=signal(chosen),
        signal_cross_known_at_ms=rows[signal_cross].t+frame_ms if signal_cross is not None else None)
    return result
