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

The run metadata from GitHub's Actions API records PR head `e7afee404920abef6891a081e7b323df208569d7` and base `04da5f9c09572e99ff310be3a1908179165e528c` for runs 38115006581 and 38115006603. The Python job checkout log explicitly fetches and checks out `be82e7467a27f0797d0f312c60a4da7170a975cf` as `refs/remotes/pull/50/merge`, with the message “Merge e7afee... into 04da5f...”. Thus those PR-triggered jobs tested the synthetic merge tree `be82...`, not the head commit alone. The string `c3d12f3313a95f25162a1503be624f3b0617f8c0` appears in the log under “Runner Image Provisioner” as the GitHub-hosted runner image build commit, not as the repository checkout SHA. This resolves the apparent `c3d12` discrepancy; it must not be recorded as source SHA. The Android Build APK run's exact checkout SHA still requires the same explicit log-level check.

| Workflow | Run | Job | PR head / tested checkout | Result | Evidence |
|---|---:|---|---|---|---|
| Python backend tests | [38115006581](https://github.com/19wolfalone92/Williams/actions/runs/38115006581) | backend-test, job 114398048523 | head `e7afee...`; checkout merge `be82e7467a27f0797d0f312c60a4da7170a975cf` | FAILURE; 1 failed, 150 passed | [full job log](https://github.com/19wolfalone92/Williams/actions/runs/38115006581) |
| Williams Campaign CI | [38115006603](https://github.com/19wolfalone92/Williams/actions/runs/38115006603) | Python tests, job 114398092299 | head `e7afee...`; merge checkout SHA must be confirmed from that job log | FAILURE; 1 failed, 167 passed | [full job log](https://github.com/19wolfalone92/Williams/actions/runs/38115006603) |
| Williams Campaign CI | [38115006603](https://github.com/19wolfalone92/Williams/actions/runs/38115006603) | Android build and static checks, job 114398092238 | head `e7afee...`; merge checkout SHA must be confirmed from that job log | SUCCESS | job steps show unit tests, lint and debug APK completed |
| Build Android APK | [38115006580](https://github.com/19wolfalone92/Williams/actions/runs/38115006580) | Build APKs, job 114398048576 | head `e7afee...`; checkout merge SHA NOT VERIFIED | SUCCESS | job steps show Android unit tests, lint, debug/release APK and artifact checks completed |

These are historical results for the `e7afee...` PR event, with the backend job demonstrably testing merge tree `be82...`; they are not evidence for the newer commits in TASK 005.5. Exact checkout SHAs for every other job must be read from each checkout log. Exact-SHA CI on the eventual final HEAD remains **NOT VERIFIED**.

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

Historical runs 38089367620 and 38089367639 failed at `:app:testDebugUnitTest`; the job logs show 28 tests completed, one failed, and Gradle's wrapper exception. The detailed test XML/inner stack trace is not present in the fetched console log. The previous report attributes the failure to test methods being outside the intended class, but that exact diagnosis is **NOT VERIFIED** from the available full log alone. On SHA `e7afee...`, the later Campaign CI Android job and APK workflow completed Android unit tests successfully. That later success does not prove the historical root cause, and it does not cover the new TASK 005.5 commits.

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
