"""Single source of truth for H1 Williams strategy decisions."""
from __future__ import annotations
from dataclasses import dataclass
from typing import Any
import pandas as pd
from campaign_model import SignalSpec,SignalType,SignalRole
from .alligator import calculate_alligator
from .ao import calculate_ao
from .fractals import FractalEngine
from .wm1 import latest_wm1
from .wm2 import latest_wm2
from .wm3 import evaluate_wm3
from .decision import StrategyDecision

@dataclass(frozen=True)
class CoreEvaluation:
    decision:StrategyDecision; signals:tuple[SignalSpec,...]; indicators:pd.DataFrame

class WilliamsCore:
    def __init__(self,*,outside_atr_mult:float=0.10,angulation_window:int=5,fractal_engine:FractalEngine|None=None):
        self.outside_atr_mult=float(outside_atr_mult); self.angulation_window=int(angulation_window); self.fractal_engine=fractal_engine or FractalEngine()
    def indicators(self,h1:pd.DataFrame)->pd.DataFrame:
        x=calculate_alligator(h1); x=calculate_ao(x); return x
    @staticmethod
    def _signal_id(symbol,typ,time_ms): return f"{str(symbol).upper()}:1h:{typ}:{int(time_ms)}"
    @staticmethod
    def _time(row,index):
        for k in ("open_time_ms","time_ms","timestamp","time","open_time"):
            if k in row and pd.notna(row[k]):
                try:return int(row[k])
                except: pass
        return int(index)
    def evaluate(self,h1:pd.DataFrame,*,symbol:str,tick_size:float=0.0,h4_context:str="NEUTRAL",wave_context:dict|None=None,campaign_id:str="",campaign_step:int=0)->CoreEvaluation:
        if h1 is None or len(h1)<50:
            d=StrategyDecision(str(symbol).upper(),"1h",context_state=str(h4_context),block_reason="INSUFFICIENT_H1_HISTORY")
            return CoreEvaluation(d,tuple(),h1 if h1 is not None else pd.DataFrame())
        x=self.indicators(h1.copy()); row=x.iloc[-1]; tnow=self._time(row,len(x)-1)
        candidates=[]
        wm1=latest_wm1(x,"LONG",max_age_bars=20)
        if wm1.valid and wm1.index==len(x)-1:
            candidates.append((wm1.index,0,"REVERSAL",wm1.trigger_price+max(0,float(tick_size)),wm1.initial_stop,wm1))
        wm2=latest_wm2(x,"LONG")
        if wm2.valid:
            candidates.append((wm2.index,1,"SUPER_AO",wm2.trigger_price+max(0,float(tick_size)),wm2.protective_reference,wm2))
        teeth=float(row.get("teeth_shifted",0.0) or 0.0); wm3=evaluate_wm3(x,"LONG",teeth_at_trigger=teeth,tick_size=tick_size,engine=self.fractal_engine)
        if wm3.valid and wm3.fractal is not None and wm3.fractal.confirmation_index<=len(x)-1:
            candidates.append((wm3.fractal.confirmation_index,2,"FRACTAL",wm3.trigger_price,wm3.protective_reference,wm3))
        candidates.sort(key=lambda z:(z[0],z[1]))
        core_valid=bool(candidates); chosen=candidates[0] if candidates else None
        signal_type="NONE"; trigger=0.0; stop=0.0; signal_time=tnow; quality="C"
        ang={}; wm1d={"valid":wm1.valid,"index":wm1.index,"reason":wm1.reason}; wm2d={"valid":wm2.valid,"index":wm2.index,"streak":wm2.streak,"reason":wm2.reason}; wm3d={"valid":wm3.valid,"reason":wm3.reason}
        if wm1.angulation: ang=wm1.angulation.to_dict()
        if chosen:
            signal_type=chosen[2]; trigger=float(chosen[3]); stop=float(chosen[4]); signal_time=self._time(x.iloc[chosen[0]],chosen[0]); quality="A" if signal_type=="REVERSAL" and wm1.valid and wm1.angulation and wm1.angulation.valid and str(h4_context).upper()=="SUPPORTIVE" else ("B" if signal_type=="REVERSAL" else "C")
        step=int(campaign_step or 0) if campaign_id else 0
        if core_valid and not campaign_id: step=1
        dec=StrategyDecision(str(symbol).upper(),"1h",signal_type,"LONG",signal_time,row.to_dict(),str(row.get("alligator_state","NEUTRAL")),wm1d,wm2d,wm3d,{"status":wm3.status,"level":getattr(wm3.fractal,"level",0.0) if wm3.fractal else 0.0},ang,{"ao":float(row.get("ao",0.0) or 0.0),"ao_green":bool(row.get("ao_green",False)),"ao_red":bool(row.get("ao_red",False))},trigger,stop,campaign_id,step,str(h4_context).upper(),True,True,core_valid,core_valid,"",quality)
        specs=[]
        for idx,_,typ,trig,prot,obj in candidates:
            role=SignalRole.ENTRY if not campaign_id else SignalRole.ADD_ON
            st=SignalType.REVERSAL if typ=="REVERSAL" else SignalType.SUPER_AO if typ=="SUPER_AO" else SignalType.FRACTAL
            ms=self._time(x.iloc[idx],idx)
            specs.append(SignalSpec.new(symbol=symbol,side="BUY",signal_type=st,role=role,timeframe="1h",signal_bar_time_ms=ms,trigger_price=trig,protective_reference=prot,teeth_at_detection=float(x.iloc[idx].get("teeth_shifted",0.0) or 0.0),alligator_bullish=bool(x.iloc[idx].get("bullish_alligator",False)),alligator_awake=str(x.iloc[idx].get("alligator_state",""))!="SLEEPING",angulation_score=float(getattr(obj.angulation,"angular_separation",0.0) if hasattr(obj,"angulation") and obj.angulation else 0.0),context_versions={"1h":len(x)},reason=f"{typ} H1 Core signal",source_candle_index=idx))
        return CoreEvaluation(dec,tuple(specs),x)
