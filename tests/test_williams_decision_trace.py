import json
from pathlib import Path
from dataclasses import replace
import pytest
from campaign_model import SignalSpec,SignalType,SignalRole
from williams_edition_rules import UNIFIED
from williams_decision_trace import build_decision_trace
from williams_wave_hierarchy import WaveBar


def cases():return json.loads((Path(__file__).parents[1]/'app/src/test/resources/williams_decision_trace_contract.json').read_text())['cases']


@pytest.mark.parametrize('case',cases(),ids=lambda c:c['name'])
def test_python_android_closed_bar_evidence_contract(case):
    signals=[SignalSpec.new(symbol='BTCUSDT',side='BUY' if s['side']=='LONG' else 'SELL',signal_type=SignalType(s['family']),role=SignalRole(s['role']),
        timeframe='1h',signal_bar_time_ms=s['at'],trigger_price=s['trigger'],protective_reference=s['stop'],strategy_profile=s['profile']) for s in case['signals']]
    bars=[WaveBar(*row) for row in case['bars']]
    def build(series):return build_decision_trace('BTCUSDT',UNIFIED,series,case['now'],{'ao':1.,'ac':float('nan')},signals,{})
    trace=build(bars)
    assert trace['decision']['state']==case['expected_state']
    assert not trace['decision']['can_place_orders']
    assert trace['indicators']['ac'] is None
    assert all(book['state']=='FORMATION_OBSERVED' for book in trace['books'])
    assert build(bars+[WaveBar(case['now'],1,1e20,.01,1,1e20)])==trace
    for d in trace['divergences']:
        assert d['first']['confirmed_at_ms']<=d['second']['confirmed_at_ms']<=trace['as_of_close_ms']
        assert d['first']['ao']!=None and d['second']['price']>0
    json.dumps(trace,allow_nan=False)


def test_missing_and_gapped_evidence_never_fabricates_direction_or_authorization():
    c=cases()[0];bars=[WaveBar(*r) for r in c['bars']]
    alternatives={'scenarios':[{'frame':'1h','status':'PROVISIONAL','active_label':'W5'},{'frame':'1h','status':'PROVISIONAL','active_label':'W3'}]}
    trace=build_decision_trace('BTCUSDT',UNIFIED,bars[:-2]+bars[-1:],c['now'],{},[],alternatives)
    assert trace['wave_state']=='ALTERNATIVES'
    assert trace['decision']['state']=='BLOCKED'
    assert 'CLOSED_H1_HISTORY_NOT_PROVEN' in trace['decision']['reasons']
    assert 'NO_VALID_INITIAL_FORMATION' in trace['decision']['reasons']
    empty=build_decision_trace('ETHUSDT',UNIFIED,[],c['now'],{},[],{})
    assert empty['wave_state']=='UNRESOLVED' and not empty['divergences'] and not empty['candles']
    assert 'H1_SNAPSHOT_STALE' in empty['decision']['reasons']
