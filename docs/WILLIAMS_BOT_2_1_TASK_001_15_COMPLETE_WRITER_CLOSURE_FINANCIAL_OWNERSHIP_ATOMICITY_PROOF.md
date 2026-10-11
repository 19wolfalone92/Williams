# WILLIAMS BOT 2.1 — TASK 001.15
## Complete Writer Closure, Financial Ownership & Executable Atomicity Proof

**Result: BLOCKED — SOURCE EXECUTION / WRITER CLOSURE / ATOMICITY EVIDENCE INCOMPLETE**

**Mode:** REMOTE STATIC ANALYSIS — NOT EXECUTED locally  
**No production code changed. No financial writer, ledger, schema, or trading algorithm was modified. No Binance order or financial mutation was performed.**

## 1. Control snapshot and provenance

| Item | Verified observation |
|---|---|
| Repository | `19wolfalone92/Williams` |
| Branch | `audit/williams-2.1-task-001-architecture` |
| Source snapshot used for static inspection | `d21329fc05acb02742497f46d31551d6d1bfd6b6` |
| Prior TASK 001.14 report commit | `d21329fc05acb02742497f46d31551d6d1bfd6b6` |
| Main SHA | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Merge base | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Compare result | branch 132 commits ahead, 0 behind main at observation time |
| PR #47 | OPEN, DRAFT, NOT MERGED |
| PR head/base | head `d21329fc05acb02742497f46d31551d6d1bfd6b6`; base `04da5f9c09572e99ff310be3a1908179165e528c` |
| PR mergeability | GitHub API returned `mergeable=true` on this check (prior `false` is stale); connector does not expose `mergeable_state` |
| Tree snapshot | Git tree API returned the requested SHA, 253 blobs, `truncated=false`; 150 Python/Kotlin/Java/SQL/workflow files were enumerated |
| Local checkout | NOT AVAILABLE: `git clone` failed because this environment could not resolve `github.com` |
| Git working tree/status | UNRESOLVED — no local checkout; cannot report `git status`, local HEAD or local dirty state |
| Branch protection | UNRESOLVED — API endpoint returned 403 “Resource not accessible by integration”; this is an access limitation, not evidence that no protection exists |
| Tests executed by this task | NONE locally |
| Production changes | NONE |

The remote tree is a complete Git tree response for the requested commit, but remote file retrieval is not an executable checkout. Conclusions below are therefore static-analysis findings and are not presented as local test results.

## 2. CI observations for the pre-existing source SHA

GitHub reported eight check runs associated with `d21329f...` when inspected:

- `Python tests`: completed, success (two reported jobs/runs).
- `backend-test`: completed, success (two reported jobs/runs).
- `Android build and static checks`: in progress (two reported runs).
- `Build APKs`: in progress (two reported runs).
- Combined commit status: pending, with no legacy status contexts.

These are CI observations on the pre-existing source SHA, not TASK 001.15 test execution. At least four Android/APK checks were still in progress at inspection time. No claim is made that all CI completed successfully. The exact workflows enumerated at the source SHA are `.github/workflows/python-ci.yml`, `.github/workflows/campaign-ci.yml`, `.github/workflows/android-apk.yml`, and `.github/workflows/testnet-readonly.yml`.

## 3. Evidence reviewed

The remote source tree contained 253 blobs (not truncated), including 150 source/test/workflow files matching Python, Kotlin, Java, SQL or workflow extensions. The following complete files were fetched and inspected at the source SHA; the listed blob hashes identify the actual contents inspected:

