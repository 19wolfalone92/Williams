# WILLIAMS BOT 2.1 — TASK 007.10
## Executable Canonical Financial Contract, Python/Android Differential Tests & Recovery-Gate Semantics

**Decision: BLOCKED — NOT ACCEPTED**

**Audit mode:** Remote GitHub source and workflow inspection. No local checkout could be obtained in this execution environment (attempted clone failed because the environment could not resolve github.com). No production tests were run locally. No financial schema migration, Mainnet/Testnet trade mutation, merge, or PR creation was performed. This report does not claim that source code was changed or that the working tree is clean.

## 1. Revalidated repository state

| Item | Verified result |
|---|---|
| Repository | `19wolfalone92/Williams` |
| Working branch | `audit/williams-2.1-task-007-recovery` |
| Input/source SHA for TASK 007.10 | `e7b66969847fede13c24711682a90b617ef2c44b` |
| Current main SHA | `04da5f9c09572e99ff310be3a1908179165e528c` |
| Branch head at inspection | `e7b66969847fede13c24711682a90b617ef2c44b` |
| Branch relationship | Branch head is the recorded TASK 007.9 commit; parent is `e67697ed9a0a89c8143c572c92bea383eb531abb` |
| Open PR for this exact recovery branch | None returned by GitHub query |
| Other PRs | Other open PRs exist for other branches; they are not this task's PR |
| Main changed / merged | No |
| Local worktree cleanliness | NOT VERIFIED; no local checkout was available |
| Production code/fixture changes in this task | None made in this checkpoint; only this audit report is being added |

Branch and main refs were re-read from GitHub rather than assumed from the task prompt.

## 2. Workflow state before changes

### Campaign CI on the input SHA

- Workflow: Williams Campaign CI
- Run ID: `38122111443`
- URL: https://github.com/19wolfalone92/Williams/actions/runs/38122111443
- Head SHA: `e7b66969847fede13c24711682a90b617ef2c44b`
- Final status: **completed / success**
- Python job ID: `114418805914` — completed / success
- Android job ID: `114418805801` — completed / success

This run validates the configured existing Campaign CI test set at the input SHA. It does **not** prove that the financial golden vectors were executed, because the Campaign CI test command does not include a golden-fixture runner for `tests/fixtures/financial_decimal_golden_vectors.json`.

### Dedicated workflows

- `.github/workflows/python-ci.yml` exists and declares `workflow_dispatch`.
- `.github/workflows/android-apk.yml` exists and declares `workflow_dispatch`.
- No dedicated run for either workflow was found in the available run evidence for the input SHA.
- The available GitHub connector provides read/inspection actions but no workflow-dispatch action. These runs were not fabricated or claimed.

Dedicated Python backend CI and Android APK workflow evidence: **NOT VERIFIED** for the input SHA.

## 3. Fixture audit: D001–D020

Source path: `tests/fixtures/financial_decimal_golden_vectors.json`  
Blob SHA: `cfdf51ca9f201c5435842346501f44f752c20944`  
Fixture set: `williams-financial-decimal-golden-vectors`  
Current contract version: `draft-0.1`  
Declared status: `PROPOSED_NOT_EXECUTED`

All 20 cases were inspected. They are not yet a complete executable contract: the current schema does not provide a uniform canonical result object for each vector, and several cases leave material decisions open. The following is the audited coverage, not a claim that any case has been executed:

