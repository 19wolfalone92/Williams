# WILLIAMS BOT 2.1 — TASK 007.6
## Canonical Decimal Ledger, Atomic Financial Effects & Recovery-Safe Execution Enforcement

**Status: IN PROGRESS — NOT APPROVED**  
**Evidence boundary:** GitHub source/config review and exact-SHA CI metadata only where stated. No local working tree was available. No Binance Mainnet/Testnet requests or trading mutations were made. No schema migration or runtime source modification is claimed.

## 1. Verified repository baseline

Verification performed against GitHub at the start of TASK 007.6.

| Item | Observed value | Evidence |
|---|---|---|
| Repository | `19wolfalone92/Williams` | CODE/REPOSITORY METADATA |
| Recovery branch | `audit/williams-2.1-task-007-recovery` | BRANCH API |
| Branch HEAD at baseline | `0c3ecfed02236c292d35eb0f82f6777534ae7b7c` | BRANCH API |
| Main/base | `04da5f9c09572e99ff310be3a1908179165e528c` | BRANCH API |
| Ahead / behind | 126 / 0 | GitHub compare endpoint |
| Merge base | `04da5f9c09572e99ff310be3a1908179165e528c` | GitHub compare endpoint |
| PR from recovery branch | None found in all-PR listing filtered by exact head branch | PR API; no PR created |
| Local working-tree status | NOT VERIFIED | No local checkout; GitHub cannot report local dirty/untracked state |
| Main modified / merge performed in this task | No | No such write operation was performed |

