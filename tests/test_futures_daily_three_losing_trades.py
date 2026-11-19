"""3% UTC daily equity drawdown OR three consecutive negative Futures closes block new risk."""
import sqlite3
from datetime import datetime, timezone
from types import SimpleNamespace

from futures_williams_runtime import FuturesWilliamsRuntime


def _runtime_with_closed_pnl(pnls):
    db = sqlite3.connect(":memory:")
    db.row_factory = sqlite3.Row
    db.execute("CREATE TABLE trades (id INTEGER PRIMARY KEY, entry_time TEXT, exit_time TEXT, pnl REAL)")
    utc_today = datetime.now(timezone.utc).date().isoformat()
    for i, pnl in enumerate(pnls):
        db.execute(
            "INSERT INTO trades (entry_time, exit_time, pnl) VALUES (?,?,?)",
            (utc_today, f"{utc_today}T00:00:{i + 1:02d}+00:00", pnl),
        )
    db.commit()
    runtime = FuturesWilliamsRuntime.__new__(FuturesWilliamsRuntime)
    runtime.db = SimpleNamespace(conn=db)
    runtime.max_trades_day = 0
    runtime.max_consecutive_losses = 3
    runtime.cooldown_minutes = 0
    runtime._equity = lambda: 1000.0
    runtime.open_trades = lambda: []
    runtime.equity_breaker = SimpleNamespace(check=lambda *args: (True, None))
    return runtime


def test_three_consecutive_negative_closes_block_new_entries():
    runtime = _runtime_with_closed_pnl([-1.0, -1.0, -1.0])
    result, reason = runtime._daily_entry_guard()
    assert result is False
    assert reason == "MAX_CONSECUTIVE_LOSSES reached: 3"


def test_win_between_three_daily_losses_resets_streak_and_allows_entries():
    runtime = _runtime_with_closed_pnl([-1.0, 2.0, -1.0, -1.0])
    allowed, reason = runtime._daily_entry_guard()
    assert allowed is True
    assert reason is None


def test_breakeven_interruption_breaks_loss_streak():
    runtime = _runtime_with_closed_pnl([-1.0, 0.0, -1.0, -1.0])
    allowed, reason = runtime._daily_entry_guard()
    assert allowed is True
    assert reason is None


def test_three_consecutive_losses_then_late_win_do_not_unlatch_same_day():
    runtime = _runtime_with_closed_pnl([-1.0, -1.0, -1.0, 2.0])
    allowed, reason = runtime._daily_entry_guard()
    assert allowed is False
    assert reason == "MAX_CONSECUTIVE_LOSSES reached: 3"


def test_two_consecutive_losses_do_not_trigger_the_three_loss_guard():
    runtime = _runtime_with_closed_pnl([-1.0, -1.0])
    allowed, reason = runtime._daily_entry_guard()
    assert allowed is True
    assert reason is None


def test_unknown_closed_pnl_never_counts_as_verified_nonloss():
    runtime = _runtime_with_closed_pnl([-1.0, None])
    allowed, reason = runtime._daily_entry_guard()
    assert allowed is False
    assert reason == "FUTURES_LOSS_STREAK_PNL_UNKNOWN"
