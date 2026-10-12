# TASK 001.7 — Mutation Boundary Proof

- Source SHA: `e5f82ae60b0b5c1e9512f9efe3bedc1d018ab132`; static guard added at `1ef8b13575bc5b728c8447b0cd92b54c24e89504`.
- Status: PARTIAL — P0 OPEN.

## Inventory
- Android authenticated REST mutations route through `StandaloneRuntime.kt:signedRequest`, called by `signedPost`, `signedDelete`, and `signedCancelReplace`.
- Known mutation endpoints include `POST /api/v3/order`, `DELETE /api/v3/order`, `POST /api/v3/orderList/oco`, `DELETE /api/v3/orderList`, and `POST /api/v3/order/cancelReplace`; call sites include campaign entry/exit and protection replacement/cancellation.
- Android recovery currently reconciles only single `POST:/api/v3/order`. OCO/list, cancellation and cancel-replace remain unresolved.
- Python transport is `binance_client.py:BinanceSpotClient._request`; `execution_barrier.py:ExecutionBarrier.execute` owns admission/persistence; `execution_recovery.py:ExecutionRecoveryWorker` is intended read-only; `trader.py` blocks startup when canonical reconciliation/gates fail; `db.py` persists recovery state.

## Automated regression
- Added `tests/test_mutation_boundary_inventory.py`: inventories Android raw OkHttp `.newCall(` sites, asserts mutation wrappers delegate to the durable sink and flags direct Python HTTP mutation calls outside the centralized client.
- Exact-SHA result for this new static test is pending CI on SHA `1ef8b135...`. It is a regression guard, not proof of complete runtime reachability or semantic correctness.

## Remaining
- Complete indirect SDK/adapter call-chain proof and endpoint classification; OCO/cancel/cancel-replace operation-specific recovery; deterministic mutation-count/retry tests; protection/exposure gate verification.
- Android SQLite and Python DB are separate transactions; no cross-runtime atomicity is assumed.
