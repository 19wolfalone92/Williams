# WILLIAMS BOT 2.1 — TASK 007.11
## Executable Financial Contract, Python/Android Differential Testing & Decimal Integrity Closure

**Decision: BLOCKED — NOT ACCEPTED**

## 1. Baseline and execution-environment gate

Repository: `19wolfalone92/Williams`  
Task branch: `audit/williams-2.1-task-007-recovery`  
Branch HEAD before this report: `50fc12aee8e98ad6cf0256dd502c1ae7db181ca4`  
`main` HEAD: `04da5f9c09572e99ff310be3a1908179165e528c`  
Input SHA named in TASK 007.10: `e7b66969847fede13c24711682a90b617ef2c44b`

GitHub refs were queried directly. The task branch is at the TASK 007.10 audit-report commit, not the earlier input SHA. Main remains unchanged. No open PR was returned for this recovery branch; open PR #50 is a separate TASK 005 branch and is not this task's PR.

**Execution environment:** a local, executable repository checkout was not available in this task session. A prior checkout attempt failed because the environment could not resolve `github.com`. Remote source inspection is possible, but it is not a substitute for modifying and running the repository. Therefore no production implementation, fixture rewrite, runner implementation, local test execution, or local worktree cleanliness claim is made. This is an explicit execution blocker, not a completed implementation.

## 2. Workflow evidence

Campaign CI run `38126718564` completed with conclusion `success` on SHA `50fc12aee8e98ad6cf0256dd502c1ae7db181ca4`.  
Run: https://github.com/19wolfalone92/Williams/actions/runs/38126718564

This is baseline Campaign CI on the current report-parent SHA. It does not include the required TASK 007.11 fixture differential runners and therefore does not prove TASK 007.11 acceptance.

Dedicated Python backend CI and Android APK workflow runs on a TASK 007.11 implementation SHA: **NOT VERIFIED**. No implementation SHA exists in this checkpoint.

## 3. Remote source findings — CODE-CONFIRMED

### Python `execution_accumulator.py`
- `accumulate_fills()` still parses quantity, price, and commission using `Decimal(str(value or "0"))`.
- Missing/empty fields can be interpreted as zero before semantic validation.
- Non-positive quantity/price fills are skipped rather than returned as a typed INVALID/UNKNOWN result.
- `ExecutionSummary` has zero-default fee fields and a zero fallback for VWAP when aggregate quantity is zero. This does not preserve KNOWN-zero versus UNKNOWN.
- Third-asset commission is tracked as unvalued for positive amounts, but that does not supply the required general KNOWN/UNKNOWN/INVALID/CONFLICTED contract.

### Python `db.py`
- Schema version remains 10.
- Existing financial tables include SQLite REAL-backed fields.
- The previously audited `save_campaign_fill()` path converts financial fields using `float(... or 0)`; no exact canonical string-based persistence path was proven.
- No mass migration was performed.

### Android `ExecutionAccumulator.kt`
- Uses `BigDecimal` for accumulation, but its JSON parsing path calls a decimal helper that was previously found to fall back to `BigDecimal.ZERO` on parse failure.
- `acceptFill()` defaults commission to `BigDecimal.ZERO` and commission asset to an empty string.
- Fill deduplication is in-memory and does not prove durable exactly-once application.
- VWAP uses scale 18 / HALF_UP in the existing implementation; this is not an approved shared canonical rounding contract.

### Android `TradingAuditStore.kt`
- Existing `orders` and `executions` schema includes REAL-backed quantity, quote, average-price and commission fields.
- A lossless canonical financial storage path has not been proven. No database migration was performed.

### Android `ExecutionGate.kt`
- Implements in-memory symbol reservation. It is not a durable global Recovery Gate and does not itself prove financial-state checks dominate every risk-increasing mutation path.

## 4. Fixture contract status

The shared fixture remains `tests/fixtures/financial_decimal_golden_vectors.json`, contract version `draft-0.1`, status `PROPOSED_NOT_EXECUTED` at the audited parent SHA.

The required ambiguities remain unresolved in production and test evidence:

- **D012 VWAP:** the vector supplies the exact expression `3.085 / 0.3`, but no approved common output scale/rounding boundary. Existing Android scale 18 / HALF_UP is not automatically normative.
- **D015 quantization:** the fixture still allows rejection or an approved explicit quantization policy as alternatives. A single deterministic outcome is not encoded.
- **D016 precision:** accepted scale depends on field and instrument limits not specified in the vector.
- All 20 vectors lack proof of execution through real Python and Android production callers.

No expected outcomes have been fabricated to conceal these unresolved policy decisions.

## 5. Runner and differential-test status

| Requirement | Status |
|---|---|
| Python runner consumes the shared fixture and invokes production code | NOT IMPLEMENTED / NOT VERIFIED |
| Android runner consumes the same fixture content and invokes production code | NOT IMPLEMENTED / NOT VERIFIED |
| Canonical result schema and stable reason codes | NOT APPROVED / NOT VERIFIED |
| Automated D001–D020 differential comparator | NOT IMPLEMENTED / NOT VERIFIED |
| Negative/mutation tests proving mismatches fail | NOT IMPLEMENTED / NOT VERIFIED |
| Required missing/invalid/overflow/commission/conflict tests | NOT VERIFIED |
| Exact same final SHA CI evidence for both platforms | NOT VERIFIED |

## 6. Recovery Gate and financial-effect ownership

**Recovery Gate: BLOCKED / NOT VERIFIED.** The current inspected gate is a reservation primitive. No complete call-graph proof shows that UNKNOWN/INVALID/CONFLICTED commission, invalid quantity/price, unresolved COMMIT, incomplete financial effect, exchange disagreement, invalid filters, or position/balance/reservation mismatch blocks all reachable risk-increasing paths.

Risk-reducing/emergency execution must not be assumed safe by exemption; it requires a separately verified path that uses authoritative reconciled order/exposure state.

**Financial-effect ownership / exactly-once: BLOCKED / NOT VERIFIED.** No proof exists here of one exclusive effect owner, durable fill identity plus APPLIED state, atomic effect/projection transaction, ambiguous-COMMIT handling, late-fee enrichment without replaying base fill, legacy-writer containment, or durable recovery confirmation. Golden tests alone would not establish these properties.

## 7. Evidence classification

- **CODE-CONFIRMED:** the source gaps listed above are visible in the inspected remote files.
- **TEST-PROVEN:** no TASK 007.11 golden or differential test results.
- **CI-PROVEN:** Campaign CI `38126718564` succeeded on `50fc12aee8e98ad6cf0256dd502c1ae7db181ca4`, but this is baseline CI, not proof of the requested contract.
- **EMULATOR-PROVEN:** NOT VERIFIED.
- **DEVICE-PROVEN:** NOT VERIFIED.
- **TESTNET-OBSERVED:** NOT VERIFIED; no trade mutations were performed.
- **NOT VERIFIED:** implementation, exact Python/Android parity, full risk-increase sink coverage, exactly-once accounting, transaction atomicity, crash recovery, and dedicated Python/Android CI on an implementation SHA.

## 8. P0 blockers and next safe step

1. Obtain an execution-capable checkout/workspace and verify the exact branch HEAD before edits.
2. Approve a complete contract schema for D001–D020, including deterministic VWAP boundary and filter quantization policy.
3. Implement a typed canonical financial parsing/result component and integrate a narrow, caller-verified Python/Android vertical slice.
4. Wire both test environments to the same fixture bytes; implement differential and negative tests.
5. Remove fallback-to-zero only on verified production paths while preserving legitimate, authoritative zero values.
6. Inventory all fill writers and mutation sinks; prove Recovery Gate enforcement on every reachable risk-increasing path.
7. Independently close financial-effect ownership, atomicity, ambiguous-COMMIT and crash-recovery evidence.
8. Run required Python and Android workflows on the same exact implementation SHA and report run IDs.

No Mainnet/Testnet trade mutation, schema migration, merge, or main-branch modification was performed.

**Final decision: TASK 007.11 — BLOCKED — NOT ACCEPTED.**
