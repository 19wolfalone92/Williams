import threading

import pytest

from binance_client import BinanceAPIError
from db import Database
from domain.contracts import (
    ExecutionIntent as CanonicalExecutionIntent,
    ProofVector,
    RiskDecision,
    SignalDirection,
    WilliamsDecision,
)
from execution_barrier import ExecutionAmbiguousError, ExecutionBarrier, OrderIntent


class FakeContext:
    def __init__(self, version=1):
        self.version = version
        self.allow_long = True
        self.allow_short = False


class FakeSnapshot:
    def context(self, symbol, interval):
        return FakeContext(1)


class FakeCache:
    def __init__(self):
        self.execution_lock = threading.RLock()

    def snapshot(self):
        return FakeSnapshot()


def order_intent(client_id="WILL_TEST_001", purpose="CAMPAIGN_ENTRY"):
    return OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "STOP_LOSS",
        {},
        client_order_id=client_id,
        purpose=purpose,
        permission_interval="5m",
    )


def test_proof_vector_is_complete_and_score_is_diagnostic():
    proof = ProofVector(*(True for _ in range(8)))
    assert proof.is_fully_proven is True
    assert proof.diagnostic_score == 1.0
    with pytest.raises(Exception):
        proof.context_pass = False


def test_canonical_decision_and_risk_contracts_are_immutable():
    proof = ProofVector(True, True, True, True, True, True, True, True)
    decision = WilliamsDecision(
        timestamp=1,
        symbol="btcusdt",
        direction=SignalDirection.LONG,
        wise_man_stage=1,
        trigger_price=101.0,
        invalidation_price=97.0,
        proof_vector=proof,
        context_regime="BULLISH",
    )
    risk = RiskDecision(
        williams_decision=decision,
        approved=True,
        allocated_r_multiple=0.25,
        calculated_quantity=0.1,
        max_allowed_slippage=0.001,
    )
    intent = CanonicalExecutionIntent(
        risk_decision=risk,
        order_type="STOP_LOSS",
        client_order_id="WILL_CAMP_9A72_WM1_TEST",
        recv_window=5000,
        time_in_force="GTC",
        reduce_only=False,
    )
    assert decision.symbol == "BTCUSDT"
    assert decision.to_dict()["direction"] == "LONG"
    assert risk.to_dict()["approved"] is True
    assert intent.order_type == "STOP_LOSS"
    with pytest.raises(Exception):
        intent.client_order_id = "OTHER"


