"""Degree-scoped, competing market phase evidence; no order authority."""
from williams_wave_hierarchy import FRAME_MS, REQUIRED_FRAMES, build_wave_hierarchy
from williams_market_reading import market_reading
from williams_wave_interpretation import wave_interpretation
from williams_tc1_terminal_evidence import tc1_five_bullet_evidence
from williams_course_correction import correction_policy_snapshot

OBSERVED_MARKET_FRAMES=("1d","6h","4h","1h","15m")


def observed_market_context(history,as_of,read_errors=None):
    """Bounded causal snapshot of already stored candles, without any network IO."""
    read_errors=read_errors or {}
    closed={frame:[b for b in history.get(frame,[]) if b.t+FRAME_MS[frame]<=as_of][-240:]
            for frame in OBSERVED_MARKET_FRAMES}
    result=build_wave_hierarchy(closed,as_of,analysis_frames=OBSERVED_MARKET_FRAMES).to_dict()
    result.update(context_source="DEVICE_CLOSED_CANDLE_SNAPSHOT",scope="OBSERVATION_ONLY",
                  can_close=False,can_reverse=False)
    result["frame_errors"].update(read_errors)
    phase=market_phase_context(result)
    result["frame_readings"]=[]
    for frame in OBSERVED_MARKET_FRAMES:
        reading=market_reading(closed[frame],phase,frame,as_of)
        if frame in read_errors:reading.update(state="HISTORY_READ_FAILED",history_error=read_errors[frame])
        result["frame_readings"].append(reading)
    return result


def market_phase_context(hierarchy):
    as_of=hierarchy.get("as_of",0)
    errors=hierarchy.get("frame_errors",{})
    counts=hierarchy.get("frame_candle_counts",{})
    signs={"divergence":"AO_DIVERGENCE","momentum_shift":"MOMENTUM_SHIFT",
        "terminal_fractal":"TERMINAL_FRACTAL","target_zone":"TARGET_ZONE","squat":"SQUAT_VOLUME_PROXY"}
    frames=[]
    for frame in ("1d","6h","4h","1h","15m"):
        usable=[h for h in hierarchy.get("scenarios",[]) if h.get("frame")==frame
            and h.get("status")=="PROVISIONAL" and 0<h.get("observed_at",0)<=as_of
            and len(h.get("anchors",[]))>=2
            and all(0<a.get("known_at",0)<=as_of for a in h["anchors"])
            and h.get("active_direction") in {"UP","DOWN"}
            and h.get("active_label") in {"W1","W2","W3","W4","W5","A","B","C"}]
        if frame in errors:
            usable=[]
        phases={(h["active_label"],h["active_direction"]) for h in usable}
        hypotheses=[{"scenario_id":h.get("scenario_id",""),
            "last_confirmed_leg":h.get("last_confirmed_leg",h.get("label","")),
            "active_label":h["active_label"],"active_direction":h["active_direction"],
            "impulse_direction":h.get("direction",""),"invalidation":h.get("invalidation"),
            "w4_zero":h.get("w4_zero",""),"scale_frame":h.get("scale_frame",""),
            "third_momentum":h.get("third_momentum"),
            "tc1_five_bullets":tc1_five_bullet_evidence(h),
            "scale_bars":h.get("scale_bars",0),"signs":[name for key,name in signs.items() if h.get(key,False)]}
            for h in usable]
        state=("NOT_ANALYSED" if as_of<=0 else "UNAVAILABLE" if frame in errors or (frame=="4h" and frame not in counts and not usable)
            else "UNRESOLVED" if not phases
            else "ALTERNATIVES" if len(phases)>1 else "PHASE_CANDIDATE")
        frames.append({"frame":frame,"state":state,"history_error":"WAVE_ANALYSIS_NOT_RUN" if as_of<=0 else errors.get(frame) or None,
            "reason":("WAVE_ANALYSIS_NOT_RUN" if as_of<=0 else errors[frame] if frame in errors else
                "NO_UNAMBIGUOUS_CAUSAL_WAVE_COUNT" if not phases else "COMPETING_WAVE_COUNTS" if len(phases)>1 else "PROVISIONAL_COUNT_NOT_CERTAINTY"),
            "closed_bar_count":counts.get(frame,0),
            "examined_scenarios":sum(h.get("frame")==frame for h in hierarchy.get("scenarios",[])),
            "market_reading":next((r for r in hierarchy.get("frame_readings",[]) if r["frame"]==frame),None),
            "hypotheses":hypotheses,"history_candle_count":counts.get(frame),
            "hypothesis_count":len(hypotheses),"wave_degree_confirmed":False,
            "probability_calibrated":False,"can_place_orders":False,"can_close":False,
            "exhaustion_warning":any(h.get("divergence") or h.get("momentum_shift") for h in usable)})
    def warns(frame):
        return any(f["frame"]==frame and f["exhaustion_warning"] for f in frames)
    scope=("SENIOR_WARNING" if warns("1d") or warns("6h") or warns("4h") else "H1_WARNING" if warns("1h")
        else "M15_ONLY_WARNING" if warns("15m") else "NONE")
    recursive=[]
    for item in hierarchy.get("complex_wave_patterns",[]):
        proof=item.get("recursive_proof")
        if (not isinstance(proof,dict) or item.get("frame") in errors
                or item.get("child_frame") in errors or item.get("known_at",as_of)>as_of):
            continue
        state=proof.get("status","UNRESOLVED")
        recursive.append({"parent_frame":item.get("frame"),"child_frame":item.get("child_frame"),
            "pattern":item.get("pattern"),"state":state,"requested_depth":proof.get("requested_depth",0),
            "frame_path":proof.get("frame_path",[]),"reason":proof.get("reason",""),
            "explanation_ru":("Внутренняя геометрия подтверждена на запрошенных масштабах; уникальная волна НЕ доказана."
                if state=="OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED"
                else "Глубина анализа ограничена: не все подволны подтверждены, нужна младшая история."
                if state=="PARTIAL_PRICE_GRAMMAR"
                else "Подволны не подтверждены: требуется пересмотр счёта или дополнительные закрытые свечи."),
            "unique_elliott_genealogy_proven":False,"can_place_orders":False,"can_close":False,"can_reverse":False})
        if len(recursive)>=16:
            break
    return {"schema":"WILLIAMS_MARKET_PHASE_V1","as_of":as_of,"frames":frames,"warning_scope":scope,
        "course_correction_policy":correction_policy_snapshot(),
        "recursive_degree_evidence":recursive,
        "wave_interpretation":wave_interpretation(hierarchy),
        "wave_genealogy_confirmed":False,"probability_calibrated":False,
        "action_policy":"SOURCE_SIGNAL_AND_PRICE_TRIGGER_REQUIRED",
        "can_place_orders":False,"can_close":False,"can_reverse":False}
