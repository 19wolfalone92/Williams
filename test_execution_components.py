import tempfile

from db import Database
from campaign_model import SignalRole, SignalSpec, SignalType, SignalState
from decision_trace import DecisionTrace
from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache, TFMarketContext
from order_state_machine import OrderLifecycleState, OrderStateMachine
from pending_signal import PendingSignal
from recovery_matrix import RecoveryAction, RecoveryMatrix
from stop_engine import StopEngine


def _ctx(symbol="BTCUSDT", allow_long=True):
    return TFMarketContext(
        symbol=symbol, interval="5m", version=0,
        candle_open_time_ms=1, candle_close_time_ms=2, price=100.0,
        allow_long=allow_long, allow_short=False, decision="LONG" if allow_long else "NO_TRADE",
        data_bars=220,
    )


def test_order_state_machine_rejects_illegal_shortcut():
    machine = OrderStateMachine("i1")
    machine.transition(OrderLifecycleState.ADMISSION)
    try:
        machine.transition(OrderLifecycleState.FILLED)
    except ValueError:
        pass
    else:
        raise AssertionError("order lifecycle allowed CREATED/ADMISSION -> FILLED shortcut")


def test_pending_signal_is_first_class_and_preserves_context():
    signal = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.SUPER_AO,
        role=SignalRole.ENTRY, timeframe="5m", signal_bar_time_ms=100,
        trigger_price=101.0, protective_reference=97.0,
        context_versions={"5m": 7},
    )
    pending = PendingSignal.from_spec(signal)
    armed = pending.transition(SignalState.ARMED)
    assert armed.context_versions == {"5m": 7}
    assert armed.armed_at_ms > 0


def test_recovery_matrix_handles_binance_stp_terminal_status():
    out = RecoveryMatrix.action_for(
        lifecycle="ENTRY_PENDING",
        exchange_status="EXPIRED_IN_MATCH",
    )
    assert out == RecoveryAction.RELEASE_RESERVATION


def test_decision_trace_is_append_only():
    with tempfile.TemporaryDirectory() as d:
        db = Database(d + "/trace.sqlite3")
        db.save_decision_trace(DecisionTrace(intent_id="i", stage="A", decision="STARTED"))
        db.save_decision_trace(DecisionTrace(intent_id="i", stage="B", decision="BLOCKED", blocker="RISK"))
        traces = list(reversed(db.recent_decision_traces(intent_id="i")))
        assert [x["stage"] for x in traces] == ["A", "B"]
        assert traces[1]["blocker"] == "RISK"


def test_barrier_reports_reconciliation_before_missing_context():
    with tempfile.TemporaryDirectory() as d:
        db = Database(d + "/state.sqlite3")
        cache = ContextCache()
        cache.publish(_ctx())
        db.state_set("position_state:BTCUSDT", "RECONCILE_REQUIRED")
        barrier = ExecutionBarrier(cache, db)
        intent = OrderIntent.new(
            "BTCUSDT", "BUY", "STOP_LOSS", {},
            purpose="CAMPAIGN_ENTRY", permission_interval="5m",
        )
        called = []
        result = barrier.execute(intent, lambda: called.append(True))
        assert not result.accepted
        assert result.reason == "RECONCILE_REQUIRED"
        assert called == []
        trace = db.recent_decision_traces(intent_id=intent.intent_id, limit=10)
        assert any(x["blocker"] == "RECONCILE_REQUIRED" for x in trace)


def test_stop_engine_rejects_stop_above_market():
    proposal = StopEngine().propose_long(
        signal_type=SignalType.FRACTAL,
        signal_bar_low=99.0,
        recent_lows=[100.0, 101.0, 102.0],
        teeth=0.0,
        wave_invalidation=0.0,
        current_stop=98.0,
        buffer=0.01,
        current_price=98.5,
    )
    assert proposal.accepted is False
    assert proposal.reason in {"stop_not_below_market", "stop_would_loosen_risk"}
