"""Regressions for geometry after mouth exit; these are not expert chart labels."""
import json
from decimal import Decimal
from pathlib import Path

import pytest

from williams_market_geometry import angulation_evidence
from williams_wave_hierarchy import WaveBar

H = 3_600_000
FIXTURE = Path(__file__).resolve().parents[1] / 'app/src/test/resources/tc2_wm1_departure_path_contract.json'
CASES = json.loads(FIXTURE.read_text())['cases']


@pytest.mark.parametrize('case', CASES, ids=lambda c: c['id'])
def test_shared_departure_path_case(case):
    proof = angulation_evidence(case['side'], [WaveBar(*b) for b in case['bars']],
                               *[case[n] for n in ('jaw', 'teeth', 'lips')], expected_step_ms=H)
    assert proof['valid'] is case['valid'], proof
    assert proof['price_edge'] == ('HIGH' if case['side'] == 'LONG' else 'LOW')
    assert proof['anchor_time_ms'] == case['anchor_time_ms']
    assert proof['source_end_bar_open_ms'] == case['bars'][-1][0]
    assert proof['numeric_price_resolution'] > 0
    if proof['valid']:
        assert proof['score'] > 0
    json.dumps(proof, allow_nan=False)


@pytest.mark.parametrize('side', ['LONG', 'SHORT'])
@pytest.mark.parametrize('scale', ['0.00001', '1', '10000'])
@pytest.mark.parametrize('translation', ['0', '100000'])
def test_exact_decimal_parallel_paths_do_not_become_an_angle_through_rounding(side, scale, translation):
    # Independently defined straight decimal paths, not outputs of the detector.
    # Outside gaps are constant; only the first departure differs.
    for count in [4, 7, 12]:
        for step in [Decimal('.001'), Decimal('.01'), Decimal('.1')]:
            base = Decimal('10') + count * step
            gap = Decimal('.1')
            sign = 1 if side == 'SHORT' else -1
            line = [base + sign * i * step for i in range(count)]
            edges = [base] + [line[i] + sign * gap for i in range(1, count)]
            factor, shift = Decimal(scale), Decimal(translation)
            def value(x):
                return float(x * factor + shift)
            bars = []
            for i, edge in enumerate(edges):
                if i == 0:
                    o, h, l, c = edge, edge + gap / 10, edge - gap / 10, edge
                elif side == 'LONG':
                    o, h, l, c = edge-gap/50, edge, edge-gap/25, edge-gap/100
                else:
                    o, h, l, c = edge+gap/50, edge+gap/25, edge, edge+gap/100
                bars.append(WaveBar((i+1)*H, *map(value, (o, h, l, c)), 10.))
            levels = list(map(value, line))
            proof = angulation_evidence(side, bars, levels, levels, levels, expected_step_ms=H)
            assert not proof['valid'], (count, step, proof)
            json.dumps(proof, allow_nan=False)


def test_a_new_mouth_passage_resets_the_path_and_future_rows_cannot_change_its_prefix():
    case = next(c for c in CASES if c['id'] == 'continued_opening_with_pullback_long')
    bars = [WaveBar(*b) for b in case['bars']]
    line = case['jaw']
    before = angulation_evidence('LONG', bars, line, line, line, expected_step_ms=H)
    future = bars + [WaveBar(6*H, 200., 201., 199., 200., 10.)]
    assert angulation_evidence('LONG', future[:5], line, line, line, expected_step_ms=H) == before
    prefix = [WaveBar(-H, 90., 91., 89., 90., 10.), WaveBar(0, 95., 96., 94., 95., 10.)]
    extended = angulation_evidence('LONG', prefix+bars, [98., 99.]+line,
                                  [98., 99.]+line, [98., 99.]+line, expected_step_ms=H)
    assert extended['valid'] == before['valid']
    assert extended['anchor_time_ms'] == before['anchor_time_ms']
