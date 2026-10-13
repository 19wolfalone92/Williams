"""Fault-injection regression tests for the execution mutation boundary."""
import threading

import pytest

from execution_barrier import ExecutionBarrier, OrderIntent


class _ContextCache:
    def __init__(self):
        self.execution_lock = threading.RLock()


class _FailingIntentDB:
    def save_execution_intent(self, intent, status, reason=""):
        raise OSError("simulated durable-storage failure")


def _exit_intent():
    return OrderIntent.new(
        symbol="BTCUSDT",
        side="SELL",
        order_type="MARKET",
        required_context_versions={},
        purpose="EXIT",
    )


def test_missing_database_blocks_every_exchange_mutation():
    submitted = []
    barrier = ExecutionBarrier(_ContextCache(), db=None)

    with pytest.raises(RuntimeError, match="database is not configured"):
        barrier.execute(_exit_intent(), lambda: submitted.append(True))

    assert submitted == []


def test_intent_persistence_failure_blocks_exchange_mutation():
    submitted = []
    barrier = ExecutionBarrier(_ContextCache(), db=_FailingIntentDB())

    with pytest.raises(RuntimeError, match="persistence failed"):
        barrier.execute(_exit_intent(), lambda: submitted.append(True))

    assert submitted == []