| Vector | Input/intent | Current semantic gap / acceptance note |
|---|---|---|
| D001 | Price `12.3400`; preserve source scale | Needs uniform canonical serialization and parse-state schema |
| D002 | Tiny quantity `0.00000001` | Acceptance depends on field/instrument constraints; currently not encoded |
| D003 | Price `0.1`; prohibit float round-trip | Good precision invariant; no runner currently proves it |
| D004 | Fee `0.00000037 BNB` | Fee amount/asset can be known while quote valuation remains unknown; effect policy must distinguish fee booking from quote valuation |
| D005 | Authoritative zero fee | KNOWN zero only if both amount and required asset/authority are present and valid |
| D006 | Missing fee | Must remain UNKNOWN; effect eligibility must be explicitly represented |
| D007 | Empty price | Required price is UNKNOWN/missing, not zero; block financial effect |
| D008 | Quantity `NaN` | INVALID, never zero |
| D009 | Price `1.2.3` | INVALID, never zero |
| D010 | Exchange order `0.300` vs ledger `0.301` | CONFLICTED; reconciliation, no last-write-wins |
| D011 | Quote quantity round trip | Exact canonical serialization/deserialization must be asserted |
| D012 | Fills `0.1 @ 10.25`, `0.2 @ 10.30` | Executed quote = `3.085`; exact VWAP is `3.085 / 0.3`; final rounding/scale is not approved |
| D013 | Partial fill `0.125 / 1.000` | Remaining = `0.875`; order and exposure must reconcile; source authority must be specified |
| D014 | Fee arrives after UNKNOWN | Must update fee-specific effect idempotently without replaying base fill effect; no such end-to-end proof found |
| D015 | Quantity `0.1234`, step `0.001` | Current expected operation explicitly allows either rejection or a separately approved quantization policy; therefore not deterministic |
| D016 | Quantity scale 18 | Depends on unapproved field and symbol precision contract |
| D017 | Negative order quantity | INVALID; reject non-positive order quantity |
| D018 | Signed realized PnL `-12.3400` | Negative value is valid for signed PnL; preserve exact decimal |
| D019 | Negative commission | INVALID under the current proposed contract; reject and reconcile |
| D020 | VWAP with zero quantity/quote | UNKNOWN; do not emit authoritative zero VWAP |

**Fixture conclusion:** the current 20 fixtures have not been promoted to an approved executable contract. No expected values have been silently invented to resolve D012, D015, D016, or other missing uniform result fields.

## 4. Proposed contract constraints — not yet production-approved

These are safety constraints derived from the required semantics. They are not represented as implemented behavior until production integration and tests prove them.

### 4.1 Parsing and decimal representation

- Wire-level financial numbers must be parsed from decimal strings (or a JSON parser mode preserving decimal tokens) without an intermediate binary float/Double.
- Reject NaN, infinities, malformed decimal strings, over-limit precision, and values outside the declared field range with a stable machine-readable error code.
- Missing/blank required values are UNKNOWN/missing; malformed values are INVALID; conflicting authoritative values are CONFLICTED. None maps to zero.
- Canonical output should preserve value exactly and encode it as a base-10 string. Scale preservation and numeric equality should be separate assertions: e.g. `12.3400` preserves source scale while comparing numerically equal to `12.34`.
- A zero value is KNOWN only when the field is present, valid, and supported by the authoritative source. Defaults do not establish knowledge.
- Field-level maximum precision, magnitude, exponent support, and sign rules must be declared before accepting D002/D016 or any instrument-dependent quantity.
- Error serialization must use stable contract codes rather than platform exception text.

### 4.2 Commission state

- **KNOWN:** authoritative valid non-negative amount and required asset identity are present; an authoritative zero is allowed.
- **UNKNOWN:** amount or asset is missing/not yet delivered, or quote valuation is unavailable. Preserve amount-known and valuation-known as separate facts where possible.
- **INVALID:** malformed, negative (unless a documented exchange-specific correction contract explicitly permits it), non-finite, or out-of-range amount/asset input.
- **CONFLICTED:** two authoritative representations for the same fee identity disagree and no approved authority precedence resolves the difference.
- A fee arriving later must update only the fee effect/version or fee adjustment under durable idempotency. It must not reapply quantity/quote fill effects.
- An unknown third-asset fee must not be treated as zero merely because conversion to quote currency is unavailable.

### 4.3 VWAP

Mathematical expression: `sum(price_i * quantity_i) / sum(quantity_i)`.

- Only validated, unique, authoritative fills are eligible.
- Each fill requires positive quantity and positive execution price; the authoritative quote quantity, if supplied, must be validated against the fill's price × quantity according to a documented exchange contract.
- Accumulate quantity and quote using exact decimal arithmetic. Do not round per fill.
- If aggregate quantity is zero/missing, return UNKNOWN or INVALID according to the actual input state; never emit a known zero VWAP.
- If authoritative inputs conflict, return CONFLICTED and request reconciliation.
- A canonical rounded VWAP cannot be approved from the existing evidence alone. The fixture explicitly says `3.085 / 0.3` and does not specify a common scale/rounding boundary. Android's current scale-18 HALF_UP is existing behavior, not an approved rule. Until an approved output precision and rounding mode are added to the contract, no production path should treat that rounded VWAP as canonical financial truth.

