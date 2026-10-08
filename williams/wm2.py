"""Second Wise Man: exactly the third same-colour H1 AO bar."""
from __future__ import annotations
from dataclasses import dataclass
import pandas as pd
@dataclass(frozen=True)
class WM2Result:
    side:str; valid:bool; index:int=-1; trigger_price:float=0.0; protective_reference:float=0.0; streak:int=0; reason:str=""
def evaluate_wm2(df:pd.DataFrame,index:int,side:str="LONG")->WM2Result:
    side=str(side).upper(); col="ao_green_streak" if side in {"LONG","BUY"} else "ao_red_streak"; row=df.iloc[index]
    streak=int(row.get(col,0) or 0)
    valid=streak==3
    trigger=float(row["high"] if side in {"LONG","BUY"} else row["low"])
    protective=float(row["low"] if side in {"LONG","BUY"} else row["high"])
    return WM2Result(side,valid,index,trigger,protective,streak,"WM2 third AO colour bar" if valid else "not third AO colour bar")
def latest_wm2(df:pd.DataFrame,side:str="LONG",max_age_bars:int=20)->WM2Result:
    if df.empty:return WM2Result(str(side).upper(),False,reason="empty")
    start=max(0,len(df)-max(2,int(max_age_bars)))
    # Preserve the third-colour AO event after the streak continues beyond
    # three bars; the original WM2 trigger remains a durable H1 event.
    for i in range(len(df)-1,start-1,-1):
        r=evaluate_wm2(df,i,side)
        if r.valid:
            return r
    return WM2Result(str(side).upper(),False,reason="no third AO colour bar in window")
