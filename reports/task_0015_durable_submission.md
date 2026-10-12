# TASK 001.5 — Durable Submission and Crash Safety

- Audited executable-source SHA: `b2b1a78c9d43a30e077d936e65a9314ddddcfcdf`
- Status: PARTIALLY IMPLEMENTED.

## Changes

1. Signed mutation transport no longer retries POST/DELETE inside its timestamp-retry loop. The retry predicate is isolated in `MutationTransportPolicy` and permits only the first read-only GET retry for a timestamp rejection.
2. SQLite schema version 3 stores reconciliation evidence with the mutation row.
3. A transition to `RECONCILED` is rejected without structured complete order/fill evidence.
4. Existing terminal labels without such evidence are reopened as `RECONCILING` by migration.
5. Tests were added for missing/incomplete evidence and legacy migration.

## Safety properties retained

- A durable claim is written before the signed request.
- An ambiguous result is not blindly retransmitted.
- An unresolved ledger row continues to block execution.
- Local SQLite is not treated as atomic with Binance.

## Remaining risks

- The transport still does not persist an append-only record for every wire attempt.
- Full fault injection at each boundary has not been executed through the actual Android transport and recovery worker.
- If a state transition fails after exchange acceptance, correctness depends on the unresolved row/barrier surviving the persistence failure; this requires the FI suite.
- REJECTED authority and safe pre-submission rejection evidence need a typed, persisted contract.
- No claim of exactly-once execution is made.
