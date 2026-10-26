"""Causal decision evidence, separate from exchange admission and execution proof."""
from math import isfinite
from williams_edition_rules import TC2,NTD,COURSE,UNIFIED,edition_fractals,valid_edition_bars
from williams_market_phase import market_phase_context
from williams_market_reading import market_reading
from williams_execution_policy import execution_policy_snapshot
def unified_initial_conflict(signals):
    entries=[s for s in signals if s.role.value=="ENTRY"]
    latest=max((s.signal_bar_time_ms for s in entries),default=0)
    return len({s.side for s in entries if s.signal_bar_time_ms==latest})>1


def build_decision_trace(symbol,profile,input_bars,now,indicators,signals,hierarchy):
    bars=[b for b in input_bars if 0<b.t<=now-3_600_000]
    contiguous=all(b.t-a.t==3_600_000 for a,b in zip(bars,bars[1:]))
    reasons=[]
    if len(bars)<45 or not valid_edition_bars(bars) or not contiguous:reasons.append("CLOSED_H1_HISTORY_NOT_PROVEN")
    if not bars or now-bars[-1].t>7_200_000:reasons.append("H1_SNAPSHOT_STALE")
    if profile==UNIFIED and unified_initial_conflict(signals):reasons.append("OPPOSING_ENTRIES_BEFORE_CONTEXT")
    if not any(s.role.value=="ENTRY" and (profile==UNIFIED or s.strategy_profile==profile) for s in signals):reasons.append("NO_VALID_INITIAL_FORMATION")
    def oscillator(i):
        if i<33:return None
        def mean(n):return sum((b.h+b.l)/2 for b in bars[i-n+1:i+1])/n
        value=mean(5)-mean(34)
        return value if isfinite(value) else None
    divergences=[]
    for kind in ("HIGH","LOW"):
        pivots=[p for p in edition_fractals(bars) if p.kind==kind and oscillator(p.center) is not None][-2:]
        if len(pivots)!=2:continue
        a,b=pivots;first,second=oscillator(a.center),oscillator(b.center)
        def point(p,value):return {"bar_time_ms":bars[p.center].t,"price":p.price,"ao":value,"confirmed_at_ms":bars[p.confirmation].t+3_600_000}
        divergences.append({"direction":"BEARISH" if kind=="HIGH" else "BULLISH",
            "observed":b.price>a.price and second<first if kind=="HIGH" else b.price<a.price and second>first,
            "first":point(a,first),"second":point(b,second)})
    books=[]
    for source in (TC2,NTD,COURSE):
        evidence=[]
        for s in signals:
            for item in s.setup_context.get("book_evidence",[{"profile":s.strategy_profile,"signal_id":s.signal_id,"family":s.signal_type.value,"trigger":s.trigger_price}]):
                if item["profile"]==source:evidence.append(dict(item,signal_bar_time_ms=s.signal_bar_time_ms,
                    direction="LONG" if s.side=="BUY" else "SHORT",selected=s.setup_context.get("source_signal_id",s.signal_id)==item["signal_id"]))
        books.append({"profile":source,"state":"FORMATION_OBSERVED" if evidence else "NO_VALID_FORMATION","evidence":evidence,
            "execution_role":"LIVE" if source in (TC2,NTD) else "OBSERVE","can_place_orders":False})
    phase=market_phase_context(hierarchy)
    h1=next((f for f in phase["frames"] if f["frame"]=="1h"),{})
    reading=market_reading(bars,phase,frame="1h",as_of=now)
    chart_fresh=reading.get("state")=="OBSERVED"
    alligator=reading.get("alligator",{}).get("state","UNKNOWN") if chart_fresh else "UNKNOWN"
    bias=reading.get("observed_bias","UNKNOWN") if chart_fresh else "UNKNOWN"
    ao_dir=reading.get("ao_direction","UNKNOWN") if chart_fresh else "UNKNOWN"
    contexts=[]
    for s in signals:
        side="LONG" if s.side=="BUY" else "SHORT"
        flags=[]
        if not chart_fresh:flags.append("H1_READING_UNVERIFIED")
        if h1.get("state")=="ALTERNATIVES":flags.append("COMPETING_WAVE_COUNTS")
        if h1.get("state") not in ("PHASE_CANDIDATE","ALTERNATIVES"):
            flags.append("WAVE_STRUCTURE_UNRESOLVED")
        if alligator=="SLEEPING":flags.append("SLEEPING_RANGE_POTENTIAL_BREAKOUT")
        if alligator=="SATIATED":flags.append("SATIATED_TREND_POTENTIAL_EXHAUSTION")
        if (side=="LONG" and bias=="DOWN") or (side=="SHORT" and bias=="UP"):
            flags.append("OBSERVED_H1_BIAS_OPPOSES_ENTRY")
        if (side=="LONG" and ao_dir=="DOWN") or (side=="SHORT" and ao_dir=="UP"):
            flags.append("AO_MOMENTUM_OPPOSES_ENTRY")
        if not (0<s.signal_bar_time_ms<now):flags.append("SIGNAL_BAR_NOT_CAUSALLY_KNOWN")
        contexts.append({"signal_id":s.signal_id,"source":s.strategy_profile,"family":s.signal_type.value,
            "direction":side,"signal_bar_time_ms":s.signal_bar_time_ms,
            "h1_reading_state":reading.get("state"),"observed_h1_close_ms":reading.get("as_of_close_ms",0),
            "alligator_phase":alligator,"observed_bias":bias,"ao_direction":ao_dir,
            "wave_state":h1.get("state","UNRESOLVED"),"distinct_wave_structures":h1.get("distinct_structural_hypotheses",0),
            "flags":flags,"context_is_trade_veto":False,"wave_degree_confirmed":False,
            "probability_calibrated":False,"can_place_orders":False,"can_close":False})
    active=[s for s in hierarchy.get("scenarios",[]) if s.get("frame")=="1h"
        and s.get("status")=="PROVISIONAL" and hierarchy.get("as_of",0)>0
        and s.get("observed_at")==hierarchy.get("as_of")]
    wave_state={"PHASE_CANDIDATE":"PROVISIONAL","ALTERNATIVES":"ALTERNATIVES"}.get(h1.get("state"),"UNRESOLVED")
    return {"schema":"WILLIAMS_DECISION_TRACE_V1","engine":"PYTHON","symbol":symbol,"strategy_profile":profile,
        "frame":"1h","observed_at_ms":now,"as_of_close_ms":bars[-1].t+3_600_000 if bars else 0,
        "closed_bar_count":len(bars),"history_contiguous":contiguous,"indicators":{k:v if isfinite(v) else None for k,v in indicators.items()},
        "divergences":divergences,"wave_state":wave_state,
        "wave_hypotheses":active,"books":books,"signals":[s.to_dict() for s in signals],
        "entry_market_contexts":contexts,
        "execution_policy":execution_policy_snapshot(),
        "market_phase":phase,
        "market_reading":reading,
        "candles":[[b.t,b.o,b.h,b.l,b.c,b.v] for b in bars[-150:]],
        "decision":{"stage":"CLOSED_BAR_ANALYSIS","state":"BLOCKED" if any(r!="OPPOSING_ENTRIES_BEFORE_CONTEXT" for r in reasons) else "CONTEXT_REQUIRED" if reasons else "CANDIDATE_REQUIRES_PREFLIGHT",
            "reasons":reasons,"can_place_orders":False}}
