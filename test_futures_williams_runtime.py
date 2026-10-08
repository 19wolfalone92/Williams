import math
import os

import pandas as pd

from campaign_engine import CampaignEngine
from campaign_model import CampaignState, SignalRole, SignalSpec, SignalType, stop_only_reduces_risk
from futures_williams_runtime import FuturesWilliamsRuntime
from williams_signals import extract_short_signal_specs, _latest_super_ao


def test_super_ao_does_not_require_fractal_gate():
    ind = pd.DataFrame({
        "ao_red_streak": [0, 1, 2, 3],
        "close": [100, 99, 98, 97],
        "high": [101, 100, 99, 98],
        "low": [99, 98, 97, 96],
        "jaw_shifted": [100, 100, 100, 100],
    })
    result = _latest_super_ao(ind, side="SHORT")
    assert result is not None
    assert result[0] == 3


def test_short_super_ao_can_be_first_wise_man():
    ind = pd.DataFrame({
        "ao_red_streak": [0, 1, 2, 3],
        "close": [100, 99, 98, 97],
        "high": [101, 100, 99, 98],
        "low": [99, 98, 97, 96],
        "jaw_shifted": [100, 100, 100, 100],
        "teeth_shifted": [100, 100, 100, 100],
        "short_fractal_outside": [False, False, False, False],
    })
    specs = extract_short_signal_specs(
        "BTCUSDT",
        ind,
        timeframe="5m",
        tick_size=0.1,
    )
    assert any(s.signal_type == SignalType.SUPER_AO and s.side == "SELL" for s in specs)


def test_campaign_initial_signal_accepts_short():
    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="SELL",
        signal_type=SignalType.SUPER_AO,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=100,
        trigger_price=99.0,
        protective_reference=101.0,
    )
    engine = CampaignEngine(None)
    assert engine.choose_initial_signal([signal]) is signal


def test_stop_monotonicity_is_directional():
    assert stop_only_reduces_risk("LONG", 100.0, 101.0)
    assert not stop_only_reduces_risk("LONG", 100.0, 99.0)
    assert stop_only_reduces_risk("SHORT", 100.0, 99.0)
    assert not stop_only_reduces_risk("SHORT", 100.0, 101.0)


def test_futures_risk_sizing_is_independent_of_leverage():
    runtime = object.__new__(FuturesWilliamsRuntime)
    runtime._equity = lambda: 10000.0
    runtime._min_notional = lambda symbol: 0.0
    runtime._normalize_qty = lambda symbol, qty: qty
    runtime._filters = lambda symbol: {
        "LOT_SIZE": {"stepSize": "0.001", "minQty": "0", "maxQty": "100000"}
    }
    os.environ["FUTURES_MAX_MARGIN_FRACTION"] = "0.25"
    os.environ["MAX_RISK_PER_TRADE_PCT"] = "0.005"
    os.environ["FUTURES_LEVERAGE"] = "2"
    qty, risk, notional = runtime._size_from_risk("BTCUSDT", 100.0, 98.0, 0.001)
    assert risk == 10.0
    assert notional <= 5000.0 + 1e-9
    assert math.isclose(qty, notional / 100.0)


def test_liquidation_guard_requires_liquidation_beyond_stop():
    runtime = object.__new__(FuturesWilliamsRuntime)
    long_campaign = type("C", (), {"side": "BUY", "current_stop_price": 95.0, "average_entry_price": 100.0})()
    short_campaign = type("C", (), {"side": "SELL", "current_stop_price": 105.0, "average_entry_price": 100.0})()
    assert runtime._liquidation_guard(long_campaign, {"liquidationPrice": "90"})
    assert not runtime._liquidation_guard(long_campaign, {"liquidationPrice": "96"})
    assert runtime._liquidation_guard(short_campaign, {"liquidationPrice": "110"})
    assert not runtime._liquidation_guard(short_campaign, {"liquidationPrice": "104"})


def test_directional_williams_triggers_are_not_same_side_only():
    long_signal = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY, timeframe="5m", signal_bar_time_ms=10,
        trigger_price=101, protective_reference=99,
    )
    short_signal = SignalSpec.new(
        symbol="BTCUSDT", side="SELL", signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY, timeframe="5m", signal_bar_time_ms=11,
        trigger_price=99, protective_reference=101,
    )
    engine = CampaignEngine(None)
    selected = engine.choose_initial_signal([long_signal, short_signal])
    assert selected is long_signal
