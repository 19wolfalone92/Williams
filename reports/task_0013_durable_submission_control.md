# TASK 001.3 — Durable submission control

**Status: PARTIAL — P0 OPEN.** Current implementation SHA: `74f12c595181d0b4362fa02f2b2de436db9a751c`.

- Ledger schema v2 persists exchange, optional campaign/signal metadata, stable first-attempt ID and timestamps. Migration v1→v2 is additive and preserves unresolved rows.
- `claimForSubmission()` uses an SQLite transaction and conditional state update; it rejects a second claim after the first has moved the row to `SUBMITTING`, and blocks a second active `SUBMITTING`/`UNKNOWN` mutation for the same symbol.
- Generic `transition()` cannot be used for `PREPARED → SUBMITTING`; the submission claim has its own entry point.
- BUY entry submission is blocked when another unresolved ledger intent exists. Database preparation/claim failures set reconciliation-required and fail before network submission.
- Recovery sets a durable `recovery_in_progress` marker with synchronous preference commit, loads that marker as a reconciliation barrier after restart, blocks execution readiness during recovery and rejects mutations from other threads while recovery runs. The ledger now uses the AndroidX SQLite transaction KTX wrapper to satisfy lint without weakening transaction semantics.

**Verification:** Android tests completed on parent source SHA `f51392ad42c440e52a5bfb1f92d84f0d7564ae4c`; its build failed at lint. The KTX transaction refactor at current SHA `74f12c5` has not yet been CI-verified. Database commit ambiguity and injected disk/write failures remain untested. No exactly-once claim is made.
