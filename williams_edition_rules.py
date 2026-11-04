"""Edition-specific price rules, without orders or invented market data.

NTD PDF103-111: Balance Line (base +1/+2, adverse zone doubles).
Course PDF61-72/86-92: Hump leverage, Thumb fingers, two-fractal stop,
and the higher-frame two-bar Air Bag. Volumes are explicit kline proxies.
"""
from dataclasses import dataclass, field
from math import isfinite
from decimal import Decimal, InvalidOperation
from typing import Sequence

from williams_wave_hierarchy import WaveBar

TC2 = "TC2_THREE_WISE_MEN"
NTD = "NTD_FIVE_DIMENSIONS"
COURSE = "COURSE_PROFITUNITY"
UNIFIED = "WILLIAMS_UNIFIED"
SOURCE_PROFILES = (TC2, NTD, COURSE)
PROFILES = SOURCE_PROFILES + (UNIFIED,)


def profile_allows_source(selected: str, source: str) -> bool:
    """The scanner selection may combine sources; an order always pins one."""
    return source in SOURCE_PROFILES and (selected == UNIFIED or selected == source)


def unified_direction_conflict(directions) -> bool:
    """Do not allow competing fresh LONG/SHORT entry hypotheses to arm."""
    return len({side for side in directions if side in {"LONG", "SHORT"}}) > 1


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


def _edition_exact_source_tick(extreme, tick, *, above: bool):
    """One actual Binance PRICE_FILTER tick beyond an exact closed-bar extreme.

    Original NTD/Course source bar values must be representable on the
    exchange grid. Research-only; a resulting price is NOT exchange order
    acceptance or a protective STOP proof.
    """
    try:
        value = Decimal(str(extreme))
        step = Decimal(str(tick))
        if not value.is_finite() or not step.is_finite() or value <= 0 or step <= 0:
            return None
        if value % step != 0:
            return None
        result = value + step if above else value - step
        return float(result) if result > 0 and result.is_finite() else None
    except (InvalidOperation, OverflowError, TypeError, ValueError):
        return None


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
    trigger=_edition_exact_source_tick(extreme(target),tick,above=sign==1)
    if trigger is None:
        return None
    mouth=max(jaw[-1],teeth[-1],lips[-1]) if sign==1 else min(jaw[-1],teeth[-1],lips[-1])
    if trigger<=0 or (require_mouth and (mouth<=0 or sign*(trigger-mouth)<=0)):
        return None
    if any(sign*(extreme(i)-trigger)>=0 for i in range(base,n)):
        return None
    window=bars[max(0,base-4):base+1]
    protective_extreme=min(b.l for b in window) if sign==1 else max(b.h for b in window)
    stop=_edition_exact_source_tick(protective_extreme,tick,above=sign!=1)
    if stop is None or sign*(trigger-stop)<=0:return None
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
    colours=[]
    for i in fingers:
        if i<=0 or not bars[i].v>bars[i-1].v>0:
            colours.append("OTHER");continue
        current=(bars[i].h-bars[i].l)/bars[i].v
        previous=(bars[i-1].h-bars[i-1].l)/bars[i-1].v
        colours.append("SQUAT" if current<previous else "GREEN" if current>previous else "MFI_UNCHANGED")
    # PDF69 permits any two and explicitly gives the increasing-volume
    # shortcut. PDF71 calls mixed Squat+Green ideal and other setups weak;
    # it does not say the weak formation ceases to exist.
    strength=("IDEAL_MIXED" if "SQUAT" in colours and "GREEN" in colours else
              "WEAK" if sum(c in {"SQUAT","GREEN"} for c in colours)>=2 else "UNCONFIRMED_FLAT_MFI")
    thumb,middle=fingers[0],fingers[-1]
    # PDF69 figure: thumb is the OLDEST/lowest of the rising fingers.
    trigger=bars[thumb].l-tick if sign==1 else bars[thumb].h+tick
    stop=bars[middle].h+tick if sign==1 else bars[middle].l-tick
    if trigger<=0 or stop<=0:return None
    if any(b.l<=trigger if sign==1 else b.h>=trigger for b in bars[middle:]):return None
    return EditionSetup("COURSE_THUMB",side,middle,trigger,stop,stop,
        {"finger_indices":fingers,"thumb_index":thumb,"reverse_level":stop,
         "volume_input":"KLINE_VOLUME_PROXY","finger_profitunity_colours":colours,
         "thumb_strength":strength,"thumb_admission_basis":"COURSE_PDF69_VOLUME_SHORTCUT"})


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
            # Course Level III p.11: lower low PLUS one tick for a LONG
            # protective SELL; mirrored higher high MINUS one tick for SHORT.
            stop=b.l+tick if sign==1 else b.h-tick
            return stop if stop>0 else None
    return None