| File | Blob SHA | Static-analysis scope |
|---|---|---|
| `db.py` | `129645290a074d7958ac75a4128de455754db13e` | SQLite setup/transactions, fill ledger/journal, trade and campaign persistence |
| `campaign_execution.py` | `699b28b1de25f90465336060ab6162537ef82668` | Entry reconciliation, fee quote, direct trade SQL |
| `campaign_engine.py` | `2183825fcab8f46e47a7ea8b2833c259a367229e` | Initial/add-on fill state transitions |
| `portfolio_trader.py` | `5c38ed933edf17e0a9d3376363c50786a94a737d` | Exit fee, pending entry recovery, trade reconciliation |
| `trader.py` | `b2d9287a10da7d803c8ce14912c24745453033d7` | Legacy entry/closed-trade recovery |
| `execution_barrier.py` | `c0e7cf4eea424d037b273e6150fa2d3d0ea93b40` | Execution-intent and reconciliation state writes |
| `execution_accumulator.py` | `551bd0941206f7459b3fc8bbce3937e836b96f52` | Python execution quantity/VWAP accumulation |
| `binance_client.py` | `df3394990addd03dff4225ff8e62111412191f7e` | Exchange boundary |
| `StandaloneRuntime.kt` | `d462682601c86fe5145646d16d417435cce032d4` | Android entry/fill/campaign/recovery/reconciliation paths |
| `TradeJournal.kt` | `2822b22b4ed7e06b8384fabf343a7dd2f8059057` | SharedPreferences trade journal |
| `TradingAuditStore.kt` | `5ba02be31c5dbc31657c46ec579d7906da59d3dd` | SQLite execution/trade/order persistence |
| `ExecutionAccumulator.kt` | `3dbc8ef7af27f45afe94ce847f2c3686c00e3290` | Android execution accumulator |
| `ExecutionGate.kt` | `aec1c01d12fe460a62a48c1fa81af527cce4afd5` | Android admission gate |
| `test_task001_recovery_regressions.py` | `1141d6c2e90ee203069d00631d59db1ff61d40b4` | Existing recovery regression tests (not run here) |
| `test_task001_architecture_contracts.py` | `1cec6069a5c13fb5c805678018793ec85e374bcb` | Existing architecture contract tests (not run here) |
| `python-ci.yml` | `77b0f745f2c901a455c3c83d4d78f7a4734f7d12` | Python CI configuration |
| `campaign-ci.yml` | `0214f8726fe7f605b61c51f19742236de961dd15` | Campaign CI configuration |
| `android-apk.yml` | `a0b7d010bc4b1f53c28ceb649977527595dcd099` | Android build/check configuration |

The tree also contains other runtime and persistence-adjacent modules, including `server.py`, `run_bot.py`, `run_server.py`, `TradingForegroundService.kt`, `TradingEventLoop.kt`, `TradingStateMachine.kt`, `BackupManager.kt`, and numerous tests. Because the checkout is not executable here and every source file/caller chain has not been manually closed, this report does not certify exhaustive writer closure.

## 4. Python writer inventory — static findings

| ID | Writer / location | Trigger and writes | Identity/idempotency evidence | Status |
|---|---|---|---|---|
| PY-01 | `db.py:record_exchange_fill()` and transactional helper | Fill observation/ledger and financial-effect journal; reconciliation markers | Existing dedupe constraints were observed in TASK 001.13/001.14; no end-to-end proof that all downstream projections are applied once | PARTIAL |
| PY-02 | `campaign_execution.py:_executed_fee_quote()` | Fee quote derived from execution/trade data | Fee availability and later fee changes need to be linked to every consumer and durable projection | PARTIAL |
| PY-03 | `campaign_execution.py:reconcile_pending_entries()` | Recovery after entry order/fill; direct SQL updates to `trades` are present (around line 668), followed by explicit commit around line 682 | Direct persistence path exists outside a proven single transaction with all Campaign/position changes | PARTIAL |
| PY-04 | `campaign_engine.py:record_initial_fill()` | Initial Campaign fill and position/Campaign model state | Caller coverage and transaction coupling to DB projection not proven | PARTIAL |
| PY-05 | `campaign_engine.py:record_add_on_fill()` | Add-on fill and Campaign state | Caller coverage, replay semantics and transaction coupling not proven | PARTIAL |
| PY-06 | `portfolio_trader.py:_exit_fee_quote()` | Exit fee calculation | Downstream fee/PnL update paths require test proof | PARTIAL |
| PY-07 | `portfolio_trader.py:_reconcile_trade()` | Exit/reconciliation; trade close or quantity update via DB methods | Recovery repeat and exact financial-effect count not executed | PARTIAL |
| PY-08 | `portfolio_trader.py:recover_pending_entries()` | Restart/recovery for pending entries | Separate recovery entry point; no proof it cannot race with other recovery paths | PARTIAL |
| PY-09 | `trader.py:recover_state()`, `_recover_entry_intent()`, `_recover_closed_trade()` | Legacy state/entry/close recovery | Runtime reachability or exclusion is not proven; must not be presumed dead code | UNRESOLVED |
| PY-10 | `db.py:save_trade()`, `update_trade_quantity()`, `close_trade()` | Generic trade projection writers | Multiple callers and their transaction contexts need whole-repository closure | PARTIAL |
| PY-11 | `db.py:save_campaign()` and Campaign-fill persistence | Campaign state/fill writes | Atomicity with fill and trade projection is not proven | PARTIAL |
| PY-12 | `execution_barrier.py` intent/reconciliation state persistence | Entry/exit admission and recovery checkpoint updates | Intent-state transaction is not itself proof that position, fee, PnL and Campaign updates are atomic | PARTIAL |
| PY-13 | Direct SQL callers in `campaign_execution.py` and other modules | Direct trade/intent/operation updates | Direct writes bypass any claim based solely on high-level DB methods; all direct SQL callers still require closure | PARTIAL |

