"""Deterministic, side-specific engineering measurement of Williams angulation."""
from __future__ import annotations
from dataclasses import asdict,dataclass
import numpy as np
import pandas as pd

@dataclass(frozen=True)
class AngulationMeasurement:
    side:str; reference_segment:str; price_segment:str; reference_slope:float; price_slope:float
    initial_distance:float; final_distance:float; distance_growth:float; angular_separation:float; valid:bool; window:int
    def to_dict(self): return asdict(self)

def _slope(v):
    if len(v)<2:return 0.0
    return float(np.polyfit(np.arange(len(v),dtype=float),v.astype(float),1)[0])

def measure_side_angulation(ind,index,side,*,window=5):
    side=str(side).upper(); start=max(0,int(index)-max(3,int(window))+1); rows=ind.iloc[start:int(index)+1].copy()
    if len(rows)<3 or "jaw_shifted" not in rows.columns:
        return AngulationMeasurement(side,"JAW","CLOSE",0,0,0,0,0,0,False,len(rows))
    ref=pd.to_numeric(rows["jaw_shifted"],errors="coerce").to_numpy(float); price=pd.to_numeric(rows["close"],errors="coerce").to_numpy(float)
    if np.isnan(ref).any() or np.isnan(price).any():
        return AngulationMeasurement(side,"JAW","CLOSE",0,0,0,0,0,0,False,len(rows))
    rs=_slope(ref); ps=_slope(price)
    d=(ref-price) if side=="LONG" else (price-ref); angular=(rs-ps) if side=="LONG" else (ps-rs)
    initial=float(d[0]); final=float(d[-1]); growth=final-initial; score=angular/max(abs(float(price[-1])),1e-12)*100
    valid=initial>0 and final>initial and angular>0
    return AngulationMeasurement(side,"JAW","CLOSE",rs,ps,initial,final,growth,float(score),bool(valid),len(rows))
