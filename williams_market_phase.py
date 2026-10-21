"""Degree-scoped, competing market phase evidence; no order authority."""


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
            "scale_bars":h.get("scale_bars",0),"signs":[name for key,name in signs.items() if h.get(key,False)]}
            for h in usable]
        state=("UNAVAILABLE" if frame in errors or (frame=="4h" and frame not in counts and not usable)
            else "UNRESOLVED" if not phases
            else "ALTERNATIVES" if len(phases)>1 else "PHASE_CANDIDATE")
        frames.append({"frame":frame,"state":state,"history_error":errors.get(frame) or None,
            "hypotheses":hypotheses,"history_candle_count":counts.get(frame),
            "hypothesis_count":len(hypotheses),"wave_degree_confirmed":False,
            "probability_calibrated":False,"can_place_orders":False,"can_close":False,
            "exhaustion_warning":any(h.get("divergence") or h.get("momentum_shift") for h in usable)})
    def warns(frame):
        return any(f["frame"]==frame and f["exhaustion_warning"] for f in frames)
    scope=("SENIOR_WARNING" if warns("1d") or warns("6h") or warns("4h") else "H1_WARNING" if warns("1h")
        else "M15_ONLY_WARNING" if warns("15m") else "NONE")
    return {"schema":"WILLIAMS_MARKET_PHASE_V1","as_of":as_of,"frames":frames,"warning_scope":scope,
        "wave_genealogy_confirmed":False,"probability_calibrated":False,
        "action_policy":"SOURCE_SIGNAL_AND_PRICE_TRIGGER_REQUIRED",
        "can_place_orders":False,"can_close":False,"can_reverse":False}
