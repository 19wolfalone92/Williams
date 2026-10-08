"""Williams Alligator calculated from one decision-timeframe sequence."""
from __future__ import annotations
from dataclasses import dataclass
import numpy as np
import pandas as pd

@dataclass(frozen=True)
class AlligatorSnapshot:
    jaw: float; teeth: float; lips: float; jaw_shifted: float; teeth_shifted: float; lips_shifted: float
    spread_pct: float; state: str

def smma(series:pd.Series, period:int)->pd.Series:
    p=int(period)
    if p<=0: raise ValueError("period must be positive")
    out=pd.Series(np.nan,index=series.index,dtype=float)
    if len(series)<p:return out
    out.iloc[p-1]=pd.to_numeric(series.iloc[:p],errors="coerce").mean()
    for i in range(p,len(series)):
        out.iloc[i]=(out.iloc[i-1]*(p-1)+float(series.iloc[i]))/p
    return out

def classify_state(spread:pd.Series,min_spread_pct:float=0.001,lookback:int=3)->pd.Series:
    out=pd.Series("NEUTRAL",index=spread.index,dtype=object)
    out.loc[spread<=min_spread_pct]="SLEEPING"
    growth=spread.diff(max(1,int(lookback)))
    out.loc[(spread>min_spread_pct)&(growth>0)]="AWAKENING"
    return out

def calculate_alligator(df:pd.DataFrame,jaw_period:int=13,teeth_period:int=8,lips_period:int=5,jaw_shift:int=8,teeth_shift:int=5,lips_shift:int=3,min_spread_pct:float=0.001)->pd.DataFrame:
    x=df.copy()
    median=(pd.to_numeric(x["high"])+pd.to_numeric(x["low"]))/2.0
    x["jaw"]=smma(median,jaw_period); x["teeth"]=smma(median,teeth_period); x["lips"]=smma(median,lips_period)
    x["jaw_shifted"]=x["jaw"].shift(jaw_shift); x["teeth_shifted"]=x["teeth"].shift(teeth_shift); x["lips_shifted"]=x["lips"].shift(lips_shift)
    mouth=x[["jaw_shifted","teeth_shifted","lips_shifted"]]
    x["alligator_spread_pct"]=(mouth.max(axis=1)-mouth.min(axis=1))/x["close"].replace(0,np.nan)
    x["alligator_state"]=classify_state(x["alligator_spread_pct"],min_spread_pct=min_spread_pct)
    x["bullish_alligator"]=(x["close"]>x["lips_shifted"])&(x["lips_shifted"]>x["teeth_shifted"])&(x["teeth_shifted"]>x["jaw_shifted"])
    x["bearish_alligator"]=(x["close"]<x["lips_shifted"])&(x["lips_shifted"]<x["teeth_shifted"])&(x["teeth_shifted"]<x["jaw_shifted"])
    return x

def snapshot(row:pd.Series)->AlligatorSnapshot:
    return AlligatorSnapshot(*(float(row.get(k,0.0) or 0.0) for k in ("jaw","teeth","lips","jaw_shifted","teeth_shifted","lips_shifted")),float(row.get("alligator_spread_pct",0.0) or 0.0),str(row.get("alligator_state","NEUTRAL")))