def course_initial_protection(side, formation_stop, airbag_stop, entry):
    """Course PDF86-87: retain formation protection AND the higher Air Bag.

    A tighter formation stop already gets flat before the Air Bag is crossed.
    Widening it discards that formation's own invalidation. A crossed Air Bag
    cannot authorize a new position in that direction.
    """
    if side not in {"LONG", "SHORT"} or not all(
            isinstance(p, (int, float)) and isfinite(p) and p > 0
            for p in (formation_stop, airbag_stop, entry)):
        return None
    if side == "LONG":
        return max(formation_stop, airbag_stop) if max(formation_stop, airbag_stop) < entry else None
    return min(formation_stop, airbag_stop) if min(formation_stop, airbag_stop) > entry else None


def course_rhythm(bars):
    if len(bars)<34 or not valid_edition_bars(bars):return {"twr":"UNKNOWN","momentum":"UNKNOWN"}
    mids=[(b.h+b.l)/2 for b in bars]
    def mean(n,end=None):
        v=mids[:end] if end is not None else mids
        return sum(v[-n:])/n
    fast,medium,slow=mean(5),mean(13),mean(34)
    twr="UP" if fast>max(medium,slow) else "DOWN" if fast<min(medium,slow) else "NEUTRAL"
    # Course Level II p14/PDF45: trading side is 5/34 versus its SMA(5).
    # The direction of the latest AO bar is a different rhythm observation.
    momentum="UNKNOWN";signal=None;oscillator=fast-slow
    if len(bars)>=38:
        recent=[]
        for end in range(len(bars)-5,len(bars)):
            recent.append(sum(mids[end-4:end+1])/5-sum(mids[end-33:end+1])/34)
        signal=sum(recent)/5
        momentum="UP" if oscillator>signal else "DOWN" if oscillator<signal else "NEUTRAL"
    return {"twr":twr,"momentum":momentum,"momentum_basis":"OSCILLATOR_VS_SIGNAL_SMA5",
            "oscillator":oscillator,"signal":signal}


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


def _ntd_source_exact_zone_stop(extreme, tick, sign):
    """NTD PDF94–96: one *actual* Binance PRICE_FILTER tick beyond source H1 bar.

    No float rounding: an off-grid source low/high cannot be silently shifted
    to make a plausible book STOP. This is a research-level proposal only.
    """
    if sign not in {1, -1}:
        return None
    try:
        price, step = Decimal(str(extreme)), Decimal(str(tick))
        if not (price.is_finite() and step.is_finite()) or price <= 0 or step <= 0 or price % step:
            return None
        level = price - step if sign == 1 else price + step
        return float(level) if level > 0 and level.is_finite() else None
    except (InvalidOperation, ValueError, TypeError, OverflowError):
        return None


def _ntd_zone_candle_fingerprint(bar):
    return "|".join(str(float(v)) for v in (bar.o, bar.h, bar.l, bar.c, bar.v))


