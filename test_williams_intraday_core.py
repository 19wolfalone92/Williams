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
