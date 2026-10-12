# TASK 000.8.3 — Targeted Regression
Date: 2026-10-12 UTC

## Actual test results
- On SHA `d038813e4c5e3e3b1d9797891e6d0ddbe3800266`, Python backend CI: **1 failed, 218 passed, 4 warnings**.
- Same SHA, Campaign Python CI: **1 failed, 229 passed, 4 warnings**.
- Failing test: `tests/test_execution_barrier_persistence_fail_closed.py::test_timeout_with_ambiguous_persistence_failure_reports_mandatory_reconciliation`.
- Failure was exact wording mismatch: test expected `mandatory before any retry`; actual safe error contains `reconciliation must be completed before any retry`.
- Commit `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0` changes the assertion to the exact sanitized public contract and retains checks for no `__cause__`, no `__context__`, `__suppress_context__ is True`, and no raw simulated transport/DB error text.
- No test process was run locally; the available environment has GitHub source/run APIs but no local repository checkout/test runner.
- No CI run has yet been observed for commit `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`. Therefore no target test or full regression is claimed as passing on that SHA.

Status: **OPEN — FIX NOT VERIFIED**.
