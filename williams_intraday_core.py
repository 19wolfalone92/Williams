"""Canonical Williams multi-timeframe trading core.

Architecture:
1D macro observation -> 4H permission -> 1H context -> 15m Williams decision
-> 5m execution.

The books define the Williams logic; the timeframe chain, risk limits, session
rules and exchange safety are engineering overlays for the bot.
"""
from __future__ import annotations
from dataclasses import asdict, dataclass
from typing import Any
import pandas as pd

from campaign_model import SignalRole, SignalSpec, SignalType
from strategy import calculate_indicators, config_from_env
from williams_angulation import measure_side_angulation
from williams_fractal_engine import WilliamsFractalEngine
from williams_intraday_spec import IntradayPolicy

_PRIORITY={"REVERSAL":0,"SUPER_AO":1,"FRACTAL":2}

@dataclass(frozen=True)
class StrategyDecision:
    symbol:str
    decision_time_ms:int
    decision_tf:str
    execution_tf:str
    macro_tf:str
    permission_tf:str
    context_tf:str
    h4_context:str
    d1_state:str
    context_state:str
    wm1:bool
    wm2:bool
    wm3:bool
    core_valid:bool
    quality:str
    first_signal_type:str
    signal_specs:tuple[SignalSpec,...]
    angulation:dict
    momentum_relation:str
    reason:str
    fields:dict[str,Any]
    def to_dict(self):
        d=asdict(self)
        d["signal_specs"]=[x.to_dict() for x in self.signal_specs]
        return d

