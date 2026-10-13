import math
import os
from types import SimpleNamespace

import pandas as pd

from campaign_engine import CampaignEngine
from campaign_model import CampaignState, PendingSignal, SignalRole, SignalSpec, SignalState, SignalType, stop_only_reduces_risk
from futures_williams_runtime import FuturesWilliamsRuntime, FuturesWilliamsScanner, _pending_entry_preflight_reason
from trading_config import TradingConfig, DEFAULT_SYMBOLS, DEFAULT_FUTURES_SYMBOLS
from williams_signals import (
    extract_long_signal_specs, extract_short_signal_specs,
    extract_ntd_ao_signal_specs, extract_ntd_ac_signal_specs,
    extract_ntd_fractal_signal_specs, extract_ntd_zone_add_on_specs,
    _latest_super_ao, _latest_three_price_bar_signal,
)


def test_tc2_three_bullish_price_bars_create_add_on_not_initial_entry():
    ind = pd.DataFrame({
        "open": [100.0, 101.0, 102.0],
        "high": [101.5, 102.5, 104.0],
        "low": [99.5, 100.5, 101.5],
        "close": [101.0, 102.0, 103.5],
        "ao_green": [True, True, True],
        "ao_red": [False, False, False],
        "teeth_shifted": [100.0, 100.5, 101.0],
        "bullish_alligator": [True, True, True],
        "alligator_awake": [True, True, True],
    })
    specs = extract_long_signal_specs("BTCUSDT", ind, timeframe="5m", tick_size=0.1)
    add_on = [s for s in specs if s.signal_type == SignalType.THREE_GREEN_BARS]
    assert len(add_on) == 1
    assert add_on[0].role == SignalRole.ADD_ON
    assert add_on[0].side == "BUY"
    assert math.isclose(add_on[0].trigger_price, 104.1)


def test_tc2_three_bearish_price_bars_create_short_add_on():
    ind = pd.DataFrame({
        "open": [103.0, 102.0, 101.0],
        "high": [103.5, 102.5, 101.5],
        "low": [101.5, 100.5, 99.0],
        "close": [102.0, 101.0, 99.5],
        "ao_green": [False, False, False],
        "ao_red": [True, True, True],
        "teeth_shifted": [102.0, 101.5, 101.0],
        "bearish_alligator": [True, True, True],
        "alligator_awake": [True, True, True],
    })
    specs = extract_short_signal_specs("BTCUSDT", ind, timeframe="5m", tick_size=0.1)
    add_on = [s for s in specs if s.signal_type == SignalType.THREE_GREEN_BARS]
    assert len(add_on) == 1
    assert add_on[0].role == SignalRole.ADD_ON
    assert add_on[0].side == "SELL"
    assert math.isclose(add_on[0].trigger_price, 98.9)


def test_super_ao_recovers_original_third_bar_after_scanner_gap():
    ind = pd.DataFrame({
        "ao_green_streak": [0, 1, 2, 3, 4, 5],
        "close": [100, 101, 102, 103, 104, 105],
        "high": [101, 102, 103, 104, 105, 106],
        "low": [99, 100, 101, 102, 103, 104],
    })
    result = _latest_super_ao(ind, side="LONG")
    assert result is not None
    assert result[0] == 3
    assert result[1] == 104.0
    assert result[2] == 102.0


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
    # Bypass the documented DRY_RUN short-circuit because this test exercises
    # structural stop proposals, not the execution-mode gate.
    runtime.dry_run = False
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
    assert calls[-1][1] == 153.0  # five closed-bar low minus one tick
    assert calls[-1][2] == {}

    short_campaign = type("C", (), {
        "symbol": "BTCUSDT", "side": "SELL", "current_stop_price": 190.0,
        "execution_timeframe": "5m", "state": CampaignState.OPEN_INITIAL,
    })()
    runtime.client = Client(90.0)
    runtime._trail(short_campaign)
    assert calls[-1][0] == "SELL"
    assert calls[-1][1] == 179.0  # five closed-bar high plus one tick
    assert calls[-1][2] == {}


def test_teeth_close_exit_uses_only_closed_bars_for_long(monkeypatch):
    monkeypatch.setenv("WILLIAMS_SIGNAL_PROFILE", "NTD_FIVE_DIMENSIONS")
    # Rising trend followed by a completed close below the chart-aligned Teeth.
    n = 55
    close = [100.0 + i for i in range(n)]
    close[-2] = 90.0
    frame = pd.DataFrame({
        "open": [x + 0.2 for x in close],
        "high": [x + 1.0 for x in close],
        "low": [x - 1.0 for x in close],
        "close": close,
        "volume": [1000.0] * n,
    })

    class Client:
        def ticker_price(self, symbol):
            return {"price": "91"}

    runtime = object.__new__(FuturesWilliamsRuntime)
    runtime.dry_run = False
    runtime.client = Client()
    runtime._tick = lambda symbol: 0.1
    runtime._replace_protection = lambda *args, **kwargs: (_ for _ in ()).throw(
        AssertionError("trail should not run after Teeth exit")
    )
    exits = []
    runtime._exit_market = lambda campaign, reason: exits.append(reason) or {
        "state": "EXIT_SIGNALLED", "reason": reason
    }
    monkeypatch.setattr("futures_williams_runtime.fetch_klines", lambda *args, **kwargs: frame)
    campaign = type("C", (), {
        "symbol": "BTCUSDT", "side": "BUY", "current_stop_price": 80.0,
        "execution_timeframe": "1h", "state": CampaignState.TREND_ACTIVE,
    })()

    result = runtime._trail(campaign)
    assert result["reason"] == "TEETH_CLOSE_EXIT"
    assert exits == ["TEETH_CLOSE_EXIT"]


