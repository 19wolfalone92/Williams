# WILLIAMS BOT 2.1 — TASK 007.8
## Financial Data Lineage, Decimal Contract & Migration Readiness Checkpoint

**Decision: BLOCKED — NOT ACCEPTED**

**Scope:** source/config review, remote Git state, workflow metadata, and a proposed language-neutral fixture set. This is not a completed call graph, a runtime-conformance result, or a migration approval. No trading runtime or database schema was changed by this checkpoint. No migration was run. No Binance Mainnet or Spot Testnet trading mutation was performed.

## 1. Git and CI evidence boundary

| Item | Verified result |
|---|---|
| Repository | `19wolfalone92/Williams` |
| Working branch | `audit/williams-2.1-task-007-recovery` |
| HEAD at TASK 007.8 inspection start | `cc0519f7873f715805cc79e9a4bb16a315b83a85` |
| Main | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Recovery branch parent at inspection start | `98bdfdba3a941320e2bf3f75f1840b1ca82428b6` |
| Local working-tree status | **NOT VERIFIED** — GitHub API provides remote refs, not local uncommitted files |
| Main modified / merge performed by this task | No |
| New branch or PR created | No |
| Prior TASK 007.7 report | `docs/audits/WILLIAMS_BOT_2_1_TASK_007_7_DATA_LINEAGE_DECIMAL_MIGRATION_AUDIT.md` |
| Golden vector file added | `tests/fixtures/financial_decimal_golden_vectors.json` |
| Initial fixture commit | `ad905bc9a583bee015dce3878f4e307e901b791f` |

The report and fixture commits are audit/test-data additions only. The current remote branch must be re-read after this report commit; the final SHA and exact-SHA CI must be recorded from that subsequent verification. Do not treat the earlier SHA's CI as final-SHA evidence.

### CI workflow discovery

The repository contains:
- `.github/workflows/campaign-ci.yml` — runs on push to all branches and PRs; Python deterministic suite plus Android unit tests, lint, debug APK.
- `.github/workflows/python-ci.yml` — push trigger is restricted to `main`/`master`; PR trigger and `workflow_dispatch` also exist. It runs `ci_release_gate.py`, source compilation, `full_safety_audit.py`, and a configured offline pytest list.
- `.github/workflows/android-apk.yml` — push trigger is restricted to `main`/`master`; PR trigger and `workflow_dispatch` also exist. It includes release gate, Android lint/unit tests, debug/release APK builds, APK metadata and signature checks.
- `.github/workflows/testnet-readonly.yml` — exists; not run because no Testnet access or mutation is needed for this audit.

At source HEAD `cc0519f7873f715805cc79e9a4bb16a315b83a85`, Campaign CI run **38120224024** completed **success**. Its head SHA equals that SHA. Jobs:
- Python tests, job ID `114413228634`: success; compileall and configured deterministic safety/campaign pytest list.
- Android build and static checks, job ID `114413228827`: success; `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`.

That run is **not** evidence for the new fixture commit or the final TASK 007.8 report SHA. At the start of this audit, no exact-SHA run for standalone `python-ci.yml` or `android-apk.yml` was returned. Their feature-branch push filters explain why they do not automatically run on every recovery-branch push; absence is not a pass. The final-SHA CI state remains pending verification.

## 2. Source inventory: verified subset, not a complete lineage map

Evidence is from source files at `audit/williams-2.1-task-007-recovery`. The status below describes what the inspected source demonstrates; it does not claim every caller, reader, or reachable sink has been found.

