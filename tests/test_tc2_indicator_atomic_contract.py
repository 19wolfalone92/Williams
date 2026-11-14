"""Shared independent numeric vectors, exercising the real indicator bundle."""
import json
import math
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from strategy import calculate_indicators, config_from_env
from williams_signals import _latest_super_ao, _tc2_ao_streak_price_proof

FIXTURE = Path(__file__).resolve().parents[1] / 'app/src/test/resources/tc2_indicator_atomic_contract.json'
DATA = json.loads(FIXTURE.read_text())
PAIRS = [('jaw',13,8),('teeth',8,5),('lips',5,3)]


def _prices(case):
    return np.array([float(x) for x in case['median_prices']])


def _bars(prices):
    return pd.DataFrame({'open':prices-.5, 'high':prices+2, 'low':prices-2,
                         'close':prices+.5, 'volume':100.})


def _same(actual, expected):
    if expected is None:
        assert not math.isfinite(actual)
    else:
        assert actual == pytest.approx(float(expected), rel=2e-12, abs=2e-10)


@pytest.mark.parametrize('case', DATA['numeric_cases'], ids=lambda c:c['id'])
def test_actual_python_bundle_matches_independent_shared_decimal_vectors(case):
    actual = calculate_indicators(_bars(_prices(case)), config_from_env({}))
    for point in case['checkpoints']:
        i = point['index']
        for name,_,_ in PAIRS:
            _same(actual.iloc[i][name], point[name])
            _same(actual.iloc[i][name+'_current'], point[name+'_display'])
        _same(actual.iloc[i]['ao'], point['ao'])
        colour = ('UNKNOWN' if i==0 or not math.isfinite(actual.iloc[i]['ao'])
                  or not math.isfinite(actual.iloc[i-1]['ao'])
                  else 'GREEN' if actual.iloc[i]['ao_green']
                  else 'RED' if actual.iloc[i]['ao_red'] else 'FLAT')
        assert colour == point['ao_colour']


@pytest.mark.parametrize('case', [c for c in DATA['numeric_cases'] if c['all_prices_finite']],
                         ids=lambda c:c['id'])
def test_close_changes_and_later_bars_cannot_rewrite_alligator_or_ao(case):
    frame = _bars(_prices(case))
    full = calculate_indicators(frame, config_from_env({}))
    other_close = frame.assign(close=frame['low']+.25, open=frame['high']-.25)
    changed = calculate_indicators(other_close, config_from_env({}))
    columns = ['ao','ao_green','ao_red'] + [n+s for n,_,_ in PAIRS for s in ('','_current')]
    for column in columns:
        pd.testing.assert_series_equal(full[column],changed[column])
    for end in (21,37,61):
        if end >= len(frame):
            continue
        prefix = calculate_indicators(frame.iloc[:end],config_from_env({}))
        for column in columns:
            pd.testing.assert_series_equal(prefix[column],full[column].iloc[:end])


@pytest.mark.parametrize('case', DATA['ordinal_cases'], ids=lambda c:c['id'])
def test_same_shared_ao_trace_keeps_the_exact_original_third_price_bar(case):
    values = [float(x) for x in case['ao_values']]
    streak = []
    run = 0
    for i,value in enumerate(values):
        previous = values[i-1] if i else math.nan
        directional = (value>previous if case['side']=='LONG' else value<previous)
        run = run+1 if math.isfinite(value) and math.isfinite(previous) and directional else 0
        streak.append(run)
    bars = _bars(np.arange(len(values),dtype=float)+100)
    bars['ao'] = values
    bars['ao_green_streak' if case['side']=='LONG' else 'ao_red_streak'] = streak
    # These vectors check the current colour run, not expiry/cancellation of
    # older pending setups. A current non-signal must fail the actual proof;
    # it need not revoke a separately formed earlier conditional order.
    expected = case['expected_third_index']
    if expected is not None:
        result = _latest_super_ao(bars, side=case['side'], max_age_bars=len(bars)+1)
        assert result is not None and result[0] == expected
    else:
        assert streak[-1] == case['expected_directional_run'] < 3
        assert not _tc2_ao_streak_price_proof(bars,len(bars)-1,len(bars)-1,case['side'])
