import json
from pathlib import Path
from types import SimpleNamespace
from datetime import datetime,timezone
import pytest
from db import Database
from entry_time_policy import EntryTimePolicy
from williams_signal_lifecycle import SignalJournal,signal_lifecycle,reason_category
from futures_williams_runtime import FuturesWilliamsRuntime

CONTRACT=json.loads(Path('app/src/test/resources/williams_entry_lifecycle_contract.json').read_text())

@pytest.mark.parametrize('case',CONTRACT['time_cases'])
def test_shared_utc_policy_boundaries(case):
    assert EntryTimePolicy(**case['policy']).allows(case['now']) is case['allowed']

@pytest.mark.parametrize('case',CONTRACT['lifecycle_cases'],ids=lambda c:c['name'])
def test_shared_signal_lifecycle(case):
    assert signal_lifecycle(case['signal'],case['owner'],case['now'],case['snapshot_at_ms'],case['current'])==case['state']

@pytest.mark.parametrize(('reconciled','created','order_observed','expected'), [
    (0, 1792411199900, 0, 'PENDING_UNVERIFIED'),
    (1792411199999, 1792411199900, 0, 'PENDING_UNVERIFIED'),
    (1792411199999, 0, 1792411079999, 'PENDING_UNVERIFIED'),
    (0, 0, 1792411199000, 'BINANCE_PENDING'),
    (1792411199999, 1792411199900, 1792411199000, 'BINANCE_PENDING'),
])
def test_local_intent_timestamp_is_not_signed_exchange_confirmation(reconciled, created, order_observed, expected):
    case=next(c for c in CONTRACT['lifecycle_cases'] if c['name']=='NEW')
    owner=dict(case['owner'],last_reconciled_ms=reconciled,
        entry_order_observed_at_ms=order_observed,created_at_ms=created)
    assert signal_lifecycle(case['signal'],owner,case['now'],case['snapshot_at_ms'],True)==expected


@pytest.mark.parametrize('policy',[dict(mode='AUTO'),dict(mode='UTC_WINDOW',start_utc='08:00',end_utc='08:00'),dict(start_utc='8:00'),dict(end_utc='24:00')])
def test_invalid_policy_cannot_widen_entry_hours(policy):
    with pytest.raises(ValueError):EntryTimePolicy(**policy)

def test_persisted_journal_survives_restart_and_preserves_confirmation_and_blockers(tmp_path):
    db=Database(str(tmp_path/'journal.sqlite3'));journal=SignalJournal(db)
    case=CONTRACT['lifecycle_cases'][1];now=case['now'];s=case['signal']
    journal.observe('BTCUSDT',[s],now,EntryTimePolicy('UTC_WINDOW','13:00','14:00'))
    journal.decision('BTCUSDT','RUNTIME_PREFLIGHT','HTF confirmation gate blocked initial campaign',now+1,s['signal_id'])
    journal.decision('BTCUSDT','RUNTIME_PREFLIGHT','PORTFOLIO_RISK_EXHAUSTED',now+2,s['signal_id'])
    journal.observe('BTCUSDT',[s],now+30_000,EntryTimePolicy())
    restarted=SignalJournal(Database(str(tmp_path/'journal.sqlite3'))).view('BTCUSDT',now+30_000)
    fact=restarted['signals'][0]
    assert fact['formation_at_ms']<fact['signal_bar_time_ms']<fact['known_at_ms']
    assert fact['first_seen_at_ms']==now and fact['trigger']==101 and fact['state']=='VALID'
    assert {'ENTRY_TIME','HTF_CONTEXT','RISK'}<={e['reason_category'] for e in restarted['events']}
    assert sum(e['stage']=='FORMATION' for e in restarted['events'])==1
    assert not any(e['can_place_orders'] for e in restarted['events'])
    assert reason_category('RUNTIME_NOT_READY')=='RUNTIME_GATE'
    journal.order('BTCUSDT',s['signal_id'],dict(status='NEW',clientOrderId='exchange-1'),now+30_001)
    assert journal.view('BTCUSDT',now+30_001)['signals'][0]['state']=='BINANCE_PENDING'
    journal.order('BTCUSDT',s['signal_id'],dict(status='CANCELED',clientOrderId='exchange-1'),now+30_002,'ENTRY_TIME_POLICY_BLOCKED')
    journal.observe('BTCUSDT',[],now+30_003,EntryTimePolicy())
    assert journal.view('BTCUSDT',now+30_003)['signals'][0]['state']=='CANCELLED'

def test_history_retention_and_redaction_cover_event_ids(tmp_path):
    journal=SignalJournal(Database(str(tmp_path/'journal.sqlite3')));case=CONTRACT['lifecycle_cases'][1]
    journal.observe('BTCUSDT',[case['signal']],case['now'],EntryTimePolicy())
    for n in range(270):journal.decision('BTCUSDT','EXCHANGE',f'ORDER_REJECTED_{n} api_key=secret-example',case['now']+n)
    history=journal.view('BTCUSDT',case['now']+300)
    assert len(history['events'])==256
    assert 'secret-example' not in json.dumps(history)

