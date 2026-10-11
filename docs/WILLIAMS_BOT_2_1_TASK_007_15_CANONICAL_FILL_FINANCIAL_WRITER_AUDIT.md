# WILLIAMS BOT 2.1 — TASK 007.15
## CanonicalFillV1 Contract Audit, Single Financial Writer & Atomic Financial Effect Proof

**Final decision: BLOCKED — MULTIPLE FINANCIAL WRITERS**  
**Additional blockers:** ATOMICITY NOT PROVEN; EXACTLY-ONCE APPLICATION NOT PROVEN; FINANCIAL CONTRACT INCOMPLETE.  
**No production changes were made. No Binance order mutations were performed.**

## 1. Source identity and baseline

| Item | Evidence |
|---|---|
| Repository | `19wolfalone92/Williams` |
| Branch | `audit/williams-2.1-task-007-recovery` |
| TASK 007.14 baseline | `a9b9555069a7833bf8d1a86bdc9d8f344725aa47` |
| Previous task report commit | `c436742dd05ece72ec91f3d6196f582d965e0c11` |
| `main` checkpoint, unchanged | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Source checkout used for this audit | GitHub-hosted Actions runner; no local executable checkout |
| Baseline CI run | [38131533371](https://github.com/19wolfalone92/Williams/actions/runs/38131533371) |
| Current pre-audit CI run | [38132378919](https://github.com/19wolfalone92/Williams/actions/runs/38132378919) |

At the start of this audit, the working branch HEAD was `c436742dd05ece72ec91f3d6196f582d965e0c11`, the commit created for TASK 007.14's report. Its parent is the verified baseline `a9b9555069a7833bf8d1a86bdc9d8f344725aa47`; the only file changed by that commit was the TASK 007.14 report. The TASK 007.14 run and the follow-up run on `c436742…` both completed successfully.

The follow-up run on `c436742…` checked out that exact SHA, completed Python compile and **201 Python tests passed, 0 failed (4 warnings)**, and completed the Android Gradle invocation including unit tests, lint, and debug APK assembly (`BUILD SUCCESSFUL`, 55 actionable tasks). Logs:
- [Python job log, run 38132378919](https://github.com/19wolfalone92/Williams/actions/runs/38132378919/job/114449189267)
- [Android job log, run 38132378919](https://github.com/19wolfalone92/Williams/actions/runs/38132378919/job/114449189353)

This is baseline/repository CI evidence, **not** proof that the missing financial invariants are tested. No task-specific new test or fault-injection run was performed during this audit. The local working tree cannot be inspected because there is no local checkout; remote branch and commit identity were verified via GitHub metadata and Actions logs.

## 2. Source-to-sink findings

### 2.1 Execution source and fill identity

The current exchange authority is Binance order/trade history fetched by the existing client methods, such as `my_trades(symbol, order_id=...)`, followed by validation in `MultiPositionTrader._authoritative_execution`, `Trader._authoritative_execution`, and the campaign exit path. Order-level REST responses alone are explicitly rejected as commission evidence in the legacy and portfolio paths.

This is a useful source-validation guard, but it is **not a canonical fill contract** shared by every path. Validation is duplicated and differs between callers.

Current persistent campaign fill identity:
- `db.py::save_campaign_fill()` requires `symbol` and a trade ID selected from `exchange_trade_id`, `tradeId`, `trade_id`, or `id`.
- `uq_campaign_fills_exchange_trade` is a partial unique index on `(symbol, exchange_trade_id)`.
- Duplicate rows are compared by converting financial fields to Python `float` and comparing tuple equality.
- The key does not include a durable exchange/account namespace. Current client usage is Binance Spot, but this is not a versioned `CanonicalFillV1` type and would not be sufficient for multiple account/exchange namespaces.
- The code stores raw JSON, but does not verify a canonical immutable payload hash or make a later commission enrichment a distinct, explicitly modelled state transition.

### 2.2 Financial writer inventory

| Component / function | Data changed | Transaction / idempotency | Audit status |
|---|---|---|---|
| Binance REST / order history via `my_trades` and `get_order` | Exchange-confirmed order/fill facts are read | Exchange is authoritative; local application still caller-specific | SOURCE CONFIRMED; canonical handoff UNRESOLVED |
| `db.py::save_campaign_fill` | `campaign_fills` fill journal | Unique index on symbol+trade ID; commits immediately unless `_transaction_active` | Fill-row idempotency partly covered; financial effect ownership UNRESOLVED |
| `db.py::save_trade`, `close_trade`, `update_trade_quantity` | `trades` position/trade/PnL/fees | Each method commits independently when called outside an active transaction | Independent financial mutation path; UNRESOLVED |
| `db.py::save_campaign` | `campaigns.position_qty`, average entry, realised/unrealised PnL, risk/capital, state, tags | UPSERT; commits independently unless nested in current transaction | Independent financial projection writer; UNRESOLVED |
| `campaign_engine.py::record_initial_fill` | In-memory Campaign fields, then persisted campaign and events | Calls `save_campaign` and separate event writes; no fill-identity claim shown in this method | Second path; atomic coupling UNRESOLVED |
| `campaign_engine.py::record_add_on_fill` | Position quantity/VWAP, additions, risk and fee tags | Separate campaign and event writes; float arithmetic | Second path; atomic coupling UNRESOLVED |
| `campaign_execution.py::exit_market` | Inserts each exit fill, then mutates Campaign, logs event, closes legacy trade and writes state | `save_campaign_fill` may commit per fill; campaign/trade/state operations are later independent writes | Multiple effects can persist partially; P0 |
| `portfolio_trader.py::execute` legacy branch | Calls `_authoritative_execution`, computes quantity/quote, then `save_trade`; later handles pending key and OCO | Does not apply the whole fill+trade+campaign projection as one atomic operation | Independent financial path; P0 |
| `trader.py::recover_state` and recovery loops | Rebuilds trade state and writes recovered campaign fill rows | Recovery has separate persistence calls; no single atomic effect ledger found | Recovery can write in parallel to normal execution paths; P0 |
| `trader.py::process` legacy single-symbol path | Direct `save_trade` after market buy | Separate write after exchange response; no canonical per-fill application claim | Independent writer path; P0 |
| Android `TradingAuditStore.recordUserEvent` | Persists execution event, order audit rows and raw JSON | SQLite event key uses symbol/order/trade/type/event time; `CONFLICT_IGNORE`; REAL columns; no atomic financial ledger effect | Audit journal, not proven canonical financial writer |
| Android `ExecutionAccumulator.acceptFill/restore` | In-memory order quantity, quote, fees and VWAP | Synchronized only within process; dedup key is orderId+tradeId+eventTime in RAM | Not durable financial idempotency; UNRESOLVED |
| Android `StandaloneRuntime.applyCampaignFill`, `applyCampaignAddOnFill`, `createCampaignPositionFromFill` | Position state and risk fields, then SharedPreferences snapshot | Uses Double; updates in-memory state and persists separately from audit event journal | Reachable independent financial projection path; P0 |
| Android `TradingAuditStore.recordTrade` | Android `trades` audit rows, fees and PnL | Uses Double/SQLite REAL and `CONFLICT_REPLACE`; separate from position persistence | Can overwrite audit trade by key; no exactly-once effect proof |

The evidence establishes multiple paths that can change financial or position projections. It does not establish a safe, durable coordinator among them. A unique fill journal row is not the same thing as exactly-once application of the position/PnL/Campaign effect.

## 3. SQLite atomicity

### Observed implementation
- `Database.transaction()` issues `BEGIN`, commits on normal exit, and rolls back on `Exception`; it is a transaction helper for callers that actually wrap all related operations.
- `Database._transaction_active` is an instance boolean, not a cross-process transaction coordinator.
- `save_campaign_fill()` commits immediately when it is not called inside the helper.
- `save_campaign()`, `save_trade()`, `close_trade()`, state writes, and campaign-event writes each have their own commit behavior when called independently.
- `campaign_execution.py::exit_market` writes fill rows in a loop before later Campaign close/projection/trade close/state updates. No enclosing transaction covering this entire effect was found in the audited path.
- Schema stores financial values in SQLite `REAL` columns, including `campaign_fills.quantity/price/quote_quantity/fee_quote/commission_base`, Campaign projections, and legacy trades. Insertion explicitly converts values through Python `float`.

### Consequence
A crash can leave a durable fill row without all corresponding trade, Campaign, position, PnL, fee or risk projections. Conversely, downstream application may be retried after a partial or ambiguous outcome without a durable effect ledger that atomically binds the fill identity to all projections.

No production-component fault-injection evidence was found for crash points before/after each projection write, during commit, or after commit-before-return. Atomicity is **NOT PROVEN**.

## 4. CanonicalFillV1 contract status

There is no complete, shared, production-enforced `CanonicalFillV1` contract in the audited source. The minimum contract below is therefore a proposed audit target, not an existing production contract:

| Field / rule | Required contract | Current evidence |
|---|---|---|
| Source | Exchange-confirmed fill from authoritative order/trade history | Guarded in several callers; duplicated and not universal |
| Identity | Versioned key including exchange/account namespace, symbol, exchange order ID and exchange trade/fill ID as required by source guarantees | Current DB uniqueness is symbol+trade ID only |
| Quantity / price / quote | Exact decimal strings or Decimal-compatible values; strictly validated positive values; quote reconciled to quantity×price under explicit policy | Some callers validate positive quantity/price; several calculations convert to float |
| Commission | Explicit status `CONFIRMED_VALUE`, `CONFIRMED_ZERO`, `UNKNOWN`, `CONFLICTING`; include asset and source evidence | Existing APIs frequently encode missing commission as zero defaults |
| Raw evidence | Immutable source payload and canonical normalized representation | Raw JSON exists, but no canonical schema/version/hash verified |
| Conflict | Same identity with changed financial payload must block and latch reconciliation; commission enrichment must be explicitly modelled | DB conflict check exists, but compares floats and does not model fee-status transitions |
| Application | Durable unique effect claim plus all financial projections and completion state in one transaction | Not found |
| Recovery | Replay the same fill safely after crash/unknown commit; do not infer success from a journal row alone | Not proven |

### Commission-specific defect
- Python `execution_accumulator.py::accumulate_fills` reads commission with `fill.get("commission", "0") or "0"`, so missing and explicit zero are collapsed at that layer. It also initializes fee totals to zero.
- The legacy and portfolio authoritative-execution wrappers separately reject missing commission fields in the paths inspected, which mitigates but does not establish a global invariant.
- `db.py::save_campaign_fill` defaults absent `fee_quote` and `commission_base` to zero; the journal has no explicit commission-status field.
- Android `ExecutionAccumulator.acceptFill` defaults commission to `BigDecimal.ZERO`; its JSON decimal parser maps invalid/missing strings to zero. Blank commission asset with positive commission is silently not valued in one branch without marking `feeKnown=false`. A missing commission therefore is not uniformly distinguishable from confirmed zero.
- Android audit schema stores commission as `REAL DEFAULT 0`; it stores raw JSON too, but no downstream proof was found that every financial mutation is blocked until fee evidence is confirmed.

Result: `UNKNOWN != CONFIRMED_ZERO` is not enforced end-to-end. P0 contract blocker.

## 5. Python / Android ownership and parity

Python stores Campaign and trade projections in one SQLite database, but writes can be initiated from legacy Trader, MultiPositionTrader, CampaignExecution/CampaignEngine and recovery paths. The audit does not prove these are coordinated through one financial-effect owner.

Android has a separate local audit DB, an in-memory execution accumulator, SharedPreferences position snapshots, and reachable `StandaloneRuntime` Campaign fill handlers. The runtime changes position quantity and average entry using `Double`, then persists position snapshots separately from the execution audit event journal. This is not merely a read-only dashboard/audit surface in the inspected code. Its role is not proven to be safely coordinated with the Python ledger; it is an independent local state path.

Parity is **NOT VERIFIED**. Successful Android build/tests do not prove cross-platform financial equivalence or single-writer ownership.

## 6. Existing tests versus required proof

Existing tests include:
- repeated `save_campaign_fill` identity returns true then false and conflicting quantity/fee payloads fail closed;
- fill without stable exchange ID is rejected;
- commission evidence missing from order-level snapshot is rejected in selected Python wrappers;
- missing commission/asset and unvalued third-asset fee are rejected in selected recovery/execution paths;
- separate tests exercise durable intent claims and independent SQLite connections for intent concurrency.

These tests do **not** prove the financial effect is applied exactly once. The repeated-fill test checks the journal row only; it does not assert that position, fees, PnL, risk and Campaign projections are atomically applied once. Intent-claim concurrency tests concern order intents, not concurrent application of the same fill effect.

Not found/proven in this audit:
- two independent processes applying the same fill and checking all financial projections;
- atomic rollback across fill journal + trade + Campaign + position/PnL/risk projections;
- crash injection after each write and during/after commit;
- ambiguous commit recovery with durable effect ownership;
- conflicting payload/hash verification across every writer;
- confirmed-zero versus unknown commission state transitions and later fee enrichment;
- actual Python/Android differential tests using the production financial application components.

The 201 passing Python tests and Android CI are real baseline evidence, but they are insufficient to close these missing financial invariants.

## 7. Idempotency and recovery invariant matrix

| Invariant | Status | Reason |
|---|---|---|
| Exchange-confirmed source required | PARTIALLY VERIFIED | Several paths fetch trade history and reject order-only snapshots; no single shared validator |
| Stable fill identity | PARTIALLY VERIFIED | Unique index on symbol+trade ID, but not a versioned/namespaced canonical identity |
| Duplicate journal row rejected/ignored safely | PARTIALLY VERIFIED | Journal-level duplicate check exists; float comparisons and missing-field defaults weaken canonicality |
| One financial effect per fill | UNRESOLVED | No durable effect ledger atomically bound to every projection |
| Cross-process duplicate effect prevention | UNRESOLVED | SQLite fill uniqueness alone does not protect separately applied projections |
| Atomic position + fee + PnL + Campaign/risk update | FAIL / NOT PROVEN | Independent commit paths; no encompassing transaction demonstrated |
| Crash before commit | UNRESOLVED | No fault-injection proof across full effect |
| Crash after commit before caller response | UNRESOLVED | No durable effect status/replay proof across all projections |
| Unknown commit outcome | UNRESOLVED | No canonical recovery protocol proven |
| Unknown commission distinct from zero | FAIL / NOT PROVEN | Defaults collapse absent/invalid commission to zero in accumulator and Android persistence paths |
| Conflicting fill data | PARTIALLY VERIFIED | DB compares existing row and raises on differences, but float-based comparisons and fee status/enrichment are incomplete |
| Campaign closure waits for all financial effects | UNRESOLVED | Exit path persists several operations independently; full atomic handoff not proven |
| Android/Python parity | UNRESOLVED | Separate storage/accumulator/projection paths; Double/REAL usage |

## 8. Required next action and minimum safe scope

**Do not connect `FinancialEffectApplier` to the production execution path.** No broad refactor or new parallel ledger is approved by this audit.

Before implementation, agree a minimal vertical slice that:
1. Defines CanonicalFillV1 fields, identity namespace and commission-state transitions explicitly.
2. Selects one existing persistence owner and enumerates every caller that must route through it; removes no existing safety gate.
3. Uses exact decimal values end-to-end for the chosen slice and explicitly avoids claiming that historical precision lost to SQLite REAL can be recovered.
4. Atomically claims the fill identity, applies the selected financial effect/projections, and records completion in one SQLite transaction.
5. Uses durable uniqueness/constraints, conflict detection and fail-closed recovery after unknown commit.
6. Adds failing-before/fixed-after tests against the real production methods for duplicate replay, conflicting payload, concurrent processes, rollback, crash before/after commit, and commission UNKNOWN→confirmed.
7. Adds Android tests or proves the Android path cannot independently apply authoritative financial effects.
8. Runs the focused tests and full required CI on the final implementation SHA.

No schema migration should be introduced until the existing financial ownership contract and migration plan are separately reviewed. Do not bulk-convert REAL fields or pretend old values regain decimal precision.

## 9. Files changed and PR status

No production source, tests, workflow, dependency manifest, or database schema was changed by TASK 007.15. This audit is documentation-only. No PR was created, merged, or closed; `main` was not changed. The exact report commit SHA is to be verified after publishing this report.

## 10. Final decision

**BLOCKED — MULTIPLE FINANCIAL WRITERS**

Also applicable:
- **BLOCKED — ATOMICITY NOT PROVEN**
- **BLOCKED — EXACTLY-ONCE APPLICATION NOT PROVEN**
- **BLOCKED — FINANCIAL CONTRACT INCOMPLETE**
- **BLOCKED — TEST EVIDENCE INCOMPLETE** for the required financial fault-injection and production-effect invariants.

The current code has useful safety mechanisms—exchange-history checks, a unique campaign-fill index, conflict rejection, durable order-intent claims, and selected commission validation—but these are not proof of one authoritative financial writer or exactly-once financial effect. Keep the production execution path blocked for `FinancialEffectApplier` until the listed P0 evidence is supplied.
