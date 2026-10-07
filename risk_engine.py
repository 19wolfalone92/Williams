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
    """
    Calculates pair-specific trade risk.

    This class DOES NOT place orders.
    """

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
        self.balance = float(balance_quote)
        self.risk_per_trade_pct = float(risk_per_trade_pct)
        self.max_position_fraction = float(max_position_fraction)
        self.max_daily_loss_pct = float(max_daily_loss_pct)
        self.min_rr = float(min_rr)
        self.max_atr_pct = float(max_atr_pct)
        self.fee_buffer_per_side_pct = max(0.0, float(fee_buffer_per_side_pct))
        self.slippage_buffer_pct = max(0.0, float(slippage_buffer_pct))

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

        entry = float(entry_price)
        atr = float(atr)
        side = str(side or "LONG").upper()

        if side not in {"LONG", "SHORT"}:
            return self._blocked(symbol, side, entry, "unsupported side")

        if entry <= 0:
            return self._blocked(symbol, side, entry, "invalid entry price")

        if atr <= 0:
            return self._blocked(symbol, side, entry, "invalid ATR")

        atr_pct = atr / entry

        if atr_pct > self.max_atr_pct:
            return self._blocked(symbol, side, entry, "ATR exceeds maximum allowed volatility")

        if spread_pct > max_spread_pct:
            return self._blocked(symbol, side, entry, "spread exceeds maximum allowed")

        fallback_stop_distance = atr * stop_atr_multiplier
        target_distance = atr * target_atr_multiplier

        if fallback_stop_distance <= 0:
            return self._blocked(symbol, side, entry, "invalid stop distance")

        structural_stop = float(invalidation_price or 0.0)
        if side == "LONG":
            stop_price = structural_stop if 0.0 < structural_stop < entry else entry - fallback_stop_distance
            take_profit_price = entry + target_distance
        else:
            stop_price = structural_stop if structural_stop > entry else entry + fallback_stop_distance
            take_profit_price = entry - target_distance

        if stop_price <= 0 or take_profit_price <= 0:
            return self._blocked(symbol, side, entry, "calculated stop price is invalid")

        stop_distance = abs(entry - stop_price)
        stop_pct = stop_distance / entry
        target_pct = abs(take_profit_price - entry) / entry

        rr = abs(take_profit_price - entry) / stop_distance

        if rr < self.min_rr:
            return self._blocked(symbol, side, entry, f"R:R {rr:.3f} below minimum {self.min_rr:.3f}")

        # Maximum money we are allowed to lose on this trade. Size against the
        # protective stop plus bounded fee/slippage reserve.
        effective_risk_pct = self.risk_per_trade_pct if risk_pct_override is None else float(risk_pct_override)
        if effective_risk_pct <= 0:
            return self._blocked(symbol, side, entry, "risk allocation is zero")
        risk_quote = self.balance * effective_risk_pct
        effective_loss_fraction = (
            stop_pct
            + (2.0 * self.fee_buffer_per_side_pct)
            + self.slippage_buffer_pct
        )

        # Position size based on stop + execution-cost reserve.
        risk_based_position = risk_quote / max(effective_loss_fraction, 1e-9)

        # Hard portfolio exposure cap.
        max_position_quote = self.balance * self.max_position_fraction

        position_quote = min(
            risk_based_position,
            max_position_quote,
        )

        if position_quote <= 0:
            return self._blocked(symbol, side, entry, "calculated position size is zero")
        if min_notional > 0 and position_quote < float(min_notional):
            return self._blocked(symbol, side, entry, f"position notional {position_quote:.8f} below Binance minimum {float(min_notional):.8f}")

        position_fraction = position_quote / self.balance

        # Score components.
        #
        # Signal strength: 30
        # R:R:             25
        # Risk efficiency: 20
        # HTF:             15
        # Spread:          5
        # ATR quality:     5

        signal_score = 30.0 * self._clamp(
            float(signal_strength),
            0.0,
            1.0,
        )

        rr_score = 25.0 * self._clamp(
            (rr - self.min_rr) / max(3.0 - self.min_rr, 0.0001),
            0.0,
            1.0,
        )

        risk_efficiency = self._clamp(
            1.0 - (stop_pct / self.max_atr_pct),
            0.0,
            1.0,
        )

        risk_score = 20.0 * risk_efficiency

        htf_score = 15.0 if htf_confirmed else 0.0

        spread_score = 5.0 * self._clamp(
            1.0 - (spread_pct / max(max_spread_pct, 1e-9)),
            0.0,
            1.0,
        )

        atr_quality = self._clamp(
            1.0 - (atr_pct / self.max_atr_pct),
            0.0,
            1.0,
        )

        atr_score = 5.0 * atr_quality

        score = (
            signal_score
            + rr_score
            + risk_score
            + htf_score
            + spread_score
            + atr_score
        )

        score = self._clamp(score, 0.0, 100.0)

        result = RiskAnalysis(
            symbol=symbol.upper(),
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
        return RiskAnalysis(
            symbol=symbol.upper(),
            side=str(side).upper(),
            entry_price=float(entry),
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
