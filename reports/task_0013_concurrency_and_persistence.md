# TASK 001.3 — Concurrency and persistence

**Status: PARTIAL / NOT VERIFIED.** Implementation/test source SHA: `8415ab5e6b74e082b865e2cf2ddbcbde29fdb366`.

Implemented source changes: SQLite schema v1→v2 additive migration; typed operation metadata persistence; a stable attempt identifier; and a transition correction so a second worker cannot treat an already-claimed `SUBMITTING` row as its own successful claim. Tests were added for a concurrent claim through two ledger instances and migration of an unresolved v1 row.

**Execution status:** these new tests have not yet been observed running on the implementation SHA. Required database busy/rollback/commit ambiguity, disk/write failure, stale in-memory state, cancellation/fill race, repeated recovery, missing protection, incomplete/conflicting evidence and remaining concurrency cases are **NOT RUN**. SQLite transactions are real in the test harness, but passing concurrency evidence is not yet available.