def test_teeth_close_exit_uses_only_closed_bars_for_short(monkeypatch):
    monkeypatch.setenv("WILLIAMS_SIGNAL_PROFILE", "NTD_FIVE_DIMENSIONS")
    # Falling trend followed by a completed close above the chart-aligned Teeth.
    n = 55
    close = [200.0 - i for i in range(n)]
    close[-2] = 210.0
    frame = pd.DataFrame({
        "open": [x - 0.2 for x in close],
        "high": [x + 1.0 for x in close],
        "low": [x - 1.0 for x in close],
        "close": close,
        "volume": [1000.0] * n,
    })

    class Client:
        def ticker_price(self, symbol):
            return {"price": "209"}

    runtime = object.__new__(FuturesWilliamsRuntime)
    runtime.dry_run = False
    runtime.client = Client()
    runtime._tick = lambda symbol: 0.1
    runtime._replace_protection = lambda *args, **kwargs: (_ for _ in ()).throw(
        AssertionError("trail should not run after Teeth exit")
    )
    exits = []
    runtime._exit_market = lambda campaign, reason: exits.append(reason) or {
        "state": "EXIT_SIGNALLED", "reason": reason
    }
    monkeypatch.setattr("futures_williams_runtime.fetch_klines", lambda *args, **kwargs: frame)
    campaign = type("C", (), {
        "symbol": "BTCUSDT", "side": "SELL", "current_stop_price": 220.0,
        "execution_timeframe": "1h", "state": CampaignState.TREND_ACTIVE,
    })()

    result = runtime._trail(campaign)
    assert result["reason"] == "TEETH_CLOSE_EXIT"
    assert exits == ["TEETH_CLOSE_EXIT"]


def test_ntd_five_same_colour_bars_activate_persistent_one_bar_trailing(monkeypatch):
    monkeypatch.setenv("WILLIAMS_SIGNAL_PROFILE", "NTD_FIVE_DIMENSIONS")
    n = 55
    close = [100.0 + i for i in range(n)]
    frame = pd.DataFrame({
        "open": [x - 0.5 for x in close],
        "high": [x + 1.0 for x in close],
        "low": [x - 1.0 for x in close],
        "close": close,
        "volume": [1000.0] * n,
    })

    class Client:
        def ticker_price(self, symbol):
            return {"price": "200"}

    runtime = object.__new__(FuturesWilliamsRuntime)
    runtime.dry_run = False
    runtime.client = Client()
    runtime._tick = lambda symbol: 0.1
    proposed = []
    runtime._replace_protection = lambda campaign, price, **kwargs: proposed.append(price) or True
    runtime._exit_market = lambda campaign, reason: (_ for _ in ()).throw(
        AssertionError("rising closes must not trigger a Teeth exit")
    )
    monkeypatch.setattr("futures_williams_runtime.fetch_klines", lambda *args, **kwargs: frame)
    campaign = type("C", (), {
        "symbol": "BTCUSDT", "side": "BUY", "current_stop_price": 100.0,
        "execution_timeframe": "1h", "state": CampaignState.TREND_ACTIVE,
        "tags": {},
    })()

    runtime._trail(campaign)
    assert campaign.tags["ntd_five_colour_trail_active"] is True
    assert proposed
    # Five green bars activate the NTD rule: trail below the latest completed bar,
    # tighter than the older 3/5-bar structural minimum in this monotonic test.
    assert math.isclose(proposed[-1], float(frame.iloc[-2]["low"]) - 0.1)


def test_ntd_teeth_exit_uses_close_beyond_line_not_only_a_fresh_crossing(monkeypatch):
    monkeypatch.setenv("WILLIAMS_SIGNAL_PROFILE", "NTD_FIVE_DIMENSIONS")
    frame = pd.DataFrame({
        "open": [100.0] * 55,
        "high": [101.0] * 55,
        "low": [99.0] * 55,
        "close": [100.0] * 55,
        "volume": [1000.0] * 55,
    })

    for side, close_price, market_price in (
        ("BUY", 95.0, 95.0),  # Already below Teeth on the previous close too.
        ("SELL", 105.0, 105.0),  # Already above Teeth on the previous close too.
    ):
        class Client:
            def ticker_price(self, symbol):
                return {"price": str(market_price)}

        runtime = object.__new__(FuturesWilliamsRuntime)
        runtime.dry_run = False
        runtime.client = Client()
        runtime._tick = lambda symbol: 0.1
        runtime._replace_protection = lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("trailing must not run after the NTD Teeth exit")
        )
        runtime._exit_market = lambda campaign, reason: {"reason": reason}
        monkeypatch.setattr(
            "futures_williams_runtime.fetch_klines",
            lambda *args, **kwargs: frame,
        )
        monkeypatch.setattr(
            "futures_williams_runtime.calculate_indicators",
            lambda *_args, **_kwargs: pd.DataFrame({
                "close": [close_price, close_price],
                "teeth_shifted": [100.0, 100.0],
            }),
        )
        campaign = type("C", (), {
            "symbol": "BTCUSDT", "side": side, "current_stop_price": 80.0 if side == "BUY" else 120.0,
            "execution_timeframe": "1h", "state": CampaignState.TREND_ACTIVE,
        })()
        assert runtime._trail(campaign)["reason"] == "TEETH_CLOSE_EXIT"


