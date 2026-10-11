# WILLIAMS BOT 2.1 — TASK 007.9
## Canonical Financial Data Contract, Executable Golden Tests & Financial Effect Ownership Proof

**Decision: BLOCKED — NOT ACCEPTED**

**Audit mode:** Remote GitHub source/config inspection and completed Campaign CI evidence. No local checkout was available. No Binance Spot Testnet/Mainnet trading mutation was performed. No production database was opened or migrated. No merge or PR creation was performed.

## A. GitHub baseline

| Item | Verified result |
|---|---|
| Repository | `19wolfalone92/Williams` |
| Working branch | `audit/williams-2.1-task-007-recovery` |
| HEAD before this report | `e67697ed9a0a89c8143c572c92bea383eb531abb` |
| Main | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Ahead/behind | Branch is 130 commits ahead and 0 behind main at baseline |
| Open PR for this recovery branch | None found; existing open PRs belong to other branches |
| Local working tree | **NOT VERIFIED** — remote GitHub inspection is not a local checkout |
| Main changed / merge performed | No |
| Source/runtime or schema changed in this task checkpoint | No |
| Final SHA | Must be read from branch after this report commit |

Remote branch and main refs were re-read before inspection. The fixture file and TASK 007.8 report were fetched from the branch itself, not reconstructed from conversation text.

## B. CI on the input SHA

### Campaign CI

- Workflow: **Williams Campaign CI**
- Run ID: `38121397154`
- Run URL: https://github.com/19wolfalone92/Williams/actions/runs/38121397154
- Exact checkout/head SHA: `e67697ed9a0a89c8143c572c92bea383eb531abb`
- Event: `push`
- Final run status: **completed / success**
- Python job ID: `114416735991` — completed / success
- Android job ID: `114416736254` — completed / success

Python job log confirms **201 passed, 4 warnings in 9.17s**. Its selected pytest set includes the existing TASK 007 recovery contract tests, but does not include a Python golden-fixture runner for `financial_decimal_golden_vectors.json`.

Android job completed the configured command:
`gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace`
and uploaded the debug APK and lint report. This proves the configured Android unit-test/build/lint job succeeded on the input SHA; it does **not** prove that the 20 cross-platform financial vectors ran.

### Dedicated workflows

- `.github/workflows/python-ci.yml` exists. It has `push` restricted to `main/master` with path filters, a `pull_request` trigger, and `workflow_dispatch`.
- `.github/workflows/android-apk.yml` exists. It has `push` restricted to `main/master` with path filters, a `pull_request` trigger, and `workflow_dispatch`.
- The run list queried for input SHA `e67697ed9a0a89c8143c572c92bea383eb531abb` returned only Campaign CI run `38121397154`.
- The available GitHub connector supports read-only workflow inspection and rerunning failed jobs, but exposes no workflow-dispatch action. No dispatch was attempted or claimed.
- Therefore, separate Python CI and Android APK workflow results for this SHA are **NOT VERIFIED**.

## C. Existing 20-vector fixture set

Fetched exact path: `tests/fixtures/financial_decimal_golden_vectors.json`  
Blob SHA: `cfdf51ca9f201c5435842346501f44f752c20944`  
Fixture set: `williams-financial-decimal-golden-vectors`  
Contract version: `draft-0.1`  
Declared status: `PROPOSED_NOT_EXECUTED`  
Count: **20 vectors, D001–D020**

The vectors cover scale preservation, small quantities, binary-float risk, fees with assets, known-zero fee, missing fee, empty price, invalid numeric input, conflicting authorities, round trip, fill aggregation, partial fill, late fee enrichment, filter step mismatch, scale boundary, negative quantity, negative PnL, negative commission, and zero-quantity VWAP.

Important contract gaps in the current fixture schema:
- It does not consistently specify `effect_allowed`, `recovery_blocked`, a canonical parse error, and a complete result schema for every vector.
- D012 specifies the exact VWAP expression `3.085 / 0.3`, but not an approved canonical decimal rounding boundary/result.
- D015 explicitly permits two different actions (“reject” or approved quantization policy) until policy is selected.
- D016 depends on an unapproved field/symbol scale policy.
- Several cases define desired operations but do not encode a uniform, machine-comparable output object.
- The fixture file still declares `PROPOSED_NOT_EXECUTED`.

The fixture was inspected in place and not recreated. It has **not** been executed through either platform's production-path runner.

## D. Source findings — decimal and data-state contract

### Python: CODE-CONFIRMED

