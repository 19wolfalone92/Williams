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
