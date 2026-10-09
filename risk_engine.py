import math
from dataclasses import dataclass


@dataclass
class RiskAnalysis:
    symbol: str
    side: str
    entry_price: float
    stop_price: float
    take_profit_price: float
    stop_distance_pct: float
    take_profit_pct: float
    risk_reward: float
    risk_quote: float
    position_quote: float
    position_fraction: float
    score: float
    allowed: bool
    reason: str

    @property
    def risk_pct(self):
        return getattr(self, "_risk_pct", 0.0)

    def to_dict(self):
        return {
            "symbol": self.symbol,
            "side": self.side,
            "entry_price": self.entry_price,
            "stop_price": self.stop_price,
            "take_profit_price": self.take_profit_price,
            "stop_distance_pct": self.stop_distance_pct,
            "take_profit_pct": self.take_profit_pct,
            "risk_reward": self.risk_reward,
            "risk_quote": self.risk_quote,
            "position_quote": self.position_quote,
            "position_fraction": self.position_fraction,
            "score": self.score,
            "allowed": self.allowed,
            "reason": self.reason,
        }


class RiskEngine:
    """Pure position-risk calculation; this class never places orders."""

    def __init__(
        self,
        balance_quote: float,
        risk_per_trade_pct: float = 0.005,
        max_position_fraction: float = 0.25,
        max_daily_loss_pct: float = 0.03,
        min_rr: float = 1.5,
        max_atr_pct: float = 0.08,
        fee_buffer_per_side_pct: float = 0.001,
        slippage_buffer_pct: float = 0.0015,
    ):
        names = (
            "balance_quote", "risk_per_trade_pct", "max_position_fraction",
            "max_daily_loss_pct", "min_rr", "max_atr_pct",
            "fee_buffer_per_side_pct", "slippage_buffer_pct",
        )
        raw = (
            balance_quote, risk_per_trade_pct, max_position_fraction,
            max_daily_loss_pct, min_rr, max_atr_pct,
            fee_buffer_per_side_pct, slippage_buffer_pct,
        )
        try:
            values = {name: float(value) for name, value in zip(names, raw)}
        except (TypeError, ValueError, OverflowError) as exc:
            raise ValueError("RiskEngine configuration must be numeric and finite") from exc
        if not all(math.isfinite(value) for value in values.values()):
            raise ValueError("RiskEngine configuration must be numeric and finite")
        if values["balance_quote"] <= 0:
            raise ValueError("balance_quote must be positive")
        for name in ("risk_per_trade_pct", "max_position_fraction", "max_daily_loss_pct"):
            if not 0.0 <= values[name] <= 1.0:
                raise ValueError(f"{name} must be in [0, 1]")
        if values["min_rr"] <= 0 or values["max_atr_pct"] <= 0:
            raise ValueError("min_rr and max_atr_pct must be positive")
        if values["fee_buffer_per_side_pct"] < 0 or values["slippage_buffer_pct"] < 0:
            raise ValueError("fee and slippage buffers cannot be negative")
        self.balance = values["balance_quote"]
        self.risk_per_trade_pct = values["risk_per_trade_pct"]
        self.max_position_fraction = values["max_position_fraction"]
        self.max_daily_loss_pct = values["max_daily_loss_pct"]
        self.min_rr = values["min_rr"]
        self.max_atr_pct = values["max_atr_pct"]
        self.fee_buffer_per_side_pct = values["fee_buffer_per_side_pct"]
        self.slippage_buffer_pct = values["slippage_buffer_pct"]

    @staticmethod
    def _clamp(value, low, high):
        return max(low, min(high, value))

    def analyse(
        self,
        symbol: str,
        entry_price: float,
        atr: float,
        signal_strength: float = 1.0,
        htf_confirmed: bool = True,
        spread_pct: float = 0.0,
        max_spread_pct: float = 0.0015,
        stop_atr_multiplier: float = 2.0,
        target_atr_multiplier: float = 4.0,
        risk_pct_override: float = None,
        side: str = "LONG",
        invalidation_price: float = 0.0,
        min_notional: float = 0.0,
    ) -> RiskAnalysis:
        side = str(side or "LONG").upper()
        try:
            entry = float(entry_price)
            atr_value = float(atr)
            strength = float(signal_strength)
            spread = float(spread_pct)
            spread_limit = float(max_spread_pct)
            stop_mult = float(stop_atr_multiplier)
            target_mult = float(target_atr_multiplier)
            structural_stop = float(invalidation_price or 0.0)
            minimum_notional = float(min_notional)
            override = None if risk_pct_override is None else float(risk_pct_override)
        except (TypeError, ValueError, OverflowError):
            return self._blocked(symbol, side, 0.0, "non-numeric risk input")

        numeric = (
            entry, atr_value, strength, spread, spread_limit, stop_mult,
            target_mult, structural_stop, minimum_notional,
        ) + (() if override is None else (override,))
        if not all(math.isfinite(value) for value in numeric):
            return self._blocked(symbol, side, entry, "non-finite risk input")
        if side not in {"LONG", "SHORT"}:
            return self._blocked(symbol, side, entry, "unsupported side")
        if entry <= 0:
            return self._blocked(symbol, side, entry, "invalid entry price")
        if atr_value <= 0:
            return self._blocked(symbol, side, entry, "invalid ATR")
        if spread < 0 or spread_limit <= 0:
            return self._blocked(symbol, side, entry, "invalid spread input")
        if stop_mult <= 0 or target_mult <= 0:
            return self._blocked(symbol, side, entry, "invalid ATR multipliers")
        if minimum_notional < 0:
            return self._blocked(symbol, side, entry, "invalid minimum notional")

        atr_pct = atr_value / entry
        if not math.isfinite(atr_pct):
            return self._blocked(symbol, side, entry, "invalid ATR percentage")
        if atr_pct > self.max_atr_pct:
            return self._blocked(symbol, side, entry, "ATR exceeds maximum allowed volatility")
        if spread > spread_limit:
            return self._blocked(symbol, side, entry, "spread exceeds maximum allowed")

        fallback_stop_distance = atr_value * stop_mult
        target_distance = atr_value * target_mult
        if (
            not math.isfinite(fallback_stop_distance) or fallback_stop_distance <= 0
            or not math.isfinite(target_distance) or target_distance <= 0
        ):
            return self._blocked(symbol, side, entry, "invalid stop/target distance")

        if side == "LONG":
            stop_price = structural_stop if 0.0 < structural_stop < entry else entry - fallback_stop_distance
            take_profit_price = entry + target_distance
        else:
            stop_price = structural_stop if structural_stop > entry else entry + fallback_stop_distance
            take_profit_price = entry - target_distance

        if (
            not math.isfinite(stop_price) or not math.isfinite(take_profit_price)
            or stop_price <= 0 or take_profit_price <= 0
        ):
            return self._blocked(symbol, side, entry, "calculated stop/target price is invalid")

        stop_distance = abs(entry - stop_price)
        if not math.isfinite(stop_distance) or stop_distance <= 0:
            return self._blocked(symbol, side, entry, "invalid stop distance")
        stop_pct = stop_distance / entry
        target_pct = abs(take_profit_price - entry) / entry
        rr = abs(take_profit_price - entry) / stop_distance
        if not all(math.isfinite(value) for value in (stop_pct, target_pct, rr)):
            return self._blocked(symbol, side, entry, "non-finite stop/target metrics")
        if rr < self.min_rr:
            return self._blocked(symbol, side, entry, f"R:R {rr:.3f} below minimum {self.min_rr:.3f}")

        effective_risk_pct = self.risk_per_trade_pct if override is None else override
        if not math.isfinite(effective_risk_pct) or effective_risk_pct <= 0:
            return self._blocked(symbol, side, entry, "risk allocation is zero or invalid")
        if effective_risk_pct > self.risk_per_trade_pct:
            return self._blocked(symbol, side, entry, "risk override exceeds configured per-trade limit")

        risk_quote = self.balance * effective_risk_pct
        effective_loss_fraction = (
            stop_pct + (2.0 * self.fee_buffer_per_side_pct) + self.slippage_buffer_pct
        )
        if not math.isfinite(effective_loss_fraction) or effective_loss_fraction <= 0:
            return self._blocked(symbol, side, entry, "invalid effective loss fraction")
        risk_based_position = risk_quote / effective_loss_fraction
        max_position_quote = self.balance * self.max_position_fraction
        position_quote = min(risk_based_position, max_position_quote)
        if not math.isfinite(position_quote) or position_quote <= 0:
            return self._blocked(symbol, side, entry, "calculated position size is zero or invalid")
        if minimum_notional > 0 and position_quote < minimum_notional:
            return self._blocked(symbol, side, entry, f"position notional {position_quote:.8f} below Binance minimum {minimum_notional:.8f}")

        position_fraction = position_quote / self.balance
        signal_score = 30.0 * self._clamp(strength, 0.0, 1.0)
        rr_score = 25.0 * self._clamp(
            (rr - self.min_rr) / max(3.0 - self.min_rr, 0.0001), 0.0, 1.0
        )
        risk_efficiency = self._clamp(1.0 - (stop_pct / self.max_atr_pct), 0.0, 1.0)
        risk_score = 20.0 * risk_efficiency
        htf_score = 15.0 if htf_confirmed else 0.0
        spread_score = 5.0 * self._clamp(1.0 - (spread / max(spread_limit, 1e-9)), 0.0, 1.0)
        atr_quality = self._clamp(1.0 - (atr_pct / self.max_atr_pct), 0.0, 1.0)
        score = self._clamp(
            signal_score + rr_score + risk_score + htf_score + spread_score + 5.0 * atr_quality,
            0.0,
            100.0,
        )
        result = RiskAnalysis(
            symbol=str(symbol).upper(),
            side=side,
            entry_price=entry,
            stop_price=stop_price,
            take_profit_price=take_profit_price,
            stop_distance_pct=round(stop_pct * 100.0, 4),
            take_profit_pct=round(target_pct * 100.0, 4),
            risk_reward=round(rr, 4),
            risk_quote=round(risk_quote, 8),
            position_quote=round(position_quote, 8),
            position_fraction=round(position_fraction, 6),
            score=round(score, 2),
            allowed=True,
            reason="risk checks passed",
        )
        result._risk_pct = effective_risk_pct * 100.0
        return result

    def _blocked(self, symbol, side, entry, reason):
        try:
            safe_entry = float(entry)
        except (TypeError, ValueError, OverflowError):
            safe_entry = 0.0
        if not math.isfinite(safe_entry):
            safe_entry = 0.0
        return RiskAnalysis(
            symbol=str(symbol or "").upper(),
            side=str(side or "").upper(),
            entry_price=safe_entry,
            stop_price=0.0,
            take_profit_price=0.0,
            stop_distance_pct=0.0,
            take_profit_pct=0.0,
            risk_reward=0.0,
            risk_quote=0.0,
            position_quote=0.0,
            position_fraction=0.0,
            score=0.0,
            allowed=False,
            reason=reason,
        )
