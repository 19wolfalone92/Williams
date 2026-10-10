import pandas as pd
import pytest
from types import SimpleNamespace

from campaign_model import SignalRole, SignalSpec, SignalType
from campaign_engine import CampaignEngine
from digital_williams_core import DigitalWilliamsCore
from portfolio_trader import MultiPositionTrader
from strategy import calculate_indicators, config_from_env
from williams_signals import (
    _angulation,
    _dedupe_and_sort_signal_specs,
    extract_long_signal_specs,
    extract_short_signal_specs,
)


@pytest.fixture(autouse=True)
def _enable_approximate_angulation_for_detector_tests(monkeypatch):
    # These tests validate the deterministic geometry only. Production defaults
    # to blocking WM1 until the approximation is source-verified.
    monkeypatch.setenv("WILLIAMS_ALLOW_APPROXIMATE_ANGULATION", "true")


def frame():
    rows = []
    for i in range(12):
        high = 101.0 + i * 1.5
        low = high - 2.0
        rows.append({
            "open": high - 0.5,
            "high": high,
            "low": low,
            "close": high - 0.7,
            "jaw_shifted": 100.0,
            "teeth_shifted": 100.0,
            "lips_shifted": 100.0,
            "bullish_reversal_bar": False,
            "bearish_reversal_bar": False,
            "ao_green_streak": 0,
            "ao_red_streak": 0,
            "long_fractal_outside": False,
            "short_fractal_outside": False,
            "confirmed_up_level": float("nan"),
            "confirmed_down_level": float("nan"),
            "fractal_up": False,
            "fractal_down": False,
            "fractal_right_bars": 2,
            "bullish_alligator": False,
            "bearish_alligator": True,
            "alligator_awake": True,
        })
    return pd.DataFrame(rows)


def test_wm1_is_blocked_by_default_when_angulation_is_only_an_approximation(monkeypatch):
    monkeypatch.delenv("WILLIAMS_ALLOW_APPROXIMATE_ANGULATION", raising=False)
    data = frame()
    data.loc[8, "bearish_reversal_bar"] = True

    signals = extract_short_signal_specs(
        "BTCUSDT", data, timeframe="1h", tick_size=0.1,
        wave_invalidation_price=115.0,
    )
    assert not [s for s in signals if s.signal_type is SignalType.REVERSAL]


def test_short_reversal_uses_low_minus_tick_and_high_as_invalidation():
    data = frame()
    data.loc[8, "bearish_reversal_bar"] = True
    # Keep all post-signal highs below the structural invalidation and all
    # post-signal lows above the untriggered SELL STOP.
    data.loc[9, ["open", "high", "low", "close"]] = [112.2, 112.8, 111.8, 112.4]
    data.loc[10, ["open", "high", "low", "close"]] = [112.0, 112.6, 111.6, 112.2]
    data.loc[11, ["open", "high", "low", "close"]] = [111.8, 112.4, 111.4, 112.0]

    signals = extract_short_signal_specs(
        "BTCUSDT", data, timeframe="5m", tick_size=0.1,
        wave_invalidation_price=115.0,
    )
    reversal = [s for s in signals if s.signal_type is SignalType.REVERSAL]
    assert reversal
    signal = reversal[0]
    assert signal.direction == "SHORT"
    assert signal.side == "SELL"
    assert abs(signal.trigger_price - (float(data.loc[8, "low"]) - 0.1)) < 1e-9
    assert signal.protective_reference == float(data.loc[8, "high"])
    assert signal.invalidation_price == signal.protective_reference
    assert signal.wave_invalidation_price == 115.0
    assert signal.alligator_bearish is True
    assert signal.signal_id.endswith(f":SHORT:{signal.signal_bar_time_ms}")


