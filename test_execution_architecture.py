from decimal import Decimal
import time
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


def _publish_early_wm1_contexts(cache, *, d1_allow_long=False, d1_allow_short=False):
    now = int(time.time() * 1000)
    for interval, age in (("1h", 20_000), ("4h", 40_000), ("1d", 60_000)):
        cache.publish(TFMarketContext(
            symbol="BTCUSDT",
            interval=interval,
            version=0,
            candle_open_time_ms=now - age - 60_000,
            candle_close_time_ms=now - age,
            price=100.0,
            atr=1.0,
            jaw=100.0,
            teeth=100.0,
            lips=100.0,
            alligator_state="SLEEP",
            alligator_awake=False,
            ao_value=0.0,
            ac_value=0.0,
            williams_core_ready=True,
            allow_long=d1_allow_long if interval == "1d" else False,
            allow_short=d1_allow_short if interval == "1d" else False,
            decision="LONG" if interval == "1d" and d1_allow_long else
                     "SHORT" if interval == "1d" and d1_allow_short else "NO_TRADE",
            data_bars=220,
        ))


def _early_wm1_intent(cache, *, angle=5.0):
    versions = cache.snapshot().versions("BTCUSDT", ["1h", "4h", "1d"])
    return OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "STOP_MARKET",
        versions,
        client_order_id="test-wm1-early",
        purpose="CAMPAIGN_ENTRY",
        permission_interval="1h",
        context_admission_mode="TC2_WM1_EARLY",
        signal_type="REVERSAL",
        angulation_score=angle,
        campaign_id="test-campaign",
        signal_id="test-signal",
    )


def test_execution_barrier_allows_proven_early_wm1_without_h1_h4_directional_alignment():
    cache = ContextCache()
    _publish_early_wm1_contexts(cache)
    barrier = ExecutionBarrier(cache)

    # H1/H4 are asleep/neutral. That is precisely why this is an early WM1 path,
    # not an ordinary trend-confirmation entry.
    intent = _early_wm1_intent(cache)
    assert barrier._validate(intent, cache.snapshot()) == ""


def test_execution_barrier_early_wm1_still_vetoes_active_opposite_d1():
    cache = ContextCache()
    _publish_early_wm1_contexts(cache, d1_allow_short=True)
    barrier = ExecutionBarrier(cache)

    reason = barrier._validate(_early_wm1_intent(cache), cache.snapshot())
    assert "opposite D1" in reason


def test_execution_barrier_early_wm1_requires_source_evidence_and_all_context_versions():
    cache = ContextCache()
    _publish_early_wm1_contexts(cache)
    barrier = ExecutionBarrier(cache)

    assert "positive angulation" in barrier._validate(
        _early_wm1_intent(cache, angle=0.0), cache.snapshot()
    )

    intent = _early_wm1_intent(cache)
    missing_d1 = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "STOP_MARKET",
        {"1h": intent.required_context_versions["1h"], "4h": intent.required_context_versions["4h"]},
        client_order_id="test-wm1-missing-d1",
        purpose="CAMPAIGN_ENTRY",
        permission_interval="1h",
        context_admission_mode="TC2_WM1_EARLY",
        signal_type="REVERSAL",
        angulation_score=5.0,
        campaign_id="test-campaign",
    )
    assert "requires versioned 1d context" in barrier._validate(missing_d1, cache.snapshot())


def test_execution_barrier_keeps_strict_directional_permission_for_other_entry_modes():
    cache = ContextCache()
    _publish_early_wm1_contexts(cache)
    barrier = ExecutionBarrier(cache)
    versions = cache.snapshot().versions("BTCUSDT", ["1h", "4h", "1d"])
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "STOP_MARKET",
        versions,
        client_order_id="test-strict-mode",
        purpose="CAMPAIGN_ENTRY",
        permission_interval="1h",
        signal_type="SUPER_AO",
        campaign_id="test-campaign",
    )
    assert "does not allow LONG" in barrier._validate(intent, cache.snapshot())


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
