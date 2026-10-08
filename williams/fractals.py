"""Stateful Williams fractal engine with tie/extension metadata."""
from __future__ import annotations
from dataclasses import dataclass
import math
import pandas as pd

@dataclass(frozen=True)
class WilliamsFractal:
    side:str
    center_index:int
    confirmation_index:int
    level:float
    protective_extreme:float
    span:int=5
    equal_extreme:bool=False
    shared_bars:bool=True
    status:str="ACTIVE"

class FractalEngine:
    def __init__(self,left:int=2,right:int=2,max_extension:int=9):
        self.left=int(left); self.right=int(right); self.max_extension=int(max_extension)
        if self.left<2 or self.right<2: raise ValueError("Williams fractal requires at least 5 bars")
    @staticmethod
    def _time_index(df, i):
        return i
    def detect(self,df:pd.DataFrame)->list[WilliamsFractal]:
        h=pd.to_numeric(df["high"],errors="coerce").tolist(); l=pd.to_numeric(df["low"],errors="coerce").tolist(); n=len(df)
        out=[]
        for i in range(self.left,n-self.right):
            wl=h[i-self.left:i]; wr=h[i+1:i+self.right+1]; v=h[i]
            if math.isfinite(v) and v>=max(wl) and v>=max(wr) and (v>min(wl+[v]+wr)):
                eq=(v in wl) or (v in wr)
                span=self._extension_span(h,i,v)
                out.append(WilliamsFractal("UP",i,i+self.right,v,float(l[i]),span,eq,True))
            v=l[i]; wl=l[i-self.left:i]; wr=l[i+1:i+self.right+1]
            if math.isfinite(v) and v<=min(wl) and v<=min(wr) and (v<max(wl+[v]+wr)):
                eq=(v in wl) or (v in wr)
                span=self._extension_span(l,i,v,down=True)
                out.append(WilliamsFractal("DOWN",i,i+self.right,v,float(h[i]),span,eq,True))
        return out
    def _extension_span(self,values,i,level,down=False):
        limit=min(len(values)-1,i+self.max_extension-self.right)
        span=5
        for j in range(i+self.right+1,min(i+5,limit)+1):
            if values[j]==level: span=6
        if limit>=i+7 and any(values[j]==level for j in range(i+3,i+8)): span=9
        return span
    def latest(self,df,side:str)->WilliamsFractal|None:
        side=side.upper(); fs=[f for f in self.detect(df) if f.side==("UP" if side in {"LONG","BUY"} else "DOWN") and f.confirmation_index<len(df)]
        return fs[-1] if fs else None
    def valid_at_trigger(self,fractal:WilliamsFractal,*,trigger_price:float,teeth:float)->bool:
        if fractal.side=="UP": return float(trigger_price)>float(teeth)
        return float(trigger_price)<float(teeth)
    def lifecycle(self,df:pd.DataFrame,side:str,current_trigger:float|None=None,teeth:float|None=None)->dict:
        items=[f for f in self.detect(df) if f.side==("UP" if str(side).upper() in {"LONG","BUY"} else "DOWN")]
        if not items:return {"status":"NONE","fractal":None}
        latest=items[-1]; status="ACTIVE"
        if current_trigger is not None:
            level_crossed=(current_trigger>=latest.level if latest.side=="UP" else current_trigger<=latest.level)
            if level_crossed: status="TRIGGERED"
            elif teeth is not None and not self.valid_at_trigger(latest,trigger_price=current_trigger,teeth=teeth): status="INVALID"
        return {"status":status,"fractal":latest}
