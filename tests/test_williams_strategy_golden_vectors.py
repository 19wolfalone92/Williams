from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from campaign_engine import CampaignEngine
from campaign_model import SignalRole, SignalSpec, SignalType
from pending_signal import PendingSignal, default_expiry_ms
from strategy import (
    calculate_indicators,
    canonical_williams_environment_blockers,
    canonical_williams_parameter_blockers,
    config_from_env,
)
from trading_config import TradingConfig, execution_timeframe_blocker
from williams_signals import _timeframe_ms
from trading_config import TradingConfig
from williams_signals import (
    _invalidation_intact,
    _trigger_unbroken,
    extract_long_signal_specs,
)


ROOT = Path(__file__).resolve().parents[1]
GOLDEN_PATH = ROOT / "app" / "src" / "test" / "resources" / "williams_strategy_golden_vectors.json"


def _golden():
    return json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))


def _frame(medians, highs=None, lows=None, times=None):
    medians = np.asarray(medians, dtype=float)
    highs = np.asarray(highs if highs is not None else medians + 0.1, dtype=float)
    lows = np.asarray(lows if lows is not None else medians - 0.1, dtype=float)
    closes = (highs + lows) / 2.0
    index = times if times is not None else pd.date_range(
        "2026-01-01T00:00:00Z", periods=len(medians), freq="h"
    )
    return pd.DataFrame(
        {
            "open": closes,
            "high": highs,
            "low": lows,
            "close": closes,
            "volume": np.ones(len(medians), dtype=float),
        },
        index=index,
    )



def test_invalid_ohlc_cannot_create_zero_price_indicators_or_fractals():
    frame = _frame(np.linspace(10.0, 20.0, 160))
    frame.loc[frame.index[100], ["high", "low"]] = [0.0, 0.0]
    frame.loc[frame.index[101], "close"] = 0.0

    indicators = calculate_indicators(frame, config_from_env({}))

    assert pd.isna(indicators["high"].iloc[100])
    assert pd.isna(indicators["low"].iloc[100])
    assert pd.isna(indicators["ao"].iloc[100])
    assert not bool(indicators["fractal_up"].iloc[100])
    assert not bool(indicators["fractal_down"].iloc[100])
    assert pd.isna(indicators["close"].iloc[101])
    assert not bool(indicators["long_fractal_signal"].iloc[101])
    assert not bool(indicators["short_fractal_signal"].iloc[101])


def test_smma_fails_closed_on_invalid_seed_and_does_not_silently_reseed_after_gap():
    from strategy import smma

    index = pd.RangeIndex(8)
    invalid_seed = pd.Series([1.0, np.nan, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0], index=index)
    assert smma(invalid_seed, 3).isna().all()

    gap_after_seed = pd.Series([1.0, 2.0, 3.0, 4.0, np.nan, 6.0, 7.0, 8.0], index=index)
    result = smma(gap_after_seed, 3)
    assert result.iloc[2] == pytest.approx(2.0)
    assert result.iloc[3] == pytest.approx((2.0 * 2.0 + 4.0) / 3.0)
    assert result.iloc[4:].isna().all()


def test_python_alligator_and_ao_ac_match_shared_golden_vectors():
    fixture = _golden()
    medians = fixture["alligator"]["medianPrices"]
    frame = _frame(medians)
    ind = calculate_indicators(frame, config_from_env({}))

    for expected in fixture["alligator"]["expected"]:
        series = ind[f"{expected['series']}_shifted"]
        if expected["value"] is None:
            assert pd.isna(series.iloc[expected["index"]])
        else:
            assert series.iloc[expected["index"]] == pytest.approx(expected["value"], abs=1e-9)

    oscillator = fixture["aoAc"]
    ind = calculate_indicators(_frame(oscillator["medianPrices"]), config_from_env({}))
    for expected in oscillator["aoExpected"]:
        assert ind["ao"].iloc[expected["index"]] == pytest.approx(expected["value"], abs=1e-9)
    for expected in oscillator["acExpected"]:
        assert ind["ac"].iloc[expected["index"]] == pytest.approx(expected["value"], abs=1e-9)