### Python transaction observations

`db.py` sets SQLite `busy_timeout=20000`, `journal_mode=WAL`, and `synchronous=NORMAL`. The inspected helper uses `BEGIN`, commit and rollback logic. The source also contains numerous direct `self.conn.commit()` calls across persistence methods. This indicates several transaction scopes, not a demonstrated transaction spanning all required financial projections.

No executable fault-injection evidence was obtained for two independent processes, commit-ack loss, SQLite lock contention, process termination at each boundary, or projection recovery. An in-process lock cannot establish cross-process exclusivity.

## 5. Android writer inventory — static findings

| ID | Writer / location | Data/sink | Trading relevance and status |
|---|---|---|---|
| AND-01 | `StandaloneRuntime.kt:applyCampaignAddOnFill()` | Local Campaign/position application | Direct financial-state mutation path; ownership vs Python is unresolved |
| AND-02 | `StandaloneRuntime.kt:applyCampaignFill()` | Local Campaign/position application | Direct financial-state mutation path; exact durable identity and replay behavior not proven |
| AND-03 | `StandaloneRuntime.kt:createCampaignPositionFromFill()` | Creates local Campaign position from fill | Could affect local exposure used by runtime; whether it is projection-only is not proven |
| AND-04 | `StandaloneRuntime.kt:recoverPendingEntries()` | Android restart recovery | Must be checked for overlap with Python recovery and duplicate event replay |
| AND-05 | `StandaloneRuntime.kt:reconcilePositionsWithExchange()` | Reconciles local position against exchange | Presence of reconciliation is not proof that all mismatches block new risk |
| AND-06 | `TradeJournal.kt:recordEntry()`, `close()`, `write()` | SharedPreferences JSON journal; `Double`-based fields/calculations are present in this source family | Its outputs are durable local records; downstream use in risk/order sizing must be fully traced before calling it display-only |
| AND-07 | `TradingAuditStore.kt:recordTrade()` and execution/order insertion paths | Separate Android SQLite tables `executions`, `trades`, `orders`; schema includes REAL financial columns | Event/audit intent does not establish projection-only behavior; readers, conflict policies, and runtime decision consumers remain to be fully traced |
| AND-08 | `ExecutionAccumulator.kt` | Accumulates execution quantities/VWAP using decimal-oriented types | Must be traced from persistence/replay through position and Campaign application |
| AND-09 | `ExecutionGate.kt` and Android runtime callers | Admission gate before execution | Gate does not itself establish financial ledger ownership or atomicity |

### Android numeric and persistence assessment

- `TradeJournal.kt` uses SharedPreferences JSON and `Double`-based numeric values. This alone is not proof of a financial defect. The critical unresolved question is whether its computed values feed risk limits, order quantity, close/exit decisions, or are isolated analytics only.
- `TradingAuditStore.kt` creates/updates its own SQLite tables and uses SQLite `REAL` fields for some financial values. It includes `insertWithOnConflict` calls. Conflict policy must be evaluated per table: conflict-ignore can prevent duplicate event rows but does not prove exactly-once downstream application; replace/upsert can rewrite a projection and does not prove the related position/Campaign was changed atomically.
- Android process death and SharedPreferences/SQLite cross-store atomicity were not tested.
- Python SQLite and Android SQLite are independent databases. No cross-database transaction was identified in the inspected evidence. A durable coordination/handoff contract was not proven.

## 6. Financial ownership matrix

