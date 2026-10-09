"""Fail-closed daily equity-loss circuit breaker.

PnL sources are read from the durable database. Missing or malformed inputs
must never be interpreted as zero PnL because that can bypass the daily limit.
"""
from __future__ import annotations

import math


class EquityCircuitBreaker:
    def __init__(self, max_daily_loss_pct: float = 0.03):
        try:
            limit = float(max_daily_loss_pct)
        except (TypeError, ValueError, OverflowError) as exc:
            raise ValueError("max_daily_loss_pct must be finite and in [0, 1]") from exc
        if not math.isfinite(limit) or not 0.0 <= limit <= 1.0:
            raise ValueError("max_daily_loss_pct must be finite and in [0, 1]")
        self.max_daily_loss_pct = limit

    def _today_start_equity(self, db, current_equity_quote: float) -> float:
        from datetime import datetime, timezone

        today = datetime.now(timezone.utc).date().isoformat()
        stored_day = db.state_get("daily_risk_day")
        stored_equity = db.state_get("daily_start_equity")
        if stored_day != today or stored_equity is None:
            # This establishes a baseline only when no valid baseline exists.
            # Deployments must initialize it at UTC day start to account for
            # losses incurred before a process restart.
            db.state_set("daily_risk_day", today)
            db.state_set("daily_start_equity", repr(current_equity_quote))
            return current_equity_quote
        try:
            baseline = float(stored_equity)
        except (TypeError, ValueError, OverflowError) as exc:
            raise ValueError("stored daily start equity is invalid") from exc
        if not math.isfinite(baseline) or baseline <= 0:
            raise ValueError("stored daily start equity is invalid")
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
            return False, "invalid daily-risk input; new entries blocked"

        if not all(math.isfinite(value) for value in (equity, unrealized, fees)):
            return False, "non-finite daily-risk input; new entries blocked"
        if equity <= 0:
            return False, "equity is zero or negative; new entries blocked"

        # Accept either caller sign convention; fees are always a cost.
        fees = abs(fees)
        try:
            if symbol is not None:
                realized_raw = db.pnl_today(symbol)
            else:
                realized_raw = db.pnl_today_all()
            realized = float(realized_raw)
        except Exception as exc:
            return False, (
                "realized PnL unavailable; new entries blocked "
                f"({type(exc).__name__})"
            )

        if not math.isfinite(realized):
            return False, "non-finite realized PnL; new entries blocked"

        net_daily_pnl = realized + unrealized - fees
        if not math.isfinite(net_daily_pnl):
            return False, "non-finite daily PnL; new entries blocked"

        try:
            start_equity = self._today_start_equity(db, equity)
        except Exception as exc:
            return False, (
                "daily start equity unavailable; new entries blocked "
                f"({type(exc).__name__})"
            )
        if not math.isfinite(start_equity) or start_equity <= 0:
            return False, "daily start equity invalid; new entries blocked"

        loss_limit = start_equity * self.max_daily_loss_pct
        if not math.isfinite(loss_limit):
            return False, "daily loss limit invalid; new entries blocked"
        if net_daily_pnl <= -loss_limit:
            return False, (
                "daily equity loss circuit breaker tripped: "
                f"net_pnl={net_daily_pnl:.8f}, limit={-loss_limit:.8f}"
            )
        return True, "ok"
