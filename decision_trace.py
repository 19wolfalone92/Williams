"""Machine-readable explanation of every Williams Core decision."""
from __future__ import annotations
from dataclasses import asdict,dataclass,field
import json,uuid,time

@dataclass(frozen=True)
class DecisionTrace:
    trace_id:str;symbol:str;macro_tf:str;context_tf:str;decision_tf:str;execution_tf:str;micro_tf:str;decision_time_ms:int
    h4_context:str="NEUTRAL";d1_state:str="UNKNOWN";alligator_state:str="UNKNOWN";wm1:str="INVALID";wm2:str="INVALID";wm3:str="INVALID"
    fractal_state:str="NONE";angulation:dict=field(default_factory=dict);momentum_relation:str="";trigger_price:float=0;initial_stop:float=0
    campaign_step:int=1;first_signal_type:str="";quality:str="D";execution_feasible:bool=False;risk_feasible:bool=False
    core_valid:bool=False;trade_allowed:bool=False;block_reason:str="";fields:dict=field(default_factory=dict)
    created_at_ms:int=field(default_factory=lambda:int(time.time()*1000))
    @classmethod
    def new(cls,**kwargs):return cls(trace_id=kwargs.pop("trace_id",uuid.uuid4().hex),**kwargs)
    def to_dict(self):return asdict(self)
    def to_json(self):return json.dumps(self.to_dict(),sort_keys=True,default=str)
