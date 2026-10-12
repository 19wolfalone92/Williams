# TASK 001.0 — BL-004 Exception Isolation
Date: 2026-10-12 UTC

## Baseline
- Inspected source SHA: `7c034dcb359a62cd8fedfa14f89f7072334c4900`.
- Existing prior report: `reports/task_0009_bl004_exception_matrix.md`.
- Existing Python CI is green on this SHA, but that does not establish Android exception-channel isolation.
- No code/test change was made for BL-004 in TASK 001.0.

## Observable-channel matrix
| Channel | Evidence at this task | Status |
|---|---|---|
| Public exception message | Android network errors may include `Binance <code>: <response body>`; no marker sweep across all mutation paths | **NOT VERIFIED** |
| `str()` | No Kotlin exception marker suite spanning transport and SQLite exceptions | **NOT VERIFIED** |
| `repr()` | Python side has selected existing tests; no complete combined all-channel matrix for current Android execution path | **NOT VERIFIED** |
| `__cause__` / `__context__` / suppression | Existing Python barrier tests cover selected Python paths only; not Android all-path proof | **PARTIAL** |
| Formatted traceback | No reproducible full synthetic marker traceback sweep for all required channels | **NOT VERIFIED** |
| Application/error logs | No captured Android log sweep using separate synthetic transport/database/sensitive markers | **NOT VERIFIED** |
| Structured events/diagnostic payloads | Trading audit persists events/raw JSON; no marker test proves no sensitive exception material reaches every payload | **NOT VERIFIED** |
| SQLite rows / persistent state | `TradingAuditStore.recordRestCall` stores raw response bodies after responses; no complete marker scan across all tables/columns | **NOT VERIFIED** |
| Recovery diagnostics after restart | No end-to-end marker test across process restart and reconciliation diagnostics | **NOT VERIFIED** |

## Required reproducible suite
Inject distinct synthetic strings, never real credentials:
- `TASK0010_TRANSPORT_MARKER_ONLY`
- `TASK0010_DATABASE_MARKER_ONLY`
- `TASK0010_SIMULATED_SENSITIVE_MARKER_ONLY`

Capture and scan public errors, string/repr equivalents, causes/contexts, formatted stack traces, Android logs, structured event JSON, every relevant SQLite column, persistent state, and post-restart recovery diagnostics. Assert both no marker leakage and preservation of operational facts: unresolved mutation state, reconciliation-required state, and new-entry block.

**Disposition: BL-004 OPEN.** The complete marker suite has not been implemented or run; no channel is represented as verified.