def test_tc2_profile_does_not_apply_ntd_teeth_close_exit(monkeypatch):
    monkeypatch.setenv("WILLIAMS_SIGNAL_PROFILE", "TC2_THREE_WISE_MEN")
    n = 55
    close = [100.0 + i for i in range(n)]
    close[-2] = 90.0
    frame = pd.DataFrame({
        "open": [x + 0.2 for x in close],
        "high": [x + 1.0 for x in close],
        "low": [x - 1.0 for x in close],
        "close": close,
        "volume": [1000.0] * n,
    })

    class Client:
        def ticker_price(self, symbol):
            return {"price": "91"}

    runtime = object.__new__(FuturesWilliamsRuntime)
    runtime.dry_run = False
    runtime.client = Client()
    runtime._tick = lambda symbol: 0.1
    proposed = []
    runtime._replace_protection = lambda campaign, price, **kwargs: proposed.append(price) or True
    runtime._exit_market = lambda campaign, reason: (_ for _ in ()).throw(
        AssertionError("NTD-specific Teeth exit leaked into TC2")
    )
    monkeypatch.setattr("futures_williams_runtime.fetch_klines", lambda *args, **kwargs: frame)
    campaign = type("C", (), {
        "symbol": "BTCUSDT", "side": "BUY", "current_stop_price": 80.0,
        "execution_timeframe": "1h", "state": CampaignState.TREND_ACTIVE,
        "tags": {},
    })()

    result = runtime._trail(campaign)
    assert result.get("reason") != "TEETH_CLOSE_EXIT"
    assert "ntd_five_colour_trail_active" not in campaign.tags
    assert proposed


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


def test_execution_door_blocks_dry_run_before_exchange_call():
    runtime = object.__new__(FuturesWilliamsRuntime)
    runtime.dry_run = True
    runtime.config = type("Cfg", (), {"allow_live": False})()
    runtime.client = type("Client", (), {"testnet": True})()
    intent = type("Intent", (), {"purpose": "CAMPAIGN_ENTRY"})()
    try:
        runtime._submit(intent, lambda: (_ for _ in ()).throw(AssertionError("exchange called")))
    except RuntimeError as exc:
        assert "DRY_RUN" in str(exc)
    else:
        raise AssertionError("DRY_RUN did not block execution door")


def test_execution_door_blocks_live_without_allow_live():
    runtime = object.__new__(FuturesWilliamsRuntime)
    runtime.dry_run = False
    runtime.config = type("Cfg", (), {"allow_live": False})()
    runtime.client = type("Client", (), {"testnet": False})()
    intent = type("Intent", (), {"purpose": "CAMPAIGN_ENTRY"})()
    try:
        runtime._submit(intent, lambda: (_ for _ in ()).throw(AssertionError("exchange called")))
    except RuntimeError as exc:
        assert "ALLOW_LIVE" in str(exc)
    else:
        raise AssertionError("live execution bypassed ALLOW_LIVE")


def test_futures_pre_submit_blocks_entry_when_position_appears_after_signal():
    runtime = object.__new__(FuturesWilliamsRuntime)
    campaign = type("C", (), {
        "campaign_id": "c1",
        "symbol": "BTCUSDT",
        "side": "BUY",
        "state": CampaignState.ENTRY_PENDING,
    })()
    runtime.engine = type("E", (), {"load_campaign": lambda self, cid: campaign})()
    runtime.config = type("Cfg", (), {"allow_long": True, "allow_short": True})()
    runtime._position = lambda symbol: {"positionAmt": "0.25"}
    runtime._signed_position_qty = lambda position: float(position["positionAmt"])
    intent = type("I", (), {
        "campaign_id": "c1",
        "symbol": "BTCUSDT",
        "side": "BUY",
        "purpose": "CAMPAIGN_ENTRY",
    })()
    try:
        runtime._futures_pre_submit_checks(intent)
    except RuntimeError as exc:
        assert "already exists" in str(exc)
    else:
        raise AssertionError("entry was admitted over an existing Futures position")


def test_short_fractal_requires_valid_teeth_context():
    ind = pd.DataFrame({
        "confirmed_down_level": [float("nan"), float("nan"), float("nan"), float("nan"), 98.0],
        "fractal_down": [False, False, True, False, False],
        "close": [99.5, 99.0, 98.5, 98.0, 97.5],
        "low": [99.0, 98.5, 97.0, 97.5, 97.0],
        "high": [100.0, 99.5, 99.0, 98.5, 98.0],
        "teeth_shifted": [0.0, 0.0, 0.0, 0.0, 0.0],
    })
    assert extract_short_signal_specs(
        "BTCUSDT",
        ind,
        timeframe="5m",
        tick_size=0.1,
    ) == []


