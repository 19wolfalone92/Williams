# WILLIAMS BOT 2.1 — TASK 003.14
## Complete Financial Mutation Graph, Decimal Integrity & Atomic Recovery Gate

**Final status: BLOCKED — EVIDENCE INCOMPLETE**  
**Mode:** AUDIT-FIRST. No production financial code, schema, strategy, risk parameter, or order-routing changes were made. No Binance trading mutation was performed.

## 1. Revision identity and scope

Repository: `https://github.com/19wolfalone92/Williams`  
Requested checkpoint: `e013aae4f0632387b899086b3097cd7cc1b32f22`  
PR #48 branch: `audit/williams-2.1-task-003-execution-safety`  
Main baseline: `04da5f9c09572e99ff310be3a1908179165e528c`

At the time of this audit, PR #48 was OPEN / DRAFT / NOT MERGED, with head `63552612d6a872676619b08daab4676689104930`, base `main` at `04da5f9c09572e99ff310be3a1908179165e528c`. The report commit from Task 003.13 is the current PR head observed for this task. PR metadata: https://github.com/19wolfalone92/Williams/pull/48.

This is a **remote static source inspection** of blobs requested explicitly at the checkpoint SHA, not a full local Git checkout. The inspected blobs and their returned Git blob IDs are:

| File at checkpoint | Git blob SHA | Use in this audit |
|---|---|---|
| `db.py` | `c43cb57857cbbf85e613bf40622239911a8a6dfc` | SQLite schema, transaction helper, legacy writers, financial-effect journal |
| `execution_accumulator.py` | `551bd0941206f7459b3fc8bbce3937e836b96f52` | REST fill aggregation and fee defaults |
| `execution_barrier.py` | `1d82e0e6ad36dc19a10c0fc3ac67ade6dcad2a1c` | Durable order intent and exchange mutation boundary |
| `campaign_execution.py` | `165298ecc9c09af4acb494e523945bc49786eeb3` | Campaign entry/add-on reconciliation and projection paths |
| `campaign_engine.py` | `2183825fcab8f46e47a7ea8b2833c259a367229e` | In-memory campaign mutations and save path |
| `portfolio_trader.py` | `559c7f0a24a366b4cf822a0534ababd5af24ec8e` | Portfolio fill recovery, exit metrics, emergency sell, trade writes |
| `trader.py` | `3e946abe5eae3fdafaa4bca82a76cde0750de27e` | Legacy single-symbol recovery and trade writes |
| `binance_client.py` | `774b17505729b5b9f10d4f4c961e9defc046468e` | Decimal formatting helpers and Binance mutation/reconciliation methods |
| `app/src/main/java/com/williamsbot/TradingAuditStore.kt` | `8e6f4b0ea20824efd549c98c2681fc550f5b2c5f` | Android SQLite schema, execution events, trade ledger and fee estimate |
| `app/src/main/java/com/williamsbot/StandaloneRuntime.kt` | `c179f5988b3759828bb457616e299260312447a6` | Android runtime fill application, campaign position recovery and mutation guards |
| `test_task003_execution_safety.py` | `04b33f731e5cb0576e3465651ac8784c4246c2fe` | Existing execution-intent failure/concurrency tests |
| `.github/workflows/python-ci.yml` | `61b7969a7f6e3f0509949914d970f5a861982500` | Configured Python baseline commands |
| `.github/workflows/campaign-ci.yml` | `0108ea1c578c2ca1d655b3c84de184b27c19747a` | Configured campaign tests and Android unit/lint/debug build |

The current execution container still has no repository checkout or Git object database; it cannot produce a local `git status`, `git rev-parse`, or locally reproducible test transcript. Source identity here is supported by the GitHub file API returning content for the exact requested commit ref, and by the returned blob IDs above. A clean worktree is **not established**.

## 2. Baseline evidence

Historical CI at the requested checkpoint `e013aae4...` was already recorded in Task 003.13:
- Python backend run `38127583767`: success; configured compile, release contract gate, full static safety audit, offline test list; 148 tests passed with 4 warnings.
- Campaign CI run `38127583771`: success; configured Python safety/campaign test list; 191 tests passed with 4 warnings.
- Android APK run `38127583886`: success; Android unit tests, lint and APK build steps succeeded.

