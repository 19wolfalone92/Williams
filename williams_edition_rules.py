"""Edition-specific price rules, without orders or invented market data.

NTD PDF103-111: Balance Line (base +1/+2, adverse zone doubles).
Course PDF61-72/86-92: Hump leverage, Thumb fingers, two-fractal stop,
and the higher-frame two-bar Air Bag. Volumes are explicit kline proxies.
"""
from dataclasses import dataclass, field
from math import isfinite
from typing import Sequence

from williams_wave_hierarchy import WaveBar

TC2 = "TC2_THREE_WISE_MEN"
NTD = "NTD_FIVE_DIMENSIONS"
COURSE = "COURSE_PROFITUNITY"
PROFILES = (TC2, NTD, COURSE)


@dataclass(frozen=True)
class EditionSetup:
    kind: str
    side: str
    known_index: int
    trigger: float
    stop: float
    invalidation: float = 0.0
    context: dict = field(default_factory=dict)


@dataclass(frozen=True)
class EditionFractal:
    kind: str
    price: float
    center: int
    group_start: int
    left_start: int
    confirmation: int


def valid_edition_bars(bars: Sequence[WaveBar]) -> bool:
    return bool(bars) and all(
        b.t >= 0 and all(isfinite(v) for v in (b.o,b.h,b.l,b.c,b.v))
        and min(b.o,b.l,b.c)>0 and b.h>=max(b.o,b.l,b.c)
        and b.l<=min(b.o,b.c) and b.v>=0 for b in bars
    ) and all(a.t<b.t for a,b in zip(bars,bars[1:]))


def edition_fractals(bars: Sequence[WaveBar]) -> list[EditionFractal]:
    """Keep every confirmed fractal, including both sides of an outside bar.

    Such simultaneous extrema cannot establish chronological Hump start/signal.
    Equal highs/lows are not counted as required fingers on either side.
    """
    if not valid_edition_bars(bars):
        return []
    out=[]
    for i in range(2,len(bars)-2):
        for kind in ("LOW","HIGH"):
            value=lambda j: bars[j].l if kind=="LOW" else bars[j].h
            p=value(i)
            if value(i+1)==p:
                continue
            start=i;left_start=i;confirmation=None;ok=True
            for step in (-1,1):
                count=0;j=i+step
                while 0<=j<len(bars) and count<2:
                    v=value(j)
                    if v==p:
                        if step==1:
                            ok=False;break
                        start=j
                    elif (v>p)==(kind=="LOW"):
                        count+=1
                    else:
                        ok=False;break
                    if count==2:
                        if step<0:left_start=j
                        else:confirmation=j
                    j+=step
                ok=ok and count==2
            if ok and confirmation is not None:
                out.append(EditionFractal(kind,p,i,start,left_start,confirmation))
    return sorted(out,key=lambda p:(p.center,p.kind))


def ntd_balance_line_setup(side, bars, jaw, teeth, lips, ao, ac, tick, require_mouth=True):
    """Read right to left. Count successive record extrema, not arbitrary bars.

    A newer higher high after a buy base does not move the buy level (and
    vice versa). Signal-side position relative to Jaw determines +1/+2.
    """
    n=len(bars)
    if (side not in {"LONG","SHORT"} or n<3 or not valid_edition_bars(bars)
            or any(len(x)!=n for x in (jaw,teeth,lips,ao,ac))
            or not isfinite(tick) or tick<=0):
        return None
    if not all(isfinite(v) for v in (jaw[-1],teeth[-1],lips[-1],ao[-2],ao[-1],ac[-2],ac[-1])):
        return None
    sign=1 if side=="LONG" else -1
    extreme=lambda i:bars[i].h if sign==1 else bars[i].l
    base=n-1;records=[];last=sign*extreme(base);needed=None
    adverse=sign*(ao[-1]-ao[-2])<0 and sign*(ac[-1]-ac[-2])<0
    for j in range(n-2,-1,-1):
        p=sign*extreme(j)
        if not records and p<last:
            base=j;last=p
        elif p>last:
            records.append(j);last=p
            if needed is None:
                if not isfinite(jaw[j]) or jaw[j]<=0:return None
                away=sign*(extreme(j)-jaw[j])>0
                needed=(1 if away else 2)*(2 if adverse else 1)
            if len(records)==needed:break
    if needed is None or len(records)<needed:
        return None
    target=records[-1]
    trigger=extreme(target)+sign*tick
    mouth=max(jaw[-1],teeth[-1],lips[-1]) if sign==1 else min(jaw[-1],teeth[-1],lips[-1])
    if trigger<=0 or (require_mouth and (mouth<=0 or sign*(trigger-mouth)<=0)):
        return None
    if any(sign*(extreme(i)-trigger)>=0 for i in range(base,n)):
        return None
    window=bars[max(0,base-4):base+1]
    stop=min(b.l for b in window)-tick if sign==1 else max(b.h for b in window)+tick
    if stop<=0 or sign*(trigger-stop)<=0:return None
    return EditionSetup("BALANCE_LINE",side,n-1,trigger,stop,stop,
        {"formation_key":f"{bars[base].t}:{bars[target].t}:{needed}","base_index":base,"record_indices":records,"required_extremes":needed,
         "adverse_zone":adverse,"signal_index":target})


