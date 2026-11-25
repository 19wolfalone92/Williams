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

    def check_model_b(
        self,
        db,
        current_equity_quote,
        start_equity_quote,
        net_external_flows_quote,
        *,
        complete=True,
        account_identity=None,
        expected_account_identity=None,
        today=None,
    ):
        """Apply the shared Model B contract; wallet equity already includes fees/funding."""
        from daily_risk_accounting import calculate_daily_risk_model_b, DailyRiskDataError

        if not complete:
            return False, "FUTURES_DAILY_BASELINE_INCOMPLETE"
        if (not account_identity or not expected_account_identity
                or str(account_identity) != str(expected_account_identity)):
            return False, "FUTURES_DAILY_BASELINE_ACCOUNT_IDENTITY_MISMATCH"
        try:
            result = calculate_daily_risk_model_b(
                start_equity_quote, current_equity_quote, net_external_flows_quote
            )
        except (DailyRiskDataError, TypeError, ValueError, OverflowError):
            return False, "FUTURES_DAILY_MODEL_B_INPUTS_INVALID"

        today = today or datetime.now(timezone.utc).date().isoformat()
        try:
            with db.transaction():
                latched_day = db.state_get("daily_equity_loss_latched_day")
                latched_model = db.state_get("daily_equity_loss_latched_model")
                # A current equity recovery is not proof that the earlier
                # intraday breach never occurred. Never clear a same-day or
                # future-dated latch during migration.
                if latched_day and latched_day >= today:
                    return False, "daily Model B loss circuit breaker remains latched for this UTC day"
                db.state_set("daily_risk_model_version", "2")
                db.state_set("daily_risk_model_b_day", today)
                db.state_set("daily_risk_model_b_start_equity", str(result["start_equity"]))
                db.state_set("daily_risk_model_b_current_equity", str(result["current_equity"]))
                db.state_set("daily_risk_model_b_external_flows", str(result["net_external_flows"]))
                db.state_set("daily_risk_model_b_pnl", str(result["daily_pnl"]))
                db.state_set("daily_risk_model_b_loss_pct", str(result["loss_pct"]))
                if result["entry_blocked"]:
                    db.state_set("daily_equity_loss_latched_day", today)
                    db.state_set("daily_equity_loss_latched_model", "MODEL_B")
                    return False, (
                        "daily Model B equity loss circuit breaker tripped: "
                        f"pnl={result['daily_pnl']}, loss_pct={result['loss_pct']}"
                    )
        except Exception:
            return False, "FUTURES_DAILY_MODEL_B_PERSIST_FAILED"
        return True, "ok"

