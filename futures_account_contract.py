"""Join USD-M V3 balances with separately authenticated account configuration.

V3 does not promise the V2 permission flags. These are read-only data joins;
no default trading permission and no balance fields come from configuration.
"""
from __future__ import annotations


class FuturesAccountContractError(ValueError):
    pass


def account_with_configuration(account: dict, configuration: dict | None = None) -> dict:
    if type(account) is not dict:
        raise FuturesAccountContractError('FUTURES_ACCOUNT_OBJECT_REQUIRED')
    if 'canTrade' in account:
        if type(account['canTrade']) is not bool:
            raise FuturesAccountContractError('FUTURES_ACCOUNT_PERMISSION_INVALID')
        return {**account, 'account_permission_source': '/fapi/v3/account'}
    if type(configuration) is not dict or type(configuration.get('canTrade')) is not bool:
        raise FuturesAccountContractError('FUTURES_ACCOUNT_CONFIGURATION_PERMISSION_UNAVAILABLE')
    result = dict(account)
    for field in ('canTrade', 'canDeposit', 'canWithdraw', 'dualSidePosition', 'multiAssetsMargin'):
        if field not in configuration:
            continue
        value = configuration[field]
        if type(value) is not bool:
            raise FuturesAccountContractError('FUTURES_ACCOUNT_CONFIGURATION_BOOLEAN_INVALID:' + field)
        if field in account and account[field] is not value:
            raise FuturesAccountContractError('FUTURES_ACCOUNT_CONFIGURATION_CONFLICT:' + field)
        result[field] = value
    result['account_permission_source'] = '/fapi/v1/accountConfig'
    return result
