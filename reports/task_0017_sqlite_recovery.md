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