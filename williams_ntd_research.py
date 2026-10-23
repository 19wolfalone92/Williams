"""NTD five-dimension evidence collection on genuine closed indicator bars.

The fifth dimension Balance Line (NTD ch8) is independent of TC2 Three
Wise Men. This report is NOT an execution engine; AO, AC and zones may add
to a campaign only after valid external-fractal / approved independent
initialization and owned exchange position has been confirmed.
"""
from __future__ import annotations

from williams_signals import (
    extract_ntd_balance_line_signal_specs, extract_ntd_fractal_signal_specs,
    extract_ntd_ao_signal_specs, extract_ntd_ac_signal_specs,
    extract_ntd_zone_add_on_specs,
)

def ntd_five_dimensions_research(symbol, ind, timeframe, tick_size) -> dict:
    """All five entry families by side, with distinct book roles and blockers.

    Caller MUST supply candles verified closed at the exchange. No external
    requests, order submissions, profile toggles or position mutations.
    """
    out={"mode":"NTD_FIVE_DIMENSIONS_RESEARCH_ONLY",
         "can_place_orders":False,"can_add_position":False,
         "long":{},"short":{},"errors":{}}
    families=(
        ("EXTERNAL_FRACTAL",extract_ntd_fractal_signal_specs),
        ("AO_5_34",extract_ntd_ao_signal_specs),
        ("AC",extract_ntd_ac_signal_specs),
        ("AO_AC_ZONE",extract_ntd_zone_add_on_specs),
        ("BALANCE_LINE",extract_ntd_balance_line_signal_specs),
    )
    for side in ("LONG","SHORT"):
        target=out[side.lower()]
        for name,fn in families:
            try:
                entries=fn(symbol,ind,side=side,timeframe=timeframe,tick_size=tick_size)
                target[name]=[{
                    "signal_id":s.signal_id,
                    "signal_type":s.signal_type.value,
                    "book_role":s.role.value,
                    "signal_closed_time_ms":s.signal_bar_time_ms,
                    "trigger_price":s.trigger_price,
                    "protective_reference":s.protective_reference,
                    "can_place_orders":False,
                } for s in entries]
            except (KeyError,IndexError,ValueError,TypeError,OverflowError) as exc:
                target[name]=[]
                out["errors"][f"{side}:{name}"]=type(exc).__name__
    if out["errors"]:
        out["reason"]="PARTIAL_NTD_EVIDENCE_MISSING; NEVER EXECUTE"
    else:
        out["reason"]="READ_ONLY_RESEARCH_REPORT; EXECUTION_REQUIRES_OWNED_CYCLE_AND_FINAL_EXCHANGE_GATES"
    return out
