# TASK 000.4 — Python Execution Safety Audit and Recovery

**Disposition: BLOCKED — acceptance criteria not met.**  
**Required immutable audit source:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`  
**PR:** #51, branch `audit/williams-2.1-task-009-integration`, keep open as Draft.  
**Safety:** No exchange requests, real orders, Testnet trading, or trading-strategy changes were performed for this audit. No production Python code was changed in this continuation.

## 1. Scope and evidence integrity

The immutable Git tree at the required SHA contains **123 Python files**. A path/blob inventory is recorded in [TASK_000_4_PYTHON_FILE_INVENTORY.csv](TASK_000_4_PYTHON_FILE_INVENTORY.csv). Each row records the path and immutable blob SHA. The inventory explicitly distinguishes targeted review from files not reviewed line by line; it is not represented as a completed 123-file semantic review.

Targeted source inspection at the required SHA included:
- `binance_client.py` (blob `774b17505729b5b9f10d4f4c961e9defc046468e`)
- `campaign_execution.py` (blob `f50a9e3c6953d763ab5017d990af318f191db60b`)
- `execution_barrier.py` (blob `85446cfc23002cfa22e79edf987b9f6cd8c4021a`)
- `mutation_registry_gate.py` (blob `b77cf968105579a01400f9490cf97bd4f9f14045`)
- `test_mutation_registry_gate.py` (blob `f9bc732b78a824f2ce0403d20eaf2c526ce9e362`)
- `test_task0094_mutation_containment.py` (blob `7a80cf6dbd1f7c491c24b91297695fed676eef92`)
- `trader.py`, `portfolio_trader.py`, and selected execution/recovery tests.

**Limitation:** this is targeted source inspection, not full line-by-line review of all 123 files or a complete whole-program call graph. Alias propagation across modules, callbacks, reflection, dynamically assigned methods, imported adapters, and all reachable production entry points remains unproven.

## 2. Mutation sinks identified in the immutable source

| Method | Endpoint | Known wrapper | Barrier/reachability conclusion |
|---|---|---|---|
| POST | `/api/v3/order` | `BinanceSpotClient.order` / `order_safe` | Canonical campaign execution submits through `CampaignExecutionService._submit` and `ExecutionBarrier.execute`; legacy paths have containment tests. Global reachability proof incomplete. |
| POST | `/api/v3/order/cancelReplace` | `BinanceSpotClient.cancel_replace` | Structural-stop replacement enters `_submit`; two-leg durable recovery is not proven. |
| POST | `/api/v3/orderList/oco` | `create_oco_sell` / `create_oco_sell_safe` | Known legacy Trader/portfolio routes include production-client guards. Full alias/dynamic caller analysis is incomplete. |
| DELETE | `/api/v3/order` | `cancel_order` | Canonical campaign cancel path uses `_execute_cancel` → `_submit`; global reachability proof incomplete. |
| DELETE | `/api/v3/openOrders` | `cancel_open_orders` | Wrapper exists; direct production caller was not established by targeted review. Must not be treated as unused without complete analysis. |
| DELETE | `/api/v3/orderList` | `cancel_oco` | Targeted review found legacy/non-canonical paths; full dynamic caller graph remains unresolved. |
| Transport | `BinanceSpotClient._request` | `requests.Session.request` | Single registered low-level transport in the bounded registry model. A registered transport sink is not itself proof that every public wrapper is barrier-protected. |

The static gate explicitly describes itself as a **bounded static analysis** and returns `PASS WITH BOUNDED MODEL`. Its AST checks reject selected raw HTTP primitives, some aliases, nonliteral/dynamic `_request` calls, and unregistered endpoints. They do not constitute an interprocedural proof of all possible Python dispatch and data-flow paths. Existing negative tests exercise representative synthetic bypasses, not every real production caller or all reflection/callback forms.

## 3. P1-1 — cancelReplace recovery is insufficiently proven

### Source facts

At the required SHA, `CampaignExecutionService.replace_structural_stop` creates a new client order ID and a normal `OrderIntent`, then calls `_submit` with a closure invoking `BinanceSpotClient.cancel_replace`. The old order ID is passed to the exchange request but is not visibly stored as a second leg in that intent. The result parser separately reads `cancelResult` and `newOrderResult`. The campaign is marked `RECONCILE_REQUIRED` for non-success/ambiguous combinations.

The Binance client uses `cancelReplaceMode=STOP_ON_FAILURE`. Its low-level transport treats HTTP 409 as an ambiguous partial cancelReplace result and does not blindly retry POST/DELETE on transport/5xx failures. These are useful safety controls, but they do not prove complete restart recovery for both legs.

### Unresolved safety gap

Durable, explicit linkage and recovery evidence for **both** the original order and replacement order is not demonstrated for timeout/lost response, HTTP 409/partial outcome, process restart, and persistence failure. In particular, recovery must independently reconcile:
- original order by its exchange order ID and/or original client order ID;
- replacement order by the persisted replacement client order ID and exchange order ID if known;
- fills/trades for both legs, including late fills and partial fills;
- whether protection currently exists, with the execution scope remaining locked until durable reconciliation completes.

No code change is claimed as a fix, and no recovery test has been run for a newly implemented two-leg cancelReplace protocol. **P1-1 remains open.**

### Required implementation and tests before PASS

Persist a dedicated cancelReplace operation record before sending, containing operation ID, symbol, account/execution scope, original order ID and client order ID if available, replacement client order ID, campaign/protection identity, and operation state. Persist the raw exchange response atomically with both leg outcomes. On any unknown result, restart recovery must query both legs and relevant trade history; never resubmit blindly. Keep execution readiness locked if either leg or fills cannot be reconciled or evidence cannot be persisted.

Add offline/fault-injection tests for: timeout after exchange acceptance; connection loss before/after response; HTTP 409 with each partial result combination; old leg canceled while new leg rejected; new leg accepted while old-leg status is unclear; partial/late fills on either leg; database write failure; crash at each persistence boundary; restart recovery; repeated recovery idempotency; and assertion that no second cancelReplace is sent while unknown.

## 4. P1-2 — ExecutionBarrier reachability is not proven repository-wide

The canonical entry/stop/exit/cancel routes inspected use `CampaignExecutionService._submit` and `ExecutionBarrier.execute`. Existing legacy containment tests assert that selected legacy BUY, OCO, emergency SELL, and fallback entry routes block a production-client stub before network I/O. Existing registry tests reject representative raw HTTP and dynamic sink examples.

However:
- `cancel_open_orders` has a mutation wrapper but no caller conclusion is justified from the targeted inspection alone.
- OCO creation/cancellation includes dynamic method selection (for example, `getattr`-based selection in portfolio code); the complete production caller graph is not established.
- Static checks do not fully resolve aliases across modules, callback registration/invocation, monkey-patching, descriptor access, reflection, dependency-injected adapters, or indirect references to bound mutation methods.
- A method can be called directly on the client without entering `ExecutionBarrier`; the existence of a wrapper or a passing endpoint registry is not an enforcement mechanism on every call.

**P1-2 remains open.** A complete caller graph and negative tests against actual production entry points are still required. Where static proof is impractical, place a fail-closed authorization token/capability at the low-level mutation transport so order-changing calls are rejected unless issued by the admitted barrier path; ensure no public caller can manufacture or bypass that capability. Test direct calls to every public mutator, alternate adapters, alias/callback/dynamic dispatch, and legacy routes without making network calls.

## 5. TASK 000.4.4 — revision-bound test evidence

Required source SHA: `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`.

- The source tree and selected file blobs were retrieved by immutable SHA through GitHub.
- **Tests were not executed locally** in this environment.
- The previously observed PR CI jobs checked out a PR merge commit, not the required source SHA; those results must not be represented as exact-source test evidence.
- No isolated successful test run on the exact required source SHA has been verified in this audit.
- The latest documentation-only commit on the audit branch must have its own CI checked and its checkout SHA recorded separately. CI on that branch does not replace exact-source evidence.

To satisfy acceptance, run the relevant test suite on a runner checking out the literal required SHA and archive logs proving `git rev-parse HEAD` equals that SHA. Separately run CI on the final work-branch HEAD and record its exact checkout/merge SHA, jobs, outcomes, and logs. Do not enable automated Testnet trading or use real funds for these checks.

## 6. Acceptance matrix

| Criterion | Status | Evidence / blocker |
|---|---|---|
| All 123 production paths reviewed and documented | **BLOCKED** | Complete 123-file path/blob inventory exists; only targeted subset reviewed. |
| Every order mutation passes mandatory barrier or has verified exception | **BLOCKED** | Canonical paths identified; whole-program reachability and dynamic callers unresolved. |
| cancelReplace recovery covers both legs across failures and restart | **BLOCKED** | Old order identity is not visibly persisted as a second leg; no new recovery implementation/test. |
| Negative tests prove bypass blocking | **PARTIAL** | Representative static-gate and legacy-containment tests exist; not exhaustive and not rerun here. |
| Tests tied to exact required SHA | **BLOCKED** | No verified test execution on literal `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`. |
| Final work-branch CI green with exact SHA recorded | **PENDING** | Must check CI for final documentation commit and distinguish merge checkout from head checkout. |
| No known unresolved P1 blockers | **BLOCKED** | P1-1 and P1-2 remain open. |

## 7. Decision

**BLOCKED — DO NOT MERGE PR #51.**

This audit adds evidence and an immutable file inventory only. It does not claim a complete 123-file review, does not claim the cancelReplace recovery defect has been fixed, and does not claim exact-source tests passed. Continue with offline implementation/fault-injection tests and complete call-graph review before reconsidering PASS.


## TASK 000.5 follow-up — 2026-10-11

The follow-up fetched and keyword/source-pattern scanned all 123 Python files at the required immutable SHA. This increases scan coverage but does **not** convert the inventory into a full line-by-line semantic review: 112 files remain not reviewed line by line and 11 remain targeted-review-only.

The scan confirmed an additional recovery-identity gap in the baseline OCO path: `trader.py` creates the durable `OrderIntent` before creating the separate OCO client/list CID; the CID is used only inside the submit closure. It also confirmed dynamic legacy adapter dispatch around `portfolio_trader.py` OCO creation/cancellation and the public `cancel_open_orders` wrapper. No whole-program proof is claimed.

Latest PR CI passed on merge checkout `bc8d2a88da81dece541cb36bb2cd00646ae21150` (branch HEAD `d0c3a4fe71025f69e3356fa0d397b5544898d240` merged into base `04da5f9c09572e99ff310be3a1908179165e528c`): backend 148 passed, campaign 208 passed, mutation registry 14 passed, and Android checks/build passed. Baseline-associated PR runs checked out merge commit `c24e28defc38e19baf20c777a069e80b87c7aecf`, not the literal required baseline SHA. No isolated exact-SHA test is proven and no local tests were run.

See `TASK_000_5_EXECUTION_SAFETY_REMEDIATION.md` for source evidence, CI links, confirmed defects, limitations and next steps. P1-1 and P1-2 remain open. **BLOCKED — DO NOT MERGE PR #51.**
