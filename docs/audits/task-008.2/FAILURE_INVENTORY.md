# Failure inventory — TASK 008.2

Source SHA: `1a7e7209c99152c3bf29f3e2f07e7db088d9243b`  
Run: [GitHub Actions 38129367877](https://github.com/19wolfalone92/Williams/actions/runs/38129367877)  
Environment: see `ENVIRONMENT_MANIFEST.md`. Full tracebacks: `baseline-logs/python-job-114440299305.log`.

| ID | Test ID | Expected | Actual | Severity | Related task | Status |
|---|---|---|---|---|---|---|
| F0082-001 | `test_production_architecture.py::test_execution_barrier_serializes_publish_and_submit` | admitted execution under valid test preflight | `accepted=False`, reason `trusted_preflight_service_missing_or_invalid` | P0 | TASK 003 / TASK 008 | Reproduced; root cause UNKNOWN |
| F0082-002 | `test_execution_barrier_fail_closed.py::test_legacy_portfolio_mutation_wrapper_requires_barrier_and_blocks_submit` | test assertion path completes without missing-attribute error | `AttributeError: MultiPositionTrader has no attribute db` | P1 | TASK 003 / TASK 007 | Reproduced; root cause UNKNOWN |
| F0082-003 | `test_multi_position_execution.py::test_multi_position_execution_creates_independent_trades` | two independent trade results | one result; `canonical ExecutionBarrier unavailable` | P0 | TASK 003 / TASK 007 | Reproduced; immediate block confirmed, root cause UNKNOWN |
| F0082-004 | `test_multi_position_lifecycle.py::test_partial_manual_sell_keeps_residual_position_protected` | residual position remains protected | assertion received False instead of True | P0 | TASK 003 / TASK 007 | Reproduced; root cause UNKNOWN |
| F0082-005 | `test_multi_position_lifecycle.py::test_partial_emergency_sell_never_closes_trade_as_flat` | emergency partial exit preserves non-flat lifecycle state | RuntimeError: dedicated emergency execution contract not implemented | P0 | TASK 003 / TASK 007 | Reproduced; implementation gap confirmed |
| F0082-006 | `test_binance_json_shapes.py::test_signed_post_retries_once_only_for_timestamp_rejection` | retry after -1021 timestamp rejection returns success; two calls | second call blocked: one mutation per permit | P0 | TASK 003 / TASK 008 | Reproduced; permit/retry contract conflict confirmed |
| F0082-007 | `test_preflight_gate.py::test_testnet_preflight_keeps_unknown_runtime_proofs_blocked` | `api_restrictions=PASS`, while overall readiness false | `api_restrictions=UNKNOWN` | P1 | TASK 008 | Reproduced; test setup lacks approved time-offset budget |
| F0082-008 | `test_preflight_gate.py::test_testnet_preflight_blocks_withdrawals` | `api_restrictions=FAIL` | `api_restrictions=UNKNOWN` | P1 | TASK 008 | Reproduced; test setup lacks approved time-offset budget |
| F0082-009 | `test_preflight_gate.py::test_testnet_preflight_blocks_foreign_order` | `open_orders=FAIL` | `open_orders=UNKNOWN` | P1 | TASK 008 | Reproduced; test setup lacks approved time-offset budget |
| F0082-010 | `test_preflight_gate.py::test_known_orders_cannot_be_marked_reconciled_by_boolean_override` | `open_orders=FAIL` | `open_orders=UNKNOWN` | P1 | TASK 008 | Reproduced; test setup lacks approved time-offset budget |
| F0082-011 | `test_runtime_freshness.py::test_future_timestamp_remains_unknown` | reason `measurement_clock_skew` | reason `measurement_timestamp_in_future`; status UNKNOWN | P2 | TASK 006 / TASK 008 | Reproduced; expected-reason contract unresolved |

## Important interpretation

- These are the 11 failures from the exact-SHA CI run, not a claim that a new independent local run occurred.
- F0082-007 through F0082-010 share a confirmed early-return path: without configured `max_offset_ms`, preflight leaves the time check UNKNOWN and returns before API restrictions/open-order checks.
- Failure status means the assertion/test did not pass; it does not automatically establish that the implementation contract is wrong. In particular, fail-closed behavior must not be weakened to satisfy a test.
- All unproven underlying causes remain UNKNOWN. See `FAILURE_ROOT_CAUSE_MATRIX.md`.
