"""Canonical Williams intraday system contract.

This module defines the engineering operating contract around the Williams
method. The indicator truth itself remains in the strategy/core modules.
"""
from __future__ import annotations
from dataclasses import dataclass
from datetime import datetime, time, timezone
from typing import Mapping
import os

CORE_PROFILE="WILLIAMS_CORE_INTRADAY"
CONSERVATIVE_PROFILE="WILLIAMS_CORE_INTRADAY_CONSERVATIVE"

@dataclass(frozen=True)
class TimeframeContract:
    macro_tf:str="1d"
    permission_tf:str="4h"
    context_tf:str="1h"
    decision_tf:str="15m"
    execution_tf:str="5m"
    airbag_tf:str="1d"
    def validate(self):
        actual=(self.macro_tf,self.permission_tf,self.context_tf,self.decision_tf,self.execution_tf,self.airbag_tf)
        expected=("1d","4h","1h","15m","5m","1d")
        if actual!=expected:
            raise ValueError(f"invalid Williams timeframe contract: {actual}")
    @property
    def all(self): return (self.macro_tf,self.permission_tf,self.context_tf,self.decision_tf,self.execution_tf)
    @property
    def higher_tf(self): return self.permission_tf

@dataclass(frozen=True)
class RiskContract:
    initial_risk_pct:float=.0025
    campaign_risk_pct:float=.006
    daily_loss_pct:float=.01
    max_campaigns:int=1
    max_full_stopouts:int=2
    leverage:float=0.0
    averaging_down:bool=False
    fixed_take_profit:bool=False
    reverse_pyramid_weights:tuple[int,...]=(1,5,4,3,2)
    def validate(self):
        if not 0<self.initial_risk_pct<=self.campaign_risk_pct: raise ValueError("initial risk must be <= campaign risk")
        if self.daily_loss_pct<self.campaign_risk_pct: raise ValueError("daily loss budget must cover campaign budget")
        if self.max_campaigns!=1 or self.leverage!=0 or self.averaging_down or self.fixed_take_profit:
            raise ValueError("invalid small-deposit risk contract")
        if self.reverse_pyramid_weights!=(1,5,4,3,2): raise ValueError("reverse pyramid must be 1:5:4:3:2")

@dataclass(frozen=True)
class SessionContract:
    start_utc:time=time(8,0)
    no_new_entries_utc:time=time(18,0)
    flat_utc:time=time(20,0)
    def state(self,when:datetime|None=None):
        t=(when or datetime.now(timezone.utc)).astimezone(timezone.utc).time()
        if t<self.start_utc:return "PRE_SESSION"
        if t<self.no_new_entries_utc:return "ENTRY_WINDOW"
        if t<self.flat_utc:return "MANAGE_ONLY"
        return "FLAT_REQUIRED"
    def allows_new_entries(self,when=None): return self.state(when)=="ENTRY_WINDOW"
    def requires_flat(self,when=None): return self.state(when)=="FLAT_REQUIRED"

@dataclass(frozen=True)
class IntradayPolicy:
    profile:str
    timeframes:TimeframeContract
    risk:RiskContract
    session:SessionContract
    symbols:tuple[str,...]=("BTCUSDT","ETHUSDT")
    wm3_first_allowed:bool=True
    h4_adverse_risk_factor:float=.50
    h4_supportive_risk_factor:float=1.0
    h4_neutral_risk_factor:float=1.0
    require_h4_support_for_conservative:bool=True
    @classmethod
    def from_env(cls,env:Mapping[str,str]|None=None):
        e=os.environ if env is None else env
        profile=str(e.get("WILLIAMS_STRATEGY_PROFILE",CORE_PROFILE)).strip().upper()
        if profile not in {CORE_PROFILE,CONSERVATIVE_PROFILE}:
            profile=CORE_PROFILE
        conservative=profile==CONSERVATIVE_PROFILE
        initial=.002 if conservative else .0025
        campaign=.005 if conservative else .006
        daily=.0075 if conservative else .01
        symbols=tuple(dict.fromkeys(
            x.strip().upper() for x in str(e.get("WILLIAMS_INTRADAY_SYMBOLS","BTCUSDT,ETHUSDT")).split(",") if x.strip()
        )) or ("BTCUSDT","ETHUSDT")
        p=cls(
            profile=profile,
            timeframes=TimeframeContract(),
            risk=RiskContract(
                initial_risk_pct=float(e.get("WILLIAMS_INITIAL_RISK_PCT",initial)),
                campaign_risk_pct=float(e.get("WILLIAMS_CAMPAIGN_RISK_PCT",campaign)),
                daily_loss_pct=float(e.get("WILLIAMS_DAILY_LOSS_PCT",daily)),
                max_campaigns=int(e.get("WILLIAMS_MAX_CAMPAIGNS",1)),
                max_full_stopouts=int(e.get("WILLIAMS_MAX_FULL_STOPOUTS",2)),
            ),
            session=SessionContract(
                start_utc=_parse_time(e.get("WILLIAMS_SESSION_START_UTC","08:00")),
                no_new_entries_utc=_parse_time(e.get("WILLIAMS_NO_NEW_ENTRIES_UTC","18:00")),
                flat_utc=_parse_time(e.get("WILLIAMS_FLAT_UTC","20:00")),
            ),
            symbols=symbols,
            wm3_first_allowed=str(e.get("WILLIAMS_WM3_FIRST_ALLOWED","true" if not conservative else "false")).lower() in {"1","true","yes","on"},
            h4_adverse_risk_factor=float(e.get("WILLIAMS_H4_ADVERSE_RISK_FACTOR",.50)),
            require_h4_support_for_conservative=conservative,
        )
        p.timeframes.validate()
        p.risk.validate()
        return p
    def initial_risk_for(self,*,quality="A",h4_context="NEUTRAL"):
        q={"A":1.0,"B":.75,"C":.50,"D":0.0}.get(str(quality).upper(),0.0)
        c={
            "SUPPORTIVE":self.h4_supportive_risk_factor,
            "NEUTRAL":self.h4_neutral_risk_factor,
            "ADVERSE":self.h4_adverse_risk_factor,
        }.get(str(h4_context).upper(),.50)
        return min(self.risk.initial_risk_pct,self.risk.campaign_risk_pct)*q*c

def _parse_time(value):
    hh,mm=str(value).strip().split(":",1)
    return time(int(hh),int(mm))
