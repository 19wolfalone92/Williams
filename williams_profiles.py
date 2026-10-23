"""Select all Williams evidence sources while retaining edition-owned campaigns."""
from dataclasses import replace
from math import isfinite

from campaign_model import SignalRole, SignalSpec, SignalType
from williams_edition_rules import (
    TC2,NTD,COURSE,UNIFIED,SOURCE_PROFILES,PROFILES,ntd_balance_line_setup,course_hump_setup,
    course_thumb_setup,course_squat_setup,course_rhythm,
)
from williams_signals import (
    extract_long_signal_specs,extract_short_signal_specs,
    extract_ntd_fractal_signal_specs,extract_ntd_ao_signal_specs,
    extract_ntd_ac_signal_specs,extract_ntd_zone_add_on_specs,
)
from williams_wave_hierarchy import FRAME_MS,wave_bars_from_dataframe


def addition_signal_time_allowed(signal,campaign):
    if signal.signal_id==campaign.origin_signal_id:return False
    if campaign.tags.get("strategy_profile",TC2)==NTD:
        return signal.signal_bar_time_ms>=int(campaign.tags.get("signal_bar_time_ms",0))>0
    return signal.signal_bar_time_ms>int(campaign.tags.get("last_signal_time_ms",0))


def observe_edition_signals(symbol,ind,*,timeframe,tick_size):
    """Independent closed-bar observations; failure of one source stays local.

    The returned signals are diagnostic evidence, never an execution pool.
    A no-signal result and an analysis failure have different states.
    """
    signals=[]
    observations=[]
    from williams_execution_policy import new_exposure_blocker
    for source in SOURCE_PROFILES:
        try:
            found=[s for side in ("LONG","SHORT") for s in extract_profile_signals(
                symbol,ind,profile=source,side=side,timeframe=timeframe,tick_size=tick_size)]
            signals.extend(found)
            observations.append({"profile":source,"state":"FORMATION_OBSERVED" if found else "NO_VALID_FORMATION",
                "signal_count":len(found),"execution_role":"LIVE" if new_exposure_blocker(source) is None else "OBSERVE",
                "can_place_orders":False})
        except Exception as exc:
            observations.append({"profile":source,"state":"ANALYSIS_FAILED","error_type":type(exc).__name__,
                "execution_role":"LIVE" if new_exposure_blocker(source) is None else "OBSERVE","can_place_orders":False})
    return signals,observations


