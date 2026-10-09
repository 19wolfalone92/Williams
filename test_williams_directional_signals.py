import pandas as pd

from campaign_model import SignalRole, SignalSpec, SignalType
from digital_williams_core import DigitalWilliamsCore
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
