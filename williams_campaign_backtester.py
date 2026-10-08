"""Event-driven H1->M15->M5 Williams campaign backtester; no fixed TP."""
from __future__ import annotations
from dataclasses import asdict,dataclass
import pandas as pd
from campaign_model import SignalType
from williams_intraday_core import WilliamsIntradayCore
from williams_intraday_spec import IntradayPolicy

@dataclass(frozen=True)
class SimFill:
 time:object;signal_type:str;price:float;quantity:float;step:int
@dataclass
class SimTrade:
 symbol:str;entry_time:object;entry_price:float;exit_time:object;exit_price:float;quantity:float;pnl_quote:float;reason:str;fills:list

class WilliamsCampaignBacktester:
 def __init__(self,*,starting_equity=500,fee_pct=.001,slippage_pct=.0005,risk_per_initial_entry=None,campaign_risk_pct=None,policy=None):
  self.policy=policy or IntradayPolicy.from_env();self.equity=float(starting_equity);self.fee_pct=float(fee_pct);self.slippage_pct=float(slippage_pct);self.initial_risk=risk_per_initial_entry or self.policy.risk.initial_risk_pct;self.campaign_risk=campaign_risk_pct or self.policy.risk.campaign_risk_pct;self.core=WilliamsIntradayCore(self.policy)
 @staticmethod
 def _ts(x):
  t=pd.Timestamp(x);return t.tz_localize("UTC") if t.tzinfo is None else t.tz_convert("UTC")
 @staticmethod
 def _bars_after(bars,start,end=None):
  idx=pd.to_datetime(bars.index,utc=True);m=idx>start
  if end is not None:m&=idx<=end
  return bars.loc[m]
 @staticmethod
 def _pending_hit(bar,trigger):
  o=float(bar["open"]);h=float(bar["high"])
  if o>=trigger:return "FILL",o
  if h>=trigger:return "FILL",trigger
  return None,0
 def run(self,symbol,h1,m15,m5=None,*,h4=None,d1=None,tick_size=.01):
  if h1 is None or len(h1)<80:return {"status":"INSUFFICIENT_DATA","equity":self.equity,"trades":[],"fills":[]}
  h1=h1.sort_index();path=(m5 if m5 is not None else m15).sort_index();trades=[];fills=[];position=None;pending=None
  for i in range(60,len(h1)):
   sl=h1.iloc[:i+1];t0=self._ts(sl.index[-1]);t1=self._ts(h1.index[i+1]) if i+1<len(h1) else None;session=self.policy.session.state(t0.to_pydatetime())
   if pending is not None and session!="ENTRY_WINDOW":pending=None
   d=self.core.evaluate(symbol,sl,h4=h4,d1=d1,tick_size=tick_size)
   if position is None and pending is None and session=="ENTRY_WINDOW":
    specs=list(d.signal_specs);chosen=next((x for x in specs if x.signal_type==SignalType.REVERSAL),None) or next((x for x in specs if x.signal_type==SignalType.SUPER_AO),None) or (next((x for x in specs if x.signal_type==SignalType.FRACTAL),None) if self.policy.wm3_first_allowed else None)
    if chosen is not None:pending=chosen
   for ts,bar in self._bars_after(path,t0,t1).iterrows():
    tt=self._ts(ts)
    if pending is not None and position is None:
     a,px=self._pending_hit(bar,pending.trigger_price)
     if a=="FILL":
      stop=pending.protective_reference;entry=px*(1+self.slippage_pct);qty=(self.equity*self.initial_risk)/max(entry-stop,1e-12)
      position={"entry_time":tt,"entry_price":entry,"stop":stop,"qty":qty,"fills":[asdict(SimFill(tt,pending.signal_type.value,px,qty,1))]};fills.append(position["fills"][0]);pending=None
     continue
    if position is not None and float(bar["low"])<=position["stop"]:
     px=position["stop"]*(1-self.slippage_pct);qty=position["qty"];pnl=(px-position["entry_price"])*qty-(position["entry_price"]*qty+px*qty)*self.fee_pct;self.equity+=pnl
     trades.append(asdict(SimTrade(symbol,position["entry_time"],position["entry_price"],tt,px,qty,pnl,"STRUCTURAL_STOP",position["fills"])));position=None
   if position is not None:
    close=float(sl["close"].iloc[-1]);recent=sl.tail(5);proposed=min(max(float(x) for x in recent["low"]),close-tick_size)
    if proposed>position["stop"]:position["stop"]=proposed
   if self.policy.session.requires_flat(t0.to_pydatetime()):
    if pending is not None:pending=None
    if position is not None:
     px=float(sl["close"].iloc[-1])*(1-self.slippage_pct);qty=position["qty"];pnl=(px-position["entry_price"])*qty-(position["entry_price"]*qty+px*qty)*self.fee_pct;self.equity+=pnl
     trades.append(asdict(SimTrade(symbol,position["entry_time"],position["entry_price"],t0,px,qty,pnl,"EOD_FLAT",position["fills"])));position=None
  return {"status":"OK","equity":self.equity,"trades":trades,"fills":fills,"open_position":position is not None}