| Domain | Observed source/facts | Local writers/consumers | Status |
|---|---|---|---|
| Exchange orders/fills/balances | Binance responses/history are external facts | Python and Android exchange clients/reconciliation | AUTHORITATIVE EXCHANGE FACTS — VERIFIED IN PRINCIPLE; exact per-path contract PARTIAL |
| Canonical fill identity | Exchange trade IDs plus local ledger/event keys appear in implementation | Python ledger and Android execution persistence | OWNERSHIP UNRESOLVED |
| Application of financial effect | Python DB/campaign/recovery paths and Android runtime fill application both exist | Multiple candidate writers | MULTIPLE WRITERS — COORDINATION UNPROVEN |
| Spot position quantity | Exchange balance is an external reconciliation fact; local quantity projections are stored separately | Python trade/Campaign persistence and Android runtime | OWNERSHIP UNRESOLVED |
| Fees and commission asset | Exchange execution data and local fee calculations | Python fee quote/reconcile paths; Android stored trade fields | OWNERSHIP UNRESOLVED |
| Realised PnL | Local trade/close/reconciliation calculations | Python trade records; Android journal/audit store | MULTIPLE WRITERS — COORDINATION UNPROVEN |
| Unrealised PnL | Derived from local position and market price | Python and Android projections/consumers | PROJECTION INTENDED, exclusive decision ownership UNRESOLVED |
| Campaign lifecycle | Campaign model/engine and DB persistence; Android Campaign mutation functions | Python Campaign engine and Android runtime | MULTIPLE WRITERS — COORDINATION UNPROVEN |
| Trade history | Python trades DB; Android audit DB and SharedPreferences journal | Multiple stores | MULTIPLE WRITERS; authority/replication semantics UNRESOLVED |
| Risk reserves / admission state | Python barrier/risk gates and Android execution gate/runtime | Both platforms may gate local operations | OWNERSHIP UNRESOLVED |
| Android execution state | Android runtime, local stores and exchange reconciliation | Android runtime | AUTHORITATIVE LOCAL EXECUTION STATE vs exchange state boundary UNRESOLVED |
| Diagnostic/analytics projections | Journals/audit stores and diagnostics | Python/Android consumers | PROJECTION ONLY not verified until readers and decision call sites are closed |

No ownership assignment is promoted from “intended architecture” to “verified” without tracing all writes and reads. The key blocking conflict is that Android includes methods that apply fills to Campaign/position state while Python independently contains fill, trade and Campaign writers.

## 7. Fill-to-financial-state map and transaction boundaries

The following is the evidence map, not a claim that every system executes every step in this exact order:

1. Exchange execution/fill is observed by an exchange client, event path, or REST reconciliation.
2. Python may persist the observation through `db.record_exchange_fill()`.
3. Python execution/recovery paths may then update trade quantity/close state and Campaign through separate DB APIs or direct SQL.
4. Python methods issue their own commits; the inspected source does not prove one transaction includes fill ledger + position/trade + fee + PnL + Campaign + recovery completion.
5. Android runtime separately has fill-to-Campaign/position methods and recovery/reconciliation.
6. Android `TradingAuditStore` and `TradeJournal` persist through different stores. No cross-store atomic transaction is established.
7. Later risk/entry/exit decisions may consume local state; all consumers have not been closed, so safe blocking on every uncertain state is not proven.

### Boundary verdicts

| Boundary | Evidence | Verdict |
|---|---|---|
| Fill identity claim → fill ledger | Unique/dedup mechanisms exist in Python source family | PARTIAL; scope and cross-path consistency not proven |
| Fill ledger → trade/position projection | Multiple APIs/direct SQL and commits exist | ATOMICITY UNPROVEN |
| Trade/position → Campaign | Separate Campaign engine and persistence APIs exist | ATOMICITY UNPROVEN |
| Fee update → PnL projection | Fee quote/reconciliation paths exist | ATOMICITY UNPROVEN |
| Execution intent → exchange mutation | ExecutionBarrier and durable intent/reconciliation state exist | PARTIAL; does not prove financial projection atomicity |
| Python DB ↔ Android DB/SharedPreferences | Separate stores | NO SHARED TRANSACTION PROVEN |
| Unknown COMMIT → recovery | Durable intent/journal state exists | SAFE EXACTLY-ONCE RECOVERY UNPROVEN |
| Reconciliation mismatch → risk gate | Gate/reconciliation components exist | GLOBAL FAIL-CLOSED BEHAVIOUR UNPROVEN |

## 8. Exactly-once and recovery assessment

