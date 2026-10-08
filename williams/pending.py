"""Durable signal lifecycle independent from exchange orders."""
from __future__ import annotations
from dataclasses import dataclass,field,replace
from enum import Enum
import time
class PendingState(str,Enum): DETECTED="DETECTED"; ACTIVE="ACTIVE"; TRIGGERED="TRIGGERED"; FILLED="FILLED"; SUPERSEDED="SUPERSEDED"; INVALID="INVALID"; EXPIRED="EXPIRED"; CANCELLED="CANCELLED"
_ALLOWED={PendingState.DETECTED:{PendingState.ACTIVE,PendingState.INVALID,PendingState.EXPIRED},PendingState.ACTIVE:{PendingState.TRIGGERED,PendingState.SUPERSEDED,PendingState.INVALID,PendingState.EXPIRED,PendingState.CANCELLED},PendingState.TRIGGERED:{PendingState.FILLED,PendingState.CANCELLED,PendingState.INVALID},PendingState.FILLED:set(),PendingState.SUPERSEDED:set(),PendingState.INVALID:set(),PendingState.EXPIRED:set(),PendingState.CANCELLED:set()}
@dataclass(frozen=True)
class IntradayPendingSignal:
    signal_id:str; symbol:str; side:str; signal_type:str; signal_bar_time_ms:int; trigger_price:float; protective_reference:float
    decision_tf:str="1h"; teeth_at_detection:float=0.0; state:PendingState=PendingState.DETECTED; supersedes_signal_id:str=""; created_at_ms:int=field(default_factory=lambda:int(time.time()*1000)); context:dict=field(default_factory=dict)
    def transition(self,state:PendingState)->"IntradayPendingSignal":
        if state!=self.state and state not in _ALLOWED[self.state]: raise ValueError(f"invalid pending transition {self.state.value}->{state.value}")
        return replace(self,state=state)
    def supersede(self,new_signal_id:str)->"IntradayPendingSignal":
        return replace(self,state=PendingState.SUPERSEDED,supersedes_signal_id=str(new_signal_id))