def test_short_super_ao_and_fractal_use_bearish_trigger_geometry():
    data = frame()
    data.loc[8, "ao_red_streak"] = 3
    data.loc[7, "short_fractal_outside"] = False
    # WM2 must remain independently detectable without a separate Balance-Line/fractal gate.
    # The WM3 center is just below the preceding low but remains above the
    # WM2 stop-entry trigger, so neither trigger was crossed before arming.
    data.loc[7, ["open", "high", "low", "close"]] = [111.8, 112.4, 111.3, 111.7]
    data.loc[8, ["open", "high", "low", "close"]] = [112.4, 113.0, 111.0, 112.0]
    data.loc[9, ["open", "high", "low", "close"]] = [111.5, 112.8, 110.95, 111.8]
    data.loc[10, ["open", "high", "low", "close"]] = [111.4, 112.6, 111.1, 111.5]
    data.loc[11, ["open", "high", "low", "close"]] = [111.3, 112.4, 111.2, 111.5]
    data.loc[9, "fractal_down"] = True
    data.loc[11, "confirmed_down_level"] = float(data.loc[9, "low"])
    # A bearish Teeth context places the trigger below the Alligator mouth.
    data.loc[11, "teeth_shifted"] = 120.0

    signals = extract_short_signal_specs(
        "BTCUSDT", data, timeframe="5m", tick_size=0.1
    )
    by_type = {s.signal_type: s for s in signals}
    assert SignalType.SUPER_AO in by_type
    assert by_type[SignalType.SUPER_AO].direction == "SHORT"
    assert by_type[SignalType.SUPER_AO].role is SignalRole.ENTRY
    assert by_type[SignalType.SUPER_AO].trigger_price < float(data.loc[8, "low"])
    assert SignalType.FRACTAL in by_type
    fractal = by_type[SignalType.FRACTAL]
    assert fractal.direction == "SHORT"
    assert fractal.role is SignalRole.ENTRY
    assert fractal.trigger_price < float(data.loc[9, "low"])
    assert fractal.trigger_price < float(data.loc[11, "teeth_shifted"])
    assert fractal.protective_reference == pytest.approx(max(data.loc[9:11, "high"]) + 0.1)


def test_short_signal_is_not_armed_after_price_has_already_broken_trigger():
    data = frame()
    data.loc[8, "bearish_reversal_bar"] = True
    close = float(data.loc[8, "low"]) - 0.5
    data.loc[11, ["open", "high", "low", "close"]] = [close + 0.1, close + 0.5, close - 0.2, close]

    signals = extract_short_signal_specs(
        "BTCUSDT", data, timeframe="5m", tick_size=0.1
    )
    assert not [s for s in signals if s.signal_type is SignalType.REVERSAL]


def test_initial_signal_selector_accepts_explicit_short_direction():
    short = SignalSpec.new(
        symbol="BTCUSDT",
        side="SELL",
        direction="SHORT",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=10,
        trigger_price=99.9,
        protective_reference=101.0,
    )
    selected = DigitalWilliamsCore().select_initial([short])
    assert selected is short


def test_long_super_ao_and_fractal_are_independent_initial_entry_candidates():
    data = frame()
    data.loc[8, "ao_green_streak"] = 3
    data.loc[7, "long_fractal_outside"] = True
    # WM3's high is above the prior bar by less than one tick, so the WM2
    # trigger is still unbroken and both structures remain valid.
    data.loc[8, ["open", "high", "low", "close"]] = [112.5, 113.0, 111.0, 112.4]
    data.loc[9, ["open", "high", "low", "close"]] = [112.5, 113.05, 111.5, 112.8]
    data.loc[10, ["open", "high", "low", "close"]] = [112.4, 112.95, 111.6, 112.7]
    data.loc[11, ["open", "high", "low", "close"]] = [112.2, 112.85, 111.7, 112.5]
    data.loc[9, "fractal_up"] = True
    data.loc[11, "confirmed_up_level"] = float(data.loc[9, "high"])
    data.loc[11, "teeth_shifted"] = 100.0

    signals = extract_long_signal_specs(
        "BTCUSDT", data, timeframe="5m", tick_size=0.1
    )
    by_type = {signal.signal_type: signal for signal in signals}
    assert SignalType.SUPER_AO in by_type
    assert SignalType.FRACTAL in by_type
    assert by_type[SignalType.SUPER_AO].role is SignalRole.ENTRY
    assert by_type[SignalType.FRACTAL].role is SignalRole.ENTRY
    assert by_type[SignalType.FRACTAL].protective_reference == pytest.approx(
        min(data.loc[9:11, "low"]) - 0.1
    )
    assert len([signal for signal in signals if signal.role is SignalRole.ENTRY]) == 2
    # The fixture uses small synthetic row indices as candle timestamps.
    selected = CampaignEngine.choose_initial_signal(signals, now_ms=1)
    assert selected is by_type[SignalType.SUPER_AO]
    # Either signal family is independently eligible; WM2 is first by confirmation time here.


