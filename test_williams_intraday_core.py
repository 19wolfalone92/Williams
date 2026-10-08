import pandas as pd
from williams_angulation import measure_side_angulation
from williams_fractal_engine import WilliamsFractalEngine
from williams_intraday_spec import IntradayPolicy,CORE_PROFILE,CONSERVATIVE_PROFILE

def _frame(rows):return pd.DataFrame(rows,columns=["open","high","low","close"])

def test_contract_and_risk_defaults():
 p=IntradayPolicy.from_env({})
 assert p.profile==CORE_PROFILE and p.timeframes.all==("1d","4h","1h","15m","5m") and p.risk.initial_risk_pct==.0025 and p.risk.campaign_risk_pct==.006 and p.risk.daily_loss_pct==.01 and p.risk.max_campaigns==1 and p.risk.max_full_stopouts==2 and not p.risk.fixed_take_profit

def test_conservative_policy():
 p=IntradayPolicy.from_env({"WILLIAMS_STRATEGY_PROFILE":CONSERVATIVE_PROFILE})
 assert not p.wm3_first_allowed and p.risk.initial_risk_pct==.002 and p.initial_risk_for(quality="A",h4_context="ADVERSE")==.001

def test_side_specific_angulation():
 frame=pd.DataFrame({"close":[95,94,93,92,90],"jaw_shifted":[100,100,100,100,100]})
 assert measure_side_angulation(frame,4,"LONG").valid is True
 assert measure_side_angulation(frame,4,"SHORT").valid is False

def test_fractal_extended_forms():
 f=WilliamsFractalEngine()
 n=_frame([(1,1,0,0.5),(1,2,0,1),(1,3,0,2),(1,4,0,3),(1,10,0,9),(1,4,0,3),(1,3,0,2),(1,2,0,1),(1,1,0,.5)])
 obs=f.detect(n,side="LONG")
 assert any(x.formation=="NINE_EXTENDED" for x in obs)
 s=_frame([(1,1,0,.5),(1,2,0,1),(1,5,0,4),(1,5,0,4),(1,2,0,1),(1,1,0,.5)])
 assert any(x.formation=="SIX_SHARED" for x in f.detect(s,side="LONG"))

def test_overlapping_fractals_preserved():
 f=WilliamsFractalEngine()
 d=_frame([(1,1,0,.5),(1,2,0,1),(1,10,0,9),(1,2,0,1),(1,1,0,.5),(1,2,0,1),(1,9,0,8),(1,2,0,1),(1,1,0,.5)])
 o=f.detect(d,side="LONG"); assert len(o)>=2 and any(x.overlapping for x in o)


def test_structural_stop_uses_lowest_recent_low():
    from campaign_model import structural_stop_for_long, SignalType
    stop, source = structural_stop_for_long(
        signal_type=SignalType.FRACTAL,
        signal_bar_low=99,
        recent_lows=[98, 97, 96, 95, 94],
        teeth=0,
        buffer=0.1,
    )
    assert source == "FRACTAL_SIGNAL_BAR"
    assert stop == 98.9


def test_final_timeframe_contract():
    p = IntradayPolicy.from_env({})
    assert p.timeframes.all == ("1d", "4h", "1h", "15m", "5m")


def test_wm1_does_not_require_green_candle_body(monkeypatch):
    base = _frame([(100,101,99,100)] * 59 + [
        (100,103,95,99), (98,100,94,96), (97,99,93,95),
        (96,98,92,94), (95,97,91,93), (92,95,88,94)
    ])
    ind = base.copy()
    ind["jaw_shifted"] = 100.0
    ind["teeth_shifted"] = 100.0
    ind["lips_shifted"] = 100.0
    ind["ao"] = [-5.0] * 64 + [-6.0]
    ind["ao_green_streak"] = [0] * 65
    ind["ao_red_streak"] = [0] * 64 + [1]
    ind["bullish_alligator"] = False
    ind["bearish_alligator"] = True
    ind["alligator_awake"] = True
    monkeypatch.setattr("williams_intraday_core.calculate_indicators", lambda *_a, **_k: ind)
    from williams_intraday_core import WilliamsIntradayCore
    decision = WilliamsIntradayCore(IntradayPolicy.from_env({})).evaluate("BTCUSDT", base, tick_size=0.1)
    assert decision.fields["close_upper_half"] is True
