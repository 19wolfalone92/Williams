# TASK 001.9 — Exact-HEAD CI and APK Verification

## HEAD and PR

- Code/test SHA whose targeted Campaign Python test step completed: `fd3276c5f041dd47f0941d5b07fff4726c8c9124`.
- Branch HEAD before this documentation refresh: `1b051659dba73913ad3c2e0a39f8278042cf4729`; this and subsequent report commits are documentation-only descendants. Recheck CI on the resulting final report HEAD.
- PR #66 remains OPEN, Draft, unmerged; base `fix/pytest-safe-collection`.

## Latest CI evidence

- Campaign run [38306238359](https://github.com/19wolfalone92/Williams/actions/runs/38306238359), associated with code/test SHA fd3276:
  - Python test step: **SUCCESS — 259 passed, 4 warnings in 7.50s**.
  - Python job was later marked cancelled during post-checkout/cleanup as a newer run entered the concurrency queue. Therefore do not label the overall workflow SUCCESS.
  - Android job remained in `Regenerate and verify Gradle wrapper from trusted Gradle` / did not reach the build, unit-test, lint, package identity or APK upload steps before it was superseded. No APK artifact was produced by this run.
- Python backend run [38306362626](https://github.com/19wolfalone92/Williams/actions/runs/38306362626), on report HEAD 1b0516 at trigger time: IN_PROGRESS at last inspection.
- Campaign run [38306362790](https://github.com/19wolfalone92/Williams/actions/runs/38306362790), on report HEAD 1b0516 at trigger time: PENDING at last inspection.
- Separate Build Android APK run [38306365073](https://github.com/19wolfalone92/Williams/actions/runs/38306365073): `startup_failure`, zero jobs and zero artifacts. The available GitHub Actions API exposes no scheduler/runner diagnostics for this failure. Do not attribute it to Gradle.

## Previously downloaded APK evidence — SHA 51ca573 only

Artifact [williams-debug-apk, ID 11770927454](https://github.com/19wolfalone92/Williams/actions/runs/38302246805/artifacts/11770927454):
- Artifact ZIP SHA-256: `85c8c03d1e97f0f1cf03dfbaf2369129e8a3fd97b7d337dd13a071023f3209c8`; independently matched downloaded ZIP bytes.
- Embedded APK SHA-256: `870f26d86c1ef2118806203f3cae13dedf38257dafca1585f40b6682f0256898`; matched embedded checksum file.
- APK ZIP integrity valid; binary manifest package `com.williamsbot.debug`, min SDK 26, target SDK 36.
- Embedded apksigner report verifies V2 signature, one Android Debug signer, certificate SHA-256 `50d5a6132fa16658970ffaa4e7c45f4f4ea20e3d1d051085a490beeb5f76ae10`.
- Provenance file records `source_sha=51ca573ee2366065ce7d75af6699e25ae3ac94a3`.
- These artifacts are **not** proof for fd3276 or the latest report HEAD.

## Remaining P0 blockers

- Final report HEAD needs exact-SHA Python/Campaign completion.
- Android build, unit tests, lint, package identity, signature, checksum, provenance and artifact upload have not completed on fd3276 or the report HEAD.
- Standalone APK workflow continues to fail at startup without jobs/logs/artifacts.
- Full Android fake-exchange fault injection, every-transition FI-07, complete mutation-boundary proof, full migration/concurrency matrix and comprehensive BL-004 artifact scan remain open.

Status: `PARTIALLY IMPLEMENTED — P0 OPEN`. No live credentials or live orders used.

## TASK 001.12 exact-HEAD addendum (checked 2026-10-13 UTC)

- Required starting SHA and actual PR head at baseline: `4e7ad93990b4b9d3b5291bed9d83909fbfac3bcf`; PR #66 is OPEN, Draft, unmerged; base is `fix/pytest-safe-collection` at `0007e79b1afde86b8c736128e6d09bfdd370106f`.
- Commit diff from previous HEAD `2bbf6bea41f575a74ebdda67aa4b588d0d733d12`: one file, `tests/test_fi_01_fi_12_fake_exchange.py`, 88 additions / 75 deletions. This commit changes repeated FI-13 race coverage, not production code.
- Python backend run [38341931627](https://github.com/19wolfalone92/Williams/actions/runs/38341931627), job 115114647440: SUCCESS, `223 passed, 4 warnings in 4.73s`; pytest artifact [11787072662](https://github.com/19wolfalone92/Williams/actions/runs/38341931627/artifacts/11787072662).
- Campaign run [38341931762](https://github.com/19wolfalone92/Williams/actions/runs/38341931762), Python job 115114648003: SUCCESS, `262 passed, 4 warnings in 6.10s`. Android job 115114647875 was still running at report time; build/unit tests and APK proof were not yet complete.
- Separate APK workflow [38341932569](https://github.com/19wolfalone92/Williams/actions/runs/38341932569): `startup_failure`, zero jobs/artifacts. Available Actions data exposes no cause. This is not evidence of a Gradle or application failure.
- Do not infer current-SHA APK evidence from the older artifact built from SHA `51ca573ee2366065ce7d75af6699e25ae3ac94a3`. Exact-HEAD Android evidence remains pending.


## TASK 001.12 Android result correction — build source SHA `b6d43d439a1c49c577570d36332bbe1c60ff7477`

Campaign run [38342390656](https://github.com/19wolfalone92/Williams/actions/runs/38342390656), Android job **115116130632**: SUCCESS. Gradle wrapper verification, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, APK checksum/provenance verification, package identity gate, and artifact uploads all completed successfully. Job log: https://github.com/19wolfalone92/Williams/actions/runs/38342390656/job/115116130632.

- APK: `app-debug.apk`; SHA-256 `e9ce68cfeac340c09979463aa5a2e0b1a346d08d19403e163a2d99589f254ac6`.
- Package `com.williamsbot.debug`, version `4.25.0-debug`, target SDK 36.
- apksigner: V2 signature verified; one Android Debug signer; certificate SHA-256 `1b9ce8b9b27681575d86a20890590b4ad79550316f099f8873fff4e5760e58d5`.
- Build-generated provenance source SHA matches `b6d43d439a1c49c577570d36332bbe1c60ff7477`.
- APK artifact [williams-debug-apk, ID 11787686242](https://github.com/19wolfalone92/Williams/actions/runs/38342390656/artifacts/11787686242); uploaded artifact ZIP SHA-256 `a34c5122e1501fc2c5565f315b0cf4bd947afe02dc5fbb412626ac43290e292e`. Lint artifact [ID 11787626329](https://github.com/19wolfalone92/Williams/actions/runs/38342390656/artifacts/11787626329).
- Separate Build Android APK [38342391326](https://github.com/19wolfalone92/Williams/actions/runs/38342391326) still ended `startup_failure` with zero jobs/artifacts and no exposed cause; Campaign CI independently completed Android verification successfully.
- Subsequent commits for this addendum modify reports only. Re-run exact-HEAD CI after the final report commit; the APK above is proven for `b6d43d439a1c49c577570d36332bbe1c60ff7477`, not automatically for a later report-only HEAD.
