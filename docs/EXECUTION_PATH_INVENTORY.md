# WILLIAMS BOT 2.1 — TASK 001.2
## Execution Path Inventory — Binance Spot Mutations

**Result: BLOCKED — MUTATION SURFACE PARTIALLY INVENTORIED; BARRIER EXCLUSIVITY NOT PROVEN**  
**Repository:** `19wolfalone92/Williams`  
**Audit ref / PR head:** `cb0a5b1046502d92dfcc8eb1038a9ee4fe995fbc`  
**Base/main:** `04da5f9c09572e99ff310be3a1908179165e528c`  
**PR #47:** OPEN / DRAFT / NOT MERGED  
**Mode:** Remote source inspection; no Mainnet requests, no real orders and no task-specific runtime execution.

## 1. Scope and limitation

The inventory below combines mutation endpoints identified in the architecture report with the Python Spot adapter and Android signed REST transport. It is **not a claim that every dynamic call site in the repository has been found**. The source-wide AST/call graph and negative bypass tests required for PASS have not been run in this task. An absent result from a search is not treated as proof of absence.

## 2. Python Binance Spot mutation inventory

Adapter evidence: `binance_client.py`, blob SHA `df3394990addd03dff4225ff8e62111412191f7e`; execution boundary evidence: `execution_barrier.py`, blob SHA `c0e7cf4eea424d037b273e6150fa2d3d0ea93b40`; campaign caller evidence: `campaign_execution.py`, blob SHA `699b28b1de25f90465336060ab6162537ef82668`.

| ID | Mutation / route | Known entry surface | Required gate and durable record | Observed status / unresolved evidence |
|---|---|---|---|---|
| PY-M01 | Submit standard/conditional order — `POST /api/v3/order` | BinanceSpotClient order method, called from execution/campaign paths | `ExecutionBarrier.execute`; persist PENDING intent before network; stable client order ID; Risk/preflight and context/state checks for exposure-increasing orders | Adapter and barrier exist. Complete caller closure and every call-site proof are **BLOCKED** |
| PY-M02 | Cancel order — `DELETE /api/v3/order` | Client cancellation methods; protection/exit/recovery flows | Barrier on active paths; managed order/client ID; persist operation; query exchange after ambiguous outcome | Cancel path identified; full direct-call negative proof **BLOCKED** |
| PY-M03 | Cancel-replace — `POST /api/v3/order/cancelReplace` | Client compatibility/mutation methods | Durable operation record must preserve old and replacement IDs/outcomes; reconcile both sides after timeout | Endpoint identified in audit; non-atomic outcome/fault matrix not executed |
| PY-M04 | Cancel all symbol open orders — `DELETE /api/v3/openOrders` | Bulk cancellation primitive | Must not be exposed as an untracked shortcut; enumerate affected managed orders and reconcile each | Dangerous primitive identified; active-path reachability and safety are **UNRESOLVED** |
| PY-M05 | Create OCO/order-list protection — `POST /api/v3/orderList/oco` and compatibility wrappers | Protection/stop management | Barrier, durable client IDs/order-list identity, quantity tied to confirmed fills, exchange read-back | Route family identified; all wrapper/caller edges and partial-fill semantics not proven |
| PY-M06 | Cancel OCO/order list — `DELETE /api/v3/orderList` | Protection replacement, exit, recovery | Barrier; durable old/new operation identities; reconcile both orders and residual exposure | Route family identified; full recovery evidence absent |
| PY-M07 | Any other POST/DELETE order-related route | Any direct `session.request`, `_request`, adapter helper or alternate client | Must be added to this table and forced through the same admission/durable-intent boundary | Exhaustive AST/call graph not executed; **UNKNOWN**, not “none” |

### Adapter-level retry semantics

`BinanceSpotClient._request` documents that POST/DELETE must not be blindly retried after timeout/5xx because the matching engine may have accepted the operation. The implementation permits a special signed timestamp-resync retry path; that exception still requires proving the timestamp rejection is definitive and cannot duplicate a mutation. For general timeout/connection ambiguity the outcome must be marked UNKNOWN and reconciled by stable order/client ID before another mutation.

The adapter constructor has a Testnet default but can be configured with `testnet=False`, which selects the production base URL. The existence of a Testnet default is not a release gate. The runtime configuration, credential provenance and final build must prove Mainnet cannot be selected for this task.

## 3. Python caller families to close