def ntd_management_plan(side,bars,teeth,lips,ao,ac,tick,opened_at,state=None):
    """Read-only NTD source exit and sticky fifth-zone STOP advisory.

    NTD pp.94–96: after five consecutive AO+AC zone bars, start one tick
    beyond that fifth candle; tighten after each subsequent CLOSED candle
    regardless of AO/AC colour. A historical stop hit is ambiguous on OHLC
    alone, so never fabricate an actual Binance fill or continuation.

    This mirrors native Kotlin williamsNtdManagementPlan; an exchange STOP
    can be proved only by authenticated Binance order records.
    """
    state = dict(state or {})
    empty = lambda: {"state":state, "exit_reason":"", "stop":None}
    n = len(bars)
    sign = 1 if side == "LONG" else -1
    h1 = 3_600_000
    if (side not in {"LONG", "SHORT"} or n < 6 or
            not valid_edition_bars(bars) or
            not isinstance(tick, (int, float)) or not isfinite(tick) or tick <= 0 or
            opened_at <= 0 or any(len(v) != n for v in (teeth,lips,ao,ac))):
        return empty()
    if (any(b.t <= 0 or b.t % h1 for b in bars) or
            any(b.t-a.t != h1 for a,b in zip(bars,bars[1:]))):
        state["zone_book_evidence"] = "NTD_H1_HISTORY_GAP"
        return empty()
    if bars[-1].t + h1 <= opened_at:
        return empty()

    # A previous observer position cannot lend its STOP and wave source time
    # to a newly opened paper campaign.
    old_activation = int(state.get("zone_active_at",0) or 0)
    if old_activation and old_activation + h1 <= opened_at:
        for key in ("zone_active_at","zone_book_stop","zone_book_stop_source_time_ms",
                    "zone_book_last_checked_at_ms","zone_book_last_checked_ohlcv",
                    "zone_historical_stop_touch_at_ms","zone_book_evidence"):
            state.pop(key,None)

    run = 0
    for i in range(1,n):
        if bars[i].t + h1 <= opened_at:
            continue
        run = (run + 1 if
            all(isfinite(v) for v in (ao[i],ao[i-1],ac[i],ac[i-1])) and
            sign*(ao[i]-ao[i-1]) > 0 and sign*(ac[i]-ac[i-1]) > 0 else 0)
        if run >= 5 and not state.get("zone_active_at"):
            state["zone_active_at"] = bars[i].t

    if state.get("zone_active_at",0):
        activation = int(state["zone_active_at"])
        activation_i = next((i for i,b in enumerate(bars) if b.t==activation),-1)
        replay_stop = None
        replay_source = activation

        if activation_i>=0:
            replay_start=activation_i
        else:
            # The earlier fifth bar has rolled out of the local H1 window.
            # A bare persisted stop is not acceptable proof of its source.
            checked_at=int(state.get("zone_book_last_checked_at_ms",0) or 0)
            cursor=next((i for i,b in enumerate(bars) if b.t==checked_at),-1)
            stored=state.get("zone_book_stop")
            candle=bars[cursor] if cursor>=0 else None
            if (candle is None or
                state.get("zone_book_last_checked_ohlcv")!=
                    _ntd_zone_candle_fingerprint(candle) or
                not isinstance(stored,(int,float)) or not isfinite(stored) or
                stored<=0 or
                _ntd_source_exact_zone_stop(stored,tick,-sign) is None):
                state["zone_book_evidence"]="NTD_ZONE_HISTORY_WINDOW_UNVERIFIED"
                return empty()
            replay_stop=float(stored)
            replay_source=int(state.get("zone_book_stop_source_time_ms",checked_at))
            replay_start=cursor+1

        for i in range(replay_start,n):
            bar=bars[i]
            if ((activation_i<0 or i>replay_start) and replay_stop is not None and
                    (bar.l <= replay_stop if sign==1 else bar.h >= replay_stop)):
                state["zone_historical_stop_touch_at_ms"]=bar.t
                state["zone_book_evidence"]="HISTORICAL_BOOK_STOP_TOUCHED_RECONCILE_EXCHANGE"
                state["zone_book_stop_authorized_order"]=False
                return empty()

            extreme=bar.l if sign==1 else bar.h
            candidate=_ntd_source_exact_zone_stop(extreme,tick,sign)
            if candidate is None:
                state["zone_book_evidence"]="NTD_BOOK_PRICE_OFF_EXCHANGE_TICK_GRID"
                state["zone_book_stop_authorized_order"]=False
                return empty()
            if replay_stop is None or sign*(candidate-replay_stop)>0:
                replay_stop=candidate
                replay_source=bar.t

        prior=state.get("zone_book_stop")
        if isinstance(prior,(int,float)) and isfinite(prior) and prior>0 and (
                sign*(replay_stop-float(prior))<0):
            state["zone_book_evidence"]="NTD_REPLAY_CONFLICTS_WITH_PERSISTED_STOP"
            state["zone_book_stop_authorized_order"]=False
            return empty()
        state.update(
            zone_book_stop=replay_stop,
            zone_book_stop_source_time_ms=replay_source,
            zone_book_last_checked_at_ms=bars[-1].t,
            zone_book_last_checked_ohlcv=_ntd_zone_candle_fingerprint(bars[-1]),
            zone_book_evidence=("REPLAYED_COMPLETE_CLOSED_H1_PATH" if activation_i>=0
                  else "REPLAYED_FROM_DURABLE_CLOSED_BAR_CHECKPOINT"),
            zone_book_rule="NTD_PDF94_96_FIVE_SAME_COLOUR_STICKY_CLOSED_BAR",
            zone_book_stop_authorized_order=False)
        return {"state":state,"exit_reason":"","stop":replay_stop}

    activated=int(state.get("lips_selected_at",0) or 0)
    use_lips=0<activated<bars[-1].t
    line=lips[-1] if use_lips else teeth[-1]
    reason=("LIPS_CLOSE_EXIT" if use_lips else "TEETH_CLOSE_EXIT") if (
        isfinite(line) and line>0 and sign*(bars[-1].c-line)<0) else ""
    if (not activated and isfinite(lips[-6]) and isfinite(lips[-1]) and lips[-6]>0):
        price_slope=sign*(bars[-1].c/bars[-6].c-1)
        lips_slope=sign*(lips[-1]/lips[-6]-1)
        if price_slope>max(0,lips_slope):
            state["lips_selected_at"]=bars[-1].t
    return {"state":state,"exit_reason":reason,"stop":None}
