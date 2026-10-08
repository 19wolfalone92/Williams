"""Williams angulation measurement using the price edge, not candle close.

Book rule translated into deterministic geometry:
- LONG: lower price edge must diverge away from the Alligator reference.
- SHORT: upper price edge must diverge away from the Alligator reference.
The exact drawing is visual in the books, so the numeric score remains an
engineering representation of that geometry, not an author-defined formula.
"""
from __future__ import annotations
from dataclasses import asdict,dataclass
import numpy as np
import pandas as pd

@dataclass(frozen=True)
class AngulationMeasurement:
    side:str
    reference_segment:str
    price_segment:str
    reference_slope:float
    price_slope:float
    initial_distance:float
    final_distance:float
    distance_growth:float
    angular_separation:float
    valid:bool
    window:int
    def to_dict(self): return asdict(self)

def _slope(v):
    if len(v)<2:return 0.0
    a=np.asarray(v,dtype=float)
    return float(np.polyfit(np.arange(len(a),dtype=float),a,1)[0])

def measure_side_angulation(ind,index,side,*,window=5):
    side=str(side).upper()
    start=max(0,int(index)-max(3,int(window))+1)
    rows=ind.iloc[start:int(index)+1].copy()
    edge_col="low" if side=="LONG" else "high"
    if len(rows)<3 or "jaw_shifted" not in rows.columns or edge_col not in rows.columns:
        return AngulationMeasurement(side,"JAW",edge_col.upper(),0,0,0,0,0,0,False,len(rows))
    ref=pd.to_numeric(rows["jaw_shifted"],errors="coerce").to_numpy(float)
    edge=pd.to_numeric(rows[edge_col],errors="coerce").to_numpy(float)
    if np.isnan(ref).any() or np.isnan(edge).any():
        return AngulationMeasurement(side,"JAW",edge_col.upper(),0,0,0,0,0,0,False,len(rows))
    rs=_slope(ref); ps=_slope(edge)
    if side=="LONG":
        d=ref-edge
        angular=rs-ps
    else:
        d=edge-ref
        angular=ps-rs
    initial=float(d[0]); final=float(d[-1]); growth=final-initial
    score=angular/max(abs(float(edge[-1])),1e-12)*100.0
    valid=initial>0 and final>initial and angular>0
    return AngulationMeasurement(
        side,"JAW",edge_col.upper(),rs,ps,initial,final,growth,float(score),bool(valid),len(rows)
    )
