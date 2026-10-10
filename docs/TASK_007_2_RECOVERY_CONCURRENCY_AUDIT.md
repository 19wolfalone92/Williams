# WILLIAMS BOT 2.1 — TASK 007.2
## Recovery, Concurrency & Financial Reconciliation Audit

**Status: IN PROGRESS — NOT APPROVED**

This report is an evidence record, not a declaration of production readiness. No Binance Mainnet requests, live orders, or order-changing Testnet requests were made. Read-only Testnet access was not attempted because the required local closure gates are not met.

## 1. Repository control and exact revisions

- Repository: `19wolfalone92/Williams`
- Working branch: `audit/williams-2.1-task-007-recovery`
- Task 007.2 starting SHA: `01d8472dbadf3d903b243d618de627bd2962aed7`
- Baseline `main` SHA: `04da5f9c09572e99ff310be3a1908179165e528c`
- Latest verified implementation/report SHA before this documentation refresh: `2312582ef7e92f5e29695db7c5e0557ac0491ad2`
- Compare result for that SHA: 121 commits ahead, 0 behind baseline.
- `main` was rechecked and remains at `04da5f9c09572e99ff310be3a1908179165e528c`.
- No PR exists for this exact recovery branch. Existing PR #43 is on `fix/task-007-risk-management`, not this branch. No PR was created and no merge was performed.
- Exact SHA `2312582ef7e92f5e29695db7c5e0557ac0491ad2` passed [workflow run 38090391571](https://github.com/19wolfalone92/Williams/actions/runs/38090391571): Python **199 passed, 4 warnings**; Android `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` all completed successfully. This documentation refresh creates a new SHA and must be verified by its own run; no result is transferred from the previous SHA.

## 2. Changes made in this continuation

1. Added `Database.claim_execution_intent()`: atomically checks intent ID, client order ID and unresolved same-symbol intents, then persists PENDING inside one SQLite transaction. The operation is serialized across independent SQLite connections.
2. Updated `ExecutionBarrier.execute()` to require the atomic durable claim before any exchange mutation. A claim failure blocks submission and latches recovery-required where possible. An unresolved same-symbol intent returns a blocked result; a poisoned barrier refuses further mutations. Duplicate identity checks remain ahead of the poisoned-barrier return.
3. Added tests using two independent Database connections and two independent ExecutionBarrier/ContextCache instances. A controlled submit is held while a second runtime attempts a conflicting same-symbol entry; only the first reaches the exchange adapter.
4. Hardened fee evidence. Portfolio and legacy Trader reconciliation no longer treat an order-level response without trade history as evidence of zero commission. Missing commission amount or commission asset raises a reconciliation error. Exit fee history lookup errors and empty history no longer silently return zero fees.
5. Hardened campaign exit fill validation so a fill missing commission amount or commission asset is rejected.
6. Updated test adapters to provide explicit, deterministic fill/commission records rather than relying on implicit zero-fee fallbacks.
7. Added regression tests for missing commission evidence and the independent-runtime mutation race.
8. Added an independent-process SQLite claim race test: two OS processes open separate SQLite connections and attempt to claim the same symbol; exactly one durable claim is expected. It passed in the Python suite on SHA `7333b94263c14180efdc7713a40928af48d46277`.
9. Found and patched a further financial-integrity gap: the shared execution accumulator previously left nonzero third-asset commissions out of quote-equivalent fees, and legacy `Trader` did not account for base-asset commissions. The accumulator now reports unvalued fee assets; legacy reconciliation blocks if any remain unvalued; portfolio conversion rejects non-positive market prices.
10. Removed a swallowed failure in `CampaignExecutionService.reconcile_pending_entries()`: if an executed conditional entry has no usable cumulative quote amount, missing/unavailable/empty fill history now raises and leaves the symbol `RECONCILE_REQUIRED` instead of continuing toward protection sizing. A regression test verifies no SELL/protection mutation is issued in that condition. This passed in exact-SHA CI `66354eb53ee456a788a01cdd2293d8068b728b83`.

These changes reduce known risks but do not close the entire TASK 007.2 acceptance matrix.


## 2A. Cumulative changed-file inventory relative to baseline

The cumulative compare against baseline `04da5f9c09572e99ff310be3a1908179165e528c` lists these 23 changed files (branch diff; not all were changed in this single continuation):

- .github/workflows/campaign-ci.yml
- app/src/main/java/com/williamsbot/StandaloneRuntime.kt
- campaign_engine.py
- campaign_execution.py
- db.py
- docs/TASK_007_2_RECOVERY_CONCURRENCY_AUDIT.md
- execution_accumulator.py
- execution_barrier.py
- gradle/wrapper/gradle-wrapper.properties
- mock_exchange.py
- portfolio_trader.py
- preflight_gate.py
- recovery_oco_failure_test.py
- recovery_test.py
- test_android_mutation_ledger_contract.py
- test_campaign_execution.py
- test_integration_bindings.py
- test_multi_position_execution.py
- test_multi_position_lifecycle.py
- test_preflight_gate.py
- test_production_architecture.py
- test_task007_recovery_contract.py
- trader.py
## 3. Mutation path inventory (partial; completeness NOT VERIFIED)

| Path | Observed call chain | Low-level mutation | Barrier / durable intent | Current evidence and remaining gap |
|---|---|---|---|---|
| Legacy Spot BUY | `Trader.market_buy` → `ExecutionBarrier.execute` → `BinanceSpotClient.order` | POST `/api/v3/order` | Barrier path observed; durable PENDING claim added | Existing tests plus CI. Full static reachability inventory of every caller is not complete. |
| Legacy Spot OCO protection | `Trader.place_oco` → `ExecutionBarrier.execute` → `BinanceSpotClient.create_oco_sell` | POST `/api/v3/orderList/oco` | Barrier path observed | OCO recovery/fault matrix is incomplete. |
| Campaign entry / stop / exit | `CampaignExecutionService` → barrier execution → `order_safe` | POST `/api/v3/order` | Barrier path observed | Ambiguous response recovery is partially covered; every crash point is not tested. |
| Campaign cancel | `CampaignExecutionService` → barrier execution → `cancel_order` | DELETE `/api/v3/order` | Barrier path observed | Fill-vs-cancel races and every terminal state are not fully tested. |
| Campaign cancel/replace | `CampaignExecutionService` → barrier execution → `cancel_replace` | POST `/api/v3/order/cancelReplace` | Barrier path observed | Binance's non-transactional cancel/replace outcomes need a complete fault matrix. |
| Multi-position entry | `MultiPositionTrader` → `_execute_mutation` → barrier → `order_safe` | POST `/api/v3/order` | Barrier path observed | Concurrent symbol reservation now has a controlled two-runtime test; full process-level matrix remains open. |
| OCO creation/restoration | `MultiPositionTrader` → `_create_oco` → `_execute_mutation` → barrier → `create_oco_sell_safe` / `create_oco_sell` | POST `/api/v3/orderList/oco` | Barrier path observed | Not every unknown/open/partial/terminal/corrupt-local-state transition has been fault-injected. |
| OCO cancellation | `MultiPositionTrader` recovery/exit path → `_execute_mutation` → barrier → `cancel_oco` | DELETE `/api/v3/orderList` | Barrier path observed | Simultaneous child fill and cancel, lost response, and two-process recovery matrix incomplete. |
| Manual / emergency Spot SELL | `MultiPositionTrader` → `_execute_mutation` → barrier → `order_safe` | POST `/api/v3/order` | Barrier path observed | Partial exit and residual protection have fixture coverage; complete balance/fill/fee reconciliation remains open. |
| Binance low-level adapter | `BinanceSpotClient.order`, `cancel_order`, `cancel_open_orders`, `cancel_replace`, `create_oco_sell`, `cancel_oco` → `_request` | Signed POST/DELETE | Low-level methods themselves are not the barrier; callers must enforce it | Repository-wide proof that no production-reachable caller bypasses the barrier is NOT VERIFIED. |
| Android mutations | `signedPost/signedDelete` → `durableMutation` → encrypted SharedPreferences PENDING commit → `signedRequest` | Signed REST POST/DELETE | Static write-ahead contract observed | Static contract tests exist; process-kill/fault-injection matrix is NOT VERIFIED. |
| Recovery | `Trader.recover_state`, `MultiPositionTrader.recover`, campaign recovery | Read-only queries plus durable state writes; recovery may schedule later mutations | Recovery queries and mutation paths are separated in reviewed code | Two simultaneous recovery executors and every REST/history window are NOT VERIFIED. |

**Static sweep evidence:** a targeted regex scan covered 16 high-risk production files (including `binance_client.py`, `trader.py`, `portfolio_trader.py`, `campaign_execution.py`, `server.py`, `StandaloneRuntime.kt`, and `ExecutionGate.kt`). The raw signed POST/DELETE methods found in the Python adapter are order create, cancel, cancel-all, cancel/replace, OCO create and OCO cancel; Android signed mutations in the reviewed runtime call through `signedPost`/`signedDelete` → `durableMutation`. This is useful but is not a repository-wide scan of all 146 Python/Kotlin source files and is not a proof of full indirect reachability. The exhaustive production call graph remains a P0.

## 4. Concurrency and SQLite matrix

| Scenario | Evidence | Status |
|---|---|---|
| Two independent DB connections claim different intents for the same symbol concurrently | Threaded test with separate SQLite connections; only one durable claim is accepted | TEST-CONFIRMED |
| Two independent runtime barriers attempt same-symbol entries | Controlled race test with separate DB connections, separate ExecutionBarrier and ContextCache objects; only one submit callback is reached | TEST-CONFIRMED on implementation SHA `4da4643e2ca3115556df727440fa5aceb07ce500`; latest exact SHA adds an independent-process claim test |
| Reuse of an existing intent ID across independent connections | Regression test rejects reuse | TEST-CONFIRMED |
| Two independent OS processes race for same-symbol claim | `test_two_independent_processes_cannot_claim_conflicting_symbol_intents`; each worker opens its own SQLite connection; Python suite passed on SHA `7333b94263c14180efdc7713a40928af48d46277` | TEST-CONFIRMED |
| Reuse of clientOrderId | Unique index and existing regression tests | TEST-CONFIRMED for covered fixtures |
| SQLite BUSY / locked DB during claim | Fail-closed transaction path is implemented | NOT VERIFIED across a deterministic busy/timeout matrix |
| Commit failure and read-after-commit failure | Some persistence failure windows have tests | PARTIALLY VERIFIED; not all required fault points |
| Two recovery processes reconcile the same unknown order/OCO | No complete process-level test matrix | NOT VERIFIED |
| Cancel racing with fill | No exhaustive independent-runtime race matrix | NOT VERIFIED |
| Recovery racing with new entry / runtime restart during second runtime | One same-symbol submit race covered; broader lifecycle race matrix remains | PARTIALLY VERIFIED |

The atomic claim prevents the previously identified check-then-insert race for a symbol in both threaded independent-connection and independent-process tests. It does not by itself prove all lifecycle transitions, multi-symbol portfolio reservation, or concurrent recovery are safe.

## 5. Financial reconciliation

### Confirmed in code/tests
- Fill persistence requires a stable exchange trade ID.
- Duplicate fill IDs are idempotent; incompatible repeated data raises an error.
- Duplicate durable client order IDs are rejected.
- Missing commission amount/asset and missing authoritative trade history block the reviewed portfolio/legacy reconciliation paths rather than being silently represented as zero fees.
- Base-asset commission is passed to the shared accumulator in the reviewed legacy `Trader` path; third-asset fees remain explicitly unvalued unless a trusted conversion is available. Legacy reconciliation blocks when such assets remain unvalued.
- Portfolio third-asset conversion now rejects zero/non-positive prices; the newly added regression tests still require an exact-SHA CI pass.
- These are code changes plus test cases; full end-to-end financial reconciliation is still not closed.

### Still open
- End-to-end proof for commission charged in base asset, quote asset and a third asset across every entry, partial fill, exit, restart and PnL path.
- Proof that unknown third-asset conversion keeps the complete campaign/accounting state reconciliation-required across every caller.
- Full VWAP, gross quantity, net base quantity, quote costs, fees and residual exposure reconciliation from authoritative exchange trades and balances.
- Proof that no local empty record or incomplete history is interpreted as FLAT or zero exposure.
- Full test of fee-adjusted PnL and balance reconciliation after each fault point.

Status: **PARTIALLY VERIFIED — NOT CLOSED**.

## 6. Fault-injection matrix

| Required family | Coverage found | Status |
|---|---|---|
| Intent persistence fails before submit | Existing ExecutionBarrier persistence tests | PARTIALLY TEST-CONFIRMED |
| Exchange accepts order, response is lost / ambiguous retry | Existing ambiguous submission and recovery tests | PARTIALLY TEST-CONFIRMED |
| Response received, response persistence fails | Existing result-persistence failure test | PARTIALLY TEST-CONFIRMED |
| Partial fill / residual exposure | Existing multi-position lifecycle tests and deterministic fixtures | PARTIALLY TEST-CONFIRMED |
| Missing fill/commission history | New fail-closed regression tests | TEST-CONFIRMED for reviewed paths |
| Every create-order crash boundary (commit/read-back/process kill) | Not all points injected | NOT VERIFIED |
| Executed conditional entry with missing/unavailable fill history | New test forces zero quote and failing trade-history lookup; recovery remains RECONCILE_REQUIRED and submits no protection SELL | TEST-CONFIRMED on SHA `66354eb53ee456a788a01cdd2293d8068b728b83` |
| Protection absent locally / absent at exchange / partial protection | Some recovery coverage only | NOT VERIFIED as a complete matrix |
| OCO cancel accepted but response lost; child fill during cancel | Some target-list recovery code/tests | NOT VERIFIED as a complete matrix |
| Full exit but local persistence fails; duplicate delivery | Partial persistence/idempotency coverage | NOT VERIFIED as a complete matrix |
| Android process death at each ledger transition | Static source-order contract only | NOT VERIFIED |
| Corrupted SQLite and recovery concurrent with a second runtime | No complete matrix | NOT VERIFIED |

UNKNOWN, PENDING or CONFIRMED-but-unreconciled outcomes must continue blocking conflicting mutations. No test may downgrade an unknown exchange outcome to a successful/flat state for convenience.

## 7. Android write-ahead ledger

- CODE-CONFIRMED: signed POST/DELETE wrappers route through `durableMutation`; the source-level contract checks PENDING persistence before the signed network call and UNKNOWN/reconciliation latching on error.
- TEST-CONFIRMED: static source contract tests are in the Python suite; Android unit tests and build are separately run in GitHub Actions.
- NOT VERIFIED: process-kill tests at each ledger boundary, two concurrent runtime starts, failed SharedPreferences commit/clear behavior under injected faults, and authoritative reconciliation for all ledger/exchange conflicts.

Static source-order tests are not a substitute for the requested Android crash/fault-injection matrix.

## 8. Spot / Futures separation

| Product path | Current evidence | Status |
|---|---|---|
| Spot LONG / BUY | Spot adapter and barrier paths exist; test coverage is substantial but the full P0 matrix is incomplete | PARTIALLY VERIFIED |
| Spot SELL reducing/closing an existing position | Portfolio lifecycle and emergency/manual exit paths exist; residual and fee reconciliation is not fully closed | PARTIALLY VERIFIED |
| Futures LONG | No complete Futures-specific end-to-end proof established in this task | NOT VERIFIED |
| Futures SHORT | Spot SELL is not a SHORT. No complete Futures SHORT lifecycle proof for position mode, order identity, protection, partial fills, close, fees and recovery | NOT VERIFIED |

No Futures readiness claim is made.

## 9. CI evidence

- Exact implementation SHA `66354eb53ee456a788a01cdd2293d8068b728b83`: [workflow run 38090157504](https://github.com/19wolfalone92/Williams/actions/runs/38090157504) completed successfully. Python tests: **199 passed, 4 warnings**. Android build job completed `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` successfully; debug APK and lint report artifacts were uploaded.
- Prior exact-SHA runs with Python failures were investigated and corrected; they are not treated as passes.
- Exact current HEAD at last verification: `2312582ef7e92f5e29695db7c5e0557ac0491ad2`; [workflow run 38090391571](https://github.com/19wolfalone92/Williams/actions/runs/38090391571) passed on this exact SHA (Python 199 passed, 4 warnings; Android build, unit tests and lint/static checks passed). A documentation-only refresh of this report will trigger a new run; that run must be checked separately and prior results must not be transferred.
- Android job command in workflow: `gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace`. A job still in progress is not PASS.

## 10. Testnet gate and remaining P0 blockers

Read-only Binance Testnet was **NOT RUN**. Preconditions remain unmet. No credentials were read, logged or used for this audit.

P0 blockers still open:
1. Complete static/direct/indirect production mutation call graph and prove no reachable bypass of ExecutionBarrier.
2. Expand concurrency coverage to independent runtime processes, SQLite BUSY/commit/read-back failures, cancel-vs-fill, OCO recovery-vs-entry and concurrent recovery.
3. Complete fault injection for every order, protection, OCO, exit and restart boundary.
4. Prove complete financial reconciliation, including base/quote/third-asset commission, net inventory and residual exposure.
5. Complete Android process-kill/write-ahead-ledger fault injection.
6. Finish and record Android build, unit tests and lint on the exact resulting branch HEAD.
7. Separate Spot evidence from Futures LONG/SHORT evidence; Futures remains unverified.
8. Only after local P0 closure, run the strictly read-only Binance Testnet checklist.

## 11. Decision

**IN PROGRESS — NOT APPROVED**

Target status remains **READY FOR READ-ONLY TESTNET VERIFICATION**, not live-trading readiness. Binance Mainnet use is prohibited by this task.

## 12. Verification addendum — 2026-10-10

- Latest pre-refresh HEAD: `2312582ef7e92f5e29695db7c5e0557ac0491ad2`.
- `main`: `04da5f9c09572e99ff310be3a1908179165e528c`, unchanged.
- Branch comparison: 121 commits ahead, 0 behind.
- Exact-SHA GitHub Actions run: [38090391571](https://github.com/19wolfalone92/Williams/actions/runs/38090391571) — **SUCCESS**.
- Python job `114325456794`: **199 passed, 4 warnings**.
- Android job `114325456555`: **SUCCESS**; workflow runs `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug`.
- Latest branch workflow query showed no in-progress run at the time of this check; the ten most recent listed runs were completed. The documentation refresh will create a new run, which must be independently inspected on its exact SHA.
- Existing related open PRs found: #43 `fix/task-007-risk-management`, #42 `audit/williams-2.1-futures-safety`, and #44 `fix/williams-fractal-stop-provenance-20261010` (targets another branch). No PR targets `audit/williams-2.1-task-007-recovery`; none was created.
- Testnet remains prohibited for now: independent-runtime concurrency coverage is partial; the requested full crash matrix, complete direct/indirect mutation reachability proof, comprehensive fee/balance/exposure reconciliation, and Android process-kill fault injection are not complete.
