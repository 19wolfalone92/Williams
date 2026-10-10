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
- Android mutation journal recovery must resolve every PENDING/UNKNOWN mutation using order/order-list IDs and authoritative Binance response/history; it must not blanket-clear an unresolved mutation just because a general startup sync completed. Until per-mutation evidence is verified, the conservative journal barrier can keep the runtime blocked after a crash. Review this behavior against the final integration test.
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
