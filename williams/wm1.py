"""First Wise Man and side-specific angulation measurement."""
from __future__ import annotations
from dataclasses import dataclass,asdict
import numpy as np
import pandas as pd

@dataclass(frozen=True)
class AngulationMeasurement:
    side:str; reference_segment:str; price_segment:str; reference_slope:float; price_slope:float
    initial_distance:float; final_distance:float; distance_growth:float; angular_separation:float; valid:bool
    def to_dict(self): return asdict(self)

@dataclass(frozen=True)
class WM1Result:
    side:str; valid:bool; index:int=-1; trigger_price:float=0.0; initial_stop:float=0.0
    market_direction:bool=False; extreme:bool=False; upper_half:bool=False; outside_mouth:bool=False; ao_relation:bool=False
    angulation:AngulationMeasurement|None=None; reason:str=""

def _slope(values):
    y=np.asarray(values,dtype=float); x=np.arange(len(y),dtype=float)
    if len(y)<2:return 0.0
    return float(np.polyfit(x,y,1)[0])

def measure_angulation(df:pd.DataFrame,index:int,side:str="LONG",window:int=5)->AngulationMeasurement:
    start=max(0,index-max(3,int(window))+1); rows=df.iloc[start:index+1].copy()
    side=str(side).upper(); ref=(pd.to_numeric(rows["jaw_shifted"])+pd.to_numeric(rows["teeth_shifted"]))/2.0
    price=pd.to_numeric(rows["low" if side in {"LONG","BUY"} else "high"])
    if ref.isna().any() or price.isna().any():
        return AngulationMeasurement(side,"JAW+TEETH","LOW" if side in {"LONG","BUY"} else "HIGH",0,0,0,0,0,0,False)
    rs=_slope(ref); ps=_slope(price)
    if side in {"LONG","BUY"}:
        d=(ref-price); separation=rs-ps
    else:
        d=(price-ref); separation=ps-rs
    di=float(d.iloc[0]); dfinal=float(d.iloc[-1]); growth=dfinal-di
    valid=bool(di>0 and growth>0 and separation>0)
    base=max(abs(float(rows["close"].iloc[-1])),1e-9)
    return AngulationMeasurement(side,"JAW+TEETH","LOW" if side in {"LONG","BUY"} else "HIGH",rs,ps,di,dfinal,growth,(separation/base)*100.0,valid)

def evaluate_wm1(df:pd.DataFrame,index:int,side:str="LONG",outside_atr_mult:float=0.10,angulation_window:int=5)->WM1Result:
    if index<2 or index>=len(df):return WM1Result(str(side).upper(),False,reason="invalid index")
    side=str(side).upper(); r=df.iloc[index]; prev=df.iloc[index-2:index]
    high=float(r["high"]); low=float(r["low"]); close=float(r["close"]); rng=high-low
    if rng<=0:return WM1Result(side,False,index,reason="zero range")
    atr_window=df.iloc[max(0,index-13):index+1]
    tr=pd.concat([(atr_window["high"]-atr_window["low"]),(atr_window["high"]-atr_window["close"].shift(1)).abs(),(atr_window["low"]-atr_window["close"].shift(1)).abs()],axis=1).max(axis=1)
    atr=float(tr.mean() or 0.0)
    mouth=[float(r.get(k,0.0) or 0.0) for k in ("jaw_shifted","teeth_shifted","lips_shifted")]
    if not all(np.isfinite(mouth)):return WM1Result(side,False,index,reason="Alligator unavailable")
    if side in {"LONG","BUY"}:
        market_direction=float(prev["close"].iloc[-1])<float(prev["close"].iloc[0])
        extreme=low<float(prev["low"].min()); upper=(close-low)/rng>=0.50
        mouth_floor=min(mouth); outside=low < mouth_floor-max(0.0,atr)*float(outside_atr_mult)
        ao=float(r.get("ao",0.0) or 0.0); prev_ao=float(df.iloc[index-1].get("ao",0.0) or 0.0); ao_relation=ao<=0 or ao<prev_ao
        trigger=high; stop=low
    else:
        market_direction=float(prev["close"].iloc[-1])>float(prev["close"].iloc[0])
        extreme=high>float(prev["high"].max()); upper=(high-close)/rng>=0.50
        mouth_ceil=max(mouth); outside=high > mouth_ceil+max(0.0,atr)*float(outside_atr_mult)
        ao=float(r.get("ao",0.0) or 0.0); prev_ao=float(df.iloc[index-1].get("ao",0.0) or 0.0); ao_relation=ao>=0 or ao>prev_ao
        trigger=low; stop=high
    ang=measure_angulation(df,index,side,angulation_window)
    valid=all((market_direction,extreme,upper,outside,ang.valid,ao_relation))
    reason="WM1 valid" if valid else "WM1 blocked: "+",".join(k for k,v in {"direction":market_direction,"extreme":extreme,"upper_half":upper,"outside_mouth":outside,"angulation":ang.valid,"ao_relation":ao_relation}.items() if not v)
    return WM1Result(side,valid,index,trigger,stop,market_direction,extreme,upper,outside,ao_relation,ang,reason)

def latest_wm1(df:pd.DataFrame,side:str="LONG",max_age_bars:int=20)->WM1Result:
    start=max(0,len(df)-max(2,int(max_age_bars)))
    for i in range(len(df)-1,start-1,-1):
        result=evaluate_wm1(df,i,side)
        if result.valid:return result
    return WM1Result(str(side).upper(),False,reason="no valid WM1")
