# TASK 000.8.2 — CI Trigger Recovery
Date: 2026-10-12 UTC

## Fresh runs found for starting SHA d038813e4c5e3e3b1d9797891e6d0ddbe3800266
- Python backend tests: run `38233695660`, FAILURE.
- Williams Campaign CI: run `38233695652`, FAILURE overall; Python failed, Android build/static checks succeeded.
- Build Android APK: run `38233696947`, `startup_failure`, no jobs/steps exposed.

Python failure detail: 1 failed, 218 passed, 4 warnings. Campaign Python: 1 failed, 229 passed, 4 warnings. Both fail in `test_timeout_with_ambiguous_persistence_failure_reports_mandatory_reconciliation`; the test asserted the substring `mandatory before any retry`, while the safe public message says `reconciliation must be completed before any retry`. This is a test expectation mismatch, not evidence that the security contract should be weakened.

## Trigger configuration
- `.github/workflows/python-ci.yml`: pull_request trigger includes `tests/**` and `workflow_dispatch`.
- `.github/workflows/campaign-ci.yml`: pull_request trigger covers all branches and includes `workflow_dispatch`.
- `.github/workflows/android-apk.yml`: pull_request trigger includes `tests/**` only indirectly? Its path filter includes app, root Python, requirements, Gradle, and workflow files; it does **not** include `tests/**`. This is not a blocker for Python/Campaign CI.
- The GitHub connector exposes rerun failed jobs and rerun single-job operations, but no `workflow_dispatch`/workflow-dispatch invocation. Rerunning run 38233695660 or 38233695652 would check out their old SHA and would not validate the correction.
- A commit `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0` corrected only the expected public wording. At the time of query, no PR-triggered runs/status checks had appeared for that SHA.

The exact platform-side reason the latest push has not generated a run is not exposed by the available connector. Do not infer that workflows are disabled or that a security setting is missing without Actions event/permissions diagnostics.

Status: **OPEN — FIX NOT VERIFIED**. Required next action: repository owner/admin should run Python backend tests and Campaign CI on exact current branch HEAD using the GitHub Actions UI/API with authorized Actions write permission, then preserve logs/artifacts.