Existing uniqueness constraints and intent/journal states are useful controls but do not establish exactly-once accounting across all writers. A unique fill row only prevents a duplicate row within that constraint's scope; it does not automatically make trade, fee, PnL, Campaign, Android journal, and risk-reserve projections a single atomic effect.

No test in this task executed the production persistence code under injected process death or an unknown commit outcome. The following remain open:

- duplicate event replay through a different entry/recovery point;
- concurrent two-process fill application;
- conflicting payload for the same exchange fill identity;
- late fee/commission-asset information;
- failure between fill observation and position/Campaign updates;
- commit succeeded but caller timed out before acknowledgement;
- recovery after exchange history becomes unavailable;
- Android process death with persisted journal/store divergence;
- reconciliation failure and proof that all risk-increasing routes remain blocked.

## 9. Required 24-scenario executable matrix

All scenarios below are **NOT RUN** in this task. Production entry points are listed to guide executable tests against the actual persistence/runtime implementation. A specification is not test evidence.

| # | Scenario / production entry point | Fault/concurrency point | Required durable invariant and risk gate | Result |
|---:|---|---|---|---|
| 1 | One fill — `db.record_exchange_fill` plus real caller | Normal commit | One fill identity, at most one financial effect; position/fee/PnL/Campaign consistent | NOT RUN |
| 2 | Duplicate fill — same path and same identity | Replay same payload | No additional effect; duplicate is idempotent | NOT RUN |
| 3 | Replay after restart — production recovery caller | Restart after commit | One effect after repeated recovery | NOT RUN |
| 4 | Concurrent threads — production fill application | Two threads race same fill | One durable effect; loser observes committed result | NOT RUN |
| 5 | Independent processes — production fill application | Two SQLite connections/processes | One durable effect under DB uniqueness/transaction semantics | NOT RUN |
| 6 | Alternate recovery entry point — `portfolio_trader` / `trader` | Same fill delivered through second path | No bypass or second application | NOT RUN |
| 7 | Same identity, conflicting payload | Different qty/price/commission | Conflict latched; no silent overwrite or new effect; risk blocked | NOT RUN |
| 8 | Crash before transaction | Process termination before fill claim | No partial financial effect; safe retry after authoritative reconciliation | NOT RUN |
| 9 | Crash after fill write before position update | Inject termination inside actual transaction scope | Either all required writes rollback, or durable pending-effect recovery prevents trading | NOT RUN |
| 10 | Crash after position update before Campaign update | Inject termination at actual boundary | Atomic rollback or deterministic recovery; no inconsistent Campaign used for risk | NOT RUN |
| 11 | Crash after commit before caller acknowledgement | Kill process after commit | Recovery detects committed effect and does not reapply | NOT RUN |
| 12 | Unknown COMMIT result | Simulate connection/ack failure after COMMIT | Reopen DB, query durable operation identity, reconcile without blind replay | NOT RUN |
| 13 | Repeated recovery after unknown commit | Run recovery repeatedly | Stable one-effect state; idempotent recovery | NOT RUN |
| 14 | Partial order fill | Multiple cumulative reports | Apply only new incremental executed quantity | NOT RUN |
| 15 | Multiple fills, one order | Distinct exchange trade IDs | Each distinct fill exactly once; order aggregate equals fills | NOT RUN |
| 16 | Commission in base asset | Fill with base-asset commission | Correct net base quantity and fee asset; risk blocks if unknown | NOT RUN |
| 17 | Commission in quote asset | Fill with quote commission | Correct quote/PnL accounting; no duplicate fee | NOT RUN |
| 18 | Commission in third asset | Fill fee paid in another asset | Preserve asset and conversion uncertainty; no false zero fee | NOT RUN |
| 19 | Unknown commission | Fill with fee missing/unknown | Unknown remains unknown; unsafe sizing/close decisions blocked as required | NOT RUN |
| 20 | Exchange balance vs local position mismatch | Reconciliation | Mismatch detected; no risk increase before resolution | NOT RUN |
| 21 | Local position vs open SELL orders mismatch | Reconcile orders and holdings | No oversell/double reservation; risk-increasing action blocked | NOT RUN |
| 22 | Android process death | Kill Android process around store writes | Recovery converges without duplicate effect or divergent decision state | NOT RUN |
| 23 | Duplicate Android execution event | Repeat same execution event | Durable event identity and downstream position/Campaign effect remain exactly once | NOT RUN |
| 24 | Android projection vs authority mismatch | Inject divergence and reconcile | Projection repaired/marked stale; no independent second authority; risk gate blocks if unresolved | NOT RUN |

