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


---

## 10. TASK 001.1 — Cancel/Replace, OCO & Crash-Recovery Closure (2026-10-11)

**Review baseline SHA:** `e96f5a0f47b1aea0460140041adbd04c8c45f6a0`  
**PR:** [#47](https://github.com/19wolfalone92/Williams/pull/47) — remains open Draft, not merged.  
**Base/main SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**Review result:** **FAIL — NOT ACCEPTED.** This review did not claim closure or change the production recovery contract.

### 10.1 Pre-change verification

- PR metadata confirms head `e96f5a0f47b1aea0460140041adbd04c8c45f6a0`, base `main` at `04da5f9c09572e99ff310be3a1908179165e528c`, state open, draft=true, merged=false.
- Reviewed current branch source for `campaign_execution.py`, `execution_barrier.py`, `db.py`, `binance_client.py`, and `test_task001_recovery_regressions.py`.
- The existing SQLite `execution_intents` schema stores the generic `client_order_id`, but does not independently store a cancel/replace operation ID, old target order ID, replacement client ID as a distinct field, or per-leg result states.
- `CampaignExecutionService.replace_structural_stop` passes `existing_order_id` to the adapter and creates a replacement client ID, but the durable generic intent does not retain the old order identity. It accepts `NOT_FOUND` in its post-call branch even though the barrier's authoritative cancel/replace response validator requires both result values to be `SUCCESS`; this path requires reconciliation and must not be interpreted as proof that both legs succeeded.
- `ExecutionBarrier.reconcile_unresolved_intents` intentionally leaves cancel/replace and standalone cancel intents unresolved because it cannot independently query both legs from the current durable schema. This is fail-closed, but not closure.
- OCO recovery validates list identity/status and queries children. That alone does not prove financial and position reconciliation or that the remaining protective quantity matches current exposure.

### 10.2 Existing deterministic regression coverage

The branch's `test_task001_recovery_regressions.py` includes:
- injected SQLite failure after a fake exchange accepted a POST, with assertions against a blind second POST and with reconciliation required;
- cancel/replace response authority checks and a response matrix;
- cancel/replace timeout remains ambiguous;
- OCO recovery checks list/child states and malformed/unknown child evidence;
- restart recovery refuses to infer the old cancel/replace leg from replacement-only identity.

These are offline fake-exchange tests. This report does **not** claim that every requested SQLite fault point, two-process race, or complete OCO/partial-fill/fee/restart lifecycle has been covered.

### 10.3 Required matrix — current evidence, not an acceptance claim

| Scenario | Expected safe result | Evidence on reviewed SHA | Status |
|---|---|---|---|
| Cancel succeeds + replacement succeeds | Persist both leg identities/results; reconcile both | Response authority tests exist; durable old-leg identity is missing | BLOCKED |
| Cancel succeeds + replacement rejected | Preserve old canceled/new rejected; reconcile exposure/protection | Matrix logic is covered at response-validation level | PARTIAL |
| Cancel fails + replacement succeeds | Keep unresolved; query both legs | Matrix logic is covered at response-validation level | PARTIAL |
| Old order filled during cancel | Preserve fills and resulting exposure; do not infer canceled | No complete two-leg/fill-reconciliation proof | NOT VERIFIED |
| Old order partially filled | Reconcile executions/fees/remaining exposure and protection | Existing partial-fill tests do not prove the combined crash lifecycle | NOT VERIFIED |
| Timeout or lost response after exchange acceptance | No blind mutation retry; query exchange by durable identifiers | Timeout/ambiguous regression exists | PARTIAL |
| Malformed response | Keep UNKNOWN/RECONCILE_REQUIRED | Authority validation tests exist | PARTIAL |
| Process death between operations or after response before DB write | Recover each leg independently | Old leg identity is not durable | BLOCKED |
| Repeat recovery after saved result | Idempotent facts/events and no duplicate mutation | Some idempotency assertions exist; concurrent two-process proof absent | NOT VERIFIED |
| OCO list and both children found | Validate list + both children + fills + fees + exposure/protection | List/child state lookup exists | PARTIAL |
| OCO child missing/unknown ID or status | Remain unresolved; never recreate protection blindly | Parameterized regression exists | PARTIAL |
| OCO partial fill / sibling auto-cancel / restart | Reconcile executions, fees, actual remaining exposure and protection | No complete end-to-end test | NOT VERIFIED |
| SQLite failures at every persistence boundary | Durable unresolved state or fail closed; no blind retry | One post-submit outcome-write failure is covered | PARTIAL |
| Two concurrent recovery instances | One idempotent reconciliation; no duplicate event/mutation | No dedicated two-connection/two-process regression verified | NOT VERIFIED |

### 10.4 Financial and position reconciliation contracts

The existing source uses the campaign fill/commission paths and durable execution records; no separate reconciliation subsystem was introduced here. The current evidence is insufficient to certify the requested independent dimensions:
- order state;
- financial state (all trade IDs, executed quantities, prices, quote amounts, commission amount and commission asset);
- position state (exchange balances and net base-asset delta);
- protection state (live OCO/list children and quantity matching actual remaining exposure);
- recovery complete only after all required dimensions agree.

Missing commission data must remain unknown, not be converted into zero. A reconciled single intent is not authority to clear the global or symbol reconciliation latch. The full integration assertion across these dimensions remains a required test.

### 10.5 Exact-SHA CI evidence available at review time

For SHA `e96f5a0f47b1aea0460140041adbd04c8c45f6a0`:
- [Python backend tests — run 38088280886](https://github.com/19wolfalone92/Williams/actions/runs/38088280886): **SUCCESS**, all job steps passed including release gate, compile, full static safety audit and offline tests.
- [Williams Campaign CI — run 38088280869](https://github.com/19wolfalone92/Williams/actions/runs/38088280869): **SUCCESS**, Python tests and Android build/static-check jobs passed.
- [Build Android APK — run 38088280868](https://github.com/19wolfalone92/Williams/actions/runs/38088280868): **IN PROGRESS** at the latest job inspection. Required Android lint and unit-test steps and debug APK build had passed, but release APK, final artifact verification and upload steps had not yet completed. This is not a successful completed workflow.

Any subsequent report commit has a new SHA and requires new exact-SHA checks. Do not transfer these results to a later commit.

### 10.6 External test status and acceptance

- Physical Android process-death/restart: **NOT VERIFIED**.
- Dedicated-credential Spot Testnet cancel/replace + OCO + partial-fill + restart matrix: **NOT RUN / NOT VERIFIED**.
- Mainnet and real order use: **NOT USED**.
- `main`: unchanged.
- PR #47: open Draft, not merged.

**TASK 001.1 status: FAIL — NOT ACCEPTED.** The most important remaining code defect is the lack of durable independent old-order identity and per-leg outcome records for cancel/replace recovery. Do not remove the reconciliation latch or automatically retry mutations to work around this gap. Next implementation work must add a migration-safe durable operation record, write it before network I/O through the existing ExecutionBarrier, independently query old and replacement orders on recovery, and prove idempotency with SQLite fault injection and concurrent recovery tests. Then close the OCO/partial-fill/fee/protection lifecycle and rerun all mandatory workflows on one final SHA.

---

## 11. TASK 001.2 — Durable Cancel/Replace State & Crash-Recovery Closure (2026-10-11)

**Baseline checked before edits:** 216c95dc0cb001a434f525b7ee1241690b5f522a  
**Implementation/test commit before this report update:** 8052953fe8a1b5fbdbd2f30af33038de887232c5  
**Base/main:** 04da5f9c09572e99ff310be3a1908179165e528c  
**PR #47:** open Draft, not merged. **Decision: FAIL — NOT ACCEPTED.**

### 11.1 Pre-change verification

- PR metadata was read live before changes: head was 216c95dc0cb001a434f525b7ee1241690b5f522a, base was main at 04da5f9c09572e99ff310be3a1908179165e528c, state open, draft=true, merged=false.
- Exact-SHA CI for the pre-change head 216c95dc0cb001a434f525b7ee1241690b5f522a completed successfully for Python backend tests, Williams Campaign CI, and Build Android APK. These are baseline-only results and do not apply to the implementation commits.
- Code inspection confirmed the concrete loss: schema v6 execution_intents held one generic client_order_id; it did not durably link the cancel target order ID and replacement client ID or store per-leg states. Startup recovery therefore refused to reconcile either leg.

### 11.2 Changes made

1. **db.py** — schema version raised from 6 to 7; migration-safe CREATE TABLE IF NOT EXISTS cancel_replace_operations added with stable operation/intent IDs, symbol, old exchange order ID, old client ID where known, replacement client ID, separate cancel/replacement states, exchange IDs as learned, executed-quantity fields, contract version, timestamps, reconciliation latch and last reconciliation evidence. Added create/read/list/update methods. Creation is identity-immutable and rejects an operation-ID collision rather than overwriting it.
2. **campaign_execution.py** — replace_structural_stop() now writes the durable two-leg operation record before calling the existing _submit()/ExecutionBarrier path. It records the returned per-leg response separately from the generic intent. If the result write fails after a response, the campaign and symbol remain reconciliation-required and the code raises instead of attempting a second mutation.
3. **execution_barrier.py** — startup recovery loads the durable cancel/replace record and queries the old order by exchange order ID and replacement by stable client order ID independently. It validates symbol, identity and authoritative status. It records observed leg states but deliberately keeps the operation and global/symbol reconciliation latch unresolved until financial, exposure and live-protection reconciliation is separately proven. Legacy ambiguous intents without the new operation record remain fail-closed and are not mutated.
4. **test_task001_recovery_regressions.py** — added deterministic SQLite/fake-exchange coverage for durable two-leg identity, independent restart lookups, no mutation replay, persistent reconciliation latch, and rejection of an operation-identity collision.

### 11.3 Durable contract and state interpretation

| Dimension | Durable field(s) | Meaning |
|---|---|---|
| Operation identity | operation_id, intent_id, contract_version | Stable link to generic ExecutionBarrier intent |
| Original leg | old_exchange_order_id, old_client_order_id, cancel_state, old_executed_qty | Target and last observed cancel/order state |
| Replacement leg | replacement_client_order_id, replacement_exchange_order_id, replacement_state, replacement_executed_qty | Stable replacement identity and last observed state |
| Recovery | reconcile_required, last_reconciled_at, last_reconciliation_json, timestamps | Recovery evidence and explicit unresolved state |

**Safety rule:** a response with two leg statuses is not an atomic exchange transaction. A restart lookup of both orders proves only observed order state. It does not prove unique fills, fee conversion, net base-asset delta, exchange balance agreement, campaign quantity, or current protective quantity. The operation therefore remains reconcile_required=1; this implementation does not clear the global latch based on order lookups alone.

### 11.4 Scenario evidence at this report update

| Scenario | Code/test evidence now available | Status |
|---|---|---|
| Migration from prior DB schema | Table created with IF NOT EXISTS; existing tables are not dropped | PARTIAL — no dedicated populated-v6 migration test confirmed yet |
| Durable old/new identities before mutation | Added in replace_structural_stop() | IMPLEMENTED; needs exact-SHA test confirmation |
| Both legs independently looked up after restart | Added to barrier recovery; new fake-exchange test | IMPLEMENTED; needs exact-SHA test confirmation |
| Missing/legacy operation record | Recovery stays unresolved; no retry | FAIL-CLOSED |
| Lost response / ambiguous mutation | Durable record remains pending; restart is read-only | PARTIAL — no full matrix for every failure point |
| SQLite error at every critical write boundary | Response-persistence failure path explicitly blocks | PARTIAL — not every write boundary has injected-failure coverage |
| Partial/full old-order fill, unique fills and fees | Executed quantity is recorded as evidence, but no complete end-to-end recovery/accounting chain added here | NOT VERIFIED |
| Actual exchange balances vs local position | Not completed by this patch | NOT VERIFIED |
| Live protection quantity and protection repair | Reconciliation latch stays set; full protection proof/repair lifecycle not added | NOT VERIFIED |
| OCO list + child persistence/restart/partial execution | Existing list/child lookup remains; full OCO financial lifecycle not closed | NOT VERIFIED |
| Two independent SQLite connections/processes racing recovery | Read-only exchange recovery repeats lookups safely, but no dedicated cross-process race test added | NOT VERIFIED |
| Exit allowed while entries blocked | Existing gate behavior not re-proven by this patch's new tests | NOT VERIFIED |
| Android physical process death | No device test run | NOT VERIFIED |
| Bounded Binance Spot Testnet | No exchange mutation/restart test run | NOT RUN / NOT VERIFIED |

### 11.5 CI for implementation commits

The implementation/test commit 8052953fe8a1b5fbdbd2f30af33038de887232c5 triggered:
- [Python backend tests — run 38088852965](https://github.com/19wolfalone92/Williams/actions/runs/38088852965)
- [Williams Campaign CI — run 38088852944](https://github.com/19wolfalone92/Williams/actions/runs/38088852944)
- [Build Android APK — run 38088852948](https://github.com/19wolfalone92/Williams/actions/runs/38088852948)

At the time of this report edit, all three were queued; no result is claimed. The report update itself creates another SHA and must trigger/receive its own exact-SHA CI. No earlier green result is transferable to the final SHA.

### 11.6 Acceptance decision and remaining blockers

**TASK 001.2: FAIL — NOT ACCEPTED.**

The primary durability gap has been addressed in code, but this is not full crash-recovery closure. Outstanding blockers:
- no demonstrated complete financial reconciliation over unique fills, commissions (including unknown commission asset/amount), net base-asset change, exchange balances and campaign position;
- no confirmed protective-order/OCO lifecycle reconciliation and repair after cancel succeeds while replacement is rejected or unknown;
- no exhaustive SQLite fault-injection matrix across each persistence boundary;
- no dedicated two-connection/two-process recovery race test;
- no proven populated legacy-schema migration test;
- exact-SHA CI for the implementation and final report SHA still pending at the time of writing;
- physical Android process-death test NOT VERIFIED;
- bounded Spot Testnet cancel/replace/OCO/restart test NOT RUN / NOT VERIFIED.

No Binance Mainnet requests or real trading operations were used. main was not modified. PR #47 remains open Draft and must not be merged.


---

## 12. TASK 001.3 — Financial Integrity / Protection Recovery / Crash Consistency (2026-10-11)

**Starting SHA:** `6bc32bf21cb437b60c2878781000800c6979cd6b`  
**Code SHA before this report update:** `ee559838c9897d4efa68af079cdbe8c6e4ac9a63`  
**Base SHA:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**PR #47:** open Draft, not merged. **Decision: FAIL — NOT ACCEPTED.**

### 12.1 P0-A — prior SHA CI verified

All three requested runs are completed and refer exactly to starting SHA `6bc32bf21cb437b60c2878781000800c6979cd6b`:

| Workflow | Run | Conclusion | Jobs |
|---|---:|---|---|
| Python backend tests | [38088901337](https://github.com/19wolfalone92/Williams/actions/runs/38088901337) | SUCCESS | backend-test: success |
| Williams Campaign CI | [38088901377](https://github.com/19wolfalone92/Williams/actions/runs/38088901377) | SUCCESS | Android build and static checks: success; Python tests: success |
| Build Android APK | [38088901391](https://github.com/19wolfalone92/Williams/actions/runs/38088901391) | SUCCESS | Build APKs: success |

No failing job was reported in those runs. These successes establish the starting revision's CI status only; they do not prove the new changes or financial/protection requirements.

### 12.2 Concrete code findings before TASK 001.3 edits

- `campaign_fills` has no exchange trade ID and no uniqueness constraint; its quantity, price and fee columns are SQLite REAL. It cannot independently guarantee exactly-once exchange-fill accounting.
- `_executed_fee_quote()` previously converted quantity, price and commission to float and used `trade.get("commission", 0)`; a missing commission field could be treated as zero.
- `/api/v3/myTrades` is the available Spot trade-record source in this client. The code path must require the response's stable per-symbol trade `id`, order ID, quantity/price, and explicit commission amount and asset before declaring fee reconciliation complete.
- Current cancel/replace recovery observes two orders but intentionally leaves the global/symbol reconciliation latch unresolved. There is still no unified end-to-end reconciler proving unique fills, fee completeness, net base-asset delta, available balances, Campaign quantity and actual protective coverage.
- The previous three CI runs succeeded; no failure from P0-A required a fix.

### 12.3 Changes in TASK 001.3

1. **`db.py` — schema v8:** added `exchange_fill_ledger`, keyed by `(symbol, exchange_trade_id)`, storing exact decimal text for quantity, price, quote quantity and commission, plus commission asset, raw evidence and explicit `CONFIRMED` / `UNKNOWN` financial state. Re-delivery is idempotent; immutable execution-field conflicts are rejected. An initially unknown fee can be enriched when the same trade is re-read with explicit fee evidence, but confirmed fee fields cannot silently change.
2. **`campaign_execution.py` — fee lookup:** `_executed_fee_quote()` persists each Spot trade's exchange identity before aggregation, uses Decimal arithmetic for quantity/price/commission aggregation and rejects missing trade IDs, missing fee amount, missing fee asset, malformed numbers or quantity mismatch. It no longer defaults absent commission to zero. The legacy return contract remains float because existing Campaign APIs consume float; the exact exchange evidence is retained separately in the ledger.
3. **`test_task001_recovery_regressions.py` — new regression coverage:** exact decimal persistence and deduplication; missing-fee UNKNOWN state and enrichment; conflicting trade-ID rejection; missing commission blocks fee reconciliation; populated legacy-shaped SQLite migration; and two independent SQLite connections racing to record the same exchange trade.

### 12.4 Evidence and limits

| Area | Current evidence | Status |
|---|---|---|
| P0-A starting SHA CI | All three named runs SUCCESS on exact starting SHA | PASS |
| Exchange trade identity | Ledger primary key uses (symbol, exchange trade ID) | IMPLEMENTED; CI pending for current changes |
| Duplicate fill delivery | SQLite uniqueness + independent-connection regression added | TEST ADDED; execution pending |
| Unknown fee amount/asset | Stored as UNKNOWN and fee path raises | IMPLEMENTED; CI pending |
| Exact numeric storage | Decimal strings in new evidence ledger; Decimal aggregation in fee path | PARTIAL — existing Campaign/trade tables and downstream API still use REAL/float |
| Position/available-balance reconciliation | No complete unified contract added | NOT VERIFIED |
| Fee conversion to quote for non-base/non-quote assets | Uses current ticker price in existing contract, not a proven historical conversion source | NOT VERIFIED for accounting valuation |
| Protection status, side, quantity and actual coverage | Existing order lookup is not enough; no complete repair/coverage reconciler added | NOT VERIFIED |
| OCO full lifecycle and partial-fill protection | Not closed by this patch | NOT VERIFIED |
| Fault injection across all 12 required boundaries | Only existing targeted SQLite failure test plus new ledger tests; not exhaustive | NOT VERIFIED |
| Populated v6→v8 migration | Populated legacy-shaped test added, but CI execution pending and no production DB supplied | PENDING / NOT VERIFIED |
| Two independent processes recovering same operation | New concurrent fill-ledger test uses two DB connections in threads, not two separate OS processes or two recovery services | NOT VERIFIED |
| Entry block vs safe exit permission matrix | Not closed by this patch | NOT VERIFIED |
| Android process-death test | Not run | NOT VERIFIED |
| Spot Testnet lifecycle test | Not run | NOT RUN / NOT VERIFIED |

### 12.5 CI for implementation

At the time of writing, the latest implementation SHA `ee559838c9897d4efa68af079cdbe8c6e4ac9a63` had these runs in progress:
- [Python backend tests — 38090072263](https://github.com/19wolfalone92/Williams/actions/runs/38090072263)
- [Williams Campaign CI — 38090072261](https://github.com/19wolfalone92/Williams/actions/runs/38090072261)
- [Build Android APK — 38090072254](https://github.com/19wolfalone92/Williams/actions/runs/38090072254)

The report update itself will produce a later SHA; all required workflows must be checked again against that final SHA. No result from a prior SHA is transferable.

### 12.6 Decision

**TASK 001.3: FAIL — NOT ACCEPTED.**

The newly added ledger improves idempotent fill evidence and closes the specific missing-commission-to-zero defect in the fee lookup path. It does **not** establish the requested complete cycle. The remaining P0 blockers are:
- unify exchange-fill ledger entries with Campaign/position accounting in one idempotent transaction or equivalent durable state machine;
- reconcile exchange account balances and net base-asset exposure against fills and local Campaign without automatic masking;
- independently validate actual protective order/OCO parameters and coverage, then define controlled repair via ExecutionBarrier;
- close the complete OCO partial-fill/cancel/restart lifecycle;
- exhaustively inject SQLite/process failures at every required boundary;
- test populated real v6 schema migration and two independent OS processes racing recovery;
- prove the separate entry-block / safe-exit permission contract;
- run and inspect all required CI on the final report SHA;
- physical Android process-death and Spot Testnet remain NOT VERIFIED.

No Binance Mainnet or real trading operation was used. `main` remains untouched. PR #47 must remain open Draft and must not be merged.


## 11. TASK 001.4 — Exactly-Once Financial Effects & Position Reconciliation (2026-10-11)

**Status: FAIL / NOT ACCEPTED — source audit established material gaps; no claim of exactly-once financial application.**

### 11.1 Baseline and prior-SHA CI

- Branch: `audit/williams-2.1-task-001-architecture`
- Existing PR: #47, still open and Draft; base `main` remains `04da5f9c09572e99ff310be3a1908179165e528c`.
- TASK 001.4 starting SHA: `07bf7673d9f9294124e0dc32026d2cb64e436565`.
- The branch/PR metadata reports this same head SHA. No merge or Mainnet/real order activity was performed in this audit.
- Python backend tests, run [38090130874](https://github.com/19wolfalone92/Williams/actions/runs/38090130874): **SUCCESS**, head SHA `07bf7673d9f9294124e0dc32026d2cb64e436565`; job `backend-test` completed successfully.
- Williams Campaign CI, run [38090130877](https://github.com/19wolfalone92/Williams/actions/runs/38090130877): **SUCCESS**, same head SHA; both `Android build and static checks` and `Python tests` completed successfully.
- Build Android APK, run [38090130946](https://github.com/19wolfalone92/Williams/actions/runs/38090130946): **SUCCESS**, same head SHA; `Build APKs` completed successfully.
- These CI results establish regression/build status only. They do not prove the financial/recovery acceptance criteria below.

### 11.2 Verified financial data paths and split state

The source audit found that `exchange_fill_ledger` is currently an evidence ledger, not the canonical financial-effect ledger:

1. `Database.record_exchange_fill()` in `db.py` inserts a unique `(symbol, exchange_trade_id)` record and stores quantity, price, quote quantity and commission as text. It rejects conflicting core trade fields and conflicting confirmed fee fields; an unknown fee can be enriched. This is useful durable evidence, but there is no durable per-fill application state tying the fill to an atomic position/Campaign update.
2. `CampaignExecutionService._executed_fee_quote()` in `campaign_execution.py` reads exchange trades, writes the fill evidence, sums quantities and fees using `Decimal`, then returns `float(fee_quote)`. The conversion is an explicit precision boundary; its result is passed into Campaign APIs.
3. The initial-entry and add-on paths call `engine.record_initial_fill()` / `engine.record_add_on_fill()`, then separately mutate signal state, legacy `trades` rows and Campaign persistence. These steps do not share one transaction keyed by exchange trade identity. A crash/retry between those effects can therefore leave the ledger populated while downstream state is incomplete, or repeat downstream effects.
4. `campaign_fills` is a separate table with an auto-increment row ID and no unique exchange trade ID constraint. `Database.save_campaign_fill()` inserts a new row and stores quantity, price, quote quantity, fee and base commission in SQLite `REAL` columns via Python `float`; it is not intrinsically idempotent against `exchange_fill_ledger`.
5. The Campaign persistence table stores `position_qty`, `average_entry_price`, realized/unrealized PnL, risk and capital fields as SQLite `REAL`; `Database.save_campaign()` explicitly converts these values to `float`. This is a separate representation from the exact-string fill ledger.
6. The legacy/portfolio execution path is independent of the Campaign fill materialization path. `PortfolioTrader._authoritative_execution()`, manual sell handling and trade close/update paths use float-based aggregates and legacy `trades` records. The TASK 001.4 follow-up changed `_exit_fee_quote()` to persist each identified raw trade into `exchange_fill_ledger`, retain unknown-fee evidence, reject missing/conflicting commission data, and latch global plus symbol `RECONCILE_REQUIRED`. However, `_authoritative_execution()` still constructs compatibility fill rows with default-zero commission fields when raw trade lookup fails or omits fee values; this remains a separate unresolved financial path.
7. Position reconstruction helpers in `portfolio_trader.py` use exchange order history, order executed quantities and account base-asset balance as separate evidence. They do not yet establish a single persisted reconciliation result joining trade IDs, commissions, net base inventory, Campaign state and verified protection.
8. Existing OCO recovery in `ExecutionBarrier` can reconcile list and child order identities/statuses, but the evidence does not establish that fills/fees have been applied exactly once or that remaining protection quantity matches the authoritative residual exposure.

### 11.3 Current conclusion by requested gate

| Gate | Finding on starting SHA | Status |
| --- | --- | --- |
| P0-B exactly-once financial effect | Unique fill evidence exists; atomic/idempotent financial effect + Campaign/position update does not. | **BLOCKED** |
| P0-C Decimal/float contract | Decimal is used in part of fee aggregation, but downstream Campaign/legacy accounting persists and aggregates floats/REAL. | **BLOCKED** |
| P0-D fee completion | Campaign entry fee path blocks on missing fee; legacy exit fee path can normalize missing commission to zero. Unknown fee is not a universal account-wide completion gate. | **BLOCKED** |
| P0-E authoritative position reconciliation | No single durable reconciler proves agreement among order history, fill IDs, fee assets, account inventory, local exposure, Campaign and protection. | **BLOCKED** |
| P0-F protection coverage | Order existence/recovery is not yet a proof of exact live protective quantity against reconciled exposure after partial fills/cancel-replace. | **BLOCKED** |
| P0-G OCO lifecycle | List/child status tests exist; end-to-end OCO partial fill → fee accounting → residual position → protection → restart is not established. | **BLOCKED** |
| P0-H crash consistency | Existing tests cover selected failure boundaries and two SQLite connections in threads, not the complete requested matrix or two independent OS processes. | **BLOCKED** |
| P0-I global recovery gate | Per-intent and per-symbol barriers exist, but a single gate whose predicate proves every financial and protection dimension is not demonstrated by current source/tests. | **BLOCKED** |

### 11.4 Required next implementation boundary

The safe next step is not to mark `exchange_fill_ledger` rows as applied from a sequence of Python calls. The application must introduce a durable financial-effect identity and transaction contract, then route every relevant Campaign and legacy entry/exit/recovery path through it. The effect transaction must bind the fill identity, exact amounts, commission asset/state, campaign/position association and the resulting canonical state; retries must read the committed effect rather than replay arithmetic. A partially known commission, missing association, or mismatch must remain unresolved and hold the global entry gate.

The current schema's `REAL` Campaign/legacy financial fields and non-unique `campaign_fills` rows mean this requires a coordinated data-model migration and a complete call-site inventory. Adding an isolated idempotency table without migrating callers would create a false assurance, so this pass deliberately does not claim that the financial-effect problem has been fixed.

### 11.5 External validation boundary

- Fake-exchange/local SQLite evidence is not a physical Android process-death/restart test.
- A dedicated Binance Spot Testnet lifecycle matrix was **not run** in this audit.
- No Binance Mainnet or real trade was used.
- Final TASK 001.4 status remains **FAIL / NOT ACCEPTED** until all P0s above are implemented and verified on one exact final SHA.


### 11.6 TASK 001.4 limited code follow-up

After the source audit, a narrow fail-closed correction was committed in the same branch:

- `portfolio_trader.py`: `_exit_fee_quote()` now persists stable exchange trade evidence through `record_exchange_fill()` before fee validation, uses `Decimal` for the commission aggregation path, refuses missing trade history / trade ID / commission amount / commission asset, rejects conflicting ledger evidence, and sets global plus per-symbol `RECONCILE_REQUIRED` on reconciliation errors. It does not resubmit exchange mutations.
- `test_task001_recovery_regressions.py`: added fake-exchange regressions for unknown exit commission (durable UNKNOWN row + recovery latch) and exact fractional trade values with a confirmed zero commission.
- This correction closes one unsafe fee-default path only. It does **not** make campaign/legacy position updates atomic with the fill ledger, does not eliminate all Decimal/float conversions, and does not complete position/protection/OCO recovery.


---

## 13. TASK 001.5 — Exactly-Once Financial Effects, Decimal Integrity & Recovery Closure (2026-10-11)

**Decision: FAIL / NOT ACCEPTED.** This pass closes one concrete legacy fee-default path and adds focused regressions. It does not prove exactly-once application to position/Campaign/risk, and it does not claim the complete TASK 001.5 acceptance criteria.

### 13.1 Baseline and CI evidence

- Base SHA: `04da5f9c09572e99ff310be3a1908179165e528c`.
- Starting HEAD requested by task: `b1a93bc166fcab2629a37bc10cf5afea46e1ffcb`; the existing PR metadata was re-read before changes and confirmed this SHA was the actual PR head.
- PR #47 remains open, Draft, based on `main`; no merge was performed.
- All required CI runs on starting SHA `b1a93bc166fcab2629a37bc10cf5afea46e1ffcb` completed successfully, with all jobs successful:
  - Python backend tests — [run 38090927007](https://github.com/19wolfalone92/Williams/actions/runs/38090927007), `backend-test: success`.
  - Williams Campaign CI — [run 38090927002](https://github.com/19wolfalone92/Williams/actions/runs/38090927002), `Android build and static checks: success`, `Python tests: success`.
  - Build Android APK — [run 38090927068](https://github.com/19wolfalone92/Williams/actions/runs/38090927068), `Build APKs: success`.
- These runs verify the starting revision only. They are not evidence for the new TASK 001.5 edits. CI must be inspected again on the final report SHA.

### 13.2 Confirmed data-flow map

| Stage | Current source / boundary | Idempotency / transaction finding | Error behavior |
|---|---|---|---|
| Exchange fill identity | Spot `myTrades` rows; symbol + exchange trade ID | `exchange_fill_ledger` primary key is `(symbol, exchange_trade_id)`; `record_exchange_fill` uses a SQLite transaction for evidence insertion/enrichment | Conflicting immutable fields raise; caller must latch reconciliation |
| Fee resolution | Explicit `commission` + `commissionAsset` from exchange | Stored as exact decimal text; unknown fee is `UNKNOWN` | Must block financial completion |
| Financial effect | `campaign_execution.py` and legacy `portfolio_trader.py` | **No unified per-fill effect key/transaction binds fill, position, Campaign and risk reservation** | Crash/retry can still leave split state; global closure not proven |
| Campaign fill | `campaign_fills` | Auto-increment row, REAL amounts, no exchange trade ID uniqueness; `save_campaign_fill` inserts independently | Duplicate downstream effect remains possible |
| Campaign position | `campaigns.position_qty`, average price, PnL/risk columns | SQLite REAL and Python float; writes are not universally in the same transaction as the fill ledger | Requires recovery/reconciliation |
| Legacy position/trade | `trades`, `orders`, portfolio execution helpers | Separate writer path; several numeric values remain float/REAL | Now blocks one missing-fee path, but not unified |
| Risk reservation | Campaign risk fields and state aggregates | Float-backed Campaign fields; no atomic per-fill coupling established | Not proven exactly-once |
| Protection/OCO | Exchange orders/list/child records plus local intents | Order-state recovery does not prove fill effects or exact residual protection coverage | Keep recovery unresolved if evidence is insufficient |
| Global recovery | global/per-symbol `position_state`, intent state and other gates | No single predicate proven to join all financial, position, risk and protection conditions across every entry path | Acceptance remains blocked |

### 13.3 TASK 001.5 code change

Implementation SHA immediately before this report refresh: `84e4785f268129b56add3cc6214a115e1c4336af`. The report refresh itself creates a new commit; mandatory CI must therefore be checked against the resulting exact report SHA.

Changed files:
1. `portfolio_trader.py`
2. `test_task001_recovery_regressions.py`
3. `test_multi_position_lifecycle.py` — fake exchange now returns trade-history rows for synthetic manual/emergency fills.
4. `test_integration_bindings.py` — recovery fake now returns explicit authoritative trade/fee evidence.
5. `.github/workflows/android-apk.yml` — Android APK CI now also runs for root Python/tests and audit-report changes, so backend financial changes can be checked on the same SHA.
6. `.github/workflows/python-ci.yml` — backend CI now runs on audit branches and for audit-report changes, allowing the exact feature-branch SHA to be tested.
7. `.github/workflows/android-apk.yml` — APK CI now runs on audit branches as well as backend/test/report changes.
8. This existing report.

In `MultiPositionTrader._authoritative_execution()` (exposed through the legacy PortfolioTrader compatibility import), a filled order without local fill details now requires authoritative `myTrades` evidence. Lookup failure, empty history, malformed identity, missing trade ID, missing fee amount/asset, invalid numeric data, or a ledger conflict latches global and symbol `RECONCILE_REQUIRED` and raises instead of silently falling back to order-level quantity data or treating missing commission as zero.

Each validated fill is recorded through `record_exchange_fill()` before a legacy execution summary is returned. Missing fee evidence is first retained in the ledger as `UNKNOWN` when the stable trade identity and execution fields are available; the method then blocks financial continuation. An explicitly confirmed zero commission with a named asset remains valid. Re-delivery of the same stable fill identity is ledger-idempotent.

The new tests exercise:
- missing commission → durable `UNKNOWN` ledger evidence, both recovery latches set, exception raised;
- explicitly confirmed zero commission → one ledger row after two deliveries, exact decimal quantity/price strings preserved.

### 13.3.1 CI regression and correction

The first CI candidate SHA `d942b5abfaed69637c3dd573a056615b54b49191` exposed three regressions in tests whose fake exchanges returned executed order quantities but no authoritative `myTrades` rows:
- Python backend tests — [run 38115592076](https://github.com/19wolfalone92/Williams/actions/runs/38115592076): **FAIL**, 3 failed / 162 passed.
- Williams Campaign CI — [run 38115592068](https://github.com/19wolfalone92/Williams/actions/runs/38115592068): Python job failed with the same fake-exchange contract issue; Android job was still running at the time of the log inspection.
- The failing cases were partial manual sell, partial emergency sell, and terminal partial-entry recovery. The production path correctly refused to infer fee-complete financial evidence from order status/quantity alone.
- Corrective change: the test doubles in `test_multi_position_lifecycle.py` and `test_integration_bindings.py` now return explicit stable trade IDs, quantities, prices, quote quantities, and confirmed zero commission/asset evidence for their synthetic fills. Production checks were not relaxed.
- These corrections are on the next candidate SHA and must be re-tested; no success is claimed until final-SHA CI completes.

### 13.4 Exactness and fee matrix: remaining limits

- The new ledger stores exact decimal strings, but `campaign_fills`, `campaigns`, and legacy `trades` continue to store financial fields in SQLite REAL and/or consume Python floats.
- Third-asset fee valuation still has a compatibility path that crosses through float price/ticker interfaces; exact native commission amount and asset remain in the ledger, but a fully specified historical conversion contract is not established.
- The code does not yet model exact net base inventory across base-asset commission, quote commission, and third-asset commission as one canonical financial state.
- This change does not add a financial-effect journal or prove atomic fill→position→Campaign→risk application. A ledger row's existence is not proof that its financial effect was applied.
- No legacy data has been deleted or rounded by this change.

### 13.5 Required gates still open

| Requirement | Evidence in this pass | Status |
|---|---|---|
| Exactly-once financial effect bound to position/Campaign/risk | No unified application transaction or equivalent durable state machine | **BLOCKED** |
| Decimal canonical position, PnL, balance and risk contract | Existing REAL/float writers remain | **BLOCKED** |
| All commission assets, conversion time/rate, and net base delta | Partial strict fee evidence only | **BLOCKED** |
| Authoritative balance/order/fill/Campaign reconciliation | No complete reconciler demonstrated | **BLOCKED** |
| Actual protection quantity and cancel/replace/OCO lifecycle | No complete end-to-end residual exposure proof | **BLOCKED** |
| Fault injection at every requested transaction/network boundary | Focused regressions only | **NOT VERIFIED** |
| Two independent OS processes racing one fill/effect/Campaign/recovery | Not performed | **NOT VERIFIED** |
| Populated real-schema v6/v7/v8 migration and interrupted migration | Not fully performed | **NOT VERIFIED** |
| One global recovery gate across every production entry path | Not demonstrated | **BLOCKED** |
| Android physical process death/restart | Not run | **NOT VERIFIED** |
| Binance Spot Testnet full lifecycle | Not run | **NOT RUN / NOT VERIFIED** |

### 13.6 External safety and final decision

No Binance Mainnet or real trading operations were used. No physical Android process-death test or Binance Spot Testnet lifecycle was performed. `main` was not modified; PR #47 remains open Draft.

**TASK 001.5 remains FAIL / NOT ACCEPTED.** The legacy missing-fee fallback is now fail-closed and focused tests were added, but exactly-once financial application, Decimal canonicalization, position/risk reconciliation, protection/OCO closure, complete fault injection, inter-process proof, and a universal recovery gate remain unproven.
