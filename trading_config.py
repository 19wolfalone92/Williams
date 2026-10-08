"""Single source of truth for the production Williams Intraday configuration.

The production profile is intentionally conservative for a small Spot account:
D1 macro -> H4 context -> H1 decision -> M15 execution -> M5 replay.
Strategy truth lives on H1; advisory layers may rank or adjust risk but never
invalidate a Williams Core decision.
"""
from __future__ import annotations
import os
from dataclasses import dataclass
from typing import Mapping,Sequence

DEFAULT_SYMBOLS=("BTCUSDT","ETHUSDT")
DEFAULT_STRUCTURAL_TFS=("1d","4h","1h","15m","5m")

def _bool(env:Mapping[str,str],key:str,default:bool)->bool:
    return str(env.get(key,str(default))).strip().lower() in {"1","true","yes","on"}
def _float(env:Mapping[str,str],key:str,default:float)->float:
    try:return float(env.get(key,str(default)))
    except (TypeError,ValueError):return default
def _int(env:Mapping[str,str],key:str,default:int)->int:
    try:return int(env.get(key,str(default)))
    except (TypeError,ValueError):return default
def _csv(env:Mapping[str,str],key:str,default:Sequence[str])->tuple[str,...]:
    raw=str(env.get(key,"")).strip()
    if not raw:return tuple(default)
    vals=tuple(dict.fromkeys(v.strip().upper() for v in raw.split(",") if v.strip()))
    return vals or tuple(default)

def _tf_chain(execution:str,env:Mapping[str,str])->tuple[str,...]:
    explicit=str(env.get("STRUCTURAL_TIMEFRAMES","")).strip()
    if explicit:return tuple(dict.fromkeys(x.strip().lower() for x in explicit.split(",") if x.strip()))
    if str(env.get("WILLIAMS_MODE","INTRADAY_CORE")).upper()=="INTRADAY_CORE":
        return DEFAULT_STRUCTURAL_TFS
    chains={
        "1m":("4h","1h","15m","5m","1m"),"5m":("1d","4h","1h","15m","5m"),
        "15m":("1d","4h","1h","15m"),"30m":("1d","4h","1h","30m","15m"),
        "1h":DEFAULT_STRUCTURAL_TFS,"1d":("1w","1d","4h"),"1w":("1M","1w","1d"),
    }
    return chains.get(str(execution).lower(),("1d","4h","1h",str(execution).lower()))

