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


## 13. Latest exact-HEAD verification — 2026-10-11

- Current branch HEAD checked: `8be12a58347810c1d77615a9b57d943092191b76`.
- Baseline `main` checked again: `04da5f9c09572e99ff310be3a1908179165e528c`; unchanged.
- Compare `main...HEAD`: **122 commits ahead, 0 behind**.
- Exact-SHA GitHub Actions run: [38091051594](https://github.com/19wolfalone92/Williams/actions/runs/38091051594) — **SUCCESS**, head SHA exactly `8be12a58347810c1d77615a9b57d943092191b76`.
  - Python job `114327383965`: **199 passed, 4 warnings**.
  - Android job `114327384145`: **SUCCESS**. The workflow's Android job includes `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug`.
- At the time of the latest run-list query, the ten newest branch runs were completed; the newest run above is the exact current SHA.
- Related open PRs rechecked: #43 `fix/task-007-risk-management`, #42 `audit/williams-2.1-futures-safety`, and #44 `fix/williams-fractal-stop-provenance-20261010` (targets another branch). No PR targets this recovery branch. No PR was created and no merge was performed.
- A broader static sweep was run against the 85 production Python/Kotlin source files in the branch tree. It found the low-level signed order-changing adapter calls in `binance_client.py`, mutation call sites in `campaign_execution.py`, `portfolio_trader.py`, and `trader.py`, and Android signed mutation call sites in `StandaloneRuntime.kt` routed through `durableMutation`. The `requests.post` in `telegram_bot.py` is a Telegram notification request, not a Binance order mutation. This static sweep strengthens the inventory but **does not prove complete indirect reachability**; the report deliberately retains P0-A as open until the call graph and runtime reachability are exhaustively verified.
- Testnet gate remains **NOT MET**: the independent-process coverage currently proves atomic same-symbol intent claiming, but does not cover every required concurrent recovery/cancel-vs-fill scenario; the complete order/protection/OCO/exit fault matrix, full financial reconciliation, and Android process-kill ledger tests remain unverified.
- Decision remains **IN PROGRESS — NOT APPROVED**. Read-only Binance Testnet was not attempted because the required local gates are still open. No Binance Mainnet request or order-changing exchange request was made.


---

## 14. TASK 007.2 — Financial writer / call-graph refresh (2026-10-11)

### 14.1 Exact source revision and provenance boundary

This refresh audits source content at **implementation SHA `b5d285f978a491f8eff8c09dce815a0f6d1e6afb`**, not the later documentation commit that contains this addendum.

| Item | Evidence / result |
|---|---|
| Repository | `https://github.com/19wolfalone92/Williams` |
| Target ref | `refs/heads/audit/williams-2.1-task-007-recovery` |
| Target HEAD observed before this documentation update | `b5d285f978a491f8eff8c09dce815a0f6d1e6afb` |
| HEAD parent | `c436742dd05ece72ec91f3d6196f582d965e0c11` |
| Commit message | `docs(audit): record TASK 007.15 financial writer blockers` |
| Main comparison baseline | `04da5f9c09572e99ff310be3a1908179165e528c` |
| GitHub compare result | 137 commits ahead, 0 behind, status `ahead` |
| Commit signature | GitHub API reports `verified=false`, reason `unsigned` |
| Local checkout / working-tree status | NOT AVAILABLE to this audit execution |
| `git bundle verify` / `git fsck --full` | NOT RUN: no local Git bundle/checkout was available |
| SHA-256 of source bundle | NOT AVAILABLE: no bundle was obtained |
| Trusted origin proof | NOT ESTABLISHED by GitHub API metadata alone |

The GitHub branch ref endpoint returned `b5d285f978a491f8eff8c09dce815a0f6d1e6afb` before this documentation update. The commit metadata and recursive tree were retrieved from GitHub API. This establishes which remote commit was inspected, but it does **not** establish signed provenance or local repository integrity. The report does not claim a clean working tree, bundle verification, or `git fsck` success.

### 14.2 Source tree and exact file evidence

The recursive Git tree for `b5d285f978a491f8eff8c09dce815a0f6d1e6afb` was not truncated and contained 264 entries. These relevant file blob SHAs were retrieved at that revision:

| File | Blob SHA | Audit use |
|---|---|---|
| `db.py` | `c33987f48310c71b939fdfe6264588447f5b160a` | SQLite schema, transaction helper, fill/trade/campaign writers |
| `execution_accumulator.py` | `c71233be0f0f11921106fb57eb456efae27127e4` | Decimal aggregation of quantity, quote and commissions |
| `portfolio_trader.py` | `c38f25deeb8871ec2357e57f0e7bf6caa8d3d119` | Multi-position execution, trade/position recovery and reconciliation |
| `campaign_execution.py` | `3c6a8f5d88d928f36c4c732c18b67f10cf59b024` | Campaign order execution, recovered orders and exit lifecycle |
| `campaign_engine.py` | `52fe7fcab22e56589b69210f644e5347ec63bb94` | In-memory campaign transitions and fill projections |
| `campaign_model.py` | `f30ec324c3f33167d73b6c21f36151832bb285c8` | Campaign domain state and transition validation |
| `trader.py` | `7499efb7b28ecb717eab75753b661c9962456578` | Legacy single-symbol execution and recovery |
| `execution_barrier.py` | `0a9502586b234bb159fca746194d9f21a741af41` | Durable order-intent mutation barrier |
| `trade_journal.py` | `0e7f13ae3c57b8304428735d9af202dfe6f7bcd1` | Entry/exit journal writers |
| `docs/WILLIAMS_BOT_2_1_TASK_007_15_CANONICAL_FILL_FINANCIAL_WRITER_AUDIT.md` | `64c63d1dec66cf0f9b6049007a7e80818e1419c4` | Existing canonical-fill/multiple-writer audit |

GitHub file views used for source inspection:
- `https://github.com/19wolfalone92/Williams/blob/b5d285f978a491f8eff8c09dce815a0f6d1e6afb/db.py`
- `https://github.com/19wolfalone92/Williams/blob/b5d285f978a491f8eff8c09dce815a0f6d1e6afb/execution_accumulator.py`
- `https://github.com/19wolfalone92/Williams/blob/b5d285f978a491f8eff8c09dce815a0f6d1e6afb/portfolio_trader.py`
- `https://github.com/19wolfalone92/Williams/blob/b5d285f978a491f8eff8c09dce815a0f6d1e6afb/campaign_execution.py`
- `https://github.com/19wolfalone92/Williams/blob/b5d285f978a491f8eff8c09dce815a0f6d1e6afb/campaign_engine.py`
- `https://github.com/19wolfalone92/Williams/blob/b5d285f978a491f8eff8c09dce815a0f6d1e6afb/trader.py`
- `https://github.com/19wolfalone92/Williams/blob/b5d285f978a491f8eff8c09dce815a0f6d1e6afb/campaign_model.py`
- `https://github.com/19wolfalone92/Williams/blob/b5d285f978a491f8eff8c09dce815a0f6d1e6afb/execution_barrier.py`

### 14.3 Financial writer inventory — confirmed direct sinks

Line numbers below refer to the source blobs listed above, not to this report.

| Writer ID | File/function and source lines | Direct effect | Boundary / idempotency evidence | Status |
|---|---|---|---|---|
| FW-DB-01 | `db.py::state_set` L418–425 | `bot_state` UPSERT; may persist recovery and position gates | Commits when called outside `transaction()`; callers can persist individual keys separately | CONFIRMED WRITER; global multi-key atomicity NOT VERIFIED |
| FW-DB-02 | `db.py::state_delete` L443–448 | Deletes state key | Own commit outside transaction | CONFIRMED WRITER |
| FW-DB-03 | `db.py::save_execution_intent` L490–515 | Inserts durable execution intent | Direct commit; unique identities checked elsewhere; not the fill-effect transaction | CONFIRMED WRITER |
| FW-DB-04 | `db.py::save_order` L583–636 | Updates/inserts legacy order rows | Order/client identity lookup; commit per method outside transaction | CONFIRMED WRITER |
| FW-DB-05 | `db.py::save_trade` L681–690 | Inserts legacy trade/position/PnL/fee row | Independent commit outside transaction | CONFIRMED FINANCIAL WRITER |
| FW-DB-06 | `db.py::update_trade_oco` L693–722 | Updates protective order identifiers/state | Independent commit outside transaction | CONFIRMED STATE WRITER |
| FW-DB-07 | `db.py::update_trade_quantity` L724–735 | Updates position quantity | Independent commit outside transaction | CONFIRMED FINANCIAL WRITER |
| FW-DB-08 | `db.py::close_trade` L737–767 | Updates exit price/time, PnL, PnL%, fees and close state | Independent commit outside transaction | CONFIRMED FINANCIAL WRITER |
| FW-DB-09 | `db.py::save_trade_journal` L769–832 | Writes trade journal/diagnostics | Independent commit outside transaction | CONFIRMED JOURNAL WRITER |
| FW-DB-10 | `db.py::save_campaign` L1027–1069 | Campaign quantity, average entry, realized/unrealized PnL, risk/capital reserves and lifecycle | UPSERT; float conversion; own commit outside transaction | CONFIRMED FINANCIAL PROJECTION WRITER |
| FW-DB-11 | `db.py::save_campaign_signal` L1081–1105 and `set_campaign_signal_state` L1107–1110 | Signal lifecycle rows | `INSERT OR REPLACE` / UPDATE and independent commit | CONFIRMED STATE WRITER |
| FW-DB-12 | `db.py::save_campaign_order` L1119–1179 | Campaign order identity/status/price/quantity/risk/capital reservation | Lookup then UPDATE/INSERT; commits independently unless wrapped | CONFIRMED STATE/RESERVATION WRITER |
| FW-DB-13 | `db.py::save_campaign_fill` L1182–1233 | Fill row, trade identity, quantity, price, quote and commission fields | Requires symbol and ID; lookup compares existing financial tuple via float; insert is protected by unique partial index on `(symbol, exchange_trade_id)`; own commit outside wrapper | JOURNAL DEDUPE CONFIRMED; FULL EFFECT EXACTLY-ONCE NOT PROVEN |
| FW-DB-14 | `db.py::log_campaign_event` L1236–1244 | Campaign event journal | Independent commit outside transaction | CONFIRMED JOURNAL WRITER |
| FW-CE-01 | `campaign_engine.py::record_add_on_fill` L320 onward | In-memory campaign position/VWAP/addition/risk fields and persistence calls | Campaign and event writes are not proven to share one outer transaction | MULTIPLE EFFECT PATH; ATOMICITY NOT VERIFIED |
| FW-CE-02 | `campaign_engine.py::record_initial_fill` L362 onward | Initial position quantity/VWAP and campaign state | Calls through campaign persistence; no shared canonical effect claim proven | MULTIPLE EFFECT PATH; ATOMICITY NOT VERIFIED |
| FW-CEX-01 | `campaign_execution.py::persist_recovered_campaign_order` L471 onward; legacy trade UPDATE at L663–677 | Recovered order/trade projection | Direct SQL UPDATE followed by explicit commit at L677 | CONFIRMED RECOVERY WRITER; shared transaction with campaign/fill effects NOT PROVEN |
| FW-PT-01 | `portfolio_trader.py::_repair_stale_reconcile_states` L239 onward and direct SQL near L264 | Repairs reconciliation state | Direct SQL path; complete transaction/call context requires focused review | CONFIRMED STATE WRITER; ATOMICITY NOT VERIFIED |
| FW-PT-02 | `portfolio_trader.py::_reconcile_trade` L1209 onward | Reconciles local trade/position from exchange evidence | Calls and persistence effects require path-by-path test; not one demonstrated canonical fill applier | CONFIRMED RECONCILIATION PATH; exactly-once financial effect NOT PROVEN |
| FW-PT-03 | `portfolio_trader.py::recover` L1685 onward | Recovery orchestration over pending entries and positions | Multiple downstream reads/writes; full recovery atomicity not established | CONFIRMED ORCHESTRATOR; effect ownership UNRESOLVED |
| FW-TR-01 | `trader.py::recover_state` L914 onward; `_recover_closed_trade` L1067 onward | Legacy recovery and trade projection | Multiple direct DB writes and state transitions; no single shared fill application claim established | CONFIRMED RECOVERY PATH; exactly-once NOT PROVEN |
| FW-TJ-01 | `trade_journal.py::record_entry` L127 onward and `record_exit` L137 onward | Delegates journal updates into DB trade-journal writer | Separate journal persistence can lag/lead trade close unless an outer transaction is demonstrated | CONFIRMED JOURNAL PATH; cross-entity atomicity NOT VERIFIED |

**Inventory scope warning:** this is a verified direct-writer inventory for the named financial/recovery modules and the directly located SQL sinks. It is not yet a proof that every indirect caller across all 264 tree entries has been enumerated. Other SQL-bearing files, server/API paths, and Android persistence paths must be included before claiming the repository-wide inventory is complete. A search with no match in one query is not evidence of absence.

### 14.4 SQLite transaction semantics and crash windows

Observed in `db.py`:
- `Database.__init__`, L13–18: `sqlite3.connect(..., check_same_thread=False, timeout=20)`, `busy_timeout=20000`, WAL journal mode and `synchronous=FULL`.
- `Database.transaction()`, L23–39: issues `BEGIN`, commits on normal exit, rolls back on `Exception`.
- Nested use is tracked by an instance-level `_transaction_active` boolean (L24–28), with no savepoint; this is not a cross-connection or cross-process coordinator.
- `init()` commits schema/recovery-state setup at L280; this is separate from runtime fill application.
- Numerous persistence methods conditionally commit when `_transaction_active` is false. A caller that does not wrap every related effect can persist each step separately.
- The fill row, campaign projection, legacy trade row, bot-state/recovery keys and campaign event journal have separate writer methods. The inspected `campaign_execution.py` exit/recovery paths contain explicit commits after direct SQL writes; no end-to-end transaction covering fill + position + fee + PnL + campaign + journal was demonstrated.

| Operation | Writes / crash window | Recovery consequence |
|---|---|---|
| Persist fill | `campaign_fills` insert can commit independently | Restart can find a fill journal row while position/campaign effects remain unapplied |
| Apply legacy trade/position | `save_trade`, `update_trade_quantity`, `close_trade` commit separately | Replay may duplicate or diverge from the fill journal unless a shared effect identity guards it |
| Apply campaign projection | `save_campaign` UPSERT can commit separately | Campaign quantity/VWAP/PnL may disagree with fill/trade rows after crash |
| Apply commission/PnL | Commission fields can be written in fill or legacy trade; close/PnL update is separate | Fee and PnL may be missing, duplicated, or sourced from a different snapshot |
| Campaign event / trade journal | Separate insert/update methods and commits | Journal can lag/lead the authoritative position projection |
| Recovery gate | Separate state-key writes unless explicitly wrapped | Partially updated state keys can be observed; new entries must remain blocked until authoritative reconciliation completes |

SQLite transaction atomicity applies only to writes made through the same connection and within the same actual transaction. It does not make a SQLite transaction atomic with Binance, nor does it make independent database connections share an in-memory `_transaction_active` flag.

### 14.5 Fill identity and exactly-once boundary

Observed in `db.py`:
- `campaign_fills.exchange_trade_id` is indexed uniquely with `symbol` by `uq_campaign_fills_exchange_trade` (partial index; see `_migrate_recovery_schema`, L303–348).
- `save_campaign_fill` derives ID from `exchange_trade_id`, `tradeId`, `trade_id`, or `id`, and rejects missing symbol/ID (L1182–1194).
- Repeated ID payloads are compared using float-converted values and a tuple; conflicting values raise; exact repeat returns `False` without inserting another journal row (L1195–1218).
- Identity scope is `(symbol, exchange_trade_id)`; exchange/account namespace and source-type semantics are not part of the uniqueness key.
- This method protects the fill journal row only. It does not atomically claim and apply position, commission, PnL, campaign, reserve and ledger effects.
- The existing TASK 007.15 audit at `docs/WILLIAMS_BOT_2_1_TASK_007_15_CANONICAL_FILL_FINANCIAL_WRITER_AUDIT.md` independently records **BLOCKED — MULTIPLE FINANCIAL WRITERS** and states that no complete shared production-enforced `CanonicalFillV1` contract was established.

No `CanonicalFillV1` implementation is asserted by this refresh. The required contract proposal remains separate work: validated canonical identity, exact decimal payload, explicit fee status (including UNKNOWN versus confirmed zero), payload-conflict handling, one durable effect claim, atomic financial projections, and replay/reconciliation after an ambiguous commit.

### 14.6 Monetary type flow

- `execution_accumulator.py::accumulate_fills` converts exchange strings to `Decimal` using `Decimal(str(...))`, sums quantity and quote, and values quote/base-asset commissions in Decimal. Third-asset commissions are recorded as unvalued assets rather than silently assigned a quote value.
- `accumulate_order` falls back to cumulative order fields and returns Decimal values.
- `db.py::save_campaign_fill` converts quantity, price, quote and fee fields to Python `float` before SQLite insertion (L1219–1233).
- `db.py` schema defines financial columns including campaign fill values, campaign position/VWAP/PnL/reserves, and legacy trade quantity/prices/PnL/fees as SQLite `REAL` (schema definitions near L90–200).
- `db.py::save_campaign` converts campaign monetary projections to `float` before persistence (L1027–1069).
- Read paths commonly return SQLite REAL values and callers convert to `float` for calculations; exact Decimal continuity across persist/recovery is therefore NOT established.
- Invalid/non-finite Decimal handling is not uniformly enforced by the accumulator: malformed numeric strings may raise, but a repository-wide NaN/Infinity rejection contract was not demonstrated. The accumulator skips non-positive quantity/price; it does not prove all other paths enforce identical validation.
- SQLite REAL history cannot be made exact retroactively by a code-only conversion. No schema migration or historical financial-data conversion was performed in this audit.

### 14.7 Required characterization / fault-injection matrix

The following tests are **PLANNED, NOT RUN by this refresh**. Each test must use isolated temporary SQLite databases and production persistence/application methods, record the exact implementation SHA, command and return code, and assert every financial projection—not only fill-row count.

| ID | Scenario / injected boundary | Required assertions |
|---|---|---|
| T01 | Replay same `exchange_trade_id` and identical payload | One fill; position, fee, realized PnL, campaign, reserve and ledger effects applied once |
| T02 | Same ID with conflicting quantity/price/commission/campaign | Fail closed; no second effect; recovery-required latch; conflict evidence retained |
| T03 | Two concurrent handlers apply same fill | Exactly one durable effect owner; no double position/fee/PnL update |
| T04 | Two independent SQLite connections apply same fill | Same as T03 across connections; no reliance on Python instance flags |
| T05 | Crash after fill commit before position update | Restart detects incomplete effect and completes or blocks safely; no duplicate on replay |
| T06 | Crash after position mutation before fee write | Position and fee/PnL consistency restored or entries remain blocked |
| T07 | Crash between fee and PnL writes | No partially accepted final financial state |
| T08 | Commit succeeds but caller observes exception/timeout | Read-after-restart resolves durable outcome; replay does not double apply |
| T09 | Re-run recovery after partially applied operation | Idempotent reconciliation to one canonical state |
| T10 | Same fill arrives through REST and WebSocket | Shared identity and single effect claim across both sources |
| T11 | Local position differs from exchange inventory/trade history | Latch reconciliation-required; block entries until mismatch resolved |
| T12 | SQLite write/commit raises (including BUSY/locked) | No exchange mutation without durable intent; no false success; no partial financial effect accepted |

**Test completion status:** NOT RUN in this documentation refresh. Existing intent-claim concurrency tests are not substitutes for same-fill financial-effect concurrency tests.

### 14.8 Recovery/reconciliation evidence and single-writer decision

The code contains recovery paths in `portfolio_trader.py::recover` (L1685 onward), `_reconcile_trade` (L1209 onward), and `trader.py::recover_state` (L914 onward), plus campaign order reconciliation in `campaign_execution.py`. The reviewed sources show multiple local persistence routes and exchange-history lookups, but this refresh did not prove a single mandatory path for REST, WebSocket and restart-recovered fills.

**Required architecture decision:** one production-enforced financial effect applier must own the transition from exchange-confirmed canonical fill to all local financial projections. Other modules may parse, fetch, propose, or report, but must not independently apply the same fill's position/fee/PnL/campaign effects. The chosen transaction must atomically persist the fill identity, financial projection updates, and an application-complete marker in SQLite; ambiguous exchange results remain a separate reconciliation concern.

Do not permit new entries while the canonical financial state, fee valuation, order result, or required reconciliation remains UNKNOWN / incomplete.

### 14.9 Updated decision

- Exact remote implementation SHA identified: **YES** (`b5d285f978a491f8eff8c09dce815a0f6d1e6afb`).
- Trusted origin and local Git integrity gates (`git bundle verify`, `git fsck --full`, clean working tree): **NOT VERIFIED / NOT RUN**.
- Direct writers in the named core modules: **PARTIALLY INVENTORIED**; repository-wide indirect call graph: **NOT COMPLETE**.
- Atomic fill + position + fee + PnL + campaign + ledger boundary: **NOT PROVEN**.
- Exactly-once financial effect across replay, REST/WebSocket and recovery: **NOT PROVEN**.
- Monetary Decimal continuity through SQLite and recovery: **NOT PROVEN**.
- Characterization/fault-injection matrix in §14.7: **PLANNED — NOT RUN**.
- Production code/schema/financial records and Mainnet/real orders: **NOT CHANGED / NOT USED** by this documentation-only refresh.

**TASK 007.2 status: BLOCKED — TRUSTED BASELINE PROVENANCE AND COMPLETE WRITER/CALL-GRAPH EVIDENCE INCOMPLETE.** The exact remote source commit was identified, but the acceptance criteria are not met. No PASS is claimed.


---

## 15. TASK 007.3 — Exactly-once / concurrency / crash-recovery checkpoint (2026-10-11)

### 15.1 Baseline verification

| Gate | Evidence | Result |
|---|---|---|
| Implementation source SHA | GitHub commit API: `b5d285f978a491f8eff8c09dce815a0f6d1e6afb` | REMOTE COMMIT IDENTIFIED |
| Implementation parent | `c436742dd05ece72ec91f3d6196f582d965e0c11` | REMOTE METADATA IDENTIFIED |
| Prior report commit | `f10cc8a64b1db7246d704e4e331949729e714fba` | IDENTIFIED |
| Current target branch HEAD | GitHub ref API: `f10cc8a64b1db7246d704e4e331949729e714fba` | VERIFIED AT CHECK |
| Relationship | `b5d285f` is an ancestor checkpoint; `f10cc8a` is the documentation-only follow-up | REMOTE HISTORY IDENTIFIED |
| Clone / local checkout | `git clone --no-checkout https://github.com/19wolfalone92/Williams.git /tmp/Williams-task0073` | NOT RUN TO COMPLETION; exit 128, DNS error `Could not resolve host: github.com` |
| Local worktree status | No checkout created | NOT AVAILABLE |
| `git bundle verify` / `git fsck --full` | No local repository or bundle | NOT RUN |
| Bundle SHA-256 | No bundle obtained | NOT AVAILABLE |
| Commit provenance | Remote GitHub metadata available; no signed provenance established | NOT VERIFIED |
| CI runs associated with `b5d285f` and `f10cc8a` | GitHub commit-workflow-run query returned empty lists for both SHAs | NO EXACT-SHA RUN EVIDENCE FROM THIS QUERY |

The remote implementation source must be analyzed only at `b5d285f978a491f8eff8c09dce815a0f6d1e6afb`; the current branch head `f10cc8a64b1db7246d704e4e331949729e714fba` includes the prior documentation update. No source code from a different later branch revision was substituted into implementation findings.

### 15.2 Executability and test boundary

A local Git checkout could not be obtained in this environment because DNS resolution for `github.com` failed. Therefore the required fault injection cannot honestly be reported as executed against the exact implementation SHA in this run. Remote source reads and previous workflow records are not substitutes for running the 12 specified scenarios against an isolated temporary SQLite database.

No production code, schema, financial data, workflow, branch history, or Mainnet/Testnet trade state was changed. No real orders were sent. This checkpoint adds documentation only.

### 15.3 FILL-001 through FILL-012 execution results

| Scenario | Required experiment | Result in TASK 007.3 run | Reason |
|---|---|---|---|
| FILL-001 | Identical fill replay | NOT RUN | No executable checkout/test harness |
| FILL-002 | Same identity, conflicting payload | NOT RUN | No executable checkout/test harness |
| FILL-003 | Concurrent application, two handlers | NOT RUN | No executable checkout/test harness |
| FILL-004 | Crash after fill persistence | NOT RUN | No executable checkout/test harness |
| FILL-005 | Crash after position update | NOT RUN | No executable checkout/test harness |
| FILL-006 | Crash during fee application | NOT RUN | No executable checkout/test harness |
| FILL-007 | Crash during PnL update | NOT RUN | No executable checkout/test harness |
| FILL-008 | Commit outcome unknown to caller | NOT RUN | No executable checkout/test harness |
| FILL-009 | Same fill through REST/WebSocket/recovery | NOT RUN | No executable checkout/test harness |
| FILL-010 | Repeated recovery | NOT RUN | No executable checkout/test harness |
| FILL-011 | Local/exchange state mismatch | NOT RUN | No controlled exchange fixture; no exchange access used |
| FILL-012 | SQLite write/commit failure | NOT RUN | No executable checkout/test harness |

No synthetic logs or simulated outcomes are represented as production-code results. This run has no FILL scenario that can be marked PASS or FAIL.

### 15.4 Financial invariants and decision

The following remain unproven by executed tests: unique application of position quantity; fee completeness and no duplication; PnL consistency; campaign/position consistency; reserve-release timing; ledger agreement; idempotent repeated recovery; global blocking of new entries while state is unknown; and correct distinction between absent, unknown and confirmed-zero financial values.

The previous source audit remains relevant: `save_campaign_fill()` protects the fill journal row but is not proof of atomic application of all projections; financial writers use separate persistence paths; Python `Decimal` values pass through `float` / SQLite `REAL`; a repository-wide mandatory `CanonicalFillV1` effect owner has not been proven.

### 15.5 TASK 007.3 status

- Exact remote implementation and current report HEAD: **IDENTIFIED**.
- Trusted local checkout / bundle integrity: **BLOCKED**.
- Complete repository-wide source-to-sink graph: **NOT COMPLETE**.
- Exactly-once financial effect: **NOT PROVEN**.
- Concurrency safety across independent SQLite connections: **NOT PROVEN**.
- Crash recovery at all financial boundaries: **NOT PROVEN**.
- FILL-001…FILL-012: **ALL NOT RUN** in this execution.
- Production code/schema/financial records/Mainnet: **NOT CHANGED / NOT USED**.

**TASK 007.3 status: BLOCKED — EXECUTABLE BASELINE AND REQUIRED FAULT-INJECTION EVIDENCE UNAVAILABLE.** Do not infer PASS from existing general CI or from the unique fill index. New entries must remain blocked whenever the canonical financial state, fee status, commit outcome, or required reconciliation is UNKNOWN or incomplete.
