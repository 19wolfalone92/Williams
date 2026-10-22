"""One closed-bar chart reading; oscillator agreement is not a wave count."""
from math import isfinite

from williams_market_geometry import alligator_phases, angulation_evidence
from williams_edition_rules import valid_edition_bars
from williams_wave_hierarchy import FRAME_MS, confirmed_wave_anchors, hypotheses_from_anchors


def chart_raw_lines(bars):
    mids=[(b.h+b.l)/2 for b in bars]
    def line(period):
        raw=[float("nan")]*len(mids)
        if len(mids)>=period:
            raw[period-1]=sum(mids[:period])/period
            for i in range(period,len(mids)):
                raw[i]=(raw[i-1]*(period-1)+mids[i])/period
        return raw
    return line(13),line(8),line(5)


def chart_lines(bars):
    return tuple([raw[i-shift] if i>=shift else float("nan") for i in range(len(raw))]
                 for raw,shift in zip(chart_raw_lines(bars),(8,5,3)))


def market_reading(bars,phase_context,frame="1h",as_of=None):
    result=dict(schema="WILLIAMS_CHART_READING_V1",state="INSUFFICIENT_HISTORY",
        wave_phase="UNRESOLVED",can_place_orders=False,can_close=False,numerical_interpretation=True,
        frame=frame,closed_bar_count=len(bars),minimum_bars=39)
    duration=FRAME_MS.get(frame)
    if duration is None:return result | {"state":"UNSUPPORTED_FRAME"}
    if len(bars)<39 or any(not all(isfinite(v) and v>0 for v in (b.h,b.l,b.c)) for b in bars):
        return result
    if (not valid_edition_bars(bars) or any(b.t-a.t!=duration for a,b in zip(bars,bars[1:]))
            or as_of is not None and any(b.t+duration>as_of for b in bars)):
        return result | {"state":"HISTORY_UNVERIFIED"}
    result["as_of_close_ms"]=bars[-1].t+duration
    if as_of is not None and as_of-result["as_of_close_ms"]>=duration:
        return result | {"state":"STALE_HISTORY"}
    mids=[(b.h+b.l)/2 for b in bars]
    def mean(n,i):return sum(mids[i-n+1:i+1])/n
    ao=[mean(5,i)-mean(34,i) if i>=33 else float("nan") for i in range(len(bars))]
    def signal(i):return sum(ao[i-4:i+1])/5
    current=ao[-1];previous=ao[-2];ac=current-signal(len(bars)-1);prior_ac=previous-signal(len(bars)-2)
    fast,medium,slow=(mean(n,len(bars)-1) for n in (5,13,34))
    direction="UP" if fast>max(medium,slow) else "DOWN" if fast<min(medium,slow) else "NEUTRAL"
    momentum="UP" if current>previous else "DOWN" if current<previous else "NEUTRAL"
    acceleration="UP" if ac>prior_ac else "DOWN" if ac<prior_ac else "NEUTRAL"
    rhythm="ALIGNED_ACCELERATION" if direction==momentum==acceleration and direction!="NEUTRAL" else "MIXED_OR_DECELERATING"
    jaw,teeth,lips=chart_lines(bars);phases=alligator_phases(bars,jaw,teeth,lips)
    h1=next((f for f in phase_context.get("frames",[]) if f["frame"]==frame),{})
    labels={(h["active_label"],h["active_direction"]) for h in h1.get("hypotheses",[])}
    local = ([h for h in hypotheses_from_anchors(
        frame,bars,confirmed_wave_anchors(bars,duration))
        if h.status in ("PROVISIONAL","ALTERNATIVE")][-12:]
        if len(bars)>=40 else [])
    local_evidence=[dict(scenario_id=h.scenario_id,last_confirmed_leg=h.label,
        active_label=h.active_label,active_direction=h.active_direction,
        status=h.status,invalidation=h.invalidation,
        last_pivot_known_at=h.anchors[-1].known_at,
        wave_degree_confirmed=False,can_place_orders=False) for h in local]
    wave="UNRESOLVED"
    if h1.get("state")=="ALTERNATIVES":wave="ALTERNATIVES"
    elif len(labels)==1:
        label,_=next(iter(labels))
        wave={"W1":"START_CANDIDATE","W2":"CORRECTION_CANDIDATE","W3":"CONTINUATION_CANDIDATE",
              "W4":"CORRECTION_CANDIDATE","W5":"LATE_IMPULSE_CANDIDATE",
              "A":"CORRECTION_CANDIDATE","B":"CORRECTION_CANDIDATE","C":"CORRECTION_CANDIDATE"}.get(label,"UNRESOLVED")
    order="UP" if lips[-1]>teeth[-1]>jaw[-1] else "DOWN" if lips[-1]<teeth[-1]<jaw[-1] else "MIXED"
    mouth=[jaw[-1],teeth[-1],lips[-1]]
    # TC2 print pp105-106: the forward plot tail uses known SMMA samples.
    # Only the first three future slots have all three lines available.
    raw_lines=chart_raw_lines(bars);last=len(bars)-1
    tail=[dict(plot_at_ms=bars[-1].t+step*duration,**{
        name:raw[last+step-shift] if last+step-shift<=last else None
        for name,raw,shift in zip(("jaw","teeth","lips"),raw_lines,(8,5,3))}) for step in range(1,9)]
    common=[raw[last+3-shift] for raw,shift in zip(raw_lines,(8,5,3))]
    tail_order="UP" if common[2]>common[1]>common[0] else "DOWN" if common[2]<common[1]<common[0] else "MIXED"
    width_change=(max(common)-min(common))-(max(mouth)-min(mouth));tolerance=bars[-1].c*1e-10
    price="ABOVE" if bars[-1].c>max(mouth) else "BELOW" if bars[-1].c<min(mouth) else "INSIDE"
    bias="UP" if order=="UP" and price=="ABOVE" and current>0 else "DOWN" if order=="DOWN" and price=="BELOW" and current<0 else "MIXED"
    window=bars[-140:];low=min(b.l for b in window);high=max(b.h for b in window)
    return result | dict(state="OBSERVED",
        alligator=dict(state=phases[-1],previous_state=phases[-2],sleep_observed="SLEEPING" in phases,
                       order=order,jaw=jaw[-1],teeth=teeth[-1],lips=lips[-1]),
        alligator_known_tail=dict(source="TC2_PRINT_105_106_KNOWN_SMMA_PLOT_OFFSETS",
            source_through_close_ms=bars[-1].t+duration,common_known_plot_steps=3,order=tail_order,
            width_change="WIDENING" if width_change>tolerance else "NARROWING" if width_change < -tolerance else "UNCHANGED",
            points=tail,uses_future_prices=False,extrapolated=False,price_forecast=False,can_place_orders=False),
        last_close=bars[-1].c,price_vs_mouth=price,observed_bias=bias,bias_is_forecast=False,
        range_location=dict(bars=len(window),low=low,high=high,
            position=max(0,min(1,(bars[-1].c-low)/(high-low))) if high>low else None,is_wave_number=False),
        ao=current,ac=ac,ao_direction=momentum,ac_direction=acceleration,
        ao_sign="POSITIVE" if current>0 else "NEGATIVE" if current<0 else "ZERO",
        ac_sign="POSITIVE" if ac>0 else "NEGATIVE" if ac<0 else "ZERO",
        zone="GREEN" if momentum==acceleration=="UP" else "RED" if momentum==acceleration=="DOWN" else "GRAY",
        profitunity_macd=dict(fast=5,slow=34,signal_period=5,oscillator=current,signal=signal(len(bars)-1),histogram=ac,
            shares_ao_and_ac=True,momentum_direction="UP" if ac>0 else "DOWN" if ac<0 else "NEUTRAL"),
        twr=dict(fast_5=fast,medium_13=medium,slow_34=slow,direction=direction),
        rhythm_condition=rhythm,wave_phase=wave,wave_state=h1.get("state","UNRESOLVED"),
        exhaustion_warning=h1.get("exhaustion_warning",False),
        independent_wave_hypotheses=local_evidence,
        independent_wave_hypothesis_count=len(local),
        wave_degree_confirmed=False,probability_calibrated=False,
        angulation={side:angulation_evidence(side,bars,jaw,teeth,lips) for side in ("LONG","SHORT")})
