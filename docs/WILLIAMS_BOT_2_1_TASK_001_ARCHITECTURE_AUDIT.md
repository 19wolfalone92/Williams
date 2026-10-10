# WILLIAMS BOT 2.1 — TASK 001
## Deep Architecture Audit & System Contracts

**Baseline main SHA:** 04da5f9c09572e99ff310be3a1908179165e528c  
**Working branch:** audit/williams-2.1-task-001-architecture  
**Scope:** Binance Spot Testnet, Python service path and Android standalone runtime. Futures modules remain isolated.  
**Safety:** no Mainnet request and no real order was submitted by this audit. Tests use fake/offline exchange responses.

> TASK 001 completion is not a whole-bot release approval. The final section records the result on the exact tested branch commit. Spot Testnet execution and restart recovery remain separate release gates.

## 1. Baseline and change control

- At audit start, main resolved to 04da5f9c09572e99ff310be3a1908179165e528c.
- Work was performed on audit/williams-2.1-task-001-architecture; main was not modified.
- PR #43 remains open and unmerged. It contains potentially useful Task 007 risk changes, including execution_barrier.py, risk_engine.py, equity_breaker.py, db.py and campaign_execution.py. It was not blindly merged because its code overlaps with this task and must be reconciled by file/commit and retested together.
- PR #44 remains open and unmerged; it targets audit/williams-2.1-futures-safety, not main. Its reported campaign CI run on head dbe755a942276ac3b369afd47d424fff48bbb608 failed. Its Williams indicator/stop fixes need a separate source/commit review; this architecture branch does not automatically absorb them.
- The main-branch release contract was inconsistent: ci_release_gate.py expected 4.24.0, while server.py and Android app metadata use 4.25.0. The branch aligns the release gate to 4.25.0.
- Baseline CI at main SHA 04da5f9c09572e99ff310be3a1908179165e528c reported Python checks successful but Android build/static validation failed (run 37966807562). Passing Python tests alone therefore did not establish a releasable Android APK.

## 2. Actual module and state ownership map

### Backend / Python

~~~text
Binance Spot REST / market stream
    ├── candle/history adapters ──> market frames / persisted market history
    └── account, order, order-list and fill queries ──> reconciliation

MultiTimeframeContextService
    └── computes TFMarketContext ──> shared ContextCache
                                      ├── Trader
                                      └── ExecutionBarrier snapshot
Strategy / Williams signal calculation
    └── digital_williams_core.py / williams_signals.py / strategy.py
        └── SignalSpec / hypothesis + context versions
            └── CampaignEngine / PendingSignal
                └── CampaignExecutionService
                    ├── persist campaign + entry reservation in SQLite
                    └── ExecutionBarrier
                        └── binance_client.py
                            └── Binance Spot REST mutation endpoint

Fills / user-stream events / REST order facts
    └── execution accumulator + db.py
        ├── durable execution intents/events
        ├── order and fill records
        ├── campaign/position projections
        └── reconcile on recovery before new risk
~~~

The inspected server.py wiring creates the shared context_cache, injects it into MultiTimeframeContextService, and passes that same object into the Trader created by ControlState.ensure_trader. This branch adds an integration regression for object identity. Direct construction of Trader without dependency injection still creates a fallback cache, so production construction must continue to go through the injection path.

**Canonical Python open path (campaign mode):**

1. Scanner/controller creates candidate and risk proposal.
2. MultiPositionTrader.execute_campaign chooses a canonical SignalSpec.
3. Unknown symbols are reconciled against open orders, order history and base-asset inventory; a missing state is not converted to FLAT by default.
4. CampaignExecutionService.arm_initial_entry or arm_add_on persists campaign/intent state and supplies context versions, timeframe, campaign ID and signal ID.
5. ExecutionBarrier.execute takes the shared context lock, persists a PENDING intent, verifies context identity/version/freshness and state contracts, performs caller pre-submit checks, then allows the adapter callback.
6. binance_client.py sends the Spot order.
7. Order status/fills are confirmed by exchange queries and stored; only exchange facts may establish the live position.
8. Protection, campaign trailing and exits use the same barrier; ambiguous outcomes remain in reconciliation.

