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

The environment limitation is lack of a local checkout/runtime in this session. GitHub Actions runs provide CI evidence for the exact recorded commit only; they do not establish the outcome of the new audit branch until its workflows complete.

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
- The CI/configuration fixes in this branch are verified by successful runs 38177124730, 38177124662 and 38177124654 on commit `ff7842d93c8d8c8a8053a4192d802d387120051a`. The broader audit remains incomplete as described above.
