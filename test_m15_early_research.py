from types import SimpleNamespace
from tools.replay_m15_early_entries import closed_hours,outcome,M15,canonical

def signal(side='BUY'):
    return SimpleNamespace(side=side,trigger_price=101 if side=='BUY' else 99,protective_reference=95 if side=='BUY' else 105,
        signal_bar_time_ms=10*M15,expires_at_ms=15*M15,role=SimpleNamespace(value='ENTRY'),execution_semantics='STOP_ENTRY')

def test_m15_confirmation_cannot_be_backdated_or_persist_after_expiry():
    s=signal()
    assert not canonical([s],11*M15-1)
    assert canonical([s],11*M15)==[s]
    assert not canonical([s],15*M15)

def test_hour_aggregation_excludes_live_incomplete_and_gapped_hours():
    rows=[[i*M15,100+i,102+i,99+i,101+i,10] for i in range(4,12)]
    hours=closed_hours(rows,11*M15)
    assert len(hours)==1 and hours[0]==[4*M15,104,109,103,108,40]
    assert not closed_hours(rows[:2]+rows[3:],8*M15)

def test_trigger_bar_with_stop_has_unknown_path_in_both_directions():
    for side in ('BUY','SELL'):
        s=signal(side);rows=[[11*M15,100,106,94,100,1]]
        assert outcome(s,rows,11*M15)['result']=='INTRABAR_PATH_UNKNOWN'

def test_stop_before_1r_is_preserved_and_not_counted_as_a_success():
    s=signal();rows=[[11*M15,100,102,99,101,1],[12*M15,101,103,94,95,1]]
    assert outcome(s,rows,11*M15)['result']=='STOP_BEFORE_1R'
    both=[[11*M15,100,102,99,101,1],[12*M15,101,108,94,100,1]]
    assert outcome(s,both,11*M15)['result']=='INTRABAR_PATH_UNKNOWN'

def test_late_trigger_after_expiry_and_preconfirmation_bars_never_count():
    s=signal();rows=[[10*M15,100,120,90,100,1],[15*M15,100,108,99,107,1]]
    assert outcome(s,rows,11*M15)['result']=='UNTRIGGERED_EXPIRED'
    assert outcome(s,[],11*M15)['result']=='RIGHT_CENSORED'
