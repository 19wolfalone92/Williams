# TASK 001.8 — Exchange Reconciliation

- Audited code/test SHA: `31ba73ff81c43edb5be4606b200dce752148967a`.
- Status: PARTIALLY IMPLEMENTED — P0 OPEN.
- Relevant code: `StandaloneRuntime.kt`, `BinanceTradeEvidence.kt`, `DurableMutationLedger.kt`; tests: `BinanceTradeEvidenceTest.kt`, `DurableMutationLedgerTest.kt`.

## IMPLEMENTED (source inspection; not a blanket runtime verification)

- Single POST order recovery queries by symbol and original client order ID.
- Identity validator requires matching symbol and exact clientOrderId, nonempty exchange order ID, and rejects conflict with a previously persisted exchange order ID.
- Fill evidence validates trade identity, uniqueness, required fields, quantity/price/quote quantity, commission fields, trade time/side flags, quote consistency, and summed quantity against exchange executedQty.
- Evidence is bound to the durable intent; transition to RECONCILED validates evidence and persists it with the state transition inside the ledger transaction.
- Trade history response capped at 1000 rows is treated as potentially truncated and therefore not complete.
- Legacy RECONCILED records without evidence are reopened as unresolved during migration.
- Failure/contradiction should leave the operation unresolved; the newly added tests cover selected identity conflicts and durable ID immutability.

## TESTED / VERIFIED

- Campaign Python job in run 38301629067: SUCCESS; unrelated to Android reconciliation.
- Android unit tests for the current audited SHA: NOT YET VERIFIED; Campaign Android job was still running at inspection.
- The single-order path is not claimed verified until exact-SHA Android unit tests pass and their report/artifact is inspected.

## Unsupported / BLOCKED

- OCO parent/list and child-order recovery.
- Cancel single order, cancel order list, and cancelReplace including partial success.
- End-to-end test for crash after exchange acceptance/response but before SQLite persistence and mutation-count proof.
- Full pagination contract beyond conservative refusal at the 1000-record boundary.
- Account balance, campaign exposure, protection coverage, and REST/user-stream convergence.
- A response timeout or unavailable history is not proof that an order does not exist; unknown remains unresolved and must block risky mutation.

## Required next tests

- Deterministic fake-exchange tests asserting both SQLite final state and exact mutation count after identity/evidence failure.
- Fault injection between response receipt and transaction commit; restart must reconcile, never blindly resubmit.
- Duplicate, missing, conflicting and truncated trade evidence must not transition to RECONCILED.

No live Binance credentials or orders used.