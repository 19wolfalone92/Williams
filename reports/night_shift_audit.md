# Williams — Night Shift Engineering Audit

**Audit timestamp:** 2026-10-12 (UTC date)  
**Repository:** `19wolfalone92/Williams`  
**Audited PR:** [#65](https://github.com/19wolfalone92/Williams/pull/65)  
**Audit branch:** `night-shift-audit-20261012`  
**Method limitation:** This session had GitHub repository/API and Actions-log access, but no attached local checkout or execution shell for the project. Findings below distinguish repository/CI evidence from tests executed directly in this session. No claim is made that a full clean-room test suite ran locally.

## 1. Executive summary

- Confirmed CI state on PR #65 head `0007e79b1afde86b8c736128e6d09bfdd370106f`:
  - Python backend workflow run [38176499328](https://github.com/19wolfalone92/Williams/actions/runs/38176499328): **PASS**. Release contract gate, Python compilation, full safety audit and explicit offline test step all completed successfully. The Actions job summary available here does not expose the pytest count or elapsed time.
  - Android APK workflow run [38176499309](https://github.com/19wolfalone92/Williams/actions/runs/38176499309): **FAIL** at required Android lint. Downstream unit tests and APK builds were skipped because lint failed.
- Confirmed Android lint root cause: `AndroidGradlePluginVersion` reports that Gradle 8.14.6 is available while `gradle/wrapper/gradle-wrapper.properties` pins 8.14.5; lint treats this finding as an error. This is not evidence of an application-source lint defect.
- Confirmed PR #65 change in `.github/workflows/python-ci.yml` adds `pytest.ini` and `TESTING.md` to the **push** path filter, but omits them from the **pull_request** path filter. A PR changing only those files can therefore fail to trigger this workflow. Corrected in this audit branch.
- PR #65 remains open; no merge was performed. No trading API calls or orders were initiated by this audit.

## 2. Fixed / proposed changes in this audit branch

### AUDIT-CI-001 — Python workflow pull-request path filter
- **Priority:** P1 (CI coverage gap).
- **Evidence:** The workflow's push filter includes `pytest.ini` and `TESTING.md`, while the pull-request filter did not.
- **Root cause:** The new paths were added to only one trigger's filter.
- **Change:** Added both paths to `pull_request.paths`.
- **Validation:** Repository diff inspection only in this session. GitHub Actions must validate the committed branch; no local workflow linter was available.

### AUDIT-ANDROID-001 — Android lint blocks build on Gradle version finding
- **Priority:** P1 (Android build/release pipeline blocked).
- **Evidence:** Actions job `114579902614`, run [38176499309](https://github.com/19wolfalone92/Williams/actions/runs/38176499309), reports: `gradle/wrapper/gradle-wrapper.properties:3` — newer Gradle 8.14.6 is available than 8.14.5 (`AndroidGradlePluginVersion`). Lint reported 1 error, 0 warnings, 2 hints.
- **Root cause:** The wrapper distribution and workflow-installed Gradle were pinned to 8.14.5; the configured lint policy has `warningsAsErrors = true`.
- **Change:** Updated wrapper distribution to Gradle 8.14.6 and aligned Android/Campaign workflow Gradle versions to 8.14.6. No lint rules were disabled and no suppressions were added.
- **Validation:** Confirmed by fresh CI on audit branch HEAD `ff7842d93c8d8c8a8053a4192d802d387120051a`: required Android lint PASS, Android unit tests PASS, debug APK build PASS, release unsigned APK build PASS, APK file/metadata checks PASS, and persistent debug signing certificate check PASS. See Actions runs 38177124662 and 38177124654.
- **Residual risk:** CI verifies compilation, lint, unit tests, packaging, and the configured debug signing check; it does not replace installation/launch tests on a physical supported device.

## 3. PR #65 change review

- `pytest.ini` sets `testpaths = tests`. This avoids implicit collection of root-level `test_*.py` scripts that may execute operational code during import. This is a reasonable safety boundary only if the explicit CI allowlist continues to cover required root-level deterministic tests.
- `TESTING.md` documents offline tests and separates Spot Testnet smoke scripts requiring credentials. The docs correctly warn against live credentials and live trading.
- Python CI explicitly lists offline tests and separately runs the release contract gate, compilation and static safety audit. Its latest recorded PR run passed.
- `ci_release_gate.py` expects `4.25.0`. The inspected Android Gradle configuration declares `versionName = "4.25.0"`; the PR's server-side version check also expects `4.25.0`. The change from 4.24.0 is supported by these checked version declarations, but it is a string-based release contract rather than a central version source.
- **Important residual concern:** the new default `testpaths = tests` does not itself run root-level tests. The Python workflow's explicit allowlist is therefore part of the safety contract and must not be accidentally reduced. No evidence in the PR diff showed tests deleted or disabled.

## 4. CI/CD results

| Check | Evidence | Result |
|---|---|---|
| Python backend workflow | Run 38176499328, all listed steps completed successfully | PASS (CI-reported) |
| Release contract gate | Same Python run, step 5 | PASS (CI-reported) |
| Python compilation | Same Python run, step 6 | PASS (CI-reported) |
| Full static safety audit | Same Python run, step 7 | PASS (CI-reported) |
| Explicit offline test command | Same Python run, step 8 | PASS (CI-reported); exact test count unavailable in inspected summary |
| Android lint (baseline) | Run 38176499309, job 114579902614 | FAIL: Gradle 8.14.5 version finding; superseded by passing post-fix run |
| Android unit tests (baseline) | Same baseline run | SKIPPED after lint failure |
| Debug/release APK build (baseline) | Same baseline run | SKIPPED after lint failure |
| Campaign CI on audit branch | Run 38177124662 | PASS: Android build/static checks and Python tests |
| Android APK workflow on audit branch | Run 38177124654 | PASS: lint, unit tests, debug APK, release unsigned APK, metadata/signature checks, artifact upload |
| Python backend workflow on audit branch | Run 38177124730 | PASS: 148 passed, 4 warnings in 3.87s |
| Post-fix validation | Commit `ff7842d93c8d8c8a8053a4192d802d387120051a` | PASS for all three GitHub Actions workflows; no device-level install/launch test performed |

## 5. Security and trading safety

- The audit did not invoke exchange endpoints, use credentials, submit orders, or modify balances.
- The documented `-2014` Binance API-key format error is not proven to be a code defect from the inspected evidence. It remains an environment/credential configuration issue until safely reproduced with valid Spot Testnet credentials; never replace test credentials with live credentials.
- No secret values are included in this report. A complete repository-history secret scan and dependency vulnerability scan were not executed in this session.
- Production trading thresholds and trading strategy logic were not changed.

## 6. Repository structure and architectural observations

The inspected repository tree contains Python trading/backend modules, a Kotlin Android application, Gradle configuration, GitHub Actions workflows, root-level deterministic tests, `tests/` unit/contract tests, and operational/testnet scripts. The empty `README.md` (zero-byte blob in the inspected tree) is a documentation defect: onboarding depends on other Russian-language guides. No change was made because a complete replacement README requires product/operational confirmation.

The release gate uses source-text assertions and AST parsing, which is fast but brittle: harmless refactors can break the gate, and matching strings does not prove runtime behavior. Treat it as a contract smoke check, not as a substitute for behavioral tests.

## 7. Checks not completed in this session

These checks were **not** run directly by this session and must not be interpreted as PASS:

- local clean checkout, `git status`, stash/submodule/upstream inventory;
- local `pytest -q`, isolated import sweep, pytest collection network guard, and test timing;
- local Ruff/mypy/Bandit/dependency vulnerability scans or secret-history scan;
- Android lint/build/unit tests after the Gradle change, emulator/lifecycle/UI tests;
- database migration/rollback/corruption and missing-artifact tests;
- mocked API failure matrix, order-state/idempotency and risk-limit tests;
- authenticated Spot Testnet checks;
- full PR/base compatibility and complete diff review in a local worktree;
- verification of all workflow path filters and all historical CI failures.

The environment limitation is lack of a local checkout/runtime in this session. GitHub Actions evidence below applies to code/configuration commit `ff7842d93c8d8c8a8053a4192d802d387120051a`. The current branch HEAD is a documentation-only follow-up commit (`ec22d42eb2301a062f9bdad9f332fe467e78255a`); the CI workflows have not been observed on that documentation-only SHA, and no source or workflow configuration changed in that follow-up.

## 8. Remaining risks and next actions

- **P1 — resolved in CI:** Gradle 8.14.6 resolves the recorded lint failure; required lint, Android unit tests, debug/release APK builds and APK verification all passed on the audit branch.
- **P1 — historical run:** The older Campaign CI failure was caused by the same Gradle 8.14.5 lint finding; the corresponding post-fix Campaign CI now passes.
- **P2:** Run default pytest in a clean environment with network disabled and no credentials; record exact test count/time and prove collection has no operational side effects.
- **P2:** Determine whether `data/test_tmp/trader.sqlite3.clean_20261004_082723` is a generated artifact or a stale test prerequisite; fix the owning fixture/script, not by creating a placeholder.
- **P2:** Verify testnet API-key source/configuration and testnet-vs-mainnet selection without printing credential values.
- **P2:** Perform a complete security/dependency/history scan and review Android manifest, network security, exported components, and local secret storage.
- **P3:** Restore useful project-level README onboarding and align version metadata to a single source of truth if compatible with existing release rules.

## 9. Git and PR state

- PR #65 head at inspection: `fix/pytest-safe-collection`, commit `0007e79b1afde86b8c736128e6d09bfdd370106f`.
- PR #65 base: `audit/task-0007-api-contract`, commit `939ceac0bf34ef10cb6551301be9b8758a4a4c59`.
- Separate audit branch created: `night-shift-audit-20261012`, based on PR #65 head.
- No branch deletion, force push, history rewrite, target-branch change, or merge was performed.
- The CI/configuration fixes are verified by successful runs 38177124730, 38177124662 and 38177124654 on code/configuration commit `ff7842d93c8d8c8a8053a4192d802d387120051a`. Current branch HEAD is documentation-only commit `ec22d42eb2301a062f9bdad9f332fe467e78255a`; the broader audit remains incomplete as described above.


## 10. Follow-up audit — 2026-10-12

### AUDIT-TRADE-001 — execution-intent persistence was fail-open (P0)

- **Evidence at prior audit HEAD** `e2493f9f20dbcaa5cc2c233a4e81d3642c642500`: `execution_barrier.py` implemented `_persist()` with `except Exception: pass`; a missing DB or missing `save_execution_intent` method also allowed execution to continue. The barrier could call the exchange submission callback without durably recording a `PENDING` intent. The source also contained duplicate FSM transitions and duplicated exchange-response validation blocks.
- **Risk:** after a crash or ambiguous exchange response, local state might not establish that a submission was attempted; an unsafe retry could duplicate an order.
- **Change committed:** make intent persistence mandatory and fail closed; durably mark `RECONCILE_REQUIRED` for ambiguous outcomes; treat post-submit persistence failure as ambiguous without resubmitting; add mock-only regression tests and include them in Python CI.
- **Regression coverage:** `tests/test_execution_barrier_persistence_fail_closed.py` covers missing persistence, PENDING/SUBMITTED/AMBIGUOUS write failures, and timeout/no-retry behavior with mocks only.
- **Validation status:** the focused tests are included in the explicit Python CI command. The new commit's workflow results must be checked before this fix is marked PASS. No local checkout/runtime is available in this connector session; no local test execution is claimed.
- **Trading safety:** no exchange endpoint was called and no order or balance mutation was performed by this audit.

### CI observed on prior audit HEAD

- Python backend run `38199984673`: completed successfully; release gate, compilation, static safety audit and configured offline tests succeeded. Exact test count was not exposed in the job-step summary.
- Campaign CI run `38199984689`: Python tests succeeded; Android build/lint was still running at the last poll.
- Android APK run `38199984681`: release gate, source validation, Android lint and Android unit tests succeeded; APK packaging checks were still running at the last poll.
- These results apply only to `e2493f9f20dbcaa5cc2c233a4e81d3642c642500`, not to the follow-up commit.


### CI follow-up for execution-barrier regression tests

- Python backend run [38200218167](https://github.com/19wolfalone92/Williams/actions/runs/38200218167) on `cbf6353c13d16fe14b9160a0e0aa579193ea0ff4`: **FAIL**, 2 failed, 154 passed, 4 warnings in 4.46s.
- Both failures were incorrect expected diagnostic substrings in the newly ported mock tests, not failures of the fail-closed behavior: the configured mock fails persistence of the `AMBIGUOUS` intent while the durable reconciliation-state write succeeds. The tests incorrectly expected the reconciliation-state write itself to fail.
- Corrected the assertions to verify the actual ambiguous-intent persistence failure and the mandatory-before-retry diagnostic. This preserves the safety assertions and does not bypass or remove tests.
- **Fresh validation PASS:** Python backend run [38200332135](https://github.com/19wolfalone92/Williams/actions/runs/38200332135) on code/test SHA `d0affea85a37b9190001dcb1faa99de207304693` completed: release contract gate, Python compile, static safety audit and offline tests passed; pytest reported `156 passed, 4 warnings in 4.92s`.
- **Fresh validation PASS:** Campaign CI Python job on run [38200327718](https://github.com/19wolfalone92/Williams/actions/runs/38200327718), same SHA, included the focused execution-barrier regression module and reported `167 passed, 4 warnings in 4.45s`.
- Android jobs for this SHA were still running at the last poll; no Android PASS is claimed until the run concludes.


- Follow-up CI routing note: the GitHub ref update did not create a fresh Python backend run for the assertion-only follow-up commit. To ensure the focused safety tests are actually executed on the latest tree, added the same test module to the all-branches Campaign CI deterministic test list. The Campaign CI run on the resulting commit is the required evidence; until it finishes, the focused regression suite remains **NOT YET VERIFIED**.


## 11. Final state at report update

- Source/test/workflow commit whose Python checks passed: `d0affea85a37b9190001dcb1faa99de207304693`.
- The report-only follow-up commit records these exact-SHA results; it does not change Python, Android, or workflow source.
- P0 execution-intent persistence is now fail-closed in the audit branch and covered by mocks; CI verifies the corrected regression tests. SQLite artifact provenance, complete repository/history secret scanning, and end-to-end order restart/reconciliation scenarios remain outstanding and are not declared resolved.
- Android lint/build checks on the source/test commit were still in progress at the last poll.


## 12. Follow-up audit — current evidence as of 2026-10-12 03:43 UTC

### Execution barrier: malformed/ambiguous exchange response

- **Finding:** After a submission callback returned a dictionary without an authoritative order status, the barrier wrote `AMBIGUOUS` without handling persistence failure and raised immediately. If that write failed, the branch did not attempt to durably set `position_state=RECONCILE_REQUIRED`; a subsequent process could lack a persistent global reconciliation lock.
- **Fix commit:** `2ebf843240fa233ef07d46117c52efe6cd35c9a9`. The non-authoritative response path now attempts both ambiguous-intent persistence and durable reconciliation-state persistence, records failures in the raised diagnostic, and never retries the exchange submission.
- **Regression coverage:** added mock-only tests that force the `AMBIGUOUS` intent write to fail for a non-authoritative response and assert the global reconciliation lock is set and a second submit callback is not invoked.
- **Python backend validation:** run [38200802961](https://github.com/19wolfalone92/Williams/actions/runs/38200802961), exact source/test SHA `2ebf843240fa233ef07d46117c52efe6cd35c9a9`: **PASS**, `158 passed, 4 warnings in 3.04s`.
- **Campaign Python validation:** run [38200798812](https://github.com/19wolfalone92/Williams/actions/runs/38200798812), same SHA: Python job **PASS**, `169 passed, 4 warnings in 4.77s`.
- **Android validation on this exact SHA:** runs [38200802985](https://github.com/19wolfalone92/Williams/actions/runs/38200802985) and [38200798812](https://github.com/19wolfalone92/Williams/actions/runs/38200798812) were still running at this report snapshot. Do not interpret as PASS. The previous report-only SHA `0ca7639bc19f6235eee61a12c83befd365126a1c` did have successful Android APK run [38200424540](https://github.com/19wolfalone92/Williams/actions/runs/38200424540) and Campaign CI run [38200424541](https://github.com/19wolfalone92/Williams/actions/runs/38200424541), but those results do not validate the new barrier source change.

### SQLite data/artifact investigation

- **Artifact tracking:** `data/test_tmp/trader.sqlite3.clean_20261004_082723` is **not present in the recursive Git tree** at audit HEAD, and the GitHub commits-by-exact-path query returned an empty list. This establishes that the exact path is not tracked in the inspected current tree and no path-specific commit was returned; it does **not** establish who created it or whether a local/unavailable artifact exists. Provenance remains **BLOCKED** pending access to the artifact or its originating environment.
- **Protection already present:** `.gitignore` excludes `data/`, SQLite WAL/SHM files, `data/test_backups/`, and `data/test_tmp/`. `test_db_guard.py` resolves configured paths and blocks the exact production path unless the explicit opt-in `WILLIAMS_ALLOW_PROD_DB_TEST=1` is set. Several DB tests create temporary databases and use mocks; the deterministic CI suite passed on the SHAs cited above.
- **Residual risk:** path guard inspection does not prove that all tests restore modified environment variables, close every connection, or protect against hard-link aliases. No local SQLite artifact was opened, copied, deleted, restored, or modified. WAL checkpoint/backup restore, corruption, lock contention, migration rollback, and close lifecycle were not directly exercised in this connector session. Overall SQLite lifecycle/recovery status: **IN PROGRESS**; exact artifact provenance: **BLOCKED**.

### Binance API / order recovery

- `BinanceSpotClient` defaults to `https://testnet.binance.vision`; the `Trader` constructor defaults `TESTNET` to true and reads API key/secret from environment variables. The manual workflow `.github/workflows/testnet-readonly.yml` sets `TESTNET=true`, `ALLOW_LIVE=false`, and uses dedicated Testnet secret names; it is workflow_dispatch-only and invokes a read-only gate.
- The client marks transport timeout/connection failures on POST/DELETE as `unknown_execution` and does not blindly retry them; GET requests can retry. A signed `-1021` timestamp rejection has a one-time time-sync retry. This was code inspection, not a live exchange test.
- Binance `-2014` root cause remains **NOT RUN / unconfirmed** in this audit: no authenticated request was sent and no credential values were inspected or printed. No live or Testnet trading order was submitted.

### Security and CI/CD review

- **Current tracked sensitive-looking paths:** the recursive tree showed `.env.example` and `deploy/.env.example`, but no tracked `.env`, private key, PKCS#12, PEM, or SQLite database file at current HEAD. The example environment file contains blank credential placeholders and a replace-me mobile token, not a live credential as inspected.
- **Android network configuration:** main config denies cleartext globally and allows it only for exact `localhost` and `127.0.0.1`; release overlay denies cleartext globally. Main manifest declares INTERNET, notification and foreground-service permissions; app backup is disabled and widget/service components are not exported. Release manifest disables cleartext.
- **GitHub Actions:** inspected Campaign and read-only Testnet workflows declare `permissions: contents: read`; Python workflow does not declare an explicit top-level permissions block, so effective token permissions depend on repository/org defaults. Actions are referenced by version tags in several workflows; immutable SHA pinning and dependency vulnerability review remain outstanding.
- **Secret scan status:** the recursive current-tree filename check is a limited inspection, not a full content/history secret scan. GitHub code-search responses were incomplete and returned no usable findings; therefore full current-file and Git-history secret scanning is **BLOCKED**, not PASS. No secret values were copied into this report.

### Status summary at this report snapshot

| Area | Status | Evidence / limitation |
|---|---|---|
| Fail-closed PENDING persistence | PASS | Regression test and Python CI on `2ebf843` |
| SUBMITTED/AMBIGUOUS persistence failure and no blind retry | PASS (covered scenarios) | Mock regression tests + Python CI on `2ebf843`; no exchange calls |
| Malformed exchange response persistence failure | PASS | New regression tests + Python CI on `2ebf843` |
| Python backend CI | PASS | Run 38200802961; 158 passed |
| Campaign Python tests | PASS | Run 38200798812 Python job; 169 passed |
| Android lint/unit tests/APK on `2ebf843` | IN PROGRESS | Runs 38200802985 and 38200798812 at snapshot time |
| Android + Campaign CI on previous `0ca7639` | PASS | Runs 38200424540 and 38200424541; do not transfer this status to newer SHA |
| SQLite artifact provenance | BLOCKED | Artifact absent from Git tree; no local artifact access |
| SQLite isolation/lifecycle/recovery matrix | IN PROGRESS | Static inspection and existing CI only; matrix not executed |
| Binance `-2014` root cause | NOT RUN | No authenticated call; no credential access |
| Full secret/history scan | BLOCKED | No full scanner/history corpus available via this connector session |
| Android manifest/network review | PASS (static inspection only) | Current manifest and network config inspected |
| Workflow immutable action pinning/dependency audit | NOT RUN | No dependency vulnerability scan or action SHA migration performed |
| Real trading operations | NOT RUN by design | No orders, account mutations, or live credentials used |

The audit is still open. Do not merge PR #66 automatically. Recheck Android workflow conclusions against their exact `head_sha` before closing this audit.


## 13. Final follow-up — execution idempotency, SQLite lifecycle and backup integrity

### Source changes and regression coverage

**Execution barrier and persistent duplicate prevention**
- Commits: `11f7182fc7a0dab8d9c81f2f950a73698a956846` and `1c522a1405e005f90235e7f495b83341dd655ee7`.
- The barrier now requires both durable intent persistence and atomic `try_claim_state` support before any submission. A stable key `execution_intent_claim:<intent_id>` is atomically claimed in SQLite; a duplicate claim is rejected. This protects duplicate calls across barrier instances and process restarts, not only calls serialized by one in-memory lock.
- Added a durable query for unresolved `PENDING`/`AMBIGUOUS` intents. If that query fails or finds an unresolved intent, the barrier refuses a new submission. If writing `RECONCILE_REQUIRED` fails after an uncertain outcome, the prior unresolved intent row (or its original `PENDING` status if ambiguity updates also fail) remains a second durable block.
- Regression tests cover PENDING/SUBMITTED/AMBIGUOUS write failures, submission timeout, malformed exchange response, state-read failure, unresolved-intent query failure, failed reconciliation-state write, duplicate intent ID, restart-style database reopen, and two parallel barrier instances using separate SQLite connections. All exchange calls are mocks.

**SQLite lifecycle and production path guard**
- Commit: `4201ea596843b4a006410e86f945c752ceb87ed6`.
- Added `Database.close()` and context-manager support to make connection closure explicit.
- Strengthened `test_db_guard.py` to reject a hard-link alias of the configured production DB, rather than checking only resolved path strings.
- Added temporary-database tests for close/reopen persistence, transaction rollback, hard-link guard behavior and SQLite online backup/restore roundtrip.
- Commit `646e02f1530779d89504e8a7643c43932bc35419` changes `deploy/backup_vps.sh` to use Python's SQLite online backup API before archiving the snapshot. The prior script archived only `trader.sqlite3` while WAL could be active and suppressed tar errors, which could omit committed WAL pages or create an empty/invalid backup. A static regression test asserts the script uses online backup and strict shell failure handling. The VPS backup script itself was **not executed** during this audit.
- Commit `a688654466efdbd1a0e6ac46a492f30f28c1d0fb` adds real-SQLite tests for a claim surviving a database close/reopen and for two concurrent barrier instances sharing one database. These tests use only `tmp_path` databases.

### Exact-SHA CI evidence

- Python backend run [38201612560](https://github.com/19wolfalone92/Williams/actions/runs/38201612560), SHA `a688654466efdbd1a0e6ac46a492f30f28c1d0fb`: **PASS**, `170 passed, 4 warnings in 6.84s`; static safety audit reported zero failures.
- Campaign Python job in run [38201612675](https://github.com/19wolfalone92/Williams/actions/runs/38201612675), same SHA: **PASS**, `181 passed, 4 warnings in 4.60s`. Campaign overall was still **IN PROGRESS** at the last poll because its Android job had not completed.
- Campaign Python job in run [38201609354](https://github.com/19wolfalone92/Williams/actions/runs/38201609354), same SHA: **PASS**, `181 passed, 4 warnings in 4.60s`; Android job was still running at last poll.
- Android APK run [38201612690](https://github.com/19wolfalone92/Williams/actions/runs/38201612690), same SHA, remained **IN PROGRESS** at the last poll in required Android lint. No final status is inferred from a running job.
- Android APK run [38200901495](https://github.com/19wolfalone92/Williams/actions/runs/38200901495) and Campaign runs [38200901513](https://github.com/19wolfalone92/Williams/actions/runs/38200901513) / [38200898686](https://github.com/19wolfalone92/Williams/actions/runs/38200898686) passed on SHA `945efe96eea7543852c94265682e26d50cc54d42`. Those runs validate that earlier source/configuration snapshot only; do not attribute them to later Python/SQLite changes.
- Several Campaign runs for intermediate commits were cancelled when newer commits arrived. Cancellation is not a test failure and not a pass.

### Remaining checks / limitations

| Area | Status | Evidence / limitation |
|---|---|---|
| Durable duplicate-claim and restart-style reopen tests | PASS | Backend and Campaign Python jobs on `a688654` |
| Concurrent same-intent submission test | PASS (CI-reported) | Real SQLite temporary DB, two separate connections, Campaign Python run on `a688654` |
| SQLite transaction rollback / online backup-restore roundtrip | PASS (CI-reported) | Temporary databases only, Campaign Python run on `a688654` |
| SQLite SQLITE_BUSY/long lock contention, corruption recovery, migration rollback matrix | NOT RUN | Not exercised by the new regression module |
| VPS backup script end-to-end on an isolated container | NOT RUN | Script was inspected and a static contract test was added; production backup script was not run |
| Exact artifact `data/test_tmp/trader.sqlite3.clean_20261004_082723` provenance | BLOCKED | Path absent from current Git tree and no path-specific commit returned; source environment/artifact unavailable |
| Binance `-2014` root cause | NOT RUN | No authenticated call or credential inspection |
| Live/Testnet order recovery against exchange | NOT RUN | No exchange order submitted; offline mocks only |
| Full current-tree + Git-history secret scan | BLOCKED | GitHub code-search connector returned no usable matches; no dedicated secret-scanning alert endpoint was available through this connector |
| Dependency vulnerability database scan | NOT RUN | No Dependabot/SCA result available through current connector |
| Immutable SHA pinning of all third-party Actions | NOT RUN | Workflows still contain version-tag references such as `actions/checkout@v4`; no mass pinning was done without verifying trusted commit SHAs |
| Android manifest/network static review | PASS (static inspection only) | No device/emulator runtime security test |
| Android APK + Campaign Android on SHA `a688654` | IN PROGRESS | Run 38201612690 and Campaign run 38201612675 had Android jobs running at last poll |

### Current conclusion

The duplicate-intent path is now protected by an atomic persistent claim and unresolved-intent inspection; the added restart/concurrency tests are included in the passing Python CI jobs on `a688654`. SQLite connection closure, hard-link path alias protection and WAL-consistent backup generation were also changed and covered by temporary-database or static regression tests.

**The full audit is not yet complete.** Android lint/unit tests/APK and Campaign Android results on the latest code SHA still need a final poll. SQLite lock-contention/corruption recovery, live-independent Binance reconciliation behavior beyond mocks, full secret-history scanning and dependency vulnerability scanning remain explicitly outstanding. No production database or unknown local artifact was modified; no real trading request was sent; PR #66 remains open and unmerged.