def test_spot_signal_deserialization_preserves_confirmation_time_and_zero_index():
    # A confirmed WM3 has an older center candle than the time at which it is
    # actionable. Deserialize it without backdating confirmation or losing index 0.
    fractal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        direction="LONG",
        signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY,
        timeframe="1h",
        signal_bar_time_ms=100,
        confirmation_time_ms=300,
        trigger_price=110.0,
        protective_reference=95.0,
        invalidation_price=95.0,
        wave_invalidation_price=90.0,
        source_candle_index=0,
        alligator_bearish=True,
        expires_at_ms=10**15,
    ).to_dict()
    reversal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        direction="LONG",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="1h",
        signal_bar_time_ms=200,
        confirmation_time_ms=201,
        trigger_price=105.0,
        protective_reference=98.0,
        expires_at_ms=10**15,
    ).to_dict()
    selection = SimpleNamespace(
        candidate=SimpleNamespace(campaign_signal_specs=[fractal, reversal])
    )

    selected = MultiPositionTrader._selection_signal(selection)
    assert selected is not None
    assert selected.signal_type is SignalType.REVERSAL

    trader = object.__new__(MultiPositionTrader)
    parsed = trader._signal_specs(selection)
    restored_fractal = next(item for item in parsed if item.signal_type is SignalType.FRACTAL)
    assert restored_fractal.signal_bar_time_ms == 100
    assert restored_fractal.confirmation_time_ms == 300
    assert restored_fractal.source_candle_index == 0
    assert restored_fractal.invalidation_price == 95.0
    assert restored_fractal.wave_invalidation_price == 90.0
    assert restored_fractal.alligator_bearish is True


def test_fractal_signal_uses_configured_confirmation_delay_not_hardcoded_two_bars():
    data = frame()
    data["fractal_right_bars"] = 3
    data.loc[8, "fractal_down"] = True
    data.loc[9, ["open", "high", "low", "close"]] = [112.0, 112.8, 111.5, 112.1]
    data.loc[10, ["open", "high", "low", "close"]] = [111.8, 112.6, 111.2, 111.6]
    data.loc[11, ["open", "high", "low", "close"]] = [111.5, 112.4, 111.1, 111.4]
    data.loc[11, "confirmed_down_level"] = float(data.loc[8, "low"])
    data.loc[11, "teeth_shifted"] = 120.0

    signals = extract_short_signal_specs(
        "BTCUSDT", data, timeframe="5m", tick_size=0.1
    )
    fractals = [signal for signal in signals if signal.signal_type is SignalType.FRACTAL]
    assert len(fractals) == 1
    assert fractals[0].protective_reference == float(data.loc[8, "high"])
    assert fractals[0].trigger_price < float(data.loc[8, "low"])
    assert fractals[0].signal_bar_time_ms == 8
    assert fractals[0].confirmation_time_ms == 11
    assert fractals[0].expires_at_ms == 11 + 9 * 300_000


def test_strict_short_signal_requires_sell_fractal_outside_teeth():
    # A falling trend plus one bearish reversal can satisfy the short Wise-Man
    # count when MIN_WISE_MEN_CONFIRMATIONS=1. Without the sell-fractal/Teeth
    # gate, that reversal alone incorrectly became a strict short signal.
    rows = []
    for i in range(300):
        close = 300.0 - i
        rows.append({
            "open": close + 0.2,
            "high": close + 0.5,
            "low": close - 0.5,
            "close": close,
            "volume": 100.0 + (i % 7),
        })
    # Keep the reversal bar's low above the preceding declining lows so it
    # cannot manufacture a sell fractal; then break its low on the next bar.
    rows[297] = {"open": 5.0, "high": 24.0, "low": 4.0, "close": 6.0, "volume": 100.0}
    rows[298] = {"open": 3.0, "high": 4.0, "low": 1.5, "close": 2.0, "volume": 101.0}
    rows[299] = {"open": 1.8, "high": 2.2, "low": 0.5, "close": 1.0, "volume": 102.0}
    data = pd.DataFrame(rows, index=pd.date_range("2025-01-01", periods=len(rows), freq="5min", tz="UTC"))
    cfg = config_from_env({
        "ALLIGATOR_JAW": "13", "ALLIGATOR_TEETH": "8", "ALLIGATOR_LIPS": "5",
        "JAW_SHIFT": "8", "TEETH_SHIFT": "5", "LIPS_SHIFT": "3",
        "AO_FAST": "5", "AO_SLOW": "34", "AC_PERIOD": "5",
        "FRACTAL_LEFT": "2", "FRACTAL_RIGHT": "2", "SUPER_AO_BARS": "3",
        "MIN_WISE_MEN_CONFIRMATIONS": "1", "ALLOW_COUNTERTREND_WISE_MAN": "false",
        "MIN_ALLIGATOR_SPREAD_PCT": "0.0001",
    })
    result = calculate_indicators(data, cfg)
    latest = result.iloc[-1]
    assert bool(latest["bearish_alligator"])
    assert bool(latest["short_wise_reversal_entry"])
    assert not bool(latest["short_fractal_outside"])
    assert not bool(latest["short_signal"])


