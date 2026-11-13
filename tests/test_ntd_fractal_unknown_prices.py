"""A missing observation cannot prove that an old NTD stop was untouched.

Synthetic closed-price fixtures, not digitized book OHLC or exchange fills.
NTD remains research-only for new exposure.
"""
import math

import pandas as pd
import pytest

from williams_signals import extract_ntd_fractal_signal_specs

H1 = 3_600_000


def frame(side):
    rows = []
    for i in range(50):
        row = {'open_time_ms': (i + 1) * H1, 'open': 99.0, 'close': 99.0,
               'high': 105.0 if i == 43 else 100.0,
               'low': 95.0 if i == 43 else 98.0,
               'fractal_up': i == 43 and side == 'LONG',
               'fractal_down': i == 43 and side == 'SHORT',
               'teeth_current': 99.0 if side == 'LONG' else 201.0}
        if side == 'SHORT':
            row.update(open=201.0, close=201.0, high=300.0-row['low'], low=300.0-row['high'])
        rows.append(row)
    return pd.DataFrame(rows)


def detect(data, side):
    return extract_ntd_fractal_signal_specs('LTCUSDT', data, side=side,
                                            timeframe='1h', tick_size=.1)


@pytest.mark.parametrize('side', ['LONG', 'SHORT'])
@pytest.mark.parametrize('missing', [float('nan'), float('inf'), -float('inf'), pd.NA, 'bad'])
def test_unknown_later_extreme_cannot_prove_old_fractal_unconsumed(side, missing):
    data = frame(side)
    assert len(detect(data, side)) == 1
    column = 'high' if side == 'LONG' else 'low'
    if isinstance(missing, str):
        data[column] = data[column].astype(object)
    data.loc[48, column] = missing
    assert detect(data, side) == []


@pytest.mark.parametrize('side', ['LONG', 'SHORT'])
@pytest.mark.parametrize('field,value', [('high', -1), ('low', 0), ('open', 999), ('close', -1)])
def test_nonpositive_or_impossible_ohlc_is_not_evidence(side, field, value):
    data = frame(side)
    data.loc[48, field] = value
    assert detect(data, side) == []


@pytest.mark.parametrize('side', ['LONG', 'SHORT'])
def test_missing_extreme_is_unknown_and_observed_break_is_consumed(side):
    data = frame(side)
    column = 'high' if side == 'LONG' else 'low'
    data.loc[48, column] = float('nan')
    assert not detect(data, side)
    data.loc[48, column] = 106.0 if side == 'LONG' else 194.0
    assert not detect(data, side)
    # With the missing observation actually supplied as unbroken, the same
    # original pivot/confirmation/trigger is recoverable, not a new signal.
    data.loc[48, column] = 100.0 if side == 'LONG' else 200.0
    restored = detect(data, side)
    assert len(restored) == 1
    assert restored[0].source_candle_index == 43
    assert restored[0].signal_bar_time_ms == 46 * H1


@pytest.mark.parametrize('side', ['LONG', 'SHORT'])
@pytest.mark.parametrize('bad_index', [float('inf'), 999, -1, 43.5, 49])
def test_invalid_or_future_confirmation_mapping_cannot_backdate_a_fractal(side, bad_index):
    data = frame(side)
    column = 'fractal_up_confirmed' if side == 'LONG' else 'fractal_down_confirmed'
    center = 'confirmed_up_center_index' if side == 'LONG' else 'confirmed_down_center_index'
    data[column] = False
    data[center] = math.nan
    data.loc[45, column] = True
    data.loc[45, center] = bad_index
    assert detect(data, side) == []
