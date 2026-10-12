# TASK 000.7.3 — Exception Isolation
Date: 2026-10-12 UTC

## Source inspection
Reviewed branch versions of `execution_barrier.py`, `binance_client.py`, `tests/test_execution_barrier_secret_redaction.py`, `tests/test_binance_client_secret_redaction.py`, `tests/test_sqlite_lifecycle_safety.py`, and persistence-failure tests.

The submission path now catches transport errors, stores only a type classification and redacted marker in internal reason fields, attempts to persist AMBIGUOUS and RECONCILE_REQUIRED, and raises the public RuntimeError after leaving the original transport exception handler. This is materially safer than `raise ... from None` inside the handler because the original exception should not be retained as `__context__`.

The secret-redaction tests use synthetic API-key, API-secret, signature, Authorization, query token, and response-body markers. They assert no marker in public error strings, persisted reasons, and captured events; and assert `__cause__` and `__context__` are absent. TASK 000.7 adds the same chain checks to the persistence-failure path.

## Verification limits
The current code fix has not passed CI on its own SHA. The full requested consumer matrix—traceback formatting, repr, structured logger sinks, real SQLite records and recovery diagnostics—has not been executed end-to-end on the current SHA. Source inspection and assertions are not equivalent to a completed runtime test matrix.

Status: **OPEN — FIX NOT VERIFIED** for runtime regression; **INCOMPLETE — EVIDENCE MISSING** for all-consumer propagation proof.
