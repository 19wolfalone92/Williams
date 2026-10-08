import math
import os

import pandas as pd

from campaign_engine import CampaignEngine
from campaign_model import CampaignState, PendingSignal, SignalRole, SignalSpec, SignalState, SignalType, stop_only_reduces_risk
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


def test_pending_signal_has_explicit_durable_lifecycle():
    signal = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.SUPER_AO,
        role=SignalRole.ENTRY, timeframe="5m", signal_bar_time_ms=1000,
        trigger_price=101.0, protective_reference=98.0,
    )
    pending = PendingSignal(signal=signal)
    pending.transition(SignalState.VALIDATED)
    pending.client_order_id = "WILLF_ENTRY_TEST"
    pending.order_id = "123"
    pending.transition(SignalState.ARMED)
    pending.filled_quantity = 0.5
    pending.transition(SignalState.FILLED)
    payload = pending.to_dict()
    assert payload["signal"]["signal_id"] == signal.signal_id
    assert payload["state"] == "FILLED"
    assert payload["client_order_id"] == "WILLF_ENTRY_TEST"
    assert payload["filled_quantity"] == 0.5


def test_structural_trail_uses_min_low_for_long_and_max_high_for_short(monkeypatch):
    frame = pd.DataFrame({
        "low": list(range(80, 160)),
        "high": list(range(100, 180)),
    })

    class Client:
        def __init__(self, price):
            self.price = price
        def ticker_price(self, symbol):
            return {"price": str(self.price)}

    runtime = object.__new__(FuturesWilliamsRuntime)
    runtime._tick = lambda symbol: 1.0

    calls = []
    runtime._replace_protection = lambda campaign, proposed, **kwargs: calls.append((campaign.side, proposed, kwargs)) or True

    monkeypatch.setattr("futures_williams_runtime.fetch_klines", lambda *args, **kwargs: frame)

    long_campaign = type("C", (), {
        "symbol": "BTCUSDT", "side": "BUY", "current_stop_price": 70.0,
        "execution_timeframe": "5m", "state": CampaignState.OPEN_INITIAL,
    })()
    runtime.client = Client(160.0)
    runtime._trail(long_campaign)
    assert calls[-1][0] == "BUY"
    assert calls[-1][1] == 153.0
    assert calls[-1][2] == {}

    short_campaign = type("C", (), {
        "symbol": "BTCUSDT", "side": "SELL", "current_stop_price": 190.0,
        "execution_timeframe": "5m", "state": CampaignState.OPEN_INITIAL,
    })()
    runtime.client = Client(90.0)
    runtime._trail(short_campaign)
    assert calls[-1][0] == "SELL"
    assert calls[-1][1] == 179.0
    assert calls[-1][2] == {}


def test_pending_signal_rejects_illegal_backward_transition():
    signal = SignalSpec.new(
        symbol="BTCUSDT", side="SELL", signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY, timeframe="5m", signal_bar_time_ms=2000,
        trigger_price=99.0, protective_reference=101.0,
    )
    pending = PendingSignal(signal=signal)
    pending.transition(SignalState.VALIDATED)
    pending.transition(SignalState.ARMED)
    try:
        pending.transition(SignalState.DETECTED)
    except ValueError:
        pass
    else:
        raise AssertionError("illegal PendingSignal rollback was accepted")


def test_portfolio_risk_capacity_is_enforced_before_new_campaign():
    runtime = object.__new__(FuturesWilliamsRuntime)
    runtime.max_total_risk_pct = 0.01
    runtime.engine = type("E", (), {
        "portfolio_reserved_risk_quote": lambda self: 75.0,
    })()
    assert math.isclose(
        runtime._portfolio_available_risk_pct(10000.0),
        0.0025,
        rel_tol=0.0,
        abs_tol=1e-12,
    )


def test_liquidation_guard_violation_uses_fail_safe_flatten():
    runtime = object.__new__(FuturesWilliamsRuntime)
    calls = []
    runtime._set_state = lambda symbol, state: calls.append(("state", symbol, state))
    runtime._exit_market = lambda campaign, reason: calls.append(("exit", reason)) or {
        "state": "CLOSED", "symbol": campaign.symbol
    }
    campaign = type("C", (), {"symbol": "BTCUSDT"})()
    result = runtime._fail_safe_flatten(campaign, "LIQUIDATION_GUARD_TEST")
    assert result["state"] == "CLOSED"
    assert ("state", "BTCUSDT", "RECONCILE_REQUIRED") in calls
    assert ("exit", "LIQUIDATION_GUARD_TEST") in calls