def test_first_live_wise_man_signal_can_start_long_or_short_campaign():
    from futures_runtime import choose_initial_williams_signal

    for direction, side in (("LONG", "BUY"), ("SHORT", "SELL")):
        wm2 = SignalSpec.new(
            symbol="BTCUSDT",
            side=side,
            direction=direction,
            signal_type=SignalType.SUPER_AO,
            role=SignalRole.ADD_ON,
            timeframe="5m",
            signal_bar_time_ms=100,
            trigger_price=101.0 if direction == "LONG" else 99.0,
            protective_reference=99.0 if direction == "LONG" else 101.0,
            expires_at_ms=10_000,
        )
        selected = choose_initial_williams_signal([wm2], direction, now_ms=1_000)
        assert selected is not None
        assert selected.signal_type is SignalType.SUPER_AO
        assert selected.role is SignalRole.ENTRY
        assert selected.direction == direction


def test_expired_wise_man_signal_cannot_start_campaign():
    from futures_runtime import choose_initial_williams_signal

    expired = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        direction="LONG",
        signal_type=SignalType.FRACTAL,
        role=SignalRole.ADD_ON,
        timeframe="5m",
        signal_bar_time_ms=100,
        trigger_price=101.0,
        protective_reference=99.0,
        expires_at_ms=999,
    )
    assert choose_initial_williams_signal([expired], "LONG", now_ms=1_000) is None


def test_long_reversal_is_rejected_if_later_candle_crossed_trigger_or_stop():
    data = frame()
    data.loc[8, "bullish_reversal_bar"] = True
    # The close remains below the BUY STOP, but a later high already crossed it.
    data.loc[11, ["open", "high", "low", "close"]] = [112.0, 113.5, 111.5, 112.5]
    signals = extract_long_signal_specs("BTCUSDT", data, timeframe="5m", tick_size=0.1)
    assert not [s for s in signals if s.signal_type is SignalType.REVERSAL]

    data = frame()
    data.loc[8, "bullish_reversal_bar"] = True
    # Structural low was breached after the reversal, so the setup is invalid.
    data.loc[10, ["open", "high", "low", "close"]] = [112.0, 113.0, 110.5, 112.0]
    data.loc[11, ["open", "high", "low", "close"]] = [112.0, 112.8, 111.5, 112.2]
    signals = extract_long_signal_specs("BTCUSDT", data, timeframe="5m", tick_size=0.1)
    assert not [s for s in signals if s.signal_type is SignalType.REVERSAL]


def test_short_reversal_is_rejected_if_later_candle_crossed_trigger_or_stop():
    data = frame()
    data.loc[8, "bearish_reversal_bar"] = True
    data.loc[9, ["open", "high", "low", "close"]] = [112.2, 112.8, 110.5, 112.0]
    data.loc[10, ["open", "high", "low", "close"]] = [112.0, 112.6, 111.0, 112.2]
    data.loc[11, ["open", "high", "low", "close"]] = [111.8, 112.4, 111.4, 112.0]
    signals = extract_short_signal_specs("BTCUSDT", data, timeframe="5m", tick_size=0.1)
    assert not [s for s in signals if s.signal_type is SignalType.REVERSAL]

    data = frame()
    data.loc[8, "bearish_reversal_bar"] = True
    # Structural high was breached after the reversal.
    data.loc[9, ["open", "high", "low", "close"]] = [112.2, 113.5, 111.8, 112.4]
    data.loc[10, ["open", "high", "low", "close"]] = [112.0, 112.6, 111.6, 112.2]
    data.loc[11, ["open", "high", "low", "close"]] = [111.8, 112.4, 111.4, 112.0]
    signals = extract_short_signal_specs("BTCUSDT", data, timeframe="5m", tick_size=0.1)
    assert not [s for s in signals if s.signal_type is SignalType.REVERSAL]