def test_execution_barrier_accepts_first_client_order_id_and_blocks_duplicate(tmp_path):
    db = Database(str(tmp_path / "barrier.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    first = order_intent("WILL_TEST_DUP")
    calls = []

    accepted = barrier.execute(first, lambda: calls.append(1) or {
        "symbol": "BTCUSDT",
        "status": "NEW",
        "orderId": 123,
        "clientOrderId": "WILL_TEST_DUP",
        "executedQty": "0",
    })
    assert accepted.accepted is True
    assert calls == [1]

    second = barrier.execute(first, lambda: calls.append(2) or {
        "symbol": "BTCUSDT",
        "status": "NEW",
    })
    assert second.accepted is False
    assert "duplicate client_order_id" in second.reason
    assert calls == [1]


def test_ambiguous_submission_locks_mutations_and_requires_reconciliation(tmp_path):
    db = Database(str(tmp_path / "unknown.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    intent = order_intent("WILL_UNKNOWN_001")

    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            intent,
            lambda: (_ for _ in ()).throw(
                BinanceAPIError("timeout", unknown_execution=True)
            ),
        )

    assert barrier.mutation_locked is True
    assert db.state_get(ExecutionBarrier.MUTATION_LOCK_KEY) is not None

    blocked = barrier.execute(
        order_intent("WILL_AFTER_UNKNOWN"),
        lambda: {
            "symbol": "BTCUSDT",
            "status": "NEW",
        },
    )
    assert blocked.accepted is False
    assert "MUTATION_LOCKED_RECONCILIATION_REQUIRED" in blocked.reason

    reconciled = barrier.reconcile(
        intent,
        lambda: {
            "symbol": "BTCUSDT",
            "status": "FILLED",
            "orderId": 123,
            "clientOrderId": "WILL_UNKNOWN_001",
            "executedQty": "0.1",
        },
    )
    assert reconciled.accepted is True
    assert reconciled.reason == "FILLED"
    assert barrier.mutation_locked is False


def test_not_found_reconciliation_releases_lock_without_post_retry(tmp_path):
    db = Database(str(tmp_path / "not-found.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    intent = order_intent("WILL_UNKNOWN_002")

    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            intent,
            lambda: (_ for _ in ()).throw(
                TimeoutError("network timeout")
            ),
        )

    calls = []
    reconciled = barrier.reconcile(intent, lambda: calls.append("GET") or None)
    assert reconciled.accepted is True
    assert reconciled.reason == "NOT_FOUND"
    assert calls == ["GET"]
    assert barrier.mutation_locked is False


def test_deterministic_binance_rejection_does_not_lock_mutations(tmp_path):
    db = Database(str(tmp_path / "reject.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    intent = order_intent("WILL_REJECT_001")

    result = barrier.execute(
        intent,
        lambda: (_ for _ in ()).throw(
            BinanceAPIError("invalid order", unknown_execution=False, status_code=400)
        ),
    )
    assert result.accepted is False
    assert barrier.mutation_locked is False

def test_core_returns_canonical_decision_without_exposing_exchange():
    from campaign_model import SignalRole, SignalSpec, SignalType
    from digital_williams_core import CoreComposition, DigitalWilliamsCore

    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=100,
        trigger_price=101.0,
        protective_reference=97.0,
        alligator_bullish=True,
        alligator_awake=True,
        angulation_score=1.0,
        htf_confirmed=True,
        source_candle_index=10,
    )
    result = DigitalWilliamsCore().compose([signal], now_ms=150)
    assert isinstance(result, CoreComposition)
    assert result.decision is not None
    assert result.decision.direction is SignalDirection.LONG
    assert result.decision.wise_man_stage == 1
    assert result.decision.proof_vector.price_proof_pass is False
    assert result.action == "ARM_ENTRY"


def test_order_state_machine_uses_unknown_for_ambiguous_lifecycle():
    from order_state_machine import OrderState, OrderStateMachine

    fsm = OrderStateMachine()
    fsm.transition(OrderState.PENDING_NEW)
    fsm.mark_unknown()
    assert fsm.state is OrderState.UNKNOWN
    fsm.reconcile("FILLED", executed_qty=1.0)
    assert fsm.state is OrderState.FILLED


def test_execution_barrier_does_not_unlock_on_failed_reconciliation(tmp_path):
    db = Database(str(tmp_path / "failed-reconcile.sqlite3"))
    barrier = ExecutionBarrier(FakeCache(), db)
    intent = order_intent("WILL_UNKNOWN_003")

    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            intent,
            lambda: (_ for _ in ()).throw(
                BinanceAPIError("timeout", unknown_execution=True)
            ),
        )

    failed = barrier.reconcile(
        intent,
        lambda: (_ for _ in ()).throw(
            BinanceAPIError("still unavailable", unknown_execution=False, status_code=503)
        ),
    )
    assert failed.accepted is False
    assert barrier.mutation_locked is True


def test_wm1_can_be_armable_without_wm2_momentum_score():
    from campaign_model import SignalRole, SignalSpec, SignalType
    from proof_engine import WilliamsProofEngine
    from why_not_engine import WhyNotEngine

    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=100,
        trigger_price=101.0,
        protective_reference=97.0,
        alligator_bullish=True,
        alligator_awake=True,
        angulation_score=1.0,
        htf_confirmed=True,
        source_candle_index=10,
    )
    evaluation = WilliamsProofEngine.evaluate(signal)
    assert evaluation.proof_vector.momentum_pass is False
    assert evaluation.armable is True
    assert WhyNotEngine.armable(
        evaluation.proof_vector,
        wise_man_stage=1,
    ) is True
    assert evaluation.hypothesis_proven is False


def test_wm2_requires_momentum_before_add_on_arm():
    from campaign_model import SignalRole, SignalSpec, SignalType
    from proof_engine import WilliamsProofEngine
    from why_not_engine import WhyNotEngine

    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.SUPER_AO,
        role=SignalRole.ADD_ON,
        timeframe="5m",
        signal_bar_time_ms=100,
        trigger_price=101.0,
        protective_reference=97.0,
        alligator_bullish=True,
        alligator_awake=True,
        htf_confirmed=True,
        source_candle_index=10,
        wave_confidence=0.0,
    )
    evaluation = WilliamsProofEngine.evaluate(signal)
    assert evaluation.proof_vector.momentum_pass is True
    assert evaluation.armable is True
    assert WhyNotEngine.explain_pre_price(
        evaluation.proof_vector,
        wise_man_stage=2,
    ) == ()


def test_proof_diagnostic_score_never_replaces_missing_gate():
    from domain.contracts import ProofVector
    from why_not_engine import WhyNotEngine

    proof = ProofVector(
        context_pass=True,
        behavior_pass=True,
        structure_pass=True,
        location_pass=True,
        angulation_pass=True,
        momentum_pass=True,
        price_proof_pass=False,
        invalidation_present=True,
    )
    assert proof.diagnostic_score == 7 / 8
    assert proof.is_fully_proven is False
    assert WhyNotEngine.explain_full(proof) == ("price_proof_not_triggered",)
