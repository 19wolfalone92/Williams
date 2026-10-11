# WILLIAMS BOT 2.1 — TASK 007.14
## Remote Execution Baseline, Trusted Source Snapshot Recovery & Reproducible Test Evidence

**Result: PASS — REMOTE BASELINE VERIFIED**  
**Scope:** baseline verification only. This is not financial-production approval and does not authorize connecting a new financial writer.

### 1. Control points and repository identity

| Item | Verified value |
|---|---|
| Repository | `19wolfalone92/Williams` |
| Working branch | `audit/williams-2.1-task-007-recovery` |
| Baseline commit under test | `a9b9555069a7833bf8d1a86bdc9d8f344725aa47` |
| Baseline commit parent | `6948a8839251a6e99dcb8d1c46c50a86bf5698b1` |
| Previous known `main` checkpoint | `04da5f9c09572e99ff310be3a1908179165e528c` |
| `main` observed during this task | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Workflow | `.github/workflows/campaign-ci.yml` |
| Workflow blob SHA at baseline | `842b1b7b9a21a90ebccc1ecd1c95e102db8cd9c8` |

GitHub API confirmed that the branch head at inspection was `a9b9555069a7833bf8d1a86bdc9d8f344725aa47`; the parent of that commit is `6948a8839251a6e99dcb8d1c46c50a86bf5698b1`. The only file changed in that commit is this prior-task report: `docs/WILLIAMS_BOT_2_1_TASK_007_13_EXECUTABLE_WORKSPACE_BOOTSTRAP.md` (161 additions, 0 deletions). No production source, test, dependency, workflow, or database-schema file changed in that commit.

GitHub compare reports `main` as unchanged at `04da5f9c09572e99ff310be3a1908179165e528c` and the task branch 135 commits ahead of that base. This is remote GitHub identity evidence; the local task container still does not have an executable checkout.

### 2. TASK 007.13 CI run — final result