### 4.4 Exchange filters and quantity quantization

Keep these as distinct steps:
1. Calculate desired quantity from strategy/risk/balance.
2. Validate filter metadata itself (stepSize, minQty, maxQty, min notional).
3. Determine whether quantity is already step-aligned.
4. Apply quantization only when an explicit, approved policy identifies direction and rounding mode.
5. Recompute notional and risk after quantization, then perform final order validation.

A mismatch must not be silently rounded. If quantization policy or filter metadata is unknown/invalid, return a stable blocked result. The policy must not increase exposure beyond the approved risk budget, exceed available balance, or violate min/max/notional limits. This policy needs symbol-side tests before D015 can become deterministic.

## 5. Production source evidence

### Python — CODE-CONFIRMED

**`execution_accumulator.py`**
- `accumulate_fills()` parses `qty`, `price`, and `commission` with `Decimal(str(value or "0"))`; absent and empty values can become zero before validation.
- Invalid decimal construction may raise rather than return a canonical INVALID state.
- Missing commission defaults to zero. Third-asset fee handling is keyed on a positive commission; absent asset/zero/malformed distinctions are not fully represented.
- `ExecutionSummary` has default-zero fee fields, which cannot alone distinguish known zero from unknown.
- `accumulate_order()` defaults missing executed quantity and quote quantity to zero and may derive fallback average price from the order price. That behavior requires semantic validation before being used as authoritative financial state.

**`db.py`**
- `Database.SCHEMA_VERSION = 10`; financial fields in tables including `campaign_fills`, `campaigns`, `campaign_orders`, and `trades` use SQLite REAL.
- `save_campaign_fill()` has a stable exchange trade/execution identity guard and detects some conflicting duplicate payloads, but converts financial fields through `float(... or 0)` before persistence/comparison.
- Fill persistence is not proof that position, campaign, fee, PnL, risk and capital projections were applied atomically.
- `save_execution_intent()` stores invalidation level using `float(value or 0.0)`.
- Actual caller set for `save_campaign_fill()` and the complete call chain to every financial writer were not exhaustively established in this remote-only checkpoint.

### Android — CODE-CONFIRMED

**`app/src/main/java/com/williamsbot/ExecutionAccumulator.kt`**
- Uses BigDecimal internally, but event parsing calls `decimal(event.optString(...))`; the decimal helper uses `toBigDecimalOrNull() ?: BigDecimal.ZERO` (confirmed in the previously audited source; the exact method is within this file).
- `acceptFill()` defaults commission to zero and asset to blank.
- For a positive commission and blank commission asset, the current branch does nothing rather than recording an explicit UNKNOWN fee state.
- Deduplication is in-memory, keyed by order/trade/event-time tuple; it is not a durable financial identity ledger by itself.
- The accumulator is separate from the durable SQLite audit/projection store.
- VWAP uses scale 18 and HALF_UP; this is existing behavior, not a shared approved rule.

**`app/src/main/java/com/williamsbot/TradingAuditStore.kt`**
- SQLite `orders`, `executions`, and `trades` tables include REAL-backed financial columns.
- Previously audited writers convert numeric strings through Double parsing with zero fallback, and average price is calculated using Double.
- Fee estimation can skip non-positive fees; a blank asset does not by itself establish an explicit unknown-fee state.
- The store retains raw event JSON, but raw-event durability does not prove that canonical financial effects are applied once and atomically.

**`app/src/main/java/com/williamsbot/ExecutionGate.kt`**
- This is an in-memory per-symbol reservation gate (`tryReserve/release/isReserved`), not a durable global financial Recovery Gate.
- The inspected implementation does not itself evaluate fee state, unresolved financial effects, conflicting authorities, or invalid exchange filters.

### Field inventory snapshot