def course_hump_setup(side,bars,tick):
    if side not in {"LONG","SHORT"} or not isfinite(tick) or tick<=0:
        return None
    pivots=edition_fractals(bars)
    target_kind="HIGH" if side=="LONG" else "LOW"
    sign=1 if side=="LONG" else -1
    for signal in reversed(pivots):
        if signal.kind!=target_kind:continue
        prior=[p for p in pivots if p.center<signal.center and p.kind!=target_kind]
        if not prior:continue
        start=prior[-1]
        # Course PDF72: the two extreme formations must not overlap.
        if start.confirmation>=signal.left_start:continue
        trigger=signal.price+sign*tick
        invalidation=start.price+sign*tick
        later=bars[signal.confirmation:]
        if any(b.h>=trigger if sign==1 else b.l<=trigger for b in later):continue
        if any(b.l<=invalidation if sign==1 else b.h>=invalidation for b in later):continue
        opposite=[p for p in pivots if p.kind!=target_kind and p.confirmation<=signal.confirmation]
        if len(opposite)<2:continue
        stop=(min(p.price for p in opposite[-2:])-tick if sign==1
              else max(p.price for p in opposite[-2:])+tick)
        if trigger<=0 or stop<=0 or sign*(trigger-stop)<=0:continue
        return EditionSetup("COURSE_HUMP",side,signal.confirmation,trigger,stop,invalidation,
            {"start_index":start.center,"signal_index":signal.center,
             "start_confirmed_at_index":start.confirmation,"stop_fractal_indices":[p.center for p in opposite[-2:]]})
    return None


def course_thumb_setup(side,bars,tick):
    if side not in {"LONG","SHORT"} or not valid_edition_bars(bars) or not isfinite(tick) or tick<=0:
        return None
    sign=1 if side=="SHORT" else -1 # Three rising fingers anticipate SHORT.
    fingers=[]
    for i,b in enumerate(bars):
        if not fingers:fingers=[i];continue
        p=bars[fingers[-1]]
        if sign*(b.h-p.h)>0 and sign*(b.l-p.l)>0:
            fingers=(fingers+[i])[-3:]
        elif b.h<=p.h and b.l>=p.l:
            continue # Inside/parallel bars never count as fingers.
        else:
            fingers=[i]
    if len(fingers)<3:return None
    if sum(i>0 and bars[i].v>bars[i-1].v>0 for i in fingers)<2:return None
    thumb,middle=fingers[0],fingers[-1]
    # PDF69 figure: thumb is the OLDEST/lowest of the rising fingers.
    trigger=bars[thumb].l-tick if sign==1 else bars[thumb].h+tick
    stop=bars[middle].h+tick if sign==1 else bars[middle].l-tick
    if trigger<=0 or stop<=0:return None
    if any(b.l<=trigger if sign==1 else b.h>=trigger for b in bars[middle:]):return None
    return EditionSetup("COURSE_THUMB",side,middle,trigger,stop,stop,
        {"finger_indices":fingers,"thumb_index":thumb,"reverse_level":stop,
         "volume_input":"KLINE_VOLUME_PROXY"})


def course_squat_setup(side,bars,tick):
    if side not in {"LONG","SHORT"} or len(bars)<2 or not valid_edition_bars(bars) or not isfinite(tick) or tick<=0:
        return None
    a,b=bars[-2:]
    if not (b.v>a.v>0 and (b.h-b.l)/b.v<(a.h-a.l)/a.v):return None
    sign=1 if side=="LONG" else -1
    trigger=b.h+tick if sign==1 else b.l-tick
    stop=b.l-tick if sign==1 else b.h+tick
    if min(trigger,stop)<=0:return None
    return EditionSetup("COURSE_SQUAT",side,len(bars)-1,trigger,stop,stop,
                        {"volume_input":"KLINE_VOLUME_PROXY"})


def course_two_fractal_stop(side,bars,tick):
    if side not in {"LONG","SHORT"} or not isfinite(tick) or tick<=0:return None
    kind="LOW" if side=="LONG" else "HIGH"
    pivots=[p for p in edition_fractals(bars) if p.kind==kind]
    if len(pivots)<2:return None
    return min(p.price for p in pivots[-2:])-tick if side=="LONG" else max(p.price for p in pivots[-2:])+tick