class WilliamsIntradayCore:
    def __init__(self,policy=None):
        self.policy=policy or IntradayPolicy.from_env()
        self.fractals=WilliamsFractalEngine()

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
    def _closed(frame):
        if frame is None or len(frame)==0:return None
        x=frame.copy()
        if "close_time" in x.columns:
            try:
                ct=pd.to_datetime(x["close_time"],utc=True)
                if ct.iloc[-1]>pd.Timestamp.now(tz="UTC"):x=x.iloc[:-1].copy()
            except Exception:pass
        return x

    @staticmethod
    def _state(frame):
        if frame is None or len(frame)<40:return "UNKNOWN"
        cfg=config_from_env()
        r=calculate_indicators(frame,cfg).iloc[-1]
        vals=[float(r.get(k,0) or 0) for k in ("jaw_shifted","teeth_shifted","lips_shifted")]
        vals=[v for v in vals if v>0]
        close=float(r.get("close",0) or 0)
        if len(vals)!=3 or close<=0:return "UNKNOWN"
        if bool(r.get("bullish_alligator",False)):return "BULLISH"
        if bool(r.get("bearish_alligator",False)):return "BEARISH"
        if max(vals)-min(vals)<=close*float(cfg.get("min_alligator_spread_pct",.001)):return "SLEEP"
        return "AWAKENING"

    @classmethod
    def _h4_permission(cls,frame):
        state=cls._state(frame)
        if state=="BULLISH":return "SUPPORTIVE"
        if state=="BEARISH":return "ADVERSE"
        return "NEUTRAL"

    @classmethod
    def _d1_state(cls,frame):
        return cls._state(frame)

    @staticmethod
    def _ao_event(ind,side,max_age=32):
        col="ao_green" if side=="LONG" else "ao_red"
        streak="ao_green_streak" if side=="LONG" else "ao_red_streak"
        if col not in ind.columns or streak not in ind.columns:return None
        start=max(0,len(ind)-max(3,int(max_age)))
        for i in range(len(ind)-1,start-1,-1):
            if not bool(ind.iloc[i].get(col,False)):continue
            before=int(ind.iloc[i-1].get(streak,0) or 0) if i>0 else 0
            if before==2:return i
        return None

    def _latest_wm3(self,ind,side,tick):
        if len(ind)<5:return None
        teeth_now=float(ind.iloc[-1].get("teeth_shifted",0) or 0)
        obs=self.fractals.detect(ind,side=side,teeth_series=ind.get("teeth_shifted"))
        active=sorted(
            (z for z in obs if z.confirmation_index<len(ind)),
            key=lambda z:(z.confirmation_index,z.center_index),
            reverse=True,
        )
        # Newer fractal supersedes an older same-direction pending fractal.
        # Never fall back to a superseded fractal after the newest one has
        # already crossed or failed its trigger-time Teeth condition.
        if not active:return None
        o=active[0]
        trigger=float(o.level)+(tick if side=="LONG" else -tick)
        teeth_ok=trigger>teeth_now if side=="LONG" else trigger<teeth_now
        price_ok=trigger>float(ind.iloc[-1]["close"]) if side=="LONG" else trigger<float(ind.iloc[-1]["close"])
        return o if teeth_ok and price_ok else None

    @staticmethod
    def _mouth_metrics(row,side):
        mouth=[float(row.get(k,0) or 0) for k in ("jaw_shifted","teeth_shifted","lips_shifted")]
        if len([v for v in mouth if v>0])!=3:return False,0.0,0.0
        lo_m,hi_m=min(mouth),max(mouth)
        close=float(row.get("close",0) or 0);low=float(row.get("low",0) or 0);high=float(row.get("high",0) or 0)
        if side=="LONG":
            edge=lo_m-low; close_dist=lo_m-close
        else:
            edge=high-hi_m; close_dist=close-hi_m
        denom=max(abs(close),1e-12)
        return edge>0,edge/denom*100.0,close_dist/denom*100.0

    def evaluate(self,symbol,m15,*,h1=None,h4=None,d1=None,tick_size=0.0):
        policy=self.policy
        m15=self._closed(m15);h1=self._closed(h1);h4=self._closed(h4);d1=self._closed(d1)
        if m15 is None or len(m15)<60:return self._empty(symbol,"INSUFFICIENT_15M_HISTORY")
        ind=calculate_indicators(m15,config_from_env())
        cur=ind.iloc[-1];prev=ind.iloc[-2]
        close=float(cur.get("close",0) or 0);high=float(cur.get("high",0) or 0);low=float(cur.get("low",0) or 0)
        if min(close,high,low)<=0:return self._empty(symbol,"INVALID_PRICE")
        tms=self._time_ms(cur);h4c=self._h4_permission(h4);d1s=self._d1_state(d1);context=self._state(h1)

        # WM1: lower/higher extreme + half-bar close + outside mouth + book geometry.
        rng=high-low;loc=(close-low)/rng if rng>0 else 0.0
        prev_lows=[float(v) for v in ind["low"].iloc[-3:-1].tolist()]
        prev_highs=[float(v) for v in ind["high"].iloc[-3:-1].tolist()]
        lower_low=bool(prev_lows) and low<min(prev_lows)
        higher_high=bool(prev_highs) and high>max(prev_highs)
        outside_long,dist_long,close_dist_long=self._mouth_metrics(cur,"LONG")
        outside_short,dist_short,close_dist_short=self._mouth_metrics(cur,"SHORT")
        long_ang=measure_side_angulation(ind,len(ind)-1,"LONG",window=5)
        short_ang=measure_side_angulation(ind,len(ind)-1,"SHORT",window=5)
        ao=float(cur.get("ao",0) or 0);prev_ao=float(prev.get("ao",0) or 0)
        ao_long_red=ao<prev_ao;ao_short_green=ao>prev_ao
        wm1_long=lower_low and loc>=.50 and outside_long and close_dist_long>0 and long_ang.valid and ao_long_red

        tick=max(float(tick_size),1e-12);specs=[]
        if wm1_long:
            stop=max(0.0,low-tick)
            specs.append(SignalSpec.new(
                symbol=symbol,side="BUY",signal_type=SignalType.REVERSAL,role=SignalRole.ENTRY,
                timeframe=policy.timeframes.decision_tf,signal_bar_time_ms=tms,trigger_price=high+tick,
                protective_reference=stop,invalidation_price=stop,teeth_at_detection=float(cur.get("teeth_shifted",0) or 0),
                alligator_bullish=bool(cur.get("bullish_alligator",False)),alligator_awake=bool(cur.get("alligator_awake",False)),
                angulation_score=long_ang.angular_separation,htf_confirmed=h4c=="SUPPORTIVE",
                reason="WM1: lower low + close upper half + outside Alligator + LONG angulation + red AO",
                source_candle_index=len(ind)-1,execution_timeframe=policy.timeframes.execution_tf,detected_time_ms=tms))

        # WM2: third same-colour AO bar, represented by 2 -> 3 transition; no fractal prerequisite.
        ao_idx=self._ao_event(ind,"LONG")
        if ao_idx is not None:
            r=ind.iloc[ao_idx];trigger=float(r["high"])+tick
            if trigger>close:
                stop=max(0.0,float(r["low"])-tick)
                specs.append(SignalSpec.new(
                    symbol=symbol,side="BUY",signal_type=SignalType.SUPER_AO,role=SignalRole.ENTRY,
                    timeframe=policy.timeframes.decision_tf,signal_bar_time_ms=self._time_ms(r),
                    trigger_price=trigger,protective_reference=stop,invalidation_price=stop,
                    teeth_at_detection=float(r.get("teeth_shifted",0) or 0),alligator_bullish=bool(cur.get("bullish_alligator",False)),
                    alligator_awake=bool(cur.get("alligator_awake",False)),htf_confirmed=h4c=="SUPPORTIVE",
                    reason="WM2: third consecutive rising/green AO bar; trigger above corresponding price bar",
                    source_candle_index=ao_idx,execution_timeframe=policy.timeframes.execution_tf,detected_time_ms=self._time_ms(r)))

        # WM3: activation remains valid only while the trigger is beyond Teeth.
        latest=self._latest_wm3(ind,"LONG",tick)
        if latest is not None and policy.wm3_first_allowed:
            center=ind.iloc[latest.center_index];trigger=float(latest.level)+tick;stop=max(0.0,float(center["low"])-tick)
            specs.append(SignalSpec.new(
                symbol=symbol,side="BUY",signal_type=SignalType.FRACTAL,role=SignalRole.ENTRY,
                timeframe=policy.timeframes.decision_tf,signal_bar_time_ms=self._time_ms(center),
                trigger_price=trigger,protective_reference=stop,invalidation_price=stop,
                teeth_at_detection=float(cur.get("teeth_shifted",0) or 0),alligator_bullish=bool(cur.get("bullish_alligator",False)),
                alligator_awake=bool(cur.get("alligator_awake",False)),htf_confirmed=h4c=="SUPPORTIVE",
                reason=f"WM3: {latest.formation} fractal; activation must remain above Teeth",
                source_candle_index=latest.center_index,execution_timeframe=policy.timeframes.execution_tf,detected_time_ms=tms))

        specs.sort(key=lambda s:(int(s.signal_bar_time_ms),_PRIORITY.get(s.signal_type.value,99),int(s.created_at_ms)))
        first=specs[0] if specs else None
        if first is None:quality="D"
        elif first.signal_type==SignalType.REVERSAL and h4c!="ADVERSE":quality="A"
        elif first.signal_type in {SignalType.SUPER_AO,SignalType.FRACTAL} and h4c!="ADVERSE":quality="B"
        else:quality="C"

        fields={
            "lower_low":lower_low,"higher_high":higher_high,"close_upper_half":loc>=.50,"close_lower_half":loc<=.50,
            "outside_mouth_long":outside_long,"outside_mouth_short":outside_short,
            "mouth_distance_long_pct":dist_long,"mouth_distance_short_pct":dist_short,
            "close_distance_long_pct":close_dist_long,"close_distance_short_pct":close_dist_short,
            "ao":ao,"ao_previous":prev_ao,
            "ao_green_streak":int(cur.get("ao_green_streak",0) or 0),"ao_red_streak":int(cur.get("ao_red_streak",0) or 0),
            "zone_color":str(cur.get("zone_color","UNKNOWN") or "UNKNOWN"),"zone_streak":int(cur.get("zone_streak",0) or 0),
            "h4_context":h4c,"h1_context":context,"d1_state":d1s,"teeth_at_trigger":float(cur.get("teeth_shifted",0) or 0),
        }
        return StrategyDecision(
            symbol=symbol,decision_time_ms=tms,decision_tf=policy.timeframes.decision_tf,execution_tf=policy.timeframes.execution_tf,
            macro_tf=policy.timeframes.macro_tf,permission_tf=policy.timeframes.permission_tf,context_tf=policy.timeframes.context_tf,
            h4_context=h4c,d1_state=d1s,context_state=context,wm1=wm1_long,wm2=ao_idx is not None,wm3=latest is not None,
            core_valid=bool(specs),quality=quality,first_signal_type=first.signal_type.value if first else "",
            signal_specs=tuple(specs),
            angulation=(long_ang.to_dict() if wm1_long else short_ang.to_dict() if short_ang.valid else long_ang.to_dict()),
            momentum_relation="RED_AO_EXPECTED_FOR_LONG_REVERSAL" if ao_long_red else "NOT_RED_AO",
            reason="Williams Core signal ready" if specs else "No valid Williams Wise-Man signal",
            fields=fields)

    @staticmethod
    def _empty(symbol,reason):
        return StrategyDecision(symbol,0,"15m","5m","1d","4h","1h","UNKNOWN","UNKNOWN","UNKNOWN",
            False,False,False,False,"D","",tuple(),{},"",reason,{})
