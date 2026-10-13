"""Single source of truth for Williams trading configuration.

The live engine reads one frozen configuration object instead of allowing
Trader/RiskEngine/Scanner to silently drift apart.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Mapping, Sequence


DEFAULT_SYMBOLS = ("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT")
DEFAULT_FUTURES_SYMBOLS = ("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT")
DEFAULT_STRUCTURAL_TFS = ("1d", "4h", "1h", "15m")


def _bool(env: Mapping[str, str], key: str, default: bool) -> bool:
    value = str(env.get(key, str(default))).strip().lower()
    return value in {"1", "true", "yes", "on"}


def _float(env: Mapping[str, str], key: str, default: float) -> float:
    try:
        return float(env.get(key, str(default)))
    except (TypeError, ValueError):
        return default


def _int(env: Mapping[str, str], key: str, default: int) -> int:
    try:
        return int(env.get(key, str(default)))
    except (TypeError, ValueError):
        return default


def _csv(env: Mapping[str, str], key: str, default: Sequence[str]) -> tuple[str, ...]:
    raw = str(env.get(key, "")).strip()
    if not raw:
        return tuple(default)
    values = tuple(dict.fromkeys(x.strip().upper() for x in raw.split(",") if x.strip()))
    return values or tuple(default)


def _tf_chain(execution: str, env: Mapping[str, str]) -> tuple[str, ...]:
    """Return a normalized structural hierarchy appropriate to any execution TF."""
    explicit = str(env.get("STRUCTURAL_TIMEFRAMES", "")).strip()
    if explicit:
        values = tuple(dict.fromkeys(
            x.strip().lower() for x in explicit.split(",") if x.strip()
        ))
        if values:
            return values

    chains = {
        "1m": ("4h", "1h", "15m", "5m", "1m"),
        "3m": ("1d", "4h", "1h", "15m", "5m", "3m"),
        "5m": ("1d", "4h", "1h", "15m", "5m"),
        "15m": ("1d", "4h", "1h", "15m"),
        "30m": ("1d", "4h", "1h", "30m", "15m"),
        "1h": ("1d", "4h", "1h", "15m"),
        "2h": ("1w", "1d", "4h", "2h", "1h"),
        "4h": ("1w", "1d", "4h", "1h"),
        "6h": ("1w", "1d", "6h", "4h"),
        "12h": ("1w", "1d", "12h", "4h"),
        "1d": ("1w", "1d", "4h"),
        "3d": ("1w", "3d", "1d"),
        "1w": ("1M", "1w", "1d"),
        "1M": ("1M", "1w", "1d"),
    }
    return chains.get(str(execution).lower(), ("1d", "4h", "1h", str(execution).lower()))


@dataclass(frozen=True)
class TradingConfig:
    symbols: tuple[str, ...] = DEFAULT_FUTURES_SYMBOLS
    structural_timeframes: tuple[str, ...] = DEFAULT_STRUCTURAL_TFS
    execution_timeframe: str = "1h"
    market: str = "futures_usdt"
    futures_leverage: int = 2
    futures_margin_type: str = "ISOLATED"
    futures_force_one_way: bool = True

    allow_long: bool = True
    allow_short: bool = True
    require_htf_confirmation: bool = True
    no_trade_when_uncertain: bool = True

    risk_per_trade_pct: float = 0.0025
    initial_entry_risk_pct: float = 0.002
    campaign_risk_limit_pct: float = 0.006
    max_total_risk_pct: float = 0.006
    max_daily_loss_pct: float = 0.03
    max_consecutive_losses: int = 3
    cooldown_minutes: int = 30
    max_open_positions: int = 5

    min_risk_reward: float = 1.5
    atr_period: int = 14
    max_atr_pct: float = 0.08
    max_spread_pct: float = 0.0015
    max_l2_slippage_pct: float = 0.0015

    wave_min_bars: int = 140
    wave_lookback: int = 220
    probability_threshold: float = 0.60
    probability_margin_threshold: float = 0.15
    entropy_threshold: float = 0.80

    dry_run: bool = True
    allow_live: bool = False

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "TradingConfig":
        source = os.environ if env is None else env
        market = str(source.get("BINANCE_MARKET", "futures_usdt")).strip().lower()
        if market in {"futures", "usdm", "usd_m", "usd-m"}:
            market = "futures_usdt"
        elif market not in {"futures_usdt", "spot"}:
            market = "futures_usdt"
        default_symbols = DEFAULT_FUTURES_SYMBOLS if market == "futures_usdt" else DEFAULT_SYMBOLS
        symbols = _csv(source, "WILLIAMS_SYMBOLS", default_symbols)
        # ALL/AUTO/* is a compatibility alias for the approved market-specific
        # list, never permission to discover arbitrary contracts.
        if len(symbols) == 1 and symbols[0].upper() in {"ALL", "AUTO", "*"}:
            symbols = default_symbols

        raw_auto_symbols = str(source.get("AUTO_SCAN_SYMBOLS", "")).strip()
        if market == "futures_usdt":
            # The Futures scanner, history loader, risk engine and UI must agree
            # on one fixed ten-contract universe. Fail closed on configuration
            # drift instead of silently scanning a different set.
            if symbols != DEFAULT_FUTURES_SYMBOLS:
                raise ValueError(
                    "WILLIAMS_SYMBOLS must match the approved ten-symbol Futures universe"
                )
            if raw_auto_symbols:
                auto_symbols = _csv(source, "AUTO_SCAN_SYMBOLS", default_symbols)
                if len(auto_symbols) == 1 and auto_symbols[0].upper() in {"ALL", "AUTO", "*"}:
                    auto_symbols = default_symbols
                if auto_symbols != DEFAULT_FUTURES_SYMBOLS:
                    raise ValueError(
                        "AUTO_SCAN_SYMBOLS cannot override the fixed Futures universe"
                    )
            symbols = DEFAULT_FUTURES_SYMBOLS
        elif raw_auto_symbols:
            # Legacy Spot mode retains its own market-specific configuration.
            symbols = _csv(source, "AUTO_SCAN_SYMBOLS", symbols)

        max_positions = min(5, max(1, _int(source, "MAX_OPEN_POSITIONS", 5)))
        risk_key = "MAX_RISK_PER_TRADE_PCT" if "MAX_RISK_PER_TRADE_PCT" in source else "RISK_PER_TRADE_PCT"
        risk = max(0.0, min(0.0025, _float(source, risk_key, 0.0025)))
        total_risk = max(0.0, min(0.006, _float(source, "MAX_TOTAL_RISK_PCT", 0.006)))

        execution_timeframe = str(source.get("EXECUTION_TIMEFRAME", source.get("INTERVAL", "1h"))).strip().lower()
        structural_timeframes = _tf_chain(execution_timeframe, source)
        if market == "futures_usdt":
            if execution_timeframe != "1h":
                raise ValueError("EXECUTION_TIMEFRAME must be H1 for the canonical Williams campaign")
            if structural_timeframes != DEFAULT_STRUCTURAL_TFS:
                raise ValueError("STRUCTURAL_TIMEFRAMES must be D1,H4,H1,M15 for the canonical Williams campaign")
        futures_leverage = max(1, min(2, _int(source, "FUTURES_LEVERAGE", 2)))
        futures_margin_type = str(source.get("FUTURES_MARGIN_TYPE", "ISOLATED")).strip().upper()
        if market == "futures_usdt" and futures_margin_type != "ISOLATED":
            raise ValueError("FUTURES_MARGIN_TYPE must be ISOLATED")
        if futures_margin_type != "ISOLATED":
            futures_margin_type = "ISOLATED"
        futures_force_one_way = _bool(source, "FUTURES_FORCE_ONE_WAY", True)
        if market == "futures_usdt" and not futures_force_one_way:
            raise ValueError("FUTURES_FORCE_ONE_WAY must remain enabled")

        return cls(
            symbols=symbols,
            structural_timeframes=structural_timeframes,
            execution_timeframe=execution_timeframe,
            market=market,
            futures_leverage=futures_leverage,
            futures_margin_type=futures_margin_type,
            futures_force_one_way=futures_force_one_way,
            allow_long=_bool(source, "ALLOW_LONG", True),
            allow_short=_bool(source, "ALLOW_SHORT", True),
            require_htf_confirmation=_bool(source, "REQUIRE_HTF_CONFIRMATION", True),
            no_trade_when_uncertain=_bool(source, "NO_TRADE_WHEN_UNCERTAIN", True),
            risk_per_trade_pct=risk,
            max_total_risk_pct=max(total_risk, risk),
            max_daily_loss_pct=max(0.0, min(0.03, _float(source, "MAX_DAILY_LOSS_PCT", 0.03))),
            max_consecutive_losses=max(0, _int(source, "MAX_CONSECUTIVE_LOSSES", 3)),
            cooldown_minutes=max(0, _int(source, "COOLDOWN_MINUTES", 30)),
            max_open_positions=max_positions,
            min_risk_reward=max(0.0, _float(source, "MIN_RISK_REWARD", 1.5)),
            atr_period=max(2, _int(source, "ATR_PERIOD", 14)),
            max_atr_pct=max(0.0, _float(source, "MAX_ATR_PCT", 0.08)),
            max_spread_pct=max(0.0, _float(source, "MAX_SPREAD_PCT", 0.0015)),
            max_l2_slippage_pct=max(0.0, _float(source, "MAX_L2_SLIPPAGE_PCT", 0.0015)),
            wave_min_bars=max(140, _int(source, "WAVE_MIN_BARS", 140)),
            wave_lookback=max(140, _int(source, "WAVE_LOOKBACK", 220)),
            probability_threshold=max(0.0, min(1.0, _float(source, "WAVE_PROB_THRESHOLD", 0.60))),
            probability_margin_threshold=max(0.0, min(1.0, _float(source, "WAVE_MARGIN_THRESHOLD", 0.15))),
            entropy_threshold=max(0.0, _float(source, "WAVE_ENTROPY_THRESHOLD", 0.80)),
            dry_run=_bool(source, "DRY_RUN", True),
            allow_live=_bool(source, "ALLOW_LIVE", False),
        )
