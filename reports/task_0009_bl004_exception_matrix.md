# TASK 000.9.2 — BL-004 Exception-Isolation Matrix
Date: 2026-10-12 UTC

## Baseline and inspected implementation
- Inspected source SHA: `991b9d9e018af562af21749a58509d640dfbf87b`.
- Python CI evidence for selected existing tests: run `38234529997` and Campaign run `38234529976` attempt 2, both tied to test-fix SHA `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`; configured Python suites passed.
- Source inspected: `execution_barrier.py`, `binance_client.py`, `execution_recovery.py`, `tests/test_execution_barrier_persistence_fail_closed.py`, `tests/test_execution_barrier_secret_redaction.py`, `tests/test_binance_client_secret_redaction.py`, and `tests/test_execution_recovery_worker.py`.
- `ExecutionBarrier.execute` raises the sanitized public `RuntimeError` after leaving the transport exception handler using `raise ... from None`; selected tests assert sanitized wording and exception-chain properties. Persistence/recovery code stores fixed or type-only diagnostic strings in several paths. This is source inspection plus selected unit evidence, not full all-channel proof.

## Channel-by-channel matrix
| Observable channel | Evidence reviewed | Result |
|---|---|---|
| Public exception message | Existing persistence-failure regression test; passing configured Python CI on `a78a3f7...` | **PARTIAL — VERIFIED only for covered cases** |
| `str(exception)` | Same selected public-error assertions; no exhaustive marker sweep across every exception type/path | **INCOMPLETE** |
| `repr(exception)` | No exhaustive test over transport + database marker variants | **NOT VERIFIED** |
| `__cause__` | Selected tests inspect cause suppression on covered barrier failures | **PARTIAL** |
| `__context__` and `__suppress_context__` | Selected regression tests assert no retained public context for covered path; not all call stacks | **PARTIAL** |
| Formatted traceback | No reproducible traceback marker scan across all failure paths | **NOT VERIFIED** |
| Application/error logs | `ExecutionBarrier._record` and recovery logging inspected; no complete captured-log sweep | **NOT VERIFIED** |
| Structured events / diagnostic payloads | Selected code paths redact raw transport details; no full structured-event marker scan | **NOT VERIFIED** |
| SQLite rows and persisted state | Existing persistence tests cover selected fields and failure states; no marker sweep across all persisted columns/tables | **PARTIAL** |
| Restart/recovery/reconciliation diagnostics | Recovery-worker tests cover selected redaction/fail-closed behavior; no complete restart-channel marker sweep | **PARTIAL** |
| Successful recovery after injected failure | Selected worker tests cover active/terminal reconciliation and persistence failures; not all failure/recovery pairs | **PARTIAL** |

## Required synthetic marker campaign — not yet executed
A dedicated test must inject distinct non-secret values such as:
- transport marker: `SYNTH_TRANSPORT_MARKER_0009`;
- database marker: `SYNTH_DATABASE_MARKER_0009`;
- simulated sensitive marker: `SYNTH_SENSITIVE_MARKER_0009`.

Each marker must be searched in exception message, `str`, `repr`, cause/context, formatted traceback, captured application/error logs, structured events, every relevant SQLite row/field, and post-restart recovery diagnostics. The same tests must assert that reconciliation remains mandatory, unresolved durable state is retained, and new submissions remain blocked. No real credential may be used.

## Conclusion
Selected exception sanitization behavior has passing regression evidence, but **BL-004 remains OPEN**. No channel is marked fully verified without a reproducible artifact for that channel. Required engineering owner action: implement and execute the marker sweep and recovery-pair tests; preserve raw test output with synthetic values only.
