# TASK 001.9 — Exchange Reconciliation

- Latest source/test HEAD at report preparation: `fd3276c5f041dd47f0941d5b07fff4726c8c9124`.
- Last completed targeted Python Campaign CI: [38306130277](https://github.com/19wolfalone92/Williams/actions/runs/38306130277), 258 passed on prior SHA `5c6499a26a493ab4ff11ca44486850a79283d7ef). The final OCO list-ID guard/test added afterward still requires exact-SHA verification.
- Status: PARTIALLY IMPLEMENTED — P0 OPEN.

## Implemented in Python recovery code

- Trade history requires unique non-empty trade IDs and exact non-empty order IDs matching the recovered order.
- Missing required quantity/economic fields are rejected; quantity, price, quote quantity, commission, commission asset, time, buyer flag and maker flag are validated.
- Buyer flag must agree with the recovered order side; quote quantity must agree with quantity × price within a bounded tolerance.
- A bounded `myTrades(limit=1000)` response containing 1000 rows is rejected as potentially truncated. Pagination completeness is not assumed.
- OCO list client ID is checked; persisted `orderListId` conflicts now raise a fail-closed validation error before child-leg lookup.
- Single-order identity/evidence validation and durable reconciliation checks remain in place.

## Tested on prior SHA

The passing Campaign Python run includes deterministic fake-exchange tests for:
- pre-submit persistence failure with zero mutations;
- exchange acceptance followed by lost response and restart reconciliation;
- response followed by failed local status persistence;
- restart from AMBIGUOUS;
- partial fill followed by cancellation with trade evidence;
- duplicate recovery invocation;
- crash after reconciliation commit;
- exchange order exists while local exchange order ID is absent;
- incomplete, missing-ID, duplicate-ID and truncated trade history;
- ambiguous cancellation and retry blocking;
- compound cancellation/cancel-replace fail-closed behavior;
- BL-004 sentinel scan of actual temporary SQLite content.

The above results are **TESTED** only on SHA `5c6499a26a493ab4ff11ca44486850a79283d7ef`. The later OCO persisted-list-ID conflict test has not yet been confirmed by CI.

## Unsupported / still blocked

- Cancel-replace (including partial success), cancel order-list and cancel OCO recovery deliberately remain unresolved/unsupported; no single-order lookup can prove a two-sided operation's final state.
- OCO recovery does not by itself prove campaign exposure/protective coverage or REST/user-stream convergence.
- The fake-exchange harness exercises Python production abstractions, not Android's direct REST runtime. Android end-to-end mutation counts and crash/restart injection remain unverified.
- FI-07 does not yet inject a crash at every individual durable transition. FI-08 models missing local exchange order ID, not every possible missing-local-record recovery case.
- A timeout, unavailable endpoint, incomplete history, contradictory order/list identity or failed persistence is not evidence of non-acceptance. UNKNOWN remains unresolved and must block unsafe retry.

No live credentials or orders used.