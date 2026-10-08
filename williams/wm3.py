"""Third Wise Man: dynamic Teeth-aware fractal trigger."""
from __future__ import annotations
from dataclasses import dataclass
from .fractals import FractalEngine,WilliamsFractal
@dataclass(frozen=True)
class WM3Result:
    side:str; valid:bool; fractal:WilliamsFractal|None=None; trigger_price:float=0.0; protective_reference:float=0.0; teeth_at_detection:float=0.0; teeth_at_trigger:float=0.0; reason:str=""
def evaluate_wm3(df,side:str="LONG",teeth_at_trigger:float=0.0,tick_size:float=0.0,engine:FractalEngine|None=None)->WM3Result:
    side=str(side).upper(); engine=engine or FractalEngine(); f=engine.latest(df,side)
    if f is None:return WM3Result(side,False,reason="no confirmed fractal")
    tick=max(float(tick_size),0.0); trigger=f.level+tick if side in {"LONG","BUY"} else f.level-tick
    valid=engine.valid_at_trigger(f,trigger_price=trigger,teeth=float(teeth_at_trigger or 0.0))
    return WM3Result(side,bool(valid),f,trigger,f.protective_extreme,float(teeth_at_trigger or 0.0),float(teeth_at_trigger or 0.0),"WM3 valid at trigger" if valid else "fractal trigger is not beyond current Teeth")
