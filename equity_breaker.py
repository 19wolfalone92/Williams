"""Backend equity/daily-loss circuit breaker."""
from __future__ import annotations


class EquityCircuitBreaker:
    def __init__(self, max_daily_loss_pct: float = 0.03):
        self.max_daily_loss_pct = max(0.0, float(max_daily_loss_pct))

    def check(self, db, current_equity_quote: float) -> tuple[bool, str]:
        equity = max(0.0, float(current_equity_quote))
        pnl = float(db.pnl_today() or 0.0)
        if equity <= 0:
            return False, "equity is zero"
        if pnl <= -equity * self.max_daily_loss_pct:
            return False, "daily equity loss circuit breaker tripped"
        return True, "ok"
