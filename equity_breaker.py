"""Backend equity/daily-loss circuit breaker with a durable UTC-day latch."""
from __future__ import annotations


class EquityCircuitBreaker:
    def __init__(self, max_daily_loss_pct: float = 0.03):
        self.max_daily_loss_pct = max(0.0, float(max_daily_loss_pct))

    def _today_start_equity(self, db, current_equity_quote: float) -> float:
        from datetime import datetime, timezone

        today = datetime.now(timezone.utc).date().isoformat()
        stored_day = db.state_get("daily_risk_day")
        stored_equity = db.state_get("daily_start_equity")
        if stored_day != today or stored_equity is None:
            equity = float(current_equity_quote)
            if not (equity > 0.0 and equity < float("inf")):
                raise ValueError("cannot establish daily baseline from invalid equity")
            # Persist day, baseline and cleared latch as one fail-closed contract.
            db.state_set("daily_risk_day", today)
            db.state_set("daily_start_equity", str(equity))
            db.state_set("daily_risk_tripped", "0")
            return equity
        equity = float(stored_equity)
        if not (equity > 0.0 and equity < float("inf")):
            raise ValueError("persisted daily start equity is invalid")
        return equity

    @staticmethod
    def _is_latched(value) -> bool:
        return str(value or "").strip().lower() in {"1", "true", "yes", "tripped"}

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
            if not (equity > 0.0 and equity < float("inf")):
                return False, "equity is zero or invalid"
            start_equity = self._today_start_equity(db, equity)
        except Exception as exc:
            return False, f"daily risk baseline persistence/validation failed: {exc}"

        # A trip remains latched for the UTC day even if unrealized PnL later
        # recovers or the process restarts.
        try:
            if self._is_latched(db.state_get("daily_risk_tripped")):
                return False, "daily equity loss circuit breaker already tripped for UTC day"
        except Exception as exc:
            return False, f"daily risk latch read failed: {exc}"

        try:
            realized = float(
                db.pnl_today(symbol) if symbol is not None
                else db.pnl_today_all()
            )
        except AttributeError:
            realized = float(
                db.pnl_today(symbol) if symbol is not None else 0.0
            )
        except Exception as exc:
            return False, f"daily PnL unavailable: {exc}"

        # Fees are a real loss and must be included even if legacy trade PnL
        # records were written before fee accounting was enabled.
        net_daily_pnl = realized + float(unrealized_pnl_quote) - abs(float(fees_quote))
        loss_limit = start_equity * self.max_daily_loss_pct

        if net_daily_pnl <= -loss_limit:
            try:
                db.state_set("daily_risk_tripped", "1")
            except Exception as exc:
                return False, f"daily loss threshold reached but latch persistence failed: {exc}"
            return False, (
                "daily equity loss circuit breaker tripped: "
                f"net_pnl={net_daily_pnl:.8f}, limit={-loss_limit:.8f}"
            )

        return True, "ok"