def test_short_reversal_has_confirmation_timestamp_and_full_pending_lifetime():
    data = frame()
    data.loc[8, "bearish_reversal_bar"] = True
    data.loc[9, [ "open", "high", "low", "close" ]] = [112.2, 112.8, 111.8, 112.4]
    data.loc[10, [ "open", "high", "low", "close" ]] = [112.0, 112.6, 111.6, 112.2]
    data.loc[11, [ "open", "high", "low", "close" ]] = [111.8, 112.4, 111.4, 112.0]
    signals = extract_short_signal_specs("BTCUSDT", data, timeframe="5m", tick_size=0.1)
    signal = next(s for s in signals if s.signal_type is SignalType.REVERSAL)
    assert signal.confirmation_time_ms == signal.signal_bar_time_ms
    assert signal.expires_at_ms == signal.confirmation_time_ms + 3 * 300_000


def test_signal_order_uses_confirmation_time_not_source_bar_time():
    # A fractal source bar can be older but becomes actionable only after
    # right-side confirmation. A reversal confirmed earlier must be selected first.
    reversal = SignalSpec.new(
        symbol="BTCUSDT", side="SELL", direction="SHORT",
        signal_type=SignalType.REVERSAL, role=SignalRole.ENTRY,
        timeframe="1h", signal_bar_time_ms=200, confirmation_time_ms=200,
        trigger_price=99.0, protective_reference=101.0,
    )
    fractal = SignalSpec.new(
        symbol="BTCUSDT", side="SELL", direction="SHORT",
        signal_type=SignalType.FRACTAL, role=SignalRole.ADD_ON,
        timeframe="1h", signal_bar_time_ms=100, confirmation_time_ms=300,
        trigger_price=98.0, protective_reference=102.0,
    )
    ordered = _dedupe_and_sort_signal_specs([fractal, reversal])
    assert ordered == [reversal, fractal]


def test_equal_high_fractal_waits_for_second_strictly_lower_high():
    # Trading Chaos 2, Figure 11.1 D: the equal high does not count as one
    # of the two required lower highs, so confirmation needs six bars here.
    from strategy import calculate_indicators, config_from_env

    data = pd.DataFrame([
        {"open": 6.0, "high": 7.0, "low": 5.0, "close": 6.0, "volume": 10.0},
        {"open": 7.0, "high": 8.0, "low": 5.5, "close": 7.0, "volume": 10.0},
        {"open": 9.0, "high": 10.0, "low": 6.0, "close": 9.0, "volume": 10.0},
        {"open": 8.0, "high": 9.0, "low": 6.2, "close": 8.0, "volume": 10.0},
        {"open": 9.0, "high": 10.0, "low": 6.5, "close": 9.0, "volume": 10.0},
        {"open": 7.0, "high": 8.0, "low": 6.7, "close": 7.5, "volume": 10.0},
    ])
    indicators = calculate_indicators(data, config_from_env({"FRACTAL_LEFT": "2", "FRACTAL_RIGHT": "2"}))
    assert bool(indicators["fractal_up"].iloc[2])
    assert pd.isna(indicators["confirmed_up_level"].iloc[4])
    assert indicators["confirmed_up_level"].iloc[5] == 10.0
    assert indicators["confirmed_up_center_index"].iloc[5] == 2

    indicators["teeth_shifted"] = 9.0
    indicators["bullish_reversal_bar"] = False
    indicators["bearish_reversal_bar"] = False
    indicators["ao_green_streak"] = 0
    indicators["ao_red_streak"] = 0
    signals = extract_long_signal_specs("BTCUSDT", indicators, timeframe="5m", tick_size=0.1)
    fractals = [signal for signal in signals if signal.signal_type is SignalType.FRACTAL]
    assert len(fractals) == 1
    assert fractals[0].signal_bar_time_ms == 2
    assert fractals[0].confirmation_time_ms == 5
    assert fractals[0].protective_reference == 6.0


