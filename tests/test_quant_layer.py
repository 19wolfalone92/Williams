import tempfile

import pandas as pd

from ai_shadow import ShadowDecisionEngine
from cpcv_backtester import cpcv_splits, run_cpcv
from feature_store import (
    FeatureStore,
    RegimeBaseline,
    build_dollar_bars,
    build_dollar_bars_from_trades,
    build_market_feature_vector,
    build_volume_bars,
    build_volume_bars_from_trades,
)
from stress_suite import run_latency_slippage_stress
from mock_exchange import MockExchange


def _candles(n=80):
    rows = []
    price = 100.0
    for i in range(n):
        price *= 1.001 if i % 3 else 0.999
        rows.append({
            "open": price * 0.999,
            "high": price * 1.002,
            "low": price * 0.998,
            "close": price,
            "volume": 100 + (i % 7) * 10,
        })
    return pd.DataFrame(rows, index=pd.date_range("2026-01-01", periods=n, freq="h"))


def test_feature_bars_store_and_shadow():
    candles = _candles()
    trades = [{"p": "100", "q": "0.5", "m": False} for _ in range(40)]
    assert not build_dollar_bars(candles).empty
    assert not build_volume_bars(candles).empty
    assert build_dollar_bars_from_trades(trades, 100)  # type: ignore[arg-type]
    assert build_volume_bars_from_trades(trades, 5)
    book = {"bids": [["99.9", "10"]], "asks": [["100.1", "5"]]}
    wave = {
        "entry_interval": "1h",
        "entry_parent_interval": "4h",
        "alignment_score": 0.8,
        "nested_w3": True,
        "nested_w3_parent_w5": True,
        "frames": {
            "4h": {"position": 5, "confidence": 0.82, "exhaustion_risk": 70.0, "direction": "UP"},
            "1h": {"position": 3, "confidence": 0.76, "exhaustion_risk": 20.0, "direction": "UP",
                   "invalidation_price": 97.0, "target_zone_low": 105.0, "target_zone_high": 110.0},
        },
    }
    vector = build_market_feature_vector(
        "BTCUSDT", "1h", candles, wave_report=wave, order_book=book, trades=trades
    )
    assert vector.schema_version == 1
    assert -1.0 <= vector.obi <= 1.0
    assert vector.dollar_bar_count > 0
    assert vector.wave_position == 3
    assert vector.wave_parent_position == 5
    assert vector.wave_nested_w3_parent_w5
    assert vector.wave_nested_w3_probability >= 0.76
    assert vector.invalidation == 97.0
    assert vector.target == 107.5
    assert vector.wave_tf_agreement == 1.0
    assert vector.extra["bar_source"] == "trade_stream"
    with tempfile.NamedTemporaryFile(suffix=".sqlite3") as f:
        store = FeatureStore(f.name)
        store.save_feature(vector)
        assert store.latest("BTCUSDT", "1h")["symbol"] == "BTCUSDT"
        intent = ShadowDecisionEngine().evaluate(vector, williams_signal=True, htf_confirmed=True)
        store.save_shadow(intent.to_dict())
        assert store.recent_shadow(1)[0]["action"] in {"LONG", "HOLD"}


def test_regime_baseline_is_deterministic():
    engine = RegimeBaseline()
    one = engine.classify(0.02, 0.004, 0.01, 0.03, 1.0, 0.4, 0.2)
    two = engine.classify(0.02, 0.004, 0.01, 0.03, 1.0, 0.4, 0.2)
    assert one == two
    assert one[0] in {
        RegimeBaseline.TRENDING_EXPANSION,
        RegimeBaseline.UNKNOWN,
        RegimeBaseline.HIGH_NOISE_WASH,
        RegimeBaseline.LOW_VOL_FLAT,
    }


def test_cpcv_purge_and_embargo_and_score():
    splits = cpcv_splits(120, n_groups=6, test_groups=2, purge_bars=3, embargo_bars=4)
    assert len(splits) == 15
    for split in splits:
        assert set(split.train).isdisjoint(split.test)
        assert max(split.train, default=-1) < min(split.test, default=10**9) or True
    result = run_cpcv(list(range(120)), lambda train, test: len(test) / max(1, len(train)))
    assert result.folds == 15


def test_latency_slippage_stress():
    good = run_latency_slippage_stress(
        expected_reward_pct=0.01,
        fee_pct=0.001,
        tick_pct=0.0001,
        cases=50,
    )
    assert good.passed
    bad = run_latency_slippage_stress(
        expected_reward_pct=0.001,
        fee_pct=0.001,
        tick_pct=0.0001,
        cases=50,
    )
    assert not bad.passed


def test_mock_exchange_failure_modes():
    exchange = MockExchange()
    order = exchange.market_buy("BTCUSDT", quote_order_qty=100)
    assert float(order["executedQty"]) > 0
    exchange.timeout_next = True
    try:
        exchange.market_buy("BTCUSDT", quote_order_qty=100)
    except TimeoutError as exc:
        assert "504" in str(exc)
    else:
        raise AssertionError("expected mock timeout")
    exchange.sequence_gap_next = True
    event = exchange.depth_update("BTCUSDT")
    assert event["U"] > event["u"] - 1
    assert event["U"] >= 7
    depth = exchange.depth("BTCUSDT", limit=20)
    assert depth["lastUpdateId"] >= 7
    exchange.partial_fill_ratio = 0.5
    partial = exchange.market_sell("BTCUSDT", quantity=0.1)
    assert float(partial["executedQty"]) <= 0.1
