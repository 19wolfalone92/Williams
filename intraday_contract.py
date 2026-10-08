"""Canonical Williams Intraday Small-Deposit contract.

This module is policy/configuration only.  It does not decide whether a
Williams formation is true; it defines the operating envelope around the
canonical H1 Williams Core.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Mapping


@dataclass(frozen=True)
class WilliamsIntradayContract:
    macro_tf: str = "1d"
    context_tf: str = "4h"
    decision_tf: str = "1h"
    execution_tf: str = "15m"
    micro_tf: str = "5m"
    airbag_tf: str = "1d"

    symbols: tuple[str, ...] = ("BTCUSDT", "ETHUSDT")
    long_only_spot: bool = True
    leverage: int = 0

    initial_risk_pct: float = 0.0025
    max_campaign_risk_pct: float = 0.006
    max_daily_loss_pct: float = 0.01
    max_open_campaigns: int = 1
    max_full_stop_outs: int = 2

    reverse_pyramid_weights: tuple[int, ...] = (1, 5, 4, 3, 2)
    averaging_down: bool = False
    fixed_take_profit: bool = False

    session_start_utc: int = 8
    no_new_entries_utc: int = 18
    mandatory_flat_utc: int = 20

    structural_trail_bars_min: int = 3
    structural_trail_bars_max: int = 5
    wm1_enabled: bool = True
    wm2_enabled: bool = True
    wm3_enabled: bool = True

    def validate(self) -> None:
        if self.initial_risk_pct <= 0:
            raise ValueError("initial_risk_pct must be positive")
        if self.max_campaign_risk_pct < self.initial_risk_pct:
            raise ValueError("campaign risk must cover initial risk")
        if self.max_daily_loss_pct < self.max_campaign_risk_pct:
            raise ValueError("daily loss limit must cover campaign risk")
        if self.max_open_campaigns != 1:
            raise ValueError("small-deposit contract requires one active campaign")
        if self.execution_tf != "15m" or self.decision_tf != "1h" or self.micro_tf != "5m":
            raise ValueError("canonical intraday contract requires H1 -> M15 -> M5")
        if self.long_only_spot and self.leverage != 0:
            raise ValueError("spot long-only contract requires leverage=0")
        if self.reverse_pyramid_weights != (1, 5, 4, 3, 2):
            raise ValueError("reverse pyramid weights are canonical 1:5:4:3:2")
        if self.structural_trail_bars_min > self.structural_trail_bars_max:
            raise ValueError("invalid structural trail window")

    @classmethod
    def from_env(cls, env: Mapping[str, str]) -> "WilliamsIntradayContract":
        def f(k, d):
            try:
                return float(env.get(k, d))
            except (TypeError, ValueError):
                return float(d)

        def i(k, d):
            try:
                return int(env.get(k, d))
            except (TypeError, ValueError):
                return int(d)

        symbols = tuple(
            dict.fromkeys(
                x.strip().upper()
                for x in str(env.get("WILLIAMS_INTRADAY_SYMBOLS", "BTCUSDT,ETHUSDT")).split(",")
                if x.strip()
            )
        ) or ("BTCUSDT", "ETHUSDT")

        c = cls(
            symbols=symbols,
            initial_risk_pct=f("WILLIAMS_INITIAL_RISK_PCT", 0.0025),
            max_campaign_risk_pct=f("WILLIAMS_MAX_CAMPAIGN_RISK_PCT", 0.006),
            max_daily_loss_pct=f("WILLIAMS_MAX_DAILY_LOSS_PCT", 0.01),
            max_open_campaigns=i("WILLIAMS_MAX_OPEN_CAMPAIGNS", 1),
            max_full_stop_outs=i("WILLIAMS_MAX_FULL_STOP_OUTS", 2),
            session_start_utc=i("WILLIAMS_SESSION_START_UTC", 8),
            no_new_entries_utc=i("WILLIAMS_NO_NEW_ENTRIES_UTC", 18),
            mandatory_flat_utc=i("WILLIAMS_MANDATORY_FLAT_UTC", 20),
        )
        c.validate()
        return c


@dataclass(frozen=True)
class SessionDecision:
    new_entries_allowed: bool
    pending_entries_allowed: bool
    force_flat: bool
    reason: str


def session_decision(now: datetime, contract: WilliamsIntradayContract) -> SessionDecision:
    """Apply the operational intraday window; Williams truth is unaffected."""
    t = now.astimezone(timezone.utc)
    hour = t.hour
    if hour < contract.session_start_utc:
        return SessionDecision(False, False, False, "OUTSIDE_SESSION_BEFORE_OPEN")
    if hour >= contract.mandatory_flat_utc:
        return SessionDecision(False, False, True, "END_OF_DAY_FORCE_FLAT")
    if hour >= contract.no_new_entries_utc:
        return SessionDecision(False, True, False, "NO_NEW_CAMPAIGNS")
    return SessionDecision(True, True, False, "SESSION_OPEN")


def position_size_from_structural_stop(
    *,
    equity_quote: float,
    entry_price: float,
    structural_stop: float,
    risk_pct: float,
) -> float:
    """Size from market structure; never compresses the structural stop."""
    if equity_quote <= 0 or entry_price <= 0 or structural_stop <= 0:
        return 0.0
    distance = entry_price - structural_stop
    if distance <= 0 or risk_pct <= 0:
        return 0.0
    return (equity_quote * risk_pct) / distance


def risk_quote_for_position(quantity: float, entry_price: float, stop_price: float) -> float:
    if quantity <= 0 or entry_price <= 0 or stop_price <= 0:
        return 0.0
    return max(0.0, quantity * (entry_price - stop_price))


def reverse_pyramid_weight(tranche_index: int) -> int:
    weights = (1, 5, 4, 3, 2)
    return weights[min(max(int(tranche_index), 0), len(weights) - 1)]
