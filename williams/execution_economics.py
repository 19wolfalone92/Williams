"""Economic feasibility gate; never changes Williams truth."""
from __future__ import annotations
from dataclasses import dataclass
@dataclass(frozen=True)
class EconomicsDecision:
    feasible:bool; expected_cost_pct:float; edge_required_pct:float; reasons:tuple[str,...]
    def to_dict(self):return {"feasible":self.feasible,"expected_cost_pct":self.expected_cost_pct,"edge_required_pct":self.edge_required_pct,"reasons":list(self.reasons)}
class ExecutionFeasibilityGate:
    def __init__(self,fee_per_side_pct:float=0.001,slippage_pct:float=0.0015,min_edge_multiple:float=2.0,max_spread_pct:float=0.0015):
        self.fee_per_side=max(0,float(fee_per_side_pct)); self.slippage=max(0,float(slippage_pct)); self.mult=max(1,float(min_edge_multiple)); self.max_spread=max(0,float(max_spread_pct))
    def evaluate(self,*,entry:float,stop:float,spread_pct:float,estimated_slippage_pct:float=0.0,minimum_notional_ok:bool=True,balance_ok:bool=True,time_to_eod_ok:bool=True)->EconomicsDecision:
        reasons=[]; stop_pct=abs(entry-stop)/entry if entry>0 else 0
        cost=2*self.fee_per_side+max(self.slippage,float(estimated_slippage_pct))+max(0,float(spread_pct))
        edge=max(0,stop_pct*1.0)
        if spread_pct>self.max_spread: reasons.append("spread_high")
        if not minimum_notional_ok: reasons.append("minimum_order_constraint")
        if not balance_ok: reasons.append("insufficient_balance")
        if not time_to_eod_ok: reasons.append("session_too_near_end")
        if stop_pct<=0: reasons.append("invalid_stop_distance")
        if edge < cost*self.mult: reasons.append("costs_dominate_structural_room")
        return EconomicsDecision(not reasons,cost,edge,tuple(reasons))