def test_wave_scenario_invalidation_is_separate_from_pattern_stop():
    from types import SimpleNamespace
    from market_scanner import _separate_signal_stop_levels

    pattern_stop, wave_stop = _separate_signal_stop_levels(
        {"invalidation_price": 98.0, "protective_reference": 98.0, "wave_invalidation_price": 90.0},
        SimpleNamespace(invalidation_price=92.0),
    )
    assert pattern_stop == 98.0
    assert wave_stop == 92.0


def test_signal_serialization_preserves_wave_invalidation_separately():
    from futures_runtime import signal_spec_from_dict

    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="1h",
        signal_bar_time_ms=123,
        trigger_price=105.0,
        protective_reference=100.0,
        invalidation_price=100.0,
        wave_invalidation_price=97.0,
    )
    restored = signal_spec_from_dict(signal.to_dict())
    assert restored.invalidation_price == 100.0
    assert restored.wave_invalidation_price == 97.0


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
@pytest.mark.parametrize("zero_index", [5, 6])
def test_wm3_requires_positive_finite_teeth_at_confirmation_and_current_bar(side, zero_index):
    from strategy import calculate_indicators, config_from_env

    if side == "LONG":
        rows = [
            {"open": 6.0, "high": 7.0, "low": 5.0, "close": 6.0, "volume": 10.0},
            {"open": 7.0, "high": 8.0, "low": 5.5, "close": 7.0, "volume": 10.0},
            {"open": 9.0, "high": 10.0, "low": 6.0, "close": 9.0, "volume": 10.0},
            {"open": 8.0, "high": 9.0, "low": 6.2, "close": 8.0, "volume": 10.0},
            {"open": 9.0, "high": 10.0, "low": 6.5, "close": 9.0, "volume": 10.0},
            {"open": 7.0, "high": 8.0, "low": 6.7, "close": 7.5, "volume": 10.0},
            {"open": 7.0, "high": 8.0, "low": 6.8, "close": 7.5, "volume": 10.0},
        ]
    else:
        rows = [
            {"open": 9.0, "high": 10.0, "low": 8.0, "close": 9.0, "volume": 10.0},
            {"open": 8.5, "high": 9.5, "low": 7.0, "close": 8.5, "volume": 10.0},
            {"open": 6.5, "high": 9.0, "low": 6.0, "close": 6.5, "volume": 10.0},
            {"open": 7.5, "high": 8.7, "low": 7.0, "close": 7.5, "volume": 10.0},
            {"open": 6.5, "high": 8.8, "low": 6.0, "close": 6.5, "volume": 10.0},
            {"open": 8.3, "high": 8.6, "low": 8.0, "close": 8.3, "volume": 10.0},
            {"open": 8.0, "high": 8.5, "low": 7.5, "close": 8.0, "volume": 10.0},
        ]
    ind = calculate_indicators(
        pd.DataFrame(rows),
        config_from_env({"FRACTAL_LEFT": "2", "FRACTAL_RIGHT": "2"})
    )
    ind["teeth_shifted"] = 9.0
    ind.loc[ind.index[zero_index], "teeth_shifted"] = 0.0
    ind["bullish_reversal_bar"] = False
    ind["bearish_reversal_bar"] = False
    ind["ao_green_streak"] = 0
    ind["ao_red_streak"] = 0

    if side == "LONG":
        signals = extract_long_signal_specs("BTCUSDT", ind, timeframe="5m", tick_size=0.1)
    else:
        signals = extract_short_signal_specs("BTCUSDT", ind, timeframe="5m", tick_size=0.1)
    assert not any(signal.signal_type is SignalType.FRACTAL for signal in signals)

