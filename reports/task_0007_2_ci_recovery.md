# TASK 000.7.2 — CI Recovery
Date: 2026-10-12 UTC

## Run results
| Run | Workflow | Result | Evidence |
|---|---|---|---|
| 38231994444 | Python backend tests | FAILURE | 1 failed, 218 passed, 4 warnings; stale test expected raw TimeoutError |
| 38231994418 | Campaign CI | CANCELLED | Python and Android jobs cancelled |
| 38231995378 | Android build | startup_failure | no jobs/steps exposed |
| 38232065247 | Python backend tests | FAILURE | test suite failure, not current source SHA |
| 38232065224 | Campaign CI | FAILURE/CANCELLED | Python failed; Android cancelled during build |
| 38232066353 | Android build | startup_failure | no jobs/steps exposed |
| 38219693589 | Android build | unavailable/startup failure evidence | no jobs exposed |
| 38232513469 | Python backend tests | FAILURE | 1 failed, 218 passed, 4 warnings |
| 38232513545 | Campaign CI | FAILURE overall | Android build/static checks SUCCESS; Python tests FAILURE (1 failed, 229 passed, 4 warnings) |
| 38232514514 | Android build | startup_failure | no jobs exposed |

Run 38232513545 produced APK and lint artifacts. Artifact metadata explicitly records head SHA `974596dbc72ea90d6bf1c08cc105151796440acf`. The Python run was on the same PR head as well. These runs do **not** test the later fix commit `956dbfe2f01a02877c4f461992edf0f937b6e5d1`.

## Confirmed current failure
`tests/test_execution_barrier_persistence_fail_closed.py::test_timeout_with_ambiguous_persistence_failure_reports_mandatory_reconciliation` expected a detailed persistence marker in the public exception. The implementation deliberately returns a sanitized stable public message when durable safety-state persistence fails. The test was revised to assert that safe public contract, no transport/database error text, and absent `__cause__`, `__context__`, with `__suppress_context__` true.

No workflow run has yet been returned for commit `956dbfe2f01a02877c4f461992edf0f937b6e5d1`. The connected GitHub actions do not expose a workflow-dispatch operation; rerunning an old run would test its old SHA and would not validate the fix. No historical result is represented as current verification.

Status: **OPEN — FIX NOT VERIFIED**. Owner/admin action needed to obtain CI on exact current HEAD if workflow triggering remains absent.
