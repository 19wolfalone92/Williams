"""Single source of truth for Williams trading configuration.

The live engine reads one frozen configuration object instead of allowing
Trader/RiskEngine/Scanner to silently drift apart.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Mapping, Sequence


DEFAULT_SYMBOLS = ("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT")
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


@dataclass(frozen=True)
class TradingConfig:
    symbols: tuple[str, ...] = DEFAULT_SYMBOLS
    structural_timeframes: tuple[str, ...] = DEFAULT_STRUCTURAL_TFS
    execution_timeframe: str = "5m"

    allow_long: bool = True
    allow_short: bool = False
    require_htf_confirmation: bool = True
    no_trade_when_uncertain: bool = True

    risk_per_trade_pct: float = 0.005
    max_total_risk_pct: float = 0.01
    max_daily_loss_pct: float = 0.03
    max_consecutive_losses: int = 3
    cooldown_minutes: int = 30
    max_open_positions: int = 0

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
        symbols = _csv(source, "WILLIAMS_SYMBOLS", DEFAULT_SYMBOLS)
        # AUTO_SCAN_SYMBOLS remains a backwards-compatible override.
        if str(source.get("AUTO_SCAN_SYMBOLS", "")).strip():
            symbols = _csv(source, "AUTO_SCAN_SYMBOLS", symbols)

        max_positions = max(0, _int(source, "MAX_OPEN_POSITIONS", 0))
        risk = max(0.0, min(0.01, _float(source, "RISK_PER_TRADE_PCT", 0.005)))
        total_risk = max(0.0, min(0.02, _float(source, "MAX_TOTAL_RISK_PCT", 0.01)))

        return cls(
            symbols=symbols,
            structural_timeframes=("1d", "4h", "1h", "15m"),
            execution_timeframe=str(source.get("EXECUTION_TIMEFRAME", "5m")).lower(),
            allow_long=_bool(source, "ALLOW_LONG", True),
            allow_short=_bool(source, "ALLOW_SHORT", False),
            require_htf_confirmation=_bool(source, "REQUIRE_HTF_CONFIRMATION", True),
            no_trade_when_uncertain=_bool(source, "NO_TRADE_WHEN_UNCERTAIN", True),
            risk_per_trade_pct=risk,
            max_total_risk_pct=max(total_risk, risk),
            max_daily_loss_pct=max(0.0, _float(source, "MAX_DAILY_LOSS_PCT", 0.03)),
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
