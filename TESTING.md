# Python test and smoke-test guide

## Default offline test suite

From the repository root, run:

```bash
python -m pytest -q --tb=short
```

The default pytest discovery is limited to `tests/`, which contains the offline
unit and API-contract tests. The repository root also contains many files named
`test_*.py` that are executable integration/operational scripts, not uniformly
offline pytest modules. Some make network requests or depend on local runtime
database artifacts, so they are not safe to run implicitly during collection.

The Python CI workflow separately invokes an explicit allowlist of offline
root-level tests.

## Operational smoke scripts

Run these explicitly, and only after their prerequisites are configured:

- `python safe_smoke_test.py` validates local runtime SQLite files and expected
  local database state. It requires local database artifacts that are not
  stored in Git.
- `python test_scanner_trader_dry_run.py` reads the Spot Testnet account and
  market data, then runs the scanner in a no-order flow.
- `python test_selected_symbol_prepare.py` reads Spot Testnet account and
  market data and validates symbol preparation/recovery.

These scripts contain top-level executable code rather than pytest test
functions. Do not run them automatically in an offline test suite.

## Testnet credentials

The two Binance smoke scripts require a valid Binance Spot Testnet API key and
secret configured locally. An HTTP 401 error with code `-2014` means Binance
rejected the API-key format; it is not safe to bypass this error or substitute
live credentials. Do not commit credentials or share `.env` contents.

The smoke scripts are intended to be read-only/no-order checks. Do not enable
live trading or submit real orders to make tests pass.
