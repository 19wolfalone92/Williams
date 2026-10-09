import pandas as pd
import pytest

from data import _frame, validate_ohlcv_frame


def row(open_time, open_price=100, high=102, low=99, close=101, volume=5, close_time=None):
    close_time = open_time + 59_999 if close_time is None else close_time
    return [
        open_time, str(open_price), str(high), str(low), str(close), str(volume),
        close_time, "500", 10, "2", "200", "0",
    ]


def test_frame_preserves_valid_ohlcv_and_utc_timestamps():
    frame = _frame([row(60_000), row(120_000)])
    assert len(frame) == 2
    assert str(frame.index.tz) == "UTC"
    assert frame.iloc[0]["high"] == 102
    assert frame["close_time"].notna().all()


@pytest.mark.parametrize("rows, message", [
    ([row(60_000), row(60_000)], "unique"),
    ([row(120_000), row(60_000)], "increasing"),
    ([row(60_000, high=98)], "OHLC invariant"),
    ([row(60_000, volume=-1)], "volume"),
    ([row(60_000, close=0)], "positive"),
    ([row(60_000, close=float("nan"))], "non-numeric or non-finite"),
])
def test_frame_rejects_invalid_historical_rows(rows, message):
    with pytest.raises(ValueError, match=message):
        _frame(rows)


def test_validator_rejects_bad_close_time():
    frame = _frame([row(60_000)])
    frame.loc[frame.index[0], "close_time"] = frame.index[0] - pd.Timedelta(seconds=1)
    with pytest.raises(ValueError, match="close timestamps"):
        validate_ohlcv_frame(frame)
