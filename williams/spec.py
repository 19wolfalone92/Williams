"""Production Williams Intraday contract and profile resolver."""
from __future__ import annotations
from dataclasses import dataclass
import os
from .timeframe import CORE_TIMEFRAME_CONTRACT
from .risk_policy import SMALL_DEPOSIT_RISK
from .intraday_policy import IntradayPolicy
@dataclass(frozen=True)
class WilliamsIntradaySpec:
    name:str="WILLIAMS_INTRADAY_CORE_1.0"; market:str="SPOT"; primary_symbols:tuple[str,...]=( "BTCUSDT","ETHUSDT")
    timeframes:object=CORE_TIMEFRAME_CONTRACT; risk:object=SMALL_DEPOSIT_RISK; session:object=IntradayPolicy()
    h4_adverse_blocks:bool=False; wm3_first_allowed:bool=True; wave_can_invalidate:bool=False; quant_can_invalidate:bool=False
    def validate(self):
        self.timeframes.validate(); self.risk.validate()
        if self.market!="SPOT":raise ValueError("production contract is Spot")
        if not self.primary_symbols:raise ValueError("universe cannot be empty")
        if self.wave_can_invalidate or self.quant_can_invalidate:raise ValueError("advisory layers cannot invalidate Core")
    def to_dict(self):
        return {"name":self.name,"market":self.market,"symbols":list(self.primary_symbols),"timeframes":self.timeframes.to_dict(),"risk":self.risk.__dict__,"session":self.session.__dict__,"h4_adverse_blocks":self.h4_adverse_blocks}
WILLIAMS_INTRADAY_CORE_1_0=WilliamsIntradaySpec(); WILLIAMS_INTRADAY_CORE_1_0.validate()

def resolve_spec(env=None):
    e=os.environ if env is None else env
    mode=str(e.get("WILLIAMS_MODE","INTRADAY_CORE")).upper()
    if mode not in {"INTRADAY_CORE","INTRADAY_FAST","POSITION"}: mode="INTRADAY_CORE"
    if mode=="INTRADAY_CORE": return WILLIAMS_INTRADAY_CORE_1_0
    return WILLIAMS_INTRADAY_CORE_1_0