def test_fractal_events_are_stamped_on_confirmation_not_center_and_do_not_repaint():
    fixture = _golden()["fractals"]

    strict = fixture["strictUp"]
    strict_frame = _frame(
        np.array(strict["highs"], dtype=float),
        highs=strict["highs"],
        lows=np.array(strict["highs"], dtype=float) - 2.0,
    )
    prefix = calculate_indicators(strict_frame.iloc[: strict["throughIndexBeforeConfirmation"] + 1], config_from_env({}))
    assert not prefix["fractal_up"].any()

    full = calculate_indicators(strict_frame, config_from_env({}))
    center = strict["centerIndex"]
    confirmation = strict["expectedConfirmationIndex"]
    assert not bool(full["fractal_up"].iloc[center])
    assert bool(full["fractal_up"].iloc[confirmation])
    assert full["confirmed_up_center_index"].iloc[confirmation] == center
    assert full["confirmed_up_level"].iloc[confirmation] == strict["expectedLevel"]

    tied = fixture["equalRightHighExtendsConfirmation"]
    tied_frame = _frame(
        tied["highs"],
        highs=tied["highs"],
        lows=np.array(tied["highs"], dtype=float) - 2.0,
    )
    pre_tie_confirmation = calculate_indicators(
        tied_frame.iloc[: tied["throughIndexBeforeConfirmation"] + 1],
        config_from_env({}),
    )
    assert not pre_tie_confirmation["fractal_up"].any()
    full_tied = calculate_indicators(tied_frame, config_from_env({}))
    assert not bool(full_tied["fractal_up"].iloc[tied["throughIndexBeforeConfirmation"]])
    assert bool(full_tied["fractal_up"].iloc[tied["expectedConfirmationIndex"]])
    assert full_tied["confirmed_up_center_index"].iloc[tied["expectedConfirmationIndex"]] == tied["centerIndex"]

    cancelled = fixture["strictNewHighCancelsCandidate"]
    cancel_frame = _frame(
        cancelled["highs"],
        highs=cancelled["highs"],
        lows=np.array(cancelled["highs"], dtype=float) - 2.0,
    )
    cancelled_ind = calculate_indicators(cancel_frame, config_from_env({}))
    assert not bool(cancelled_ind["fractal_up"].any())
    assert not cancelled_ind["confirmed_up_level"].notna().any()


def test_wm2_third_ao_bar_is_independent_of_fractal_and_not_every_later_bar():
    # Acceleration creates a monotonic rising AO series but no strict price
    # fractal. This is deliberately a signal identity test, not a PnL test.
    prices = [(i + 2.0) ** 2 for i in range(60)]
    ind = calculate_indicators(_frame(prices), config_from_env({}))
    wm2 = ind["long_super_ao_signal"]
    assert wm2.any()
    candidates = ind.loc[wm2]
    assert not candidates["long_fractal_outside"].any()
    for idx in candidates.index:
        assert ind.loc[idx, "ao_green_streak"] == 3
    assert not (ind["ao_green_streak"] > 3).where(ind["long_super_ao_signal"], False).any()


def test_pending_trigger_cannot_be_rearmed_after_cross_or_structural_breach():
    safe = pd.DataFrame({"high": [10.0, 10.05, 10.05], "low": [9.0, 9.5, 9.4]})
    assert _trigger_unbroken(safe, 0, side="LONG", trigger_price=10.1)
    assert _invalidation_intact(safe, 0, side="LONG", protective_level=9.0)

    crossed = safe.copy()
    crossed.loc[1, "high"] = 10.2
    assert not _trigger_unbroken(crossed, 0, side="LONG", trigger_price=10.1)

    breached = safe.copy()
    breached.loc[1, "low"] = 8.9
    assert not _invalidation_intact(breached, 0, side="LONG", protective_level=9.0)

    touched_stop = safe.copy()
    touched_stop.loc[1, "low"] = 9.0
    assert not _invalidation_intact(touched_stop, 0, side="LONG", protective_level=9.0)