| Field / transform | Current representation/behavior | Required treatment | Status |
|---|---|---|---|
| Python fill qty/price | Decimal parsed after `value or "0"` | Missing vs invalid vs known must remain distinct | CODE-CONFIRMED gap |
| Python commission amount/asset | Decimal default zero; asset string branch | Explicit fee state and asset required for KNOWN | CODE-CONFIRMED gap |
| Python DB fill qty/price/quote/fee | float then SQLite REAL | Exact canonical path must not traverse float/REAL | CODE-CONFIRMED gap |
| Android event qty/price/commission | BigDecimal parser with zero fallback | Parser must return typed state/error | CODE-CONFIRMED gap |
| Android execution persistence | SQLite REAL / Double projections | Exact canonical path must use lossless representation | CODE-CONFIRMED gap |
| Android commission asset | Blank asset may be ignored | UNKNOWN or INVALID according to field presence/contract | CODE-CONFIRMED gap |
| Android VWAP | scale 18 HALF_UP | Shared rounding boundary must be approved first | CODE-CONFIRMED divergence risk |
| Filter quantization | Policy not fully encoded in fixtures | Explicit policy or block | NOT VERIFIED |
| `save_campaign_fill()` callers | Full transitive caller inventory not established | Enumerate all callers and sinks | NOT VERIFIED |
| Recovery Gate at all risk-increase sinks | No global durable gate proven | Gate must dominate every entry/increase mutation sink | NOT VERIFIED |

## 6. Python/Android runners and differential testing

**Result: NOT IMPLEMENTED / NOT VERIFIED in this checkpoint.**

No production source changes or runner wiring are claimed. The Campaign CI command currently runs a curated list of tests and does not include a Python golden runner for the fixture file. The Android Campaign CI command runs `:app:testDebugUnitTest`, lint, and APK assembly, but no inspected evidence establishes a test that loads the same JSON fixture and invokes the production accumulator/store contract.

Required missing work:
- a single fixture file used by both test environments;
- a Python runner invoking production parsing/accumulation methods;
- an Android test that loads the same fixture bytes from a shared resource or deterministic generated resource and invokes production methods;
- uniform canonical output objects including parse/result state, normalized decimal strings, commission state, effect eligibility, Recovery Gate decision, and stable reason code;
- a comparator that fails on any per-vector discrepancy;
- mutation/negative tests proving changes to amount, asset, qty, price, scale, sign, source conflict, or expected gate state are detected.

A schema-only test or duplicate test-only financial algorithm would not satisfy this requirement.

## 7. Recovery Gate coverage

**Status: NOT VERIFIED; P0 blocker.**

The inspected `ExecutionGate` only reserves a symbol in memory. No proof was found that a durable financial-state gate dominates every reachable risk-increase mutation path. Required classifications must be explicit:

| Condition | New risk-increasing operation | Reconciliation/diagnostics | Risk-reducing operation |
|---|---|---|---|
| Commission UNKNOWN | Block unless a separately approved policy proves fee uncertainty cannot compromise exposure/accounting safety; current contract has no such approved exception | Allow | Allow only under a separately verified safe-exit path; do not invent a fee value |
| Commission INVALID | Block | Allow | Safe reduction may proceed only if order/exposure state is otherwise authoritative and mutation path is independently guarded |
| Commission CONFLICTED | Block | Required | Permit only a verified safe reduction that does not rely on disputed accounting |
| Quantity/price invalid or missing | Block | Allow | Do not derive an exit quantity/price from invalid data; reconcile authoritative exchange state |
| Previous operation UNKNOWN / COMMIT outcome uncertain | Block | Required | Only verified idempotent risk reduction after authoritative reconciliation |
| Financial effect pending/incomplete | Block | Required | Must not replay base effect; reduction requires independently proven safe execution |
| Exchange sources disagree | Block | Required | Do not choose last-write-wins; safe reduction needs authoritative order/position resolution |
| Filter metadata invalid/unknown | Block new order | Allow | Do not bypass filter validation; use a separately verified emergency-safe path if one exists |

This table is a safety disposition, not evidence that these guards exist in code. All mutation sinks and all risk-increase callers remain to be inventoried and tested. No claim is made that diagnostic or risk-reducing paths are correctly exempted today.

## 8. Financial-effect ownership and exactly-once

**Status: BLOCKED / NOT VERIFIED independently of golden tests.**

The required chain is:

`Exchange Fill → Canonical Ingestion → Durable Fill Identity → Financial Effect → Projections → Recovery Confirmation`

Evidence supports a fill identity guard in Python persistence and in-memory duplicate filtering in Android. It does not establish:
- one exclusive financial-effect owner;
- a durable unique identity and effect-status state machine across crashes;
- a transaction containing identity, effect and all projections;
- who marks a fill APPLIED and under what commit/reconciliation conditions;
- handling of an ambiguous COMMIT result;
- protection against old/conflicting writers;
- fee-only late enrichment without replaying base fill effects;
- recovery confirmation that all projections agree with authoritative exchange state.

