# TASK 000.5 — Execution Safety Remediation and Evidence

**Decision: BLOCKED — P1-1 and P1-2 remain open. Do not merge PR #51.**

- Issue: #52
- Required immutable source: `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`
- Work branch: `audit/williams-2.1-task-009-integration`
- Branch HEAD at the start of this task: `d0c3a4fe71025f69e3356fa0d397b5544898d240`
- PR #51: open, Draft, unmerged.
- Safety: no exchange requests, real orders, Testnet trading, or strategy changes were made.

## 1. Work performed and evidence boundary

I re-opened Issue #52, PR #51, the work branch and immutable baseline through GitHub. The inventory file records exactly 123 unique Python paths and their baseline blob SHAs. I fetched all 123 baseline Python files and performed a repository-wide keyword/source-pattern scan for order mutation wrappers, transport primitives, ExecutionBarrier references, dynamic `getattr` dispatch and related paths. I then inspected relevant source sections at the immutable SHA.

This scan is useful breadth evidence, **not** a semantic line-by-line review of all 123 files or a complete interprocedural call graph. The existing inventory remains honest: 11 files are marked targeted-review-only and 112 are not reviewed line by line. No Python source was modified in this task because the available environment could not clone the repository (outbound DNS to github.com failed), no local test execution was possible, and a partial untested change to order recovery would be unsafe. This is not a claim that the blockers were fixed.

## 2. Confirmed defects

### P1-1 — cancelReplace does not persist a recoverable two-leg operation

At baseline `campaign_execution.py` blob `f50a9e3c6953d763ab5017d990af318f191db60b`, `CampaignExecutionService.replace_structural_stop`:
- generates a replacement client order ID and persists it as the ordinary intent's `client_order_id`;
- passes the original exchange `existing_order_id` only inside the submission closure to `BinanceSpotClient.cancel_replace`;
- does not set `OrderIntent.target_order_id` or `target_client_order_id` for the original leg;
- inspects `cancelResult` and `newOrderResult` only after a response is returned.

At baseline `binance_client.py` blob `774b17505729b5b9f10d4f4c961e9defc046468e`, `cancel_replace` is a non-atomic `POST /api/v3/order/cancelReplace`. HTTP 409 and transport/5xx ambiguity are conservatively marked unknown and not blindly retried. The barrier persists a generic `SUBMITTING` intent before submission; when a response arrives, the generic result writer stores the response event and only extracts a top-level `orderId` into the generic intent row. That is not a durable operation record capable of independently reconciling both cancel and replacement legs after a lost response or process crash.

**Required fix (not implemented here):** add a dedicated cancelReplace operation journal persisted before network submission, containing account/execution scope, symbol, campaign/protection identity, original order ID/CID, replacement CID, state and response/evidence references. Recovery must independently query both legs and complete trade history, persist both leg outcomes and fills idempotently, and retain the account/symbol execution lock whenever either leg or its fills cannot be proven. An unknown operation must never be resubmitted automatically.

### P1-2 — barrier coverage is not globally enforced

The canonical campaign submission helper in `campaign_execution.py` refuses to submit without an `ExecutionBarrier` and calls `barrier.execute`. The bounded mutation registry rejects representative raw transport and dynamic endpoint examples, but its own successful output says `PASS WITH BOUNDED MODEL — independent review still required`.

The 123-file scan identified the following mutation-sensitive production surfaces:

| Source location at baseline | Observed path | Conclusion |
|---|---|---|
| `campaign_execution.py:877`, mutation call sites around lines 1072, 1901, 1977, 2051, 2143, 2227 | `_submit` → `ExecutionBarrier.execute` → client mutation | Canonical campaign paths are gated; cancelReplace still has the separate two-leg recovery defect. |
| `binance_client.py:27,98` and public mutators | `BinanceSpotClient._request` → `requests.Session.request`; public order, cancel, cancelReplace, OCO and cancel-all wrappers | Transport sink is registered, but direct invocation of public mutators is not inherently prevented by the transport. |
| `trader.py:1210–1240` | OCO intent enters `execution_barrier.execute` | **Confirmed identity gap:** the OCO client/list ID is generated separately after the intent is created and passed only to the closure. The durable intent does not carry that CID, so ambiguous OCO recovery cannot rely on the intent's persisted client identity. |
| `portfolio_trader.py:843–945` | Legacy `_create_oco`; `getattr(self.client, "create_oco_sell_safe", None) or self.client.create_oco_sell` | The legacy helper blocks a concrete `BinanceSpotClient` instance, but dispatch is adapter-dependent. The full reachability and enforcement story for every adapter is not proven. |
| `portfolio_trader.py:1676–1698` | Dynamic `getattr(..., "cancel_oco")`, followed by legacy direct order call for non-`BinanceSpotClient` clients | Production reachability of every alternate adapter is not established; do not infer this route is harmless or unused. It requires explicit adapter policy and negative tests. |
| `binance_client.py` public `cancel_open_orders` wrapper | `DELETE /api/v3/openOrders` | The mutator exists. No conclusion that it is unused is justified from a keyword scan alone; alias/dynamic reachability remains unresolved. |
| `data.py:67` | direct client `_request("GET", "/api/v3/klines", ...)` | Read-only market-data access, not an order mutation. |
| `telegram_bot.py:45` | `requests.post` | Non-exchange notification sink; distinct from Binance mutation transport. |

