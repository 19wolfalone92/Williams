"""Williams fractal detector with explicit 5/6/9/overlap lifecycle metadata."""
from __future__ import annotations
from dataclasses import asdict,dataclass
from typing import Iterable
import pandas as pd

@dataclass(frozen=True)
class FractalObservation:
    side:str; center_index:int; confirmation_index:int; level:float; formation:str
    shared_bar:bool=False; overlapping:bool=False; teeth_at_confirmation:float=0.0; valid_at_confirmation:bool=False
    def to_dict(self):return asdict(self)

class WilliamsFractalEngine:
    def __init__(self,*,base_left=2,base_right=2):self.base_left=int(base_left);self.base_right=int(base_right)
    def _basic(self,f,side):
        out=[]
        for i in range(self.base_left,len(f)-self.base_right):
            if side=="LONG":
                x=float(f.high.iloc[i]); ok=x>max(map(float,f.high.iloc[i-self.base_left:i])) and x>max(map(float,f.high.iloc[i+1:i+self.base_right+1]))
            else:
                x=float(f.low.iloc[i]); ok=x<min(map(float,f.low.iloc[i-self.base_left:i])) and x<min(map(float,f.low.iloc[i+1:i+self.base_right+1]))
            if ok: out.append(FractalObservation(side,i,i+self.base_right,x,"FIVE"))
        return out
    def _shared_six(self,f,side):
        out=[]
        for i in range(2,len(f)-3):
            j=i+1
            a=float(f.high.iloc[i] if side=="LONG" else f.low.iloc[i]); b=float(f.high.iloc[j] if side=="LONG" else f.low.iloc[j])
            if a!=b:continue
            outer=[float(f.high.iloc[i-2]),float(f.high.iloc[i-1]),float(f.high.iloc[j+1]),float(f.high.iloc[j+2])] if side=="LONG" else [float(f.low.iloc[i-2]),float(f.low.iloc[i-1]),float(f.low.iloc[j+1]),float(f.low.iloc[j+2])]
            if (a>max(outer)) if side=="LONG" else (a<min(outer)):
                out.append(FractalObservation(side,j,j+2,a,"SIX_SHARED",shared_bar=True))
        return out
    def _nine(self,f,side):
        out=[]
        for i in range(4,len(f)-4):
            a=float(f.high.iloc[i] if side=="LONG" else f.low.iloc[i])
            vals=list(map(float,(f.high.iloc[i-4:i] if side=="LONG" else f.low.iloc[i-4:i])))
            vals+=list(map(float,(f.high.iloc[i+1:i+5] if side=="LONG" else f.low.iloc[i+1:i+5])))
            if (a>max(vals)) if side=="LONG" else (a<min(vals)):out.append(FractalObservation(side,i,i+4,a,"NINE_EXTENDED"))
        return out
    def detect(self,frame,*,side,teeth_series=None):
        side=str(side).upper()
        if side not in {"LONG","SHORT"}:raise ValueError("invalid fractal side")
        if frame is None or len(frame)<5:return []
        obs=self._basic(frame,side)+self._shared_six(frame,side)+self._nine(frame,side);obs.sort(key=lambda x:(x.confirmation_index,x.center_index,x.formation))
        out=[];seen=set();last=None
        for o in obs:
            key=(o.side,o.center_index,o.level,o.formation)
            if key in seen:continue
            seen.add(key);teeth=0.0
            if teeth_series is not None and o.confirmation_index<len(teeth_series):
                try:teeth=float(teeth_series.iloc[o.confirmation_index])
                except Exception:teeth=0.0
            overlap=last is not None and abs(o.center_index-last.center_index)<=4
            valid=(o.level>teeth) if side=="LONG" else (o.level<teeth)
            out.append(FractalObservation(o.side,o.center_index,o.confirmation_index,o.level,o.formation,o.shared_bar,overlap,teeth,bool(valid)));last=o
        return out
    @staticmethod
    def latest_actionable(observations:Iterable[FractalObservation],current_index,teeth_now,side):
        side=str(side).upper()
        for o in sorted((x for x in observations if x.confirmation_index<=current_index),key=lambda x:(x.confirmation_index,x.center_index),reverse=True):
            if (o.level>teeth_now) if side=="LONG" else (o.level<teeth_now):return o
        return None