No second ledger was created. No historical REAL-to-text migration was run or declared safe. Existing REAL precision loss cannot be reconstructed from the rounded value alone; any migration proposal must retain raw/exchange source evidence and define reconciliation/rollback before implementation.

## 9. Required additional tests

The following required tests are **NOT VERIFIED as implemented and passing under TASK 007.10**:

1. Missing quantity.
2. Missing price.
3. Empty commission.
4. Authoritative known-zero commission.
5. Invalid decimal string.
6. Overflow / out-of-range value.
7. Unknown commission asset.
8. Conflicting commission authorities.
9. Late commission enrichment.
10. Zero aggregate quantity for VWAP.
11. Partial fills with unequal prices.
12. Exact stepSize boundary.
13. Quantity below minQty.
14. Exact minimum-notional boundary.
15. Decimal serialization differences.
16. Python/Android result mismatch.
17. Recovery Gate with unresolved financial state.
18. Repeated delivery of the same source data.

Additionally, exactly-once, transaction atomicity, ambiguous-commit recovery, and crash/restart tests against the real persistence boundary are separate requirements and cannot be replaced by these parser/aggregation vectors.

## 10. CI and evidence matrix

| Evidence class | Result |
|---|---|
| CODE-CONFIRMED | Current Python/Android zero-fallback, REAL/Double, fee-state, and in-memory gate gaps identified from remote source |
| TEST-PROVEN | No TASK 007.10 golden/differential test run proven |
| CI-PROVEN | Campaign CI run `38122111443` passed on input SHA `e7b66969847fede13c24711682a90b617ef2c44b`; this is baseline CI only, not proof of the required runners |
| EMULATOR-PROVEN | NOT VERIFIED |
| DEVICE-PROVEN | NOT VERIFIED |
| TESTNET-OBSERVED | NOT VERIFIED; no trading mutations performed |
| NOT VERIFIED | Executable contract, both runners, differential results, exhaustive callers, global Recovery Gate, exactly-once ownership, atomic projections, crash recovery, dedicated workflow results on a final implementation SHA |

No CI run is claimed for a final implementation SHA because no implementation commit was produced in this checkpoint. Once source/fixture changes exist, all required workflows must run on that exact resulting SHA.

## 11. P0 blockers

1. No approved, machine-complete contract schema for all 20 vectors.
2. D012 VWAP canonical precision/rounding boundary unresolved.
3. D015 quantity quantization policy unresolved; two outcomes are currently allowed.
4. D016 precision eligibility depends on an unspecified field/symbol contract.
5. No Python production-path golden runner.
6. No Android production-path golden runner loading the same fixture.
7. No differential comparator and mutation/negative tests.
8. Production fallback-to-zero and float/Double/SQLite REAL paths remain.
9. Full `save_campaign_fill()` caller and financial-writer inventory remains incomplete.
10. Global Recovery Gate is not proven to dominate all risk-increase mutation sinks.
11. Single durable financial-effect owner, atomic projections, ambiguous-commit handling, and crash recovery remain unproven.
12. Dedicated Python CI and Android APK workflow runs for the final implementation SHA are absent.

## 12. Next minimal safe step

1. Obtain a local checkout or another execution-capable repository workspace for the exact branch SHA; verify HEAD and clean/dirty state before editing.
2. Resolve contract decisions in the fixture schema first: canonical result object, field constraints, commission state, VWAP rounding boundary, and deterministic filter policy. Have these decisions reviewed before changing production behavior.
3. Implement a typed canonical decimal/commission result in production and migrate only the directly connected callers in a small vertical slice. Remove fallback-to-zero at those boundaries without changing unrelated zero defaults.
4. Wire both test runners to the same fixture and compare normalized results. Add negative tests before broad call-site migration.
5. Trace every risk-increase mutation sink and add a durable fail-closed gate; separately test fee enrichment and financial-effect ownership/transaction recovery.
6. Run Campaign CI, dedicated Python CI, and Android build/unit tests on the same final SHA; then report exact run IDs and conclusions.
7. Keep trading mutation paths blocked until these P0 items are closed. Do not migrate existing financial tables as part of this task.

**Final decision: TASK 007.10 — BLOCKED — NOT ACCEPTED.**

This decision reflects unimplemented/unproven acceptance criteria; it does not imply the baseline Campaign CI failed.
