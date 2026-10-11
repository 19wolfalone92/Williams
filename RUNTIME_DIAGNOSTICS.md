# RUNTIME_DIAGNOSTICS — TASK 006

**Status: BLOCKED — no physical-device runtime sample available**  
This document defines the required sanitized evidence; it does not claim the values were collected. Do not place API keys, signatures, secret headers, signed payloads, or raw credential objects in diagnostics.

## Required runtime fields

| Component | Required fields | Collection status |
|---|---|---|
| Credentials | configured, read_success, auth_result, last_error_category | NOT RUN |
| REST | connected, last_success, last_error, HTTP status | NOT RUN |
| WebSocket | connected, last_message_time, reconnect_count, last_error | NOT RUN |
| History | symbol, timeframe, candle_count, last_candle_time | NOT RUN |
| Scanner | running, symbols_total, symbols_enabled, symbols_scanned | NOT RUN |
| Balances | fetch_success, asset_count, last_success, last_error | NOT RUN |
| SQLite | database_open, schema_version, integrity_result, last_commit | NOT RUN |
| FSM | current_state, recovery_result, reconciliation_result | NOT RUN |
| Execution Gate | safe_mode, kill_switch, entry_blocked, block_reason | NOT RUN |
| Backend | required_for_execution, connected, last_error | NOT RUN |

Every event must include UTC timestamp, run ID, app version, APK SHA-256, tested source SHA, component, result, and blocker/error category. Never log secret values.

## Required diagnostic chain for `symbols=0`

`Credentials/Config → REST Connectivity → Symbol Universe → Market Data → SQLite Persistence → Scanner Input → Strategy Eligibility`

For every edge, capture sanitized input count/state, result count/state, and error category. Identify the first failing edge instead of reporting only `scanning=true`.

## Independent component checks

1. Credentials configured/read/auth result must be distinct states.
2. REST health and market WebSocket health/freshness must be reported independently.
3. Kline history request, response validation, persistence, and last candle timestamp must be separated.
4. WebSocket reconnect must reset freshness until a new valid message is received.
5. Symbol universe must be the project-approved Spot USDT pair set; do not expand it during diagnostics.
6. Scanner must expose input symbol count and successfully processed count, not merely a running flag.
7. Balances must distinguish unavailable/unauthorized/empty response from a valid zero balance.
8. SQLite integrity or read/write failure must force a safe blocked state where required.
9. Backend reachability must be distinguished from local runtime readiness.

## Collection result

No device runtime data, raw ADB logs, network capture, SQLite snapshot, or Testnet response was collected during this task run. All fields remain NOT RUN. No sample values have been fabricated.