These are historical CI results, not proof of this task's newly designed financial integrity properties. The later report-commit runs were subsequently checked and completed successfully:
- Python backend run `38133021216`: success.
- Campaign CI run `38133021219`: success.
- Android APK run `38133021192`: success.

The CI workflow at the checkpoint runs `test_task003_execution_safety.py` in Campaign CI, but the Python backend workflow's listed suite is a fixed explicit file list. CI does not, by itself, establish a complete financial mutation graph or exactly-once accounting. No new financial fault-injection suite was run as part of this task. No numeric process exit code was separately exposed by the Actions API; job and workflow conclusions were `success`.

## 3. Partial source-to-sink graph (not complete)

### 3.1 Order mutation and fill acquisition

1. `binance_client.py` contains order mutation methods and reconciliation around ambiguous transport outcomes. It uses Decimal in filter/format helpers (notably `decimal_floor` and `decimal_format`), but that does not establish Decimal preservation by callers.
2. `execution_barrier.py: ExecutionBarrier.execute` persists/read-backs durable intent before invoking the exchange callback, and marks ambiguous outcomes for reconciliation. It also converts response execution quantities/status values through `float(...)` in several paths (examples from the inspected blob: lines 386, 512–518, 568–570, 603, 837, 871–878). This is an order-mutation safety mechanism, not a canonical financial fill ledger.
3. `trader.py: _authoritative_execution` reads response fills or calls `my_trades` for an order, then uses `accumulate_fills`; if fills are unavailable, it falls back to `accumulate_order`. This can produce an order-level summary rather than a durable identity for each individual fill.
4. `campaign_execution.py` reconciles pending entries and conditional order execution. At inspected lines around 835 and 953 it calls `CampaignEngine.record_add_on_fill` / `record_initial_fill` with order-derived aggregate executed quantity and average price; those calls include `fee_quote=0.0`. It subsequently saves campaign state and can call `db.save_trade(... fees=0.0)` (around lines 977 onward). This is a direct path that does not demonstrably route through `Database.apply_financial_fill_effect`.
5. `portfolio_trader.py` has its own authoritative execution/recovery paths, including `_authoritative_execution`, `recover_pending_entries`, `_reconcile_trade`, `recover`, and emergency SELL handling. The inspected code uses `db.save_trade(...)` in recovery and campaign execution paths. The complete relation between this route and all trade/fee/position projections is not proven.
6. `trader.py` has separate recovery methods (`_recover_entry_intent`, `recover_state`, `_recover_closed_trade`) and multiple `db.save_trade(...)` calls. The audit does not prove these legacy paths are unreachable or routed through a shared canonical effect.
7. Android `StandaloneRuntime.kt` processes order intents and recovery, and separately mutates in-memory `PositionState` in `applyCampaignAddOnFill` / `createCampaignPositionFromFill`, then calls `savePersistedState()`. The audit store `TradingAuditStore.kt` records execution events and completed trades in separate methods/tables. A single atomic transaction spanning exchange fill identity, position/campaign state, fees and trade/PnL projections has not been established.

### 3.2 Financial writers and sinks observed

