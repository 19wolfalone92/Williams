# TASK 001.9 — Exact-HEAD CI and APK Verification

## Identity and status

- Latest source/test HEAD at report preparation: `fd3276c5f041dd47f0941d5b07fff4726c8c9124`.
- PR #66: OPEN, Draft, unmerged; base remains `fix/pytest-safe-collection`.
- Report updates are documentation-only descendants; their final SHA must be checked separately.
- No real Binance credentials or live orders were used.

## Latest relevant runs

- Latest Python backend run: [38306238305](https://github.com/19wolfalone92/Williams/actions/runs/38306238305), IN_PROGRESS at last inspection.
- Latest Williams Campaign CI: [38306238359](https://github.com/19wolfalone92/Williams/actions/runs/38306238359), PENDING at last inspection.
- Separate Build Android APK: [38306239714](https://github.com/19wolfalone92/Williams/actions/runs/38306239714), completed `startup_failure`; no jobs or artifacts returned. Root cause is not exposed by the available GitHub Actions data. This is an external workflow-startup blocker, not evidence of a Gradle failure.

## Completed evidence on prior source SHAs

- Campaign Python job [38306130277](https://github.com/19wolfalone92/Williams/actions/runs/38306130277): **SUCCESS**, 258 passed, 4 warnings, on source/test SHA `5c6499a26a493ab4ff11ca44486850a79283d7ef`. This included FI fake-exchange tests, stricter Python trade evidence checks, and the BL-004 SQLite sentinel test. It predates the final OCO persisted-list-ID guard and its regression test.
- Python backend job [38306130365](https://github.com/19wolfalone92/Williams/actions/runs/38306130365): **SUCCESS** on the prior tested tree.
- Campaign Android build on [38302246805](https://github.com/19wolfalone92/Williams/actions/runs/38302246805): completed SUCCESS on SHA `51ca573ee2366065ce7d75af6699e25ae3ac94a3`. The job executed `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; the Gradle output ended with `BUILD SUCCESSFUL`. This run predates later Python recovery changes and the package-identity workflow step.
- A later Android job on [38306130277](https://github.com/19wolfalone92/Williams/actions/runs/38306130277) had not completed at last inspection; do not infer APK success from its Python job.

## Downloaded APK evidence (SHA 51ca573)

Artifact [williams-debug-apk, ID 11770927454](https://github.com/19wolfalone92/Williams/actions/runs/38302246805/artifacts/11770927454):
- Artifact ZIP SHA-256: `85c8c03d1e97f0f1cf03dfbaf2369129e8a3fd97b7d337dd13a071023f3209c8`; independently matched downloaded ZIP bytes.
- Embedded APK SHA-256: `870f26d86c1ef2118806203f3cae13dedf38257dafca1585f40b6682f0256898`; matched the artifact's `.sha256` file.
- APK archive integrity: ZIP validated; `AndroidManifest.xml` and DEX files present.
- Package identity parsed from binary manifest: `com.williamsbot.debug`; min SDK 26, target SDK 36.
- Embedded signature report: apksigner reported valid V2 signature, one signer, Android Debug certificate SHA-256 `50d5a6132fa16658970ffaa4e7c45f4f4ea20e3d1d051085a490beeb5f76ae10`.
- Provenance file: `source_sha=51ca573ee2366065ce7d75af6699e25ae3ac94a3`.
- These checks prove the downloaded artifact corresponds to SHA 51ca573, **not** the latest source/test SHA.

## Workflow configuration

- Campaign CI now runs `tests/test_fi_01_fi_12_fake_exchange.py`.
- Campaign CI includes a package-identity step requiring `com.williamsbot.debug` and target SDK 36 before artifact upload.
- Separate APK workflow still repeatedly fails at startup with zero jobs/artifacts. The available API exposes no step logs or scheduler diagnostics for those runs.
- The `verified-gradle-wrapper-jar` artifact is not an APK.

## Remaining P0 blockers

- Exact latest SHA `fd3276c5f041dd47f0941d5b07fff4726c8c9124` needs final Python/Campaign CI and Android package build results.
- APK checksum, signature, provenance and artifact must be verified again on the final tested SHA.
- Separate APK workflow startup failure remains unresolved.
- Full Android fake-exchange/fault-injection coverage, every-transition crash matrix, complete mutation-path proof, all migration/concurrency cases, and artifact-wide BL-004 scan remain open.

Status: `PARTIALLY IMPLEMENTED — P0 OPEN`.