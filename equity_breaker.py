"""Backend equity/daily-loss circuit breaker with realized + unrealized PnL."""
from __future__ import annotations

import math


class EquityCircuitBreaker:
    def __init__(self, max_daily_loss_pct: float = 0.03):
        self.max_daily_loss_pct = max(0.0, float(max_daily_loss_pct))

    def _today_start_equity(self, db, current_equity_quote: float) -> float:
        from datetime import datetime, timezone
        today = datetime.now(timezone.utc).date().isoformat()
        stored_day = db.state_get("daily_risk_day")
        stored_equity = db.state_get("daily_start_equity")
        if stored_day != today or stored_equity is None:
            db.state_set("daily_risk_day", today)
            db.state_set("daily_start_equity", str(max(0.0, float(current_equity_quote))))
            return max(0.0, float(current_equity_quote))
        return max(0.0, float(stored_equity))

    def check(
        self,
        db,
        current_equity_quote: float,
        symbol: str | None = None,
        unrealized_pnl_quote: float = 0.0,
        fees_quote: float = 0.0,
    ) -> tuple[bool, str]:
        try:
            equity = float(current_equity_quote)
            unrealized = float(unrealized_pnl_quote)
            fees = float(fees_quote)
        except (TypeError, ValueError, OverflowError):
            return False, "invalid daily-risk input; new entries blocked"

        if not all(math.isfinite(value) for value in (equity, unrealized, fees)):
            return False, "non-finite daily-risk input; new entries blocked"
        if equity <= 0:
            return False, "equity is zero"
        if fees < 0:
            return False, "fees must be non-negative"

        try:
            realized = float(
                db.pnl_today(symbol) if symbol is not None
                else db.pnl_today_all()
            )
        except AttributeError:
            realized = float(
                db.pnl_today(symbol) if symbol is not None else 0.0
            )

        if not math.isfinite(realized):
            return False, "non-finite realized PnL; new entries blocked"

        # Fees are a real loss and must be included even if legacy trade PnL
        # records were written before fee accounting was enabled.
        net_daily_pnl = realized + unrealized - fees
        if not math.isfinite(net_daily_pnl):
            return False, "non-finite daily PnL; new entries blocked"
        try:
            start_equity = self._today_start_equity(db, equity)
        except (TypeError, ValueError, OverflowError):
            return False, "daily start equity unavailable; new entries blocked"
        if not math.isfinite(start_equity) or start_equity <= 0:
            return False, "daily start equity invalid; new entries blocked"
        loss_limit = start_equity * self.max_daily_loss_pct

        if net_daily_pnl <= -loss_limit:
            return False, (
                "daily equity loss circuit breaker tripped: "
                f"net_pnl={net_daily_pnl:.8f}, limit={-loss_limit:.8f}"
            )

        return True, "ok"
