# TASK 001.9 — SQLite Migration, Concurrency and Recovery

- Latest source/test HEAD at report preparation: `fd3276c5f041dd47f0941d5b07fff4726c8c9124`.
- Campaign Python run [38306130277](https://github.com/19wolfalone92/Williams/actions/runs/38306130277): 258 passed on prior SHA `5c6499a26a493ab4ff11ca44486850a79283d7ef`; latest OCO guard remains pending exact-SHA validation.
- Status: PARTIAL — P0 OPEN.

## Implemented / tested on prior SHA

- Python DB lifecycle tests use temporary SQLite databases and test rollback, reopen, claims, recovery leases and unresolved intent handling.
- ExecutionBarrier persists intent before submission, records ambiguity after timeout/persistence failure, and blocks new submissions when unresolved state exists.
- Recovery tests verify exchange lookup and final SQLite state, including an injected failure before reconciliation commit and a crash after commit.
- Recovery remains read-only; no blind submit retry is performed by the worker.
- Android durable mutation ledger uses transactions and persists operation identity/evidence; prior Android unit tests passed on SHA `51ca573ee2366065ce7d75af6699e25ae3ac94a3`.

## Not yet fully verified

- All supported legacy Android and Python schema versions, repeated migration from every version, and DDL/DML interruption boundaries.
- Legacy RECONCILED rows with missing/invalid evidence across every historical schema.
- Duplicate legacy client IDs during unique-index migration.
- Cross-process concurrent mutation claims and concurrent Android reconciliation under crash conditions.
- Full crash/restart matrix at every SUBMITTING, UNKNOWN, RECONCILING and transaction boundary.
- Exact final-SHA Android ledger tests after the latest Python recovery changes.
- Campaign exposure/protection reconciliation across both runtimes.

Android SQLite and Python SQLite are separate databases and cannot be treated as one atomic transaction. A successful write to one does not imply a successful write to the other; exchange reconciliation and fail-closed admission remain required.