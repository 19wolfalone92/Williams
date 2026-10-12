# TASK 001.3 — Durable submission control

**Status: PARTIAL — P0 OPEN.** Source SHA: `efe5d12272e3cdd01ef48daeac4a56af6f685040`.

The Android mutation sink persists a `PREPARED` ledger row, then uses a SQLite transaction and conditional state transition to `SUBMITTING` before network I/O. Existing states are `PREPARED`, `SUBMITTING`, `UNKNOWN`, `ACKNOWLEDGED`, `RECONCILED`, and `REJECTED`. The sink rejects non-`PREPARED` replays; exceptions after submission attempt set reconciliation-required and attempt to persist `UNKNOWN`. Execution readiness includes both the runtime reconciliation flag and unresolved ledger rows.

TASK 001.3 corrected identity derivation to canonicalize request parameters, added typed identity helpers, reordered replay rejection before the submission transition, and removed the random-UUID fallback for mutation paths that lack a stable logical/client/target key.

**Not proven:** transaction contention across independent SQLite helper instances; commit-ambiguity behavior; full persistence failure injection; caller-supplied operation identity across all entry/exit/protection paths; operation-attempt history; or Android exchange reconciliation. No exactly-once claim is made. New source tests have not yet been observed passing on this SHA.