| Critical value | Source / producer observed | Transform and persistence observed | Consumers / recovery evidence | Status / risk |
|---|---|---|---|---|
| Order requested price, stop price, quantity | Order builders; `Database.save_order()`; campaign order persistence | `db.py` schema stores `orders.price/stop_price/quantity` and `campaign_orders.price/stop_price/quantity` as SQLite `REAL`; order/campaign model fields include Python `float` | Order recovery, sizing, stop/protection paths exist; complete reader-to-sink graph not closed | PARTIALLY TRACED; exact decimal contract absent |
| Exchange fill quantity, price, quote amount | Exchange order snapshots and trade history; campaign fill persistence; Python `accumulate_fills()`; Android user-stream execution report | `campaign_fills.quantity/price/quote_quantity` use `REAL`; Python accumulator uses `Decimal` locally; Android accumulator uses `BigDecimal`; runtime has `Double` fields | Campaign execution rebuilds quote amount from trade history in some paths; full atomic projection and all recovery callers not established | PARTIALLY TRACED; mixed representations |
| Commission amount and asset | Exchange fills / user-stream fields `n` and `N`; fee history adapters | Python accumulator parses fee as Decimal but defaults absent/empty to zero; Android defaults commission to `BigDecimal.ZERO`; DB campaign fill fee columns include `REAL DEFAULT 0` and `commission_asset` | Python portfolio exit fee reconciliation throws when commission amount/asset is missing; Android accumulator has `feeKnown`, but missing/blank commission asset with positive fee can be ignored without a fee-known transition | CONFLICTED across paths; UNKNOWN/INVALID semantics are not consistent |
| VWAP / average execution price | Aggregated fills or cumulative quote divided by quantity | Python divides Decimal quote by Decimal quantity without a cross-language rounding contract; Android `ExecutionAccumulator.snapshot()` divides at scale 18 with `HALF_UP`; other paths use float division | Used in order/campaign accounting and trade reporting; exact consumer boundary not fully mapped | PARTIALLY TRACED; Android rounding rule is not proven canonical |
| Available/locked balance and remaining exposure | Exchange balance/open-order snapshots | `campaign_execution.py` converts `free`, `locked`, `origQty`, `executedQty` to float and substitutes zero for missing values in multiple paths; Android runtime execution state uses `Double` | Used in maximum position and reconciliation checks; all callers and mutation paths not closed | PARTIALLY TRACED; missing-versus-zero hazard |
| Position quantity / average entry / residual exposure | Campaign and position persistence, fill/order snapshots | `campaigns.position_qty/average_entry_price` are `REAL DEFAULT 0`; `TradingCampaign`, `PendingOrderRecord`, Android `PositionState` use float/Double | Used for risk, protection, SELL sizing, recovery and campaign transitions; complete path not closed | PARTIALLY TRACED; exactness and knownness risk |
| Initial/current structural stop and invalidation | Signal/intent producers and campaign stop updates | Python `SignalSpec` prices are floats; `execution_intents.invalidation_level` is `REAL`; `save_execution_intent()` uses `float(intent.invalidation_level or 0.0)`; Android stop fields use Double | Barrier checks and protection/trailing paths exist; complete invalidation-to-mutation trace pending | CODE-CONFIRMED conversion; semantic impact must be field-specific |
| Realized/unrealized PnL, fees, risk and capital reservation | Trade close, campaign engine and runtime trade recording | `trades.pnl/fees`, campaign PnL/risk/reservation fields are SQLite REAL; campaign model and multiple accounting paths use float/Double | PnL feeds reports/risk; campaign risk and capital reservations feed admission; full ownership and update atomicity not proven | PARTIALLY TRACED; high impact |
| Financial effect / fill state | `campaign_fills`, `execution_events`, `campaign_events`, order/trade records, Android audit store and mutation ledger | A fill/event record is observable, but the inspected tree does not prove a single durable effect journal with one atomic transaction spanning fill identity, effect application and every required projection | Existing recovery tests cover selected intent reservation and recovery failures; end-to-end fill → effect → position → campaign → fees/PnL → risk proof remains absent | NOT VERIFIED as an exactly-once projection chain |
| Recovery state and unresolved intents | `db.py` recovery state, `ExecutionBarrier`, campaign reconciliation; Android durable mutation ledger in SharedPreferences | Python barrier checks `recovery_state`, unresolved intents and `position_state`; Android durable mutation ledger persists PENDING before request and reconciles at startup | Static test `test_android_mutation_ledger_contract.py` asserts write-ahead routing and recovery ordering; it does not prove financial effect atomicity or process-kill behavior on device | PARTIALLY TRACED; separate order-mutation ledger is not proof of a financial-effect ledger |
| Market OHLCV / analytical fields | Market-data pipeline | SQLite REAL and floating numeric models | Primarily strategy/analytics; full proof that no analytics value reaches authoritative order sizing/accounting without controlled conversion is pending | NOT VERIFIED for all downstream paths |

### Confirmed conversions and their proven limits