Run: [GitHub Actions run 38131533371](https://github.com/19wolfalone92/Williams/actions/runs/38131533371)

- Event: `push`
- Workflow: `Williams Campaign CI`
- Run attempt: 1
- Final run status/conclusion: `completed / success`
- Exact `head_sha`: `a9b9555069a7833bf8d1a86bdc9d8f344725aa47`
- Run started: 2026-10-11 10:19:23 UTC
- Run completed/updated: 2026-10-11 10:21:56 UTC
- Jobs: 2; both completed successfully; no job was skipped or cancelled.

The full job logs were retrieved through the GitHub Actions job-log endpoint. Both logs record fetching the exact SHA, checking out `audit/williams-2.1-task-007-recovery`, and printing `git log -1 --format=%H` as `a9b9555069a7833bf8d1a86bdc9d8f344725aa47`. The workflow therefore tested the exact baseline commit rather than an unrelated branch tip.

### 3. Required checks and evidence

| Check | Actual workflow command/step | Result | Evidence |
|---|---|---|---|
| Dependency installation | `python -m pip install -r requirements.txt` | PASS; step completed | [Python job log](https://github.com/19wolfalone92/Williams/actions/runs/38131533371/job/114446693519) |
| Python compile | `python -m compileall -q .` | PASS; step completed | [Python job log](https://github.com/19wolfalone92/Williams/actions/runs/38131533371/job/114446693519) |
| Required Python suite | `python -m pytest -q` over the explicit test list in `campaign-ci.yml`, including `test_task007_recovery_contract.py` | PASS; **201 passed, 0 failed**, 4 warnings, 5.27 s | [Python job log](https://github.com/19wolfalone92/Williams/actions/runs/38131533371/job/114446693519) |
| Android unit tests | `gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace` | PASS as part of successful Gradle invocation; step completed | [Android job log](https://github.com/19wolfalone92/Williams/actions/runs/38131533371/job/114446693646) |
| Android lint | ` :app:lintDebug` in the same Gradle invocation | PASS; task executed, lint report uploaded | [Android job log](https://github.com/19wolfalone92/Williams/actions/runs/38131533371/job/114446693646) |
| Debug APK assembly | `:app:assembleDebug` in the same Gradle invocation | PASS; `BUILD SUCCESSFUL`, 55 actionable tasks executed | [Android job log](https://github.com/19wolfalone92/Williams/actions/runs/38131533371/job/114446693646) |
| APK artifact upload | `actions/upload-artifact@v4`, `williams-debug-apk`, `if-no-files-found: error` | PASS; artifact exists | [Run artifacts](https://github.com/19wolfalone92/Williams/actions/runs/38131533371) |
| Lint report upload | `actions/upload-artifact@v4`, `williams-lint-report` | PASS; artifact exists | [Run artifacts](https://github.com/19wolfalone92/Williams/actions/runs/38131533371) |

The workflow uses `set -o pipefail` for the Python pytest pipeline and `tee campaign-pytest-output.txt`; the pytest output includes the final count. GitHub reports each run step as completed successfully. The UI/API does not expose a separate numeric shell exit-code field for every step, so this report records the observed step/job conclusion rather than inventing numeric codes.

### 4. Runner and toolchain actually observed

- Runner: GitHub-hosted `ubuntu-latest`; job log paths identify Ubuntu 24.04.
- Python: `3.12.15` (from hosted tool cache).
- Java: Temurin JDK 17; log environment `JAVA_HOME=.../17.0.20-1/x64`.
- Gradle: `8.14.5`, provisioned by `gradle/actions/setup-gradle@v5`.
- Gradle command: `gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace`.
- Dependencies: installed from the exact commit's `requirements.txt`; Gradle/Android dependencies were resolved/restored by the hosted runner.
- Android SDK: available sufficiently for the configured Android build/lint/unit-test tasks to complete; the workflow does not print a complete SDK component inventory.

### 5. Artifact availability and reproducibility limits

Run artifacts listed by GitHub:
- `williams-debug-apk`, artifact ID `11698034712`, reported size 20,645,908 bytes.
- `williams-lint-report`, artifact ID `11698709158`, reported size 24,966 bytes; log reports ZIP SHA-256 `8cb77656bbc2bbe5f98943093ccd542ca5b4b86d5d98620bc1ec513355578641`.

The artifacts are listed as not expired at inspection. The artifact list confirms their existence, not bit-for-bit reproducibility of the APK. No independent second build of the same source SHA was compared; therefore **repeatability of this one CI result is verified, but independent rerun equivalence and bitwise APK reproducibility are not claimed**.

### 6. Source identity versus local executable identity

**Remote source identity: VERIFIED.** GitHub branch/commit metadata, parent linkage, workflow blob SHA, both job logs, and checkout `git log -1` output agree on the tested baseline SHA.

**Executable source identity in the local task container: NOT VERIFIED / not present.** The prior diagnostics remain: DNS resolution for `github.com` fails, no usable proxy was configured, and the local container had no Gradle or Android SDK and no project checkout. This does not invalidate the exact-SHA remote run: the remote GitHub Actions runner performed a real checkout and executed the workflow against that SHA.

The baseline commit itself changes only the prior audit report. The task branch does contain earlier financial/runtime source changes relative to `main`; those historical changes are part of the tested baseline and are not represented as changes made by TASK 007.14.

### 7. PR and main integrity

- `main` remained `04da5f9c09572e99ff310be3a1908179165e528c` at inspection.
- No merge or mutation of `main` was performed.
- No PR was associated with run 38131533371; a GitHub query for a PR whose head is `audit/williams-2.1-task-007-recovery` returned none.
- PR #43 and PR #50 are separate draft/open task PRs on different branches; neither is this recovery branch. No PR was created or merged by TASK 007.14.

### 8. Financial safety gate

The baseline is now adequate to proceed to the **next isolated financial-writer audit/implementation-planning task**, because a trusted remote workflow has checked out the exact commit and completed the repository's declared Python and Android checks with logs and artifacts available.

This is not evidence that the financial system is production-safe. In particular, this result does **not** approve:
- connecting `FinancialEffectApplier` to a production execution path;
- a CanonicalFillV1 contract whose unresolved rules are still undecided;
- single-writer ownership or exactly-once financial effect application;
- atomic coupling of financial ledger effects and projections;
- recovery after process death or uncertain commit;
- any SQLite REAL-to-text migration or recovery of precision already lost to floating-point storage;
- Binance Testnet or Mainnet trading mutations.

The next financial task must establish and test the actual contract, single-writer boundary, durable fill identity, atomicity, replay behavior, and crash/commit-uncertainty recovery. Do not change financial semantics merely to satisfy tests.

### 9. Files not changed by TASK 007.14

No production file, test, workflow, dependency manifest, Gradle configuration, or database schema was changed in this task's baseline-verification work. The report is the only intended file addition:
- `docs/WILLIAMS_BOT_2_1_TASK_007_14_REMOTE_EXECUTION_BASELINE.md`

### 10. Final decision

**PASS — REMOTE BASELINE VERIFIED**

This status means only that the declared remote CI baseline is verified for source commit `a9b9555069a7833bf8d1a86bdc9d8f344725aa47` under the observed GitHub-hosted runner/toolchain. It does not certify financial correctness, Testnet behavior, production readiness, local checkout restoration, or bit-for-bit reproducibility of the APK.

The report commit SHA is recorded by the GitHub Contents API response and must be cross-checked against the recovery branch head after this file is created. No production behavior or database schema change is included in this report commit.