def test_signal_extractor_blocks_expired_wm2_and_accepts_independent_live_wm2():
    fixture = _golden()["causalEntry"]
    signal_index = 138
    count = 140
    times = pd.date_range("2026-01-01T00:00:00Z", periods=count, freq="h")
    high = np.full(count, 99.0)
    low = np.full(count, 95.0)
    close = np.full(count, 98.0)
    high[signal_index] = 100.0
    low[signal_index] = 90.0
    close[signal_index] = 98.0
    ind = pd.DataFrame(
        {
            "high": high,
            "low": low,
            "close": close,
            "teeth_shifted": np.full(count, 94.0),
            "ao_green_streak": np.zeros(count, dtype=int),
            "bullish_alligator": np.zeros(count, dtype=bool),
            "alligator_awake": np.zeros(count, dtype=bool),
        },
        index=times,
    )
    ind.loc[times[signal_index], "ao_green_streak"] = 3
    specs = extract_long_signal_specs("BTCUSDT", ind, timeframe="1h", tick_size=0.1)
    wm2 = [s for s in specs if s.signal_type == SignalType.SUPER_AO]
    assert len(wm2) == 1
    assert wm2[0].side == "BUY"
    assert wm2[0].trigger_price == pytest.approx(100.1)
    assert wm2[0].confirmation_time_ms > wm2[0].signal_bar_time_ms

    stale = pd.concat(
        [
            ind,
            pd.DataFrame(
                {
                    "high": [99.0, 99.0, 99.0],
                    "low": [95.0, 95.0, 95.0],
                    "close": [98.0, 98.0, 98.0],
                    "teeth_shifted": [94.0, 94.0, 94.0],
                    "ao_green_streak": [0, 0, 0],
                    "bullish_alligator": [False, False, False],
                    "alligator_awake": [False, False, False],
                },
                index=pd.date_range(times[-1] + pd.Timedelta(hours=1), periods=3, freq="h"),
            ),
        ]
    )
    assert not any(
        s.signal_type == SignalType.SUPER_AO
        for s in extract_long_signal_specs("BTCUSDT", stale, timeframe="1h", tick_size=0.1)
    )


def test_first_campaign_signal_uses_true_confirmation_chronology():
    wm3 = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY,
        timeframe="1h",
        signal_bar_time_ms=1_000,
        confirmation_time_ms=30_000,
        trigger_price=101.0,
        protective_reference=95.0,
        created_at_ms=31_000,
    )
    wm2 = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.SUPER_AO,
        role=SignalRole.ENTRY,
        timeframe="1h",
        signal_bar_time_ms=12_000,
        trigger_price=102.0,
        protective_reference=96.0,
        created_at_ms=32_000,
    )
    assert CampaignEngine.choose_initial_signal([wm3, wm2]).signal_type == SignalType.SUPER_AO


def test_live_williams_profile_mismatch_is_blocked_and_timeframes_are_separated():
    assert canonical_williams_parameter_blockers(config_from_env({})) == []
    assert canonical_williams_parameter_blockers({**config_from_env({}), "ao_slow": 35})
    assert canonical_williams_environment_blockers({"AO_SLOW": "not-a-number"})
    assert canonical_williams_environment_blockers({"AO_SLOW": "35"})

    config = TradingConfig.from_env({})
    assert config.execution_timeframe == "1h"
    assert config.structural_timeframes == ("1d", "4h", "1h", "15m", "5m")
    assert TradingConfig.from_env({"EXECUTION_TIMEFRAME": "5m"}).execution_timeframe == "5m"




def _pending_test_signal(*, timeframe="1h", confirmation_time_ms=3_600_000, expires_at_ms=0):
    return SignalSpec.new(
        symbol="BTCUSDT",
        side="LONG",
        signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY,
        timeframe=timeframe,
        signal_bar_time_ms=0,
        confirmation_time_ms=confirmation_time_ms,
        expires_at_ms=expires_at_ms,
        trigger_price=101.0,
        protective_reference=99.0,
    )


