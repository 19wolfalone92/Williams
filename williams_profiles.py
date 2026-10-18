"""Select one book edition; never silently mix its initiating/continuation rules."""
from dataclasses import replace
from math import isfinite

from campaign_model import SignalRole, SignalSpec, SignalType
from williams_edition_rules import (
    TC2,NTD,COURSE,PROFILES,ntd_balance_line_setup,course_hump_setup,
    course_thumb_setup,course_squat_setup,course_rhythm,
)
from williams_signals import (
    extract_long_signal_specs,extract_short_signal_specs,
    extract_ntd_fractal_signal_specs,extract_ntd_ao_signal_specs,
    extract_ntd_ac_signal_specs,extract_ntd_zone_add_on_specs,
)
from williams_wave_hierarchy import FRAME_MS,wave_bars_from_dataframe


def extract_profile_signals(symbol,ind,*,profile,side,timeframe,tick_size,exit_mode=False,**tc2_kwargs):
    if profile not in PROFILES or side not in {"LONG","SHORT"}:
        raise ValueError("Invalid Williams edition or direction")
    if ind is None or ind.empty:return []
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
            if detector==extract_ntd_zone_add_on_specs:
                work=ind.copy()
                work["long_fractal_outside" if side=="LONG" else "short_fractal_outside"]=True
                # This emits continuation evidence only. Durable fractal-cycle
                # ownership is required by both Futures execution coordinators.
            if exit_mode and detector==extract_ntd_fractal_signal_specs:
                work=ind.copy()
                # An opposing exit signal is allowed on the position's side of
                # the mouth. It cannot authorize a reverse/new position.
                work["teeth_current"]=1e-12 if side=="LONG" else float(ind.high.max())*100
            specs.extend(detector(symbol,work,**kwargs))
        def line(name):
            col=next((c for c in (name+"_current",name+"_shifted",name) if c in ind),None)
            return [float(v) for v in ind[col]] if col else [float("nan")]*len(ind)
        if {"ao","ac"}.issubset(ind.columns):
            setup=ntd_balance_line_setup(side,bars,line("jaw"),line("teeth"),line("lips"),
                [float(v) for v in ind.ao],[float(v) for v in ind.ac],tick_size,require_mouth=not exit_mode)
            if setup:setups.append(setup)
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
            if setup.kind=="COURSE_HUMP" and rhythm["momentum"]!=direction:continue
            if setup.kind=="COURSE_SQUAT" and (rhythm["twr"]!=direction or rhythm["momentum"]!=direction):continue
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
    specs=[replace(s,signal_id=f"{s.symbol}:{timeframe}:{s.side}:BALANCE_LINE:{s.setup_context['formation_key']}")
        if s.signal_type==SignalType.BALANCE_LINE else s for s in specs]
    # A single-tick buffer also protects AO/AC/fractal signal-bar extrema.
    specs=[replace(s,protective_reference=s.protective_reference+(-tick_size if side=="LONG" else tick_size))
        if s.signal_type not in {SignalType.BALANCE_LINE,SignalType.COURSE_HUMP,SignalType.COURSE_THUMB,SignalType.COURSE_SQUAT} else s for s in specs]
    return sorted([replace(s,strategy_profile=profile,signal_id=f"{profile}:{s.signal_id}") for s in specs],
                  key=lambda s:(s.signal_bar_time_ms,s.signal_type.value))
