# TASK 000.3 — Test Evidence

**Source SHA assessed:** `f6dd193f9147ba49ff7f11efc81b18e10c1e2c44`  
**Local tests:** Not run; no local checkout/test environment was available.  
**Remote CI:** Logs inspected through GitHub. All jobs below checked out PR merge ref `c24e28defc38e19baf20c777a069e80b87c7aecf`, not the required head SHA. These are PR CI evidence, not exact-head proof.

## Actual remote commands and results

### Enforced Mutation Registry — run 38156886920, job 114521591759
- `python -m py_compile mutation_registry_gate.py test_mutation_registry_gate.py` — exit 0.
- `python -m pytest -q test_mutation_registry_gate.py` — **14 passed in 0.05s**, exit 0.
- `python mutation_registry_gate.py` — exit 0; `RESULT: PASS WITH BOUNDED MODEL — independent review still required`.

Inspected negative fixtures cover direct `requests.post`, helper-hidden direct mutation, alias-based mutator, alternate HTTP client, raw `Session.request`, identity-bypass direct transport, dynamic `_request`, unknown endpoint, endpoint absent from registry, evidence-free registry entry, sink moved to another module, and dynamic endpoint.

### Python backend tests — run 38156886908, job 114521592043
Exact command recorded in the workflow log:
```bash
python -m pytest -q \
  test_production_architecture.py test_android_remote_only.py test_l2_slippage.py \
  test_hypothesis_engine.py test_market_scanner.py test_wave_engine.py test_dynamic_scanner.py \
  test_portfolio_controller.py test_multi_position_risk.py test_multi_position_execution.py \
  test_portfolio_scanner.py test_portfolio_scanner_atr.py test_risk_engine.py \
  test_strategy_diagnostics.py test_db_guard.py test_db_isolation.py test_atomic_real_db.py \
  test_atomic_switch_db_failure.py test_atomic_switch_rollback.py test_buy_safety.py \
  test_williams_book_alignment.py test_book_wave_safety.py test_trade_journal.py \
  test_multi_position_lifecycle.py recovery_test.py recovery_oco_failure_test.py \
  test_chaos_hardening.py test_wave_safety_fields.py test_binance_market_data.py \
  test_binance_json_shapes.py tests/test_quant_layer.py tests/test_quant_models.py \
  test_preflight_gate.py test_execution_architecture.py test_integration_bindings.py \
  test_testnet_release_gate.py
```
Observed: **148 passed, 4 warnings in 4.94s**, exit 0.

### Williams Campaign CI — run 38156886916, job 114521645691
Command `python -m pytest -q` included at least `test_production_architecture.py`, `test_task0091_execution_barrier_fail_closed.py`, `test_task0092_atomic_execution_persistence.py`, `test_task0093_exchange_backed_recovery.py`, `test_task0094_account_identity.py`, `test_task0094_mutation_containment.py`.
Observed: **208 passed, 4 warnings in 6.86s**, exit 0.

### Android build — run 38156886910, job 114521591859
Build APK job completed successfully. It is not evidence of Python mutation-path coverage or physical-device verification.

## Mandatory objectives

| Objective | Evidence status |
|---|---|
| Registry validation | Remote CI pass on PR merge ref |
| Negative unregistered/bypass fixtures | 14 tests passed on PR merge ref |
| Production architecture enforcement | Backend/Campaign CI pass on PR merge ref |
| Identity rejection | `test_task0094_account_identity.py` asserts changed key/domain/UID, failed account request and expected fingerprint |
| Persistence failure before mutation | `test_task0091_execution_barrier_fail_closed.py` asserts submit not called when intent persistence fails |
| Ambiguous outcomes | Barrier timeout/result-persistence and recovery tests inspected |
| Duplicate submission/fill handling | Same-client-ID barrier and atomic fill-deduplication tests inspected |

These statements refer to actual remote test runs and inspected assertions. No local execution is claimed; no exact-head test pass is claimed. To close this limitation, rerun the focused commands on a checkout of the required immutable SHA and record the resulting commit/run IDs.
