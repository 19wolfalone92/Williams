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