def test_five_magic_bullets_core_math():
    from magic_bullets import (
        ao_price_divergence,
        evaluate_magic_bullets,
        momentum_change,
        price_in_target_zone,
        terminal_squat,
    )

    assert ao_price_divergence(
        side="LONG",
        wave3_price=100,
        wave5_price=110,
        wave3_ao=8,
        wave5_ao=5,
    )
    assert ao_price_divergence(
        side="SHORT",
        wave3_price=110,
        wave5_price=100,
        wave3_ao=-8,
        wave5_ao=-5,
    )
    assert price_in_target_zone(
        162,
        side="LONG",
        wave1_start=100,
        wave3_end=150,
        wave4_end=130,
    )
    assert terminal_squat(
        candidate_index=8,
        terminal_extreme_index=10,
        squat=True,
    )
    assert momentum_change(previous_ao_color="GREEN", current_ao_color="RED")

    state = evaluate_magic_bullets(
        divergence=True,
        target_zone_hit=True,
        terminal_fractal=True,
        terminal_squat_hit=True,
        momentum_change_hit=True,
    )
    assert state.count == 5
    assert state.all_five


def test_futures_universe_uses_the_configured_default_ten_when_env_is_absent(monkeypatch):
    monkeypatch.delenv("FUTURES_APPROVED_SYMBOLS", raising=False)
    approved = [
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
        "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT",
    ]

    class Client:
        def exchange_info(self):
            return {"symbols": [
                {
                    "symbol": symbol,
                    "status": "TRADING",
                    "quoteAsset": "USDT",
                    "contractType": "PERPETUAL",
                    "filters": [
                        {"filterType": "PRICE_FILTER", "tickSize": "0.01"},
                        {"filterType": "LOT_SIZE", "stepSize": "0.001"},
                        {"filterType": "MIN_NOTIONAL", "notional": "5"},
                    ],
                }
                for symbol in approved
            ]}

    scanner = FuturesWilliamsScanner(Client(), interval="5m")
    assert scanner.universe() == approved
    assert scanner.symbols == approved
    assert scanner.last_universe_error is None


def test_futures_universe_rejects_duplicates_and_exchange_ineligible_symbols(monkeypatch):
    symbols = ["AAAUSDT", "BBBUSDT", "CCCUSDT", "DDDUSDT", "EEEUSDT",
               "FFFUSDT", "GGGUSDT", "HHHUSDT", "IIIUSDT", "JJJUSDT"]
    monkeypatch.setenv("FUTURES_APPROVED_SYMBOLS", ",".join(symbols[:-1] + [symbols[0]]))

    class Client:
        def exchange_info(self):
            raise AssertionError("duplicate configuration must be rejected before exchange lookup")

    scanner = FuturesWilliamsScanner(Client(), interval="5m")
    assert scanner.universe() == []
    assert "duplicate" in scanner.last_universe_error

    monkeypatch.setenv("FUTURES_APPROVED_SYMBOLS", ",".join(symbols))
    rows = []
    for symbol in symbols:
        rows.append({
            "symbol": symbol,
            "status": "TRADING",
            "quoteAsset": "USDT",
            "contractType": "PERPETUAL",
            "filters": [
                {"filterType": "PRICE_FILTER", "tickSize": "0.01"},
                {"filterType": "LOT_SIZE", "stepSize": "0.001"},
                {"filterType": "MIN_NOTIONAL", "notional": "5"},
            ],
        })
    rows[-1]["status"] = "BREAK"
    class IneligibleClient:
        def exchange_info(self):
            return {"symbols": rows}

    scanner = FuturesWilliamsScanner(IneligibleClient(), interval="5m")
    assert scanner.universe() == []
    assert symbols[-1] in scanner.last_universe_error


def test_futures_universe_returns_exact_approved_ten_without_dynamic_additions(monkeypatch):
    symbols = ["AAAUSDT", "BBBUSDT", "CCCUSDT", "DDDUSDT", "EEEUSDT",
               "FFFUSDT", "GGGUSDT", "HHHUSDT", "IIIUSDT", "JJJUSDT"]
    monkeypatch.setenv("FUTURES_APPROVED_SYMBOLS", ",".join(symbols))
    rows = [{
        "symbol": symbol,
        "status": "TRADING",
        "quoteAsset": "USDT",
        "contractType": "PERPETUAL",
        "filters": [
            {"filterType": "PRICE_FILTER", "tickSize": "0.01"},
            {"filterType": "LOT_SIZE", "stepSize": "0.001"},
            {"filterType": "MIN_NOTIONAL", "notional": "5"},
        ],
    } for symbol in symbols]
    rows.append({
        "symbol": "UNAPPROVEDUSDT",
        "status": "TRADING",
        "quoteAsset": "USDT",
        "contractType": "PERPETUAL",
        "filters": [
            {"filterType": "PRICE_FILTER", "tickSize": "0.01"},
            {"filterType": "LOT_SIZE", "stepSize": "0.001"},
            {"filterType": "MIN_NOTIONAL", "notional": "5"},
        ],
    })

    class Client:
        def exchange_info(self):
            return {"symbols": rows}

    scanner = FuturesWilliamsScanner(Client(), interval="5m")
    assert scanner.universe(limit=40) == symbols
    assert len(scanner.universe()) == 10
    assert len(set(scanner.universe())) == 10
    assert scanner.last_universe_error is None

