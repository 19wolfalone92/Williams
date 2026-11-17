"""Exercise the real signed transport and barrier after waiting/timestamp rejection."""
import json

import pytest
import requests

from binance_futures_client import BinanceFuturesClient
from db import Database
from execution_barrier import ExecutionBarrier, OrderIntent
from futures_half_margin import ExposureNotSubmittedError
from market_context import ContextCache


@pytest.mark.parametrize('path', ['/fapi/v1/order','/fapi/v1/algoOrder'])
@pytest.mark.parametrize('side', ['BUY','SELL'])
def test_rate_wait_cannot_reuse_revoked_margin_lease(monkeypatch, path, side):
    client = BinanceFuturesClient('test-key','test-secret',testnet=True)
    wall = [1000.0]
    order = []
    monkeypatch.setattr('binance_futures_client.time.time',lambda:wall[0])
    def sleep(seconds):
        wall[0] += seconds
        order.append('WAIT')
    monkeypatch.setattr('binance_futures_client.time.sleep',sleep)
    def fence(params):
        order.append('FENCE')
        assert params['side']==side
        raise ExposureNotSubmittedError('FUTURES_HALF_MARGIN_UNKNOWN:STALE')
    client.before_exposure_transport=fence
    client.rate_limit_pause_until=1001.0
    monkeypatch.setattr(client.session,'request',lambda *args,**kwargs:pytest.fail('POST must not leave transport'))
    with pytest.raises(ExposureNotSubmittedError):
        client._request('POST',path,{'symbol':'BTCUSDT','side':side},signed=True)
    assert order==['WAIT','FENCE']


def test_explicit_timestamp_rejection_rechecks_margin_before_only_retry(monkeypatch):
    client=BinanceFuturesClient('test-key','test-secret',testnet=True)
    posts=[]
    def request(*args,**kwargs):
        posts.append(kwargs['params'])
        response=requests.Response();response.status_code=400
        response._content=json.dumps({'code':-1021,'msg':'timestamp rejected'}).encode()
        return response
    def fence(params):
        if posts:raise ExposureNotSubmittedError('FUTURES_HALF_MARGIN_UNKNOWN:STALE')
    client.before_exposure_transport=fence
    monkeypatch.setattr(client.session,'request',request)
    monkeypatch.setattr(client,'sync_time',lambda:None)
    with pytest.raises(ExposureNotSubmittedError):
        client._request('POST','/fapi/v1/order',{'symbol':'BTCUSDT','side':'BUY'},signed=True)
    assert len(posts)==1


def test_barrier_persists_final_local_rejection_as_blocked_not_ambiguous(tmp_path):
    db=Database(str(tmp_path/'transport.sqlite3'))
    intent=OrderIntent.new('BTCUSDT','BUY','STOP_MARKET',{},purpose='CAMPAIGN_ENTRY')
    def reject():raise ExposureNotSubmittedError('FUTURES_HALF_MARGIN_UNKNOWN:STALE')
    result=ExecutionBarrier(ContextCache(),db).execute(intent,reject)
    assert not result.accepted
    assert db.conn.execute('SELECT status FROM execution_intents').fetchone()['status']=='BLOCKED'
    db.conn.close()
