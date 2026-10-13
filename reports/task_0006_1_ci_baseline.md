# TASK 000.6.1 — Baseline & CI result recovery

Audit date: 2026-10-12 UTC.

## PR baseline
- PR #66 exists, OPEN / DRAFT / NOT MERGED.
- Head branch: `night-shift-audit-20261012`.
- Base branch: `fix/pytest-safe-collection`, base SHA `0007e79b1afde86b8c736128e6d09bfdd370106f`.
- User-provided starting HEAD: `94ec14b35f0a37ddae8e5f8e4100efc5061a8577`; confirmed by PR metadata at task start.
- Final code HEAD at report creation: `8f40eeca38eab84a26b96bebb2d7b1bee8edc223`.
- PR still has previous TASK 000.5 files and new tests. No merge, force-push, or main mutation.

## Prior runs and exact source identity
### Python backend tests — run 38231994444
- Job `backend-test` completed FAILURE.
- The artifact metadata identifies workflow head SHA `3bdafc120a9d7f8d4057e058c71d6eb6ff6b83e3`, not the user-provided starting HEAD `94ec14b...` and not the final code SHA `8f40eec...`.
- Steps: setup, checkout, Python setup, dependency install, Python dependency audit, release contract gate, production compile, static safety audit all SUCCESS; `Run safe offline tests` FAILURE; pytest diagnostics upload SUCCESS.
- Actual pytest result: **1 failed, 218 passed, 4 warnings**.
- Failing test: `tests/test_sqlite_lifecycle_safety.py::test_ambiguous_submission_survives_restart_and_blocks_blind_retry`.
- Cause: the test still expected raw `TimeoutError` propagation after the security fix intentionally changed the public result to a sanitized `RuntimeError`. This was a real regression-test mismatch, not evidence of a secret leak.

### Williams Campaign CI — run 38231994418
- Both reported jobs, `Android build and static checks` and `Python tests`, concluded CANCELLED.
- This is not a passing result and contains no usable final test evidence.

### Build Android APK — run 38231995378
- Run conclusion `startup_failure`; jobs endpoint returned an empty list; artifacts endpoint returned no artifacts.
- No job/step failure message exists in the available API response, so this run does not prove a workflow-code failure.

### Additional runs associated with earlier SHA
- Run `38232065247` Python backend tests also concluded FAILURE; not associated with final code SHA.
- Run `38232065224` Campaign CI was in progress at query time.
- Run `38232066353` Android build ended `startup_failure`, with no jobs.
- Workflow-run lookup for final SHA `8f40eeca38eab84a26b96bebb2d7b1bee8edc223` returned no PR-triggered runs at query time. This means current code is not yet CI-verified.

## Remediation after CI failure
At verified HEAD `94ec14b...`, the failing test was reproduced from CI logs. The execution barrier was then changed so the sanitized public exception is raised after leaving the transport exception handler, avoiding the original exception remaining accessible through `__context__`. The SQLite restart test and secret-redaction test now assert both `__cause__ is None` and `__context__ is None`.

Commits:
- `6609f8dab1c9489e29a70134b4741282d96a3938` — detach public error from transport exception chain.
- `29f80e65b90bfa5ee7a98f468baee3861ba3cefa` — assert exception-chain absence in restart test.
- `8f40eeca38eab84a26b96bebb2d7b1bee8edc223` — assert exception-chain absence in redaction test.

## Status
**OPEN — FIX NOT VERIFIED**. CI failure cause is identified and test expectation corrected, but no fresh run result exists for final HEAD. Local uncommitted-tree status cannot be inspected through the GitHub API integration.