# TASK 000.5.4 — Fault injection and recovery

## Implemented/available test evidence
The new mock-only regression tests exercise a transport exception containing synthetic secrets, ambiguous execution persistence, the durable RECONCILE_REQUIRED state, and prevention of a subsequent blind submit. Existing `tests/test_execution_barrier_persistence_fail_closed.py` covers several persistence failures and unresolved-intent blocking.

## Scenario matrix
| Scenario | Evidence status |
|---|---|
| Timeout after mutation submit | Test committed; not yet executed on latest HEAD |
| Response lost after exchange accepted order | Not fully simulated end-to-end |
| Process restart after PENDING/AMBIGUOUS | Existing persistence tests cover duplicate claims partially; full restart integration test missing |
| Duplicate execution event | Not verified |
| Partial fill | Not verified end-to-end |
| Unknown exchange order status | Existing tests cover non-authoritative response/reconciliation; full exchange reconciliation matrix incomplete |
| SQLite transaction failure | Partial persistence-failure mocks; actual crash/rollback drill not run |
| Corrupt/incomplete local state | Not verified |
| Exchange order without local row | Not verified end-to-end |
| Local row without exchange confirmation | Partial ambiguity behavior only; complete reconciliation matrix not run |

No Binance Testnet calls or real orders were used. The connected API cannot run the test suite directly, and no fresh workflow run is currently available for the latest source commit. No scenario is marked PASS without execution evidence.

## Required recovery invariants
Unknown is not rejection; Binance remains authority for actual orders/fills; unresolved states block new entries; recovery/reconciliation precedes retry; no blind retry; diagnostics contain no raw secrets or transport bodies.

## Status
**OPEN — FIX NOT VERIFIED** for the tested ambiguous-submit path; **INCOMPLETE — EVIDENCE MISSING** for the full fault-injection matrix.