def test_final_submission_gate_blocks_entry_and_addition_but_allows_cancel(monkeypatch):
    runtime=object.__new__(FuturesWilliamsRuntime)
    now=datetime(2026,10,19,20,0,tzinfo=timezone.utc).timestamp()
    monkeypatch.setattr('futures_williams_runtime.time.time',lambda:now)
    runtime.client=SimpleNamespace(time_offset_ms=0)
    runtime.config=SimpleNamespace(entry_time_policy=EntryTimePolicy('UTC_WINDOW','08:00','20:00'))
    campaign=SimpleNamespace(symbol='BTCUSDT',side='BUY',state=__import__('campaign_model').CampaignState.ENTRY_PENDING,
        tags={'pending_order_client_id':'cid'})
    runtime.engine=SimpleNamespace(load_campaign=lambda _:campaign)
    for purpose in ('CAMPAIGN_ENTRY','CAMPAIGN_ADD_ON'):
        with pytest.raises(RuntimeError,match='ENTRY_TIME_POLICY_BLOCKED'):
            runtime._futures_pre_submit_checks(SimpleNamespace(campaign_id='c',symbol='BTCUSDT',side='BUY',purpose=purpose))
    runtime.client.get_order=lambda *a,**kw:dict(clientOrderId='cid',symbol='BTCUSDT',side='BUY')
    runtime._futures_pre_submit_checks(SimpleNamespace(campaign_id='c',symbol='BTCUSDT',side='BUY',purpose='CAMPAIGN_CANCEL_ENTRY',client_order_id='cid'))


def test_signal_fact_keeps_pivot_ohlc_separate_from_confirmation():
    from campaign_model import SignalSpec,SignalType,SignalRole
    from williams_wave_hierarchy import WaveBar
    from williams_signal_lifecycle import signal_facts
    bars=[WaveBar((n+1)*3_600_000,100,110+n,90,100+n,50+n) for n in range(5)]
    spec=SignalSpec('fractal-1','BTCUSDT','BUY',SignalType.FRACTAL,SignalRole.ENTRY,'1h',bars[3].t,112,89,
        source_candle_index=1,teeth_at_detection=float('nan'))
    fact=signal_facts([spec],bars,3_600_000)[0]
    assert fact['formation_candle']==[bars[1].t,100,111,90,101,51]
    assert fact['confirmation_candle']==[bars[3].t,100,113,90,103,53]
    assert fact['known_at_ms']==bars[3].t+3_600_000 and fact['formation_at_ms']==bars[1].t
    assert fact['teeth_at_detection'] is None
    json.dumps(fact,allow_nan=False)


def test_ambiguous_gate_messages_do_not_invent_an_entry_time_block():
    assert reason_category('Addition profile/session/daily risk changed before mutation')=='RUNTIME_GATE'
    assert reason_category('RUNTIME_OR_CAMPAIGN_NOT_READY: H1_STRUCTURE_NOT_READY')=='RUNTIME_GATE'
    assert reason_category('ENTRY_TIME_POLICY_BLOCKED_BEFORE_ADDITION')=='ENTRY_TIME'
    assert reason_category('DAILY_LOSS_OR_EQUITY_BASELINE_BLOCKED_BEFORE_ADDITION')=='RISK'


@pytest.mark.parametrize("legacy_mode", ["UTC_WINDOW", "INVALID_LEGACY_VALUE"])
def test_futures_config_retires_legacy_utc_windows(legacy_mode):
    from trading_config import TradingConfig
    config = TradingConfig.from_env({"BINANCE_MARKET": "futures_usdt",
        "ENTRY_TIME_MODE": legacy_mode, "ENTRY_TIME_START_UTC": "08:00",
        "ENTRY_TIME_END_UTC": "20:00"})
    assert config.entry_time_policy.mode == "ALWAYS"
    for hour in range(24):
        ts = int(datetime(2026, 11, 26, hour, tzinfo=timezone.utc).timestamp()*1000)
        assert config.entry_time_policy.allows(ts)
    assert config.max_daily_loss_pct == 0.03
    assert config.max_consecutive_losses == 3
    assert config.max_total_risk_pct == 0.006


def test_legacy_spot_utc_window_remains_unchanged():
    from trading_config import TradingConfig
    config = TradingConfig.from_env({"BINANCE_MARKET": "spot",
        "ENTRY_TIME_MODE": "UTC_WINDOW", "ENTRY_TIME_START_UTC": "08:00",
        "ENTRY_TIME_END_UTC": "20:00"})
    assert config.entry_time_policy.mode == "UTC_WINDOW"
    ts = int(datetime(2026, 11, 26, 21, tzinfo=timezone.utc).timestamp()*1000)
    assert not config.entry_time_policy.allows(ts)

@pytest.mark.parametrize("status,expected", [
    ("NEW","BINANCE_PENDING"), ("PARTIALLY_FILLED","PARTIAL_FILL"),
    ("FILLED","FILLED"), ("CANCELED","CANCELLED"),
])
def test_sqlite_rescan_does_not_erase_order_specific_observation(tmp_path,status,expected):
    path=str(tmp_path/'signal-rescan.sqlite3')
    case=CONTRACT['lifecycle_cases'][1]; now=case['now']; signal=case['signal']
    journal=SignalJournal(Database(path))
    journal.observe('BTCUSDT',[signal],now,EntryTimePolicy())
    journal.order('BTCUSDT',signal['signal_id'],dict(status=status,clientOrderId='signed-order'),now+1)
    journal.observe('BTCUSDT',[],now+2,EntryTimePolicy())
    journal.observe('BTCUSDT',[signal],now+3,EntryTimePolicy())
    fact=SignalJournal(Database(path)).view('BTCUSDT',now+4)['signals'][0]
    assert fact['state']==expected
    assert fact['first_seen_at_ms']==now
    assert fact['exchange_observation']['client_id']=='signed-order'
    assert fact['exchange_observation']['entry_order_observed_at_ms']==now+1
