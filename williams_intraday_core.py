"""H1-only Williams strategy truth for intraday mode."""
from __future__ import annotations
from dataclasses import asdict,dataclass
from typing import Any
import pandas as pd
from campaign_model import SignalRole,SignalSpec,SignalType
from strategy import calculate_indicators,config_from_env
from williams_angulation import AngulationMeasurement,measure_side_angulation
from williams_fractal_engine import WilliamsFractalEngine
from williams_intraday_spec import IntradayPolicy

@dataclass(frozen=True)
class StrategyDecision:
 symbol:str;decision_time_ms:int;decision_tf:str;execution_tf:str;micro_tf:str;h4_context:str;d1_state:str;alligator_state:str
 wm1:bool;wm2:bool;wm3:bool;core_valid:bool;quality:str;first_signal_type:str;signal_specs:tuple[SignalSpec,...];angulation:dict;momentum_relation:str;reason:str;fields:dict[str,Any]
 def to_dict(self):
  d=asdict(self);d["signal_specs"]=[x.to_dict() for x in self.signal_specs];return d

class WilliamsIntradayCore:
 def __init__(self,policy=None):self.policy=policy or IntradayPolicy.from_env();self.fractals=WilliamsFractalEngine()
 @staticmethod
 def _time_ms(row):
  for k in ("open_time_ms","time_ms","timestamp","open_time"):
   v=row.get(k)
   if v is not None and not pd.isna(v):
    try:return int(v)
    except (TypeError,ValueError):pass
  try:return int(pd.Timestamp(row.name).timestamp()*1000)
  except Exception:return 0
 @staticmethod
 def _h4_context(frame):
  if frame is None or frame.empty:return "NEUTRAL"
  x=calculate_indicators(frame,config_from_env()).iloc[-1]
  return "SUPPORTIVE" if bool(x.get("bullish_alligator",False)) else "ADVERSE" if bool(x.get("bearish_alligator",False)) else "NEUTRAL"
 @staticmethod
 def _d1_state(frame):
  if frame is None or frame.empty:return "UNKNOWN"
  x=calculate_indicators(frame,config_from_env()).iloc[-1]
  return "BULLISH" if bool(x.get("bullish_alligator",False)) else "BEARISH" if bool(x.get("bearish_alligator",False)) else "NEUTRAL"
 def evaluate(self,symbol,h1,*,h4=None,d1=None,tick_size=0):
  if h1 is None or len(h1)<60:return self._empty(symbol,"INSUFFICIENT_H1_HISTORY")
  x=h1.copy()
  if "close_time" in x.columns:
   try:
    if pd.to_datetime(x["close_time"],utc=True).iloc[-1]>pd.Timestamp.now(tz="UTC"):x=x.iloc[:-1].copy()
   except Exception:pass
  if len(x)<60:return self._empty(symbol,"INSUFFICIENT_H1_HISTORY")
  if "volume" not in x.columns: x["volume"]=0.0
  ind=calculate_indicators(x,config_from_env());cur=ind.iloc[-1];prev=ind.iloc[-2]
  tms=self._time_ms(cur);h4c=self._h4_context(h4);d1s=self._d1_state(d1)
  vals=[float(cur.get(k,0) or 0) for k in ("jaw_shifted","teeth_shifted","lips_shifted")];mouth=[v for v in vals if v>0]
  close=float(cur.get("close",0) or 0);open_price=float(cur.get("open",close) or close);high=float(cur.get("high",0) or 0);low=float(cur.get("low",0) or 0)
  if len(mouth)==3:
   if close>max(mouth):ag="BULLISH"
   elif close<min(mouth):ag="BEARISH"
   elif max(mouth)-min(mouth)<=close*.001:ag="SLEEP"
   else:ag="AWAKENING"
  else:ag="UNKNOWN"
  prev_lows=[float(v) for v in ind["low"].iloc[-3:-1].tolist()]; lower=bool(prev_lows and low<min(prev_lows))
  rng=high-low;loc=(close-low)/rng if rng>0 else 0
  outside=bool(mouth) and low<min(mouth)
  bullish_bar=close>open_price
  reversal=lower and bullish_bar and loc>=.5 and outside
  ang=measure_side_angulation(ind,len(ind)-1,"LONG",window=5)
  ao=float(cur.get("ao",0) or 0);prev_ao=float(prev.get("ao",0) or 0);green=int(cur.get("ao_green_streak",0) or 0)
  ao_down=ao<=0 or ao<prev_ao
  wm1=bool(reversal and ang.valid and ao_down);wm2=green==3
  obs=self.fractals.detect(ind,side="LONG",teeth_series=ind.get("teeth_shifted"));teeth=float(cur.get("teeth_shifted",0) or 0)
  latest=self.fractals.latest_actionable(obs,len(ind)-1,teeth,"LONG");wm3=latest is not None
  tick=max(float(tick_size),1e-12);specs=[]
  if wm1:
   specs.append(SignalSpec.new(symbol=symbol,side="BUY",signal_type=SignalType.REVERSAL,role=SignalRole.ENTRY,timeframe="1h",signal_bar_time_ms=tms,trigger_price=high+tick,protective_reference=max(0,low-tick),invalidation_price=max(0,low-tick),teeth_at_detection=teeth,alligator_bullish=bool(cur.get("bullish_alligator",False)),alligator_awake=bool(cur.get("alligator_awake",False)),angulation_score=ang.angular_separation,htf_confirmed=h4c=="SUPPORTIVE",reason="WM1: lower low + bullish reversal + outside mouth + side-specific angulation + bearish/downward AO",source_candle_index=len(ind)-1,execution_timeframe="15m",detected_time_ms=tms))
  if wm2:
   specs.append(SignalSpec.new(symbol=symbol,side="BUY",signal_type=SignalType.SUPER_AO,role=SignalRole.ENTRY,timeframe="1h",signal_bar_time_ms=tms,trigger_price=high+tick,protective_reference=max(0,low-tick),invalidation_price=max(0,low-tick),teeth_at_detection=teeth,alligator_bullish=bool(cur.get("bullish_alligator",False)),alligator_awake=bool(cur.get("alligator_awake",False)),reason="WM2: third consecutive green H1 AO bar",source_candle_index=len(ind)-1,execution_timeframe="15m",detected_time_ms=tms))
  if wm3:
   center=ind.iloc[latest.center_index]
   specs.append(SignalSpec.new(symbol=symbol,side="BUY",signal_type=SignalType.FRACTAL,role=SignalRole.ENTRY,timeframe="1h",signal_bar_time_ms=self._time_ms(center),trigger_price=latest.level+tick,protective_reference=max(0,float(center["low"])-tick),invalidation_price=max(0,float(center["low"])-tick),teeth_at_detection=teeth,alligator_bullish=bool(cur.get("bullish_alligator",False)),alligator_awake=bool(cur.get("alligator_awake",False)),reason=f"WM3: {latest.formation} fractal; Teeth checked dynamically at trigger",source_candle_index=latest.center_index,execution_timeframe="15m",detected_time_ms=tms))
  priority={"REVERSAL":0,"SUPER_AO":1,"FRACTAL":2};specs.sort(key=lambda s:(s.signal_bar_time_ms,priority[s.signal_type.value]));usable=[s for s in specs if s.signal_type!=SignalType.FRACTAL or self.policy.wm3_first_allowed]
  first=usable[0].signal_type.value if usable else "";q=self._quality(usable,ang,ag,h4c)
  return StrategyDecision(symbol,tms,"1h","15m","5m",h4c,d1s,ag,wm1,wm2,wm3,bool(usable),q,first,tuple(usable),ang.to_dict(),"BEARISH_OR_FALLING_AO" if ao_down else "NOT_BEARISH","Williams Core valid" if usable else "No H1 Wise Man condition",{"lower_low":lower,"close_upper_half":loc>=.5,"bullish_reversal_bar":bullish_bar,"outside_mouth":outside,"ao":ao,"ao_previous":prev_ao,"ao_green_streak":green,"teeth_at_trigger":teeth,"fractal_count":len(obs)})
 @staticmethod
 def _quality(specs,ang,ag,h4c):
  if not specs:return "D"
  if any(s.signal_type==SignalType.REVERSAL for s in specs) and ang.valid and ag in {"BEARISH","AWAKENING"} and h4c!="ADVERSE":return "A"
  return "B" if len({s.signal_type.value for s in specs})>=2 else "C"
 @staticmethod
 def _empty(symbol,reason):
  return StrategyDecision(symbol,0,"1h","15m","5m","UNKNOWN","UNKNOWN","UNKNOWN",False,False,False,False,"D","",(),{}, "",reason,{})
