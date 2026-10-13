# WILLIAMS BOT 2.1 — TASK 000.9 Final Verification Report
Date: 2026-10-12 UTC

## Baseline
- Expected task-start HEAD: `991b9d9e018af562af21749a58509d640dfbf87b`.
- Baseline verified: PR #66 OPEN/DRAFT/NOT MERGED; base `fix/pytest-safe-collection`; head branch `night-shift-audit-20261012`; exact head SHA matched expectation.
- No mutation was made to `main`; no merge, force-push, history rewrite, live Binance order, authenticated Binance mutation, or real credential was used.
- This task's code state was inspected at `991b9d9...`; the passing Campaign rerun validates test-fix SHA `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0`. A report-only commit must not be mistaken for code/test evidence.

## Evidence summary
| Requirement | Evidence | Disposition |
|---|---|---|
| Campaign Android CI completion | Run `38234529976`, attempt 2, SHA `a78a3f7...`: Python 230 passed/4 warnings; Android build + unit-test task + lint + artifact upload SUCCESS | **VERIFIED on test-fix SHA only** |
| Current-HEAD Campaign run | `38234750880` on `991b9d9...` CANCELLED, zero jobs/artifacts; same-ref concurrency group cancels in-progress runs | **NOT VERIFIED on current HEAD** |
| Standalone Android startup failure | Runs `38234751836` (current HEAD) and `38234530951` (test-fix SHA) startup_failure, zero jobs/logs/artifacts; retry endpoint 403 | **BLOCKED — ACCESS REQUIRED** |
| Debug APK digest and cert | APK SHA `795dda02251cf4fad631cc7966f4ef6372cb0c12a170e91797d14262d592f5c6`; Android Debug cert fingerprint recorded in Android security report | **VERIFIED for artifact identity only** |
| Trusted release signing | No expected certificate fingerprint; artifact is debug-signed | **BLOCKED** |
| BL-004 all-channel exception isolation | Selected tests pass, but no full marker sweep across repr/traceback/logs/events/SQLite/restart diagnostics | **OPEN — EVIDENCE INCOMPLETE** |
| 12-case order-recovery matrix | Existing tests cover subsets; full explicit matrix and per-case result fields not run | **OPEN — EVIDENCE INCOMPLETE** |
| Complete mutation-path registry | Python barrier/client inspected; Android has direct signed mutation call sites in `StandaloneRuntime.kt`; universal durable barrier not proven | **OPEN — P0 BLOCKER** |
| Android SCA/dependency integrity | Gradle dependencies partly version-pinned; no verified Android SCA or hash-locked dependency reproducibility | **OPEN — EVIDENCE MISSING** |
| Repository security controls | Workflow permissions and SHA-pinned actions partially inspected; admin-only branch protection/rulesets/Secrets/Environments inaccessible; shallow history prevents all-ref scan | **BLOCKED / INCOMPLETE** |

## Cancellation and startup findings
- `campaign-ci.yml` uses `concurrency.group: williams-ci-${{ github.ref }}` and `cancel-in-progress: true`. The current-HEAD run began 10:11:27Z; the prior test-fix rerun attempt 2 began 10:11:32Z on the same ref. This strongly supports a same-ref concurrency cancellation race; the canceled run itself exposes no job logs.
- Standalone Android runs failed before any job record was created. GitHub exposed no runner/step logs or artifacts. The authorized retry request returned 403. Exact distinction between runner allocation and workflow initialization cannot be established from accessible metadata.

## Required owner-assigned actions
1. **Repository Actions administrator:** inspect GitHub Actions service/runner diagnostics and trigger `.github/workflows/android-apk.yml` on an exact current SHA; retain event/check-suite diagnostics. Do not alter workflow permissions to force a run.
2. **Execution-engineering owner:** implement and execute the explicit 12-scenario deterministic mock-exchange fault-injection matrix with durable-state, exchange-state, mutation-count, reconciliation, entry-block, and final-state assertions.
3. **Execution-engineering owner:** complete synthetic transport/database/sensitive-marker propagation tests across exception, traceback, log, structured-event, SQLite, restart, and recovery channels.
4. **Android execution owner:** prove every reachable signed POST/DELETE/cancelReplace/OCO mutation uses a durable pre-submit intent and shared execution boundary; add fault tests for direct paths.
5. **Android/release owner:** provide expected release certificate SHA-256 fingerprint if release signing is intended; run Android dependency vulnerability analysis and establish dependency locking/integrity controls.
6. **Repository administrator:** provide branch protection/ruleset, Actions permissions, Secrets/Environments configuration evidence, and complete-history/all-ref secret-scan evidence. Do not expose secret values in reports.

## Final disposition
**TASK 000.9 NOT CLOSED. P0 NOT CLOSED.** The Campaign Android CI result is now explicit and artifact digests are recorded for the test-fix SHA, but the current-HEAD run, standalone workflow startup failure, BL-004 all-channel proof, complete order-recovery matrix, universal mutation-path containment, release trust, Android SCA, and repository-administration evidence remain unresolved. No live exchange mutations were performed.
