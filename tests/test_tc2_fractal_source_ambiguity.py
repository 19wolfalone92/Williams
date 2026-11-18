"""Guard causal exact-source TC2 11.1D six-bar confirmation; never peek ahead."""
import json
from pathlib import Path

import pandas as pd
import pytest

from strategy import calculate_indicators, config_from_env
from williams_edition_rules import edition_fractals
from williams_exit_policy import plan_williams_stop
from williams_signals import _latest_confirmed_fractal
from williams_wave_hierarchy import WaveBar, confirmed_wave_anchors

DATA = json.loads((Path(__file__).resolve().parents[1] /
    'app/src/test/resources/tc2_fractal_source_ambiguity.json').read_text())


@pytest.mark.parametrize('case', DATA['cases'], ids=lambda c:c['id'])
def test_declared_conservative_timing_and_source_identity_are_not_rewritten(case):
    frame = pd.DataFrame(case['bars'],columns=['open_time_ms','open','high','low','close','volume'])
    full = calculate_indicators(frame,config_from_env({}))
    side = case['side']
    prefix_name = 'up' if side=='LONG' else 'down'
    columns = [f'confirmed_{prefix_name}_{suffix}' for suffix in
               ('level','center_index','group_start_index','confirmation_index')]
    for end in range(5,len(frame)+1):
        prefix = calculate_indicators(frame.iloc[:end],config_from_env({}))
        # Confirmation columns are decisions at their actual close. Retrospective
        # centre arrows are deliberately excluded from this causal comparison.
        pd.testing.assert_frame_equal(prefix[columns],full.iloc[:end][columns])
        prefix['teeth_current'] = case['teeth']
        got = _latest_confirmed_fractal(prefix,side=side,current_teeth=case['teeth'],
            current_close=float(prefix.iloc[-1]['close']),tick_size=.1)
        expected = case['expected']
        if end == len(frame):
            if expected is None:
                assert got is None
            else:
                assert got is not None
                assert got[:2] == (expected['confirmation'],expected['center'])
                assert prefix.iloc[expected['confirmation']][f'confirmed_{prefix_name}_group_start_index'] == expected['group_start']
                assert got[2] == expected['pivot']
                assert got[3] == expected['protective_extreme']
        # An earlier prefix may be an independently valid six-bar figure
        # even if a later equal pivot changes the final centre. A finished
        # seven/nine-bar candle must not be used to deny that past proof.
        if got is not None:
            confirmation, center, pivot, _, _ = got
            assert center+2<=confirmation<end
            assert (prefix.iloc[confirmation][f'confirmed_{prefix_name}_center_index']
                == center)
            assert float(prefix.iloc[center]['high' if side=='LONG' else 'low'])==pivot
            pivots=edition_fractals([WaveBar(*row) for row in case['bars'][:end]])
            kind='HIGH' if side=='LONG' else 'LOW'
            assert any(f.kind==kind and f.center==center and
                f.confirmation==confirmation for f in pivots)


@pytest.mark.parametrize('side',['LONG','SHORT'])
def test_first_equal_extreme_before_position_opening_cannot_be_relabelled_post_entry(side):
    lows = [111.,110.,108.,110.,108.,110.,108.,110.,111.]
    bars = [WaveBar((i+1)*3_600_000,l+1,120.,l,l+1,10.) for i,l in enumerate(lows)]
    if side=='SHORT':
        bars = [WaveBar(b.t,250-b.o,250-b.l,250-b.h,250-b.c,b.v) for b in bars]
    old,entry,mark = (100.,115.,125.) if side=='LONG' else (150.,135.,125.)
    # Opening before the entire group permits its confirmed fractal.
    assert plan_williams_stop(side,old,entry,None,mark,.1,3_600_000,bars,window=None) is not None
    # Opening after its FIRST equal extreme does not create a post-entry group.
    assert plan_williams_stop(side,old,entry,None,mark,.1,4*3_600_000,bars,window=None) is None


@pytest.mark.parametrize('side',['LONG','SHORT'])
def test_unknown_left_flank_before_whole_equal_group_cannot_create_fractal_or_wave(side):
    highs = [105.,103.,105.,103.,105.,103.,102.]
    bars = [WaveBar((i+1)*3_600_000,100.,h,99.,100.,10.) for i,h in enumerate(highs)]
    if side=='SHORT':
        bars = [WaveBar(b.t,250-b.o,250-b.l,250-b.h,250-b.c,b.v) for b in bars]
    kind = 'HIGH' if side=='LONG' else 'LOW'
    assert not [f for f in edition_fractals(bars) if f.kind==kind]
    assert not [f for f in confirmed_wave_anchors(bars,3_600_000) if f.kind==kind]
    frame = pd.DataFrame([dict(open_time_ms=b.t,open=b.o,high=b.h,low=b.l,close=b.c,volume=b.v) for b in bars])
    ind = calculate_indicators(frame,config_from_env({}))
    assert ind['confirmed_up_level' if side=='LONG' else 'confirmed_down_level'].isna().all()


@pytest.mark.parametrize('side',['LONG','SHORT'])
def test_two_real_right_bars_separate_an_older_confirmed_group_from_a_later_equal_pivot(side):
    highs = [101.,102.,105.,103.,102.,105.,103.,102.]
    bars = [WaveBar((i+1)*3_600_000,100.,h,99.,100.,10.) for i,h in enumerate(highs)]
    if side=='SHORT':
        bars = [WaveBar(b.t,250-b.o,250-b.l,250-b.h,250-b.c,b.v) for b in bars]
    kind = 'HIGH' if side=='LONG' else 'LOW'
    pivots = [f for f in edition_fractals(bars) if f.kind==kind]
    assert [(f.center,f.group_start,f.confirmation) for f in pivots] == [(2,2,4),(5,5,7)]