| Writer / sink | File and approximate location | Financial effect | Identity / idempotency evidence | Transaction evidence | Status |
|---|---|---|---|---|---|
| `Database.apply_financial_fill_effect(fill, projection_writer)` | `db.py:1551+` | Journal plus callback-defined projections | Derived effect key from exchange/environment/account_scope/symbol/fill ID; journal primary key and unique identity tuple; compares canonical payload | `with self.transaction()`; projection callback contract says same DB connection | Candidate mechanism only; no verified production call-site found in inspected files; callback restriction is a documented contract, not proof every callback obeys it |
| `Database.resolve_financial_effect_fee(...)` | `db.py:1629+` | Resolves fee for pending fill and invokes projection callback | Reuses effect ID; blocks fee changes after APPLIED unless exact same fee | Same transaction helper | Not shown to be called by live fee-reconciliation path; does not expose the requested INVALID/CONFLICTED state machine |
| `Database.save_trade(**kwargs)` | `db.py:1071+` | Inserts trade row | No unique exchange fill ID or effect ID shown in the INSERT; returns row ID | Commits when `_transaction_active` is false | Legacy writer; can create duplicate trade rows unless every caller prevents it |
| `Database.close_trade(...)` | `db.py:1127+` | Writes exit time/price/PnL/fees | Guard `WHERE id=? AND exit_time IS NULL`; no fill-level identity in this update | Commits when outside active transaction | Not linked to an authoritative exit-fill ledger in a proven shared transaction |
| `Database.save_campaign_fill(fill)` | `db.py:1689+` | Inserts campaign fill quantity/price/quote/fees | No uniqueness constraint on exchange fill ID in the observed table; insert is not idempotent by fill identity | Commits when outside active transaction | Separate writer; numeric fields explicitly cast to float |
| `CampaignEngine.record_initial_fill` | `campaign_engine.py:362+` | In-memory campaign quantity, entry price, stop, risk, fee tag; persists campaign through caller | Takes order ID, not proven stable per-fill ID; not independently idempotent by fill | Memory mutation and DB save are not shown in one transaction with trade/ledger | Separate projection path |
| `CampaignEngine.record_add_on_fill` | `campaign_engine.py:320+` | Adds quantity, recalculates average entry, risk and fee tag | No durable per-fill identity in method contract | Not shown atomic with ledger and trade row | Separate projection path |
| `TradingAuditStore.recordTrade` | `TradingAuditStore.kt:819+` | Android trade/PnL/fee summary | `trade_id` primary key; not proven equal to exchange fill identity | Uses one SQLite insert, but no encompassing transaction with executions and runtime positions is shown | Separate projection writer; uses Double and `CONFLICT_REPLACE` |
| Android `executions` table writes | `TradingAuditStore.kt` schema and event recording | Stores execution events/commission | `event_key` primary key; key derivation and collision behavior not exhaustively traced | No shared transaction with `recordTrade` established | Event journal and trade projection are separate observed stores |

**Critical graph gap:** a complete repository-wide inventory of every SQL writer, every caller, every read-modify-write sequence, and all cross-module/Android/Futures paths is not established. Repository code-search API queries returned no indexed results for several symbols, so those empty search responses are not treated as proof that call sites do not exist.

## 4. SQLite transaction and identity findings

### 4.1 Python transaction helper

At `db.py:31+`, `Database.transaction()`:
- acquires an instance-local `threading.RLock`;
- executes `BEGIN IMMEDIATE`;
- calls the injected `_commit_durable()` on success;
- attempts rollback on an exception;
- tracks the transaction owner to reject unexpected cross-thread nested use;
- connection configuration sets WAL, `synchronous=FULL`, a busy timeout and `check_same_thread=False`.

This is useful transaction scaffolding. The RLock is only instance-local; cross-process serialization relies on SQLite locking/constraints, not this lock. No two-process stress test was run here.

### 4.2 Journal contract

The `financial_effect_journal` table in `db.py:275+` contains:
- `effect_id TEXT PRIMARY KEY`;
- `exchange_name`, `environment`, `account_scope`, `symbol`, `exchange_fill_id`;
- a unique constraint over those identity fields;
- decimal-text quantity, price and quote quantity;
- commission decimal text, asset, commission status, projection status and canonical payload JSON.

`apply_financial_fill_effect` creates a deterministic SHA-256 key from exchange + environment + account scope + symbol + fill ID, inserts with `INSERT OR IGNORE`, reads back, compares canonical payload, and returns without reapplying if status is already APPLIED. For a KNOWN fee, it invokes the supplied projection callback and updates APPLIED within the same `Database.transaction()`. Unknown fees are persisted as PENDING_FEE and block callback application.

These properties apply only when this method is actually used and the callback writes exclusively through the supplied connection. Production reachability and callback conformance are unproven.

### 4.3 Defects / unresolved risks in the alternate writer family

- `save_trade` is an INSERT path with no observed per-fill uniqueness key.
- `save_campaign_fill` inserts every call and casts quantity, price, quote quantity, fee quote and base commission to binary float before persistence.
- Campaign initial/add-on fill methods mutate quantity/average price separately from the journal and trade insert.
- A fill can therefore be reflected through legacy campaign/trade projections without demonstrated linkage to the journal's identity guard.
- No proof exists that ledger insert, commission resolution, position update, realized PnL, risk reserve and protection status commit atomically across the full production graph.

## 5. Decimal integrity

### 5.1 Positive observations

