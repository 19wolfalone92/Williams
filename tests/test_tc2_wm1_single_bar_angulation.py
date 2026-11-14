"""TC2_EN142/160 qualitative rules; synthetic prices, not digitized book charts."""
import json
import math
from pathlib import Path

import pandas as pd
import pytest

from strategy import calculate_indicators, config_from_env
from williams_market_geometry import angulation_evidence
from williams_signals import _angulation, _latest_reversal
from williams_wave_hierarchy import WaveBar

H = 3_600_000
FIXTURE = Path(__file__).resolve().parents[1] / 'app/src/test/resources/tc2_wm1_angulation_contract.json'
DATA = json.loads(FIXTURE.read_text())


@pytest.mark.parametrize('case', DATA['cases'], ids=lambda c: c['id'])
def test_shared_qualitative_angulation_contract(case):
    bars = [WaveBar(*b) for b in case['bars']]
    lines = [[float('nan') if v is None else v for v in case[k]] for k in ('jaw','teeth','lips')]
    proof = angulation_evidence(case['side'], bars, *lines, expected_step_ms=H)
    assert proof['valid'] is case['valid']
    assert proof['reason'] == case['reason']
    if proof['valid']:
        assert proof['gap_growth_baseline_index'] == case['baseline']
        assert proof['gap_growth_basis'] == case['basis']
        assert proof['outside_gap_growth_pct'] > 0
    json.dumps(proof, allow_nan=False)


def indicator_frame(side, scale=1.):
    # The first fully outside candle is itself a divergent bar. Its mouth
    # passage is the immediately preceding candle; AO is calculated, not mocked.
    rows = [[100.,102.,98.,100.]] * 60 + [[70.,95.,60.,90.]]
    if side == 'SHORT':
        rows = [[250-o,250-l,250-h,250-c] for o,h,l,c in rows]
    frame = pd.DataFrame(rows, columns=['open','high','low','close']) * scale
    frame['volume'] = 10.
    frame['open_time_ms'] = [(i+1)*H for i in range(len(frame))]
    return calculate_indicators(frame, config_from_env({}))


@pytest.mark.parametrize('side', ['LONG','SHORT'])
@pytest.mark.parametrize('scale', [.00001,1.,10000.])
def test_actual_indicator_bundle_and_reversal_extractor_allow_self_created_angle(side,scale):
    ind = indicator_frame(side, scale)
    bar = ind.iloc[-1]
    assert bar['bullish_reversal_bar' if side=='LONG' else 'bearish_reversal_bar']
    assert bar['ao_red' if side=='LONG' else 'ao_green']
    expected = (60,float(bar['high' if side=='LONG' else 'low']),
                float(bar['low' if side=='LONG' else 'high']))
    assert _latest_reversal(ind,side=side) == expected
    assert _angulation(ind,60,side=side)[1]
    # Appended future values must not change the geometry of the known bar.
    later = pd.concat([ind,ind.iloc[-1:].assign(high=100000*scale,low=scale)])
    assert _angulation(later,60,side=side) == _angulation(ind,60,side=side)


@pytest.mark.parametrize('side', ['LONG','SHORT'])
@pytest.mark.parametrize('fault', ['wrong_ao','unknown_ao','midpoint','no_new_extreme'])
def test_single_bar_angle_does_not_replace_other_wm1_source_conditions(side,fault):
    ind = indicator_frame(side)
    i = ind.index[-1]
    if fault == 'wrong_ao':
        ind.loc[i,'ao'] = ind.iloc[-2]['ao'] + (1 if side=='LONG' else -1)
    elif fault == 'unknown_ao':
        ind.loc[i,'ao'] = math.nan
    elif fault == 'midpoint':
        ind.loc[i,'close'] = (ind.loc[i,'high']+ind.loc[i,'low'])/2
    else:
        extreme = 'low' if side=='LONG' else 'high'
        ind.loc[ind.index[-2],extreme] = ind.loc[i,extreme]
    assert _latest_reversal(ind,side=side) is None


def test_finite_prices_cannot_publish_nonfinite_geometry_or_break_json_diagnostics():
    bars = [WaveBar((i+1)*H,*ohlc,10.) for i,ohlc in enumerate([
        (1.6e308,1.75e308,1.5e308,1.6e308),
        (1.6e308,1.75e308,1.5e308,1.6e308),
        (1.1e308,1.4e308,1.e308,1.3e308),
        (1.e308,1.3e308,.9e308,1.2e308)])]
    line = [1.65e308]*4
    proof = angulation_evidence('LONG',bars,line,line,line,expected_step_ms=H)
    assert not proof['valid']
    assert proof['reason'] == 'NUMERICAL_GEOMETRY_UNVERIFIED'
    json.dumps(proof,allow_nan=False)