1. `db.py` declares `SCHEMA_VERSION = 10`; `orders`, `trades`, `execution_intents`, `campaigns`, `campaign_signals`, `campaign_orders` and `campaign_fills` contain financial/price/quantity fields declared as SQLite `REAL`. Several campaign/order fields have `DEFAULT 0`. This is a storage representation finding, not proof that every zero-valued row is wrong.
2. Python `execution_accumulator.py` uses `Decimal(str(fill.get("qty", "0") or "0"))`, the same pattern for price and commission, and similar defaults in `accumulate_order()`. Missing or empty required fields can become numeric zero. A zero quantity/price fill is then skipped. Malformed non-empty decimal text may raise. The caller's handling and whether the skipped fill can affect exposure are not fully traced.
3. Python campaign execution contains float-based instrument-filter checks, balances, order quantities and quote aggregation, including `sum(float(price) * float(qty))` when reconstructing quote from trades. This is an authoritative execution/recovery path, not merely UI formatting.
4. Python `campaign_engine.py` reconstructs campaign state from database rows with `float(row.get(...) or 0)` for quantity, entry, stops, PnL, risk and capital. This erases the distinction between absent/NULL and a known zero at that model boundary.
5. Python `portfolio_trader.py` has an exit-fee path that explicitly raises on missing commission or commission asset. This is safer than a zero fallback in that path, but uses float for the commission and accumulated quote-equivalent fee. Other paths are not proven to share the same behavior.
6. Android `ExecutionAccumulator.kt` has a `BigDecimal` in-memory accumulator but `decimal(value)` returns `BigDecimal.ZERO` on parse failure. `acceptFill()` defaults commission to zero and asset to empty text. For positive commission with blank asset, the inspected branch does not mark `feeKnown=false`. The seen-fill set and order map are in memory; the class does not itself provide durable fill deduplication. VWAP uses scale 18 and `HALF_UP`.
7. Android `StandaloneRuntime.kt` defines `PositionState.qty/entry/stop/take`, pending notional/risk/capital reservation and symbol lot/tick/min/max fields as `Double`. `recordCompletedTrade()` computes gross/net PnL using Double and subtracts `auditStore.estimateFeesUsdt()`; the fee-known flag is recorded but this excerpt does not prove that UNKNOWN fee prevents every downstream decision.
8. `ExecutionBarrier` has durable-intent/recovery gates and rejects unresolved same-symbol intents before mutation. That is useful order-mutation protection, but does not establish that every required fill/fee/PnL/risk projection is complete before trading resumes.
9. A SQLite `REAL` value cannot generally reveal the exact original decimal input string. Converting the currently stored numeric value to text is not evidence of recovering original precision.

## 3. Canonical Decimal contract — proposed requirements, not runtime conformance

Use the following as the draft contract for review. It is deliberately not described as implemented or approved.

- **Wire/source:** parse exchange decimal strings directly to Python `Decimal` / Kotlin `BigDecimal`. Do not route canonical financial values through binary float/Double. Preserve raw source text, field presence, source identity, and provenance where available.
- **States:** `KNOWN(value)`, `UNKNOWN`, `INVALID(raw, reason)`, `CONFLICTED(evidence)`. A known zero is `KNOWN(0)`; it is not a default for missing or invalid data.
- **Required fields:** missing or invalid required fill quantity/price, order identity, or other required financial-effect inputs block that effect and retain a reconciliation reason. Unknown fee may allow a narrowly defined inventory projection only if the approved contract proves the quantity/asset effect independently; it must not mark final fees/PnL/risk as fully reconciled.
- **Conflicts:** retain both sources and their provenance. Do not use last-write-wins. Resolve only through a defined authority rule or explicit reconciliation.
- **Storage/wire canonical form:** decimal text with invariant locale. Define whether source scale/trailing zeros are preserved separately from numeric equality. No exponent notation unless explicitly allowed by a versioned contract. Schema TEXT adoption is deferred until all writers/readers and old-app compatibility are mapped.
- **Arithmetic:** quantity × price produces exact decimal quote amount within the approved precision/range envelope. VWAP is the exact quotient until an explicitly named boundary requires rounding. Any scale and rounding mode must be specified by contract and justified by downstream use; scale 18/HALF_UP is not automatically accepted.
- **Instrument filters:** compare exact decimal values against exchange step/tick/min/max/notional filters. Quantization must use an explicitly approved direction and rule; do not use epsilon-based float flooring for authoritative order parameters.
- **Persistence:** serialize → write → reopen → deserialize must preserve value and state. Transactional atomicity and crash recovery must be tested against a real temporary SQLite database.
- **UI/diagnostics:** formatting may round for display only; display formatting must not feed back into orders or accounting.
- **Versioning:** record contract version for durable effect/ledger records if semantics can change. Do not silently reinterpret old rows under a new rule.

### State transition requirements

| State | Meaning | Use in financial calculations / recovery |
|---|---|---|
| KNOWN | Valid value with identifiable authoritative provenance or an explicitly approved derived value | May be used only where field contract and required evidence are satisfied |
| UNKNOWN | Missing, not yet received, or not yet authoritatively valued | Never coerce to zero; critical unknown blocks dependent effect/mutation and triggers reconciliation |
| INVALID | Present but malformed, non-finite, out of range, or prohibited by field contract | Preserve diagnostic raw evidence safely; block dependent operation |
| CONFLICTED | Two or more incompatible evidence values/identities | Do not choose newest arbitrarily; quarantine dependent projections and reconcile |