`execution_accumulator.py`:
- `accumulate_fills()` parses qty, price and commission using `Decimal(str(value or "0"))`. Missing/empty values can therefore become numeric zero before validation.
- Invalid decimal strings can raise during Decimal construction rather than becoming an explicit `INVALID` state.
- A missing commission field defaults to zero. A third-asset commission is recorded as unvalued only when commission is positive.
- `ExecutionSummary` defaults fee fields to `Decimal("0")`, which does not itself distinguish known-zero from unknown.

`db.py`:
- `SCHEMA_VERSION = 10`.
- `campaign_fills` includes numeric financial columns stored using SQLite REAL.
- `save_campaign_fill()` requires symbol and stable exchange trade/execution ID; duplicate identity with differing fields raises a conflict. This is a useful identity guard, but comparison and writes convert quantity, price, quote quantity and fee values through `float(... or 0)`.
- A newly inserted fill may be committed by this method when no outer transaction is active. That alone does not prove that financial projections were updated atomically with the fill.
- `campaign_risk_reserved_quote()` and `campaign_capital_reserved_quote()` return floats and use SQL `COALESCE` to treat absent aggregate rows as zero. Whether this is valid depends on whether an empty aggregate is defined as known-zero in the relevant contract; this is not a substitute for proving source completeness.
- `save_execution_intent()` persists `invalidation_level` through `float(intent.invalidation_level or 0.0)`.

`campaign_execution.py`, `campaign_engine.py`, `portfolio_trader.py`, `trader.py`, and `execution_barrier.py` contain additional floating-point financial/configuration paths. This is a targeted inspection, not a complete transitive call graph.

### Android: CODE-CONFIRMED

`app/src/main/java/com/williamsbot/ExecutionAccumulator.kt`:
- Uses `BigDecimal` for accumulated quantity and quote.
- `decimal(value)` uses `value.toBigDecimalOrNull() ?: BigDecimal.ZERO`, collapsing invalid input into zero.
- `acceptFill()` defaults commission to `BigDecimal.ZERO` and asset to empty string; blank asset with positive commission is ignored by the fee branch rather than represented as an explicit unknown-fee state.
- Duplicate detection uses an in-memory set keyed by order/trade/event time. It is not a durable financial identity ledger by itself.
- VWAP uses `divide(qty, 18, RoundingMode.HALF_UP)`; this is existing behavior, not an approved shared contract.

`app/src/main/java/com/williamsbot/TradingAuditStore.kt`:
- The `executions` table declares `last_qty`, `last_price`, and `commission` as SQLite REAL.
- `recordUserEvent()` converts last quantity, last price, commission, original quantity, executed quantity and cumulative quote via `toDoubleOrNull() ?: 0.0`.
- Order projection computes average price using Double and stores REAL.
- `estimateFeesUsdt()` reads commission/price as Double and ignores non-positive commission; blank commission asset is treated as no fee valuation contribution while `known` remains true. This can conceal missing fee information in this projection.
- The inspected class is an audit/event store and projection writer; it is not yet proven to be the exclusive owner of financial-effect application.

`StandaloneRuntime.kt` includes Double-backed position, entry, stop, risk reservation, capital reservation, prices and exchange-filter fields. The full reachable path from every field to every mutation sink remains **PARTIALLY TRACED**.

`ExecutionGate.kt` is a small in-memory symbol reservation gate. It is not a global durable financial Recovery Gate and does not by itself prove that unknown financial state blocks every mutation sink.

## E. Financial effect ownership

**Ownership status: NOT VERIFIED.**

The inspected Python `save_campaign_fill()` persists an idempotency-checked fill row. It does not, in the inspected method, apply all position, campaign, fee, PnL and exposure projections. Search/inspection did not establish the requested functions `apply_financial_fill_effect()` and `resolve_financial_effect_fee()` as implemented in the current branch. No conclusion is made that such a function exists under another name without a complete source-to-sink proof.

Android has at least two distinct layers in evidence:
1. `TradingAuditStore.recordUserEvent()` writes raw events and REAL-backed execution/order projections.
2. `ExecutionAccumulator` aggregates fills in process memory.

The inspected code does not prove these are one transactionally coordinated financial-effect owner. Android's separate SQLite store also means Python and Android projections can diverge unless explicit reconciliation and authority rules prevent it.

Required but unproven chain:
`Exchange Fill → Canonical Ingestion → Durable Fill Identity → Financial Effect → Projections → Recovery Confirmation`

No exactly-once claim is made.

## F. Canonical contract decision

A contract version cannot yet be marked approved. The following are minimum required rules for the next implementation step:

- Numeric wire values must be parsed from strings/decimal JSON tokens without an intermediate binary float/Double.
- `KNOWN`, `UNKNOWN`, `INVALID`, and `CONFLICTED` are distinct states and must survive serialization/persistence.
- Required absent/blank numeric values are UNKNOWN; malformed, non-finite or out-of-range values are INVALID; incompatible authoritative values are CONFLICTED.
- Zero is KNOWN only when present, parseable, contract-valid, and authoritative for that field.
- Commission amount and commission asset are a coupled fact. Missing either required component leaves fee state unresolved. Unknown conversion value must not be represented as a zero-valued fee.
- Exact quantity/price/quote/fee values must not pass through Python float, Android Double, or SQLite REAL on the canonical exact-accounting path.
- VWAP is a rational quotient of exact aggregate quote / exact aggregate quantity. The contract must name the one rounding boundary, output scale and rounding mode; no platform-specific implicit rounding.
- Exchange filter quantization is a separate, explicit order-construction operation, not internal accounting rounding.
- Conflicts require reconciliation against an authoritative source; no last-write-wins.
- Contract version and a uniform output object for each golden vector must be finalized before runners can provide valid conformance evidence.

These are proposed requirements, not an assertion that the current runtime conforms.

## G. Fault injection and migration readiness

- Existing recovery contract tests are included in Campaign CI; CI passing those tests is not proof that the newly proposed 20 vectors or all financial-effect crash points are covered.
- No new targeted financial-effect fault-injection matrix was run in this task checkpoint.
- No SQLite migration was executed.
- Python schema version 10 and Android audit-store REAL columns are confirmed; a complete table-by-table schema lineage and historical data inventory is not complete.
- Historical SQLite REAL values cannot be assumed to recover their original decimal source strings. Any future migration must preserve original raw/authoritative exchange evidence where available and mark unprovable historical values for reconciliation.
- No lossless migration claim is made.

## H. Recovery Gate and mutation sinks

Current evidence is partial:
- Python ExecutionBarrier has durable intent/recovery checks; it is not yet proven to gate every entry/add-on/restart/background/mutation path on the financial states defined here.
- Android ExecutionGate reserves symbols in memory only; it is not proof of durable global recovery admission.
- Unknown commission, conflicting fills, uncommitted financial effects and Python/Android projection mismatches have not been shown to block every new-risk mutation path.
- Unverified Futures mutation paths remain **BLOCKED / NOT VERIFIED**.

## I. Acceptance matrix

| Criterion | Evidence status | Blocking reason |
|---|---|---|
| Input SHA Campaign CI | **CI-PROVEN** | Run 38121397154 passed on exact input SHA; this is only a baseline, not final-SHA CI |
| Dedicated Python CI on input SHA | **NOT VERIFIED** | No run in exact-SHA run listing; dispatch not available via connected tool |
| Dedicated Android APK workflow on input SHA | **NOT VERIFIED** | No run in exact-SHA run listing; dispatch not available via connected tool |
| Four-state semantics | **PARTIALLY TRACED** | No production contract + complete tests |
| Canonical Decimal contract | **NOT VERIFIED** | Contract draft not implemented/approved end-to-end |
| 20 golden vectors | **CODE-CONFIRMED** | Fixture exists, draft-0.1, not executed |
| Python golden runner | **NOT VERIFIED** | No runner executes all 20 through production functions |
| Android golden runner | **NOT VERIFIED** | Existing Android unit tests do not run this shared fixture set |
| Cross-platform differential run | **NOT VERIFIED** | Neither complete runner executed |
| Financial-effect owner | **NOT VERIFIED** | No proven exclusive/transactionally coordinated owner across projections |
| Exactly-once accounting | **NOT VERIFIED** | Durable idempotency at one fill writer does not prove all projections exactly once |
| Fault injection | **NOT VERIFIED** | Required crash/concurrency/SQLite matrix not executed |
| SQLite migration readiness | **PARTIALLY TRACED** | Schema and REAL use known; complete table/version/data/rollback plan absent |
| Global Recovery Gate | **PARTIALLY TRACED** | No complete mutation-sink coverage proof |
| Final-SHA CI | **NOT VERIFIED** | This report commit changes the SHA and requires fresh runs |

## J. Minimal next step

1. Finalize a uniform fixture result schema and explicitly decide VWAP precision/rounding and filter policy.
2. Implement a strict decimal/state parser used by the real Python fill ingestion path; add tests proving missing/blank/invalid/conflicted values do not become zero.
3. Locate and prove the real Android event → durable fill identity → financial effect path before adding an Android wrapper; use the same fixture source in JVM tests.
4. Identify the single logical owner and transaction boundary for each supported production contract, then add crash/restart/two-process tests against the real SQLite state.
5. Complete the mutation-sink inventory and Recovery Gate persistence proof.
6. Only after those changes, run all required CI and golden tests on one final SHA.

**Final decision: TASK 007.9 — BLOCKED — NOT ACCEPTED.**