- `db.py:_canonical_decimal` parses via `Decimal(str(value))`, rejects missing/bool/non-finite inputs, and emits fixed-point text without calling `Decimal.normalize()`. This avoids binary-float arithmetic inside that serializer, provided the incoming value has not already passed through float.
- `financial_effect_journal` stores quantity/price/quote/commission as TEXT decimal representations.
- `execution_accumulator.py` uses `Decimal` for quantity, quote, commission and average price calculations.
- `binance_client.py` uses `Decimal` and `ROUND_DOWN` for its floor/format helpers.

### 5.2 Material risks observed

1. **Python SQLite REAL:** `db.py` schemas use REAL for `trades.entry_price`, `exit_price`, `quantity`, `pnl`, `pnl_pct`, `fees`, campaign quantity/average price/PnL/risk fields, order quantities/prices, and `campaign_fills.quantity/price/quote_quantity/fee_quote/commission_base`. These columns cannot preserve arbitrary decimal strings exactly.
2. **Explicit float casts:** `save_campaign_fill` converts its financial fields to float before SQLite binding.
3. **Campaign math:** `campaign_execution.py` converts order quantities, quotes, prices, filters, balances and risk values to float; average price is derived from aggregate quote/quantity in float paths. `campaign_engine.py` uses float for campaign position, average entry, PnL/risk projections.
4. **Portfolio/legacy trader:** `portfolio_trader.py` and `trader.py` use float for balances, order quantities, quote amounts, fees, average prices and PnL. Some paths correctly obtain per-fill summaries first, but precision is lost when those values are converted to float and persisted through legacy writers.
5. **Fee aggregation defaults:** `execution_accumulator.py:44-46` parses missing `qty`, `price` and `commission` as string `0`. This collapses absent fee data into zero rather than distinguishing UNKNOWN from confirmed zero. `ExecutionSummary` also initializes fee fields to Decimal("0").
6. **Hard-coded zero fee on campaign fill:** `campaign_execution.py` calls `record_initial_fill` and `record_add_on_fill` with `fee_quote=0.0`; it also saves entry trades with `fees=0.0` in the inspected path. That is not evidence of a confirmed zero commission.
7. **Android SQLite REAL and Double:** `TradingAuditStore.kt` defines `REAL` columns for order quantities, cumulative quote, average price, execution quantity/price/commission, trade prices/qty/notional/PnL/fees. `recordTrade` accepts Double arguments and stores them directly.
8. **Android fee estimate:** `estimateFeesUsdt` sums Double values. Non-positive commission is skipped. A blank commission asset follows a no-op branch and does not necessarily set `known=false`, so missing commission evidence can be mistaken for a known fee result.
9. **Android replacement semantics:** `recordTrade` uses SQLite `CONFLICT_REPLACE`, which can overwrite an existing trade row for the same `trade_id`; no conflict-payload comparison is performed there.
10. **Exchange rounding:** `binance_client.py` has Decimal-based tick/step flooring, but caller paths frequently convert filter values and quantities to float before formatting. End-to-end tickSize/stepSize exactness is not proven.

### 5.3 Required reproducible decimal tests — NOT RUN in this task

No new tests were run locally. The following isolated-DB tests should be added or verified against the actual call graph before accepting the task:
- high-scale decimal string preserved through parse → journal → read-back;
- quantities such as `0.00000001` and values just below/at/above stepSize;
- quote quantity with long decimal scale and repeated partial fills;
- repeated fee payload does not double-accumulate;
- unknown commission remains unknown and does not enter net PnL as zero;
- base-asset commission is deducted once from net base quantity;
- quote-asset and third-asset commissions preserve asset identity and conversion provenance;
- conflicting fee payload for the same fill is quarantined;
- restart/reload preserves exact decimal strings;
- ROUND_DOWN behavior immediately below/at/above tickSize and stepSize.

## 6. Fill identity, duplicate delivery and fee-state analysis

### What the candidate journal does

The journal identity tuple is `(exchange_name, environment, account_scope, symbol, exchange_fill_id)`. The table has a matching UNIQUE constraint, and the canonical JSON payload is compared after read-back. Same identity with different canonical payload raises an error instead of silently overwriting. This is a strong candidate rule **inside that method**.

### What is not proven or is deficient elsewhere