Late authoritative evidence may resolve UNKNOWN/CONFLICTED only through an idempotent, auditable transition. Replaying it must not duplicate inventory, fee or PnL effects.

## 4. Proposed shared golden vectors

Added `tests/fixtures/financial_decimal_golden_vectors.json` with 20 language-neutral draft cases: decimal scale, very small quantity, non-binary-exact decimal, fractional fee, known zero, missing fee, empty price, malformed values, conflicting authorities, persistence round trip, multi-fill aggregation, non-terminating VWAP, partial fill, late fee enrichment, filter-step mismatch, scale boundary, negative quantity, signed PnL, negative commission and zero-quantity VWAP.

The fixture is explicitly labelled `PROPOSED_NOT_EXECUTED`. Python and Android runners do not yet demonstrate identical results; no cross-platform golden test is claimed. The fixture intentionally requires no rounding choice for the non-terminating VWAP case and requires an explicit quantization policy for step-size mismatch. Its vectors are a starting contract proposal and require review against actual supported symbols/filters before becoming an authoritative production contract.

## 5. Financial journal and projection lineage

Observed durable structures include:
- Python SQLite `campaign_fills` for fill records;
- `execution_events` and `campaign_events` for event records;
- `orders`, `trades`, and `campaigns` projections;
- Android user-event audit persistence and a durable mutation-intent ledger.

The reviewed evidence does **not** establish a single authoritative financial-effect journal whose unique effect identity and application status are transactionally linked to fill identity and all production projections. Do not assume that `campaign_fills` or the Android order mutation ledger alone provides this guarantee.

Required next proof: identify the actual owner and transaction boundary for each transition in:

`Exchange Fill → Fill Ledger → Financial Effect → Position/Inventory → Campaign → Fees → PnL → Risk/Exposure`

For every transition, enumerate the unique key, idempotency constraint, transaction, writer, readers, recovery replay, concurrent-process behavior and late-fee adjustment. If a component is absent, document the minimum design needed before changing runtime.

## 6. SQLite schema and migration readiness

- Current declared schema version: **10**.
- The initialization code creates tables with `executescript`, then calls `_migrate_trade_columns()` and `_migrate_recovery_schema()`, writes schema/recovery state and commits. The complete interruption/atomicity behavior of every supported historical path is not proven here.
- Existing tests in `test_task007_recovery_contract.py` include v7 and v9 migration/reopen checks, future-version rejection, selected fill conflict tests, durable-intent and recovery-gate scenarios, and independent-process intent-claim helpers. These do not prove a lossless conversion of every supported database version or every REAL column.
- `test_atomic_real_db.py` tests selected transaction success/failure cases. It is not the complete migration/fill/fee/projection fault matrix.
- No production database was opened or changed. No migration was run.

### Legacy data classification rule

Every candidate row must be classified before any migration:
1. **Authoritatively confirmed:** corroborated by raw exchange payload/trade history or another explicitly authoritative source.
2. **Stored value preserved, original lexical precision unknown:** current SQLite value can be retained, but original decimal spelling/scale is not recoverable by assumption.
3. **Potential precision loss:** financial REAL value whose exact source value cannot be corroborated.
4. **Missing/NULL:** remains UNKNOWN unless authoritative evidence resolves it.
5. **Invalid:** malformed/out-of-contract value; quarantine and block dependent operation.
6. **Conflicted:** local projection disagrees with exchange/ledger/another authoritative source; reconciliation required.

### Migration readiness decision

**NOT READY.** No mass conversion to TEXT, no schema change, and no claim of losslessness is approved. Before a migration can be designed for execution, capture representative copies of every supported old schema, inventory all readers/writers, compare rows with authoritative exchange evidence where available, test interrupted migration and rollback on isolated copies, reopen after each fault, and prove that unknown/conflicted rows remain quarantined rather than defaulted to zero.

## 7. Recovery Gate and mutation sinks

Python `ExecutionBarrier` checks durable recovery state, unresolved same-symbol intents, position reconciliation state and campaign reconciliation state before new non-risk-reducing mutation. `db.py` initialization resets recovery to REQUIRED and position state to RECONCILE_REQUIRED. Existing Android static contract tests check that durable mutation intent is persisted before POST/DELETE and that mutation-ledger reconciliation precedes pending-entry recovery.

