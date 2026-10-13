# TASK 001.9 — SQLite Migration, Concurrency and Recovery

- Code/test SHA: `fd3276c5f041dd47f0941d5b07fff4726c8c9124`.
- Campaign Python test step [38306238359](https://github.com/19wolfalone92/Williams/actions/runs/38306238359): 259 passed, 4 warnings; overall run was later cancelled during cleanup/concurrency.
- Branch HEAD before this report refresh: `1b051659dba73913ad3c2e0a39f8278042cf4729`.

## Implemented / tested

- Python SQLite lifecycle tests cover reopen, rollback, claims, recovery leases and unresolved intent handling.
- ExecutionBarrier persists before submit and marks ambiguity on timeout/persistence failure; unresolved state blocks a second submission.
- Recovery tests check final SQLite state and mutation count, including failure before reconciliation commit and crash after commit.
- Android durable mutation ledger uses transactions and persists operation identity/evidence; Android unit tests last completed successfully on SHA `51ca573ee2366065ce7d75af6699e25ae3ac94a3`.

## Not yet verified

- All supported old Android/Python schema versions, repeat migrations from each version, DDL/DML interruption boundaries.
- Legacy RECONCILED rows with missing/invalid evidence for all historical schemas.
- Duplicate legacy client IDs during unique-index migration.
- Cross-process claims and concurrent reconciliation under crash/restart.
- Crash injection at every SUBMITTING/UNKNOWN/RECONCILING and transaction boundary.
- Final-SHA Android ledger tests after latest recovery changes.
- Campaign exposure/protection consistency across runtimes.

Android SQLite and Python SQLite are distinct stores without a common atomic transaction. A successful write to one does not guarantee the other was written. P0 remains open.

## TASK 001.12 exact-HEAD addendum

- At SHA `4e7ad93990b4b9d3b5291bed9d83909fbfac3bcf`, production `db.py` uses `BEGIN IMMEDIATE` for the migration transaction, serializing schema inspection/ALTER operations before applying column migrations and schema-version update. Migration failure rolls back that transaction; constructor closes the connection and re-raises.
- FI-13B repeats 12 independent temporary-database initialization races and checks worker termination, zero recorded exceptions, both successes, `PRAGMA integrity_check='ok'`, and integrity after reopen. Campaign Python tests passed 262/262 on run [38341931762](https://github.com/19wolfalone92/Williams/actions/runs/38341931762).
- FI-13 repeats 12 same-process/two-thread claims using independent connections; exactly one claim and one exchange observation are asserted, with zero fake mutations and persisted order identity.
- Remaining gaps: true multi-process races, process termination during each migration DDL/DML boundary, explicit preservation assertions for populated legacy tables under interrupted migration, lock-timeout/non-lock SQLite error injection, and lease expiry/loss while reconciliation is in flight. P0 remains open.


## TASK 001.12 Android evidence correction

At code SHA `b6d43d439a1c49c577570d36332bbe1c60ff7477`, Android job 115116130632 in [run 38342390656](https://github.com/19wolfalone92/Williams/actions/runs/38342390656) passed `:app:testDebugUnitTest`, `:app:lintDebug` and `:app:assembleDebug`. APK SHA-256 `e9ce68cfeac340c09979463aa5a2e0b1a346d08d19403e163a2d99589f254ac6`; provenance matches `b6d43d439a1c49c577570d36332bbe1c60ff7477`; artifact ID 11787686242. This does not close the untested multi-process lease race, interrupted migration matrix, lease expiry during in-flight reconciliation, or lock/non-lock error injection. Report-only commits after this SHA require another exact-HEAD check.
