"""Small-deposit risk envelope kept separate from strategy truth."""
from __future__ import annotations
from dataclasses import dataclass
@dataclass(frozen=True)
class RiskEnvelope:
    initial_risk_pct:float=0.0025; max_campaign_risk_pct:float=0.006; max_daily_loss_pct:float=0.01; max_campaigns:int=1; max_full_stop_outs:int=2; leverage:float=1.0; averaging_down:bool=False; fixed_take_profit:bool=False
    def validate(self):
        if not (0<self.initial_risk_pct<=self.max_campaign_risk_pct<=0.05):raise ValueError("invalid risk envelope")
        if self.max_campaigns!=1 or self.leverage!=1.0 or self.averaging_down or self.fixed_take_profit:raise ValueError("production spot safety policy violated")
@dataclass(frozen=True)
class RiskDecision:
    allowed:bool; risk_pct:float; reason:str

SMALL_DEPOSIT_RISK=RiskEnvelope(); SMALL_DEPOSIT_RISK.validate()
def risk_for_quality(quality:str,envelope:RiskEnvelope=SMALL_DEPOSIT_RISK)->RiskDecision:
    q=str(quality).upper()
    table={"A":envelope.initial_risk_pct,"B":envelope.initial_risk_pct*0.75,"C":envelope.initial_risk_pct*0.5,"D":0.0}
    risk=table.get(q,0.0)
    return RiskDecision(risk>0,risk,"quality-adjusted risk" if risk>0 else "late quality blocked")