However, this evidence does not prove that every reachable mutation sink is gated on **financial projection completeness**. The current gate's observed durable state is primarily order/recovery-state oriented. Complete inventory is still required for:
- direct Python exchange calls and all wrapper/legacy mutation routes;
- Android signed POST/DELETE and campaign mutation routes;
- BUY, emergency SELL, stop/protection, OCO, cancel/replace and any Futures-capable routes;
- unknown balance/position, missing commission, conflicting fill, incomplete financial effect, unvalued fee, and Python/Android projection divergence;
- restart persistence of each blocking condition.

Required order remains **Recover → Reconcile → Validate → Trade**. A durable order-intent ledger is not enough if a fill/fee/PnL/risk effect is still unresolved. No separate parallel gate should be introduced until the existing gate's ownership and contracts are fully mapped.

## 8. Fault injection: evidence found and gaps

Existing `test_task007_recovery_contract.py` contains scenarios/helpers for:
- v7/v9 migration and reopen;
- future schema version rejection;
- selected fill/fee conflict;
- recovery gate blocked by unknown open orders;
- failed persistence after exchange success;
- duplicate client order ID;
- intent reservation across independent processes;
- selected restart/recovery behavior.

`test_android_mutation_ledger_contract.py` is a static source-contract test, not Android process-kill evidence. `test_atomic_real_db.py` covers selected SQLite transaction failures.

The full repository-wide canonical fault-injection inventory was not closed, and this audit did not execute tests locally. Required additional scenarios include crash before/after effect commit, DB commit failure, duplicate fill after DB reopen, same fill identity with conflicting payload, effect replay after interruption, late fee enrichment exactly once, exchange unavailable during reconciliation, independent recovery processes racing to apply the same effect, and proof that no mutation reaches the actual network sink while a critical financial state is UNKNOWN/INVALID/CONFLICTED. Each test must reopen the database and assert journal, fill, projections, fees/PnL, risk, gate state and absence of mutation.

## 9. Acceptance matrix

| Criterion | Status | Why blocked / minimum next step |
|---|---|---|
| Full financial lineage | **PARTIALLY TRACED** | Complete every writer, reader, serializer, downstream consumer and recovery path; especially fill-to-effect projections |
| Canonical Decimal contract | **PROPOSED / NOT APPROVED** | Confirm supported precision/range, asset valuation, rounding boundaries, and quantization policy |
| KNOWN/UNKNOWN/INVALID/CONFLICTED semantics | **PARTIAL** | Implement/verify transitions in real producer and consumer paths; no silent defaults |
| Shared Python/Android golden tests | **NOT VERIFIED** | Fixture exists; implement both runners and execute same cases on same final SHA |
| Financial-effect journal | **NOT VERIFIED** | Establish actual owner, unique effect identity and atomic projection boundary |
| SQLite schema support matrix | **PARTIAL** | Enumerate all historical schema versions and migration paths, then test isolated copies |
| Lossless migration | **NOT READY / NOT RUN** | Preserve uncertainty; corroborate or quarantine REAL values; no production migration |
| Recovery Gate on financial completeness | **PARTIAL** | Trace every reachable mutation sink and prove critical unknown states block it |
| Mutation-sink inventory | **PARTIALLY TRACED** | Complete call graph and negative network-sink tests |
| Fault injection | **PARTIAL / NOT EXECUTED HERE** | Enumerate canonical suite and add effect/fee/concurrent recovery failures |
| Campaign CI | **SUCCESS on prior SHA only** | Must check completed run on final report SHA |
| Standalone Python CI | **NOT VERIFIED on final SHA** | Trigger through approved workflow_dispatch or applicable PR and verify checkout SHA |
| Standalone Android APK CI | **NOT VERIFIED on final SHA** | Trigger through approved workflow_dispatch or applicable PR and verify checkout SHA |
| Git / PR integrity | **PARTIAL** | Remote branch/main checked; local worktree is not observable through GitHub API |

## 10. Decision and minimal next step

**TASK 007.8: BLOCKED — NOT ACCEPTED.**

The decisive blockers are not documentation formatting. The implementation still contains authoritative execution/accounting paths that convert financial values through float/Double or map missing/invalid values to zero; the financial-effect journal-to-projection transaction boundary is not proven; cross-platform vectors have not been executed; migration losslessness is not established; and the full mutation-sink/recovery/fault matrix remains open.

Next, finish the source-to-sink register at function level and identify the actual financial-effect applier before changing runtime/schema. Then implement the shared fixture runners and narrowly targeted failing tests. Only after those results establish the minimal correction scope should production code or schema be changed. No P0 is considered closed by this checkpoint.