Reference links:
- [Recovery branch](https://github.com/19wolfalone92/Williams/tree/audit/williams-2.1-task-007-recovery)
- [Main/base commit](https://github.com/19wolfalone92/Williams/commit/04da5f9c09572e99ff310be3a1908179165e528c)
- [Baseline HEAD](https://github.com/19wolfalone92/Williams/commit/0c3ecfed02236c292d35eb0f82f6777534ae7b7c)
- [Compare baseline to main](https://github.com/19wolfalone92/Williams/compare/04da5f9c09572e99ff310be3a1908179165e528c...0c3ecfed02236c292d35eb0f82f6777534ae7b7c)

## 2. Previous TASK 007.5 CI — exact SHA

Campaign CI run [38117879452](https://github.com/19wolfalone92/Williams/actions/runs/38117879452) completed successfully on exact SHA `0c3ecfed02236c292d35eb0f82f6777534ae7b7c`.

| Workflow / job | Job ID | Result | Steps observed |
|---|---:|---|---|
| Williams Campaign CI | 38117879452 | SUCCESS | Run metadata reports completed/success |
| Python tests | 114406354961 | SUCCESS | Checkout; Python setup; dependencies; compileall; deterministic safety/campaign pytest command |
| Android build and static checks | 114406354791 | SUCCESS | Checkout; JDK/Gradle setup; `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`; artifact uploads |

The Python workflow command in `.github/workflows/campaign-ci.yml` invokes the listed deterministic safety/campaign test files, including DB guards/isolation, atomic DB tests, recovery tests, execution architecture/integration, campaign execution, and TASK 007 recovery contract tests. This CI does **not** include the full TASK 007.6 Decimal-migration, end-to-end financial-effect, complete mutation-sink, independent-OS-process crash matrix, or physical-device acceptance suite. Successful CI is not proof of those properties.

### Other workflows on baseline SHA

| Workflow | Exact-SHA run | Result |
|---|---|---|
| `python-ci.yml` — Python backend tests | No run found in exact-SHA Actions listing | NOT VERIFIED |
| `android-apk.yml` — Build Android APKs | No run found in exact-SHA Actions listing | NOT VERIFIED |
| `testnet-readonly.yml` | Intentionally not run | NOT RUN — prohibited until local P0 gates close |

The Python and standalone Android APK workflows have branch/path filters for pushes, plus manual dispatch. Their configuration does not make the successful Campaign CI job equivalent to a run of those separate workflows.

## 3. Initial financial-field source-to-sink inventory

**This is a confirmed initial inventory, not the complete register required for acceptance.** A full function-by-function writer/reader mapping across every Python module, Android DTO/database, API serialization, fixture, and migration remains open. Do not begin a mass schema migration until that mapping and compatibility contract are complete.

| Entity | Field(s) observed | Source / writer | Readers / sinks observed | Current storage / representation | Semantic classification | Canonical target | Migration / test status |
|---|---|---|---|---|---|---|---|
| `orders` | `price`, `stop_price`, `quantity` | `Database.save_order` | order/recovery consumers; complete reader map pending | SQLite REAL; `_num()` converts through Python float | price / asset quantity | Decimal + canonical decimal TEXT | NOT MIGRATED; complete reader inventory required |
| `trades` | `entry_price`, `exit_price`, `quantity`, `pnl`, `fees` | trade insert/update/close methods | open-trade, learning summaries, reporting and risk consumers; complete map pending | SQLite REAL; writers include float conversion or numeric parameters | price / quantity / money | Decimal + canonical decimal TEXT | NOT MIGRATED; historical NULL/invalid rows need reconciliation rules |
| `trades` | `pnl_pct`, `risk_pct` | trade writers | analytics/risk/reporting | SQLite REAL | percentage/rate, not currency | Decimal for decision use; float may be acceptable only for non-authoritative display/statistics after dependency proof | NOT VERIFIED |
| `campaigns` | `position_qty`, `average_entry_price`, `initial_stop_price`, `current_stop_price` | campaign persistence paths | campaign engine, recovery, exit sizing/protection | SQLite REAL | quantity / price | Decimal + canonical decimal TEXT | NOT MIGRATED |
| `campaigns` | `realized_pnl_quote`, `unrealized_pnl_quote`, `open_risk_quote`, `pending_risk_quote`, `capital_reserved_quote` | campaign state writers | risk gate, campaign lifecycle, reports | SQLite REAL | money / risk / reservation | Decimal + canonical decimal TEXT | NOT MIGRATED |
| `campaign_orders` | `price`, `stop_price`, `quantity`, `risk_quote`, `capital_reserved_quote` | campaign order writers | order recovery, protection, risk/capital checks | SQLite REAL | price / quantity / money | Decimal + canonical decimal TEXT | NOT MIGRATED |
| `campaign_fills` | `quantity`, `price`, `quote_quantity`, `fee_quote`, `commission_base`, `commission_asset` | fill persistence path | campaign accounting/recovery; complete projection ownership pending | SQLite REAL for numeric fields, TEXT for asset/raw JSON | quantity / price / commission | Decimal + canonical decimal TEXT; commission knownness must be explicit | NOT MIGRATED; uniqueness and effect-application linkage need proof |
| `execution_intents` / `OrderIntent` | `invalidation_level`, `risk_quote`, `capital_reserved_quote` | `ExecutionBarrier` / DB intent persistence | admission, risk, recovery | dataclass float for at least these fields; SQLite REAL for invalidation level; `save_execution_intent` calls `float(...)` | price / money / risk | Decimal/string representation; no float bridge | NOT MIGRATED |
| Python `ExecutionSummary` | executed quantity, quote quantity, average price, fees, base commission | `execution_accumulator.py` | order/campaign accounting callers | Decimal arithmetic; serialized with `str` | quantity / money / price / fee | Decimal retained end-to-end | Partial improvement only; callers/persistence and unknown-field semantics still require audit |
| Android `ExecutionAccumulator` | qty, quote, VWAP, fee, fee-known | Android fill events | runtime order/campaign handling; persistence/recovery linkage pending | BigDecimal in accumulator | quantity / money / price / fee | BigDecimal with Python-compatible canonical string semantics | UNIT SOURCE REVIEW; durable end-to-end ledger not proven |
| Android `PositionState`, `PendingEntry`, `SymbolRules` | qty, entry, stops, notional, risk, capital, step/tick/minimums | `StandaloneRuntime.kt` | order sizing, SELL/protection, campaign state | Double | mixture of price/quantity/money/risk/instrument rules | BigDecimal for authoritative financial and execution-sizing paths; instrument step/tick exact decimal | NOT MIGRATED; usage mapping and parity tests required |
| `candles` / `market_context` / `wave_state` | OHLCV, scores, timestamps, context JSON | market-data pipeline | indicators and signal logic | REAL for market numbers; INTEGER/TEXT for times/JSON | market observations / analytical values, not ledger money | classify by actual downstream use; do not blindly migrate all numeric columns | Full dependency classification pending |
| IDs / timestamps / counts | order/trade IDs, signal IDs, timestamps, counts, additions, tranche index | exchange/runtime/database | identity, ordering, state machine | TEXT/INTEGER | identity / time / count | stable identifiers and integer time/count types | No Decimal conversion appropriate |

### Source-confirmed risk points

1. `db.py` declares `SCHEMA_VERSION = 10`; several authoritative order, trade, campaign, intent and fill numeric columns use SQLite `REAL`.
2. `Database._num()` converts input with `float(v)` and returns `None` for parsing errors. That makes the current persistence path unsuitable as a canonical exact financial representation; malformed financial input can be converted to NULL rather than a typed blocking error.
3. `Database.save_execution_intent()` explicitly converts `invalidation_level` to float. `OrderIntent` declares `invalidation_level`, `risk_quote`, and `capital_reserved_quote` as floats.
4. `execution_accumulator.py` uses Decimal for its local arithmetic, but that alone does not make the end-to-end database/projection path exact or atomic.
5. Android `ExecutionAccumulator.kt` uses BigDecimal in memory, but `decimal(value)` defaults unparseable strings to `BigDecimal.ZERO`; the API also defaults commission to zero. This does not preserve the distinction between absent/invalid data and a known zero unless the caller independently proves those fields are present.
6. Android accumulator identity currently uses `orderId:tradeId:eventTime` in an in-memory set. It is not durable across process restart and is not a proven globally scoped exchange-fill key across market type/symbol.
7. The schema contains `campaign_fills`, but source review has not established a single durable effect journal and one atomic transaction spanning fill identity, effect application, inventory/position, campaign, fee/PnL, risk and protection projections.
8. Existing schema upgrades include `ALTER TABLE` additions and a recovery-schema migration. The complete supported-version matrix, interruption behavior, and data-preservation proof are not established. No migration was run during this audit.

## 4. Effect journal and atomicity

**Status: BLOCKED — design and implementation not yet proven.**

The required logical flow is:

`Exchange event → canonical fill identity → durable fill record → durable effect state → position/inventory projection → campaign projection → fee/PnL → risk → protection`

The source reviewed so far proves only partial mechanisms:
- execution intent durability and same-symbol unresolved-intent gating exist in `db.py` / `execution_barrier.py`;
- Python fill aggregation uses Decimal locally;
- Android fill aggregation uses BigDecimal locally;
- existing fill/order deduplication paths exist in some modules.

It does **not** prove:
- a stable composite fill identity scoped by exchange + market type + symbol + order ID + trade ID;
- an effect state machine distinguishing pending/applied/reconcile-required/conflict;
- atomic application of all local projections with the effect journal;
- exactly-once behavior across independent OS processes;
- late fee enrichment without loss or double counting;
- recovery after every commit/crash boundary.

No second ledger was added, no journal schema was changed, and no exactly-once claim is made at this checkpoint.

## 5. Execution mutation sinks and recovery

The prior report `docs/TASK_007_2_RECOVERY_CONCURRENCY_AUDIT.md` provides a partial call graph for BUY, campaign entry/exit/stop, cancel, cancel-replace, OCO, and multi-position execution. It explicitly does not establish complete repository-wide reachable-caller coverage.

| Area | Current evidence | Status |
|---|---|---|
| Python ExecutionBarrier | durable intent, validation, persistence error handling, ambiguous-state poisoning present in source | CODE-CONFIRMED partial controls; complete sink enforcement NOT VERIFIED |
| Python direct adapter callers / alternate entry points | prior report lists several paths; full reachable caller proof incomplete | BLOCKED |
| Unknown POST result | no blind retry claim at barrier layer; full caller/restart reconciliation not proven | BLOCKED |
| Spot SELL / oversell | no complete authoritative free/locked balance + commission + open-order + concurrent SELL proof | BLOCKED |
| OCO/cancel-replace | no complete ambiguity, sibling-fill, residual exposure and restart matrix on current SHA | BLOCKED |
| Futures | no proven global technical disable across every mutation sink in this review | BLOCKED until negative sink tests prove no network call |
| Android signed mutation sinks | prior static contract test checks durableMutation routing and pending persistence; `StandaloneRuntime.kt` still contains Double-based execution state | static source/test only; runtime/lifecycle NOT VERIFIED |

Do not treat the presence of an ExecutionBarrier, a static test, or an Android build as proof that all production mutation sinks are enforced.

## 6. TASK 007.6 fault-injection and migration tests

No new TASK 007.6 fault-injection, migration, or independent-process tests were executed in this checkpoint. The required acceptance suite remains to be added or extended against the repository's existing tests and fixtures, including:
- real SQLite files and independent connections/processes;
- duplicate/conflicting fills and stable composite identity;
- interruption before/after each durable commit;
- rollback/commit failures and database reopen;
- late commission enrichment and unknown commission;
- projection replay exactly once;
- concurrent recovery workers;
- unknown network mutation result and proof of no blind retry;
- OCO sibling cancellation/fill races and residual exposure;
- Spot SELL oversell and actual available inventory;
- Android process/runtime restart and mutation-spy at the real network sink;
- Futures disabled-path negative tests.

Physical-device status: **PHYSICAL-DEVICE-NOT-VERIFIED**.  
Testnet observation: **NOT RUN**. Mainnet: **NOT USED**.

## 7. CI baseline and next exact-SHA rule

At the baseline SHA:
- Campaign CI run `38117879452`: SUCCESS on `0c3ecfed02236c292d35eb0f82f6777534ae7b7c`.
- Python job `114406354961`: SUCCESS.
- Android job `114406354791`: SUCCESS.
- Standalone `python-ci.yml` and `android-apk.yml`: no run confirmed on that exact SHA.

Any new report commit changes HEAD and must have its own Campaign CI run inspected. The report-only commit's successful CI, if any, does not establish financial correctness or close the P0s. Required separate workflows and new TASK 007.6 tests must be run on the eventual final SHA; pending/skipped/missing runs are not passes.

## 8. Acceptance matrix at this checkpoint

| Requirement | Status | Evidence / reason |
|---|---|---|
| Current GitHub refs and baseline CI checked | PASS for baseline metadata | Exact branch/base/compare and Campaign CI queried |
| No duplicate recovery-branch PR | PASS at check time | No PR returned for exact head branch |
| Local working tree status | NOT VERIFIED | No local checkout |
| Complete financial source-to-sink register | BLOCKED | Initial table above is partial; all writers/readers and DTO/API consumers not mapped |
| Canonical Decimal model implemented end-to-end | BLOCKED | REAL/float and invalid-to-zero/NULL paths remain |
| Safe historical DB migration | NOT RUN / BLOCKED | No migration; version compatibility and interruption proof missing |
| Durable canonical effect journal | BLOCKED | No end-to-end journal/projection atomicity proof |
| Exactly-once financial effects | BLOCKED | In-memory/partial dedupe is insufficient for process-safe projections |
| Full mutation sink enforcement | BLOCKED | Complete call graph and negative sink tests absent |
| Crash injection / independent-process recovery | NOT RUN / BLOCKED | Required matrix not executed |
| Spot SELL / OCO / residual exposure | BLOCKED | Full reconciliation and race matrix absent |
| Android lifecycle recovery | BLOCKED | No durable effect proof or process-kill tests in this task |
| Futures fail-closed | NOT VERIFIED / BLOCKED | Global no-network-mutation proof absent |
| Python CI and standalone Android APK CI on baseline SHA | NOT VERIFIED | No exact-SHA runs found |
| Physical device | NOT VERIFIED | Not tested |
| Main unchanged / no merge / no exchange mutations | PASS | No such operation performed |

## 9. Remaining blockers and next actions

### P0
1. Finish the complete writer/reader/source-to-sink register before schema design or migration.
2. Remove binary-float conversion from authoritative financial persistence and execution-sizing paths, with explicit unknown/invalid states and cross-platform golden fixtures.
3. Design and prove a lossless, versioned, transactional/recoverable migration; quarantine ambiguous legacy rows for reconciliation rather than rounding/defaulting them.
4. Implement or safely extend one durable effect journal and atomically apply/replay all local projections.
5. Add independent-process crash/fault-injection tests and prove exactly-once projection application.
6. Prove every production mutation sink is barrier-enforced, including emergency risk reduction, and test actual absence of network calls on denied gates.
7. Prove Spot SELL/OCO/cancel-replace residual exposure and UNKNOWN outcome recovery.
8. Prove Futures mutation is technically blocked until a separately approved/tested Futures contract exists.
9. Add Android durable ledger/lifecycle recovery tests and mutation-spy at the actual sink.
10. Run all required workflows/tests on one final SHA.

### P1
- Document Python/Android decimal parsing, scale, normalization, and rounding boundaries consistently.
- Inventory non-ledger analytics that still use float and establish that none feed authoritative execution/risk decisions.
- Define diagnostics and reconciliation handling for malformed legacy values and conflicting exchange payloads.

### P2
- Add operational metrics for pending effects, unvalued commission assets, quarantined legacy rows, recovery retries, and projection divergence.

**Final TASK 007.6 status at this checkpoint: IN PROGRESS — NOT APPROVED.** This commit is an audit checkpoint only. No runtime fix, schema migration, complete acceptance test suite, or P0 closure is claimed.