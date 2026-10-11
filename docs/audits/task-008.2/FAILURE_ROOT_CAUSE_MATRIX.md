# Failure root-cause matrix — TASK 008.2

Baseline SHA: `1a7e7209c99152c3bf29f3e2f07e7db088d9243b`  
Evidence: raw CI logs under `baseline-logs/`; source files read at the same SHA.

| Failure ID | Evidence / immediate mechanism | Root cause classification | Confirmed root cause? | Affected contract | Minimal safe next step |
|---|---|---|---|---|---|
| F0082-001 | Barrier returns `trusted_preflight_service_missing_or_invalid`; failing test is `test_execution_barrier_serializes_publish_and_submit`. | Test wiring or preflight contract mismatch; deeper cause UNKNOWN. | NO | ExecutionBarrier admission must require trusted current preflight. | Inspect exact barrier construction and test setup; preserve fail-closed rejection when preflight is absent/invalid. Add a positive-path test with concrete valid PreflightCheckService and fresh evidence. |
| F0082-002 | Traceback raises AttributeError for `MultiPositionTrader.db`; production constructor assigns `self.db = db or Database()`. | Likely test/object construction mismatch or lifecycle issue; not proven. | NO | Durable state / fail-closed legacy mutation. | Inspect test fixture and object initialization path; do not add a fake fallback that masks absent durable DB. Add regression for partially initialized object only if such state is supported. |
| F0082-003 | Result includes `Portfolio mutation blocked: canonical ExecutionBarrier unavailable`; source routes mutation through barrier. | Barrier dependency unavailable on tested legacy path; exact intended legacy/production wiring UNKNOWN. | Immediate mechanism YES; root cause NO | All order mutations must route through canonical barrier. | Trace construction and injected barrier for this test path; either wire canonical barrier or explicitly deprecate the path with contract-aligned tests. Never bypass the barrier. |
| F0082-004 | Assertion says expected True, got False for partial manual sell protection. | UNKNOWN: complete causal path and assertion semantics not established. | NO | Residual position protection / reconciliation. | Recover full test traceback and inspect order/fill/stop persistence; add regression asserting exchange and SQLite state after partial fill. |
| F0082-005 | Source raises `Emergency exit blocked: dedicated emergency execution contract is not implemented` and sets `RECONCILE_REQUIRED`. | Missing emergency-exit implementation is directly confirmed; whether test's desired path is safe requires a separate contract. | YES, implementation gap | Emergency exit, position state, fail-closed. | Design separate durable emergency mutation contract with stable client ID, serialized admission, exchange-result verification and restart recovery; add injected failures at submit/response/persistence. Do not remove the guard alone. |
| F0082-006 | `require_mutation_permit` marks permit consumed before network call; reuse raises `one mutation per ExecutionBarrier permit`. Test expects retry after HTTP -1021 using same permit. | Permit lifetime and timestamp-retry contract conflict. Why retry is not granted a fresh admission is a design gap; safe retry semantics not yet specified. | Immediate conflict YES; final root cause/design UNKNOWN | Exactly-once mutation intent / retry safety. | Keep one-use permit. Define whether -1021 proves no mutation; require fresh barrier admission/permit and durable intent linkage before retry. Add tests for timeout/ambiguous response to prove no retry occurs. |
| F0082-007 | `PreflightCheckService.verify_all` returns early when `max_offset_ms` is absent, recording `clock_offset_budget_unapproved`; later checks remain UNKNOWN. Test does not pass a budget. | Test fixture missing a required approved offset-budget precondition. | YES for immediate failure | Fail-closed preflight. | Configure explicit test-only approved `max_offset_ms` for tests that intend to reach later checks. Retain a separate test asserting missing budget blocks and later evidence stays UNKNOWN. |
| F0082-008 | Same early return before API restrictions reader runs. | Test fixture missing approved offset budget; withdrawal assertion is never reached. | YES for immediate failure | API permission verification. | Same as F0082-007; do not change UNKNOWN to FAIL without actually performing the permission check. |
| F0082-009 | Same early return before `_check_orders` is reached. | Test fixture missing approved offset budget; open-order assertion is never reached. | YES for immediate failure | Unknown/foreign open-order detection. | Same setup correction; assert unknown orders fail once preflight reaches the open-order check. |
| F0082-010 | Same early return before `_check_orders` is reached. | Test fixture missing approved offset budget; boolean override behavior is not exercised. | YES for immediate failure | Durable reconciliation evidence; boolean override must not satisfy reconciliation. | Same setup correction; retain explicit assertion that boolean override cannot turn known orders into reconciled PASS. |
| F0082-011 | Implementation returns `measurement_timestamp_in_future`; test expects `measurement_clock_skew`; both expect UNKNOWN status. | Test/implementation reason vocabulary disagreement; governing reason-code contract not established. | NO | Runtime freshness diagnostics. | Decide and document canonical reason code from contract; update implementation or test only after contract review, and retain UNKNOWN fail-closed status. |

## Cross-task relation discipline

- TASK 003 relation is justified for ExecutionBarrier/permit/mutation failures (F0082-001, -002, -003, -006); it does not prove those tasks' own reports or fixes.
- TASK 007 relation is justified for portfolio lifecycle and protection failures (-002 through -005).
- TASK 008 relation is justified for preflight, retry and Spot Testnet gating (-001, -003, -006 through -010).
- TASK 006 relation for freshness is limited to diagnostics/freshness semantics (-011).
- No claim is made that any related task is PASS, implemented, or released based on this CI run.

## No fixes in this task

This matrix records findings and proposed next steps only. It does not authorize changing production code, weakening assertions, or merging a fix.


## TASK 008.3 review addendum

Review is based on source and tests at the current target branch (which differs from baseline only by report/log commits according to comparison). No source correction has been made.

- F0082-001: immediate test/contract mismatch is now confirmed: the test passes legacy preflight_provider callback, while ExecutionBarrier requires a concrete PreflightCheckService bound to the exact exchange client. Keep fail-closed behavior; correct test composition only after validating a fully ready fake service.
- F0082-002: the failing fixture uses MultiPositionTrader.__new__ and omits db, while normal constructor creates/assigns Database. This confirms the fixture bypasses the constructor invariant, but does not justify adding a fake production field.
- F0082-003: the test constructs MultiPositionTrader without execution_barrier; mutation wrapper deliberately blocks when no canonical barrier exists. Composition-root wiring still needs tracing.
- F0082-004 remains UNKNOWN pending complete method/trace analysis of partial fill, OCO protection and persistence.
- F0082-005 is a confirmed implementation gap: emergency exit deliberately blocks and marks RECONCILE_REQUIRED. Keep it blocked until a separately approved contract exists.
- F0082-006 confirms a test/permit contract conflict: a one-use mutation permit is reused across attempts. Do not relax permit reuse; design a fresh admission for definitive -1021 rejection and reconcile any ambiguous outcome.
- F0082-007 through F0082-010: missing approved time-offset budget is a confirmed early-return prerequisite issue. No approved numeric budget was found; do not invent one. Tests that exercise downstream checks require an approved explicit fixture value plus separate missing-budget negatives.
- F0082-011: reason-code mismatch remains unresolved; both implementation and test preserve UNKNOWN. Canonical reason-code contract needs approval.

Detailed correction plan and per-failure ledger: ../task-008.3/CORRECTION_PLAN.md. Regression status: ../task-008.3/REGRESSION_RESULTS.md. Safety invariants: ../task-008.3/SAFETY_INVARIANT_MATRIX.md.

TASK 008.3 status: BLOCKED. No code changes, no post-correction tests, no new result SHA. Historical CI evidence is baseline-only.