def course_airbag_stop(side,higher_bars,tick):
    if side not in {"LONG","SHORT"} or len(higher_bars)<2 or not valid_edition_bars(higher_bars) or not isfinite(tick) or tick<=0:
        return None
    sign=1 if side=="LONG" else -1
    current=higher_bars[-1]
    for b in reversed(higher_bars[:-1]):
        if sign*(current.h-b.h)>0 and sign*(current.l-b.l)>0:
            stop=b.l-tick if sign==1 else b.h+tick
            return stop if stop>0 else None
    return None


def course_rhythm(bars):
    if len(bars)<34 or not valid_edition_bars(bars):return {"twr":"UNKNOWN","momentum":"UNKNOWN"}
    mids=[(b.h+b.l)/2 for b in bars]
    def mean(n,end=None):
        v=mids[:end] if end is not None else mids
        return sum(v[-n:])/n
    fast,medium,slow=mean(5),mean(13),mean(34)
    twr="UP" if fast>max(medium,slow) else "DOWN" if fast<min(medium,slow) else "NEUTRAL"
    momentum="UNKNOWN"
    if len(bars)>=35:
        delta=(fast-slow)-(mean(5,-1)-mean(34,-1))
        momentum="UP" if delta>0 else "DOWN" if delta<0 else "NEUTRAL"
    return {"twr":twr,"momentum":momentum}


def opposing_close_event_triggered(signal_at,frame_ms,opened_at,now):
    """Risk reduction after a real close, including a bounded offline interval.

    This is not permission to replay a new MARKET entry at a historical price.
    """
    if min(signal_at,frame_ms,opened_at,now)<=0:return False
    known_at=signal_at+frame_ms
    return opened_at<known_at<=now and now-known_at<5*frame_ms


def market_on_close_reason(signal_at,frame_ms,now,reference,mark,max_age_ms=90_000,max_deviation=.0015):
    """A just-completed bar event is a MARKET request, never a backfilled price.

    The 90s admission/slippage limits are explicit execution policy, not a
    historical claim that Binance can fill a crypto order at the old close.
    """
    if frame_ms<=0 or signal_at<=0 or max_age_ms<0:return "INVALID_CLOSE_EVENT_TIME"
    closed=signal_at+frame_ms
    if now<closed:return "CLOSE_EVENT_NOT_CLOSED"
    if now-closed>max_age_ms:return "CLOSE_EVENT_EXPIRED"
    if not all(isfinite(v) and v>0 for v in (reference,mark)) or not isfinite(max_deviation) or max_deviation<0:
        return "INVALID_CLOSE_EVENT_PRICE"
    if abs(mark/reference-1)>max_deviation:return "CLOSE_EVENT_SLIPPAGE_LIMIT"
    return None


def ntd_management_plan(side,bars,teeth,lips,ao,ac,tick,opened_at,state=None):
    """NTD PDF115-120. Fast Lips selection takes effect on the NEXT bar.

    Chart angles depend on zoom; fast movement is explicitly formalized as a
    favourable 5-bar percentage-price slope steeper than the Lips slope.
    Five joint zone bars permanently select the one-bar protective ratchet.
    """
    state=dict(state or {})
    n=len(bars); sign=1 if side=="LONG" else -1
    if side not in {"LONG","SHORT"} or n<6 or not valid_edition_bars(bars) or tick<=0 or opened_at<=0:
        return {"state":state,"exit_reason":"","stop":None}
    if any(len(v)!=n for v in (teeth,lips,ao,ac)):
        return {"state":state,"exit_reason":"","stop":None}
    run=0
    for i in range(1,n):
        if bars[i].t+3_600_000<=opened_at:continue
        run=run+1 if all(isfinite(v) for v in (ao[i],ao[i-1],ac[i],ac[i-1])) and sign*(ao[i]-ao[i-1])>0 and sign*(ac[i]-ac[i-1])>0 else 0
        if run>=5:state.setdefault("zone_active_at",bars[i].t)
    stop=None;reason=""
    if state.get("zone_active_at",0)>0:
        stop=bars[-1].l-tick if sign==1 else bars[-1].h+tick
    else:
        activated=int(state.get("lips_selected_at",0) or 0)
        use_lips=0<activated<bars[-1].t
        line=lips[-1] if use_lips else teeth[-1]
        if isfinite(line) and line>0 and sign*(bars[-1].c-line)<0:
            reason="LIPS_CLOSE_EXIT" if use_lips else "TEETH_CLOSE_EXIT"
        if not activated and isfinite(lips[-6]) and isfinite(lips[-1]) and lips[-6]>0:
            price_slope=sign*(bars[-1].c/bars[-6].c-1)
            lips_slope=sign*(lips[-1]/lips[-6]-1)
            if price_slope>max(0,lips_slope):state["lips_selected_at"]=bars[-1].t
    return {"state":state,"exit_reason":reason,"stop":stop}
