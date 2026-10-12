# TASK 000.8.4 — BL-004 Exception Isolation
Date: 2026-10-12 UTC

Source inspected: `execution_barrier.py`, `binance_client.py`, secret-redaction tests, SQLite lifecycle tests, and persistence fail-closed tests on the branch.

Observed design:
- transport exceptions are converted to sanitized reason text rather than copied with `str(exc)`;
- a public RuntimeError is raised after exiting the transport handler, to avoid retaining the original exception in `__context__`;
- selected tests assert absent `__cause__`/`__context__`, context suppression, and no synthetic raw error content in the public message;
- internal diagnostics preserve a redacted error type/classification and reconciliation-required semantics.

The CI run on SHA `d038813e4c5e3e3b1d9797891e6d0ddbe3800266` failed before the new exception-chain assertions in the affected test could all complete, due to the incorrect expected phrase. The assertion was corrected in commit `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`, but no test run on that SHA is available yet.

Not executed end-to-end on current SHA: traceback and repr inspection; all application logger/structured-event sinks; real isolated SQLite records; recovery diagnostics after restart; cancel-order and failed-reconciliation exceptions. No claim is made that every sensitive marker is absent from every consumer.

Status: **OPEN — FIX NOT VERIFIED**. BL-004 must remain open until exact-SHA CI passes and consumer-channel coverage is demonstrated.
