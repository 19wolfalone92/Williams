# TASK 001.3 — Concurrency and persistence

**Status: PARTIAL / NOT VERIFIED on current SHA.**

Implemented: schema v1→v2 additive migration; stable attempt metadata; immutable fingerprint checks; transactional submission claims; per-symbol blocking for active `SUBMITTING`/`UNKNOWN` intents; rejection of duplicate target-state claim success; same-instance concurrent-claim and cross-instance duplicate-claim tests; and same-symbol conflict/independent-symbol tests.

The Android unit-test task completed on SHA `f51392ad42c440e52a5bfb1f92d84f0d7564ae4c`, before the final KTX transaction refactor. That Campaign CI run failed at lint after unit tests and `assembleDebug` tasks. The latest transaction implementation SHA `74f12c5` has not yet been verified by a completed CI run.

Database busy/rollback/commit ambiguity, disk failure, cancellation/fill races, protection gaps, conflicting exchange evidence and the full required persistence matrix remain **NOT RUN**.
