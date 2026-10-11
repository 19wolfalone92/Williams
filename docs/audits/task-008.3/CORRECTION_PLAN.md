# TASK 008.3 — Correction plan

**Status: BLOCKED — CONTRACT APPROVAL AND SAFE CODE CORRECTIONS NOT COMPLETE**  
Baseline SHA: 1a7e7209c99152c3bf29f3e2f07e7db088d9243b  
Baseline CI: run 38129367877

## Baseline gate

- Exact baseline SHA is confirmed in preserved CI logs.
- Four TASK 008.2 reports and complete Python/Android CI logs were read.
- Environment: Ubuntu 24.04.5, Python 3.12.15, JDK 17, Gradle 8.14.6.
- Dependency installation uses lower-bound ranges without a lock file. SQLite runtime and exact Android SDK version were not recorded.
- This is a remote CI baseline, not an independent local replay.
- The current target branch has 13 commits after baseline, containing TASK 008.2 report/log artifacts only. No production-code correction was made in TASK 008.3.
- Current branch CI is NOT VERIFIED; no confirmed post-artifact workflow run was found.

## Group A — ExecutionBarrier and trusted preflight

**Failures:** F0082-001, F0082-002, F0082-003.

1. F0082-001 test supplies the legacy preflight_provider callback. ExecutionBarrier._verify_preflight deliberately rejects callback-only proof and requires a concrete PreflightCheckService bound by object identity to the exact Testnet client. The test setup conflicts with that implementation contract. Proposed safe action: use a concrete fake-client-backed service that actually satisfies required checks; retain negative cases for missing, stale, mismatched and forged preflight. Do not restore callback admission.
2. F0082-002 constructs MultiPositionTrader via __new__ and omits db. The production constructor normally assigns self.db = db or Database(). The test therefore creates a partial object outside the constructor invariant. Do not add a fake production db attribute. Test a constructor-valid object, and separately assert fail-closed behavior for a deliberately partial object.
3. F0082-003 constructs MultiPositionTrader without injecting execution_barrier. The mutation path correctly blocks when canonical ExecutionBarrier is absent. The correct fix is to verify composition-root wiring or inject a valid canonical barrier in the test, not bypass it.

Code changes: NOT MADE. The production composition/factory path must be traced before deciding whether construction belongs in the trader or composition root.

## Group B — Residual position and emergency exit

**Failures:** F0082-004, F0082-005.

4. F0082-004 simulates manual sell fill quantity 0.6 with remaining base balance 0.4 and an existing OCO. The final failed assertion needs line-by-line method and persistence tracing; root cause remains UNKNOWN.
5. F0082-005 is a confirmed capability blocker: the implementation sets RECONCILE_REQUIRED and rejects EMERGENCY_EXIT because the dedicated execution contract is not implemented.

Emergency exit must not be implemented by removing the guard. A separately approved contract must define durable intent before mutation, canonical barrier admission, stable client order ID, owned-position validation, protection cancel/replace, partial-fill accounting, reconciliation, restart recovery, and the evidence required before marking FLAT. Missing/unknown protection must block new entries. Required failure injection: pre-submit failure, timeout after submit, partial fill, persistence failure, restart, and unknown protection. Status: NOT IMPLEMENTED; approval required.

## Group C — Signed POST timestamp retry

**Failure:** F0082-006.

The test reuses one _mutation_permit around two attempts. The client requires a permit inside the mutation loop, while permit consumption is one-use. This is a contract mismatch; one-use semantics must not be weakened.

Safe retry options, pending contract approval:
- If a -1021 response is definitively classified as rejection before acceptance, allow a fresh barrier admission tied to the same durable intent and stable client ID.
- If the outcome is ambiguous, set RECONCILE_REQUIRED and query order/trade history by stable client ID before another mutation.
- Timeout, 5xx, malformed response, transport failure or lost response must never be blindly retried.

