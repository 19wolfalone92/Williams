"""Execution-only feasibility gate; never changes Williams signal truth."""
from __future__ import annotations
from dataclasses import asdict,dataclass

@dataclass(frozen=True)
class EconomicDecision:
    allowed:bool;block_reason:str;equity_quote:float;risk_quote:float;stop_distance_pct:float;estimated_round_trip_cost_pct:float;quantity:float;notional_quote:float;expected_edge_pct:float=0.0
    def to_dict(self):return asdict(self)

class ExecutionEconomicsGate:
    def __init__(self,*,fee_pct=.001,slippage_pct=.0015,spread_pct_limit=.0015,cost_multiple=1.5):
        self.fee_pct=float(fee_pct);self.slippage_pct=float(slippage_pct);self.spread_pct_limit=float(spread_pct_limit);self.cost_multiple=max(1,float(cost_multiple))
    def evaluate(self,*,equity_quote,entry_price,stop_price,risk_pct,spread_pct,min_qty=0,qty_step=0,min_notional=0,expected_edge_pct=0):
        equity=float(equity_quote);entry=float(entry_price);stop=float(stop_price);spread=float(spread_pct)
        if equity<=0:return self._blocked(equity,"EQUITY_UNAVAILABLE")
        if entry<=0 or stop<=0 or stop>=entry:return self._blocked(equity,"STRUCTURAL_STOP_INVALID")
        if spread>self.spread_pct_limit:return self._blocked(equity,f"SPREAD_TOO_HIGH:{spread:.6%}")
        dist=(entry-stop)/entry;cost=2*self.fee_pct+self.slippage_pct+spread
        if dist<=cost*.25:return self._blocked(equity,"STRUCTURAL_STOP_TOO_CLOSE_FOR_COSTS")
        risk=equity*max(0,float(risk_pct));qty=risk/max(entry-stop,1e-12)
        if qty_step>0:qty=(qty//qty_step)*qty_step
        notional=qty*entry
        if qty<=0 or qty<min_qty:return self._blocked(equity,"MIN_QTY_INCOMPATIBLE")
        if notional<min_notional:return self._blocked(equity,"MIN_NOTIONAL_INCOMPATIBLE")
        if expected_edge_pct>0 and expected_edge_pct<=cost*self.cost_multiple:
            return EconomicDecision(False,"EXPECTED_EDGE_TOO_SMALL_FOR_COSTS",equity,risk,dist,cost,qty,notional,expected_edge_pct)
        return EconomicDecision(True,"",equity,risk,dist,cost,qty,notional,expected_edge_pct)
    def _blocked(self,equity,reason):return EconomicDecision(False,reason,equity,0,0,0,0,0)