| Family | Source file / function family | Risk if unclosed | Required evidence |
|---|---|---|---|
| Canonical campaign entry/add-on | `campaign_execution.py`: `arm_initial_entry`, `arm_add_on` and associated submit methods | Entry could bypass reservation/context or duplicate an UNKNOWN submit | Every submit call maps to one `OrderIntent` and barrier; fault injection before/after network |
| Multi-position / legacy entry | `portfolio_trader.py`: `MultiPositionTrader.execute_campaign`, `execute`, and `_submit_via_execution_barrier` family | Legacy score-only route could accidentally become an independent order path | Reachability and fail-closed tests for campaign mode on/off, absent context and absent barrier |
| Single-symbol Trader | `trader.py` / `strategy.py` order and recovery callers | Parallel execution state or duplicate recovery | Prove disabled/excluded or barrier-routed; tests against direct adapter calls |
| Protection and structural stop | campaign/portfolio stop, OCO, cancel/replace methods | Unprotected residual exposure or double protection | Old/new OCO identity, partial fills, cancel timeout, exchange reconciliation |
| Emergency/manual exit | emergency SELL, manual SELL, kill-switch paths | A shortcut may mutate without durable intent or state verification | All routes in mutation graph; risk-reducing policy explicit; residual balance reconciliation |
| Recovery / startup | `CampaignExecutionService` startup recovery; `portfolio_trader.py` pending entry/trade recovery; `trader.py` legacy recovery | Recovery can race or re-submit an operation whose outcome is unknown | Recovery must query only; no blind re-submit; two-process and restart tests |
| Bulk cancel | cancel-open-orders methods | Managed protective orders can be removed without matching state transitions | Prove no untracked bulk cancel, or implement journaled operation with full post-query |

The current source evidence includes several recovery and persistence entry points. It does not establish that they are mutually exclusive or that all direct SQL writes are transactionally coupled.

## 4. Android mutation inventory

Evidence: `StandaloneRuntime.kt`, blob SHA `d462682601c86fe5145646d16d417435cce032d4`; `TradingAuditStore.kt`, blob SHA `5ba02be31c5dbc31657c46ec579d7906da59d3dd`.

| ID | Mutation surface | Required policy | Status |
|---|---|---|---|
| AND-M01 | `signedPost` → `signedRequest` → `performSignedRequest` | Central allow-list for route/side/type/IDs; journal durable intent before network; persist response or UNKNOWN | Central transport gate and SQLite mutation-intent journal are described in branch audit; installed APK and every call site not independently verified here |
| AND-M02 | `signedDelete` → `signedRequest` | Same journal/gate; cancellation must preserve order identity and reconcile afterward | Same as above; full direct-call inventory not executed |
| AND-M03 | Entry / add-on BUY | Must require runtime safety gate, reservation, fresh context, exchange/account reconciliation, no unresolved mutation | Presence of `ExecutionGate` and state checks is not proof all BUY routes are covered |
| AND-M04 | OCO/protection create/cancel/replace | Persist operation identities; tie protection quantity to confirmed executed inventory; reconcile old and new order | Source contains stop/protection paths; full crash/partial-fill tests absent |
| AND-M05 | Campaign exit / manual sell / emergency exit | Explicit risk-reducing allow-list, durable intent, exchange confirmation before FLAT | Methods identified in architecture audit; device execution evidence absent |
| AND-M06 | Any alternative OkHttp POST/DELETE or direct exchange transport | Must be prohibited or routed through `signedRequest`; unrecognized routes fail closed | Audit describes `performSignedRequest` as the single transport, but repository-wide compiler/AST proof and negative tests are not attached to this report |

## 5. Mandatory negative tests

1. Monkeypatch/intercept every Python adapter mutation method and assert no reachable active caller can hit it without the barrier.
2. Assert all order-related routes are Spot `/api/v3/*`; reject `/fapi/*`, `/dapi/*`, arbitrary POST/DELETE and unknown order-list routes.
3. Force persistence failure before network; verify zero mutation requests.
4. Simulate accepted order + lost response; verify UNKNOWN/reconcile and exactly zero blind second POST.
5. Simulate cancel/replace partial success, both-success/both-fail/unknown outcomes, OCO partial fills and cancel timeout.
6. Inject kill switch during entry, protection replacement and partial exit; verify latch durability and no false FLAT.
7. On Android, enumerate every signed mutation call site and assert all reach `signedRequest`; fail test for direct transport bypass.
8. Restart between intent commit, request dispatch, exchange acceptance, response persistence and fill application.
9. Prove Mainnet base URL cannot be selected by release configuration or Android runtime for the audited Testnet build.

## 6. Evidence requirements for PASS

Each row must include exact source commit SHA, file blob SHA, function/caller path, test name and run ID, test command and exit code, log/artifact digest, exchange environment, and review status. A green generic unit-test suite does not prove the mutation graph is complete.

**Final status: BLOCKED.** The known Spot mutation families and intended gates are listed, but barrier exclusivity, all-call-site closure and the full unknown-outcome test matrix remain unproven. No Mainnet or real order was used by this audit.