- Aggregate order `executedQty` + cumulative quote/VWAP is not an individual-fill identity. `accumulate_order` and several recovery paths can summarize the order.
- Campaign fills use `order_id` and an auto-increment row ID, without observed uniqueness on exchange fill ID.
- The current journal implementation only writes `KNOWN` or `UNKNOWN` commission statuses in the inspected method. Requested `INVALID` and `CONFLICTED` states are not established as implemented.
- A missing commission value or missing commission asset can become zero/default in the accumulator or legacy paths.
- `resolve_financial_effect_fee` refuses to alter the fee after APPLIED, which is safer than silent mutation; however, no complete production fee-resolution caller graph or reconciliation path was established.
- Late fee resolution, third-asset fee conversion, fee price provenance and correction semantics are not proven end-to-end.
- Android `executions.event_key` is a primary key, but its complete derivation, conflict behavior and linkage to each trade row were not established.

**Conclusion:** the candidate journal's local idempotency rules do not currently prove system-wide exactly-once accounting because multiple alternate writer families exist and the call graph is incomplete.

## 7. Atomic recovery and commit uncertainty

The journal method places its journal insert, callback projection writes, and APPLIED marker in a single SQLite transaction when the callback uses the same `Database` connection. An exception before commit causes rollback. If the client loses the commit acknowledgement after the database actually commits, retrying the same canonical fill should find APPLIED and return duplicate without reapplying — but that recovery behavior was not newly fault-injected in this task.

The following properties remain unproven:
- every fill source calls the journal method;
- every projection writer obeys the same-connection rule;
- no projection is changed through direct SQL or a legacy writer outside that transaction;
- an ambiguous commit followed by process death is reconciled by durable identity before any reapplication;
- a fee update after APPLIED is reconciled without reapplying quantity;
- recovery across Android persisted runtime state and `TradingAuditStore` is atomic;
- all Futures paths, if present in this revision, use equivalent identity/transaction rules.

### Required crash matrix — NOT RUN in this task

| Fault point | Expected invariant | Evidence still needed |
|---|---|---|
| Before event insert | No projection changed | Inject exception and assert journal/projections unchanged |
| After journal insert, before projection | Transaction rollback or durable pending state, never an untracked partial projection | Fault injection at exact statement boundary |
| During projection update | Journal and all same-DB projections roll back together | Multi-table rollback assertion |
| Before COMMIT | No partially applied effect | Inject commit failure; inspect every projection |
| After actual COMMIT, before caller sees success | Retry same fill returns duplicate; exactly one effect | Simulate lost acknowledgement after successful commit |
| Process restart | Reconcile by durable fill identity; no duplicate quantity/PnL | Close/reopen DB and run recovery twice |
| Concurrent duplicate in one process | One effect | Threaded duplicate-fill test |
| Concurrent duplicate across processes | One effect | Two DB connections/processes, barrier synchronized |
| Concurrent recovery and new event | No exposure-increasing mutation before recovery gate clears | Recovery/mutation race test |
| Local/exchange ledger conflict | Quarantine and block risk increase | Conflict injection and gate assertion |
| Legacy row without identity | No unsafe automatic adoption or double application | Migration/recovery fixture with historical rows |

Existing `test_task003_execution_safety.py` includes write-ahead intent persistence failure, commit failure before exchange callback, missing mandatory persistence method, two parallel identical order submissions, and ambiguous network timeout locking new entries. Those are **order-intent safety tests**, not proof of financial fill exactly-once, fee idempotency, atomic projection rollback, or two-process financial serialization.

## 8. Concurrency and cross-platform gate

- Python `Database.transaction()` serializes users of the same object with an RLock and uses SQLite `BEGIN IMMEDIATE`. Independent processes/instances rely on SQLite locks, constraints and retry/error handling; a multi-process fill accounting test was not run.
- The journal's unique identity constraint is an appropriate conflict barrier only for calls through the journal method.
- Legacy `save_trade` and `save_campaign_fill` have no observed common fill-identity uniqueness key.
- Android `TradingAuditStore.recordTrade` is annotated `@Synchronized`, which protects calls through that instance/method monitor, not independent processes and not all writes to the database. It uses `CONFLICT_REPLACE`, not immutable payload conflict detection.
- Android runtime uses `Double` for position quantity/entry and campaign arithmetic, then persists runtime state separately from execution audit/trade tables.
- `ExecutionBarrier` provides a fail-closed order-intent mechanism in inspected Python paths, and Android has its own durable execution-intent reconciliation helpers. Neither observation proves a universal financial Recovery Gate immediately before every exposure-increasing mutation across all entry/add-on paths and all legacy modules.
- No complete Futures financial writer graph was established. Futures support must remain explicitly unverified unless files and call sites in the checkpoint are mapped.