The scan did not establish every alias, imported reference, callback, reflection path, monkey patch, descriptor, injected adapter or dynamic dispatch chain. Therefore I cannot claim that all production mutation paths are protected.

**Required fix (not implemented here):** enforce a non-forgeable, fail-closed mutation capability at the lowest Binance mutation transport (or an equivalently comprehensive boundary), issued only after the barrier's durable admission. Make adapter policy explicit rather than treating every non-`BinanceSpotClient` object as safe. Persist the OCO list client ID in the durable intent before submitting. Add negative tests for every public mutator and production entry point, including OCO, cancel-all, aliases, callbacks, dynamic dispatch and alternative adapters.

## 3. Tests and CI

### Current work-branch CI

The latest observed PR-triggered runs for branch HEAD `d0c3a4fe71025f69e3356fa0d397b5544898d240` completed successfully:

- [Python backend tests — run 38171251079](https://github.com/19wolfalone92/Williams/actions/runs/38171251079): 148 passed, 4 warnings.
- [Williams Campaign CI — run 38171251089](https://github.com/19wolfalone92/Williams/actions/runs/38171251089): 208 passed, 4 warnings; Android build/static checks also passed.
- [Enforced Mutation Registry — run 38171251083](https://github.com/19wolfalone92/Williams/actions/runs/38171251083): 14 passed; output is `PASS WITH BOUNDED MODEL`, not whole-program proof.
- [Build Android APK — run 38171251094](https://github.com/19wolfalone92/Williams/actions/runs/38171251094): build, lint, unit tests, APK verification and provenance steps completed successfully.

**Checkout caveat:** inspected job logs checked out merge commit `bc8d2a88da81dece541cb36bb2cd00646ae21150` (merge of branch HEAD `d0c3a4...` into base `04da5f9c09572e99ff310be3a1908179165e528c`). These are successful merge-checkout CI results, not isolated checkout results for the branch head or the immutable baseline.

### Baseline-associated PR CI

The runs associated by GitHub with the baseline SHA are also PR merge-checkout runs, not isolated baseline execution:

- [Python backend — run 38156886908](https://github.com/19wolfalone92/Williams/actions/runs/38156886908)
- [Campaign CI — run 38156886916](https://github.com/19wolfalone92/Williams/actions/runs/38156886916)
- [Mutation Registry — run 38156886920](https://github.com/19wolfalone92/Williams/actions/runs/38156886920)
- [Build Android APK — run 38156886910](https://github.com/19wolfalone92/Williams/actions/runs/38156886910)

The logs explicitly checked out merge commit `c24e28defc38e19baf20c777a069e80b87c7aecf` (merge of baseline `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44` into base `04da5f9c09572e99ff310be3a1908179165e528c`). They **do not** satisfy the criterion requiring a test job whose checkout HEAD is exactly `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.

No local tests were run. No new failure-injection tests were added or run. No test result is claimed for the exact immutable SHA.

## 4. Acceptance matrix

| Criterion | Status | Evidence / blocker |
|---|---|---|
| All 123 Python files accounted for | **PASS (inventory only)** | 123 unique paths/blob SHAs in the inventory. |
| All 123 files semantically reviewed line by line; gaps disclosed | **BLOCKED** | All files were fetched and keyword-scanned; 112 remain not reviewed line by line and 11 targeted-review-only. |
| Production mutation paths documented | **PARTIAL** | Principal sinks and suspicious dynamic paths recorded above; full caller graph unresolved. |
| No bypass of ExecutionBarrier within proven coverage | **BLOCKED** | Bounded AST gate and representative negative tests pass, but no whole-program proof or all-entry-point negative suite. |
| cancelReplace recovers both legs across ambiguity/restart | **BLOCKED** | No durable two-leg journal or recovery implementation/test. |
| Failure-injection and bypass tests pass | **BLOCKED** | Existing CI passes existing suites; no new required fault cases were added/run. |
| Exact baseline SHA tested in isolation | **BLOCKED** | CI checked out merge commit `c24e28d...`, not literal baseline SHA. |
| Current branch CI successful | **PASS for observed merge checkout** | All four latest workflows succeeded on merge checkout `bc8d2a8...`. |
| No unresolved P1 blockers | **BLOCKED** | P1-1 and P1-2 remain open. |
| No real orders/Testnet trading/strategy changes | **PASS** | None performed. |

## 5. Next required steps

1. Use a network-enabled runner or repository checkout to implement the cancelReplace journal, atomic persistence, two-leg exchange reconciliation and fail-closed lock semantics.
2. Persist OCO client/list identity in its durable intent before submission.
3. Add and run offline tests for timeout/lost response, HTTP 409 result combinations, partial fills/late fills, persistence faults, crash/restart, idempotent repeated recovery and no blind resubmission.
4. Add direct-mutator and adapter/dynamic-dispatch negative tests; review all 123 files semantically and finish the source-to-sink call graph.
5. Run a dedicated CI job that checks out the literal baseline SHA and asserts `git rev-parse HEAD`; separately test the final work-branch commit and record its actual checkout SHA.
6. Reassess acceptance only after test evidence closes both P1s. Keep PR #51 Draft/open and automated Testnet execution disabled until then.

**Final decision: BLOCKED — no production fix was safely implemented or verified in this environment. Do not merge PR #51.**
