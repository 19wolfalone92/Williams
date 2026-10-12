# TASK 001.8 — Mutation Boundary Proof

- Audited code/test SHA: `31ba73ff81c43edb5be4606b200dce752148967a`.
- Status: PARTIAL — P0 OPEN.

## Inventory currently enforced

- Android raw OkHttp call-site inventory is covered by `tests/test_mutation_boundary_inventory.py`; current guard expects the three raw calls in `StandaloneRuntime.kt` and verifies the signed mutation sink contains durable prepare/claim/state handling.
- Android wrappers `signedPost`, `signedDelete`, and `signedCancelReplace` route through `signedRequest`; read-only market and signed GET are classified separately.
- Known mutation endpoints: POST/DELETE `/api/v3/order`, POST `/api/v3/orderList/oco`, DELETE `/api/v3/orderList`, POST `/api/v3/order/cancelReplace`.
- Python exchange transport is centralized in `binance_client.py:BinanceSpotClient._request`; mutation adjudication/persistence in `execution_barrier.py`; `execution_recovery.py` is read-only; startup gate in `trader.py`; durable state in `db.py`.

## CI evidence

- Run [38301629067](https://github.com/19wolfalone92/Williams/actions/runs/38301629067), SHA 31ba73f: Campaign Python job SUCCESS, including configured deterministic test set. Campaign Android job remained IN_PROGRESS at last inspection.
- Static inventory regression is TESTED by that Python job; it does not prove every indirect SDK/adapter route or runtime reachability.
- Standalone APK run [38301630392](https://github.com/19wolfalone92/Williams/actions/runs/38301630392) failed at workflow startup with no jobs/artifacts; it provides no Android evidence.

## Remaining gaps

- No complete dynamic call graph or exhaustive proof for all emergency-exit, recovery worker, background, diagnostic and adapter paths.
- OCO/cancel/cancelReplace reconciliation is unsupported; those ambiguous operations must remain UNKNOWN/RECONCILING and block normal trading.
- No integrated fake-exchange mutation-count regression currently demonstrates zero resubmissions during unresolved recovery.
- Android SQLite and Python SQLite are separate stores and do not form one atomic transaction.

Status: PARTIAL — P0 OPEN.