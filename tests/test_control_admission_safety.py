import threading
from types import SimpleNamespace

import pytest

from db import Database
from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache


@pytest.mark.parametrize("flag", ["kill_switch_latched", "entry_admission_paused"])
@pytest.mark.parametrize("late", [False, True])
def test_durable_control_flags_block_new_risk_even_if_set_during_preflight(tmp_path, flag, late):
    db = Database(str(tmp_path / "control.sqlite3"))
    barrier = ExecutionBarrier(ContextCache(), db)
    if not late:
        db.state_set(flag, "true")
    sent = []
    intent = OrderIntent.new("BTCUSDT", "BUY", "STOP_MARKET", {}, purpose="CAMPAIGN_ENTRY")
    try:
        result = barrier.execute(intent, lambda: sent.append("POST"),
            pre_submit_checks=lambda _: db.state_set(flag, "true") if late else None)
        assert not result.accepted
        assert not sent
    finally:
        db.conn.close()


def test_kill_does_not_report_a_rejected_manual_close_as_a_closed_position(tmp_path):
    import server
    db = Database(str(tmp_path / "kill.sqlite3"))
    runtime = SimpleNamespace(db=db, open_trades=lambda: [{"symbol": "BTCUSDT"}],
        manual_sell=lambda _: {"sold": False, "state": "RECONCILE_REQUIRED", "error": "model exchange unavailable"},
        notify=lambda _: None)
    state = object.__new__(server.ControlState)
    state.lock = threading.RLock()
    state.running = state.desired_running = True
    state.paused = False
    state.thread = None
    state.ensure_trader = lambda: runtime
    state.ensure_multi = lambda: runtime
    try:
        result = state.kill()
        assert result["closed_positions"] == []
        assert result["errors"]
        assert result["state"] == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()
