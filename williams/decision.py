"""Canonical strategy decision and diagnostic trace model."""
from __future__ import annotations
from dataclasses import dataclass,field,asdict
from typing import Any,Mapping
import time,uuid
@dataclass(frozen=True)
class StrategyDecision:
    symbol:str; decision_tf:str; signal_type:str="NONE"; signal_side:str="NONE"; signal_time_ms:int=0; decision_candle:dict=field(default_factory=dict)
    alligator_state:str="NEUTRAL"; wm1:dict=field(default_factory=dict); wm2:dict=field(default_factory=dict); wm3:dict=field(default_factory=dict); fractal:dict=field(default_factory=dict)
    angulation:dict=field(default_factory=dict); momentum_relation:dict=field(default_factory=dict); trigger_price:float=0.0; initial_stop:float=0.0
    campaign_id:str=""; campaign_step:int=0; context_state:str="NEUTRAL"; execution_feasible:bool=True; risk_feasible:bool=True; core_valid:bool=False; trade_allowed:bool=False; block_reason:str=""
    quality:str="C"; trace_id:str=field(default_factory=lambda:uuid.uuid4().hex); created_at_ms:int=field(default_factory=lambda:int(time.time()*1000))
    def to_dict(self):return asdict(self)

@dataclass(frozen=True)
class DecisionTraceRecord:
    decision:StrategyDecision; stage:str="STRATEGY"; reason:str=""
    def to_dict(self):
        d=self.decision.to_dict(); d.update({"stage":self.stage,"reason":self.reason}); return d