No retry implementation or test assertion was changed. Safe fresh-admission semantics and durable linkage still need verification.

## Group D — Preflight UNKNOWN and freshness

**Failures:** F0082-007 through F0082-011.

- Preflight uses max_offset_ms or LIVE_MAX_TIME_OFFSET_MS. Absence intentionally leaves the time proof UNKNOWN and causes early return. No approved numeric clock-offset budget was found in the reviewed contract. Do not invent a value to green the suite. Obtain an explicit risk/operations-approved budget.
- Tests that intend to exercise API permissions and open orders must explicitly use that approved budget, while retaining negative tests for missing/invalid budget.
- Whether permission/order diagnostics should run independently after a failed time gate is a contract decision. Diagnostics may continue only if aggregate readiness remains blocked while any required proof is UNKNOWN.
- The open-order tests must reach the actual order check; a boolean reconciliation override must never replace durable reconciliation evidence.
- Freshness implementation returns measurement_timestamp_in_future; the test expects measurement_clock_skew. Both produce UNKNOWN. The canonical reason-code contract is unresolved. Do not change either side solely to make CI green.

Code changes: NOT MADE for this group.

## Failure closure ledger

| Failure ID | Test | Baseline SHA | Root cause | Code change | Regression test | Result SHA | CI evidence | Status |
|---|---|---|---|---|---|---|---|---|
| F0082-001 | execution barrier serialization | 1a7e7209... | legacy callback supplied instead of trusted service; setup mismatch confirmed | None | concrete service positive path plus forged/missing negatives | N/A | run 38129367877 | BLOCKED |
| F0082-002 | legacy wrapper fail-closed | 1a7e7209... | test bypasses constructor and omits db | None | valid construction plus partial-object negative case | N/A | run 38129367877 | BLOCKED |
| F0082-003 | independent portfolio trades | 1a7e7209... | barrier dependency omitted; composition wiring not proven | None | valid barrier path and missing-barrier block | N/A | run 38129367877 | BLOCKED |
| F0082-004 | partial manual sell | 1a7e7209... | UNKNOWN | None | residual quantity, protection, DB and exchange-state assertions | N/A | run 38129367877 | NOT VERIFIED |
| F0082-005 | partial emergency sell | 1a7e7209... | dedicated contract explicitly unimplemented | None; design only | failure-injection suite after approval | N/A | run 38129367877 | NOT IMPLEMENTED |
| F0082-006 | signed POST timestamp retry | 1a7e7209... | one-use permit conflicts with same-permit retry test | None | fresh admission only after definitive rejection; ambiguous result never retries | N/A | run 38129367877 | BLOCKED |
| F0082-007 | unknown runtime proofs | 1a7e7209... | missing approved offset budget causes early return | None | approved budget plus missing-budget negative test | N/A | run 38129367877 | BLOCKED |
| F0082-008 | withdrawal restriction | 1a7e7209... | same prerequisite issue; permission check not reached | None | safe/unsafe permission response after time proof | N/A | run 38129367877 | BLOCKED |
| F0082-009 | foreign open order | 1a7e7209... | same prerequisite issue; order check not reached | None | foreign order fails after time proof | N/A | run 38129367877 | BLOCKED |
| F0082-010 | boolean reconciliation override | 1a7e7209... | same prerequisite issue; reconciliation check not reached | None | durable reconciliation required | N/A | run 38129367877 | BLOCKED |
| F0082-011 | future timestamp freshness | 1a7e7209... | reason-code mismatch; canonical contract unresolved | None | UNKNOWN with approved canonical reason ID | N/A | run 38129367877 | NOT VERIFIED |

## Exit criteria

TASK 008.3 cannot be PASS until critical contracts are approved, corrections are made in isolated commits, each failure has a regression result on the result SHA, the full Python and Android CI reruns, and safety invariants are reviewed. Green CI alone does not prove Spot Testnet end-to-end readiness.
