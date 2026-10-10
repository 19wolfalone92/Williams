from decimal import Decimal
from pathlib import Path

from execution_accumulator import accumulate_fills, accumulate_order
from hypothesis_engine import build_hypotheses
from market_context import ContextCache, TFMarketContext
from db import Database
from execution_barrier import ExecutionBarrier, OrderIntent


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


def test_execution_barrier_refuses_duplicate_stable_client_id_after_unknown_retry(tmp_path):
    db = Database(str(tmp_path / "idempotency.sqlite3"))
    barrier = ExecutionBarrier(ContextCache(), db)
    submissions = []

    def submit():
        submissions.append("sent")
        return {"status": "NEW", "orderId": 123, "clientOrderId": "stable-exit-id"}

    try:
        first = OrderIntent.new(
            "BTCUSDT", "SELL", "MARKET", {},
            purpose="EXIT", client_order_id="stable-exit-id",
        )
        first_result = barrier.execute(first, submit)
        assert first_result.accepted
        assert submissions == ["sent"]

        # A new process/call creates a different intent UUID but must not
        # submit again under the same exchange idempotency key.
        second = OrderIntent.new(
            "BTCUSDT", "SELL", "MARKET", {},
            purpose="EXIT", client_order_id="stable-exit-id",
        )
        second_result = barrier.execute(second, submit)
        assert not second_result.accepted
        assert "duplicate client_order_id" in second_result.reason
        assert submissions == ["sent"]

        # Repeating the rejected retry must remain blocked; the block record
        # must not overwrite/erase evidence of the original submitted intent.
        third = OrderIntent.new(
            "BTCUSDT", "SELL", "MARKET", {},
            purpose="EXIT", client_order_id="stable-exit-id",
        )
        third_result = barrier.execute(third, submit)
        assert not third_result.accepted
        assert "duplicate client_order_id" in third_result.reason
        assert submissions == ["sent"]
    finally:
        db.conn.close()


def test_execution_barrier_allows_wm1_before_directional_context_turns_but_honors_d1_veto():
    import time
    from market_context import ContextCache, TFMarketContext
    from execution_barrier import ExecutionBarrier, OrderIntent

    cache = ContextCache()
    base = dict(
        symbol="BTCUSDT", version=1, candle_open_time_ms=1,
        candle_close_time_ms=2, price=100.0, data_bars=200,
    )
    # H1/H4 are not yet bullish; D1 is neutral. A structurally valid WM1 may
    # be admitted without pretending the higher-timeframe trend has confirmed.
    for interval in ("1h", "4h", "1d"):
        cache.publish(TFMarketContext(
            **base, interval=interval,
            allow_long=False, allow_short=False, alligator_state="SLEEP",
        ))
    barrier = ExecutionBarrier(cache, db=None, require_durable_intent=False)
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_MARKET",
        {"1h": 1, "4h": 1, "1d": 1},
        purpose="CAMPAIGN_ENTRY", permission_interval="1h",
        signal_type="REVERSAL", client_order_id="wm1-entry-1",
    )
    assert barrier._validate(intent, cache.snapshot()) == ""

    # A directional D1 macro context opposing the WM1 is still a hard veto.
    cache.publish(TFMarketContext(
        **{**base, "version": 1}, interval="1d",
        allow_long=False, allow_short=True, alligator_state="BEARISH",
    ))
    refreshed = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_MARKET",
        {"1h": 1, "4h": 1, "1d": 2},
        purpose="CAMPAIGN_ENTRY", permission_interval="1h",
        signal_type="REVERSAL", client_order_id="wm1-entry-2",
    )
    assert "D1 macro context vetoes" in barrier._validate(refreshed, cache.snapshot())
