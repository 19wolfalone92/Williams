# TASK 001.7 — Exchange Reconciliation

- Current source SHA: `7e7b240f07d11e0077d1b1d741a8087f4846a750`.
- Core code: `StandaloneRuntime.kt:reconcileJournaledSingleOrders`, `BinanceTradeEvidence.kt`, `DurableMutationLedger.kt`; tests in `BinanceTradeEvidenceTest.kt` and `DurableMutationLedgerTest.kt`.
- Status: PARTIALLY IMPLEMENTED — P0 OPEN.

## Implemented
- Exact symbol + persisted `origClientOrderId` order lookup; missing/contradictory response stays unresolved.
- Trade query scoped to symbol/order ID, with conservative refusal at 1000 records because completeness cannot be assumed.
- Per-trade checks: symbol/order ID, non-empty unique trade IDs, positive quantity/price/quote quantity, nonnegative commission, commission asset for nonzero commission, positive time, boolean buyer/maker flags, quote quantity consistency, and exact summed quantity versus order executedQty.
- NEW/PARTIALLY_FILLED/FILLED status-to-quantity consistency checks.
- Evidence now carries symbol, client order ID, exchange order ID, status, original/executed quantity, trade IDs/count/sum, and verification flags.
- Durable ledger requires structured evidence, validates status/quantity/trade-ID consistency, binds evidence symbol/client ID to the persisted intent, checks exchange order ID equality, and persists evidence with the state transition transaction. Legacy RECONCILED rows lacking evidence are reopened as unresolved during migration.
- No blind mutation retry occurs in this reconciler.

## Tests
- New `BinanceTradeEvidenceTest` suite covers valid evidence, zero-execution empty history, duplicate IDs, symbol/order mismatches, invalid economics, commission fields, quote mismatch, missing fields and aggregate quantity mismatch.
- `DurableMutationLedgerTest` valid reconciliation fixtures now include the required structured and intent-bound evidence.
- Exact-SHA Android test outcome is pending the current Campaign Android job; a previous run failed to compile a fixture at line 79, fixed in `7e7b240...`.

## Unresolved
- Full pagination contract; order-intent economics compared against a typed persisted request (only fingerprint currently persists); commission aggregation by asset; OCO/list children; cancellation/cancel-replace; account balances, campaign exposure, protective coverage and REST/user-stream convergence.
- A single order/fill match is not campaign or protection certification. P0 remains open.
