# TASK 000.3 — Caller-to-Sink Graph

**Source SHA:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`  
**Status:** Known paths documented; full production reachability remains unresolved.

## Confirmed production paths

### CP-01 — Entry/add-on order
`server.py::start → RuntimeState.loop → Trader.process → Trader._auto_scan_process → MultiPositionTrader.scan_and_execute → MultiPositionTrader.execute_campaign → CampaignExecutionService.arm_initial_entry / arm_add_on → CampaignExecutionService._submit → ExecutionBarrier.execute → BinanceSpotClient.order_safe → BinanceSpotClient.order → BinanceSpotClient._request → requests.Session.request`

Evidence anchors: `campaign_execution.py` around lines 1072 and 1901 calls `_submit`; `_submit` around line 874 checks account identity then calls the barrier. `execution_barrier.py::ExecutionBarrier.execute` persists `SUBMITTING` before the callback. `binance_client.py::order_safe` calls `order`; `order` sends fixed `POST /api/v3/order`; `_request` calls `session.request` around line 98.

### CP-02 — Protective stop
`CampaignExecutionService.create_hard_stop → _submit → ExecutionBarrier.execute → BinanceSpotClient.order_safe → order → _request → requests.Session.request`

The canonical campaign protection path uses a STOP_LOSS order. Legacy `Trader.place_oco` and `MultiPositionTrader._create_oco` reject a `BinanceSpotClient` before their direct OCO calls.

### CP-03 — Market exit
`server.py::manual_sell → RuntimeState.ensure_multi → MultiPositionTrader.manual_sell → CampaignExecutionService.exit_market → _submit → ExecutionBarrier.execute → order_safe → order → _request → requests.Session.request`

The production `BinanceSpotClient` branch routes through campaign execution; legacy direct adapter logic is separate.

### CP-04 — Protective-order cancellation
`CampaignExecutionService.exit_market / recovery caller → CampaignExecutionService._execute_cancel → _submit → ExecutionBarrier.execute → BinanceSpotClient.cancel_order → _request → requests.Session.request`

`_execute_cancel` checks durable `campaign_orders` ownership and verifies exchange symbol/orderId/clientOrderId. Target IDs are stored on the cancel intent before DELETE.

### CP-05 — Structural stop replacement
`campaign lifecycle caller → CampaignExecutionService.replace_structural_stop → _submit → ExecutionBarrier.execute → BinanceSpotClient.cancel_replace → _request → requests.Session.request`

The replacement CID and intent are persisted before network mutation. Returned `cancelResult` and `newOrderResult` are checked; ambiguous results mark reconciliation required.

## Registered wrappers without confirmed production reachability

### CP-06 — Native OCO create
`Trader.place_oco / MultiPositionTrader._create_oco → BinanceSpotClient.create_oco_sell(_safe) → _request → session.request`

Known legacy callers guard against `BinanceSpotClient`; current canonical campaign protection uses STOP_LOSS. Test/mock adapter paths do not prove a production Binance call path. Arbitrary future callers remain unresolved.

### CP-07 — Cancel all open orders
`BinanceSpotClient.cancel_open_orders → _request → session.request`. **Caller link UNRESOLVED**: no direct caller found in inspected runtime modules. Resolve with repository-wide reference analysis including aliases, `getattr`, callbacks and dynamic dispatch, plus a focused reachability test.

### CP-08 — Native OCO cancel
Known callsite: `MultiPositionTrader.manual_sell` calls `cancel_oco` only in the non-`BinanceSpotClient` branch. Production Binance manual sell routes through campaign execution and single-order cancellation. Dynamic/unreviewed callers remain unresolved.

## Test-only paths and limits
- `mock_exchange.py::MockExchange` operations are in-memory and do not invoke Binance HTTP transport.
- `test_task0094_mutation_containment.py` asserts legacy BUY/OCO/emergency-SELL paths fail before the production-client stub's mutation methods.
- The registry gate is bounded AST/endpoint containment, not a complete interprocedural proof that all public client mutation methods are reached only through the barrier.
- Dynamic dispatch, runtime monkey-patching, imported aliases and external callers remain limitations.
- `cancelReplace` ambiguity recovery does not establish complete two-leg reconciliation of the old order and replacement after a lost response.

All claims refer to source SHA `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`; do not extrapolate to later commits.
