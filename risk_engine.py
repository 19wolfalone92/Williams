from dataclasses import dataclass


@dataclass
class RiskAnalysis:
    symbol: str
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

    def to_dict(self):
        return {
            "symbol": self.symbol,
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
        risk_per_trade_pct: float = 0.01,
        max_position_fraction: float = 0.25,
        max_daily_loss_pct: float = 0.03,
        min_rr: float = 1.5,
        max_atr_pct: float = 0.08,
    ):
        self.balance = float(balance_quote)
        self.risk_per_trade_pct = float(risk_per_trade_pct)
        self.max_position_fraction = float(max_position_fraction)
        self.max_daily_loss_pct = float(max_daily_loss_pct)
        self.min_rr = float(min_rr)
        self.max_atr_pct = float(max_atr_pct)

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
    ) -> RiskAnalysis:

        entry = float(entry_price)
        atr = float(atr)

        if entry <= 0:
            return self._blocked(
                symbol,
                entry,
                "invalid entry price",
            )

        if atr <= 0:
            return self._blocked(
                symbol,
                entry,
                "invalid ATR",
            )

        atr_pct = atr / entry

        if atr_pct > self.max_atr_pct:
            return self._blocked(
                symbol,
                entry,
                "ATR exceeds maximum allowed volatility",
            )

        if spread_pct > max_spread_pct:
            return self._blocked(
                symbol,
                entry,
                "spread exceeds maximum allowed",
            )

        stop_distance = atr * stop_atr_multiplier
        target_distance = atr * target_atr_multiplier

        if stop_distance <= 0:
            return self._blocked(
                symbol,
                entry,
                "invalid stop distance",
            )

        stop_price = entry - stop_distance
        take_profit_price = entry + target_distance

        if stop_price <= 0:
            return self._blocked(
                symbol,
                entry,
                "calculated stop price is invalid",
            )

        stop_pct = stop_distance / entry
        target_pct = target_distance / entry

        rr = target_distance / stop_distance

        if rr < self.min_rr:
            return self._blocked(
                symbol,
                entry,
                f"R:R {rr:.3f} below minimum {self.min_rr:.3f}",
            )

        # Maximum money we are allowed to lose on this trade.
        risk_quote = self.balance * self.risk_per_trade_pct

        # Position size based on actual stop distance.
        risk_based_position = risk_quote / stop_pct

        # Hard portfolio exposure cap.
        max_position_quote = self.balance * self.max_position_fraction

        position_quote = min(
            risk_based_position,
            max_position_quote,
        )

        if position_quote <= 0:
            return self._blocked(
                symbol,
                entry,
                "calculated position size is zero",
            )

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

        return RiskAnalysis(
            symbol=symbol.upper(),
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

    def _blocked(self, symbol, entry, reason):
        return RiskAnalysis(
            symbol=symbol.upper(),
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
