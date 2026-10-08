"""Deterministic H1 -> M15 -> M5 event-path backtester skeleton."""
from __future__ import annotations
from dataclasses import dataclass,field
import pandas as pd
@dataclass(frozen=True)
class BacktestFill:
    timestamp_ms:int; price:float; quantity:float; reason:str
@dataclass
class BacktestResult:
    fills:list[BacktestFill]=field(default_factory=list); blocked:list[dict]=field(default_factory=list); exits:list[dict]=field(default_factory=list); decisions:list[dict]=field(default_factory=list)
class WilliamsCampaignBacktester:
    """Never fills from candle high/low alone when ordered intrabar data exists."""
    def __init__(self,core,policy=None): self.core=core; self.policy=policy
    @staticmethod
    def _bars_between(frame,start_ms,end_ms):
        if frame is None:return []
        key=next((k for k in ("open_time_ms","time_ms","timestamp") if k in frame.columns),None)
        if not key:return []
        return [r for _,r in frame.iterrows() if int(r[key])>=start_ms and int(r[key])<end_ms]
    @staticmethod
    def _trigger_path(rows,trigger,side="LONG"):
        for r in rows:
            hi=float(r["high"]); lo=float(r["low"])
            if side=="LONG" and hi>=trigger:return float(max(trigger,float(r["open"])))
            if side=="SHORT" and lo<=trigger:return float(min(trigger,float(r["open"])))
        return None
    def run(self,h1:pd.DataFrame,m15:pd.DataFrame|None=None,m5:pd.DataFrame|None=None,symbol="BTCUSDT",tick_size=0.0)->BacktestResult:
        out=BacktestResult()
        if h1 is None:return out
        for i in range(49,len(h1)):
            closed=h1.iloc[:i+1]
            ev=self.core.evaluate(closed,symbol=symbol,tick_size=tick_size)
            out.decisions.append(ev.decision.to_dict())
            if not ev.signals:continue
            s=ev.signals[0]; signal_t=s.signal_bar_time_ms; next_h1_t=int(h1.iloc[i+1].get("open_time_ms",0)) if i+1<len(h1) else signal_t+3600000
            rows=self._bars_between(m5 if m5 is not None else m15,signal_t+1,next_h1_t)
            fill=self._trigger_path(rows,s.trigger_price,"LONG")
            if fill is None:continue
            out.fills.append(BacktestFill(next_h1_t,fill,0.0,s.signal_type.value))
        return out
