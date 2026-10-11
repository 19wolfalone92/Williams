# TASK 000.3 — Findings

**Status: BLOCKED — PYTHON PRODUCTION PATH INVENTORY INCOMPLETE**  
**Source SHA:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`

## P1-1 — cancelReplace recovery does not prove both order legs reconciled

- **Files/symbols:** `campaign_execution.py::CampaignExecutionService.replace_structural_stop` (source lines 2201–2268); `execution_recovery.py::ExecutionRecoveryCoordinator._query_order/recover_one`; `binance_client.py::BinanceSpotClient.cancel_replace` and `_request`.
- **Evidence SHA:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.
- **Observed source behavior:** replacement creates a new CID and a standard `OrderIntent`, then calls `cancel_replace` with the existing order ID. The client sends `POST /api/v3/order/cancelReplace` with `cancelReplaceMode=STOP_ON_FAILURE`. HTTP 409 and transport errors classify the operation as unknown and forbid blind retry. The caller checks `cancelResult` and `newOrderResult`; non-success marks the campaign reconciliation-required. The standard intent persists the replacement CID but does not visibly persist the prior protective order's clientOrderId as a second leg. Generic recovery queries the intent order/CID and trade history, not a proven two-leg state machine.
- **Risk:** a replacement CID lookup alone cannot prove whether the original stop remains active or was canceled when the replacement failed or response was lost.
- **Required evidence:** persist old order ID and old clientOrderId together with replacement CID as a cancelReplace-specific durable intent; recovery must query/reconcile both legs and related fills before unlocking or declaring protection restored. Add offline tests for each partial response combination, accepted request/lost response, timeout, 409, restart between mutation and persistence, and persistence failure.
- **Severity:** P1 recovery/protection assurance gap. No mandatory-gate bypass confirmed in known current callsites.

## P1-2 — static registry gate is not a complete caller-to-barrier proof

- **Files/symbols:** `mutation_registry_gate.py::analyze_python_sources`; `binance_client.py::BinanceSpotClient.order/order_safe/cancel_replace/cancel_order/cancel_open_orders/create_oco_sell/create_oco_sell_safe/cancel_oco`.
- **Evidence SHA:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.
- **Observed:** the gate checks AST patterns for raw transports, endpoint literals and registered wrappers, and its own output expressly calls the model bounded. It does not perform complete interprocedural proof that every public mutation-wrapper caller entered `CampaignExecutionService._submit` and `ExecutionBarrier.execute`. Existing negative fixtures exercise several syntactic bypass forms but do not prove arbitrary runtime/reflection/alias reachability.
- **Risk:** transport approval may be mistaken for proof of per-caller identity, risk checks and durable-intent gates.
- **Required evidence:** complete caller/callee graph across all Python modules; enforce wrapper callsites against approved campaign submit callbacks or fail closed; add negative fixtures for direct public client methods, aliases, `getattr`, callbacks and alternate adapters; execute at exact audited source revision.
- **Severity:** P1 enforcement assurance gap. No reachable production bypass established in inspected known current callsites.

## Unresolved — cancel-all wrapper reachability

- **File/symbol:** `binance_client.py::BinanceSpotClient.cancel_open_orders` (source line 465).
- **Evidence SHA:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.
- **Current evidence:** no direct caller found in the inspected runtime modules; wrapper itself calls the registered low-level `_request` sink with `DELETE /api/v3/openOrders`.
- **Needed to resolve:** enumerate all repository-wide references, aliases, `getattr`, callbacks and dynamically passed client methods at the required SHA; add a focused reachability/rejection test.

## Unresolved — OCO wrapper production reachability

- **Files/symbols:** `binance_client.py::BinanceSpotClient.create_oco_sell/create_oco_sell_safe/cancel_oco`; `trader.py::Trader.place_oco`; `portfolio_trader.py::MultiPositionTrader._create_oco/manual_sell`.
- **Evidence SHA:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.
- **Current evidence:** known legacy OCO create paths guard against the production Binance client; known OCO cancellation call is in the non-`BinanceSpotClient` branch. The repository tree includes 122 Python files including tests, but a complete runtime/dynamic call graph has not been proven.
- **Needed to resolve:** reference and alias analysis across every production module, tests proving production-client calls are rejected from legacy paths, and review of dynamically dispatched client interfaces.

## No confirmed mandatory-gate bypass in known production paths

Known campaign entry/add-on/protection/exit/cancel/cancelReplace callsites enter `CampaignExecutionService._submit`, which checks account identity before invoking the barrier. The barrier uses durable readiness/intent claim, context/state and pre-submit checks, persists `SUBMITTING`, invokes the callback, persists outcome, and marks unknown outcomes for reconciliation. This is a bounded source conclusion, not whole-program proof.

No exactly-once financial effect or global mutation-path coverage claim is made.

## Follow-up source verification at required SHA

The exact-ref source was re-opened for `campaign_execution.py` lines 2201–2268 and `binance_client.py` lines 110–160. `CampaignExecutionService.replace_structural_stop` creates a new CID and standard `OrderIntent`, calls `cancel_replace` with the existing order ID, then checks `cancelResult` and `newOrderResult`. `BinanceSpotClient._request` explicitly maps HTTP 409 for `/api/v3/order/cancelReplace` to `unknown_execution=True` and says reconciliation is required before retry. This supports conservative ambiguity classification, but does not establish durable two-leg recovery: the standard intent contains the new CID and the generic recovery lookup centers on the intent's order/CID and trades. P1-1 remains open.

The immutable Git tree contains 122 Python files including tests. The priority source files were fetched at the required SHA, but a complete line-by-line review of all 122 modules and dynamic alias/callback paths has not been established. P1-2 and the `cancel_open_orders`/OCO reachability items remain open. No mandatory-gate bypass was confirmed in the known inspected production callsites.


## Follow-up review — 2026-10-11

The required-ref source was reopened for `binance_client.py` and `campaign_execution.py`. The low-level client implements `cancel_replace` as `POST /api/v3/order/cancelReplace` with `cancelReplaceMode=STOP_ON_FAILURE`; its docstring explicitly states the operation is non-transactional. `CampaignExecutionService.replace_structural_stop` submits the replacement through `_submit`, then evaluates `cancelResult` and `newOrderResult`; ambiguous/non-success results mark reconciliation required. The durable standard intent visible at this callsite records the replacement CID, but not the old protective order's clientOrderId as a second leg. This corroborates P1-1; it does not prove both protective-order states can be reconstructed after a lost response.

The public wrapper definitions for `cancel_open_orders`, `create_oco_sell`, `create_oco_sell_safe`, and `cancel_oco` are present in `binance_client.py`. Existing call-path evidence only establishes the known legacy guards/non-client branches documented above. A complete repository-wide interprocedural analysis of aliases, callbacks and dynamic dispatch was not completed in this follow-up, so P1-2 and the cancel-all/OCO reachability questions remain open.

Current PR metadata reports head `6a6e47c798e4a2cc6e2d2a5245911a22788170fe`, and newer PR-triggered registry, backend, campaign and APK workflows are green. They do not close the findings: the audit target is still immutable source `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`, and the inspected backend/campaign job logs explicitly checked out merge ref `c24e28defc38e19baf20c777a069e80b87c7aecf`, not the source commit by itself.

**Status remains BLOCKED. Do not merge PR #51.**


## Follow-up verification — current PR head 2518a69 (2026-10-11)

PR #51 remains open and Draft, with head `2518a69f96a41f74a18445017c666855b420b4f1`; no merge occurred. The latest observed Mutation Registry, backend and Campaign Python CI jobs passed on merge checkout `e5bf8466760c4583e272d339036f59a4dfae03ef` (merge of PR head `2518a69f96a41f74a18445017c666855b420b4f1` into base `04da5f9c09572e99ff310be3a1908179165e528c`). The logs therefore do not prove isolated execution of immutable required source `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.

No new source evidence closes P1-1: conservative ambiguity classification and reconciliation-required state are present, but durable recovery of both old and replacement order legs remains unproven. No new source evidence closes P1-2: full interprocedural caller reachability for OCO, cancel-all, aliases, callbacks and dynamic dispatch remains incomplete. The Android jobs were still in progress at the last observation.

**Disposition unchanged: BLOCKED — PYTHON PRODUCTION PATH INVENTORY INCOMPLETE. Do not merge PR #51.**
