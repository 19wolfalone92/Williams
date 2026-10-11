# Python test and smoke-test guide

## Offline test suite

Run the regular test suite from the repository root:

```bash
python -m pytest -q --tb=short
```

The repository's three operational smoke scripts are deliberately excluded from
automatic pytest collection:

- `safe_smoke_test.py` validates local runtime SQLite files and expected local
  database state. It requires the local database artifacts that are not stored
  in Git.
- `test_scanner_trader_dry_run.py` reads the Spot Testnet account and market
  data, then runs the scanner in a no-order flow.
- `test_selected_symbol_prepare.py` reads Spot Testnet account and market data
  and validates symbol preparation/recovery.

These files contain top-level executable code rather than pytest test functions.
Importing them during collection therefore runs environment-dependent checks.
Run a smoke script explicitly only when its prerequisites are configured.

## Testnet credentials

The two Binance smoke scripts require a valid Binance Spot Testnet API key and
secret configured locally. An HTTP 401 error with code `-2014` means Binance
rejected the API-key format; it is not safe to bypass this error or substitute
live credentials. Do not commit credentials or share `.env` contents.

The smoke scripts are intended to be read-only/no-order checks. Do not enable
live trading or submit real orders to make tests pass.