def test_dry_run_still_scans_market_data_but_never_arms_orders():
    candidate = object()

    class Scanner:
        last_scan_status = {
            "state": "COMPLETE",
            "configured_symbols": 10,
            "evaluated_symbols": 10,
        }

        def __init__(self):
            self.calls = 0

        def scan(self):
            self.calls += 1
            return [candidate]

    class DB:
        def __init__(self):
            self.events = []

        def log_event(self, *args):
            self.events.append(args)

    runtime = object.__new__(FuturesWilliamsRuntime)
    runtime.preflight_report = {"ready": True}
    runtime.recovered = True
    runtime.dry_run = True
    runtime.scanner = Scanner()
    runtime.db = DB()
    runtime._reconcile_pending = lambda: None
    runtime._arm_entry = lambda *_: (_ for _ in ()).throw(
        AssertionError("DRY_RUN must never arm an entry")
    )

    result = runtime.process()

    assert result == [candidate]
    assert runtime.scanner.calls == 1
    assert runtime.db.events[-1][1] == "futures_dry_run_cycle"
    assert "no strategy orders or exits submitted" in runtime.db.events[-1][2]



def test_tc2_wm1_is_not_blocked_by_directional_htf_confirmation_policy():
    from futures_williams_runtime import _requires_directional_htf_confirmation

    # WM1 is the early reversal signal. Requiring an already-aligned HTF trend
    # would turn it into a late confirmation entry rather than the book's WM1.
    assert _requires_directional_htf_confirmation(SignalType.REVERSAL) is False
    assert _requires_directional_htf_confirmation(SignalType.SUPER_AO) is True
    assert _requires_directional_htf_confirmation(SignalType.FRACTAL) is True



def test_ntd_green_zone_add_on_uses_matching_ao_ac_and_price_colours_without_zero_gate():
    ind = pd.DataFrame({
        "open_time_ms": [1_700_000_000_000, 1_700_000_300_000],
        "open": [99.0, 100.0],
        "high": [100.0, 102.0],
        "low": [98.0, 99.0],
        "close": [99.5, 101.5],
        "ao": [-1.1, -1.0],
        "ac": [-0.6, -0.5],
        "teeth_shifted": [99.0, 99.5],
        "long_fractal_outside": [True, True],
        "bullish_alligator": [True, True],
        "alligator_awake": [True, True],
    })
    signals = extract_ntd_zone_add_on_specs(
        "BTCUSDT", ind, side="LONG", timeframe="5m", tick_size=0.1
    )
    assert len(signals) == 1
    assert signals[0].signal_type == SignalType.ZONE_ADD_ON
    assert signals[0].role == SignalRole.ADD_ON
    assert signals[0].side == "BUY"
    assert math.isclose(signals[0].trigger_price, 102.1)


def test_ntd_red_zone_add_on_is_mirrored_and_gray_zone_is_blocked():
    ind = pd.DataFrame({
        "open_time_ms": [1_700_000_000_000, 1_700_000_300_000],
        "open": [101.0, 100.0],
        "high": [102.0, 101.0],
        "low": [100.0, 98.0],
        "close": [100.5, 98.5],
        "ao": [1.1, 1.0],
        "ac": [0.6, 0.5],
        "teeth_shifted": [101.0, 100.5],
        "short_fractal_outside": [True, True],
        "bearish_alligator": [True, True],
        "alligator_awake": [True, True],
    })
    signals = extract_ntd_zone_add_on_specs(
        "BTCUSDT", ind, side="SHORT", timeframe="5m", tick_size=0.1
    )
    assert len(signals) == 1
    assert signals[0].signal_type == SignalType.ZONE_ADD_ON
    assert signals[0].role == SignalRole.ADD_ON
    assert signals[0].side == "SELL"
    assert math.isclose(signals[0].trigger_price, 97.9)

    ind.loc[1, "ac"] = 0.7  # AO falls while AC rises: Gray Zone.
    assert extract_ntd_zone_add_on_specs(
        "BTCUSDT", ind, side="SHORT", timeframe="5m", tick_size=0.1
    ) == []


def test_conditional_williams_trigger_is_not_rearmed_after_intrabar_touch():
    from williams_signals import _trigger_still_pending

    long_frame = pd.DataFrame({
        "high": [101.0, 105.2, 104.8],
        "low": [99.0, 103.0, 102.0],
        "close": [100.0, 104.0, 104.5],
    })
    assert not _trigger_still_pending(
        long_frame, 0, side="BUY", trigger_price=105.1
    )

    short_frame = pd.DataFrame({
        "high": [101.0, 100.0, 100.5],
        "low": [99.0, 94.8, 95.2],
        "close": [100.0, 96.0, 96.5],
    })
    assert not _trigger_still_pending(
        short_frame, 0, side="SELL", trigger_price=94.9
    )

    untouched = pd.DataFrame({
        "high": [101.0, 104.0, 104.5],
        "low": [99.0, 100.0, 100.5],
        "close": [100.0, 103.0, 104.0],
    })
    assert _trigger_still_pending(
        untouched, 0, side="BUY", trigger_price=105.1
    )


