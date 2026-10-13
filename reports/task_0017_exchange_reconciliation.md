# TASK 001.9 — Exchange Reconciliation

- Code/test SHA: `fd3276c5f041dd47f0941d5b07fff4726c8c9124`.
- Campaign Python test step [38306238359](https://github.com/19wolfalone92/Williams/actions/runs/38306238359): **259 passed, 4 warnings** on fd3276. The overall run was subsequently cancelled during cleanup/concurrency; Android verification did not complete.
- Branch HEAD before this report refresh: `1b051659dba73913ad3c2e0a39f8278042cf4729`, documentation-only descendant.

## Implemented and exercised in Python tests

- Trade history requires unique non-empty trade IDs and exact order IDs matching the recovered order.
- Missing quantity/economic evidence is rejected. Quantity, price, quote quantity, commission, commission asset, time, buyer flag and maker flag are validated.
- Buyer flag must match order side; quote quantity must match quantity × price within tolerance.
- A bounded `myTrades(limit=1000)` response with 1000 rows is treated as potentially truncated and remains unresolved.
- OCO list client ID is validated, and the new persisted `orderListId` conflict guard is present. The test step with the guard passed in run 38306238359.
- Single-order identity and durable reconciliation validation remain in place.

## Explicitly unsupported / blocked

- Cancel-replace, partial cancel-replace, cancel-list and cancel-OCO recovery remain unsupported; the worker refuses to resolve these via a single order lookup.
- OCO reconciliation alone does not prove campaign exposure, protective coverage or REST/user-stream convergence.
- The Python fake-exchange tests do not exercise Android's direct REST runtime.
- FI-07 still lacks injection at every durable transition; FI-08 covers missing local exchange order ID, not all missing-intent inventory cases.
- No response, failed lookup, truncated history, contradictory identity or persistence error is not evidence of non-acceptance. UNKNOWN must remain unresolved and block unsafe retry.

P0 remains open. No live credentials or orders used.

## TASK 001.12 exact-HEAD addendum

- At SHA `4e7ad93990b4b9d3b5291bed9d83909fbfac3bcf`, Campaign Python tests passed 262/262 (run [38341931762](https://github.com/19wolfalone92/Williams/actions/runs/38341931762), job 115114648003); backend tests passed 223/223 (run [38341931627](https://github.com/19wolfalone92/Williams/actions/runs/38341931627), job 115114647440).
- FI-13 code repeats 12 fresh SQLite databases with two independent SQLite connections and two threads. Its assertions include exactly one claim, one exchange observation, zero fake-exchange mutations, expected persisted order ID and terminal durable state. This is thread-level, same-process evidence, not a multi-process proof.
- Recovery validation still rejects conflicting persisted exchange order IDs, incomplete trade evidence and unsupported compound cancel/cancel-replace operations. Unknown outcomes remain unresolved; these cases are fail-closed, not fully implemented compound recovery.
- Not yet proven: lease expiry during a live reconciliation, process kill across all transaction boundaries, active/partial/terminal cases under true multiprocessing, or Android/Python cross-runtime atomicity. No live Binance mutation was used.


## TASK 001.12 Android evidence correction

At code SHA `b6d43d439a1c49c577570d36332bbe1c60ff7477`, Campaign Android job 115116130632 in [run 38342390656](https://github.com/19wolfalone92/Williams/actions/runs/38342390656) succeeded, including unit tests and lint. The exact APK hash is `e9ce68cfeac340c09979463aa5a2e0b1a346d08d19403e163a2d99589f254ac6`; provenance matches `b6d43d439a1c49c577570d36332bbe1c60ff7477`. This validates build/runtime tests, not the missing cross-process recovery matrix. Recovery unknown states remain fail-closed; compound cancel/cancel-replace remains unsupported. A later report-only HEAD requires its own exact-HEAD verification.
