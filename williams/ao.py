"""Canonical Awesome Oscillator for the H1 decision stream."""
from __future__ import annotations
import pandas as pd

def calculate_ao(df:pd.DataFrame,fast:int=5,slow:int=34)->pd.DataFrame:
    x=df.copy(); median=(pd.to_numeric(x["high"])+pd.to_numeric(x["low"]))/2.0
    x["ao"]=median.rolling(int(fast)).mean()-median.rolling(int(slow)).mean()
    x["ao_green"]=x["ao"]>x["ao"].shift(1); x["ao_red"]=x["ao"]<x["ao"].shift(1)
    def streak(mask):
        n=0; out=[]
        for v in mask.fillna(False).astype(bool): n=n+1 if v else 0; out.append(n)
        return pd.Series(out,index=x.index,dtype=int)
    x["ao_green_streak"]=streak(x["ao_green"]); x["ao_red_streak"]=streak(x["ao_red"])
    return x
