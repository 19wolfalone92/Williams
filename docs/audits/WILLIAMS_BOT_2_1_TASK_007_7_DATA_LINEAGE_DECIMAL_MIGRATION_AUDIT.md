# WILLIAMS BOT 2.1 — TASK 007.7
## Complete Financial Data Lineage, Canonical Decimal Contract & Lossless Migration Safety Proof

**Final status: BLOCKED — NOT ACCEPTED**

This is a source-review checkpoint, not a claim that the complete lineage or migration proof is finished. Only an audit document is added by this task checkpoint; no production runtime, schema, or trading behavior was changed. No Binance Mainnet access or Spot Testnet trading mutations were performed. No production database was migrated.

## A. GitHub state and exact-SHA CI

Verified through GitHub API during TASK 007.7:

| Item | Result |
|---|---|
| Repository | `19wolfalone92/Williams` |
| Recovery branch | `audit/williams-2.1-task-007-recovery` |
| Branch HEAD at verification | `98bdfdba3a941320e2bf3f75f1840b1ca82428b6` |
| Main | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Merge base | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Ahead / behind | 127 / 0 |
| Open PR from recovery branch | No matching PR found |
| Local working-tree status | NOT VERIFIED; no local checkout available |
| Main mutation / merge in this task | None performed |

Links:
- [Recovery branch](https://github.com/19wolfalone92/Williams/tree/audit/williams-2.1-task-007-recovery)
- [Main/base commit](https://github.com/19wolfalone92/Williams/commit/04da5f9c09572e99ff310be3a1908179165e528c)
- [Verified HEAD](https://github.com/19wolfalone92/Williams/commit/98bdfdba3a941320e2bf3f75f1840b1ca82428b6)
- [Compare to main](https://github.com/19wolfalone92/Williams/compare/04da5f9c09572e99ff310be3a1908179165e528c...98bdfdba3a941320e2bf3f75f1840b1ca82428b6)

### CI at baseline SHA 98bdfdba3a941320e2bf3f75f1840b1ca82428b6

| Workflow / job | Run ID | Checkout/source SHA | Result | Scope limitation |
|---|---:|---|---|---|
| Williams Campaign CI | [38119766198](https://github.com/19wolfalone92/Williams/actions/runs/38119766198) | `98bdfdba3a941320e2bf3f75f1840b1ca82428b6` | SUCCESS | Existing deterministic safety/campaign Python suite and Android unit tests/lint/debug APK build; not the complete TASK 007.7 suite |
| Python tests job | 114411898196 | Same run/SHA | SUCCESS | Compiled Python sources and ran the workflow's deterministic safety/campaign pytest command |
| Android build and static checks | 114411898285 | Same run/SHA | SUCCESS | `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` |
| Standalone `python-ci.yml` | No exact-SHA run found | — | NOT VERIFIED | Not equivalent to Campaign CI |
| Standalone `android-apk.yml` | No exact-SHA run found | — | NOT VERIFIED | Not equivalent to Campaign CI |
| `testnet-readonly.yml` | Intentionally not run | — | NOT RUN | No Testnet request or trading mutation permitted at this stage |

This CI is confirmed only for the stated SHA and configured test scope. It does not prove lineage completeness, lossless migration, Decimal parity, runtime recovery, or physical-device behavior.

## B. Source review boundary

Files inspected at the recovery branch include:
- `db.py`
- `execution_accumulator.py`
- `execution_barrier.py` (prior TASK 007.6 review)
- `campaign_execution.py`, `campaign_engine.py`, `portfolio_trader.py`, `trader.py` (present in branch diff / prior mutation-path review; complete producer-consumer closure remains outstanding)
- `app/src/main/java/com/williamsbot/ExecutionAccumulator.kt`
- `app/src/main/java/com/williamsbot/StandaloneRuntime.kt`
- `test_atomic_real_db.py`, recovery/campaign tests and `test_android_mutation_ledger_contract.py` are present in repository tree.

The GitHub code search used for journal symbols returned no matches in the searched index. This is not by itself proof of absence. The checked recovery-branch tree did not identify a file named as a standalone financial effect journal; function-level searches and call-graph proof are still required. Do not assume `financial_effect_journal`, `apply_financial_fill_effect()`, or `resolve_financial_effect_fee()` exist in this branch until verified from the actual checked-out source.

## C. Financial lineage register — verified subset

**The complete field-by-field producer/consumer graph is NOT CLOSED.** The following table records verified fields and explicit gaps. It is not permission to migrate the schema.

| Field ID | Semantics / source of truth | Producers observed | Consumers / transformation | Python / SQLite / Android | Serialization / nullability / rounding | Persistence / recovery | Evidence status |
|---|---|---|---|---|---|---|---|
| ORDERS.PRICE | Requested/recorded order price; exchange order is authority for accepted order state | `Database.save_order()`; upstream order builders | Order recovery, campaign/execution paths; complete reader graph pending | Python `float` conversion via DB numeric helper; SQLite `REAL`; Android runtime includes `Double)-based price state | Raw exchange JSON retained; canonical exact format not defined end-to-end; tick rounding must use instrument rules | `orders.price`; restore/reconcile paths need complete proof | CODE-CONFIRMED partial; lineage NOT VERIFIED |
| ORDERS.STOP_PRICE | Stop/trigger price | Order persistence path | Protection, recovery and order decisions; full consumer inventory pending | SQLite `REAL`; Android protection prices include `Double` | Exchange filter precision and stop semantics not fully documented here | `orders.stop_price` | CODE-CONFIRMED partial |
| ORDERS.QUANTITY | Requested order quantity | Order builders / `save_order()` | Order state, position and exit sizing | SQLite `REAL`; Android runtime `Double` quantities/rules | Must use exact decimal strings and symbol lot-step; current full path not proven | `orders.quantity` and raw JSON | CODE-CONFIRMED partial |
| TRADES.ENTRY_PRICE / EXIT_PRICE | Recorded entry/exit prices; exchange fills are authoritative execution evidence | Trade writers | PnL, reports, learning/analytics; all readers not enumerated | SQLite `REAL`; float-bearing path exists | Rounding contract not complete | `trades` | CODE-CONFIRMED storage; readers NOT VERIFIED |
| TRADES.QUANTITY | Historical trade quantity | Trade writers | Inventory/PnL/reporting | SQLite `REAL` | Must distinguish requested, gross filled and net after base-asset fee | `trades.quantity` | CODE-CONFIRMED storage; semantics need closure |
| TRADES.PNL / FEES | Realized money and fees; fee asset matters | Trade close/accounting writers | Risk/reporting/campaigns; full graph pending | SQLite `REAL`, `fees` has default 0 in schema | Schema default 0 cannot establish that unknown fees are known zero; canonical currency and fee-known state required | `trades.pnl`, `trades.fees` | P0 semantic risk; CODE-CONFIRMED |
| EXECUTION_INTENTS.INVALIDATION_LEVEL | Price threshold in durable intent | `save_execution_intent()` | Barrier/risk/validation | `OrderIntent` uses float; SQLite `REAL`; writer calls `float(intent.invalidation_level or 0.0)` | `None` is converted to 0.0 in this writer; this must be reviewed against intent contract | `execution_intents.invalidation_level` | CODE-CONFIRMED |
| CAMPAIGNS.POSITION_QTY / AVERAGE_ENTRY_PRICE | Campaign inventory and weighted entry price | Campaign persistence paths | Campaign engine, SELL sizing, stops and recovery | SQLite `REAL`; Android `PositionState.qty/entry` are `Double` | Canonical decimal contract absent | `campaigns` | CODE-CONFIRMED storage/type; all writers/readers NOT VERIFIED |
| CAMPAIGNS.STOPS | Initial/current structural stop prices | Campaign updates | Protection and risk calculations | SQLite `REAL`; Android `Double` | Tick-size boundary not established in this audit | `campaigns` | CODE-CONFIRMED partial |
| CAMPAIGNS.PNL / RISK / CAPITAL RESERVATION | Money/risk/capital reservation | Campaign/risk writers | Admission gates, exposure calculations, reports | SQLite `REAL`; intent models include float risk/reservation fields | Quote asset, unknown-state behavior, and rounding boundaries require explicit contract | `campaigns`, intents/order state | CODE-CONFIRMED partial; critical lineage NOT VERIFIED |
| CAMPAIGN_FILLS.QUANTITY / PRICE / QUOTE_QUANTITY | Exchange fill quantity, price and quote amount | Fill persistence paths | Accumulator, campaign, inventory, PnL and recovery | SQLite `REAL` fields exist; Python accumulator uses `Decimal` locally | Fill identity and raw payload round-trip contract not proven | `campaign_fills` | CODE-CONFIRMED schema; atomic projection linkage NOT VERIFIED |
| CAMPAIGN_FILLS.COMMISSION / ASSET | Fee amount and fee asset | Exchange fill payload | Net inventory, fees/PnL, risk and reconciliation | Numeric commission storage uses `REAL` in existing schema; Android accumulator uses `BigDecimal` in memory | Missing commission and unknown asset must remain UNKNOWN/CONFLICTED, not zero; cross-asset conversion policy not complete | `campaign_fills` and accumulator state | CODE-CONFIRMED partial; recovery NOT VERIFIED |
| PYTHON ExecutionSummary fields | Executed qty, quote qty, average price, fee, base commission, unvalued assets | `accumulate_fills()`, `accumulate_order()` | Callers that use summary for accounting/execution | `Decimal`; `as_dict()` serializes values with `str()` | Parser uses `Decimal(str(value or "0"))`; absent/empty commission and absent qty/price can become zero; malformed non-empty Decimal raises; complete caller handling not verified | In-memory result / downstream writers | CODE-CONFIRMED; end-to-end safety NOT VERIFIED |
| ANDROID ExecutionAccumulator | Per-order qty/quote/VWAP/fee-known snapshot | `accept()`, `acceptFill()`, `restore()` | Runtime consumers; durable downstream path pending | `BigDecimal` in memory; seen-fill set and orders map are in-memory | `decimal(value)` uses `toBigDecimalOrNull() ?: BigDecimal.ZERO`; commission defaults to zero; key is `orderId:tradeId:eventTime`; VWAP rounds scale 18 HALF_UP | In-memory map; no durable ledger proven by this class | CODE-CONFIRMED; persistence/recovery NOT VERIFIED |
| ANDROID Position/PendingEntry/SymbolRules | Quantity, price, risk, notional, tick/step/min/max rules | `StandaloneRuntime.kt` | Sizing, entry/exit, OCO/protection and filters | Relevant fields are `Double` | String-to-Double paths and exact exchange filter serialization need complete mapping | Runtime state and its existing persistence paths require audit | CODE-CONFIRMED type; complete use graph NOT VERIFIED |
| Market OHLCV / analytics | Market observations and derived indicators, not automatically ledger values | Market-data pipeline | Strategy/context; downstream use must be checked before allowing float | SQLite `REAL`, Python/Android floating types | May be binary float only if proven not to feed authoritative order sizing/accounting without controlled conversion | Market tables / cache | Classification pending |
| IDs / timestamps / counters | Identity, ordering, counts, time | Exchange/runtime | Deduplication, lifecycle, chronology | TEXT/INTEGER/Long | Not Decimal values | Database/runtime | Must remain non-Decimal |

### Confirmed conversion and knownness hazards

1. Python `accumulate_fills()` reads quantity/price/commission with `Decimal(str(fill.get(..., "0") or "0"))`. Missing or empty values can therefore be interpreted as zero. This is not a safe general contract for critical financial fields unless presence/knownness is validated before this function.
2. `ExecutionSummary` declares fee fields with `Decimal("0")` defaults. It also tracks unvalued third-asset commissions only when a positive commission amount is present. Absence/invalidity is not independently represented as UNKNOWN in the summary input contract.
3. Android `ExecutionAccumulator.accept()` parses exchange event fields through `decimal()`, which falls back to `BigDecimal.ZERO`. `acceptFill()` also defaults commission to `BigDecimal.ZERO` and commission asset to empty text.
4. Android fill dedupe state is held in an in-memory map/set. The composite key is `orderId:tradeId:eventTime`, not a demonstrated exchange + market type + symbol + order + trade identity persisted across restart.
5. Android VWAP uses `divide(qty, 18, RoundingMode.HALF_UP)`. Whether scale 18 is contractually valid for every supported instrument and downstream use has not been established. It must not be treated as an authoritative rounding rule without that proof.
6. `StandaloneRuntime.kt` defines financial execution state and symbol filters with `Double`, including position quantity/entry/stop, pending notional/stop/risk/capital reservation, and step/tick/min/max quantities/prices/notional.
7. `db.py` uses schema version 10 and declares multiple order/trade/campaign/fill financial columns as `REAL`. `save_execution_intent()` converts `invalidation_level` to `float(... or 0.0)`.
8. Previously saved SQLite REAL values cannot, in general, be proven to recover their original decimal input strings. Migration must preserve the exact stored numeric value as observed or quarantine/reconcile it; it must not claim restoration of unknowable original precision.
9. This audit has not yet established every writer/reader in Python, API/DTO serialization, Android persistence, tests and fixtures. Therefore no claim of complete lineage is made.

## D. Canonical Decimal contract — proposed contract, not yet implemented/proven

The following is the minimum contract to approve before migration. It is not a claim that current runtime complies.

### Parsing and states
- A critical numeric value is represented as a tagged state: `KNOWN(value)`, `UNKNOWN`, `INVALID(raw, reason)`, or `CONFLICTED(evidence)`. Known zero is `KNOWN(0)`, never a missing/default state.
- Parse exchange numeric strings directly to Python `Decimal` and Kotlin `BigDecimal`; reject non-finite values and malformed strings. No parse failure defaults to zero.
- If a field is required for a financial effect and is absent, the effect remains pending/reconciliation-required until authoritative evidence resolves it.
- Keep original raw payload and provenance where safe; never log API secrets.
- Never create `Decimal` from a binary float in the canonical path. Existing REAL values are legacy evidence with explicitly limited recoverability.

### Canonical wire/storage form
- Use decimal strings for canonical cross-platform wire representation, with invariant locale and no exponent notation unless explicitly allowed by the schema contract.
- Preserve value exactly; define whether trailing zeros are preserved as source scale metadata or normalized away. Do not conflate value equality with scale/precision provenance.
- Store canonical ledger decimals as TEXT only after all readers/writers and migration compatibility are mapped. Existing tables must not be mass-converted during TASK 007.7.
- Use Python Decimal and Kotlin BigDecimal for calculations. Any rounding mode and scale must be named at a documented boundary. Instrument step/tick and exchange filters govern order quantization; no arbitrary global scale is accepted.
- Malformed, out-of-range, or contradictory values fail closed and retain diagnostic context.

### Still unresolved
- supported instruments and actual precision/range envelope;
- quote/base asset model and valuation policy for third-asset fees;
- which historical fields are authoritative versus analytics snapshots;
- compatible schema versions and app rollback behavior;
- exact producer/consumer graph needed to decide each rounding boundary;
- Python/Android shared fixture location and runner integration.

## E. Migration safety

**No migration was run. No production database was accessed or changed.**

Observed:
- `db.py` declares `SCHEMA_VERSION = 10`.
- Existing schema uses SQLite `REAL` for several financial fields.
- Initialization performs a SQLite `quick_check` and configures WAL / FULL synchronous mode.
- A transaction context exists, but it does not itself prove all schema initialization/migration operations are atomic; the schema initialization uses `executescript`, whose transaction semantics need to be audited for the actual sequence.
- Existing database upgrade compatibility, all migration versions, fixture provenance, and crash interruption behavior have not been fully mapped in this checkpoint.

Required migration protocol before implementation:
1. Complete table/index/constraint/trigger/foreign-key and reader/writer inventory.
2. Identify every supported historical schema and source fixture provenance.
3. Copy the DB to an isolated test location and retain the source copy immutable.
4. Snapshot row counts, keys, relationships, raw payloads, financial values, fees/knownness, positions, campaigns, pending intents and protection state.
5. For REAL fields, classify the stored binary numeric value and its representability; do not claim the original input decimal can be recovered when it cannot.
6. Convert only values whose interpretation is unambiguous. Mark ambiguous/corrupt/conflicting records as requiring reconciliation; do not silently round or default.
7. Run migration inside a proven atomic or resumable protocol, with schema-version update only after verification.
8. Inject interruption before copy, during copy, before switch, after switch/before final marker, and after success; reopen and verify.
9. Compare record identity/counts, foreign relationships, fill totals, commission knownness, inventory, campaign/risk/protection and pending operation states.
10. Test rerun/idempotence and concurrent process behavior against independent connections/processes.
11. Keep legacy compatibility or reject unsupported schema versions explicitly; do not partially open a newer/unknown schema.

**Acceptance status: NOT RUN / NOT PROVEN.** No representative historical DB fixture has yet been certified as a real database from a known release/schema. Synthetic fixtures must be labelled synthetic, not historical.

## F. Existing financial journal integration contract

The desired invariant is:

`Exchange fill → stable fill identity → durable effect state → inventory/position → campaign → fees/PnL → risk → protection`

The identity must be scoped by the actual exchange contract, at minimum including exchange, market type, symbol, order ID and fill/trade ID where available. If a source lacks required identity, do not invent a globally unique numeric ID.

Effect states must distinguish unprocessed/pending, applied, reconciliation-required and conflict. Fill persistence alone is not proof that projections have been applied.

For local SQLite projections, fill identity, effect state, and all projections that must agree should share a proven transaction boundary. No exchange network call should occur inside a long local transaction. If any projection is outside SQLite, use durable idempotency and recovery rather than claiming global atomicity.

The current source review has **not** established a canonical effect journal or confirmed the presence of `financial_effect_journal`, `apply_financial_fill_effect()`, or `resolve_financial_effect_fee()` in this recovery branch. No second ledger was created. Existing writers still require full inventory before choosing an authoritative ledger.

## G. Recovery Gate and execution integration

The required gate contract is:
- block risk-increasing operations while recovery is incomplete;
- block when required financial values are UNKNOWN, INVALID or CONFLICTED;
- do not treat unknown commission as zero when it changes net inventory, fee/PnL or required risk calculations;
- block after an ambiguous network mutation until exchange reconciliation resolves it;
- durable intent must precede external mutation;
- emergency risk reduction must still use durable intent, idempotency and reconciliation, with its own safe admission rules;
- no direct mutation sink may bypass enforcement.

Complete production sink coverage and negative network-spy proof remain open. The TASK 007.6 mutation inventory is partial, not an acceptance proof.

## H. Fault injection / tests

Repository tree includes existing test files such as `test_atomic_real_db.py`, recovery/campaign tests, and `test_android_mutation_ledger_contract.py`. Their existence and the Campaign CI success do not prove that the complete TASK 007.7 migration/persistence matrix ran.

Not executed in this checkpoint:
- fixture migration across each supported schema;
- source-to-sink test coverage for every critical field;
- Python/Android shared Decimal golden vectors;
- round-trip tests over persisted canonical strings;
- malformed/unknown/conflicted value tests across both platforms;
- migration interruption at each stage;
- SQLITE BUSY/LOCKED, commit/rollback failure and reopen scenarios;
- independent-process recovery and duplicate fill replay;
- late commission enrichment and conflicting exchange payload tests;
- Android process-kill / durable reload / actual mutation-sink spy;
- physical-device tests.

Physical device: **PHYSICAL-DEVICE-NOT-VERIFIED**.  
Testnet: **NOT RUN**. Mainnet: **NOT USED**.

## I. Remaining blockers

### P0
1. Full critical financial-field lineage is incomplete: every producer/consumer, API/DTO, persistence and recovery edge must be listed and reviewed.
2. Canonical Decimal contract is not implemented end-to-end; Python defaults and Android parse paths can conflate absent/invalid values with zero.
3. SQLite REAL legacy precision limitations require an explicit reconciliation/quarantine policy and tested migration protocol.
4. No proven lossless migration across representative supported old DBs; no migration tests were executed.
5. Cross-platform golden Decimal vectors and persistence round trips are not implemented/proven.
6. Canonical financial-effect journal existence/ownership and atomic projection integration are not proven.
7. Recovery Gate and all mutation sinks are not completely mapped or negatively tested.
8. Independent-process fault injection and recovery are not proven.
9. Standalone Python CI and Android APK workflow have no confirmed exact-SHA run.
10. Complete source lineage and migration safety must be closed before any broad schema/runtime migration.

### P1
- Decide canonical asset/quote semantics for fees and valuation of non-quote commissions.
- Document decimal scale provenance versus normalized representation.
- Certify real historical DB fixture provenance and supported app/schema compatibility.
- Separate analytics-only floating-point fields from any values feeding order/risk decisions.

### P2
- Add metrics for unknown/invalid/conflicted values, quarantined legacy records, pending effects and reconciliation duration.

## J. Final decision

**TASK 007.7: BLOCKED — NOT ACCEPTED.**

The audit found concrete precision/knownness risks and recorded a migration protocol, but it did not complete the full lineage graph, implement the canonical Decimal model, run migration tests, or prove cross-platform/runtime recovery. No production runtime or schema changes were made. Do not resume trading or run exchange mutations until P0 blockers are closed with tests and exact-SHA CI evidence.