### Test implementation constraint

Tests must use the production classes/functions and actual SQLite schema/transactions. Do not create a simplified replacement writer. Scenarios 9–13 must inject failures at actual production transaction boundaries or via subprocess/connection interruption; a generic raised exception outside the transaction is insufficient. Android scenarios must exercise the actual persistence and recovery paths, not only pure math/unit helpers.

## 10. ADR — decision before implementation

**ADR-00115 — Financial effect ownership: NOT ACCEPTED / DECISION BLOCKED**

- **Decision status:** no ownership architecture can be approved from the available evidence.
- **Observed fact:** Binance is the source of exchange facts, while Python and Android each contain local fill/position/Campaign persistence paths.
- **Not yet established:** which runtime is the sole authority for applying a fill to the canonical local financial projection; whether Android is a projection-only client; how a durable cross-platform handoff is enforced; and whether every execution decision checks the same unresolved/reconciliation state.
- **Guardrail:** do not add or connect `FinancialEffectApplier`, do not add another ledger, and do not delete existing writers in this task.
- **Minimum next design work:** finish reachable call graph closure; trace all reads of Android financial values into order sizing, risk, and close logic; identify one canonical application owner; specify stable operation identity and recovery for unknown COMMIT; map all projection writes into one transaction or a proven durable outbox/recovery contract; define global fail-closed semantics.
- **Implementation gate:** separate task after the inventory/ownership decision, with fault-injection tests against production persistence.

## 11. Defects and minimal remediation plans (not implemented)

| Potential defect / blocker | Evidence | Minimum next action | Status |
|---|---|---|---|
| Python and Android both expose fill-to-position/Campaign mutation paths | Python Campaign/DB methods and Android `StandaloneRuntime` methods | Trace runtime reachability and decide one canonical financial-effect owner; classify other path as controlled projection only if proven | P0 UNRESOLVED |
| Fill record and all dependent projections not shown to share one transaction | Separate persistence calls/direct SQL/commits | Draw transaction-level call graph; test rollback and crash boundaries; then plan smallest transaction/recovery change | P0 UNPROVEN |
| Legacy `trader.py` recovery reachability not closed | Recovery methods present | Trace startup/service/runner references; prove active or safely unreachable under supported configuration | P0 UNRESOLVED |
| Android journal and SQLite may diverge | SharedPreferences journal plus separate SQLite store | Trace consumers and recovery reconciliation; do not label analytics-only without evidence | P0 UNRESOLVED |
| Unknown COMMIT exactly-once recovery not executed | Intent/journal exists, but no injected test evidence | Implement tests against real DB/connection lifecycle before changing production behavior | P0 UNPROVEN |
| Branch protection cannot be inspected through current integration | API returned 403 | Obtain authorized branch-rule/check requirement evidence outside this connector limitation | EVIDENCE GAP |

## 12. Final decision

**BLOCKED — WRITER INVENTORY INCOMPLETE / OWNERSHIP UNRESOLVED / ATOMICITY UNPROVEN / TEST EVIDENCE INCOMPLETE**

The remote source tree and selected source files are verified at `d21329fc05acb02742497f46d31551d6d1bfd6b6`, but a local executable checkout could not be obtained. The source contains multiple Python and Android financial mutation paths. Their complete runtime reachability, cross-platform ownership, and exact transaction boundaries have not been proven. The 24 required scenarios were not executed by this task.

This report does not assert that duplicate financial application has already been reproduced. It asserts that absence of duplicate application has not been proven. No production trading readiness is implied.

### Required next actions

1. Restore a trusted executable checkout at the exact source SHA.
2. Run a complete source-wide writer/sink and caller-chain audit across all runtime entry points.
3. Finish Android read/write consumer tracing, including all risk, sizing, close and execution decisions.
4. Define and approve ownership before any production writer changes.
5. Execute all 24 scenarios, especially transaction-boundary and unknown-COMMIT cases.
6. Reconcile any remaining blockers, then issue a separate implementation task if required.

**Financial invariant remains enforced as a release requirement:** one confirmed exchange fill must produce no more than one financial effect. Until that and global fail-closed reconciliation are proven, unknown financial state must not authorize risk-increasing operations.
