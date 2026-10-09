import pandas as pd

from campaign_model import SignalRole, SignalSpec, SignalType
from digital_williams_core import DigitalWilliamsCore
from strategy import calculate_indicators, config_from_env
from williams_signals import extract_long_signal_specs, extract_short_signal_specs


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


def test_short_reversal_uses_low_minus_tick_and_high_as_invalidation():
    data = frame()
    data.loc[8, "bearish_reversal_bar"] = True
    data.loc[11, "close"] = 115.0

    signals = extract_short_signal_specs(
        "BTCUSDT", data, timeframe="5m", tick_size=0.1
    )
    reversal = [s for s in signals if s.signal_type is SignalType.REVERSAL]
    assert reversal
    signal = reversal[0]
    assert signal.direction == "SHORT"
    assert signal.side == "SELL"
    assert abs(signal.trigger_price - (float(data.loc[8, "low"]) - 0.1)) < 1e-9
    assert signal.protective_reference == float(data.loc[8, "high"])
    assert signal.alligator_bearish is True
    assert signal.signal_id.endswith(f":SHORT:{signal.signal_bar_time_ms}")


def test_short_super_ao_and_fractal_use_bearish_trigger_geometry():
    data = frame()
    data.loc[8, "ao_red_streak"] = 3
    data.loc[7, "short_fractal_outside"] = True
    data.loc[9, "fractal_down"] = True
    data.loc[11, "confirmed_down_level"] = float(data.loc[9, "low"])
    # A bearish Teeth context places the trigger below the Alligator mouth.
    data.loc[11, "teeth_shifted"] = 120.0
    data.loc[11, "close"] = 115.0

    signals = extract_short_signal_specs(
        "BTCUSDT", data, timeframe="5m", tick_size=0.1
    )
    by_type = {s.signal_type: s for s in signals}
    assert SignalType.SUPER_AO in by_type
    assert by_type[SignalType.SUPER_AO].direction == "SHORT"
    assert by_type[SignalType.SUPER_AO].role is SignalRole.ADD_ON
    assert by_type[SignalType.SUPER_AO].trigger_price < float(data.loc[8, "low"])
    assert SignalType.FRACTAL in by_type
    fractal = by_type[SignalType.FRACTAL]
    assert fractal.direction == "SHORT"
    assert fractal.role is SignalRole.ADD_ON
    assert fractal.trigger_price < float(data.loc[9, "low"])
    assert fractal.trigger_price < float(data.loc[11, "teeth_shifted"])
    assert fractal.protective_reference == float(data.loc[9, "high"])


def test_short_signal_is_not_armed_after_price_has_already_broken_trigger():
    data = frame()
    data.loc[8, "bearish_reversal_bar"] = True
    data.loc[11, "close"] = float(data.loc[8, "low"]) - 0.5

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


def test_long_super_ao_and_fractal_are_add_ons_not_initial_entries():
    data = frame()
    data.loc[8, "ao_green_streak"] = 3
    data.loc[7, "long_fractal_outside"] = True
    data.loc[9, "fractal_up"] = True
    data.loc[11, "confirmed_up_level"] = float(data.loc[9, "high"])
    data.loc[11, "teeth_shifted"] = 100.0
    data.loc[11, "close"] = 110.0

    signals = extract_long_signal_specs(
        "BTCUSDT", data, timeframe="5m", tick_size=0.1
    )
    by_type = {signal.signal_type: signal for signal in signals}
    assert SignalType.SUPER_AO in by_type
    assert SignalType.FRACTAL in by_type
    assert by_type[SignalType.SUPER_AO].role is SignalRole.ADD_ON
    assert by_type[SignalType.FRACTAL].role is SignalRole.ADD_ON
    assert not [signal for signal in signals if signal.role is SignalRole.ENTRY]


def test_fractal_signal_uses_configured_confirmation_delay_not_hardcoded_two_bars():
    data = frame()
    data["fractal_right_bars"] = 3
    data.loc[8, "fractal_down"] = True
    data.loc[11, "confirmed_down_level"] = float(data.loc[8, "low"])
    data.loc[11, "teeth_shifted"] = 120.0
    data.loc[11, "close"] = 115.0

    signals = extract_short_signal_specs(
        "BTCUSDT", data, timeframe="5m", tick_size=0.1
    )
    fractals = [signal for signal in signals if signal.signal_type is SignalType.FRACTAL]
    assert len(fractals) == 1
    assert fractals[0].protective_reference == float(data.loc[8, "high"])
    assert fractals[0].trigger_price < float(data.loc[8, "low"])


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
