# TASK 001.1 — Bypass, persistence and concurrency tests
**Status: NOT EXECUTED — P0 OPEN.**

## Implementation references
- `DurableMutationLedger.prepare`: transactional insert, identity-conflict rejection.
- `DurableMutationLedger.transition`: transactional compare-and-transition.
- `DurableMutationLedger.unresolved`: PREPARED/SUBMITTING/UNKNOWN enumeration.
- `StandaloneRuntime.signedRequest`: common signed POST/DELETE sink; unresolved mutations block entry readiness.

## Required tests still missing
1. Two concurrent requests using one operation ID: exactly one authorization/network attempt.
2. Duplicate intent with same identity: idempotent; conflicting identity: rejected.
3. Direct mutation bypass: impossible to reach authenticated mutation without a valid boundary context.
4. Process restart: SQLite state persists and unresolved intent blocks entry.
5. Simulated storage failure before send: no exchange call.
6. Simulated storage failure after exchange acceptance: no blind retry.
7. Cancel-replace partial result: UNKNOWN until both old and replacement order states are reconciled.

No concurrency or bypass test is marked PASS. `@Synchronized` on ledger methods alone is not evidence that the whole prepare → authorize → network → acknowledge sequence is globally serialized across all runtime paths.
