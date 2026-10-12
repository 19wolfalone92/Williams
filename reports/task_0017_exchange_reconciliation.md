# TASK 001.7 — Exchange Reconciliation

- Implementation SHA: `e5f82ae60b0b5c1e9512f9efe3bedc1d018ab132`.
- Code: `app/src/main/java/com/williamsbot/StandaloneRuntime.kt` (`reconcileJournaledSingleOrders`); new `BinanceTradeEvidence.kt`; new `BinanceTradeEvidenceTest.kt`; existing `DurableMutationLedger.kt`.
- Status: PARTIALLY IMPLEMENTED — P0 OPEN.

## Implemented
- Single-order lookup uses exact symbol and persisted `origClientOrderId`; missing/contradictory order responses remain unresolved.
- Trade history query is scoped to symbol/order ID and capped at 1000; a response at that limit is rejected as potentially truncated.
- New validator checks trade symbol, order ID, non-empty unique trade IDs, positive qty/price/quoteQty, nonnegative commission, commission asset when commission is nonzero, positive time, buyer/maker flags, quoteQty consistency and exact summed quantity versus order executedQty.
- NEW, PARTIALLY_FILLED and FILLED status/quantity consistency is checked. Evidence stores trade IDs/count and verified quantity; the ledger writes evidence with the state transition transaction.
- No mutation retry is performed by this reconciliation path.

## Tests added (results only count when exact-SHA CI runs)
- `BinanceTradeEvidenceTest.validTradeEvidenceMatchesExpectedExecutedQuantity`
- `zeroExecutionCanBeVerifiedOnlyAgainstEmptyTradeHistory`
- `duplicateTradeIdsAreRejected`
- `mismatchedSymbolAndOrderIdAreRejected`
- `missingTradeIdAndInvalidEconomicsAreRejected`
- `quoteQuantityConflictAndMissingRequiredFieldsAreRejected`
- `incompleteOrContradictoryExecutedQuantityIsRejected`

## Not implemented / unresolved
- Full pagination/completeness semantics beyond conservative refusal at the 1000-record limit.
- Comparison of all original request economics: the durable row has a fingerprint rather than a typed persisted order-intent schema.
- Commission totals by asset, OCO parent/child/list reconstruction, cancellation outcome, cancel-replace dual outcomes, account balances, campaign exposure, protective coverage and REST/user-stream convergence.
- A positive order and trade lookup is not campaign or protection certification. P0 remains open.
