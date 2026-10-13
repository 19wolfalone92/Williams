# TASK 000.8.4 — BL-004 Exception Isolation
Date: 2026-10-12 UTC

## CI result
On test-fix SHA `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`:
- Python backend CI `38234529997`: SUCCESS, 219 passed, 4 warnings.
- Campaign Python job `38234529976`: SUCCESS, 230 passed, 4 warnings.
- The persistence-failure test now passes in the configured suites with assertions for sanitized public wording, no `__cause__`, no `__context__`, `__suppress_context__ is True`, and absence of raw synthetic transport/DB text.

## Scope limits
Source review supports raising a sanitized public RuntimeError outside the transport handler. Existing secret-redaction tests use synthetic sensitive markers and assert no raw text in selected public/state/event sinks. However, a complete end-to-end consumer matrix has not been proven for traceback/repr, every application logger, every structured event, real isolated SQLite rows, recovery diagnostics after restart, cancellation failures, and reconciliation failures. The current CI pass verifies configured test suites, not every requested channel/scenario.

Status: **OPEN — FIX NOT VERIFIED** for full BL-004 closure. The specific regression assertion is verified on test-fix SHA; BL-004 remains open until all required propagation channels are demonstrated.
