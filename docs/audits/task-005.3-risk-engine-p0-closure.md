# WILLIAMS BOT 2.1 — TASK 005.3 P0 Closure Report

**Status: IN PROGRESS — NOT APPROVED**  
**Branch:** `audit/williams-2.1-task-005-risk-management`  
**PR:** [#50](https://github.com/19wolfalone92/Williams/pull/50) — open, draft, not merged  
**Baseline main SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**HEAD at report preparation:** `0c116255d6b49330c0181eb1f7db7533e6cf5cb3`  
**Trading safety:** No Mainnet use and no real orders were performed.

## 1. Repository state

Verified via GitHub API before this report:
- branch HEAD: `0c116255d6b49330c0181eb1f7db7533e6cf5cb3`;
- `main`: `04da5f9c09572e99ff310be3a1908179165e528c`;
- PR #50 targets `main`, remains open/draft and is not merged.

## 2. Initial failing CI — exact evidence on SHA `39ef2669cd737d8b4c5ee68b9a15834e9bbf97ee`

| Run | Job | Result | Evidence / cause |
|---|---|---|---|
| [38089367620](https://github.com/19wolfalone92/Williams/actions/runs/38089367620) | Python tests | FAILURE | `4 failed, 164 passed, 4 warnings`. Three lifecycle tests and one recovery test failed. The new fee contract rejected order-level responses without authoritative `myTrades`; offline fake exchanges did not provide trade history. Recovery consequently did not materialize a partial entry. |
| [38089367620](https://github.com/19wolfalone92/Williams/actions/runs/38089367620) | Android build and static checks | FAILURE | Unit-test step failed: `ExecutionAccumulatorTestKt > initializationError`; 28 tests completed, 1 failed. The source placed several `@Test` functions outside the test class due to a premature closing brace. |
| [38089367639](https://github.com/19wolfalone92/Williams/actions/runs/38089367639) | Build APKs | FAILURE | Release contract gate, Python-source validation and Android lint passed. Android unit tests failed with the same `ExecutionAccumulatorTestKt > initializationError`; debug/release APK build and artifact verification were skipped because the test step failed. |
| [38089367688](https://github.com/19wolfalone92/Williams/actions/runs/38089367688) | backend-test | FAILURE | `4 failed, 147 passed, 4 warnings`, same four Python test names and same missing-authoritative-trade-history cause. Static safety audit and Python compilation passed. |

Warnings observed in Python runs: FastAPI `on_event` deprecation; GitHub Actions warned that Node.js 20 actions are being forced to Node.js 24. These warnings did not cause the failures.

## 3. Changes made on this branch

| Commit | Change | Validation status |
|---|---|---|
| `070b052de97737956612b65d8e4363edc0f4abc7` | Android accumulator test methods moved back inside the test class; third-asset fee no longer adds an exit/current-price estimate to the USDT fee total. Unknown third-asset valuation remains `feeKnown=false`. | CI for this SHA was still running/was not the final validation. |
| `0c116255d6b49330c0181eb1f7db7533e6cf5cb3` | Offline Python exchange fakes now expose explicit `my_trades` rows with explicit zero commission for deterministic simulated fills; added Android regression assertion that a supplied exit price does not value a historical third-asset commission. | CI for this SHA was running at report preparation; no pass is claimed. |

The zero commission above exists only in test fixtures as an explicit exchange-trade field. Production code must not infer zero commission from absent history or fields.

## 4. Financial and execution invariants

### Fixed or present in inspected code — not yet end-to-end accepted
- `campaign_execution.py::_account_order_fills()` requires exchange `orderId`, `my_trades`, trade IDs, quantity, price, quote quantity, commission, commission asset and fill time; missing/invalid rows raise and require reconciliation.
- Fill rows are persisted in a SQLite transaction. The `campaign_fills(symbol, trade_id)` unique index rejects duplicate exchange trade IDs. Existing duplicate historical IDs block migration with diagnostic keys rather than being silently deleted.
- Conflicting replay fields are compared against the stored fill and raise rather than silently overwrite.
- Unknown third-asset valuation in the Python campaign path raises; it is not replaced with a current-price estimate.
- Android `ExecutionAccumulator` marks missing commission asset / unconvertible third-asset fees unknown; the code change in `070b052` removes an unsafe approximate value from its USDT fee total.

### Implemented but not proven end-to-end
- Fill ledger persistence is separate from Campaign position mutation and risk/accounting updates. A single atomic transaction spanning fill insertion, net quantity, Campaign position, commission/PnL and reservation release has **not** been demonstrated.
- Recovery may discover fills through history, but exactly-once financial effect across process death at every boundary has not been proven.
- Protective order quantity after partial fills and fee uncertainty needs additional race/fault-injection evidence.
- Third-asset fee valuation uses a historical candle convention in Python; this is a valuation convention, not an exact intraminute execution price. Whether that convention is approved for final PnL/risk remains a contract decision. Failure to retrieve the historical price blocks accounting.
- Python and Android daily-equity baseline semantics have not been demonstrated equivalent or complete.

## 5. Remaining P0 blockers

| Area | Status | Blocker / required evidence |
|---|---|---|
| CI on final SHA | UNKNOWN / IN PROGRESS | Must obtain completed results for backend tests, Campaign CI, Android unit tests, lint and debug APK on the exact final SHA after the last edit. |
| Fee accounting in all paths | IN PROGRESS | Audit every `fee_quote = 0.0`, `_executed_fee_quote()`, `_account_order_fills()`, cancellation/recovery and Android caller. Prove absent fee data always remains UNKNOWN and cannot release risk. |
| Exactly-once financial effect | NOT VERIFIED | Fault injection after fill insert, before/after commit, after Campaign update and after restart; demonstrate no duplicate quantity/fee/PnL. |
| Atomic risk reservation + intent | NOT VERIFIED | Inject failures at reservation, intent, commit, post-commit readback, permit and exchange response. Verify SQLite state, risk hold and number of exchange mutations. |
| Inter-process concurrency | NOT RUN / NOT VERIFIED | Two independent processes sharing the same SQLite file for same-fill ingestion, competing risk reservation, campaign recovery and same-operation submission. |
| Daily equity breaker | NOT VERIFIED | Python and Android UTC-boundary, restart, stale/missing/corrupt balance, failed persistence, clock changes and concurrent initialization. |
| Binance Spot filters | NOT VERIFIED | Full controlled matrix for `LOT_SIZE`, `MARKET_LOT_SIZE`, `PRICE_FILTER`, `NOTIONAL`/`MIN_NOTIONAL`, missing/malformed filters, rounding-to-zero, and risk after rounding. |
| Spot LONG / reduction | PARTIAL / NOT VERIFIED | Full entry, fee-adjusted inventory, partial sell, protection, lost response and restart reconciliation are not all proven. |
| Futures LONG / SHORT | UNKNOWN / NOT VERIFIED | No evidence here of a separate Futures Demo/Testnet end-to-end contract. Spot SELL is not SHORT. |
| Risk limits | Must remain fixed | Per-entry ≤ 0.25%, aggregate ≤ 0.60%, daily loss ≤ 1.00%, max one active campaign, no averaging down. No increase or relaxation permitted. |

## 6. Explicit NOT RUN / UNKNOWN

- No real exchange order or Binance Mainnet test was run.
- No full two-process SQLite stress/fault-injection matrix has been confirmed.
- No completed final-SHA CI result was available when this report was prepared.
- No claim is made that Futures SHORT is supported.
- No claim is made that the app is ready for autonomous live trading.

## 7. Acceptance decision

**IN PROGRESS — NOT APPROVED.** The initial CI failures have concrete diagnoses and corrective changes have been pushed to the existing branch, but the latest SHA's workflows must finish, and the financial, recovery, concurrency, filter and daily-equity P0 evidence remains incomplete. Do not merge PR #50 or enable live trading based on this report.


---

# TASK 005.5 Addendum — Emergency SELL Recovery / Exact-SHA CI

**Decision: BLOCKED — NOT ACCEPTED**  
**Branch:** `audit/williams-2.1-task-005-risk-management`  
**PR:** [#50](https://github.com/19wolfalone92/Williams/pull/50), open, Draft, not merged  
**Base SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Source HEAD before this addendum:** `e035d52d7f2c7e7aa1e2b65845c10ff70fdfcdb7`  
**Safety:** no Mainnet use and no real trading operations.

## A. GitHub state and CI SHA mapping

The run metadata from GitHub's Actions API records PR head `e7afee404920abef6891a081e7b323df208569d7` and base `04da5f9c09572e99ff310be3a1908179165e528c` for runs 38115006581 and 38115006603. The Python job checkout log explicitly fetches and checks out `be82e7467a27f0797d0f312c60a4da7170a975cf` as `refs/remotes/pull/50/merge`, with the message “Merge e7afee... into 04da5f...”. Thus those PR-triggered jobs tested the synthetic merge tree `be82...`, not the head commit alone. The string `c3d12f3313a95f25162a1503be624f3b0617f8c0` appears in the log under “Runner Image Provisioner” as the GitHub-hosted runner image build commit, not as the repository checkout SHA. This resolves the apparent `c3d12` discrepancy; it must not be recorded as source SHA. The Campaign CI Python job, Campaign CI Android job, and Build Android APK job logs have all been checked: each explicitly fetched and checked out merge ref `be82e7467a27f0797d0f312c60a4da7170a975cf`.

| Workflow | Run | Job | PR head / tested checkout | Result | Evidence |
|---|---:|---|---|---|---|
| Python backend tests | [38115006581](https://github.com/19wolfalone92/Williams/actions/runs/38115006581) | backend-test, job 114398048523 | head `e7afee...`; checkout merge `be82e7467a27f0797d0f312c60a4da7170a975cf` | FAILURE; 1 failed, 150 passed | [full job log](https://github.com/19wolfalone92/Williams/actions/runs/38115006581) |
| Williams Campaign CI | [38115006603](https://github.com/19wolfalone92/Williams/actions/runs/38115006603) | Python tests, job 114398092299 | head `e7afee...`; checkout merge `be82e7467a27f0797d0f312c60a4da7170a975cf` | FAILURE; 1 failed, 167 passed | [full job log](https://github.com/19wolfalone92/Williams/actions/runs/38115006603) |
| Williams Campaign CI | [38115006603](https://github.com/19wolfalone92/Williams/actions/runs/38115006603) | Android build and static checks, job 114398092238 | head `e7afee...`; checkout merge `be82e7467a27f0797d0f312c60a4da7170a975cf` | SUCCESS | job steps show unit tests, lint and debug APK completed |
| Build Android APK | [38115006580](https://github.com/19wolfalone92/Williams/actions/runs/38115006580) | Build APKs, job 114398048576 | head `e7afee...`; checkout merge `be82e7467a27f0797d0f312c60a4da7170a975cf` | SUCCESS | job steps show Android unit tests, lint, debug/release APK and artifact checks completed |

These are historical results for the `e7afee...` PR event, with the backend job demonstrably testing merge tree `be82...`; they are not evidence for the newer commits in TASK 005.5. All three run logs now explicitly show checkout of merge ref `be82e7467a27f0797d0f312c60a4da7170a975cf`. Exact-SHA CI on the eventual final HEAD remains **NOT VERIFIED**.

## B. Python failure — established root cause for the currently failing test

The two latest Python workflows fail at `test_multi_position_lifecycle.py::test_partial_emergency_sell_never_closes_trade_as_flat`, with:

`RuntimeError: BTCUSDT: executed order emergency-1 has no authoritative trade rows; fee status UNKNOWN`

Traceback reaches `portfolio_trader.py::_emergency_market_sell` → `_authoritative_execution` → line 684. The production guard is intentional: order-level `executedQty` is not sufficient to infer commission-adjusted inventory. The previous fixture returned an executed order but did not provide matching authoritative `myTrades` rows; it also reported 0.4 BTC free in account while claiming a 0.6 BTC SELL execution against a 1.0 BTC managed position. The fixture therefore did not model a coherent exchange state.

The existing test was changed to model a terminal partial order: requested/original quantity 1.0, status `EXPIRED`, authoritative executed quantity 0.6, quote quantity 60, and an explicit trade-history row with explicit commission amount and asset. This does not substitute synthetic fills into production: the row is a controlled fixture representing the exchange response contract. The production path now accepts terminal `FILLED`, `CANCELED`, `EXPIRED`, and `REJECTED` states for reconciliation, but rejects non-terminal/ambiguous states into `RECONCILE_REQUIRED` without resubmission. Partial residual quantity subtracts a known base-asset commission from managed inventory; unknown or invalid commission remains a hard failure.

Changes committed on the existing branch:
- `86710ec33cc6db13a8c788f7e4705b835d6fedf9` — production terminal partial emergency-SELL handling and net inventory debit.
- `e035d52d7f2c7e7aa1e2b65845c10ff70fdfcdb7` — regression fixture with terminal partial execution and authoritative trade history.

**Validation status:** the patch has not yet been shown passing on the final SHA. Other previously reported Python failures (filled pending BUY recovery, partial manual SELL protection, terminal partial entry materialization) were not shown as failing in the latest 150/167-test runs; their full independent state-transition/root-cause proofs remain **NOT VERIFIED**.

## C. Test-count discrepancy

The differing counts are explained at least in part by workflow test selection, not by assuming environment-only variance:
- `.github/workflows/python-ci.yml` uses Python 3.13 and runs the listed backend suite without `test_campaign_engine.py` and `test_campaign_execution.py`.
- `.github/workflows/campaign-ci.yml` uses Python 3.12 and adds `test_campaign_engine.py` and `test_campaign_execution.py`.
- Latest outcomes are 150 passed versus 167 passed (17 additional passing tests in Campaign CI); older outcomes were 147 versus 164 (also a difference of 17).

This matches the workflow command difference, but the exact collected test-ID diff and the individual count from those two added files have not yet been retrieved from pytest collection output. Therefore the detailed 17-test attribution is **PARTIALLY VERIFIED**, not fully closed. The workflows currently do not share one identical test command or Python version.

## D. Android initializationError

Historical runs 38089367620 and 38089367639 failed at `:app:testDebugUnitTest`; the artifact `williams-android-unit-test-diagnostics-1867` (artifact ID `11683916451`) contains the XML report for `ExecutionAccumulatorTestKt`. Its complete JUnit cause is `org.junit.runners.model.InvalidTestClassError`: the generated Kotlin file class had a public constructor and five `@Test` methods emitted as static methods (`invalidFillDoesNotIncreasePositionAccounting`, `missingCommissionAssetMarksAccountingUnknownInsteadOfZeroFee`, `sameOrderIdAcrossSymbolsDoesNotMergeFills`, `thirdAssetCommissionWithoutHistoricalConversionRemainsUnknown`, `duplicateTradeIdWithDifferentEventTimeIsStillOneFill`). The source-level cause was those test functions being declared at file scope rather than inside the JUnit test class after a premature class-closing brace. This establishes the historical initializationError root cause; the correction was committed earlier in `070b052de97737956612b65d8e4363edc0f4abc7`. On merge tree `be82...`, Campaign CI Android tests/lint/debug APK and Build APK unit tests plus APK packaging passed. Those historical successes do not validate the newer TASK 005.5 commits.

## E. P0 gaps still open

| Requirement | Status | Why not accepted |
|---|---|---|
| Exact final-SHA CI | BLOCKED | No completed required workflow results have yet been established for final report commit SHA. |
| Emergency SELL after lost response | NOT VERIFIED | Current path still needs proof of durable intent before network mutation and order lookup by stable client order ID after a timeout/process death. |
| Atomic fill/fee/net-quantity/PnL/position/campaign/risk update | NOT VERIFIED | No single transaction or documented replayable durable protocol has been demonstrated across all financial effects. |
| Exactly-once cross-process accounting | NOT VERIFIED | No independent OS-process test with shared DB and concurrent duplicate fill recovery has been demonstrated. |
| Unknown fee and base-asset fee | PARTIAL | Guard remains fail-closed and residual now subtracts known base commission; end-to-end ledger/restart proof remains absent. |
| Crash/fault injection matrix | NOT RUN / NOT VERIFIED | Required kill points and recovered-state invariants have not all been exercised. |
| Daily capital baseline and daily loss | NOT VERIFIED | Restart, UTC boundary, persistence failure, stale equity and cross-process initialization matrix is incomplete. |
| Binance filter boundary matrix | NOT VERIFIED | All filter combinations and before/on/after threshold cases have not been shown passing. |
| Four Python lifecycle failures | PARTIAL | The current emergency SELL fixture failure has a targeted correction; independent regression proofs for the other historical failures are not all attached. |
| Android historical initializationError root cause | NOT VERIFIED | Historical test XML/inner trace not recovered from console log. |

## F. Decision

**TASK 005.5 = BLOCKED — NOT ACCEPTED.** The emergency SELL regression fixture and production terminal-partial handling were changed on the existing TASK 005 branch, but the final SHA still requires fresh exact-SHA CI and the financial-ledger, lost-response, atomicity, cross-process, fault-injection, daily-capital and Binance-filter evidence above. PR #50 remains open/Draft; do not merge, do not modify `main`, and do not enable real trading based on this report.


---

# TASK 005.6 Addendum — Durable Emergency SELL / Source-to-Sink Re-audit

**Decision: BLOCKED — NOT ACCEPTED**  
**Audit time:** 2026-10-11  
**Branch:** `audit/williams-2.1-task-005-risk-management`  
**PR:** #50 — open, Draft, not merged  
**PR head audited:** `78c705b417d2d96442adfaac5a7b8008c1b6d892`  
**Base SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**No Mainnet or real orders used.**

## 1. Repository and PR integrity — CODE-CONFIRMED

- GitHub reports the branch exists and PR #50 points from `audit/williams-2.1-task-005-risk-management` to `main`.
- PR #50 is `open`, `draft=true`, `merged=false`; its base SHA remains `04da5f9c09572e99ff310be3a1908179165e528c`.
- PR head at the time of this audit is `78c705b417d2d96442adfaac5a7b8008c1b6d892`.
- The required CI workflows completed successfully for the PR merge ref `5f1e9cabbedaf5e950b955a3af38a9169a1a17ab`, created by merging that head with the stated base. These runs validate the tested merge tree, not the head commit in isolation.
- No branch change, merge, or live trading operation was performed during this audit.

## 2. Emergency SELL source-to-sink findings

| Source / sink | Evidence | Classification |
|---|---|---|
| Emergency SELL entry | `portfolio_trader.py::_emergency_market_sell(symbol, qty, trade_id, reason)`; reached from the OCO creation path when the market is already beyond the trigger. | CODE-CONFIRMED |
| Quantity source | Reads account, calls `_asset_balance()`, then `_normalize_qty(min(float(qty), free_qty), market=True)`. The path uses float conversions and does not prove all applicable market filters / notional / reserved-order constraints are validated before send. | CODE-CONFIRMED |
| Client order identity | Creates `WILLV4_EMERGENCY_<random uuid fragment>` inside each invocation. Re-entry after timeout/restart can generate a different client ID for the same trade/reason. | CODE-CONFIRMED — P0 BLOCKER |
| Durable intent before send | No emergency-specific intent record is written before `client.order_safe(... SELL ...)`. The first visible durable order persistence is `db.save_order(sell)`, after the network mutation returns. | CODE-CONFIRMED — P0 BLOCKER |
| Submission state | No durable `SUBMITTING` transition is committed for this emergency SELL before the external request. | CODE-CONFIRMED — P0 BLOCKER |
| Ambiguous submission | If the call raises after the exchange accepted the order, the function can exit before saving the returned order or marking the symbol `RECONCILE_REQUIRED`. No emergency-specific recovery lookup by a previously persisted stable client ID is established in this path. | CODE-CONFIRMED — P0 BLOCKER |
| Order confirmation | On a returned response, it calls `get_order`; non-terminal status is marked `RECONCILE_REQUIRED` and does not immediately retry. This is a useful guard but cannot repair the missing pre-submit intent / stable identity. | CODE-CONFIRMED |
| Fill / commission evidence | `_authoritative_execution()` requires trade rows and explicit commission amount/asset when executed quantity is positive; missing rows or commission raises and remains unknown. Third-asset conversion calls a live ticker-based valuation path, not a proven historical fill-time valuation. | CODE-CONFIRMED; accounting convention NOT VERIFIED |
| Financial sink | The emergency function converts the execution summary to float, then calls `close_trade()` or `update_trade_quantity()` and updates position state. Those writes are not shown inside one explicit transaction spanning fill ledger, fees, PnL, Campaign, and risk reservation. | CODE-CONFIRMED — P0 BLOCKER |
| Durable schema | `db.py` has generic `execution_intents` and `execution_events`, but the emergency path does not use them before submission. The existing intent schema also does not itself represent the full emergency contract (safe quantity/filter snapshot, trade/Campaign linkage, unknown-result state, canonical fill/accounting status). | CODE-CONFIRMED |
| Decimal integrity | Emergency SELL uses `float(qty)`, `float(free_qty)`, `float(execution.executed_qty)`, and float-based residual/PnL updates. SQLite `trades.quantity`, `pnl`, `fees` and `campaign_fills` projections include REAL columns. | CODE-CONFIRMED — P0 BLOCKER |

### Root cause

The emergency SELL path currently performs the external mutation before durable, stable intent identity exists. A lost response can therefore leave no durable record from which recovery can safely distinguish “not submitted” from “accepted by Binance but response lost.” A subsequent invocation generates a new random `clientOrderId`, so it can create an independent SELL. This directly violates the task’s no-blind-retry and durable-intent requirements.

The post-response `save_order()`, terminal-status handling, and authoritative `myTrades` checks do not close this crash window. Do not treat them as proof of exactly-once execution or accounting.

## 3. Required next implementation steps

1. Add a durable emergency-intent contract with a stable identity derived from the managed trade/Campaign and exit episode, and persist it transactionally before any external mutation.
2. Persist the exact Decimal sell quantity, symbol/account, applicable filter snapshot/version, reason, contract version, and stable `clientOrderId`; verify the committed row before submission.
3. Persist `SUBMITTING` before the request. On any timeout, process death, malformed response, or lost outcome, transition to durable `RECOVERY_REQUIRED`; never mint a new client ID for the same unresolved intent.
4. Recover by querying the exchange with the stable client order ID and authoritative order/trade history. If the result cannot be resolved, keep entries blocked.
5. Route fills through a canonical unique fill ledger and atomically apply all local projections, or use a documented durable idempotent protocol if they span stores. Unknown fee must remain unknown.
6. Remove float/SQLite REAL from canonical emergency quantity, fee, and financial-effect calculations; migrate safely without silently rewriting historical values.
7. Recompute residual exposure from authoritative account balances, open-order reservations, fills, and base-asset commission; validate protection before clearing recovery state.
8. Add independent-process and crash/fault-injection tests at every specified boundary, then rerun all required workflows and capture the exact pytest test-ID difference.

These steps are not claimed implemented by this report update.

## 4. Exact-SHA CI observed during this audit

All three required workflows completed with conclusion `success` for PR event head `78c705b417d2d96442adfaac5a7b8008c1b6d892`, checkout merge SHA `5f1e9cabbedaf5e950b955a3af38a9169a1a17ab`, base `04da5f9c09572e99ff310be3a1908179165e528c`.

| Workflow | Run ID | Job(s) | Result | Checkout evidence |
|---|---:|---|---|---|
| Build Android APK | 38117480066 | Build APKs (job 114405165745) | CI-PROVEN: SUCCESS | Log says `HEAD is now at 5f1e9ca Merge 78c705b... into 04da5f...`; Android unit tests, lint, debug/release APK build and artifact checks completed. |
| Python backend tests | 38117480034 | backend-test (job 114405166254) | CI-PROVEN: SUCCESS | Log says checkout merge SHA `5f1e9cabbedaf5e950b955a3af38a9169a1a17ab`. |
| Williams Campaign CI | 38117480040 | Python tests (job 114405205693); Android build and static checks (job 114405205800) | CI-PROVEN: SUCCESS | Both logs say checkout merge SHA `5f1e9cabbedaf5e950b955a3af38a9169a1a17ab`; Campaign Python suite reported `168 passed, 4 warnings`; Android tests, lint, debug APK completed. |

Important scope: these successful runs are evidence for the PR merge ref, not proof that the branch head alone was checked out. They do not prove durable emergency intent, lost-response recovery, exactly-once accounting, multi-process safety, the full fault-injection matrix, Android physical process-death, or the exact pytest test-ID diff. CI success cannot override the code-confirmed P0 defects above.

## 5. Acceptance matrix

| Criterion | Status | Evidence / blocker |
|---|---|---|
| P0-01 durable intent before SELL | BLOCKED | No emergency intent persisted before `order_safe()`. |
| P0-02 stable `clientOrderId` | BLOCKED | Random ID is regenerated on each invocation. |
| P0-03 no blind retry after lost response | BLOCKED | No pre-submit stable identity exists to safely reconcile the lost-response window. |
| P0-04 authoritative order reconciliation | PARTIAL | `get_order` and `myTrades` checks exist after a response; timeout/process-death recovery not proven. |
| P0-05 exactly-once fills | NOT VERIFIED | No end-to-end emergency fill-ledger proof. |
| P0-06 exactly-once Position/Campaign/PnL | BLOCKED | Float-based trade mutations; no demonstrated atomic financial-effect boundary. |
| P0-07 fee integrity | PARTIAL | Missing fee data is rejected; full late-fee/explicit-zero/base/quote/third-asset recovery remains unproven. |
| P0-08 residual position/protection | BLOCKED | Partial residual is written locally, but authoritative residual and protection revalidation are not proven end-to-end. |
| P0-09 atomic financial effect | NOT VERIFIED | No transaction/protocol spanning all projections demonstrated. |
| P0-10 independent-process idempotency | NOT VERIFIED | No confirmed independent OS-process proof for the same emergency intent/fill. |
| P0-11 full crash consistency | NOT VERIFIED | Required crash points not all exercised. |
| P0-12 daily capital baseline | NOT VERIFIED | Restart/date-boundary/corruption/concurrency evidence incomplete. |
| P0-13 Decimal integrity | BLOCKED | Float and SQLite REAL remain in canonical emergency/financial path. |
| P0-14 Android runtime mutation paths | NOT VERIFIED | No full mutation-path/process-death evidence. |
| P0-15 Android tests/lint/APK | CI-PROVEN for merge ref | Successful workflow, not physical-device verification. |
| P0-16 all required CI | CI-PROVEN for merge ref | All three listed workflows succeeded on the same merge ref. |
| P0-17 exact pytest test-ID diff | NOT VERIFIED | Collection outputs were not compared as sets. |
| P0-18 Git/PR integrity | CODE-CONFIRMED | Branch/PR/base verified; PR remains Draft, main unchanged. |

## 6. Final decision

**TASK 005.6 = BLOCKED — NOT ACCEPTED.** The central emergency SELL crash window is code-confirmed and remains unresolved. Current green CI is useful regression evidence for the tested merge tree, but cannot satisfy P0 acceptance while durable pre-submit intent, stable client-order identity, atomic exactly-once accounting, Decimal integrity, independent-process recovery, and the complete fault-injection matrix remain open.

PR #50 must remain Draft. Do not merge, do not modify `main`, and do not enable live trading based on this evidence.



---

# TASK 005.8 Addendum — Durable Emergency SELL Identity and Pre-submit Intent

**Decision: BLOCKED — NOT ACCEPTED**  
**Branch:** `audit/williams-2.1-task-005-risk-management`  
**PR:** #50 — open, Draft, not merged  
**Base SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Final code/test commit at report update:** `74910334c64362690f64f265cf5ba141b06d3322`  
**Safety:** no Binance Mainnet request and no Binance Testnet trading mutation was performed.

## 1. Fresh repository state — CODE-CONFIRMED

- PR #50 is open, Draft, not merged, base `main`, base SHA `04da5f9c09572e99ff310be3a1908179165e528c`.
- The existing branch `audit/williams-2.1-task-005-risk-management` remains the PR head branch.
- Head at report update: `74910334c64362690f64f265cf5ba141b06d3322`.
- No branch was created; PR #50 was not merged; `main` was not changed by this task.
- Historical CI runs `38119397495`, `38119397553`, and `38119397594` belong to the earlier code/report SHA `7fb3f57e261a934cd5f1aa24a8bc2c3c0337aba6`. They are **not** evidence for the new code SHA.
- A workflow lookup for the current head returned no PR-triggered runs at the time of this report. Current-head CI is therefore **NOT VERIFIED**.

## 2. Production changes made

### `db.py` — CODE-CONFIRMED

Added `emergency_sell_intents` with:
- stable `operation_id` primary key;
- identity contract version, environment and non-secret account scope fingerprint;
- symbol, trade linkage, operation type, reason and canonical quantity string;
- immutable parameter hash and unique `client_order_id`;
- lifecycle status, exchange order/status, timestamps and last error.

Added `claim_emergency_sell_intent()`:
- claims under SQLite `BEGIN IMMEDIATE` through the existing transaction context;
- uses insert-if-absent and validates all identity parameters rather than overwriting a prior row;
- reads back the row and rejects incompatible parameters or client-ID conflicts;
- returns only after the transaction commits and the persisted identity is checked.

Added `transition_emergency_sell_intent()` with a restricted lifecycle transition map. A second process cannot also transition an already-`SUBMITTING` operation to `SUBMITTING`; it fails closed.

### `portfolio_trader.py` — CODE-CONFIRMED

The emergency SELL now:
1. Requires account identity to be available; stores only a one-way hash of the API-key identifier, never the key or secret.
2. Derives a stable operation ID from contract version, environment, account scope, symbol, managed trade ID and operation type.
3. Derives a stable Binance client order ID from that operation ID and checks its length/character contract.
4. Computes a canonical decimal quantity string after existing balance/market-lot normalization.
5. Claims and commits the intent before mutation.
6. Commits `SUBMITTING` before `order_safe()`.
7. On POST exception, order-lookup failure, non-terminal exchange status or unresolved authoritative fill/fee data, latches `RECONCILE_REQUIRED`; it does not mint a new client ID or blindly retry.
8. Records response/order evidence and marks the intent `APPLIED` only after the existing local accounting statements complete.

A repeat call for the same managed trade resolves to the same operation/client ID. Any changed reason or quantity conflicts with the immutable intent rather than silently replacing it.

### `test_task005_risk_safety.py` — CODE-CONFIRMED

Added tests for:
- immutable intent and canonical quantity persistence;
- conflicting parameters being rejected;
- illegal repeated `SUBMITTING` transition being rejected;
- eight independent processes sharing one SQLite file, where only one may claim the `PREPARED → SUBMITTING` transition.

These tests were added to the branch but their execution on the current SHA has not yet been observed. They are **NOT VERIFIED**, not TEST-PROVEN.

## 3. Emergency SELL call graph and remaining barrier gap

Observed legacy path:

`_create_oco()` detects the market already crossed TP/SL → `_emergency_market_sell()` → `Database.claim_emergency_sell_intent()` → `Database.transition_emergency_sell_intent(SUBMITTING)` → `client.order_safe(... SELL MARKET ...)` → `client.get_order(... stable clientOrderId ...)` → `_authoritative_execution()` / `myTrades` evidence → `close_trade()` or `update_trade_quantity()` → intent finalization.

The separate `ExecutionBarrier` persists generic campaign intents, but this legacy emergency path does not invoke it. The shared lower-level signed mutation sink also does not yet enforce an emergency-intent permit itself. Therefore a direct or alternate reachable SELL mutation path may bypass the new intent gate. This is a **P0 blocker**.

## 4. P0 blockers still open

| Criterion | Status | Evidence / gap |
|---|---|---|
| Stable logical operation identity | CODE-CONFIRMED; TEST-PROVEN pending | Deterministic identity code added; golden/collision tests not run on current SHA. |
| Durable intent before SELL | CODE-CONFIRMED; TEST-PROVEN pending | SQLite claim + readback and committed `SUBMITTING` precede `order_safe()`; no current-SHA fault-injection proof. |
| No independent retry after UNKNOWN POST | PARTIAL | This call path blocks on an existing non-`PREPARED` intent. A complete exchange reconciliation worker for the stored operation is not demonstrated. |
| Mutation-sink Execution Barrier | BLOCKED | Legacy method now gates its own call, but direct/lower-level sink invocation is not protected by an intent-bound permit. |
| Authoritative order recovery | BLOCKED | Stable ID is persisted, but restart recovery that queries Binance by client ID and resolves all unknown states is not implemented/proven end-to-end. |
| Exactly-once financial effects | BLOCKED | Existing `close_trade()` / `update_trade_quantity()` remain outside a demonstrated atomic canonical fill/effect transaction. Crash between projection updates and `APPLIED` can leave partial local state. |
| Fill ledger and late fee correction | BLOCKED | Emergency fills are not yet proven to use the single canonical fill/effect applier with durable per-component fee state. |
| Decimal integrity | BLOCKED | Intent quantity is a decimal string, but downstream normalization and financial projections still use `float` and SQLite REAL. No migration was attempted. |
| Residual Spot exposure and reservations | BLOCKED | Authoritative free balance is read before intent, but complete open-order reservation, commission-base debit and post-fill residual reconciliation is not proven. |
| Global Recovery Gate | BLOCKED | Position state is latched on several failure paths, but every new-entry route has not been proven to consult one global unresolved-intent/fill/effect gate. |
| Eight-process concurrency | NOT VERIFIED | Test source added; no test execution evidence yet. |
| Full crash/fault matrix | NOT VERIFIED | Only code guards and initial concurrency test source added; required crash points have not all been exercised. |
| Android contract | NOT VERIFIED | No Android runtime contract changes or process-death/golden-fixture evidence in this task. |
| Current-SHA CI | NOT VERIFIED | No workflow run was returned for current head `74910334c64362690f64f265cf5ba141b06d3322`. |
| PR/main integrity | CODE-CONFIRMED | PR remains open/Draft/unmerged; work stayed on the existing branch. |

## 5. Classification discipline

- **CODE-CONFIRMED:** new schema/methods and emergency call-path changes are present in branch source.
- **TEST-PROVEN:** none claimed for the new tests; their source exists but execution result is not yet observed.
- **CI-PROVEN:** none for final SHA `74910334c64362690f64f265cf5ba141b06d3322`.
- **TESTNET-OBSERVED:** none; no real Testnet mutation was performed.
- **NOT VERIFIED:** complete recovery worker, sink enforcement, financial-effect idempotency, Decimal-only accounting, all required fault points, Android runtime equivalence, and final-SHA CI.

## 6. Minimum next steps

1. Run the new tests and all existing Python/Android required workflows against the exact current head; inspect checkout SHA and logs.
2. Extend the execution barrier down to every reachable SELL mutation sink so the sink itself verifies a committed, immutable, operation-bound permit.
3. Implement recovery for `PREPARED`, `SUBMITTING`, `SUBMITTED`, and `RECONCILE_REQUIRED` using the persisted client ID plus authoritative Binance order and trade history; unresolved state must remain blocked.
4. Route authoritative fills and fee components through the existing canonical fill ledger and one idempotent financial-effect applier; add crash tests around every projection/commit boundary.
5. Replace float/REAL as canonical values on this path only after auditing all readers/writers and specifying a safe migration; do not rewrite historical REAL values without evidence.
6. Add eight-process, lost-response, process-death, conflicting fill, unknown/late fee, balance/commission/reservation, direct-sink bypass and new-entry-gate tests.
7. Verify Android contract or explicitly retain it as an unresolved cross-platform blocker.
8. Re-run required CI on the final SHA and record exact workflow IDs, checkout SHAs and test output.

## 7. Final decision

**TASK 005.8 = BLOCKED — NOT ACCEPTED.** This iteration closes part of the original pre-submit intent gap in the legacy emergency method and adds immutable, cross-process SQLite intent claiming. It does not yet prove the full P0 contract. In particular, sink-level enforcement, authoritative restart reconciliation, exactly-once financial effects, Decimal integrity, the full fault-injection matrix, Android parity, and current-head CI remain unresolved.

PR #50 must remain Draft. Do not merge, do not modify `main`, and do not enable live trading based on this evidence.
