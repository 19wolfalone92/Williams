"""Fail-closed portfolio-wide daily-loss circuit breaker."""
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

        def parse_baseline(value) -> float:
            try:
                baseline_value = float(value)
            except (TypeError, ValueError, OverflowError) as exc:
                raise ValueError("stored daily start equity is invalid") from exc
            if not math.isfinite(baseline_value) or baseline_value <= 0:
                raise ValueError("stored daily start equity is invalid")
            return baseline_value

        stored_day = db.state_get("daily_risk_day")
        stored_equity = db.state_get("daily_start_equity")
        if stored_day == today and stored_equity is None:
            raise ValueError("daily start equity missing for an already initialized UTC day")
        if stored_day == today:
            return parse_baseline(stored_equity)

        # Re-read under a write lock: two workers starting at UTC rollover
        # must not race and overwrite the first valid baseline.
        transaction = getattr(db, "transaction", None)
        if callable(transaction):
            with transaction(immediate=True):
                latest_day = db.state_get("daily_risk_day")
                latest_equity = db.state_get("daily_start_equity")
                if latest_day == today:
                    if latest_equity is None:
                        raise ValueError("daily start equity missing for an already initialized UTC day")
                    return parse_baseline(latest_equity)
                baseline = float(current_equity_quote)
                if not math.isfinite(baseline) or baseline <= 0:
                    raise ValueError("cannot initialize daily baseline from invalid equity")
                db.state_set("daily_risk_day", today)
                db.state_set("daily_start_equity", repr(baseline))
                return baseline

        # Generic adapters without transactions are accepted only for the
        # single-writer path. They cannot provide cross-worker atomicity.
        baseline = float(current_equity_quote)
        if not math.isfinite(baseline) or baseline <= 0:
            raise ValueError("cannot initialize daily baseline from invalid equity")
        db.state_set("daily_risk_day", today)
        db.state_set("daily_start_equity", repr(baseline))
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
            fees = abs(float(fees_quote))
        except (TypeError, ValueError, OverflowError):
            return False, "invalid daily-risk input; new entries blocked"

        if not all(math.isfinite(v) for v in (equity, unrealized, fees)):
            return False, "non-finite daily-risk input; new entries blocked"
        if equity <= 0:
            return False, "equity is zero or negative; new entries blocked"

        try:
            pnl_reader = getattr(db, "pnl_today", None) if symbol is not None else getattr(db, "pnl_today_all", None)
            if not callable(pnl_reader):
                raise RuntimeError("required realized-PnL query is unavailable")
            realized_raw = pnl_reader(symbol) if symbol is not None else pnl_reader()
            realized = float(realized_raw)
        except Exception as exc:
            return False, (
                "realized PnL unavailable; new entries blocked "
                f"({type(exc).__name__})"
            )

        if not math.isfinite(realized):
            return False, "non-finite realized PnL; new entries blocked"

        # Current equity already includes realized PnL, open-position marks and
        # fees through the account balances. Summing realized + lifetime
        # unrealized PnL here double-counts closed trades and misstates positions
        # carried over from previous days. Use equity delta as the primary daily
        # PnL source. A separate realized-loss guard protects a late process
        # start/restart when the day's equity baseline is initialized afterward.
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
        equity_delta = equity - start_equity
        if not math.isfinite(equity_delta):
            return False, "non-finite daily equity change; new entries blocked"
        if equity_delta <= -loss_limit:
            return False, (
                "daily equity loss circuit breaker tripped: "
                f"equity_delta={equity_delta:.8f}, limit={-loss_limit:.8f}"
            )
        if realized <= -loss_limit:
            return False, (
                "daily realized-loss circuit breaker tripped: "
                f"realized_pnl={realized:.8f}, limit={-loss_limit:.8f}"
            )
        return True, "ok"
