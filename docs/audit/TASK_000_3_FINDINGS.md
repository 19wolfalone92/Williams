# TASK 000.3 — Findings

**Status: BLOCKED — PYTHON PRODUCTION PATH INVENTORY INCOMPLETE**  
**Source SHA:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`

## P1 — cancelReplace ambiguous-result recovery does not prove both legs are reconciled

- **Files/symbols:** `campaign_execution.py::CampaignExecutionService.replace_structural_stop` (around line 2227); `execution_recovery.py::ExecutionRecoveryCoordinator._query_order/recover_one`; `binance_client.py::BinanceSpotClient.cancel_replace`.
- **Evidence SHA:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.
- **Observed:** structural stop replacement submits non-atomic `POST /api/v3/order/cancelReplace`; returned `cancelResult`/`newOrderResult` are checked. On lost response, generic recovery looks up the replacement intent by replacement CID and its trades, but the inspected intent does not establish a complete two-leg recovery proof for both prior protective order and replacement.
- **Risk:** the system may conservatively remain locked, but this review cannot prove whether the old stop was canceled while the new stop failed, or that protection is restored before unlocking.
- **Evidence needed:** persist old order ID/clientOrderId and replacement CID as one cancelReplace-specific intent; recovery must reconcile both orders and fills before declaring protection restored. Add offline fault-injection tests for each partial result, response loss and restart boundary.
- **Severity:** P1 recovery/protection assurance gap; no mandatory-gate bypass confirmed in known callsites.

## P1 — mutation registry gate is not a complete caller-to-barrier proof

- **Files/symbols:** `mutation_registry_gate.py::analyze_python_sources`; `binance_client.py::BinanceSpotClient.order/order_safe/cancel_replace/cancel_order/cancel_open_orders/create_oco_sell/cancel_oco`.
- **Evidence SHA:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.
- **Observed:** static gate checks raw transport containment and registered method/endpoint literals. It does not prove every call to a public mutation wrapper originates inside `CampaignExecutionService._submit` and `ExecutionBarrier.execute`. Known legacy production-client BUY/OCO/emergency-Sell paths have explicit guards; known campaign mutation paths enter the barrier. A future/new direct caller could still use the public wrapper unless callsite enforcement is strengthened.
- **Risk:** registered transport approval could be mistaken for proof of per-caller identity and durable-intent gates.
- **Evidence needed:** add interprocedural/callsite enforcement allowing mutation wrappers only from approved campaign-service submit callbacks; negative fixtures for direct `client.order_safe`, `cancel_order`, `cancel_replace`, `create_oco_sell`, aliases and dynamic references.
- **Severity:** P1 enforcement assurance gap; no reachable production bypass established in inspected current callsites.

## Unresolved — cancel-all wrapper reachability
- **File/symbol:** `binance_client.py::BinanceSpotClient.cancel_open_orders`.
- **Evidence SHA:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.
- No direct caller found in inspected runtime modules. Need repository-wide direct/alias/`getattr`/callback search at this SHA and a focused reachability test.

## Unresolved — OCO wrapper production reachability
- **Files/symbols:** `binance_client.py::BinanceSpotClient.create_oco_sell/cancel_oco`; `trader.py::Trader.place_oco`; `portfolio_trader.py::MultiPositionTrader._create_oco/manual_sell`.
- **Evidence SHA:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.
- Known legacy paths reject the Binance client before OCO create; OCO cancel is called only on the non-Binance client branch; canonical campaign protection uses STOP_LOSS. Need complete dynamic call graph to rule out other callers.

## No confirmed mandatory-gate bypass in known production paths

Known campaign entry/add-on/protection/exit/cancel/cancelReplace callsites enter `CampaignExecutionService._submit`, which checks account identity then calls the barrier. The barrier checks durable readiness/claim, context/state and pre-submit checks, persists `SUBMITTING`, invokes the callback, persists the result and marks UNKNOWN on ambiguity. This is a bounded source conclusion, not whole-program proof.

No exactly-once financial effect or global mutation-path coverage claim is made.
