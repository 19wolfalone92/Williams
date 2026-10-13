"""Backend equity/daily-loss circuit breaker with realized + unrealized PnL."""
from __future__ import annotations

import math


class EquityCircuitBreaker:
    def __init__(self, max_daily_loss_pct: float = 0.03):
        pct = float(max_daily_loss_pct)
        if not math.isfinite(pct) or not 0.0 <= pct <= 1.0:
            raise ValueError("max_daily_loss_pct must be finite and in [0, 1]")
        # The project-wide hard cap is 3%; callers may tighten it but
        # configuration must never widen the loss boundary.
        self.max_daily_loss_pct = min(0.03, pct)

    def _today_start_equity(self, db, current_equity_quote: float) -> float:
        from datetime import datetime, timezone
        today = datetime.now(timezone.utc).date().isoformat()
        stored_day = db.state_get("daily_risk_day")
        stored_equity = db.state_get("daily_start_equity")
        if stored_day != today or stored_equity is None:
            baseline = float(current_equity_quote)
            if not math.isfinite(baseline) or baseline <= 0.0:
                raise ValueError("cannot establish a valid daily equity baseline")
            # Persist day and equity before allowing the first entry of the day.
            # Write the baseline first. If the process dies between writes,
            # the old day key forces a safe re-initialization on the next check.
            db.state_set("daily_start_equity", str(baseline))
            db.state_set("daily_risk_day", today)
            return baseline
        baseline = float(stored_equity)
        if not math.isfinite(baseline) or baseline <= 0.0:
            raise ValueError("persisted daily equity baseline is invalid")
        return baseline

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
            return False, "daily risk inputs are invalid"

        if not all(math.isfinite(v) for v in (equity, unrealized, fees)):
            return False, "daily risk inputs are non-finite"
        if equity <= 0.0:
            return False, "equity is zero or negative"

        try:
            pnl_reader = getattr(db, "pnl_today", None) if symbol is not None else getattr(db, "pnl_today_all", None)
            if not callable(pnl_reader):
                return False, "daily realized PnL unavailable; entry blocked"
            realized = float(pnl_reader(symbol) if symbol is not None else pnl_reader())
        except Exception as exc:
            return False, f"daily realized PnL unavailable ({type(exc).__name__}); entry blocked"

        if not math.isfinite(realized):
            return False, "daily realized PnL is non-finite; entry blocked"

        try:
            start_equity = self._today_start_equity(db, equity)
        except Exception as exc:
            return False, f"daily equity baseline unavailable ({type(exc).__name__}); entry blocked"

        net_daily_pnl = realized + unrealized - abs(fees)
        if not math.isfinite(net_daily_pnl):
            return False, "daily net PnL is non-finite; entry blocked"

        loss_limit = start_equity * self.max_daily_loss_pct
        if net_daily_pnl <= -loss_limit:
            return False, (
                "daily equity loss circuit breaker tripped: "
                f"net_pnl={net_daily_pnl:.8f}, limit={-loss_limit:.8f}"
            )

        return True, "ok"
