import pytest

from futures_runtime import _assert_fresh_closed_candle


def test_fresh_closed_candle_is_accepted():
    now = 1_800_000_000_000
    _assert_fresh_closed_candle("BTCUSDT", "5m", now - 300_000, now_ms=now)


@pytest.mark.parametrize("close_ms", [0, -1, 1_800_000_000_000 + 60_001])
def test_invalid_or_future_closed_candle_is_rejected(close_ms):
    with pytest.raises(RuntimeError, match="stale or has an invalid timestamp"):
        _assert_fresh_closed_candle("BTCUSDT", "5m", close_ms, now_ms=1_800_000_000_000)


def test_stale_closed_candle_is_rejected():
    now = 1_800_000_000_000
    with pytest.raises(RuntimeError, match="stale or has an invalid timestamp"):
        _assert_fresh_closed_candle("BTCUSDT", "5m", now - 600_001, now_ms=now)


def test_monthly_candle_uses_monthly_staleness_window():
    now = 1_800_000_000_000
    _assert_fresh_closed_candle("BTCUSDT", "1M", now - 40 * 24 * 60 * 60 * 1000, now_ms=now)


def test_initial_williams_signal_expires_at_exact_boundary():
    from campaign_model import SignalRole, SignalSpec, SignalType
    from futures_runtime import choose_initial_williams_signal

    now = 10_000
    expired = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY, timeframe="5m", signal_bar_time_ms=1_000,
        trigger_price=101.0, protective_reference=95.0,
        created_at_ms=1_000, expires_at_ms=now,
    )
    valid = SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=SignalType.FRACTAL,
        role=SignalRole.ADD_ON, timeframe="5m", signal_bar_time_ms=2_000,
        trigger_price=102.0, protective_reference=96.0,
        created_at_ms=2_000, expires_at_ms=now + 1,
    )
    chosen = choose_initial_williams_signal([expired, valid], "LONG", now_ms=now)
    assert chosen is not None
    assert chosen.signal_bar_time_ms == 2_000
