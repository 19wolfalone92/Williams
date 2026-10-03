# Williams 4.6.0

## Core
- Added explicit trading state machine.
- Added hard `RECONCILE_REQUIRED` entry block.
- Added durable `ENTRY_PENDING` clientOrderId recovery.
- Added exact Entry → OCO linkage.
- Added two-sided position balance reconciliation.
- Added safer offline exit reconstruction.

## Risk
- Daily loss limit.
- Daily trade limit.
- Consecutive-loss limit.
- Cooldown.
- Minimum risk/reward.
- Spread filter.
- ATR volatility filter.
- Higher-timeframe trend confirmation.
- Risk-based position sizing capped by capital fraction.

## Connectivity
- User-data WebSocket proactive rotation.
- Existing reconnect/backoff retained.
- REST remains the reconciliation authority.

## Android
- Version 4.6.0 / versionCode 7.
- Reconcile state shown prominently.
- Secure credential storage retained.
- Fixed TradingChart Kotlin syntax.

## Other fixes
- Fixed historical backtester `fetch_klines()` positional date call.
- `test_connection.py` no longer performs network requests on import.
- Release archives no longer include SQLite/WAL/SHM runtime state.