def test_pending_signal_unknown_timeframe_fails_closed_without_m5_fallback(monkeypatch):
    monkeypatch.delenv("WILLIAMS_PENDING_FRACTAL_BARS", raising=False)
    signal = _pending_test_signal(timeframe="unknown-frame")
    pending = PendingSignal.from_spec(signal)
    assert default_expiry_ms(signal) == 0
    assert pending.expires_at_ms == 0
    assert pending.is_expired(now_ms=1_000_000_000)
    assert not pending.actionable(now_ms=1_000_000_000)


def test_pending_signal_missing_confirmation_time_fails_closed():
    signal = _pending_test_signal(confirmation_time_ms=0)
    pending = PendingSignal.from_spec(signal)
    assert pending.expires_at_ms == 0
    assert not pending.actionable(now_ms=1)


def test_pending_signal_expiry_boundary_is_not_actionable():
    signal = _pending_test_signal(
        timeframe="1h",
        confirmation_time_ms=3_600_000,
        expires_at_ms=7_200_000,
    )
    pending = PendingSignal.from_spec(signal)
    assert pending.expires_at_ms == 7_200_000
    assert pending.actionable(now_ms=7_199_999)
    assert pending.is_expired(now_ms=7_200_000)
    assert not pending.actionable(now_ms=7_200_000)


def test_pending_signal_explicit_expiry_is_not_replaced_by_default_ttl(monkeypatch):
    monkeypatch.setenv("WILLIAMS_PENDING_FRACTAL_BARS", "99")
    signal = _pending_test_signal(
        timeframe="1h",
        confirmation_time_ms=3_600_000,
        expires_at_ms=123,
    )
    assert default_expiry_ms(signal) == 123
    pending = PendingSignal.from_spec(signal)
    assert pending.expires_at_ms == 123
    assert not pending.actionable(now_ms=123)



@pytest.mark.parametrize("raw_ttl", ["", "not-an-integer", "0", "-3"])
def test_pending_signal_malformed_or_nonpositive_ttl_fails_closed(monkeypatch, raw_ttl):
    monkeypatch.setenv("WILLIAMS_PENDING_FRACTAL_BARS", raw_ttl)
    signal = _pending_test_signal(timeframe="1h", confirmation_time_ms=3_600_000)
    pending = PendingSignal.from_spec(signal)
    assert pending.expires_at_ms == 0
    assert not pending.actionable(now_ms=3_600_001)



@pytest.mark.parametrize("timeframe", ["5m", "15m", "", "M5", "unknown"])
def test_canonical_environment_blocks_non_h1_execution_timeframe(timeframe):
    blockers = canonical_williams_environment_blockers({"EXECUTION_TIMEFRAME": timeframe})
    assert any("EXECUTION_TIMEFRAME" in blocker and "H1" in blocker for blocker in blockers)


def test_canonical_environment_allows_h1_and_default():
    assert not canonical_williams_environment_blockers({})
    assert not canonical_williams_environment_blockers({"EXECUTION_TIMEFRAME": "1h"})



@pytest.mark.parametrize("timeframe", ["5m", "15m", "", "unknown"])
def test_trading_config_execution_timeframe_guard_blocks_non_h1(timeframe):
    assert execution_timeframe_blocker({"EXECUTION_TIMEFRAME": timeframe}) is not None
    assert TradingConfig.from_env({"EXECUTION_TIMEFRAME": timeframe}).execution_timeframe == timeframe.lower()


def test_trading_config_execution_timeframe_guard_accepts_h1_or_default():
    assert execution_timeframe_blocker({}) is None
    assert execution_timeframe_blocker({"EXECUTION_TIMEFRAME": "1h"}) is None


def test_signal_timestamp_math_has_no_unknown_timeframe_m5_fallback():
    assert _timeframe_ms("1h") == 3_600_000
    assert _timeframe_ms("5m") == 300_000
    assert _timeframe_ms("unknown") == 0
