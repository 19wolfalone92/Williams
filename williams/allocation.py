"""Risk-budgeted sizing for reverse-pyramid allocations."""
from __future__ import annotations
from dataclasses import dataclass
WEIGHTS=(1,5,4,3,2)
@dataclass(frozen=True)
class Allocation:
    quantity:float; notional:float; risk_quote:float; effective_loss_fraction:float; allowed:bool; reason:str; weight:int; step:int

def weight_for_step(step:int)->int:return WEIGHTS[min(max(int(step)-1,0),len(WEIGHTS)-1)]
def size_for_risk(*,equity:float,risk_pct:float,entry:float,stop:float,fee_pct:float=0.001,slippage_pct:float=0.0015,max_position_fraction:float=0.25,min_notional:float=0.0,step:int=1,campaign_remaining_risk:float|None=None)->Allocation:
    if equity<=0 or entry<=0 or stop<=0:return Allocation(0,0,0,0,False,"invalid market/risk inputs",weight_for_step(step),step)
    distance=abs(entry-stop)/entry
    effective=distance+2*max(0,fee_pct)+max(0,slippage_pct)
    budget=min(equity*max(0,risk_pct),campaign_remaining_risk if campaign_remaining_risk is not None else equity*max(0,risk_pct))
    if budget<=0:return Allocation(0,0,0,effective,False,"risk budget exhausted",weight_for_step(step),step)
    notional=min(equity*max(0,max_position_fraction),budget/max(effective,1e-12)); qty=notional/entry
    if notional<=0:return Allocation(0,0,0,effective,False,"position size zero",weight_for_step(step),step)
    if min_notional>0 and notional<min_notional:return Allocation(0,notional,budget,effective,False,"minimum order notional incompatible with risk budget",weight_for_step(step),step)
    return Allocation(qty,notional,budget,effective,True,"risk-sized",weight_for_step(step),step)
