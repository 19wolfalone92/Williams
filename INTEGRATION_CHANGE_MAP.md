# INTEGRATION CHANGE MAP — WILLIAMS BOT 2.1 / TASK 009

**Integration branch:** `audit/williams-2.1-task-009-integration`  
**Base:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Policy:** cherry-pick/minimal patch by contract, never merge PRs wholesale. Do not alter main.

## 1. Main baseline: what is already present

Direct GitHub contents reads against `main` confirmed these files exist in the baseline:
- `execution_barrier.py`
- `risk_engine.py`
- `app/src/main/java/com/williamsbot/StandaloneRuntime.kt`

Their presence does not mean they already satisfy the newer branch contracts. They are existing modules to patch, not replace.

The following files were not found at these exact paths in main and appear as additions in candidate comparisons:
- `futures_runtime.py` (#42/#44)
- `execution_recovery.py` (#46)
- `release_contract.py` (#45)
- `app/src/main/java/com/williamsbot/WilliamsAlligatorMath.kt` (#44/#49)
- `docs/ARCHITECTURE_OWNERSHIP_MATRIX.md` (#47)

No PR #42–#50 is merged. Therefore no code from those unmerged PRs is considered present in main merely because it appears in a PR diff. Other pre-existing equivalent logic may exist under different files/names and must be resolved by symbol/call-graph checks before porting.

## 2. Overlap map

| Surface / contract | Branches / PRs touching it | Overlap / conflict risk | Integration rule |
|---|---|---|---|
| `execution_barrier.py`, permits, serialization | #42, #43, #44, #47, #48, #49, #50; TASK 007/008 | Same core file changed repeatedly; behavior and permit lifecycle can diverge. #42/#44 use older base; #44 mergeable=false. | Establish one canonical mutation barrier contract first. Compare call sites and invariants; take only independently tested hunks. |
| `campaign_execution.py`, `portfolio_trader.py`, `trader.py` | #42–#44, #46–#50, TASK 007/008 | Multiple mutation paths and emergency/partial-exit changes; potential bypasses and duplicated financial writers. | Build one source-to-sink inventory. Every mutation must route through barrier and durable intent; no direct API escape hatches. |
| `db.py`, fills, position/campaign, fees/PnL | #43, #46–#50, TASK 007/008 | Overlapping schema/transaction/idempotency edits; concurrency and migration compatibility risk. | Define canonical fill applier and transaction boundary first; validate migrations and replay/crash matrix before importing further writers. |
| `StandaloneRuntime.kt`, `TradingAuditStore.kt`, Android execution | #42, #44, #46–#50; TASK 007 | Large overlapping runtime edits; possible competing owners for state, risk gates, fills and recovery. | Keep NativeEngine/runtime as state owner; dashboard is read-only. Integrate diagnostic/runtime slices separately from trading mutation changes. |
| Williams strategy / Alligator / fractals / stops | #42, #44, #49 | #44 is based on a Futures branch SHA and changes much of the same strategy and execution surface as #49. #42 also modifies `digital_williams_core.py`, `strategy.py`, `williams_signals.py`. | Prefer the current TASK 004 branch as the strategy candidate; compare #44 patch symbol-by-symbol and only port unique verified fixes. Enforce causal replay and Python–Android parity. |
| Risk engine / equity breaker / sizing | #43, #44, #42, #49, #50; TASK 007 | Legacy PR #43 is separate from current TASK 005 #50. Float/Decimal, structural stop, daily loss and campaign reserve contracts may conflict. | Select a single risk contract. Decimal migration must cover signal-to-ledger path, not only `risk_engine.py`; use atomic shared reservation tests. |
| Futures API, margin and Long/Short | #42, #44; `audit/williams-2.1-futures-safety` | #42 is based on old main parent; #44 base is a particular Futures SHA `80fbafd8...`. Huge diff and mergeability false. | Do not merge #42 or #44 wholesale. Extract Futures adapter only after shared decision contract, position-mode contract and separate Futures risk gate are stable. |
| Version/release contract / CI workflows | #45, #46, #47, #49, #50 | Workflow files and Gradle wrapper are changed repeatedly; release versioning may diverge from runtime code. #45 Android build fails on its exact SHA. | Integrate release metadata after stable module composition; then rerun all workflows on integration SHA and verify artifact provenance. |
| Scanner/data freshness/diagnostics | #42, #44, #46, #49, #50, TASK 008 | Runtime freshness and scanner edits overlap; passing unit tests do not establish live data path. | Integrate observability and freshness contracts early, without enabling trading. Verify actual stored candle counts and scanner symbols. |

## 3. Exact PR ancestry and CI blockers

- #42: `40a82056dee88a9c79e799912b07a8ddb1a1859b`, base `3fe008d85c06c90ed00d8035484b22aae657317b`; compare with main is `diverged`, 728 ahead / 1 behind. Mergeable=false.
- #43: `c4892b6ab08aafcf75485be2e548eb767cd571df`, base `3fe008d85c06c90ed00d8035484b22aae657317b`; 12 ahead / 1 behind. Campaign CI failed on this exact SHA.
- #44: `dbe755a942276ac3b369afd47d424fff48bbb608`, base Futures SHA `80fbafd8b7899a70c8039eac5045f3d4af25baa1`; 950 ahead / 1 behind vs main; mergeable=false.
- #45: `7fbe11b4ea1eb9b114f5ca8758ee2830877b5f0d`; 22 ahead / 0 behind; Android APK workflow failed on this exact SHA.
- #46: `7c642e1f447e540819778ba0e3694b0d15377493`; 204 ahead / 0 behind; Python, Campaign, Android APK workflows succeeded on this exact SHA.
- #47: `c599abaf4c4f345c6f113693d75dd732b282ab93`; 143 ahead / 0 behind; Python, Campaign, Android APK workflows succeeded on this exact SHA.
- #48: `cd22ddfe7931377fe168bdb5411a6b438e47a5b9`; 164 ahead / 0 behind; Python, Campaign, Android APK workflows succeeded on this exact SHA.
- #49: `9ec717ac7b3a1bf7735f57ab508ca85100af2f3b`; 164 ahead / 0 behind; Python, Campaign, Android APK workflows succeeded on this exact SHA.
- #50: `799941744e1f0b822dc779a653441de828914bc3`; 79 ahead / 0 behind; Python, Campaign, Android APK workflows succeeded on this exact SHA.
- TASK 007: `e7ab3e72a0a7a85823ec898da28a1761476b84de`; compare 147 ahead / 0 behind; no matching workflow records returned for this exact SHA.
- TASK 008: `7c4514d22ebca19861de2183317f7c39f3263e0c`; compare 128 ahead / 0 behind; no matching workflow records returned for this exact SHA.

“Workflow records not returned” means unverified by this query, not proven never run. Green CI for a candidate SHA is evidence only for that SHA and those workflows; it is not end-to-end acceptance.

## 4. Changes definitely present in main

The direct tree checks confirm the baseline already contains `execution_barrier.py`, `risk_engine.py`, and `StandaloneRuntime.kt`. The unmerged PR metadata confirms that the branch-specific deltas of PR #42–#50 have not been merged through those PRs. This inventory does **not** yet claim that no similar individual lines were independently introduced in main through another commit.

For every incoming change, the integration review must classify it as:
1. already implemented in main;
2. new implementation absent from main;
3. duplicate implementation of an existing contract;
4. incompatible contract change;
5. report/test-only change;
6. blocked pending evidence.

## 5. Recommended transfer order

### Slice 0 — Freeze and pin
- Keep main untouched.
- Pin the integration base and branch HEAD.
- Capture baseline CI and tree hashes on the integration branch.
- No trade mutations or live credentials.

### Slice 1 — Contract-only architecture
- Add/align ownership matrix, call graph, module interfaces and error-state vocabulary.
- Establish the authoritative state owner and canonical ExecutionBarrier API.
- Do not yet alter strategy rules or exchange behavior.

### Slice 2 — Execution safety vertical slice
- Pick the minimal compatible changes from #47/#48/TASK 008.
- Durable intent → risk reservation → barrier permit → mutation → UNKNOWN recovery.
- Test each order/cancel/replace/OCO sink, including timeout and partial fill.

### Slice 3 — Ledger and risk atomicity
- Reconcile TASK 007 and TASK 005 #50 with the older #43 changes.
- Select one canonical Decimal representation, fill applier and SQLite transaction boundary.
- Run concurrent reservation, duplicate fill, crash-before/after-commit and restart recovery tests.

### Slice 4 — Strategy contract
- Use TASK 004/#49 as the primary candidate.
- Compare #44 and #42 only for unique strategy corrections; do not import their old-base bulk changes.
- Require causal replay, confirmed-fractal timing, structural stop provenance and Python–Android parity.

### Slice 5 — Android runtime/diagnostics in parallel
- Continue #46-based APK/runtime work on a separate integration slice.
- Validate SQLite durability, process-death recovery, freshness, scanner visibility and diagnostics.
- Do not gate APK development on Futures completion; do gate trading enablement on its own proofs.

### Slice 6 — Spot and Futures adapters separately
- Spot: long-only inventory/position contract; never open a short.
- Futures: separate adapter, position-side/margin/leverage contract, risk gate and ledger dimensions.
- Use #42 only after resolving base divergence and mergeability; require isolated Futures Testnet tests.

### Slice 7 — release integration
- Reconcile #45 version/provenance contracts after runtime/module contracts stabilize.
- Build from exact integration SHA; rerun all CI; record APK SHA-256, signing certificate, version and logs.

## 6. Immediate blockers

1. No local full checkout was available for `git fsck --full`, bundle checks, local diff validation, or running tests in this session.
2. No complete per-file semantic diff across all branches has been manually reviewed; compare endpoints give file-level change statistics only.
3. PR #42 and #44 are non-mergeable and use divergent/old ancestry.
4. PR #43 Campaign CI failed; PR #45 Android APK CI failed.
5. TASK 007 and TASK 008 exact current branch heads have no matching workflow records in the query.
6. Green branch CI does not prove integration compatibility or physical Android behavior.
7. Exactly-once financial effects, unknown-outcome recovery, Decimal end-to-end integrity and universal mutation-sink coverage remain acceptance blockers until tested on the integration SHA.
8. Futures Testnet, Spot Testnet and physical-device tests have not been run as part of this integration operation.

**Decision:** proceed with small contract-aligned slices on this integration branch. Keep both Spot and Futures in scope, one Williams strategy core, and independent execution/risk/ledger contracts. No PR is accepted solely by title, mergeability flag or green isolated CI.


## TASK 009.1 controlled checkpoint (2026-10-11)

- Branch-only patch now makes missing/failed execution-intent persistence fail closed and requires durable recording of the raw exchange response before returning an accepted result.
- Added `test_task0091_execution_barrier_fail_closed.py` and updated the existing barrier tests to use temporary SQLite stores, preserving their original stale-context and serialization assertions.
- Detailed contract, confirmed Python/Android mutation paths and remaining P0 blockers: `docs/WILLIAMS_BOT_2_1_TASK_009_1_EXECUTION_CONTRACT.md`.
- This checkpoint remains BLOCKED: direct mutation paths in `portfolio_trader.py` and `StandaloneRuntime.kt`, unresolved-intent restart gating, stable idempotency claims, cancel/fill reconciliation and exactly-once fill projection are not closed by this patch.
- Do not merge this branch until all TASK 009.1 acceptance gates pass on the exact final source SHA.


## TASK 009.2 — Atomic persistence checkpoint

- Added immutable atomic execution-intent claims, unique non-empty `client_order_id`, durable UNKNOWN lock, atomic exchange-result recording and a unique execution-fill ledger primitive in `db.py`.
- Updated `execution_barrier.py` to require readiness + claim, persist `SUBMITTING` before the network callback, and fail closed on ambiguous submission/result persistence.
- Added fault-injection tests in `test_task0092_atomic_execution_persistence.py` and a concurrent/timeout barrier test in `test_task0091_execution_barrier_fail_closed.py`; Campaign CI now explicitly runs these files.
- Details: `docs/WILLIAMS_BOT_2_1_TASK_009_2_ATOMIC_EXECUTION_PERSISTENCE.md`.
- **Status remains BLOCKED** until exact-HEAD CI is confirmed and an exchange-backed startup reconciler plus production CampaignExecutionService/financial-ledger fill integration are implemented and proven. Direct Python/Android mutation paths remain.

## TASK 009.3 — Exchange-backed recovery and campaign fills (2026-10-11)

- Added `execution_recovery.py` and `execution_recovery_contract.py`: Binance Spot order/trade-history reconciliation, sealed in-process evidence and fail-closed behavior for missing/conflicting/incomplete exchange data.
- Added scoped intent/account identity, persisted cancellation target ids, schema migration to version 9, legacy-unscoped blocking, stronger SQLite durability and repair-only `PROTECTION_PENDING` admission.
- Connected campaign BUY/SELL per-trade fill IDs to `apply_execution_fill()`, including campaign position, fees, trade journal and realized-PnL changes in the same SQLite transaction. Aggregate `executedQty` is now used as an exchange consistency check, not a deduplication id.
- Cancellation uses a durable target-bound intent; partial conditional-entry cancellation rereads exchange order/trade history. Current-format order reconciliation is not allowed to fall through to the legacy aggregate-replay path.
- Added 20 offline recovery fault-injection tests and added full CampaignExecutionService restart/exit assertions to `test_campaign_execution.py`.
- Detailed state/API/transaction contract and remaining blockers: `docs/WILLIAMS_BOT_2_1_TASK_009_3_EXCHANGE_BACKED_RECOVERY.md`.
- **Status BLOCKED.** Exact-head CI still contains a partial-entry protection/recovery failure plus failures in the new E2E test assertions. The latest changes are being re-tested; do not treat earlier (194 passed / 3 failed) results as current proof. Add-on protective-stop resize, residual SELL protection, and the independent `portfolio_trader.py`/`trader.py` and Android mutation domains remain P0.
- PR #51 stays Draft/open/unmerged, based on main `04da5f9c09572e99ff310be3a1908179165e528c`. No live trades or Android runtime tests were performed.
