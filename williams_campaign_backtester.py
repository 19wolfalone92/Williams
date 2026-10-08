"""Event-driven backtester for the canonical Williams intraday campaign.

Signal truth is formed on closed M15 candles after H4/H1 context is known.
M5 is only an execution microscope: it refines trigger/stop ordering inside
the M15 bar and never creates an independent signal.
"""
from __future__ import annotations
from dataclasses import asdict, dataclass
import pandas as pd

from campaign_model import SignalType
from williams_intraday_core import WilliamsIntradayCore
from williams_intraday_spec import IntradayPolicy

@dataclass(frozen=True)
class SimFill:
    time: object
    signal_type: str
    price: float
    quantity: float
    step: int

@dataclass
class SimTrade:
    symbol: str
    entry_time: object
    entry_price: float
    exit_time: object
    exit_price: float
    quantity: float
    pnl_quote: float
    reason: str
    fills: list

class WilliamsCampaignBacktester:
    def __init__(self, *, starting_equity=500, fee_pct=.001, slippage_pct=.0005,
                 risk_per_initial_entry=None, campaign_risk_pct=None, policy=None):
        self.policy=policy or IntradayPolicy.from_env()
        self.equity=float(starting_equity)
        self.fee_pct=float(fee_pct)
        self.slippage_pct=float(slippage_pct)
        self.initial_risk=risk_per_initial_entry if risk_per_initial_entry is not None else self.policy.risk.initial_risk_pct
        self.campaign_risk=campaign_risk_pct if campaign_risk_pct is not None else self.policy.risk.campaign_risk_pct
        self.core=WilliamsIntradayCore(self.policy)

    @staticmethod
    def _ts(value):
        t=pd.Timestamp(value)
        return t.tz_localize("UTC") if t.tzinfo is None else t.tz_convert("UTC")

    @classmethod
    def _context_slice(cls, frame, at, duration):
        if frame is None or len(frame)==0:return None
        out=frame.copy().sort_index()
        idx=pd.to_datetime(out.index,utc=True)
        at=cls._ts(at)
        eligible=idx+duration<=at
        out=out.loc[eligible]
        return out if len(out) else None

    @classmethod
    def _micro_slice(cls,m5,start,end):
        if m5 is None or len(m5)==0:return None
        idx=pd.to_datetime(m5.index,utc=True)
        start=cls._ts(start);end=cls._ts(end)
        out=m5.loc[(idx>=start)&(idx<end)]
        return out if len(out) else None

    @classmethod
    def _resolve_buy_trigger(cls,m15_bar,trigger,m5=None):
        start=cls._ts(m15_bar.name);end=start+pd.Timedelta(minutes=15)
        if float(m15_bar["open"])>=trigger:return start,float(m15_bar["open"])
        if float(m15_bar["high"])<trigger:return None,0.0
        micro=cls._micro_slice(m5,start,end)
        if micro is None:return start,trigger
        for ts,bar in micro.iterrows():
            if float(bar["open"])>=trigger:return cls._ts(ts),float(bar["open"])
            if float(bar["high"])>=trigger:return cls._ts(ts),trigger
        return None,0.0

    @classmethod
    def _resolve_stop(cls,m15_bar,stop,m5=None,after=None):
        start=cls._ts(m15_bar.name);end=start+pd.Timedelta(minutes=15)
        micro=cls._micro_slice(m5,start,end)
        if micro is None:
            if after is not None and cls._ts(after)>=start:return None,0.0
            return (start,stop) if float(m15_bar["low"])<=stop else (None,0.0)
        for ts,bar in micro.iterrows():
            ts=cls._ts(ts)
            if after is not None and ts<=cls._ts(after):continue
            if float(bar["low"])<=stop:return ts,stop
        return None,0.0

    @staticmethod
    def _weighted_add_risk_pct(equity,used_risk,campaign_cap,tranche_index):
        if equity<=0:return 0.0
        weights=(1,5,4,3,2)
        idx=min(max(int(tranche_index),0),4)
        remaining=max(0.0,equity*campaign_cap-used_risk)
        weighted=equity*campaign_cap*(weights[idx]/sum(weights))
        return max(0.0,min(remaining,weighted))/equity

    @staticmethod
    def _pick_initial(specs):
        if not specs:return None
        priority={SignalType.REVERSAL:0,SignalType.SUPER_AO:1,SignalType.FRACTAL:2}
        return min(specs,key=lambda s:(int(s.signal_bar_time_ms),priority.get(s.signal_type,99),int(s.created_at_ms)))

    def run(self,symbol,h1,m15,m5=None,*,h4=None,d1=None,tick_size=.01):
        if m15 is None or len(m15)<80:
            return {"status":"INSUFFICIENT_DATA","equity":self.equity,"trades":[],"fills":[]}
        m15=m15.sort_index()
        h1=h1.sort_index() if h1 is not None else None
        h4=h4.sort_index() if h4 is not None else None
        d1=d1.sort_index() if d1 is not None else None
        m5=m5.sort_index() if m5 is not None else None

        position=None;pending=None;fills=[];trades=[]
        last_signal_time=0;current_day=None;daily_start_equity=self.equity;daily_stopouts=0

        for i in range(79,len(m15)):
            bar=m15.iloc[i]; now=self._ts(bar.name)
            session=self.policy.session.state(now.to_pydatetime())
            day=now.date()
            if current_day!=day:
                current_day=day;daily_start_equity=self.equity;daily_stopouts=0
            day_loss=self.equity/max(daily_start_equity,1e-12)-1.0
            day_blocked=day_loss<=-self.policy.risk.daily_loss_pct or daily_stopouts>=self.policy.risk.max_full_stopouts

            if pending is not None:
                spec=pending["spec"]
                trigger_time,raw=self._resolve_buy_trigger(bar,float(spec.trigger_price),m5)
                if trigger_time is not None:
                    fill_price=raw*(1.0+self.slippage_pct)
                    stop=float(position["stop"]) if position is not None else float(spec.protective_reference)
                    if stop>0 and fill_price>stop:
                        if position is None:
                            risk_quote=self.equity*self.initial_risk
                            qty=risk_quote/max(fill_price-stop,1e-12)
                            step=1
                            sig=spec.signal_type.value
                            position={"entry_time":trigger_time,"entry_price":fill_price,"stop":stop,"qty":qty,"tranche_index":1,"fills":[],"last_signal_time":int(spec.signal_bar_time_ms)}
                        else:
                            used=max(0.0,(position["entry_price"]-position["stop"])*position["qty"])
                            risk_pct=self._weighted_add_risk_pct(self.equity,used,self.campaign_risk,position["tranche_index"])
                            risk_quote=self.equity*risk_pct
                            qty=risk_quote/max(fill_price-stop,1e-12)
                            old_qty=position["qty"]
                            position["entry_price"]=(old_qty*position["entry_price"]+qty*fill_price)/max(old_qty+qty,1e-12)
                            position["qty"]+=qty
                            step=position["tranche_index"]+1
                            position["tranche_index"]=min(5,step)
                            position["last_signal_time"]=int(spec.signal_bar_time_ms)
                            sig=spec.signal_type.value
                        fill=asdict(SimFill(trigger_time,sig,raw,qty,step))
                        position["fills"].append(fill);fills.append(fill)
                        last_signal_time=int(spec.signal_bar_time_ms);pending=None
                        stop_time,stop_px=self._resolve_stop(bar,position["stop"],m5,after=trigger_time)
                        if stop_time is not None:
                            exit_price=stop_px*(1.0-self.slippage_pct);qty=float(position["qty"])
                            pnl=(exit_price-position["entry_price"])*qty-(position["entry_price"]*qty+exit_price*qty)*self.fee_pct
                            self.equity+=pnl;daily_stopouts+=1
                            trades.append(asdict(SimTrade(symbol,position["entry_time"],position["entry_price"],stop_time,exit_price,qty,pnl,"STRUCTURAL_STOP",list(position["fills"]))))
                            position=None;pending=None

            if position is not None:
                ind=self.core._closed(m15.iloc[:i+1])
                if ind is not None and len(ind)>=5:
                    zone=str(ind.iloc[-1].get("zone_color","GRAY") or "GRAY")
                    zstreak=int(ind.iloc[-1].get("zone_streak",0) or 0)
                    if zone=="GREEN" and zstreak>=5:position["zone_trail_armed"]=True
                    lows=[float(x) for x in m15.iloc[max(0,i-4):i+1]["low"].tolist()]
                    proposed=min(lows)-tick_size if lows else position["stop"]
                    if position.get("zone_trail_armed",False):
                        proposed=max(proposed,float(bar["low"])-tick_size)
                    position["stop"]=max(position["stop"],proposed)

            decision=self.core.evaluate(
                symbol,m15.iloc[:i+1],
                h1=self._context_slice(h1,now,pd.Timedelta(hours=1)),
                h4=self._context_slice(h4,now,pd.Timedelta(hours=4)),
                d1=self._context_slice(d1,now,pd.Timedelta(days=1)),
                tick_size=tick_size,
            )

            if position is None and pending is None and session=="ENTRY_WINDOW" and not day_blocked:
                chosen=self._pick_initial(decision.signal_specs)
                if chosen is not None:pending={"spec":chosen}
            elif position is not None and pending is None:
                later=[s for s in decision.signal_specs if s.signal_type in {SignalType.SUPER_AO,SignalType.FRACTAL} and int(s.signal_bar_time_ms)>last_signal_time]
                if later:pending={"spec":min(later,key=lambda s:(int(s.signal_bar_time_ms),int(s.created_at_ms)))}

            if self.policy.session.requires_flat(now.to_pydatetime()):
                pending=None
                if position is not None:
                    exit_price=float(bar["close"])*(1.0-self.slippage_pct);qty=float(position["qty"])
                    pnl=(exit_price-position["entry_price"])*qty-(position["entry_price"]*qty+exit_price*qty)*self.fee_pct
                    self.equity+=pnl
                    trades.append(asdict(SimTrade(symbol,position["entry_time"],position["entry_price"],now,exit_price,qty,pnl,"EOD_FLAT",list(position["fills"]))))
                    position=None

        return {"status":"OK","equity":self.equity,"trades":trades,"fills":fills,"open_position":position is not None,
                "risk_stopped":daily_stopouts>=self.policy.risk.max_full_stopouts}