**Legacy Python path:** Trader still has a single-symbol execution path and MultiPositionTrader.execute still contains a legacy score-only route when campaign mode is disabled. Its entry is now forced through the same barrier with no canonical context versions, so it fails closed rather than falling back to a direct MARKET BUY. Exit/OCO methods are routed through the injected barrier. This is deliberately not treated as a replacement Williams entry system.

### Android standalone path

~~~text
TradingForegroundService / StandaloneRuntime worker
    └── REST + stream/history warmup and candidate scan
        └── Williams CampaignSignalN / PendingEntry
            └── savePersistedState + ExecutionGate reservation
                └── signedPost / signedDelete
                    └── signedRequest (single mutation boundary)
                        ├── authorizeExchangeMutation
                        ├── TradingAuditStore.beginMutationIntent (SQLite)
                        ├── performSignedRequest
                        └── persist exchange outcome or UNKNOWN
                            └── recovery / reconcile before new BUY
~~~

The Android UI talks to the local runtime and is not an authoritative order/position store. The runtime's SharedPreferences projections are restart hints; Binance order/account facts, read back over REST/user stream, are authoritative. TradingAuditStore stores orders, executions, REST calls, state transitions and, on this branch, a durable mutation_intents journal.

### Close / protection path

- Python: campaign monitor proposes structural stop; CampaignExecutionService cancels/replaces protection or submits a campaign exit through ExecutionBarrier; Binance response/order history is reconciled before local campaign state is finalized.
- Android: manageWilliamsStops, campaignExitMarket, manual sell, protection replacement and emergency unprotected exits call signedPost/signedDelete; these converge in signedRequest. The central gate allows explicitly risk-reducing SELL/cancel/protection mutations while refusing new BUY exposure under reconciliation.
- A PendingSignal/campaign reservation is an intention, not an order or fill. An order acknowledgment is not a fill. A local position projection is not proof of Binance inventory.

## 3. Write / mutation surface inventoried

### Python Spot client

The exchange adapter's write methods are concentrated in binance_client.py:

| Mutation | Spot REST route | Required owner / control |
|---|---|---|
| Submit standard/conditional order | POST /api/v3/order | ExecutionBarrier caller; unique client order ID; persisted intent |
| Cancel/replace | POST /api/v3/order/cancelReplace | execution/cancel contract; must reconcile non-atomic outcome |
| Cancel order | DELETE /api/v3/order | managed order ID/client ID; barrier on active paths |
| Cancel all symbol orders | DELETE /api/v3/openOrders | dangerous bulk primitive; no active path is approved to bypass managed-order reconciliation |
| Create OCO protection | POST /api/v3/orderList/oco (adapter compatibility methods may wrap it) | protection path, durable IDs and exchange confirmation |
| Cancel OCO | DELETE /api/v3/orderList | managed order-list ID/client ID and subsequent reconciliation |

