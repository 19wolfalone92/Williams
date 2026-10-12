# TASK 001.9 — Mutation Boundary Proof

- Latest source/test HEAD at report preparation: `fd3276c5f041dd47f0941d5b07fff4726c8c9124`.
- Status: PARTIAL — P0 OPEN.

## Inventory and controls

- Android regression inventory: `tests/test_mutation_boundary_inventory.py` enumerates raw OkHttp call sites and asserts Binance mutations remain inside `StandaloneRuntime.signedRequest`, with durable prepare/claim and SUBMITTING/UNKNOWN handling.
- Known Android mutation wrappers include signed POST/DELETE and cancel-replace; recovery/read-only lookup must not call mutation wrappers.
- Python HTTP mutations are centralized in `binance_client.py`; execution admission/persistence is mediated by `execution_barrier.py`; recovery uses read-only lookup calls in `execution_recovery.py`.
- Python fake-exchange tests assert actual mutation counters for ambiguous submission/recovery and unresolved retry.
- Unsupported `CANCEL_REPLACE`, `CANCEL_LIST`, `CANCEL_ORDER_LIST`, `ORDER_LIST_CANCEL` and `CANCEL_OCO` operation types are now explicitly rejected by recovery before exchange lookup; durable state remains AMBIGUOUS.

## CI evidence

- Campaign Python run [38306130277](https://github.com/19wolfalone92/Williams/actions/runs/38306130277): SUCCESS, 258 passed on prior SHA `5c6499a26a493ab4ff11ca44486850a79283d7ef`.
- Final OCO list-ID conflict guard/test and documentation descendants require their own exact-SHA CI result.
- Separate APK workflow [38306239714](https://github.com/19wolfalone92/Williams/actions/runs/38306239714) ended `startup_failure` with no jobs/artifacts; no Android proof is available from that run.

## Remaining gaps

- Static inventory is not a complete dynamic call graph of every Android/Python emergency exit, background worker, SDK/adapter, diagnostic route and test-only mutation route.
- No integrated Android fake exchange currently demonstrates zero duplicate mutations across all FI-01–FI-12 boundaries.
- Compound cancel-replace and order-list cancellation are blocked rather than falsely claimed recovered.
- Android SQLite and Python SQLite are separate stores and do not share an atomic transaction.

Status: PARTIAL — P0 OPEN.