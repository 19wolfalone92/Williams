# TASK 001.3 — Durable submission control

**Status: PARTIAL — P0 OPEN.** Implementation/test source SHA: `8415ab5e6b74e082b865e2cf2ddbcbde29fdb366`.

The Android sink prepares a durable ledger intent and transitions `PREPARED` to `SUBMITTING` in a SQLite transaction before network I/O. The conditional state update is the submission claim. The transition helper no longer returns success when another worker has already moved the row to the requested target state; a second `PREPARED → SUBMITTING` claimant now fails instead of receiving a false claim success.

The ledger schema is version 2 and adds exchange, campaign ID, signal ID and stable attempt ID fields. The v1→v2 migration is additive and preserves existing rows, including unresolved `SUBMITTING`/`UNKNOWN` intents. The runtime rejects mutation calls without a stable client/target key. Exceptions after submission attempt set reconciliation-required and attempt to persist `UNKNOWN`.

**Tests added but not yet executed:** two-ledger-instance concurrent claim test, schema-v1 migration preserving an unresolved intent, typed metadata persistence, plus canonical identity cases. A successful SQLite transaction is not atomic with Binance. Commit ambiguity, database busy/write failure, actual response-persistence failure, and exchange reconciliation remain unproven. No exactly-once claim is made.
