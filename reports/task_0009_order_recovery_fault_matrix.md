# TASK 000.9.3 — Order-Recovery Fault-Injection Matrix
Date: 2026-10-12 UTC

## Baseline and method
- Inspected source SHA: `991b9d9e018af562af21749a58509d640dfbf87b`.
- Read-only source/test inspection: `execution_barrier.py`, `execution_recovery.py`, `tests/test_execution_barrier_persistence_fail_closed.py`, `tests/test_execution_recovery_worker.py`, and `reports/task_0005_4_fault_injection_recovery.md`.
- Configured Campaign Python suite passed on test-fix SHA `a78a3f78a8a8be80d60fcc5b4e4ddf6ccaa627d0` (run `38234529976`, attempt 2; 230 passed, 4 warnings). Existing tests are not equivalent to an executed end-to-end 12-case matrix.
- No live Binance orders, authenticated Binance mutations, or real credentials were used. This task added no fault-injection test code; the table below is an evidence inventory, not a claim that each scenario passed.

## Scenario matrix
| # | Required scenario | Existing evidence / candidate test | Durable/exchange/result assertions needed | Status |
|---:|---|---|---|---|
| 1 | Submit times out before exchange accepts | Barrier transport-exception tests simulate submit failure, but not authoritative proof of pre-acceptance | PENDING then AMBIGUOUS or safe reconciliation state; exactly one submit; no retry until authoritative lookup | **PARTIAL — not scenario-specific** |
| 2 | Submit times out after exchange accepts | Barrier ambiguous-submit behavior; no complete simulator proof that exchange accepted before response loss | Exchange order discoverable by stable client ID; local state reconciled; submit count remains one | **NOT VERIFIED end-to-end** |
| 3 | Exchange accepts; local persistence fails | Barrier post-submit persistence-failure branch and persistence mocks | Exchange mutation count one; local state becomes/retains AMBIGUOUS or RECONCILE_REQUIRED; no second mutation; new entries blocked | **PARTIAL** |
| 4 | Local intent exists; exchange status unknown | `test_not_found_or_rate_limit_remains_ambiguous_with_backoff`, `test_none_order_lookup_is_not_treated_as_proof_of_non_submission`, unexpected-payload test | Unknown stays AMBIGUOUS; retry is read-only reconciliation only; entries remain blocked | **PARTIAL — unit-level paths exist** |
| 5 | Exchange reports partial execution | `test_worker_persists_exact_exchange_lifecycle`, `test_active_order_recovery_remains_scheduled_until_terminal` | Exact executed quantity and status persist; no new submit; scheduled terminal follow-up; campaign exposure is correct | **PARTIAL — worker lifecycle covered, campaign integration incomplete** |
| 6 | Duplicate exchange events | No complete duplicate-event/idempotent-fill matrix identified | Duplicate event does not double-count fills, create second mutation, or close/reopen campaign twice | **NOT VERIFIED** |
| 7 | Restart with pending intent | SQLite lifecycle/restart tests exist, but no complete worker-restart matrix for all statuses | Reopened DB recovers exact intent and client ID; one lookup; no blind submit | **PARTIAL** |
| 8 | Local order missing, exchange order exists | No end-to-end orphan-exchange-order discovery test established | Exchange order found by authoritative enumeration; adopted/quarantined safely; no duplicate mutation | **NOT VERIFIED** |
| 9 | Local order exists, exchange order not found | Not-found lookup test keeps the intent ambiguous; absence is not treated as proof of non-submission | Durable unresolved status and backoff persist; no resubmit; new entries blocked | **PARTIAL** |
| 10 | Exit request result ambiguous | `test_cancel_intent_stays_unresolved_while_exchange_order_is_active` and barrier ambiguous-submit path | Exit/cancel result reconciled; no duplicate exit; protection-gap policy explicit; entry gate remains closed while unresolved | **PARTIAL** |
| 11 | Recovery fails during reconciliation | `test_database_read_failure_aborts_recovery_before_exchange_lookup`, `test_failure_to_persist_reconciliation_does_not_claim_success`, trade-history failure tests | No false success; durable AMBIGUOUS state/backoff; no mutation; entries blocked | **PARTIAL — selected faults covered** |
| 12 | Retry attempted while previous operation unresolved | Barrier unresolved-intent and duplicate-claim gates; existing persistence regression | second submit callback count stays zero; reconciliation is mandatory; new entries remain blocked | **PARTIAL — needs explicit scenario-wide assertion** |

## Mandatory per-case result fields not yet captured
For every row, add an executable test name and actual run result, plus initial durable state, exchange state, fault point, resulting local state, mutation count, reconciliation-required flag, new-entry gate result, and final recovered state. These fields are not all represented by the existing tests, so the acceptance criterion is not met.

## Conclusion
**OPEN — EVIDENCE INCOMPLETE.** Existing tests support fail-closed design in selected paths, but the full 12-scenario deterministic mock-exchange matrix has not been implemented and run. Required engineering owner action: add explicit assertions for all 12 scenarios, include them in the configured CI commands, run the targeted tests and complete Python/Campaign suites, and retain raw results tied to the exact tested SHA.
