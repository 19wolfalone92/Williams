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
