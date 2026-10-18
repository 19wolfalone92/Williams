"""The NTD zone add-on requires genuine indicator evidence, never a forged gate."""
import pandas as pd
import pytest

import williams_profiles
from campaign_model import SignalType
from williams_edition_rules import NTD


def frame(side, confirmed):
    n=40
    if side=="LONG":
        prices=[100.0]*36+[100.0,101.0,102.0,103.0]
        jaw,teeth,lips=95.0,94.0,96.0
    else:
        prices=[200.0]*36+[200.0,199.0,198.0,197.0]
        jaw,teeth,lips=205.0,206.0,204.0
    df=pd.DataFrame({
        "open_time_ms":[1_700_000_000_000+i*3_600_000 for i in range(n)],
        "open":prices,"close":prices,
        "high":[x+1 for x in prices],"low":[x-1 for x in prices],
        "volume":[100.0]*n,
        "jaw_current":[jaw]*n,"teeth_current":[teeth]*n,
        "lips_current":[lips]*n,
        "ao":[0.0]*36+([0.,1.,2.,3.] if side=="LONG" else [0.,-1.,-2.,-3.]),
        "ac":[0.0]*36+([0.,1.,2.,3.] if side=="LONG" else [0.,-1.,-2.,-3.]),
        "long_fractal_outside":[confirmed if side=="LONG" else False]*n,
        "short_fractal_outside":[confirmed if side=="SHORT" else False]*n,
    })
    return df


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_ntd_zone_add_on_does_not_fabricate_fractal_proof(monkeypatch,side):
    # Isolate the real zone detector from unrelated NTD signal families.
    for name in ("extract_ntd_fractal_signal_specs","extract_ntd_ao_signal_specs",
                 "extract_ntd_ac_signal_specs"):
        monkeypatch.setattr(williams_profiles,name,lambda *args,**kwargs:[])
    monkeypatch.setattr(williams_profiles,"ntd_balance_line_setup",
                        lambda *args,**kwargs:None)

    unsafe=frame(side,False)
    signals=williams_profiles.extract_profile_signals("BTCUSDT",unsafe,
        profile=NTD,side=side,timeframe="1h",tick_size=.1)
    assert all(s.signal_type!=SignalType.ZONE_ADD_ON for s in signals)

    valid=frame(side,True)
    evidence=williams_profiles.extract_profile_signals("BTCUSDT",valid,
        profile=NTD,side=side,timeframe="1h",tick_size=.1)
    zone=[s for s in evidence if s.signal_type==SignalType.ZONE_ADD_ON]
    assert len(zone)==1
    assert zone[0].execution_semantics=="MARKET_ON_CLOSE"
    # Scanning the opposite signal family must not turn on a different gate.
    opposite="SHORT" if side=="LONG" else "LONG"
    assert not any(s.signal_type==SignalType.ZONE_ADD_ON for s in
        williams_profiles.extract_profile_signals("BTCUSDT",valid,
        profile=NTD,side=opposite,timeframe="1h",tick_size=.1))


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_ntd_missing_confirmed_fractal_field_fails_closed(monkeypatch,side):
    for name in ("extract_ntd_fractal_signal_specs","extract_ntd_ao_signal_specs",
                 "extract_ntd_ac_signal_specs"):
        monkeypatch.setattr(williams_profiles,name,lambda *args,**kwargs:[])
    monkeypatch.setattr(williams_profiles,"ntd_balance_line_setup",
                        lambda *args,**kwargs:None)
    data=frame(side,False)
    data=data.drop(columns=["long_fractal_outside","short_fractal_outside"])
    result=williams_profiles.extract_profile_signals("BTCUSDT",data,
        profile=NTD,side=side,timeframe="1h",tick_size=.1)
    assert all(s.signal_type!=SignalType.ZONE_ADD_ON for s in result)