@dataclass(frozen=True)
class TradingConfig:
    mode:str="INTRADAY_CORE"
    symbols:tuple[str,...]=DEFAULT_SYMBOLS
    structural_timeframes:tuple[str,...]=DEFAULT_STRUCTURAL_TFS
    execution_timeframe:str="15m"
    decision_timeframe:str="1h"
    macro_timeframe:str="1d"
    context_timeframe:str="4h"
    micro_timeframe:str="5m"
    airbag_timeframe:str="1d"
    allow_long:bool=True
    allow_short:bool=False
    require_htf_confirmation:bool=False
    h4_adverse_blocks:bool=False
    no_trade_when_uncertain:bool=True
    risk_per_trade_pct:float=0.0025
    max_total_risk_pct:float=0.006
    max_daily_loss_pct:float=0.01
    max_consecutive_losses:int=2
    max_full_stop_outs:int=2
    cooldown_minutes:int=30
    max_open_positions:int=1
    leverage:float=1.0
    averaging_down:bool=False
    fixed_take_profit:bool=False
    eod_flat:bool=True
    session_start_utc:str="08:00"
    no_new_entries_utc:str="18:00"
    mandatory_flat_utc:str="20:00"
    min_risk_reward:float=0.0
    atr_period:int=14
    max_atr_pct:float=0.08
    max_spread_pct:float=0.0015
    max_l2_slippage_pct:float=0.0015
    wm1_outside_atr_mult:float=0.10
    wm1_angulation_window:int=5
    wm3_first_allowed:bool=True
    wave_can_invalidate:bool=False
    quant_can_invalidate:bool=False
    dry_run:bool=True
    allow_live:bool=False

    @classmethod
    def from_env(cls,env:Mapping[str,str]|None=None)->"TradingConfig":
        e=os.environ if env is None else env
        mode=str(e.get("WILLIAMS_MODE","INTRADAY_CORE")).upper()
        core=mode=="INTRADAY_CORE"
        symbols=_csv(e,"WILLIAMS_SYMBOLS",DEFAULT_SYMBOLS if core else DEFAULT_SYMBOLS)
        if core:symbols=tuple(s for s in symbols if s in DEFAULT_SYMBOLS) or DEFAULT_SYMBOLS
        risk=max(0.0,min(0.0025,_float(e,"RISK_PER_TRADE_PCT",0.0025)))
        total=max(risk,min(0.006,_float(e,"MAX_TOTAL_RISK_PCT",0.006)))
        return cls(
            mode=mode,symbols=symbols,structural_timeframes=_tf_chain("15m",e) if core else _tf_chain(str(e.get("EXECUTION_TIMEFRAME","15m")),e),
            execution_timeframe="15m" if core else str(e.get("EXECUTION_TIMEFRAME","15m")).lower(),
            decision_timeframe="1h" if core else str(e.get("DECISION_TIMEFRAME","1h")).lower(),
            macro_timeframe="1d",context_timeframe="4h",micro_timeframe="5m",airbag_timeframe="1d",
            allow_long=_bool(e,"ALLOW_LONG",True),allow_short=False if core else _bool(e,"ALLOW_SHORT",False),
            require_htf_confirmation=False if core else _bool(e,"REQUIRE_HTF_CONFIRMATION",True),
            h4_adverse_blocks=_bool(e,"H4_ADVERSE_BLOCKS",False),no_trade_when_uncertain=_bool(e,"NO_TRADE_WHEN_UNCERTAIN",True),
            risk_per_trade_pct=risk,max_total_risk_pct=total,max_daily_loss_pct=max(0.0,min(0.01,_float(e,"MAX_DAILY_LOSS_PCT",0.01))),
            max_consecutive_losses=max(0,_int(e,"MAX_CONSECUTIVE_LOSSES",2)),max_full_stop_outs=max(0,_int(e,"MAX_FULL_STOP_OUTS",2)),cooldown_minutes=max(0,_int(e,"COOLDOWN_MINUTES",30)),max_open_positions=1 if core else max(0,_int(e,"MAX_OPEN_POSITIONS",5)),
            leverage=1.0,averaging_down=False,fixed_take_profit=False,eod_flat=True,
            session_start_utc=str(e.get("TRADING_SESSION_START_UTC","08:00")),no_new_entries_utc=str(e.get("NO_NEW_ENTRIES_UTC","18:00")),mandatory_flat_utc=str(e.get("MANDATORY_FLAT_UTC","20:00")),
            min_risk_reward=0.0 if core else max(0.0,_float(e,"MIN_RISK_REWARD",1.5)),atr_period=max(2,_int(e,"ATR_PERIOD",14)),max_atr_pct=max(0.0,_float(e,"MAX_ATR_PCT",0.08)),max_spread_pct=max(0.0,_float(e,"MAX_SPREAD_PCT",0.0015)),max_l2_slippage_pct=max(0.0,_float(e,"MAX_L2_SLIPPAGE_PCT",0.0015)),
            wm1_outside_atr_mult=max(0.0,_float(e,"WM1_OUTSIDE_ATR_MULT",0.10)),wm1_angulation_window=max(3,_int(e,"WM1_ANGULATION_WINDOW",5)),wm3_first_allowed=_bool(e,"WM3_FIRST_ALLOWED",True),wave_can_invalidate=False,quant_can_invalidate=False,
            dry_run=_bool(e,"DRY_RUN",True),allow_live=_bool(e,"ALLOW_LIVE",False),
        )

    def validate(self)->None:
        if self.mode=="INTRADAY_CORE":
            expected=("1d","4h","1h","15m","5m")
            if self.structural_timeframes!=expected: raise ValueError("production timeframe contract mismatch")
            if self.execution_timeframe!="15m" or self.decision_timeframe!="1h": raise ValueError("production decision/execution TF mismatch")
            if self.max_open_positions!=1 or self.allow_short or self.leverage!=1.0 or self.averaging_down or self.fixed_take_profit: raise ValueError("production Spot safety profile violated")
            if self.risk_per_trade_pct>0.0025 or self.max_total_risk_pct>0.006 or self.max_daily_loss_pct>0.01: raise ValueError("risk exceeds small-deposit production envelope")

CONFIG=TradingConfig.from_env()
if CONFIG.mode=="INTRADAY_CORE": CONFIG.validate()
