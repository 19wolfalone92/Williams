# TASK 001.7 — Mutation Boundary Proof

- Current source/test SHA: `7e7b240f07d11e0077d1b1d741a8087f4846a750`.
- Status: PARTIAL — P0 OPEN.

## Inventory and enforced boundaries
- Android mutation wrappers `signedPost`, `signedDelete`, and `signedCancelReplace` delegate to `StandaloneRuntime.signedRequest`, where durable intent preparation/claim and mutation state tracking occur.
- Android read-only call sites include `getBody` (market reads) and `signedRawGet` (signed GET). Automated guard classifies these separately from the mutation sink.
- Known mutation endpoints include POST/DELETE `/api/v3/order`, POST `/api/v3/orderList/oco`, DELETE `/api/v3/orderList`, and POST `/api/v3/order/cancelReplace`. Only single POST orders have operation-specific Android reconciliation; OCO, cancellation and cancel-replace remain unresolved/fail-closed.
- Python transport boundary is `binance_client.py:BinanceSpotClient._request`; `execution_barrier.py` persists/adjudicates mutations; `execution_recovery.py` is read-only; `trader.py` blocks startup on unresolved recovery/gates; `db.py` persists recovery state.

## Automated regression
- `tests/test_mutation_boundary_inventory.py` inventories raw Android OkHttp call sites, asserts mutation wrappers delegate to the durable sink, distinguishes read-only GET call sites, flags direct Python HTTP mutation calls outside the central client, and checks recovery remains read-only.
- Campaign CI Python job `38278336855` on SHA `7e7b240...`: SUCCESS, 233 passed / 4 warnings, including the inventory regression.
- The guard caught two false positives during development (dashboard backend HTTP and Telegram notification) and one omitted Binance read-only GET call site; classifications were corrected rather than suppressing the test.
- This static regression is not proof of all indirect SDK/adapter call chains or runtime reachability.

## Remaining
- Complete endpoint and caller-chain inventory, OCO/cancel/cancel-replace recovery, deterministic exchange mutation-count/retry tests, and proof of protection/exposure gates. Android and Python SQLite transactions are separate; no cross-runtime atomicity is assumed.