def test_tc2_fractal_signal_time_is_confirmation_time_not_pivot_time():
    n = 9
    times = [1_700_000_000_000 + i * 300_000 for i in range(n)]
    ind = pd.DataFrame({
        "open_time_ms": times,
        "open": [99.0, 100.0, 101.0, 102.0, 103.0, 102.5, 103.0, 103.8, 104.0],
        "high": [100.0, 101.0, 102.0, 103.0, 105.0, 103.5, 104.0, 104.2, 104.7],
        "low": [98.0, 99.0, 100.0, 101.0, 99.0, 101.0, 102.0, 102.5, 103.0],
        "close": [99.0, 100.0, 101.0, 102.0, 103.0, 102.5, 103.0, 103.8, 104.5],
        "teeth_shifted": [98.0, 98.5, 99.0, 100.0, 101.0, 102.0, 104.0, 104.0, 104.1],
        "fractal_up": [False, False, False, False, True, False, False, False, False],
        "confirmed_up_level": [float("nan")] * 6 + [105.0, float("nan"), float("nan")],
        "ao_green": [False] * n,
        "ao_red": [False] * n,
        "ao_green_streak": [0] * n,
        "ao_red_streak": [0] * n,
        "bullish_reversal_bar": [False] * n,
        "bearish_reversal_bar": [False] * n,
        "bullish_alligator": [True] * n,
        "bearish_alligator": [False] * n,
        "alligator_awake": [True] * n,
    })
    signals = extract_long_signal_specs(
        "BTCUSDT", ind, timeframe="5m", tick_size=0.1
    )
    fractals = [s for s in signals if s.signal_type == SignalType.FRACTAL]
    assert len(fractals) == 1
    assert fractals[0].source_candle_index == 4
    assert fractals[0].signal_bar_time_ms == times[6]
    assert fractals[0].expires_at_ms == times[6] + 80 * 300_000


def test_ntd_external_fractal_is_initial_entry_and_requires_untriggered_breakout():
    n = 9
    times = [1_700_000_000_000 + i * 300_000 for i in range(n)]
    ind = pd.DataFrame({
        "open_time_ms": times,
        "high": [100.0, 101.0, 102.0, 103.0, 105.0, 103.5, 104.0, 104.2, 104.7],
        "low": [98.0, 99.0, 100.0, 101.0, 99.0, 101.0, 102.0, 102.5, 103.0],
        "close": [99.0, 100.0, 101.0, 102.0, 103.0, 102.5, 103.0, 103.8, 104.5],
        "teeth_shifted": [98.0, 98.5, 99.0, 100.0, 101.0, 102.0, 104.0, 104.0, 104.1],
        "fractal_up": [False, False, False, False, True, False, False, False, False],
        "fractal_down": [False] * n,
        "bullish_alligator": [True] * n,
        "alligator_awake": [True] * n,
    })
    signals = extract_ntd_fractal_signal_specs(
        "BTCUSDT", ind, side="LONG", timeframe="5m", tick_size=0.1
    )
    assert len(signals) == 1
    assert signals[0].signal_type == SignalType.FRACTAL
    assert signals[0].role == SignalRole.ENTRY
    assert signals[0].signal_bar_time_ms == times[6]
    assert math.isclose(signals[0].trigger_price, 105.1)
    assert math.isclose(signals[0].protective_reference, 99.0)

    # If any post-confirmation candle already touched the trigger, do not
    # resurrect that old conditional order after a scan/process gap.
    ind.loc[7, "high"] = 105.2
    assert extract_ntd_fractal_signal_specs(
        "BTCUSDT", ind, side="LONG", timeframe="5m", tick_size=0.1
    ) == []
    ind.loc[7, "high"] = 104.2
    ind.loc[6, "high"] = 105.2  # The second right-hand bar crossed before confirmation.
    assert extract_ntd_fractal_signal_specs(
        "BTCUSDT", ind, side="LONG", timeframe="5m", tick_size=0.1
    ) == []


def test_ntd_ao_saucer_is_an_explicit_conditional_entry():
    ind = pd.DataFrame({
        "ao": [1.0, 1.1, 1.0, 1.05],
        # The first Saucer bar may itself be red; only the middle
        # downswing and final upswing colours are required by NTD.
        "ao_green": [False, False, False, True],
        "ao_red": [False, False, True, False],
        "high": [100.0, 101.0, 100.5, 101.0],
        "low": [98.0, 99.0, 98.5, 99.0],
        "close": [99.0, 100.0, 99.0, 100.0],
        "teeth_shifted": [99.0, 99.5, 99.0, 99.5],
    })
    signals = extract_ntd_ao_signal_specs(
        "BTCUSDT", ind, side="LONG", timeframe="5m", tick_size=0.1
    )
    assert len(signals) == 1
    assert signals[0].signal_type == SignalType.AO_SAUCER
    assert signals[0].role == SignalRole.ADD_ON
    assert signals[0].side == "BUY"
    assert math.isclose(signals[0].trigger_price, 101.1)
    assert math.isclose(signals[0].protective_reference, 99.0)