def test_wm1_angulation_requires_steeper_price_edge_and_extreme_outside_mouth():
    data = pd.DataFrame({
        "jaw_shifted": [104.0, 103.0, 102.0, 101.0, 100.0],
        "teeth_shifted": [105.0, 104.0, 103.0, 102.0, 101.0],
        "lips_shifted": [103.0, 102.0, 101.0, 100.0, 99.0],
        "low": [106.0, 104.0, 102.0, 98.0, 90.0],
        "high": [108.0, 106.0, 104.0, 100.0, 95.0],
        "close": [107.0, 105.0, 103.0, 99.0, 94.0],
    })
    score, valid = _angulation(data, 4, side="LONG")
    assert valid
    assert score > 0.0

    # Price is moving away from Jaw, but the last low is not beyond all
    # three shifted Alligator lines; source-profile WM1 evidence must block.
    not_outside = pd.DataFrame({
        "jaw_shifted": [100.0] * 5,
        "teeth_shifted": [101.0] * 5,
        "lips_shifted": [97.0] * 5,
        "low": [100.5, 100.3, 100.1, 99.8, 99.5],
        "high": [102.0] * 5,
        "close": [101.0, 101.0, 100.8, 100.5, 100.0],
    })
    score, valid = _angulation(not_outside, 4, side="LONG")
    assert not valid
    assert score == 0.0


def test_wm1_angulation_is_directionally_symmetric_for_short():
    data = pd.DataFrame({
        "jaw_shifted": [100.0] * 5,
        "teeth_shifted": [100.5] * 5,
        "lips_shifted": [99.5] * 5,
        "low": [98.0] * 5,
        "high": [101.0, 102.0, 103.0, 104.0, 105.0],
        "close": [99.0, 100.0, 101.0, 102.0, 103.0],
    })
    score, valid = _angulation(data, 4, side="SHORT")
    assert valid
    assert score > 0.0



def test_tc2_live_decision_timeframe_is_h1_only():
    from market_scanner import MarketScanner

    assert MarketScanner._tc2_decision_timeframe_allowed("TC2_THREE_WISE_MEN", "1h")
    assert MarketScanner._tc2_decision_timeframe_allowed("TC2_THREE_WISE_MEN", "1H")
    assert not MarketScanner._tc2_decision_timeframe_allowed("TC2_THREE_WISE_MEN", "15m")
    assert not MarketScanner._tc2_decision_timeframe_allowed("TC2_THREE_WISE_MEN", "5m")
    assert MarketScanner._tc2_decision_timeframe_allowed("LEGACY", "5m")


def test_wm3_long_fractal_must_be_above_teeth_before_tick_buffer():
    data = frame()
    data.loc[7, "long_fractal_outside"] = True
    data.loc[7, [ "open", "high", "low", "close" ]] = [112.5, 113.0, 111.0, 112.4]
    data.loc[8, [ "open", "high", "low", "close" ]] = [112.5, 113.05, 111.5, 112.8]
    data.loc[9, [ "open", "high", "low", "close" ]] = [112.4, 112.95, 111.6, 112.7]
    data.loc[10, [ "open", "high", "low", "close" ]] = [112.4, 112.95, 111.6, 112.7]
    data.loc[11, [ "open", "high", "low", "close" ]] = [112.2, 112.85, 111.7, 112.5]
    data.loc[8, "fractal_up"] = True
    data.loc[10, "confirmed_up_level"] = float(data.loc[8, "high"])
    data.loc[10, "confirmed_up_center_index"] = 8
    data.loc[11, "teeth_shifted"] = 113.1

    signals = extract_long_signal_specs(
        "BTCUSDT", data, timeframe="1h", tick_size=0.1
    )
    assert not [s for s in signals if s.signal_type is SignalType.FRACTAL]


def test_wm3_short_fractal_must_be_below_teeth_before_tick_buffer():
    data = frame()
    data.loc[7, [ "open", "high", "low", "close" ]] = [111.8, 112.4, 111.3, 111.7]
    data.loc[8, [ "open", "high", "low", "close" ]] = [112.4, 113.0, 111.0, 112.0]
    data.loc[9, [ "open", "high", "low", "close" ]] = [111.5, 112.8, 110.95, 111.8]
    data.loc[10, [ "open", "high", "low", "close" ]] = [111.4, 112.6, 111.1, 111.5]
    data.loc[11, [ "open", "high", "low", "close" ]] = [111.3, 112.4, 111.2, 111.5]
    data.loc[9, "fractal_down"] = True
    data.loc[11, "confirmed_down_level"] = float(data.loc[9, "low"])
    data.loc[11, "confirmed_down_center_index"] = 9
    data.loc[11, "teeth_shifted"] = 110.9
    data.loc[11, "close"] = 111.5

    signals = extract_short_signal_specs(
        "BTCUSDT", data, timeframe="1h", tick_size=0.1
    )
    assert not [s for s in signals if s.signal_type is SignalType.FRACTAL]
