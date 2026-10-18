import pandas as pd

from strategy import calculate_indicators, config_from_env
from williams_ntd_research import ntd_five_dimensions_research

def test_five_ntd_dimensions_are_separate_from_live_tc2_gate():
    prices=[120.0 + i*.05 for i in range(160)]
    rows=[dict(open=p-.1,high=p+.25,low=p-.25,close=p,volume=500+i,
               open_time_ms=(i+1)*3600000) for i,p in enumerate(prices)]
    ind=calculate_indicators(pd.DataFrame(rows),config_from_env({}))
    report=ntd_five_dimensions_research("BTCUSDT",ind,"1h",.01)
    assert report["mode"]=="NTD_FIVE_DIMENSIONS_RESEARCH_ONLY"
    assert not report["can_place_orders"]
    assert not report["can_add_position"]
    for side in ("long","short"):
        assert set(report[side])=={
            "EXTERNAL_FRACTAL","AO_5_34","AC","AO_AC_ZONE","BALANCE_LINE"}
        for signals in report[side].values():
            assert all(not sig["can_place_orders"] for sig in signals)

def test_invalid_indicator_frame_does_not_manufacture_ntd_entries():
    report=ntd_five_dimensions_research("BTCUSDT",pd.DataFrame(),"1h",.01)
    assert report["can_place_orders"] is False
    assert not any(signals for side in ("long","short")
                   for signals in report[side].values())
