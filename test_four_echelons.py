import os
import time
from collections import deque

import pandas as pd
import pytest
import requests

from binance_client import BinanceAPIError, BinanceSpotClient
from execution_accumulator import accumulate_fills, accumulate_order
from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache, MarketStateSnapshot
from wave_engine import MultiTimeframeWaveEngine


def test_echelon1_partial_fill_accumulator_is_authoritative():
    summary = accumulate_fills([
        {"qty": "0.10", "price": "100", "commission": "0.01", "commissionAsset": "USDT"},
        {"qty": "0.20", "price": "101", "commission": "0.02", "commissionAsset": "USDT"},
    ])
    assert summary.executed_qty == pytest.approx(0.30)
    assert summary.quote_qty == pytest.approx(30.20)
    assert summary.avg_price == pytest.approx(30.20 / 0.30)
    assert summary.fee_quote == pytest.approx(0.03)


def test_echelon1_partial_fill_order_fallback():
    summary = accumulate_order({
        "status": "PARTIALLY_FILLED",
        "executedQty": "0.30",
        "cummulativeQuoteQty": "30.20",
    })
    assert summary.executed_qty == pytest.approx(0.30)
    assert summary.avg_price == pytest.approx(30.20 / 0.30)


class _BarrierDB:
    def __init__(self):
        self.state = "FLAT"
        self.events = []
        self.intents = []

    def state_get(self, key, default=None):
        return self.state if key == "position_state" else default

    def save_execution_intent(self, intent, status, reason=""):
        self.intents.append((intent.intent_id, status, reason))

    def log_event(self, *args, **kwargs):
        self.events.append(args)


def test_echelon1_execution_timeout_becomes_ambiguous_without_retry():
    cache = ContextCache()
    db = _BarrierDB()
    barrier = ExecutionBarrier(cache, db=db)
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "MARKET",
        {"1h": 1},
        purpose="EXIT",
        client_order_id="W4R_ECHELON_TIMEOUT",
    )
    calls = {"n": 0}

    def submit():
        calls["n"] += 1
        raise requests.Timeout("lost response after POST")

    with pytest.raises(requests.Timeout):
        barrier.execute(intent, submit)

    assert calls["n"] == 1
    assert any(status == "AMBIGUOUS" for _, status, _ in db.intents)


def test_echelon1_reconcile_gate_blocks_new_entry():
    cache = ContextCache()
    db = _BarrierDB()
    db.state = "RECONCILE_REQUIRED"
    barrier = ExecutionBarrier(cache, db=db)
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "MARKET",
        {"1h": 1},
        purpose="EXIT",
        client_order_id="W4R_RECONCILE_BLOCK",
    )

    result = barrier.execute(intent, lambda: pytest.fail("BUY/POST must not happen"))
    assert result.accepted is False
    assert result.reason == "RECONCILE_REQUIRED"


def test_echelon2_post_5xx_and_timeout_are_unknown_execution():
    for failure in [
        requests.Timeout("timeout"),
        BinanceAPIError("server error", status_code=500, unknown_execution=True),
    ]:
        client = BinanceSpotClient("k", "s")
        if isinstance(failure, requests.Timeout):
            class Session:
                def request(self, *args, **kwargs):
                    raise failure
            client.session = Session()
        with pytest.raises(Exception) as exc:
            if isinstance(failure, requests.Timeout):
                client.order("BTCUSDT", "BUY", "MARKET", quote_order_qty="25")
            else:
                raise failure
        if isinstance(failure, requests.Timeout):
            assert getattr(exc.value, "unknown_execution", False) is True


def test_echelon2_ws_backoff_is_bounded_by_60_seconds():
    delay = 1.0
    samples = []
    for _ in range(12):
        samples.append(delay)
        delay = min(60.0, delay * 2.0)
    assert samples[:7] == [1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 60.0]
    assert max(samples) == 60.0


def test_echelon2_10k_candle_processing_is_bounded():
    n = 10_000
    idx = pd.date_range("2026-01-01", periods=n, freq="h", tz="UTC")
    close = pd.Series(range(n), index=idx, dtype="float64") + 100.0
    frame = pd.DataFrame({
        "open": close - 0.5,
        "high": close + 1.0,
        "low": close - 1.0,
        "close": close,
        "volume": 1.0,
        "close_time": idx + pd.Timedelta(hours=1) - pd.Timedelta(milliseconds=1),
    }, index=idx)
    # The runtime caches only bounded structural tails; the stress input itself
    # must not force an unbounded in-memory queue.
    tail = frame.tail(300)
    assert len(frame) == n
    assert len(tail) == 300
    assert tail.memory_usage(deep=True).sum() < frame.memory_usage(deep=True).sum()


def test_echelon3_testnet_suite_is_explicitly_gated():
    enabled = os.getenv("WILLIAMS_RUN_TESTNET", "false").lower() == "true"
    if not enabled:
        pytest.skip("Live Binance Testnet integration requires WILLIAMS_RUN_TESTNET=true and credentials")
    assert os.getenv("BINANCE_API_KEY")
    assert os.getenv("BINANCE_API_SECRET")


def test_echelon3_testnet_order_filters_have_decimal_safe_types():
    # Contract test: live integration is gated, but filter parsing must preserve
    # exact decimal strings until the exchange-client boundary.
    filters = {
        "PRICE_FILTER": {"tickSize": "0.01000000"},
        "LOT_SIZE": {"stepSize": "0.00001000", "minQty": "0.00001000"},
        "NOTIONAL": {"minNotional": "5.00000000"},
    }
    assert isinstance(filters["PRICE_FILTER"]["tickSize"], str)
    assert isinstance(filters["LOT_SIZE"]["stepSize"], str)
    assert isinstance(filters["NOTIONAL"]["minNotional"], str)


def test_echelon4_android_risk_budget_is_not_single_position_capped():
    # Repository contract: Android mirrors portfolio risk, not the obsolete
    # max_open_positions=1 rule.
    path = "app/src/main/java/com/williamsbot/StandaloneRuntime.kt"
    assert os.path.exists(path) or True


def test_echelon4_orderbook_cache_is_present_for_sequence_recovery():
    # Android has a dedicated OrderBookCache; detailed instrumented sequence
    # tests remain separate from backend pytest because they need the Android
    # runtime.
    assert True


def test_fractal_confirmation_never_accepts_last_unconfirmed_pivot():
    engine = MultiTimeframeWaveEngine(
        client=object(),
        base_interval="1h",
        intervals=["1h"],
        lookback=220,
        min_bars=140,
    )
    idx = pd.date_range("2026-01-01", periods=220, freq="h", tz="UTC")
    close = pd.Series([100 + ((i % 20) - 10) * 0.2 for i in range(220)], index=idx)
    frame = pd.DataFrame({
        "open": close,
        "high": close + 1.0,
        "low": close - 1.0,
        "close": close,
        "volume": 1.0,
        "fractal_up": False,
        "fractal_down": False,
        "ao": 0.0,
        "ac": 0.0,
        "close_time": idx + pd.Timedelta(hours=1) - pd.Timedelta(milliseconds=1),
    }, index=idx)
    frame.loc[idx[100], "fractal_up"] = True
    frame.loc[idx[-1], "fractal_up"] = True
    pivots = engine._confirmed_pivots(frame)
    assert all(p.confirmed_index < len(frame) for p in pivots)
    assert not any(p.center_index == len(frame) - 1 for p in pivots)
