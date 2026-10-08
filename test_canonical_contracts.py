import os
import tempfile
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