def extract_profile_signals(symbol,ind,*,profile,side,timeframe,tick_size,exit_mode=False,**tc2_kwargs):
    if profile not in PROFILES or side not in {"LONG","SHORT"}:
        raise ValueError("Invalid Williams edition or direction")
    if ind is None or ind.empty:return []
    if profile==UNIFIED:
        # A missing open/volume or indicator is not permission to fall back
        # silently to a different trading system. Incomplete data fail closed.
        if not {"open","high","low","close","volume","ao","ac"}.issubset(ind.columns):
            return []
        # Independent source-specific detectors share a closed-bar snapshot,
        # but their orders retain the source's initiating and management rules.
        combined = [
            s for source in SOURCE_PROFILES
            for s in extract_profile_signals(symbol,ind,profile=source,side=side,
                timeframe=timeframe,tick_size=tick_size,exit_mode=exit_mode,**tc2_kwargs)
        ]
        return sorted({s.signal_id:s for s in combined}.values(),
                      key=lambda s:(s.signal_bar_time_ms,s.signal_id))
    if profile==TC2:
        detector=extract_long_signal_specs if side=="LONG" else extract_short_signal_specs
        return detector(symbol,ind,timeframe=timeframe,tick_size=tick_size,**tc2_kwargs)
    bars=wave_bars_from_dataframe(ind)
    if timeframe not in FRAME_MS or any(b.t-a.t!=FRAME_MS[timeframe] for a,b in zip(bars,bars[1:])):return []
    specs=[]
    setups=[]
    if profile==NTD:
        kwargs=dict(side=side,timeframe=timeframe,tick_size=tick_size)
        for detector in (extract_ntd_fractal_signal_specs,extract_ntd_ao_signal_specs,extract_ntd_ac_signal_specs,extract_ntd_zone_add_on_specs):
            work=ind
            # Never fabricate the independently calculated confirmed-fractal
            # gate to make AO/AC zone additions appear admissible. The
            # detector consumes the REAL long/short_fractal_outside field
            # from strategy.py. Missing or false evidence fails closed,
            # independently of exchange campaign ownership and risk checks.
            if detector==extract_ntd_ao_signal_specs:
                # NTD PDF63-67: keep an unhit AO formation after its signal
                # candle. A later colour recovery cannot revive a cancelled stop.
                sign=1 if side=="LONG" else -1
                for omitted in range(min(4,max(0,len(work)-3))+1):
                    prefix=work if not omitted else work.iloc[:-omitted]
                    for signal in detector(symbol,prefix,**kwargs):
                        later=work.iloc[len(prefix):]
                        osc=[float(v) for v in work.ao]
                        colour=all(isfinite(osc[j]) and isfinite(osc[j-1]) and sign*(osc[j]-osc[j-1])>0
                            for j in range(len(prefix),len(work)))
                        unhit=not any((float(b.high)>=signal.trigger_price if side=="LONG" else float(b.low)<=signal.trigger_price)
                            for b in later.itertuples())
                        if colour and unhit:specs.append(signal)
            else:
                specs.extend(detector(symbol,work,**kwargs,**({"exit_mode":exit_mode} if detector==extract_ntd_zone_add_on_specs else {})))
        def line(name):
            col=next((c for c in (name+"_current",name+"_shifted",name) if c in ind),None)
            return [float(v) for v in ind[col]] if col else [float("nan")]*len(ind)
        if {"ao","ac"}.issubset(ind.columns):
            setup=ntd_balance_line_setup(side,bars,line("jaw"),line("teeth"),line("lips"),
                [float(v) for v in ind.ao],[float(v) for v in ind.ac],tick_size,require_mouth=not exit_mode)
            if setup:setups.append(setup)
        # The sleep/first-breakout rule concerns a NEW NTD cycle only.
        # Keep later fractals visible as additions; never gate TC2 on sleep.
        if not exit_mode:
            from williams_market_geometry import ntd_initial_context
            updated=[]
            for signal in specs:
                if signal.signal_type==SignalType.FRACTAL:
                    proof=ntd_initial_context(bars,line("jaw"),line("teeth"),line("lips"),signal.source_candle_index,tick_size)
                    signal=replace(signal,role=SignalRole.ENTRY if proof["eligible"] else SignalRole.ADD_ON,
                        setup_context=signal.setup_context | {"ntd_initial":proof})
                updated.append(signal)
            specs=updated
        # NTD additional dimensions cannot initiate before the first fractal.
        # They also must point out of the current mouth, rather than feed it.
        mouth=[line(n)[-1] for n in ("jaw","teeth","lips")]
        if exit_mode:pass
        elif all(isfinite(v) and v>0 for v in mouth):
            specs=[s for s in specs if s.role==SignalRole.ENTRY or (
                s.trigger_price>max(mouth) if side=="LONG" else s.trigger_price<min(mouth))]
        else:
            specs=[s for s in specs if s.role==SignalRole.ENTRY]
    else:
        rhythm=course_rhythm(bars)
        direction="UP" if side=="LONG" else "DOWN"
        for detector in (course_hump_setup,course_thumb_setup,course_squat_setup):
            setup=detector(side,bars,tick_size)
            if setup is None:continue
            # Course PDF92: Hump/fractal follows current AO momentum. The
            # Thumb is a distinct counter-move tool for a bracketed market.
            if not exit_mode and setup.kind=="COURSE_HUMP" and rhythm["momentum"]!=direction:continue
            if not exit_mode and setup.kind=="COURSE_SQUAT" and (rhythm["twr"]!=direction or rhythm["momentum"]!=direction):continue
            setups.append(replace(setup,context=setup.context|{"rhythm":rhythm}))
    for setup in setups:
        when=bars[setup.known_index].t
        if timeframe not in FRAME_MS or bars[-1].t-when>=5*FRAME_MS[timeframe]:continue
        specs.append(SignalSpec.new(symbol=symbol,side="BUY" if side=="LONG" else "SELL",
            signal_type=SignalType(setup.kind),role=SignalRole.ADD_ON if profile==NTD else SignalRole.ENTRY,
            timeframe=timeframe,signal_bar_time_ms=when,trigger_price=setup.trigger,
            protective_reference=setup.stop,invalidation_price=setup.invalidation,source_candle_index=setup.known_index,
            expires_at_ms=when+5*FRAME_MS[timeframe],strategy_profile=profile,setup_context=setup.context,
            reason=profile+": "+setup.kind+"; confirmed closed-bar formation"))
    specs=[replace(s,expires_at_ms=s.signal_bar_time_ms+FRAME_MS[timeframe]+90_000)
        if s.signal_type==SignalType.ZONE_ADD_ON and s.execution_semantics=="MARKET_ON_CLOSE" else s for s in specs]
    specs=[replace(s,signal_id=f"{s.symbol}:{timeframe}:{s.side}:BALANCE_LINE:{s.setup_context['formation_key']}")
        if s.signal_type==SignalType.BALANCE_LINE else s for s in specs]
    # A single-tick buffer also protects AO/AC/fractal signal-bar extrema.
    specs=[replace(s,protective_reference=s.protective_reference+(-tick_size if side=="LONG" else tick_size))
        if s.signal_type not in {SignalType.BALANCE_LINE,SignalType.COURSE_HUMP,SignalType.COURSE_THUMB,SignalType.COURSE_SQUAT} else s for s in specs]
    if profile==NTD and timeframe=="1h":
        from williams_execution_policy import h1_signal_expiry
        specs=[replace(s,expires_at_ms=h1_signal_expiry(s.signal_bar_time_ms,s.signal_type,NTD))
            if s.execution_semantics=="STOP_ENTRY" else s for s in specs]
    return sorted([replace(s,strategy_profile=profile,signal_id=f"{profile}:{s.signal_id}") for s in specs],
                  key=lambda s:(s.signal_bar_time_ms,s.signal_type.value))
