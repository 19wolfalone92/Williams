from decimal import Decimal
from pathlib import Path
import pytest

from execution_accumulator import accumulate_fills, accumulate_order
from hypothesis_engine import build_hypotheses
from market_context import TFMarketContext
from db import Database
from execution_barrier import ExecutionBarrier, ExecutionPersistenceError, OrderIntent
from market_context import ContextCache


def test_execution_accumulator_vwap():
    summary = accumulate_fills([
        {"price": "100", "qty": "2", "commission": "0.01", "commissionAsset": "USDT"},
        {"price": "110", "qty": "1", "commission": "0.005", "commissionAsset": "USDT"},
    ])
    assert summary.executed_qty == Decimal("3")
    assert summary.quote_qty == Decimal("310")
    assert summary.avg_price == Decimal("103.3333333333333333333333333")
    assert summary.fee_quote == Decimal("0.015")


def test_execution_accumulator_order_fallback():
    summary = accumulate_order({
        "executedQty": "2.5",
        "cummulativeQuoteQty": "250",
    })
    assert summary.executed_qty == Decimal("2.5")
    assert summary.avg_price == Decimal("100")


def test_direction_probabilities_are_independent_and_normalized():
    class Snapshot:
        primary_count = "W3"
        alternative_count = "W5"
        abc_phase = ""
        confidence = 85
        structural_confidence = 80
        exhaustion_risk = 10
        phase = "IMPULSE"
        nested_w3 = True
        invalidation_price = 94

    summary = build_hypotheses(
        "BTCUSDT", "1h", Snapshot(), bullish=True, bearish=False
    )
    total = (
        summary.long_probability
        + summary.short_probability
        + summary.no_trade_probability
    )
    assert abs(total - 1.0) < 1e-8
    assert summary.long_probability > summary.short_probability
    assert summary.calibration_status == "UNCALIBRATED"


def test_market_context_persistence(tmp_path: Path):
    db = Database(str(tmp_path / "state.sqlite3"))
    context = TFMarketContext(
        symbol="BTCUSDT",
        interval="1h",
        version=3,
        candle_open_time_ms=1,
        candle_close_time_ms=2,
        price=100.0,
        long_probability=0.7,
        short_probability=0.1,
        no_trade_probability=0.2,
    )
    db.save_market_context(context)
    row = db.conn.execute(
        "SELECT version, context_json FROM market_context WHERE symbol=? AND interval=?",
        ("BTCUSDT", "1h"),
    ).fetchone()
    assert row["version"] == 3
    assert '"long_probability": 0.7' in row["context_json"]


def _campaign_intent():
    return OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "MARKET",
        required_context_versions={},
        purpose="CAMPAIGN_ENTRY",
        client_order_id="WILLV5_ENTRY_TEST",
        campaign_id="campaign-test",
    )


def test_execution_barrier_blocks_order_when_intent_write_fails():
    db = Database(":memory:")
    barrier = ExecutionBarrier(ContextCache(), db)
    db.save_execution_intent = lambda *args: (_ for _ in ()).throw(OSError("disk write failed"))
    submitted = []
    with pytest.raises(ExecutionPersistenceError):
        barrier.execute(_campaign_intent(), lambda: submitted.append("sent"))
    assert submitted == []
    assert db.conn.execute("SELECT COUNT(*) FROM execution_intents").fetchone()[0] == 0


def test_execution_barrier_blocks_order_when_intent_readback_fails():
    db = Database(":memory:")
    original_connection = db.conn

    class ReadbackFailure:
        def __getattr__(self, name):
            return getattr(original_connection, name)

        def execute(self, sql, *args):
            if "FROM execution_intents WHERE intent_id" in sql:
                raise OSError("read-after-commit failed")
            return original_connection.execute(sql, *args)

    db.conn = ReadbackFailure()
    barrier = ExecutionBarrier(ContextCache(), db)
    submitted = []
    with pytest.raises(ExecutionPersistenceError, match="persistence/readback failed"):
        barrier.execute(_campaign_intent(), lambda: submitted.append("sent"))
    assert submitted == []
    assert original_connection.execute(
        "SELECT COUNT(*) FROM execution_intents"
    ).fetchone()[0] == 1
