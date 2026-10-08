"""H1 structural trail and operational EOD exit."""
from __future__ import annotations
from dataclasses import dataclass
@dataclass(frozen=True)
class TrailDecision:
    stop:float; source:str; moved:bool; reason:str

def structural_trail_long(recent_h1_lows:list[float],current_stop:float,current_price:float,buffer:float=0.0)->TrailDecision:
    lows=[float(x) for x in recent_h1_lows if float(x)>0]
    if not lows:return TrailDecision(current_stop,"UNAVAILABLE",False,"no structural lows")
    proposed=max(lows)-max(0,float(buffer))
    if proposed<=current_stop:return TrailDecision(current_stop,"3_5_H1_BAR",False,"stop would loosen risk")
    if proposed>=current_price:return TrailDecision(current_stop,"3_5_H1_BAR",False,"stop not below market")
    return TrailDecision(proposed,"3_5_H1_BAR",True,"structural stop advanced")

def end_of_day_actions(policy,dt,has_pending:bool,has_position:bool)->dict:
    return {"cancel_pending":bool(has_pending and policy.pending_action(dt)=="CANCEL"),"force_flat":bool(has_position and policy.must_flat(dt)),"allow_new":policy.allows_new_campaign(dt)}
