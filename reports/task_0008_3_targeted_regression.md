# TASK 000.8.3 — Targeted Regression
Date: 2026-10-12 UTC

## Actual results on test-fix SHA `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`
- Python backend run `38234529997`: SUCCESS; **219 passed, 4 warnings** in 4.46s. The job completed release contract gate, dependency audit, compile, full static safety audit, and configured safe offline tests.
- Campaign Python job in run `38234529976`: SUCCESS; **230 passed, 4 warnings** in 4.80s.
- The corrected test `test_timeout_with_ambiguous_persistence_failure_reports_mandatory_reconciliation` is included in the passing configured suites; the old assertion mismatch no longer appears.
- Warnings are FastAPI `on_event` deprecations in `server.py`; they did not fail these jobs.
- Local execution was not performed; evidence is from GitHub-hosted CI. The run checked out the exact test-fix SHA `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`.

The configured suites passed; this is not a claim that every repository test file or the complete recovery matrix was executed. Campaign Android job was still running at report update.

Status: **CLOSED — VERIFIED** for the configured Python CI suites on test-fix SHA; broader fault-injection and end-to-end consumer coverage remain incomplete.