The client is Spot-specific: the inspected implementation uses /api/v3/*; this branch adds a regression preventing accidental introduction of /fapi/ or /dapi/ paths into that adapter. Futures code and its API base paths must remain a separately configured adapter, not a mode flag that can silently redirect the Spot order path.

### Android REST writes

At the HTTP transport level, performSignedRequest is the only location that constructs OkHttp POST/DELETE requests. signedRequest is now the mandatory gate above it; all unrecognized mutation routes or unsupported methods fail closed. No PUT writer was found in the inspected Android transport. Each accepted mutation must first insert a durable row into mutation_intents; inability to persist the intent prevents network I/O. Transport ambiguity or outcome-persistence failure sets RECONCILE_REQUIRED.

## 4. Confirmed defects and branch changes

| ID | Severity | Evidence before fix | Branch correction |
|---|---|---|---|
| A-01 | P0 | ExecutionBarrier._persist swallowed SQLite errors; execution could continue after a failed write of the intent/outcome. | Persistence is mandatory. PENDING failure blocks before submit; failure persisting the post-submit outcome raises an explicit reconciliation error. |
| A-02 | P0 | _validate defaulted absent global position_state to FLAT and absent per-symbol state fell back to global FLAT. | Missing state means UNKNOWN. An explicit per-symbol state is mandatory; global FLAT does not prove a symbol is FLAT. |
| A-03 | P0 | Validation only special-cased purpose ENTRY; CAMPAIGN_ENTRY/CAMPAIGN_ADD_ON could bypass required context and permission-version checks. | Every non-risk-reducing entry/add-on requires non-empty versioned context, an explicit permission timeframe, freshness, matching version and LONG permission. Campaign intents also require persisted campaign/signal identity and valid symbol/campaign states. |
| A-04 | P0 | Context service assigned candle_close_time_ms=candle_open_time_ms, so the freshness contract used the wrong timestamp. | Publishes actual frame close time where available; known closed-bar intervals have a conservative fallback; unknown intervals remain unverifiable and block entries. |
| A-05 | P0 | portfolio_trader.py contained emergency SELL, OCO create/cancel, manual SELL and legacy score-only BUY calls outside the canonical barrier. | Active direct call sites route through a common barrier wrapper; missing barrier blocks mutation. Score-only legacy BUY is rejected by absence of canonical context evidence rather than submitted as MARKET. |
| A-06 | P0 | Android direct signedPost/signedDelete calls were not globally guarded by a single write policy; legacy order code remained behind guard clauses. | Central signedRequest authorizes route/side/type/IDs and journals each mutation. Dead legacy order-intent and direct MARKET-BUY bodies are removed. |
| A-07 | P0 | Android had no durable per-mutation journal proving an attempted write before transport and preserving ambiguous outcomes. | SQLite mutation_intents table and mandatory pre-write/after-response writes added; unresolved mutation state blocks new entries and is surfaced in diagnostics. |
| A-08 | P1 | CAMPAIGN_ADD_ON intent omitted permission_interval. | Add-on intent supplies signal.timeframe. |
| A-09 | P1 | Explicit reconciliation barrier repair checked orders/history but not unexplained base-asset inventory before declaring FLAT. | Stale barriers remain in place when non-dust unmanaged base inventory exists; new-symbol preflight checks orders, bot order history and inventory before persisting FLAT. |
| A-10 | P1 | Python release gate expected 4.24.0 while server/Android used 4.25.0. | Gate changed to 4.25.0; regression added. |
| A-11 | P2 | Duplicate order-state import/transitions and duplicate response handling obscured FSM semantics. | Duplicate import, transition/assignment and duplicate response branch removed. |
| A-12 | P1 | Risk-reducing protection/cancel actions and entry admission shared over-broad uncertainty blocking. | Explicit SELL/CANCEL/protection/exit intents do not use entry permission gates; new BUY/add-on stays blocked by UNKNOWN/RECONCILE_REQUIRED. This exception must remain limited to actual risk-reducing intents. |
| A-13 | P0 | The generic Python barrier could treat a SELL with a permissive `allow_short` context as a new entry, despite this execution profile being Spot-only. | Any non-risk-reducing SELL is rejected at the barrier; explicit exit/protection SELL and cancel purposes use the separate reducing-risk contract. |
| A-14 | P1 | Malformed context-version data could throw during validation after PENDING intent persistence without a normalized BLOCKED result. | Validation exceptions are converted to a durable BLOCKED result; if even that persistence fails, the function returns a no-submit failure and never invokes the exchange callback. |

## 5. Contracts that the code now enforces

1. **Market context identity:** server.mtf_service.cache is server.context_cache, and that exact cache is injected into Trader/execution. Market context is versioned and time-stamped from a closed candle.
2. **Fresh context:** entry/add-on intent must include a version for every required interval and a permission interval present in that same map; the current cache must have those exact versions, close timestamp must be verifiable and within timeframe freshness bounds, and permission context must allow the requested direction.
3. **Single mutation door:** Python Spot intents use ExecutionBarrier; Android mutating HTTP methods use signedRequest → authorizeExchangeMutation → durable journal → performSignedRequest. A bypass path is a blocker.
4. **Durable intent before I/O:** failure to persist PENDING/mutation intent means no exchange request is made. Failure to persist an exchange outcome means UNKNOWN/reconciliation, never a retry invitation.
5. **Exchange-confirmed state:** PENDING_SIGNAL, ENTRY_PENDING and order acknowledgments are not fills. Executed quantity/status and managed inventory are confirmed by exchange REST/user-stream/recovery.
6. **UNKNOWN:** absent per-symbol/global/campaign facts are not FLAT/CLEAN. Entries are refused until authoritative reconciliation makes state explicit.
7. **Risk-reducing exception:** cancellation, protection, trailing replacement and explicit SELL exits remain available under reconciliation, while they still require a durable intent and controlled route. This is not permission for arbitrary SELL orders or blind retries.
8. **Restart:** local projections are reconciled against Binance orders, open order lists, order history and inventory. Any ambiguous write remains blocked until supported by authoritative evidence.
9. **Spot/Futures separation:** Spot adapter contains only Spot endpoints. Futures adapter/configuration remains separate; no automatic conversion from Spot intent into Futures order is permitted.

## 6. Regression coverage added or updated

- test_task001_architecture_contracts.py: shared cache object identity; fresh/versioned context required; stale/missing context blocks before callback; UNKNOWN and RECONCILE_REQUIRED block new entries; risk-reducing exit remains admitted under reconciliation; missing global state and missing per-symbol state cannot be inferred as FLAT; failed pre-intent persistence prevents submit; malformed context versions become BLOCKED; unsupported non-risk-reducing Spot SELL blocks; PendingSignal is not an order/fill/position; AST checks for backend mutation call sites; Android mutation boundary/journal recovery check; Spot/Futures route isolation; missing campaign state blocks.
- test_campaign_execution.py: fake context now provides a real current close timestamp and signal context versions; fixture explicitly models completed clean reconciliation.
- test_production_architecture.py: stale-context and execution-lock tests now use a durable test DB and verifiable timestamps.
- .github/workflows/python-ci.yml and .github/workflows/campaign-ci.yml: include the new architecture contracts test.

The tests must be evaluated on the final commit after CI completes; source edits and workflow configuration do not themselves prove the suite passes.

## 7. Evidence gaps / remaining blockers

- Do not accept TASK 001 until campaign/Python tests, backend tests, Android build/lint and release-gate checks complete successfully on the **same exact final SHA**. A pending workflow is not a pass.
- Android journal recovery now queries the exact Binance order, order-list, or both cancel/replace legs using persisted identifiers. It only marks a journal row RECONCILED when a matching exchange response contains authoritative IDs and a recognized state. Unsupported/malformed routes and failed lookups stay unresolved and keep entries blocked. The code-level query path is present; its Android build and crash/restart behavior still require successful CI and Testnet evidence.
- Spot Testnet must separately exercise: initial conditional LONG, partial/final fill, protection creation, stop replacement, manual/emergency exit, commission/realized-result reconciliation, process kill/restart at each mutation boundary, and unknown-response recovery. This report does not claim those exchange E2E scenarios were run.
- Verify each response type and cancellation outcome from Binance Testnet: cancelReplace and OCO operations are non-atomic, and an HTTP success alone is not proof the final order-list state is what the caller expects.
- Run an exact scan of all repository call sites against the mutation inventory before merging; the wrapper tests enforce key paths but are not a proof from an arbitrary dynamically constructed call or external extension.
- Re-review PR #43 and #44 at the file/commit level and reconcile any overlapping changes instead of merging either wholesale.
- Confirm runtime config and APK default to Testnet and do not silently allow Mainnet; do not enable Mainnet in TASK 001.
- Keep Williams strategy-canon audits (four books, figure captions, non-repainting fractals, Alligator timing, WM1/WM2/WM3 provenance) and live Testnet trading proofs separate from architecture-task completion.

## 8. Acceptance record

**Status at report creation:** PASS WITH BLOCKERS — architecture corrections and offline regressions are present on the task branch, but exact-final-SHA CI and Spot Testnet end-to-end recovery have not yet been proven.

**Final tested commit SHA:** pending final CI result.  
**Final task status:** must be updated to PASS, PASS WITH BLOCKERS or FAIL after checks finish.  
**Whole-bot release status:** not approved by TASK 001 alone.


---

## 9. TASK 001 follow-up — exact-SHA CI and mutation-path review

**Reviewed source SHA:** `6f6839c8419795f50bf99c71f5be3175d6895dcd`  
**PR:** [#47](https://github.com/19wolfalone92/Williams/pull/47)  
**Main remains:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**No PR was merged; no Mainnet request/order was sent.**

### 9.1 CI evidence for the reviewed SHA

All of these runs identify the exact head SHA above:

| Workflow | Run | Result | Concrete evidence |
|---|---|---|---|
| Build Android APK | [38080627301](https://github.com/19wolfalone92/Williams/actions/runs/38080627301) | **SUCCESS** | Release contract gate PASS; Android unit-test task successful; debug and unsigned release APKs built; APK metadata and debug signature verified; checksums generated. |
| Python backend tests | [38080627318](https://github.com/19wolfalone92/Williams/actions/runs/38080627318) | **SUCCESS** | Release gate, Python compile, static safety audit and offline tests all successful. Log: 165 passed, 4 warnings. |
| Williams Campaign CI (push) | [38080625240](https://github.com/19wolfalone92/Williams/actions/runs/38080625240) | **SUCCESS** | Python tests 176 passed, 4 warnings; Android build, lint and debug unit-test task successful; debug APK artifact uploaded. |
| Williams Campaign CI (PR) | [38080627398](https://github.com/19wolfalone92/Williams/actions/runs/38080627398) | **SUCCESS** | Python tests 176 passed, 4 warnings; Android build, lint and debug unit-test task successful; debug APK artifact uploaded. |

APK metadata recorded by the Android workflow:
- Debug: package `com.williamsbot.debug`, versionCode `1695`, versionName `4.25.0-debug`.
- Release: package `com.williamsbot`, versionCode `1695`, versionName `4.25.0`; artifact is explicitly **unsigned**.
- Debug APK SHA-256: `27c1f07f6d91d5607b3105e87726d82206f58eb1c1ebd2969a680854fadfb120`.
- Unsigned release APK SHA-256: `abd1403bfd6d6836e244eb80d6121c7e7e8b557ec7a7af7397a838539bb3c92c`.
- Installable debug artifact ID: `11680606330`. The separate campaign debug artifact ID is `11679723954`.

The previous failures were specific and corrected before these successful runs: an OCO regression omitted the required `required_context_versions` argument; Android lint rejected Gradle 8.14.5 in favor of 8.14.6. These successes establish CI/build behavior for the reviewed SHA only; they do not establish live Testnet execution or restart safety on a device.

### 9.2 Mutation-path inventory

This is the reviewed call-path inventory for known repository-owned Spot mutation surfaces. Dynamically assembled requests, third-party extension code and every future call site still require repository-wide re-audit before merge.

| Call path / mutation | Required checks before network mutation | Durable intent before I/O | Unknown/ambiguous result | Reconciliation | Contract evidence |
|---|---|---|---|---|---|
| Python campaign entry: scanner/signal → `CampaignExecutionService` → `ExecutionBarrier.execute` → `BinanceSpotClient.order_safe` → POST `/api/v3/order` | Signal/context versions, freshness, direction, expiry/trigger, symbol/global/campaign state, risk and exchange filters; serialized barrier | Campaign `ENTRY_PENDING`, client order ID and execution intent persisted before submit | Timeout/connection/5xx is not blindly retried; state remains unknown/reconcile-required | `reconcile_pending_entries()` looks up persisted client/order IDs and adopts only exchange evidence | `test_task001_architecture_contracts.py`; `test_campaign_execution.py`; `test_execution_architecture.py`; `test_binance_json_shapes.py` |
| Python protection/OCO create: campaign execution → barrier → client OCO adapter → POST `/api/v3/orderList/oco` or compatible OCO route | Must be an explicit protection intent with managed campaign/symbol context; Spot-only side/order contract; validate list response and child IDs/states | Execution intent plus campaign/order identifiers | Missing/malformed list IDs or child states cannot be treated as a fill; outcome must remain unresolved | Query order list/children and confirm authoritative status before adopting protection | `test_task001_architecture_contracts.py::test_oco_list_response_is_validated_without_confusing_it_with_a_position_fill`; Testnet OCO response vectors still needed |
| Python cancel order: campaign/protection lifecycle → barrier → client cancel → DELETE `/api/v3/order` | Managed order identity and explicit cancel/risk-reducing purpose; no untracked blind retry | Intent persisted before cancel | Timeout or contradictory cancel result remains unknown | Query order/client ID and reconcile actual status/fills | `test_execution_architecture.py`, `test_campaign_execution.py`; explicit cancel-timeout/restart test still needed |
| Python cancel/replace: campaign protection → barrier/client → POST `/api/v3/order/cancelReplace` | Validate old order, replacement parameters, filters and risk; preserve both leg outcomes | Intent must identify old order and replacement client ID | Non-atomic two-leg response cannot be collapsed into one success boolean | Query both old and replacement order IDs; keep blocked if either leg unresolved | Static boundary and campaign tests; explicit matrix test for old-cancel success/new-order failure and inverse is still needed |
| Python cancel all symbol orders: client primitive → DELETE `/api/v3/openOrders` | Must not be invoked as an untracked shortcut; active path must prove managed scope and account for all affected orders | Required for every approved mutation; if no per-order durable intent, path is not approved for production use | Ambiguous bulk cancellation remains unresolved | Re-query open orders and order history per managed order before clearing barriers | Inventory identifies this dangerous primitive; a dedicated allow/deny regression test and call-site proof are still required |
| Python cancel OCO: managed campaign protection → barrier/client → DELETE `/api/v3/orderList` | Exact managed order-list ID/client ID; explicit cancel purpose | Durable intent before call | Timeout remains unknown | Query order list and child orders; confirm actual terminal state | Mutation-boundary regression plus required explicit OCO-cancel failure/restart vector |
| Android order/OCO/cancel-replace: runtime campaign/protection/manual exit → `signedPost`/`signedDelete` → `signedRequest` → `authorizeExchangeMutation` → SQLite mutation journal → `performSignedRequest` | Testnet host guard, route/method allowlist, side/type/IDs, entry gate and reconciliation barrier; unknown route fails closed | `TradingAuditStore.beginMutationIntent` must commit before transport | Transport or result-persistence ambiguity writes UNKNOWN/RECONCILE_REQUIRED and blocks exposure increase | `reconcileUnresolvedMutationIntents()` looks up persisted order/order-list/replace identifiers and requires authoritative response evidence | `test_task001_architecture_contracts.py` checks one POST and one DELETE primitive, guarded transport and journal/reconcile symbols; Android CI passes. Crash/restart against Testnet remains untested |
| Android configuration/control API POST/DELETE | Local API handler and config/control validation; these are not themselves Binance order writes | Config/control persistence is separate from exchange mutation intent | Runtime state changes must not be mistaken for Binance order outcomes | Runtime recovery must query Binance before clearing execution barriers | Route inventory and Android unit/static checks; device-level control/restart test remains needed |

**Boundary interpretation:** Python's exchange adapter contains Spot `/api/v3/*` mutation endpoints; Android signed REST mutations converge on the guarded `signedRequest` transport. The AST/static checks are regression guards, not a formal proof against arbitrary dynamic calls. The bulk-cancel primitive and non-atomic cancel/replace deserve explicit deny/allow tests before this task can be accepted.

### 9.3 Recovery tests: proven versus missing — follow-up

Deterministic offline regressions added in this follow-up:

1. **Post-submit SQLite outcome-write failure.** A fake exchange accepts one order, then the injected SUBMITTED SQLite write fails. The barrier best-effort persists AMBIGUOUS, sets global and symbol state to RECONCILE_REQUIRED, and raises a non-retryable execution error. A startup recovery path performs a read-only lookup by the stable client order ID; it does not submit a second POST. If the database is too broken even to persist the ambiguity, the durable PENDING row is treated as unresolved and blocks another mutation.
2. **Cancel/replace response matrix.** Regression cases cover both legs succeeding, cancellation succeeding while replacement fails, replacement succeeding while cancellation fails, timeout, and malformed response. Only a response with authoritative identifiers/status for both legs is admitted as submitted; all other cases stay ambiguous and require reconciliation. **Restart resolution of campaign stop replacement remains blocked** because the Python durable intent schema does not yet store the old target order ID independently from the replacement client ID.
3. **Bulk cancel.** BinanceSpotClient.cancel_open_orders is disabled and raises before network I/O. The Android mutation allowlist does not authorize DELETE /api/v3/openOrders; its openOrders usages reviewed here are reads. Static regression checks cover the known Python wrapper and Android route guard. This is a fail-closed restriction, not proof against every possible dynamically constructed request.
4. **OCO timeout/restart.** Recovery now verifies the order-list identity/status and then queries both child orders by their exchange order IDs. Creation/cancel timeout tests prove read-only lookup of the list and both children, and the portfolio latch remains RECONCILE_REQUIRED after the lookup. Missing or malformed child evidence leaves the intent unresolved; no duplicate protection is sent by recovery.
5. **Partial fill, protection, and fees.** Campaign fill materialization reads actual exchange trade records, validates their total quantity against executedQty, converts commission assets to quote value, and no longer assumes fees are zero. Protection is installed for the actual executed quantity before the ancillary commission lookup. The fake-exchange partial-fill regression checks executed quantity, protective-stop quantity, and the known synthetic commission. **A combined partial-fill + process restart + real exchange fee reconciliation scenario is not yet proven end-to-end.**
6. **Android process death.** The runtime recovery path now queries OCO child orders as well as the list. Source/static tests and CI are not hardware evidence. Killing the Android process at journal-commit, response-before-outcome-commit, and in-reconciliation points on a physical device remains NOT VERIFIED.
7. **Spot Testnet.** No bounded Spot Testnet order-create/cancel/protection/restart run is claimed. All new recovery regressions are offline fake-exchange tests; no Mainnet requests or real orders were sent.

The full mutation inventory remains code/static evidence, not a formal proof against dynamically generated routes or every production runtime state. CI on the final report SHA is tracked separately from the earlier successful source-SHA runs in §9.1; earlier green CI is not evidence of complete recovery safety.

### 9.4 PR #43 / #44 overlap reconciliation

Neither PR was merged. The overlap review is contract-based; do not cherry-pick whole PRs.

- **PR #43 (risk and execution safety):** overlaps on `execution_barrier.py`, `campaign_execution.py`, `db.py`, `ci_release_gate.py`, `risk_engine.py`, `equity_breaker.py`. Preserve its numeric-input validation, exchange-filter fail-closed behavior, daily-loss breaker and reserved-risk accounting only after confirming they coexist with Task 001's mandatory durable intent, UNKNOWN semantics and single execution door. PR #43's older barrier changes must not overwrite the current Task 001 fail-closed persistence and context-version checks.
- **PR #44 (Williams/Futures parity):** overlaps on `StandaloneRuntime.kt`, `campaign_execution.py`, `campaign_engine.py`, `campaign_model.py`, `execution_barrier.py`, `db.py`, `risk_engine.py`, `market_context.py`, `mtf_context_service.py`, `portfolio_trader.py`, `server.py`, `strategy.py` and tests. Its Futures-specific engine/policies must remain isolated. Strategy/indicator/WM1/WM2/WM3 changes and source-book parity belong to TASK 004, not duplicated here. Only shared execution-contract changes should be reconciled into this branch after per-file diff and test review.
- PR #44's current reported head in its PR metadata is `dbe755a942276ac3b369afd47d424fff48bbb608`; its earlier Campaign CI was reported failed. That is not evidence that its current code should be merged or rejected wholesale; rerun its checks at the current head before considering any transfer.

### 9.5 Acceptance decision

**TASK 001 status: FAIL — NOT ACCEPTED.**

The prior four required CI runs on 6f6839c8419795f50bf99c71f5be3175d6895dcd succeeded (links and counts are preserved in §9.1). They do not establish recovery safety. The follow-up introduced code and deterministic tests, and the exact final report SHA must have its own Python backend, Williams Campaign CI, and Android APK results recorded before treating the branch CI as green.

Acceptance remains blocked because:

- campaign stop cancel/replace restart recovery cannot prove both legs until the old order ID is durably stored alongside the replacement client order ID;
- a combined partial-fill + restart + protection + actual commission-reconciliation scenario is not yet demonstrated end-to-end;
- OCO partial execution followed by restart and exposure/fee accounting still needs a full lifecycle regression, beyond list/child-state lookup;
- Android physical-device process-death tests are NOT VERIFIED;
- bounded Spot Testnet recovery tests with dedicated test credentials have not been run;
- static route inventory is not a complete dynamic proof of every possible mutation path.

**Next acceptance gate:** close the cancel/replace durable-identity gap, add the remaining OCO/partial-fill restart cases, obtain the exact-SHA CI results, then run the bounded Spot Testnet matrix and physical-device Android crash/restart matrix. Keep PR #47 open and do not merge. main remains unchanged.


---

## 9.6 TASK 001 deterministic recovery follow-up — 2026-10-11

**Test-source parent SHA:** `0fec5080aff5f2fd34dc29b6cb6bf6fd8537269c`  
**Recovery regression test commit:** `442c6d7668ccce13ab86d1d012b1a7e389f91f5d`  
**Branch:** `audit/williams-2.1-task-001-architecture`  
**PR:** [#47](https://github.com/19wolfalone92/Williams/pull/47), open, draft, not merged.  
**main:** remains `04da5f9c09572e99ff310be3a1908179165e528c`.

### Added deterministic cases

- Added parameterized recovery tests for OCO child descriptors with an unknown state, missing state, or missing child ID. These assert the unresolved intent remains ambiguous, both child lookups are attempted where possible, the reconciliation latch remains set, and recovery never creates duplicate protection.
- Added a cancel/replace restart test proving the current durable schema cannot infer the old leg from a replacement-only client ID. Recovery must leave the intent unresolved and must not POST another cancel/replace.
- Tests use fake exchange implementations and local SQLite. They submit no real exchange orders and do not call Binance Mainnet.

### Current evidence and CI

The prior verified CI results on SHA `e7017ed27b2167f6fd8feaf428cf1f7255ee9d13` are preserved:
- [Python backend tests — run 38081059348](https://github.com/19wolfalone92/Williams/actions/runs/38081059348) — SUCCESS.
- [Williams Campaign CI — run 38081059304](https://github.com/19wolfalone92/Williams/actions/runs/38081059304) — SUCCESS.
- [Build Android APK — run 38081059292](https://github.com/19wolfalone92/Williams/actions/runs/38081059292) — SUCCESS.

These are historical CI evidence only and are not proof of recovery safety. On regression-test SHA `442c6d7668ccce13ab86d1d012b1a7e389f91f5d`, the current runs were started and are pending at the time of this report update:
- [Python backend tests — run 38088218639](https://github.com/19wolfalone92/Williams/actions/runs/38088218639).
- [Williams Campaign CI — run 38088218627](https://github.com/19wolfalone92/Williams/actions/runs/38088218627).
- [Build Android APK — run 38088218651](https://github.com/19wolfalone92/Williams/actions/runs/38088218651).

The final report-only commit will have a different SHA; CI must be checked again against that exact final SHA before reporting final CI status. Do not transfer green results across SHAs.

### Acceptance remains blocked

**TASK 001 status: FAIL — NOT ACCEPTED.**

Still not proven:
1. End-to-end cancel/replace recovery with both old and replacement order identities durably persisted and independently reconciled.
2. Full OCO partial-fill → restart → real exposure/protection/commission reconciliation lifecycle.
3. Combined partial fill + process restart + protection quantity + actual fee reconciliation end-to-end.
4. Physical Android process-death tests. Hardware evidence: **NOT VERIFIED**.
5. Bounded Spot Testnet mutation/restart matrix with dedicated Testnet credentials. **NOT RUN / NOT VERIFIED**.
6. Complete dynamic proof of every possible production mutation route. Bulk `DELETE /api/v3/openOrders` remains disabled in Python and unapproved in Android production transport.

No Mainnet requests or orders were used. Do not merge PR #47.
