"""Fail-closed daily equity-loss gate.

The durable UTC baseline and the latest known equity survive process restarts.
A missing baseline can be bootstrapped only for a genuinely empty database;
otherwise entry is blocked until the baseline is reconciled.
"""
from __future__ import annotations

import math
from datetime import datetime, timezone, timedelta


class EquityCircuitBreaker:
    def __init__(self, max_daily_loss_pct: float = 0.01):
        try:
            limit = float(max_daily_loss_pct)
        except (TypeError, ValueError, OverflowError) as exc:
            raise ValueError("max_daily_loss_pct must be finite and in [0, 1%]") from exc
        if not math.isfinite(limit) or not 0.0 <= limit <= 0.01:
            raise ValueError("max_daily_loss_pct must be finite and in [0, 1%]")
        self.max_daily_loss_pct = limit

    @staticmethod
    def _parse_positive(value, label: str) -> float:
        try:
            result = float(value)
        except (TypeError, ValueError, OverflowError) as exc:
            raise ValueError(f"{label} is invalid") from exc
        if not math.isfinite(result) or result <= 0:
            raise ValueError(f"{label} is invalid")
        return result

    @staticmethod
    def _database_has_risk_history(db) -> bool:
        # A real Database has a connection. Any failure to inspect its history is
        # treated as unknown exposure, not as an empty installation.
        conn = getattr(db, "conn", None)
        if conn is None:
            # Lightweight test doubles represent a known empty database unless
            # they explicitly provide history.
            return bool(getattr(db, "has_risk_history", False))
        try:
            trades = int(conn.execute("SELECT COUNT(*) FROM trades").fetchone()[0])
            open_campaigns = int(conn.execute(
                "SELECT COUNT(*) FROM campaigns WHERE state NOT IN ('CLOSED','FLAT')"
            ).fetchone()[0])
            return trades > 0 or open_campaigns > 0
        except Exception as exc:
            raise ValueError("risk history cannot be verified") from exc

    def _today_start_equity(self, db, current_equity_quote: float) -> float:
        today = datetime.now(timezone.utc).date().isoformat()
        stored_day = db.state_get("daily_risk_day")
        stored_equity = db.state_get("daily_start_equity")

        if stored_day == today:
            return self._parse_positive(stored_equity, "stored daily start equity")

        last_observed = db.state_get("last_observed_equity")
        last_observed_at = db.state_get("last_observed_equity_at_ms")
        if last_observed is not None and last_observed_at is not None:
            baseline = self._parse_positive(last_observed, "last observed equity")
            try:
                observed_ms = int(last_observed_at)
            except (TypeError, ValueError, OverflowError) as exc:
                raise ValueError("last observed equity timestamp is invalid") from exc
            if observed_ms <= 0:
                raise ValueError("last observed equity timestamp is invalid")
            now = datetime.now(timezone.utc)
            midnight = datetime.combine(now.date(), datetime.min.time(), tzinfo=timezone.utc)
            observed_at = datetime.fromtimestamp(observed_ms / 1000.0, tz=timezone.utc)
            if observed_at < midnight - timedelta(minutes=5) or observed_at >= midnight or observed_at > now:
                raise ValueError("no trustworthy equity snapshot near UTC day start")
        elif stored_day is None and stored_equity is None:
            if self._database_has_risk_history(db):
                raise ValueError("daily baseline missing while risk history exists")
            baseline = self._parse_positive(current_equity_quote, "initial equity")
        else:
            raise ValueError("daily baseline is stale/corrupt and no prior equity snapshot exists")

        db.state_set("daily_risk_day", today)
        db.state_set("daily_start_equity", repr(baseline))
        db.state_set("daily_risk_tripped", "false")
        return baseline

    def check(
        self,
        db,
        current_equity_quote: float,
        symbol: str | None = None,
        unrealized_pnl_quote: float = 0.0,
        fees_quote: float = 0.0,
    ) -> tuple[bool, str]:
        """Serialize baseline initialization, PnL check and daily latch in SQLite."""
        transaction = getattr(db, "transaction", None)
        if callable(transaction):
            try:
                with transaction():
                    return self._check_locked(
                        db, current_equity_quote, symbol,
                        unrealized_pnl_quote, fees_quote,
                    )
            except Exception as exc:
                return False, (
                    "daily risk transaction/persistence unavailable; new entries blocked "
                    f"({type(exc).__name__})"
                )
        return self._check_locked(
            db, current_equity_quote, symbol, unrealized_pnl_quote, fees_quote
        )

    def _check_locked(
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

        if not all(math.isfinite(value) for value in (equity, unrealized, fees)) or equity <= 0:
            return False, "non-finite or non-positive daily-risk input; new entries blocked"

        try:
            if symbol is None:
                realized_raw = db.pnl_today_all()
            else:
                realized_raw = db.pnl_today(symbol)
            realized = float(realized_raw)
        except Exception as exc:
            return False, f"realized PnL unavailable; new entries blocked ({type(exc).__name__})"

        if not math.isfinite(realized):
            return False, "non-finite realized PnL; new entries blocked"

        net_daily_pnl = realized + unrealized - fees
        if not math.isfinite(net_daily_pnl):
            return False, "non-finite daily PnL; new entries blocked"

        try:
            start_equity = self._today_start_equity(db, equity)
            # Persist every valid observation even after the daily latch trips,
            # so a trustworthy UTC-boundary sample can seed tomorrow's base.
            db.state_set("last_observed_equity", repr(equity))
            db.state_set("last_observed_equity_at_ms", str(int(datetime.now(timezone.utc).timestamp() * 1000)))
            if str(db.state_get("daily_risk_tripped", "false")).lower() == "true":
                return False, "daily loss breaker is latched for this UTC day; new entries blocked"
            loss_limit = start_equity * self.max_daily_loss_pct
            if not math.isfinite(loss_limit) or loss_limit < 0:
                raise ValueError("daily loss limit is invalid")
        except Exception as exc:
            return False, f"daily start equity/snapshot unavailable; new entries blocked ({type(exc).__name__})"

        # Check both the durable PnL ledger and marked equity. Either source can
        # trip the breaker; this avoids treating missing unrealized PnL as zero
        # when the caller's equity marks the position already.
        equity_loss = equity - start_equity
        if net_daily_pnl <= -loss_limit or equity_loss <= -loss_limit:
            try:
                db.state_set("daily_risk_tripped", "true")
            except Exception as exc:
                return False, f"daily loss reached and breaker latch persistence failed ({type(exc).__name__})"
            return False, (
                "daily equity loss circuit breaker tripped: "
                f"net_pnl={net_daily_pnl:.8f}, equity_change={equity_loss:.8f}, "
                f"limit={-loss_limit:.8f}"
            )

        return True, "ok"
