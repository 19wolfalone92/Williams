import unittest

from execution_barrier import (
    ExecutionBarrier,
    ExecutionPersistenceError,
    OrderIntent,
)
from market_context import ContextCache, TFMarketContext


class FailingIntentStore:
    def execution_readiness(self, **kwargs):
        return {"ready": True}

    def claim_execution_intent(self, intent, **kwargs):
        return {"claimed": True}

    def save_execution_event(self, *args, **kwargs):
        return True

    def save_execution_intent(self, intent, status, reason=""):
        if status == "SUBMITTING":
            raise OSError("simulated durable-store failure")
        return True

    def state_get(self, key, default=None):
        return default


class DurableIntentFailClosedTests(unittest.TestCase):
    def test_submit_is_never_called_when_submitting_intent_cannot_be_persisted(self):
        cache = ContextCache()
        cache.publish(TFMarketContext(
            symbol="BTCUSDT",
            interval="1h",
            version=1,
            candle_open_time_ms=1,
            candle_close_time_ms=2,
            price=100.0,
            allow_long=True,
            decision="ALLOW_LONG",
        ))
        barrier = ExecutionBarrier(cache, db=FailingIntentStore())
        intent = OrderIntent.new(
            "BTCUSDT", "BUY", "STOP_LOSS", {"1h": 1},
            purpose="ENTRY", permission_interval="1h",
        )
        submitted = []

        with self.assertRaises(ExecutionPersistenceError):
            barrier.execute(intent, lambda: submitted.append(True) or {"status": "NEW"})
        self.assertEqual(submitted, [])


if __name__ == "__main__":
    unittest.main()
