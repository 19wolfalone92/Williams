"""Backend equity/daily-loss circuit breaker with realized + unrealized PnL."""
from __future__ import annotations
import math
from datetime import datetime, timezone


class EquityCircuitBreaker:
    def __init__(self, max_daily_loss_pct: float = 0.03):
        limit = float(max_daily_loss_pct)
        self.max_daily_loss_pct = max(0.0, min(0.03, limit)) if math.isfinite(limit) else 0.0

    def _today_start_equity(self, db, current_equity_quote: float, today=None) -> float:
        today = today or datetime.now(timezone.utc).date().isoformat()
        # A crash must not commit today's day key with yesterday's equity.
        with db.transaction():
            stored_day = db.state_get("daily_risk_day")
            stored_equity = db.state_get("daily_start_equity")
            if stored_day != today or stored_equity is None:
                db.state_set("daily_risk_day", today)
                db.state_set("daily_start_equity", str(current_equity_quote))
                return current_equity_quote
            return float(stored_equity)

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
            if not all(math.isfinite(v) for v in (equity, unrealized, fees)):
                return False, "daily risk inputs are not finite"
            if equity <= 0 or self.max_daily_loss_pct <= 0:
                return False, "equity or daily risk budget is zero"
            realized = float(
                db.pnl_today(symbol) if symbol is not None
                else db.pnl_today_all()
            )
        except (AttributeError, TypeError, ValueError, OverflowError):
            return False, "realized daily PnL cannot be verified"
        if not math.isfinite(realized):
            return False, "realized daily PnL is not finite"
        today = datetime.now(timezone.utc).date().isoformat()
        if db.state_get("daily_equity_loss_latched_day") == today:
            return False, "daily equity loss circuit breaker remains latched for this UTC day"

        # Fees are a real loss and must be included even if legacy trade PnL
        # records were written before fee accounting was enabled.
        net_daily_pnl = realized + unrealized - abs(fees)
        try:
            start_equity = self._today_start_equity(db, equity, today)
        except (TypeError, ValueError, OverflowError):
            return False, "persisted daily equity baseline is invalid"
        if not math.isfinite(start_equity) or start_equity <= 0:
            return False, "persisted daily equity baseline is invalid"
        loss_limit = start_equity * self.max_daily_loss_pct
        # Account drawdown also sees fees/funding absent from older local journals.
        effective_pnl = min(net_daily_pnl, equity - start_equity)
        if effective_pnl <= -loss_limit:
            db.state_set("daily_equity_loss_latched_day", today)
            return False, (
                "daily equity loss circuit breaker tripped: "
                f"net_pnl={net_daily_pnl:.8f}, limit={-loss_limit:.8f}"
            )

        return True, "ok"
