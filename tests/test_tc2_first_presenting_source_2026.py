"""TC2 chapter 12 PDF156–157: any one Wise Man can initiate a campaign.

These are intentionally independent semantic admission regressions (not a
claim that historical signals always earn money or that Binance confirmed a SL).
"""
import pandas as pd
import pytest

from campaign_model import SignalRole, SignalType
from williams_signals import extract_long_signal_specs,extract_short_signal_specs


@pytest.mark.parametrize("side",["LONG","SHORT"])
@pytest.mark.parametrize("family,signal_type",[
    ("WM1",SignalType.REVERSAL),
    ("WM2",SignalType.SUPER_AO),
    ("WM3",SignalType.FRACTAL),
])
def test_tc2_each_first_presenting_wise_man_can_generate_own_initial_entry(
    monkeypatch,side,family,signal_type
):
    import williams_signals as signals
    price=105.0 if side=="LONG" else 95.0
    protective=95.0 if side=="LONG" else 105.0
    # Real closed signal time; all other Wise Men are absent.
    frame=pd.DataFrame({
        "open":[100.0]*4,"high":[105.0]*4,"low":[95.0]*4,
        "close":[100.0]*4,
        "teeth_current":[100.0]*4,
        "open_time_ms":[(i+1)*3_600_000 for i in range(4)],
        "bullish_alligator":[side=="LONG"]*4,
        "bearish_alligator":[side=="SHORT"]*4,
        "alligator_awake":[True]*4,
    })
    monkeypatch.setattr(signals,"_latest_reversal",
        lambda *_a,**_k:(2,price,protective) if family=="WM1" else None)
    monkeypatch.setattr(signals,"_latest_super_ao",
        lambda *_a,**_k:(2,price,protective) if family=="WM2" else None)
    monkeypatch.setattr(signals,"_latest_confirmed_fractal",
        lambda *_a,**_k:(2,1,price,protective,100.0) if family=="WM3" else None)
    monkeypatch.setattr(signals,"_latest_three_price_bar_signal",lambda *_a,**_k:None)
    monkeypatch.setattr(signals,"_trigger_still_pending",lambda *_a,**_k:True)
    monkeypatch.setattr(signals,"_angulation",lambda *_a,**_k:(1.0,True))
    extract=extract_long_signal_specs if side=="LONG" else extract_short_signal_specs
    candidate=extract("LTCUSDT",frame,timeframe="1h",tick_size=.1)
    assert len(candidate)==1,(side,family,candidate)
    entry=candidate[0]
    assert entry.signal_type==signal_type
    assert entry.role==SignalRole.ENTRY
    assert entry.side==("BUY" if side=="LONG" else "SELL")
    assert entry.trigger_price==(105.1 if side=="LONG" else 94.9)
    assert entry.protective_reference==(94.9 if side=="LONG" else 105.1)
    assert entry.source_candle_index==(1 if family=="WM3" else 2)


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_tc2_first_presenting_prefers_earlier_still_pending_signal_over_later_family(
    monkeypatch,side
):
    import williams_signals as signals
    price=105.0 if side=="LONG" else 95.0
    protective=95.0 if side=="LONG" else 105.0
    df=pd.DataFrame({
        "open":[100.0]*5,"high":[105.0]*5,"low":[95.0]*5,
        "close":[100.0]*5,"teeth_current":[100.0]*5,
        "open_time_ms":[(i+1)*3_600_000 for i in range(5)]
    })
    # WM2 appeared earlier at i=1 and is still waiting; WM1 at i=3.
    monkeypatch.setattr(signals,"_latest_reversal",lambda *_a,**_k:(3,price,protective))
    monkeypatch.setattr(signals,"_latest_super_ao",lambda *_a,**_k:(1,price,protective))
    monkeypatch.setattr(signals,"_latest_confirmed_fractal",lambda *_a,**_k:None)
    monkeypatch.setattr(signals,"_latest_three_price_bar_signal",lambda *_a,**_k:None)
    monkeypatch.setattr(signals,"_trigger_still_pending",lambda *_a,**_k:True)
    monkeypatch.setattr(signals,"_angulation",lambda *_a,**_k:(1.0,True))
    extract=extract_long_signal_specs if side=="LONG" else extract_short_signal_specs
    candidates=extract("BTCUSDT",df,timeframe="1h",tick_size=.1)
    assert [s.signal_type for s in candidates]==[SignalType.SUPER_AO,SignalType.REVERSAL]
    assert candidates[0].signal_bar_time_ms < candidates[1].signal_bar_time_ms
