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
    # Bare old labels without an observed-at timestamp and confirmed pivot
    # evidence cannot be promoted to current competing counts.
    assert trace['wave_state']=='UNRESOLVED'
    assert trace['decision']['state']=='BLOCKED'
    assert 'CLOSED_H1_HISTORY_NOT_PROVEN' in trace['decision']['reasons']
    assert 'NO_VALID_INITIAL_FORMATION' in trace['decision']['reasons']
    empty=build_decision_trace('ETHUSDT',UNIFIED,[],c['now'],{},[],{})
    assert empty['wave_state']=='UNRESOLVED' and not empty['divergences'] and not empty['candles']
    assert 'H1_SNAPSHOT_STALE' in empty['decision']['reasons']



def test_entry_context_catches_sleeping_fractal_without_inventing_trade_veto():
    from williams_edition_rules import TC2
    h=3_600_000
    now=400*h
    bars=[WaveBar(now-(80-i)*h,100.,100.5,99.5,100.,1000.) for i in range(80)]
    signal=SignalSpec.new(symbol="BTCUSDT",side="SELL",signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY,timeframe="1h",signal_bar_time_ms=bars[-3].t,
        trigger_price=99.49,protective_reference=100.51,strategy_profile=TC2)
    report=build_decision_trace("BTCUSDT",TC2,bars,now,{},[signal],{"as_of":now})
    context=report["entry_market_contexts"][0]
    assert context["alligator_phase"]=="SLEEPING"
    assert "SLEEPING_RANGE_POTENTIAL_BREAKOUT" in context["flags"]
    assert "WAVE_STRUCTURE_UNRESOLVED" in context["flags"]
    assert context["h1_reading_state"]=="OBSERVED"
    assert context["source"]==TC2
    assert not context["context_is_trade_veto"]
    assert not context["can_place_orders"]
    assert not context["probability_calibrated"]
    assert report["wave_state"]=="UNRESOLVED"
    assert build_decision_trace("BTCUSDT",TC2,bars+[WaveBar(now,1,1e9,.1,1,1e9)],
        now,{},[signal],{"as_of":now})==report