def test_ntd_bearish_ao_saucer_does_not_require_first_bar_red():
    ind = pd.DataFrame({
        "ao": [-1.0, -0.8, -0.9],
        "ao_green": [False, True, False],
        "ao_red": [False, False, True],
        "high": [101.0, 100.5, 100.0],
        "low": [99.0, 98.5, 98.0],
        "close": [100.0, 99.0, 99.0],
    })
    signals = extract_ntd_ao_signal_specs(
        "BTCUSDT", ind, side="SHORT", timeframe="5m", tick_size=0.1
    )
    assert len(signals) == 1
    assert signals[0].signal_type == SignalType.AO_SAUCER
    assert signals[0].side == "SELL"
    assert signals[0].role == SignalRole.ADD_ON
    assert math.isclose(signals[0].trigger_price, 97.9)


def test_ntd_ao_zero_line_cross_is_directional_and_mirrored():
    long_ind = pd.DataFrame({
        "ao": [-0.2, -0.05, 0.1],
        "ao_green": [False, True, True],
        "ao_red": [False, False, False],
        "high": [100.0, 100.5, 101.0],
        "low": [98.0, 98.5, 99.0],
        "close": [99.0, 100.0, 100.0],
    })
    short_ind = pd.DataFrame({
        "ao": [0.2, 0.05, -0.1],
        "ao_green": [False, False, False],
        "ao_red": [False, True, True],
        "high": [102.0, 101.5, 101.0],
        "low": [100.0, 99.5, 99.0],
        "close": [101.0, 100.0, 100.0],
    })
    long_signals = extract_ntd_ao_signal_specs(
        "BTCUSDT", long_ind, side="LONG", timeframe="5m", tick_size=0.1
    )
    short_signals = extract_ntd_ao_signal_specs(
        "BTCUSDT", short_ind, side="SHORT", timeframe="5m", tick_size=0.1
    )
    assert [s.signal_type for s in long_signals] == [SignalType.AO_ZERO_LINE]
    assert [s.signal_type for s in short_signals] == [SignalType.AO_ZERO_LINE]
    assert long_signals[0].role == SignalRole.ADD_ON
    assert short_signals[0].role == SignalRole.ADD_ON
    assert long_signals[0].side == "BUY"
    assert short_signals[0].side == "SELL"


def test_ntd_ao_twin_peaks_requires_second_peak_closer_to_zero():
    ind = pd.DataFrame({
        "ao": [-0.5, -1.0, -0.6, -0.8, -0.4],
        "ao_green": [False, False, True, False, True],
        "ao_red": [False, True, False, True, False],
        "high": [100.0, 99.0, 100.0, 99.5, 101.0],
        "low": [98.0, 97.0, 98.0, 97.5, 99.0],
        "close": [99.0, 98.0, 99.0, 98.5, 100.0],
    })
    signals = extract_ntd_ao_signal_specs(
        "BTCUSDT", ind, side="LONG", timeframe="5m", tick_size=0.1
    )
    assert len(signals) == 1
    assert signals[0].signal_type == SignalType.AO_TWIN_PEAKS



def test_ntd_ac_requires_directional_colour_run_and_prices_the_signal_bar():
    ind = pd.DataFrame({
        "ac": [0.5, 0.4, 0.45, 0.5],
        "high": [100.0, 100.5, 100.8, 101.0],
        "low": [98.0, 98.5, 98.8, 99.0],
        "close": [99.0, 99.5, 100.0, 100.0],
    })
    signals = extract_ntd_ac_signal_specs(
        "BTCUSDT", ind, side="LONG", timeframe="5m", tick_size=0.1
    )
    assert len(signals) == 1
    assert signals[0].signal_type == SignalType.AC_SIGNAL
    assert signals[0].side == "BUY"
    assert math.isclose(signals[0].trigger_price, 101.1)
    assert math.isclose(signals[0].protective_reference, 99.0)


def test_ntd_ac_below_zero_requires_three_green_bars_for_long():
    ind = pd.DataFrame({
        "ac": [-0.5, -0.6, -0.55, -0.5, -0.45],
        "high": [100.0, 99.5, 100.0, 100.5, 101.0],
        "low": [98.0, 97.5, 98.0, 98.5, 99.0],
        "close": [99.0, 98.0, 99.0, 100.0, 100.0],
    })
    signals = extract_ntd_ac_signal_specs(
        "BTCUSDT", ind, side="LONG", timeframe="5m", tick_size=0.1
    )
    assert len(signals) == 1
    assert signals[0].signal_type == SignalType.AC_SIGNAL
    assert signals[0].source_candle_index == 4


def test_ntd_ac_below_zero_short_is_mirrored():
    ind = pd.DataFrame({
        "ac": [-0.5, -0.4, -0.45, -0.5],
        "high": [102.0, 101.5, 101.0, 100.5],
        "low": [100.0, 99.5, 99.0, 98.5],
        "close": [101.0, 100.0, 100.0, 99.0],
    })
    signals = extract_ntd_ac_signal_specs(
        "BTCUSDT", ind, side="SHORT", timeframe="5m", tick_size=0.1
    )
    assert len(signals) == 1
    assert signals[0].side == "SELL"
    assert math.isclose(signals[0].trigger_price, 98.4)



def test_invalid_williams_profile_blocks_scan_before_exchange_discovery(monkeypatch):
    monkeypatch.setenv("WILLIAMS_SIGNAL_PROFILE", "TYPO_PROFILE")

    class Client:
        def exchange_info(self):
            raise AssertionError("invalid profile must block before exchange access")

    scanner = FuturesWilliamsScanner(Client(), interval="5m")
    assert scanner.scan() == []
    assert scanner.last_scan_status["state"] == "BLOCKED"
    assert scanner.last_scan_status["reason"] == "INVALID_WILLIAMS_SIGNAL_PROFILE"



