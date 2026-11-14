import copy

import pytest

from binance_client import BinanceAPIError
from binance_futures_client import BinanceFuturesClient
from futures_account_contract import FuturesAccountContractError, account_with_configuration


@pytest.fixture
def balances_v3():
    # Minimal documented V3 shape: no canTrade/feeTier/account mode flags.
    return {'totalWalletBalance':'1000', 'totalUnrealizedProfit':'-10',
            'totalMarginBalance':'990', 'availableBalance':'890',
            'totalInitialMargin':'100', 'totalPositionInitialMargin':'100',
            'totalOpenOrderInitialMargin':'0',
            'assets':[{'asset':'USDT', 'walletBalance':'1000', 'availableBalance':'890'}],
            'positions':[{'symbol':'BTCUSDT', 'positionAmt':'0.01',
                          'initialMargin':'100', 'isolatedWallet':'100'}]}


@pytest.mark.parametrize('permission', [True, False])
def test_real_v3_shape_uses_separate_signed_configuration_and_preserves_balance_truth(monkeypatch, balances_v3, permission):
    client = BinanceFuturesClient('test-key','test-secret',testnet=True)
    calls=[]
    original=copy.deepcopy(balances_v3)
    config={'canTrade':permission, 'multiAssetsMargin':False, 'dualSidePosition':False,
            'totalMarginBalance':'999999', 'assets':[{'asset':'EVIL'}]}
    def request(method,path,params=None,signed=False):
        calls.append((method,path,signed))
        if path=='/fapi/v3/account': return balances_v3
        if path=='/fapi/v1/accountConfig': return config
        raise AssertionError(path)
    monkeypatch.setattr(client,'_request',request)
    result=client.account()
    assert calls==[('GET','/fapi/v3/account',True),('GET','/fapi/v1/accountConfig',True)]
    assert result['canTrade'] is permission
    assert result['status']==('TRADING' if permission else 'NOT_TRADING')
    assert result['totalMarginBalance']=='990'
    assert result['positions']==original['positions']
    assert result['assets']==original['assets']
    assert result['multiAssetsMargin'] is False
    assert result['account_permission_source']=='/fapi/v1/accountConfig'
    assert balances_v3==original


@pytest.mark.parametrize('value',[None,'true','false',0,1,{},[]])
def test_present_but_malformed_permission_cannot_be_replaced_by_a_more_favorable_config(monkeypatch, balances_v3, value):
    client=BinanceFuturesClient('test-key','test-secret',testnet=True)
    balances_v3['canTrade']=value
    calls=[]
    def request(method,path,**kwargs):
        calls.append(path)
        assert path=='/fapi/v3/account'
        return balances_v3
    monkeypatch.setattr(client,'_request',request)
    with pytest.raises(BinanceAPIError,match='FUTURES_ACCOUNT_PERMISSION_INVALID'):
        client.account()
    assert calls==['/fapi/v3/account']


@pytest.mark.parametrize('config',[None,{}, {'canTrade':None},{'canTrade':'true'},
                                   {'canTrade':1},{'canTrade':False,'multiAssetsMargin':'false'}])
def test_unknown_or_malformed_configuration_is_not_defaulted_to_trading(config,balances_v3):
    with pytest.raises(FuturesAccountContractError):
        account_with_configuration(balances_v3,config)


def test_missing_configuration_during_network_failure_keeps_original_error(monkeypatch,balances_v3):
    client=BinanceFuturesClient('test-key','test-secret',testnet=True)
    def request(method,path,**kwargs):
        if path=='/fapi/v3/account':return balances_v3
        raise BinanceAPIError('accountConfig unavailable')
    monkeypatch.setattr(client,'_request',request)
    with pytest.raises(BinanceAPIError,match='accountConfig unavailable'):
        client.account()


@pytest.mark.parametrize('permission',[True,False])
def test_legacy_authenticated_boolean_is_preserved_without_extra_get(balances_v3,permission):
    balances_v3['canTrade']=permission
    result=account_with_configuration(balances_v3)
    assert result['canTrade'] is permission
    assert result['account_permission_source']=='/fapi/v3/account'


def test_collateral_mode_conflict_is_never_silently_overwritten(balances_v3):
    balances_v3['multiAssetsMargin']=True
    with pytest.raises(FuturesAccountContractError,match='CONFIGURATION_CONFLICT'):
        account_with_configuration(balances_v3,{'canTrade':True,'multiAssetsMargin':False})


@pytest.mark.parametrize('bad_assets',[None,{},[None],[1]])
def test_malformed_assets_rejected_before_configuration_lookup(monkeypatch,balances_v3,bad_assets):
    client=BinanceFuturesClient('test-key','test-secret',testnet=True)
    balances_v3['assets']=bad_assets
    def request(method,path,**kwargs):
        assert path=='/fapi/v3/account'
        return balances_v3
    monkeypatch.setattr(client,'_request',request)
    with pytest.raises(BinanceAPIError,match='Malformed Futures account snapshot'):
        client.account()