## 9. CanonicalFillV1 decision

**Status: PROVISIONAL — NOT APPROVED FOR PRODUCTION INTEGRATION.**

Minimum contract requirements:
1. Identity scoped by exchange, environment, account, symbol and stable exchange fill/trade ID.
2. Source provenance and raw authoritative payload retained.
3. Exact decimal strings or rigorously specified integer-quantized amounts for price, base quantity, quote quantity and commission.
4. Fee state distinguishes `KNOWN`, `UNKNOWN`, `INVALID`, and `CONFLICTED`; confirmed zero must be explicit and must not be inferred from missing fields.
5. Duplicate identical payload is a no-op; same identity with differing payload is quarantined and sets reconciliation-required.
6. Journal insert, fee state, position/quantity, realized PnL, campaign projection, risk reserve and APPLIED marker share a documented atomic boundary, or an explicitly proven recoverable state machine.
7. Unknown commit result is resolved by re-reading durable journal and authoritative exchange history before deciding whether to apply again.
8. Late fee resolution changes only fee-derived effects and never re-applies fill quantity.
9. Every REST, WebSocket, order-history, recovery, Android and Futures source is shown to route through this contract, with legacy bypasses disabled or proven idempotent.

The repository contains a candidate journal method, but no verified complete caller graph demonstrates that CanonicalFillV1 is the actual canonical production contract.

## 10. Acceptance checklist

| Requirement | Status |
|---|---|
| Requested checkpoint and inspected blob IDs recorded | PARTIAL PASS — remote blob identity recorded; no local checkout/clean tree |
| Executable environment or verified snapshot provenance | BLOCKED — no local full checkout; remote CI is available but not equivalent to a clean local Git checkout |
| All reachable financial writers inventoried | BLOCKED — writer families identified, complete call graph not proven |
| Every financial table/field mapped | BLOCKED — principal Python/Android tables mapped; exhaustive schema/reader graph not complete |
| Full fills-to-projections graph | BLOCKED |
| All bypass/legacy writers resolved | BLOCKED |
| Critical Decimal transformations fully mapped | BLOCKED — significant float/Double/REAL paths observed |
| Unknown/zero/invalid/conflicted fee states proven | BLOCKED — missing fee can become zero; INVALID/CONFLICTED states not established |
| SQLite transaction boundaries proven across all projections | BLOCKED |
| Duplicate/conflicting fills across all sources proven safe | BLOCKED |
| Ambiguous COMMIT and process-death recovery tests run | NOT RUN |
| Independent-process financial concurrency tested | NOT RUN |
| CanonicalFillV1 approved | NO — provisional only |
| Production financial changes made | NO |
| Real Binance mutations made | NO |

## 11. Final decision and next allowed step

**BLOCKED — EVIDENCE INCOMPLETE.**

The most material observed blockers are concrete:
- the candidate `financial_effect_journal` path exists but is not proven to own all financial effects;
- `save_trade`, `save_campaign_fill`, campaign initial/add-on projection methods, legacy trader recovery, portfolio recovery and Android trade recording remain separate writer families;
- Python SQLite REAL/float and Android REAL/Double are used in financial paths;
- missing commission can be represented as zero in accumulator and campaign paths;
- Android trade writes use REPLACE semantics and are not shown atomic with execution events/runtime positions;
- financial commit-uncertainty, two-process idempotency, and full Recovery Gate behavior were not tested;
- no full local checkout/working-tree verification was possible.

Next permitted work: continue static inventory at this exact checkpoint until every source, caller, writer, table, and transaction boundary is traced, and prepare isolated-database fault-injection tests. Do not connect a new writer, change production schema/PnL/fees, weaken safety gates, enable live trading, mutate Binance, edit `main`, or merge PR #48 until the evidence checklist passes.

**This report does not claim the financial system is corrected or safe for autonomous trading.**
