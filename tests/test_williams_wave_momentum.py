from dataclasses import replace
import json
from pathlib import Path

import pytest

from williams_market_phase import market_phase_context
from williams_wave_hierarchy import FRAME_MS, WaveBar, build_wave_hierarchy
from williams_wave_momentum import evaluate_third_momentum

ROOT=Path(__file__).resolve().parents[1]
CONTRACT=json.loads((ROOT/'app/src/test/resources/williams_w3_momentum_source_contract.json').read_text())
HOUR=FRAME_MS['1h']


@pytest.mark.parametrize('case',CONTRACT['cases'],ids=lambda case:case['id'])
@pytest.mark.parametrize('direction',['UP','DOWN'])
def test_source_global_peak_and_one_to_five_bar_lag(case,direction):
    rows=[WaveBar((1000+i)*HOUR,p,p+1,p-1,p,1.) for i in range(120)
          for p in [150. if i==70 else 100.]]
    if 'higher_bar' in case:
        rows[case['higher_bar']]=replace(rows[case['higher_bar']],h=160.)
    ao=[float('nan') if i<33 else 10. if i==case['peak_index'] else -1. if i>=90 else 2.
        for i in range(120)]
    if 'other_peak' in case:
        i,value=case['other_peak'];ao[i]=value
    if direction=='DOWN':
        rows=[replace(b,o=300-b.o,h=300-b.l,l=300-b.h,c=300-b.c) for b in rows]
        ao=[-a for a in ao]
    result=evaluate_third_momentum(rows,ao,40,70,case['end_index'],direction,HOUR)
    assert result['status']==case['expected']
    if 'lag' in case:
        assert result['price_to_ao_lag_bars']==case['lag']
        assert result['peak_turn_known_at_ms']==(1000+case['known_bar'])*HOUR
    for key in ('wave_degree_confirmed','can_place_orders','can_close_positions'):
        assert not result[key]
    # Arbitrary future oscillator and price changes cannot rewrite known evidence.
    for i in range(case['end_index']+1,120):
        ao[i]=10000.;rows[i]=replace(rows[i],h=99999.)
    assert evaluate_third_momentum(rows,ao,40,70,case['end_index'],direction,HOUR)==result


def test_actual_hierarchy_and_market_phase_carry_the_source_evidence():
    case=json.loads((ROOT/'app/src/test/resources/williams_wave_contract.json').read_text())['cases'][0]
    bars=[WaveBar(*row) for row in case['bars']]
    report=build_wave_hierarchy({'1h':bars},case['as_of'])
    phase=market_phase_context(report.to_dict())
    frame=next(f for f in phase['frames'] if f['frame']=='1h')
    hypothesis=next(s for s in report.scenarios if len(s.anchors)==6 and s.status=='PROVISIONAL')
    assert hypothesis.third_momentum is not None
    assert hypothesis.third_momentum['source']=='TC1_PDF60_COURSE_PDF42'
    assert next(s for s in frame['hypotheses'] if s['scenario_id']==hypothesis.scenario_id)['third_momentum']==hypothesis.third_momentum
    cutoff=150*HOUR
    assert build_wave_hierarchy({'1h':bars},cutoff).to_dict()==build_wave_hierarchy(
        {'1h':[b for b in bars if b.t+HOUR<=cutoff]},cutoff).to_dict()