def test_newer_fractal_inside_teeth_does_not_shadow_older_valid_trigger():
    from williams_signals import _latest_confirmed_fractal

    # Two confirmed upper fractals: the newer one is not above current Teeth,
    # while the older one remains untriggered and valid. Preserve its trigger.
    ind = pd.DataFrame({
        "high": [101.0, 102.0, 110.0, 104.0, 105.0, 103.0, 102.0],
        "low":  [99.0, 100.0, 108.0, 101.0, 100.0, 101.0, 100.0],
        "fractal_up": [False, False, True, False, True, False, False],
        "confirmed_up_level": [float("nan"), float("nan"), float("nan"), float("nan"), 110.0, float("nan"), 105.0],
        "teeth_shifted": [103.0, 103.0, 103.0, 104.0, 104.0, 106.0, 106.0],
    })
    result = _latest_confirmed_fractal(
        ind, side="LONG", current_teeth=106.0, current_close=107.0, tick_size=1.0
    )
    assert result is not None
    assert result[0] == 4
    assert result[1] == 2
    assert result[2] == 110.0

    # A later intrabar touch must invalidate the pending stop even if the
    # latest close is again below the trigger.
    ind.loc[5, "high"] = 112.0
    assert _latest_confirmed_fractal(
        ind, side="LONG", current_teeth=106.0, current_close=107.0, tick_size=1.0
    ) is None



def test_default_config_keeps_intraday_h1_and_market_specific_universes():
    futures = TradingConfig.from_env({
        "BINANCE_MARKET": "futures_usdt",
        "INTERVAL": "1h",
    })
    assert futures.execution_timeframe == "1h"
    assert futures.structural_timeframes == ("1d", "4h", "1h", "15m")
    assert futures.symbols == DEFAULT_FUTURES_SYMBOLS
    assert len(futures.symbols) == 10

    spot = TradingConfig.from_env({"BINANCE_MARKET": "spot"})
    assert spot.symbols == DEFAULT_SYMBOLS
    assert len(spot.symbols) == 5



def test_final_entry_preflight_blocks_expired_or_already_crossed_williams_triggers():
    from types import SimpleNamespace

    intent = SimpleNamespace(signal_id="BTCUSDT:1h:FRACTAL:1000", side="BUY")
    pending = {
        "state": "VALIDATED",
        "signal": {
            "signal_id": intent.signal_id,
            "side": "BUY",
            "trigger_price": 100.0,
            "protective_reference": 97.0,
            "expires_at_ms": 5000,
        },
    }
    assert _pending_entry_preflight_reason(pending, intent, 99.9, now_ms=2000) == ""
    assert "already_crossed" in _pending_entry_preflight_reason(
        pending, intent, 100.0, now_ms=2000
    )
    assert _pending_entry_preflight_reason(pending, intent, 96.9, now_ms=2000) == "long_setup_invalidated_before_trigger"
    assert _pending_entry_preflight_reason(pending, intent, 99.9, now_ms=5000) == "pending_signal_expired"

    short_intent = SimpleNamespace(signal_id="ETHUSDT:1h:SUPER_AO:1000", side="SELL")
    short_pending = {
        "state": "VALIDATED",
        "signal": {
            "signal_id": short_intent.signal_id,
            "side": "SELL",
            "trigger_price": 200.0,
            "protective_reference": 203.0,
            "expires_at_ms": 5000,
        },
    }
    assert _pending_entry_preflight_reason(short_pending, short_intent, 200.1, now_ms=2000) == ""
    assert "already_crossed" in _pending_entry_preflight_reason(
        short_pending, short_intent, 199.9, now_ms=2000
    )
    assert _pending_entry_preflight_reason(short_pending, short_intent, 203.1, now_ms=2000) == "short_setup_invalidated_before_trigger"


def test_scanner_reports_all_ten_evaluated_symbols_and_both_directions(monkeypatch):
    monkeypatch.setenv("WILLIAMS_SIGNAL_PROFILE", "TC2_THREE_WISE_MEN")
    scanner = FuturesWilliamsScanner(object(), interval="1h")
    symbols = list(DEFAULT_FUTURES_SYMBOLS)
    scanner.universe = lambda limit=10: symbols
    scanner.analyse = lambda symbol: [
        SimpleNamespace(
            symbol=symbol,
            side="LONG",
            signal=SimpleNamespace(signal_bar_time_ms=100),
            score=60.0,
        ),
        SimpleNamespace(
            symbol=symbol,
            side="SHORT",
            signal=SimpleNamespace(signal_bar_time_ms=101),
            score=61.0,
        ),
    ]

    candidates = scanner.scan()

    assert len(scanner.symbols) == 10
    assert scanner.last_scan_status["state"] == "COMPLETE"
    assert scanner.last_scan_status["configured_symbols"] == 10
    assert scanner.last_scan_status["evaluated_symbols"] == 10
    assert {candidate.side for candidate in candidates} == {"LONG", "SHORT"}
    assert candidates[0].score == 61.0
    assert len(candidates) == 20
