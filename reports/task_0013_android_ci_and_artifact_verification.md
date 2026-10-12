# TASK 001.3 — Android CI and artifact verification

**Status: BLOCKED / NOT VERIFIED on current implementation SHA `74f12c595181d0b4362fa02f2b2de436db9a751c`.**

- Campaign CI `38247803390`, source SHA `f51392ad42c440e52a5bfb1f92d84f0d7564ae4c`: unit-test task and `assembleDebug` completed; lint failed with 2 errors. First reported error: `DurableMutationLedger.kt:170 [UseKtx]` (“Use the KTX extension function SQLiteDatabase.transaction instead?”). The current SHA refactors the ledger to `androidx.core.database.transaction`, but that correction is **NOT YET VERIFIED** by CI.
- The prior Campaign CI `38247007163`, SHA `eaa8f7c5a2446dd7678ae13fc82010e84684b51b`, also failed lint with 3 errors; the recovery function's synchronous persistence was annotated narrowly with `@SuppressLint("ApplySharedPref")` in later code.
- Standalone Android APK run `38247804640`, SHA `f51392ad42c440e52a5bfb1f92d84f0d7564ae4c`: `startup_failure`; zero jobs and no artifacts. The rerun endpoint returned HTTP 403 for a prior startup-failure run; root cause is **NOT ESTABLISHED**.
- Python backend run `38247803258`, SHA `f51392ad42c440e52a5bfb1f92d84f0d7564ae4c`: SUCCESS; 219 passed, 4 warnings. Artifact `11745814344`, digest `sha256:af526e724bd85c8cc34efb9e856c4b58e1e16d37be4204f04a92001c5e47ecfa`.

No current-SHA APK artifact, checksum or signing certificate is available. The Campaign workflow did assemble a debug APK on SHA f513, but the overall step failed at lint and did not upload it. Debug output is not a trusted release. The current SHA has no completed CI result yet.
