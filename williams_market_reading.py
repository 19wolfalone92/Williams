"""One closed-bar chart reading; oscillator agreement is not a wave count."""
from math import isfinite

from williams_market_geometry import alligator_phases, angulation_evidence
from williams_edition_rules import valid_edition_bars
from williams_wave_hierarchy import confirmed_wave_anchors, hypotheses_from_anchors


def chart_lines(bars):
    mids=[(b.h+b.l)/2 for b in bars]
    def line(period,shift):
        raw=[float("nan")]*len(mids)
        if len(mids)>=period:
            raw[period-1]=sum(mids[:period])/period
            for i in range(period,len(mids)):
                raw[i]=(raw[i-1]*(period-1)+mids[i])/period
        return [raw[i-shift] if i>=shift else float("nan") for i in range(len(mids))]
    return line(13,8),line(8,5),line(5,3)


FRAME_MS={"1m":60_000,"5m":300_000,"15m":900_000,"1h":3_600_000,
          "4h":14_400_000,"6h":21_600_000,"1d":86_400_000}

def market_reading(bars,phase_context,frame="1h"):
    duration=FRAME_MS.get(frame)
    if duration is None:
        return dict(schema="WILLIAMS_CHART_READING_V1",frame=frame,
                    state="UNSUPPORTED_FRAME",can_place_orders=False,can_close=False)
    result=dict(schema="WILLIAMS_CHART_READING_V1",state="INSUFFICIENT_HISTORY",
        wave_phase="UNRESOLVED",frame=frame,can_place_orders=False,can_close=False,numerical_interpretation=True)
    if len(bars)<39 or any(not all(isfinite(v) and v>0 for v in (b.h,b.l,b.c)) for b in bars):
        return result
    if not valid_edition_bars(bars) or any(b.t-a.t!=duration for a,b in zip(bars,bars[1:])):
        return result | {"state":"HISTORY_UNVERIFIED"}
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
    return result | dict(state="OBSERVED",as_of_close_ms=bars[-1].t+duration,
        alligator=dict(state=phases[-1],previous_state=phases[-2],sleep_observed="SLEEPING" in phases,
                       jaw=jaw[-1],teeth=teeth[-1],lips=lips[-1]),
        ao=current,ac=ac,ao_direction=momentum,ac_direction=acceleration,
        profitunity_macd=dict(fast=5,slow=34,signal_period=5,oscillator=current,signal=signal(len(bars)-1),histogram=ac,
            shares_ao_and_ac=True,momentum_direction="UP" if ac>0 else "DOWN" if ac<0 else "NEUTRAL"),
        twr=dict(fast_5=fast,medium_13=medium,slow_34=slow,direction=direction),
        rhythm_condition=rhythm,wave_phase=wave,wave_state=h1.get("state","UNRESOLVED"),
        exhaustion_warning=h1.get("exhaustion_warning",False),
        independent_wave_hypotheses=local_evidence,
        independent_wave_hypothesis_count=len(local),
        wave_degree_confirmed=False,probability_calibrated=False,
        angulation={side:angulation_evidence(side,bars,jaw,teeth,lips) for side in ("LONG","SHORT")})
