"""Immutable timeframe contract for Williams Intraday Core."""
from __future__ import annotations
from dataclasses import dataclass
from typing import Mapping

D1="1d"; H4="4h"; H1="1h"; M15="15m"; M5="5m"

@dataclass(frozen=True)
class TimeframeContract:
    macro: str=D1
    context: str=H4
    decision: str=H1
    execution: str=M15
    micro: str=M5
    airbag: str=D1
    def validate(self)->None:
        expected=(D1,H4,H1,M15,M5,D1)
        actual=(self.macro,self.context,self.decision,self.execution,self.micro,self.airbag)
        if actual!=expected: raise ValueError(f"unsupported production timeframe contract: {actual}")
    def to_dict(self)->dict[str,str]:
        return {"macro":self.macro,"context":self.context,"decision":self.decision,"execution":self.execution,"micro":self.micro,"airbag":self.airbag}

CORE_TIMEFRAME_CONTRACT=TimeframeContract()
CORE_TIMEFRAME_CONTRACT.validate()

def normalize_timeframe(value:str)->str:
    v=str(value or "").strip().lower()
    aliases={"d":"1d","day":"1d","daily":"1d","h4":"4h","4hour":"4h","h1":"1h","hour":"1h","m15":"15m","15":"15m","m5":"5m","5":"5m"}
    return aliases.get(v,v)

def assert_core_roles(intervals:Mapping[str,str])->None:
    expected=CORE_TIMEFRAME_CONTRACT.to_dict()
    for role,tf in expected.items():
        if normalize_timeframe(intervals.get(role,""))!=tf: raise ValueError(f"{role} must be {tf}")
